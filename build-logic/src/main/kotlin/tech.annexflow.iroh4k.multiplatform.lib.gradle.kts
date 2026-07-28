import java.lang.System.getenv
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.gradle.nativeplatform.platform.internal.DefaultOperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import tech.annexflow.iroh4k.multiplatform.Utils

val os: DefaultOperatingSystem = DefaultNativePlatform.getCurrentOperatingSystem()

val exeExt: String = when {
    os.isWindows -> ".exe"
    else -> ""
}

/** Path to the Rust cargo binary used to build the native library. */
val cargo: String = when {
    os.isWindows -> getenv("USERPROFILE")
    else -> getenv("HOME")
}?.let(::File)
    ?.resolve(".cargo/bin/cargo$exeExt")?.absolutePath
    ?: throw GradleException("Rust cargo binary is required to build project but it wasn't found.")

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

extensions.configure<KotlinMultiplatformExtension> {
    // Pins the compiler itself, so the build does not depend on whichever JDK a contributor happens
    // to have installed. 17 rather than the 11 this library targets, because a toolchain moves the
    // JDK *everything* runs on, tests included, and Robolectric refuses to create a sandbox for
    // Android SDK 34 on anything below 17 — a toolchain of 11 costs the whole `androidHostTest`
    // suite. The gap between compiling on 17 and promising 11 is closed by `-Xjdk-release=11`
    // below, not by the toolchain.
    jvmToolchain(17)

    val targets = Utils.targetsOf(project)
    val availableTargets = mapOf(
        Pair("jvm") {
            jvm {
                compilerOptions {
                    // Java 11 because nothing here needs more, and every version above it is a
                    // consumer this library would turn away for no gain. The JVM and JNI code
                    // reaches for `java.io.File`, `AtomicBoolean` and a class loader — the newest
                    // of those is Java 5 — while everything genuinely modern in the binding is
                    // Kotlin's own: coroutines, `kotlin.concurrent.atomics`, the codec.
                    //
                    jvmTarget.set(JvmTarget.JVM_11)

                    // `jvmTarget` alone only lowers the class-file version; the compiler still sees
                    // every API of whatever JDK the build runs on, so a call to a Java 12+ method
                    // would compile here and fail at run time on the Java 11 a consumer was
                    // promised. This is javac's `--release`: it restricts the visible API surface
                    // to 11 as well, which is the half that actually keeps the promise.
                    //
                    // A `jvmToolchain(11)` would do the same and pin the compiler itself, but it
                    // pins the *whole* build: Robolectric refuses to create a sandbox for Android
                    // SDK 34 on anything below Java 17, so a toolchain of 11 silently costs the
                    // entire `androidHostTest` suite. This flag restricts the API without moving
                    // the JDK anything runs on, so those tests keep working.
                    freeCompilerArgs.add("-Xjdk-release=11")
                }
            }
        },
        Pair("iosArm64") { iosArm64 { rust("aarch64-apple-ios") } },
        Pair("iosSimulatorArm64") { iosSimulatorArm64 { rust("aarch64-apple-ios-sim") } },
        Pair("androidNativeArm64") { androidNativeArm64 { rust("aarch64-linux-android") } },
        Pair("androidNativeX64") { androidNativeX64 { rust("x86_64-linux-android") } },
        Pair("macosArm64") { macosArm64 { rust("aarch64-apple-darwin") } },
        Pair("linuxArm64") { linuxArm64 { rust("aarch64-unknown-linux-gnu") } },
        Pair("linuxX64") { linuxX64 { rust("x86_64-unknown-linux-gnu") } },
        Pair("mingwX64") { mingwX64 { rust("x86_64-pc-windows-gnu") } },
    )

    targets.forEach {
        logger.lifecycle("Enabling target $it")
        availableTargets[it]?.invoke()
    }

    applyDefaultHierarchyTemplate()
}

/**
 * Builds the Rust crate as a static library for [target] and links it into this Kotlin/Native
 * target through cinterop.
 *
 * @param target the Rust target triple, e.g. `aarch64-apple-darwin`.
 */
fun KotlinNativeTarget.rust(target: String) {
    val tasks = project.tasks
    // Read once, at configuration time: capturing `project` inside `onlyIf` would break the
    // configuration cache, which this build has enabled.
    val prebuilt = Utils.rustPrebuilt(project)
    fun file(path: String) = project.projectDir.resolve(path)

    compilations["main"].cinterops {
        create("ffi") {
            // mingw needs unqualified `libraryPaths`/`linkerOpts`, so it gets its own def file.
            val defFile = if (target == "x86_64-pc-windows-gnu") {
                file("src/nativeInterop/cinterop/iroh4k-mingwX64.def")
            } else {
                file("src/nativeInterop/cinterop/iroh4k.def")
            }
            definitionFile.set(defFile)

            // Rust sources whose changes should trigger a rebuild — everything under src/rust
            // except the cargo `target/` output directory (to avoid input/output overlap).
            val rustSources = project.fileTree(file("src/rust")) {
                include("Cargo.toml", "Cargo.lock", "build.rs", "cbindgen.toml")
                include("src/**")
            }
            // The static library cinterop links, read from the def's `staticLibraries` entry.
            // Declaring it as the task output lets Gradle mark the task UP-TO-DATE and skip
            // cargo when the sources are unchanged, instead of re-running it on every build.
            val staticLib = defFile.readLines()
                .firstOrNull { it.trimStart().startsWith("staticLibraries") }
                ?.substringAfter('=')?.trim()
                ?: error("No `staticLibraries` entry in ${defFile.name}")

            val cargoTask = tasks.register<Exec>("cargo-$target") {
                group = "rust"
                description = "Builds the Rust crate's static library ($staticLib) for $target, " +
                        "linked by the Kotlin/Native cinterop."
                inputs.files(rustSources).withPathSensitivity(PathSensitivity.RELATIVE)
                outputs.file(file("src/rust/target/$target/release/$staticLib"))
                commandLine(
                    cargo,
                    "build",
                    "--manifest-path", file("src/rust/Cargo.toml").absolutePath,
                    "--target=$target",
                    "--release"
                )
                onlyIf { !prebuilt }
            }
            tasks.getByName(interopProcessingTaskName) { dependsOn(cargoTask) }
        }
    }
}
