import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import tech.annexflow.iroh4k.multiplatform.Utils

plugins {
    id("tech.annexflow.iroh4k.multiplatform.lib")
    id("tech.annexflow.iroh4k.rust.jni")
    id("tech.annexflow.iroh4k.publish")
    id("tech.annexflow.iroh4k.dokka")
    // Resolved onto this script's classpath but applied below only when Android is selected —
    // see the `androidEnabled` comment. Applying it eagerly would make every build of this
    // module require an Android SDK, including `-Ptargets=linuxX64` on a machine that has none.
    alias(libs.plugins.android) apply false
}

/**
 * Whether to build the Android library (AAR) target.
 *
 * ## Why the target lives in this build file and not in `multiplatform.lib`
 *
 * `build-logic` is where configuration shared by more than one module goes, and Android is not
 * shared: `:iroh4k` is the only module that publishes a library, and the echo example deliberately
 * has no Android artifact. Moving it into the convention plugin would also mean putting the
 * Android Gradle plugin on `build-logic`'s own compile classpath — a change to
 * `build-logic/build.gradle.kts` — to buy nothing but a longer path from `-Ptargets` to the
 * `android { }` block. The two module-specific pieces below (`namespace`, and the JNI source-set
 * wiring) would have to travel there with it.
 *
 * ## When Android is enabled
 *
 * It follows `-Ptargets` like every other target, so the toolchain a build needs stays a function
 * of what it was asked to build:
 *
 *  - `-Ptargets=jvm,android` — the AAR without any Kotlin/Native Android target, which is what
 *    Phase 1 can build: no NDK, no `*-linux-android` Rust targets.
 *  - `-Ptargets=all` — implied by `androidNativeArm64`/`androidNativeX64`, so a publish (which
 *    has to run with `all`) ships the AAR rather than silently dropping the platform.
 *  - the default (`jvm` plus the build host), `-Ptargets=jvm`, `-Ptargets=macosArm64` — off, and
 *    no Android SDK is needed to configure the build.
 *
 * `Utils.allTargets` has no `android` entry, so `android` is a token this module understands and
 * `Utils` does not; `multiplatform.lib` ignores it, being absent from its target map. That is the
 * seam this file works within — `Utils` belongs to `build-logic`.
 */
val androidEnabled: Boolean = Utils.targetsOf(project).let { targets ->
    "android" in targets || targets.any { it.startsWith("androidNative") }
}

// Applied by id rather than through the `plugins { }` block above, because a plugin listed there
// is applied unconditionally and this one has to be conditional. The cost is that the generated
// `kotlin { android { } }` accessor does not exist, so the target — which the plugin registers on
// the Kotlin extension under the name `android` — is configured by type below instead.
if (androidEnabled) apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    if (androidEnabled) {
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "tech.annexflow.iroh4k"

            // The newest platform installed on this machine. `compileSdk` only bounds which APIs
            // this module may compile against — it is not a floor for consumers — and the module
            // calls almost nothing from the framework, so tracking the newest costs nothing.
            compileSdk = 36

            // API 26 (Android 8.0). Three things set this floor:
            //
            //  - The JNI facade passes every native handle as a `jlong`, and there is no 32-bit
            //    ABI story anywhere in the project (`buildRustAndroid` builds `arm64-v8a` and
            //    `x86_64` only), so the AAR is 64-bit-only. Both 64-bit ABIs arrived with API 21.
            //  - Rust's `std` supports API 21 upwards, and Kotlin/Native links its Android output
            //    against NDK r21 (see `scripts/config.toml`), so 21 is a floor the toolchains set
            //    rather than one this project chooses.
            //  - 26 sits above that floor because it is where uncompressed, directly mapped
            //    native libraries in the APK became the norm — the `extractNativeLibs` handling
            //    below it is legacy weight for an artifact that ships little but a `.so` — and
            //    because the java.time and NIO APIs the shared JNI code uses are present without
            //    desugaring.
            //
            // For consumers this is the usual trade: an app with a lower `minSdk` cannot depend
            // on iroh4k without raising its own. API 26 covers effectively the whole active
            // device population, so the cost is small, and the alternative — a 32-bit ABI plus a
            // handle representation that survives it — is a much larger one.
            minSdk = 26

            // Host-side (Robolectric) tests, compiled from `src/androidHostTest`. Its source-set
            // tree is `test`, so `commonTest` is already part of the compilation and the shared
            // `Common*Tests` runners those tests delegate to come from there.
            withHostTestBuilder {}

            packaging {
                // `cargo ndk` copies *every* `.so` cargo produced for the ABI into `jniLibs`, and
                // several of iroh4k's dependencies (`iroh`, `iroh-relay`, `irpc`, `irpc-iroh`)
                // declare a `cdylib` crate type of their own. Those copies are dead weight: this
                // crate is a `cdylib` that links its dependencies statically, so
                // `libiroh4k.so`'s only `NEEDED` entries are `libc`/`libm`/`libdl`. Shipping them
                // would add roughly 1.2 MB per ABI to every consumer's APK under names that carry
                // a cargo metadata hash and therefore change between builds.
                //
                // The pattern keys on exactly that hash: cargo names a dependency's shared object
                // `lib<crate>-<hash>.so`, while the crate's own output is plain `libiroh4k.so`.
                // If a real shared dependency ever appears — check `llvm-readelf -d` for a new
                // `NEEDED` entry — it would need naming back in here.
                jniLibs.excludes += "**/lib*-*.so"
            }
        }
    }

    // Shared JNI implementation for the JVM and Android targets. Both consume the same Rust
    // shared library through the same `Iroh4kJni` symbols; only the native-library loader
    // differs per platform.
    //
    // `jvmMain` only exists when the `jvm` target is enabled — it is absent for native-only
    // target subsets such as `-Ptargets=linuxX64` — so it is wired only when registered.
    // Guarding on the target (rather than probing the source set) avoids spuriously creating it.
    // `androidMain`/`androidHostTest` exist exactly when the Android target above was created,
    // which is the same condition, so they are guarded on the same flag.
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
    if (androidEnabled) {
        sourceSets.getByName("androidMain").dependsOn(jniMain)
        sourceSets.getByName("androidHostTest").dependsOn(jniTest)
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
        if (androidEnabled) {
            // Robolectric is the only thing these tests need beyond `commonTest`: it supplies the
            // Android framework the host JVM does not have. The test bodies themselves are the
            // shared `Common*Tests` and touch no Android API, so there is no `androidx.test`
            // dependency here — nothing asks for a Context. It reaches no consumer either; this
            // is a test-only compilation.
            getByName("androidHostTest").dependencies {
                implementation(libs.robolectric)
            }
        }
    }
}

rustJni {
    crateName = "iroh4k"
}
