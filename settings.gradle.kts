pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    includeBuild("build-logic")
}

// Lets Gradle fetch the JDK the `jvmToolchain` in `build-logic` asks for rather than failing on a
// machine that does not happen to have it. Without this the toolchain is a hand-installation
// requirement for every contributor and every CI runner; with it, the cost is one download.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "iroh4k"

include("iroh4k")
include("examples:echo")
