import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

/**
 * The echo example: a runnable binary on the host's native target and a runnable JVM main.
 *
 * ## Why this is a plain build file and not a convention plugin
 *
 * `build-logic` exists to share configuration, and there is nothing here to share with: this is the
 * only module in the repository that produces an executable, and the library's own
 * `multiplatform.lib` plugin is about building and linking a Rust crate, which a consumer of iroh4k
 * never does. A convention plugin would also hide the two lines a reader most wants to see —
 * `implementation(project(":iroh4k"))` and `binaries { executable { … } }` — behind an id they would
 * have to go and read. An example whose build is itself readable is worth more than one fewer
 * duplicated block, so this file stays self-contained and looks like what a real consumer writes.
 *
 * The one thing it does copy from `build-logic` is the `-Ptargets` convention, because it has to:
 * the example resolves `:iroh4k` per target, so it must not ask for a target the library was not
 * built for. `Utils.targetsOf` is not on this script's classpath (nothing here applies a
 * `build-logic` plugin), so the selection is repeated below in the few lines it takes.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/**
 * The Kotlin/Native target matching the build host — the mirror of `Utils.defaultTarget`.
 *
 * A host that is not one of iroh4k's native targets simply gets no executable: the name falls
 * through the `when` in `kotlin { }` below and only the JVM main is built. That is friendlier than
 * failing the configuration of an example.
 */
val hostTarget: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm64 = arch == "aarch64" || arch == "arm64"
    when {
        os.contains("mac") || os.contains("darwin") -> if (isArm64) "macosArm64" else "macosX64"
        os.contains("windows") -> "mingwX64"
        else -> if (isArm64) "linuxArm64" else "linuxX64"
    }
}

/**
 * The targets to build, from the same `targets` property the library reads.
 *
 * `all` is narrowed to the ones an executable makes sense on: iOS and Android native produce
 * binaries nothing here can launch, so the example would only slow a full build down without
 * demonstrating anything.
 */
val selected: List<String> = when (val property = providers.gradleProperty("targets").orNull) {
    null -> listOf("jvm", hostTarget)
    "all" -> listOf("jvm", "macosArm64", "linuxArm64", "linuxX64", "mingwX64")
    else -> property.split(",").map { it.trim() }
}

kotlin {
    // The entry point is `main` in the example's own package rather than the root one, so
    // Kotlin/Native has to be told where it is. The JVM finds it from the class name below.
    val entry = "tech.annexflow.iroh4k.examples.echo.main"

    if ("jvm" in selected) {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        jvm {
            mainRun {
                mainClass.set("tech.annexflow.iroh4k.examples.echo.EchoKt")
            }
        }
    }

    for (target in selected) {
        when (target) {
            "macosArm64" -> macosArm64 { binaries { executable { entryPoint = entry } } }
            "linuxArm64" -> linuxArm64 { binaries { executable { entryPoint = entry } } }
            "linuxX64" -> linuxX64 { binaries { executable { entryPoint = entry } } }
            "mingwX64" -> mingwX64 { binaries { executable { entryPoint = entry } } }
        }
    }

    sourceSets {
        getByName("commonMain").dependencies {
            implementation(project(":iroh4k"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
