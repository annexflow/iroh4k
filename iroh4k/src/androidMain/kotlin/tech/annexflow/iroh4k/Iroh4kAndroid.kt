package tech.annexflow.iroh4k

import android.content.Context

/**
 * Android process initialisation, done once before the first [Endpoint].
 *
 * **Consumers do not normally call this.** [Iroh4kInitializer] runs it through `androidx.startup`
 * when the process starts, so an app that just depends on the AAR is already initialised. The
 * function is public for the two cases where that does not happen: an app that removed the startup
 * provider from its manifest, and an embedder that creates iroh4k in a process App Startup does not
 * run in.
 *
 * ## What it installs, and why the library aborts without it
 *
 * Crates underneath iroh reach the Android platform through `ndk_context` — `hickory-resolver` for
 * the system DNS servers, `netdev` for the interface list — and that crate has to be handed the
 * process's `JavaVM` and application `Context` before anything asks it for them. Nothing in the
 * NDK does that for a library loaded by an ordinary app, so without this call the first
 * `Endpoint.bind` ends the process:
 *
 * ```text
 * Fatal signal 6 (SIGABRT) in tid (tokio-rt-worker)
 * Abort message: 'android context was not initialized'
 * ```
 *
 * That is a native abort, not an exception: `panic = "abort"` is deliberate in the Rust profile
 * because unwinding across the FFI boundary is undefined behaviour. So there is nothing for Kotlin
 * to catch, which is exactly why initialisation is automatic rather than documented.
 *
 * The stored `Context` is the *application* context regardless of what is passed in, so nothing
 * here can outlive-leak an Activity.
 */
object Iroh4kAndroid {

    /**
     * Installs the process's `JavaVM` and application `Context`.
     *
     * Idempotent and safe from any thread: the native side installs on the first successful call
     * and every later call returns that same outcome without touching anything.
     *
     * @throws IllegalStateException if the JNI handles could not be obtained, which would
     * otherwise surface much later as the abort above, on a thread with no connection to the code
     * that caused it.
     */
    fun install(context: Context) {
        check(Iroh4kAndroidJni.installContext(context.applicationContext)) {
            "iroh4k could not install the Android context. Endpoints cannot be created in this " +
                    "process; see Iroh4kAndroid."
        }
    }
}

/**
 * The native half of [Iroh4kAndroid.install].
 *
 * Symbol name is part of the ABI: it must match `Java_tech_annexflow_iroh4k_Iroh4kAndroidJni_*`
 * in the Rust `android.rs`. Loading the library first is this object's own job, as it is for
 * [Iroh4kJni] — [install][Iroh4kAndroid.install] is by design the first iroh4k call an app makes,
 * so nothing else has had a chance to load it.
 */
internal object Iroh4kAndroidJni {
    init {
        ensureNativeLoaded()
    }

    external fun installContext(context: Context): Boolean
}
