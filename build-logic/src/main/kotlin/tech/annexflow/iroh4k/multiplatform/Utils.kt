package tech.annexflow.iroh4k.multiplatform

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

object Utils {
    private val os = DefaultNativePlatform.getCurrentOperatingSystem()
    private val arch = DefaultNativePlatform.getCurrentArchitecture()

    private val osString = when {
        os.isLinux -> "linux"
        os.isMacOsX -> "macos"
        os.isWindows -> "mingw"
        else -> throw GradleException("Unsupported operating system: $os")
    }

    private val archString = when {
        arch.isArm64 -> "Arm64"
        arch.isAmd64 -> "X64"
        else -> throw GradleException("Unsupported architecture: $arch")
    }

    /** The Kotlin target matching the build host, e.g. `macosArm64`. */
    private val defaultTarget = "$osString$archString"

    /**
     * Every target iroh4k can be built for.
     *
     * `android` is the odd one out: it is the Android *library* (AAR) target, added by the
     * Android Gradle plugin rather than by `multiplatform.lib`'s target map, so the library
     * module reads this token itself and nothing here acts on it. It is listed because `all` has
     * to mean all — a publish runs with `-Ptargets=all`, and leaving the token out would drop the
     * AAR from Maven Central without any build failing. It is distinct from `androidNativeArm64`
     * and `androidNativeX64`, which are Kotlin/Native targets using the cinterop path.
     */
    val allTargets = listOf(
        "jvm",
        "android",
        "iosArm64",
        "iosSimulatorArm64",
        "androidNativeX64",
        "androidNativeArm64",
        "macosArm64",
        "linuxArm64",
        "linuxX64",
        "mingwX64",
    )

    /**
     * The targets to enable, from the `targets` Gradle property:
     *
     *  - `-Ptargets=all` — every supported target (needs the full cross toolchain).
     *  - `-Ptargets=macosArm64,linuxX64` — an explicit subset.
     *  - `-Ptargets=jvm,android` — the AAR, which needs an Android SDK, an NDK and `cargo ndk`
     *    but no Kotlin/Native Android toolchain.
     *  - absent — the JVM plus the build host only, so local development needs no cross
     *    toolchain.
     */
    fun targetsOf(project: Project): List<String> =
        (project.properties["targets"] as? String)?.let {
            when (it) {
                "all" -> allTargets
                else -> it.split(",").map { t -> t.trim() }
            }
        } ?: listOf("jvm", defaultTarget)
}
