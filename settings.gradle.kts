rootProject.name = "iroh4k"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    includeBuild("build-logic")
}

include("iroh4k")
include("examples:echo")
