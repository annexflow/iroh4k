plugins {
    id("tech.annexflow.iroh4k.multiplatform.lib")
    id("tech.annexflow.iroh4k.rust.jni")
}

kotlin {
    // Shared JNI implementation for the JVM and Android targets. Both consume the same Rust
    // shared library through the same `Iroh4kJni` symbols; only the native-library loader
    // differs per platform.
    //
    // `jvmMain` only exists when the `jvm` target is enabled — it is absent for native-only
    // target subsets such as `-Ptargets=linuxX64` — so it is wired only when registered.
    // Guarding on the target (rather than probing the source set) avoids spuriously creating it.
    val jniMain = sourceSets.create("jniMain") {
        dependsOn(sourceSets.getByName("commonMain"))
    }
    val jniTest = sourceSets.create("jniTest") {
        dependsOn(sourceSets.getByName("commonTest"))
    }
    if (targets.findByName("jvm") != null) {
        sourceSets.getByName("jvmMain").dependsOn(jniMain)
        sourceSets.getByName("jvmTest").dependsOn(jniTest)
    }

    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        getByName("commonMain").dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("commonTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.assertk)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

rustJni {
    crateName = "iroh4k"
}
