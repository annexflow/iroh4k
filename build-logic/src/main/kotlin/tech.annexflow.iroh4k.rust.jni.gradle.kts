import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import tech.annexflow.iroh4k.multiplatform.Utils
import tech.annexflow.iroh4k.rust.RustJniExtension

/**
 * Builds the Rust crate as a shared library for the JNI targets (JVM host + Android) and wires
 * the outputs into the build.
 *
 *  - **Android**: `cargo ndk` builds the per-ABI `.so` into `src/androidMain/jniLibs` (packaged
 *    into the AAR; requires the Android NDK + `cargo ndk`). Wired before the JNI-merge tasks.
 *    Selected by the `android` token — the AAR target — or by an `androidNative*` Kotlin/Native
 *    target; the NDK is located from the environment or `local.properties`.
 *  - **JVM host**: `cargo build` builds the host `dylib`/`so`/`dll`, copies it into the JVM
 *    resources (so it can be extracted from the classpath at runtime), and exposes its
 *    directory to `jvmTest` via the `<crate>.native.path` system property, which the runtime
 *    loader reads to `System.load` the host library.
 *
 * Kotlin/Native (FFI/cinterop) targets are built and linked separately by the
 * `tech.annexflow.iroh4k.multiplatform.lib` plugin (the `rust(...)` helper).
 *
 * ```kotlin
 * plugins { id("tech.annexflow.iroh4k.rust.jni") }
 * rustJni { crateName = "iroh4k" }
 * ```
 */
val rustJni = extensions.create("rustJni", RustJniExtension::class.java)

// Resolved lazily in afterEvaluate so the consuming module's `rustJni { }` values are visible.
afterEvaluate {
    val crateName = rustJni.crateName.ifEmpty {
        error("rustJni.crateName must be set in $path")
    }
    val rustDir = layout.projectDirectory.dir(rustJni.cargoDir)
    val jniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")
    val generatedResources = layout.buildDirectory.dir("generated/resources/jvmAndroid")
    val cargo: String = file("${System.getProperty("user.home")}/.cargo/bin/cargo")
        .takeIf { it.exists() }?.absolutePath ?: "cargo"

    // Files whose changes should trigger a Rust rebuild. Excludes `target/` (the cargo output
    // directory, inside rustDir) to avoid input/output overlap errors in Gradle.
    val rustSources = fileTree(rustDir) {
        include("Cargo.toml", "Cargo.lock", "build.rs", "cbindgen.toml")
        include("src/**")
    }

    // The JNI fan-out follows the selected Kotlin targets (`-Ptargets`, via Utils.targetsOf):
    // the default (build host only) stays light and needs no Android/cross toolchains, while
    // `-Ptargets=all` produces the full Android + multi-host JVM build.
    val selectedTargets = Utils.targetsOf(project)

    /** A value from the root `local.properties`, or null when the file or the key is absent. */
    fun localProperty(key: String): String? = rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
        ?.getProperty(key)

    // Where the NDK might be, most explicit first: an exported variable, then an explicit
    // `ndk.dir`, then the versioned directories Android Studio installs under the SDK. The first
    // candidate that actually looks like an NDK wins, so a stale ANDROID_NDK_HOME pointing at a
    // deleted directory falls through to the SDK rather than failing the build.
    val ndkHome: File? = sequence {
        listOfNotNull(
            System.getenv("ANDROID_NDK_HOME"),
            System.getenv("ANDROID_NDK_ROOT"),
            localProperty("ndk.dir"),
        ).forEach { yield(File(it)) }

        listOfNotNull(
            localProperty("sdk.dir"),
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
        ).forEach { sdk ->
            File(sdk, "ndk").listFiles().orEmpty()
                .filter { it.isDirectory }
                // Newest first. NDK directories are named `<major>.<minor>.<build>`, so the major
                // is compared as a number — a plain string sort would rank `9.x` above `30.x`.
                .sortedByDescending { it.name.substringBefore('.').toIntOrNull() ?: 0 }
                .forEach { yield(it) }
        }
    }.firstOrNull { File(it, "toolchains/llvm/prebuilt").isDirectory }

    // ── Android (cargo-ndk → jniLibs) ───────────────────────────────────────────────────────
    // ABIs come from two independent places, because the AAR and the Kotlin/Native Android
    // targets are independent things:
    //
    //  - `android` is the AAR target. It ships both 64-bit ABIs — the JNI facade passes handles
    //    as `jlong` and there is no 32-bit story — so selecting it asks for both, with no
    //    Kotlin/Native Android toolchain and no `*-linux-android` Rust target involved.
    //  - `androidNativeArm64`/`androidNativeX64` are Kotlin/Native targets. They go through
    //    cinterop and a static library, not through this task at all, but a build that asks for
    //    one is a build that wants that ABI, so it gets the `.so` too.
    //
    // When neither is selected the task is neither created nor wired, so no Android NDK and no
    // cargo-ndk are needed — which is what keeps `-Ptargets=jvm` or a plain host build light.
    val aarAbis = if ("android" in selectedTargets) listOf("arm64-v8a", "x86_64") else emptyList()
    val nativeTargetAbis = selectedTargets.mapNotNull {
        when (it) {
            "androidNativeArm64" -> "arm64-v8a"
            "androidNativeX64" -> "x86_64"
            else -> null
        }
    }
    val androidAbis = (aarAbis + nativeTargetAbis).distinct()
    if (androidAbis.isNotEmpty()) {
        val ndkCommand = buildList {
            add(cargo); add("ndk")
            androidAbis.forEach { add("-t"); add(it) }
            add("-o"); add(jniLibsDir.asFile.absolutePath)
            // Pinned rather than left to cargo-ndk's default, for the reason spelled out in
            // scripts/config.toml: Kotlin/Native links its Android output against an API 21
            // sysroot, and the two sides have to agree. A library built for 21 loads fine on the
            // AAR's higher minSdk, so one number serves both facades.
            add("--platform"); add("21")
            add("build"); add("--release")
        }
        val buildRustAndroid = tasks.register<Exec>("buildRustAndroid") {
            group = "rust"
            description = "Builds the Rust crate's Android JNI libraries (.so per ABI) via " +
                    "cargo-ndk into src/androidMain/jniLibs."
            workingDir(rustDir)
            inputs.files(rustSources).withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.dir(jniLibsDir)
            commandLine(*ndkCommand.toTypedArray())

            // cargo-ndk locates the NDK through the environment only, and a machine that builds
            // Android from Gradle does not necessarily export anything: Android Studio writes
            // `sdk.dir` into local.properties and leaves it at that. Passing the resolved path to
            // the task keeps the build working from a bare `./gradlew` with nothing exported, and
            // keeps it off the daemon's ambient environment either way.
            ndkHome?.let { environment("ANDROID_NDK_HOME", it.absolutePath) }
            doFirst {
                if (ndkHome == null) {
                    error(
                        "No Android NDK found. Set ANDROID_NDK_HOME (or ANDROID_NDK_ROOT), or " +
                                "put ndk.dir — or an sdk.dir with an ndk/<version> under it — in " +
                                "local.properties."
                    )
                }
            }
        }
        // Wire before any task that merges JNI libraries into the AAR.
        tasks.matching {
            it.name.contains("JniLib", ignoreCase = true) ||
                    it.name.contains("MergeJni", ignoreCase = true)
        }.configureEach { dependsOn(buildRustAndroid) }
    }

    // ── JVM / desktop hosts (cargo → JVM resources) ──────────────────────────────────────────
    // Detect the build host; it is always bundled into the JVM jar (so jvmTest can load it).
    val hostOs = System.getProperty("os.name").lowercase()
    val hostArch = System.getProperty("os.arch").lowercase()
    val isMacHost = hostOs.contains("mac") || hostOs.contains("darwin")
    val isLinuxHost = hostOs.contains("linux")
    val isWindowsHost = hostOs.contains("windows")
    val isArm64Host = hostArch == "aarch64" || hostArch == "arm64"
    val hostTarget = when {
        isMacHost && isArm64Host -> "aarch64-apple-darwin"
        isMacHost -> "x86_64-apple-darwin"
        isLinuxHost && isArm64Host -> "aarch64-unknown-linux-gnu"
        isLinuxHost -> "x86_64-unknown-linux-gnu"
        isWindowsHost && isArm64Host -> "aarch64-pc-windows-msvc"
        isWindowsHost -> "x86_64-pc-windows-msvc"
        else -> error("Unsupported host for rustJni: os=$hostOs arch=$hostArch")
    }

    // For a Rust target triple, returns (cargo output lib path, classpath resource name). The
    // resource name must match the runtime loader's `hostNativeLibName()`: Unix prefixes with
    // `lib`, Windows doesn't; the extension is OS-specific; arm64 variants carry an `_aarch64`
    // suffix so one fat jar can hold every desktop host's library without collision.
    fun jvmLibInfo(triple: String): Pair<String, String> {
        val isWindows = triple.contains("windows")
        val isApple = triple.contains("apple")
        val isArm64 = triple.startsWith("aarch64") || triple.startsWith("arm64")
        val prefix = if (isWindows) "" else "lib"
        val ext = when {
            isApple -> "dylib"
            isWindows -> "dll"
            else -> "so"
        }
        val suffix = if (isArm64) "_aarch64" else ""
        val libFile = "target/$triple/release/$prefix$crateName.$ext"
        val resourceName = "$prefix$crateName$suffix.$ext"
        return libFile to resourceName
    }

    // Desktop hosts bundled into the JVM jar, derived from the selected native targets, plus the
    // build host (always — so jvmTest can load a matching library).
    val jvmTargets = (selectedTargets.mapNotNull {
        when (it) {
            "macosArm64" -> "aarch64-apple-darwin"
            "linuxArm64" -> "aarch64-unknown-linux-gnu"
            "linuxX64" -> "x86_64-unknown-linux-gnu"
            "mingwX64" -> "x86_64-pc-windows-gnu"
            else -> null
        }
    } + hostTarget).distinct()

    val buildJvmTasks = jvmTargets.map { triple ->
        val (libFile, _) = jvmLibInfo(triple)
        tasks.register<Exec>("buildRustJvm_$triple") {
            group = "rust"
            description = "Builds the Rust crate's JVM/desktop shared library for $triple."
            workingDir(rustDir)
            inputs.files(rustSources).withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.file(rustDir.file(libFile))
            commandLine(cargo, "build", "--release", "--target", triple)
        }
    }

    // `Sync` (not `Copy`) so the resource dir mirrors exactly the currently-selected targets: a
    // library for a target that is no longer built is removed instead of lingering in the jar.
    val copyNativeLibJvm = tasks.register<Sync>("copyNativeLibJvm") {
        group = "rust"
        description = "Syncs the built JVM/desktop native libraries into the JVM resources " +
                "(jar packaging + host tests)."
        into(generatedResources)
        dependsOn(buildJvmTasks)
        jvmTargets.forEach { triple ->
            val (libFile, resourceName) = jvmLibInfo(triple)
            from(rustDir.file(libFile)) { rename(".+", resourceName) }
        }
    }

    // Package the host shared library into the JVM jar (extracted from the classpath at
    // runtime). Guard on the jvm *target* so we don't spuriously create `jvmMain` for
    // native-only builds.
    val kmp = extensions.findByType(KotlinMultiplatformExtension::class.java)
    if (kmp != null && kmp.targets.findByName("jvm") != null) {
        kmp.sourceSets.getByName("jvmMain").resources.srcDir(generatedResources)
    }

    tasks.matching {
        it.name.startsWith("process") &&
                (it.name.endsWith("Resources") || it.name.endsWith("JavaRes"))
    }.configureEach { dependsOn(copyNativeLibJvm) }
    tasks.matching { it.name == "jvmProcessResources" }.configureEach { dependsOn(copyNativeLibJvm) }

    // jvmTest and the Android host (Robolectric) tests run on the host JVM; both load the host
    // shared library from this directory via the `<crate>.native.path` system property.
    val nativePathProperty = "$crateName.native.path"
    tasks.withType<Test>().configureEach {
        if (name == "jvmTest" || name.contains("HostTest", ignoreCase = true)) {
            dependsOn(copyNativeLibJvm)
            systemProperty(nativePathProperty, generatedResources.get().asFile.absolutePath)
        }
    }
}
