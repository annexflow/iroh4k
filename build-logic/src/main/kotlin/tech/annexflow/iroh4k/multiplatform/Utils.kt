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

    /** Every target iroh4k can be built for. */
    val allTargets = listOf(
        "jvm",
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
