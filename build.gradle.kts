group = "tech.annexflow.iroh4k"
// The release workflow passes `-Piroh4kVersion=<tag without its leading v>`; everything else — a
// local build, a snapshot publish, CI — takes the default. A distinct property name rather than
// `-Pversion`, which collides with Gradle's own `project.version` handling and is easy to set by
// accident.
version = providers.gradleProperty("iroh4kVersion").getOrElse("0.2.0-SNAPSHOT")

// Declared here, applied by the convention plugins in `build-logic`. `apply false` puts each on
// the build classpath without applying it to the root project, which is what lets a convention
// plugin reference it by id.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.publish) apply false
    alias(libs.plugins.dokka) apply false
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
