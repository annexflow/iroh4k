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
    val targets = Utils.targetsOf(project)
    val availableTargets = mapOf(
        Pair("jvm") {
            jvm {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
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
