group = "tech.annexflow.iroh4k"
version = "0.1.0-SNAPSHOT"

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

repositories {
    google()
    mavenCentral()
}

subprojects {
    group = rootProject.group
    version = rootProject.version
    repositories {
        google()
        mavenCentral()
    }
}
