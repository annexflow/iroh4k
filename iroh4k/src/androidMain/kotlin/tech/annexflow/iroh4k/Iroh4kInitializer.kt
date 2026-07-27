package tech.annexflow.iroh4k

import android.content.Context
import androidx.startup.Initializer

/**
 * Installs the Android context at process start, through `androidx.startup`.
 *
 * This exists because forgetting [Iroh4kAndroid.install] does not produce a stack trace or a
 * catchable exception — it ends the process with a native `SIGABRT` on a tokio worker thread,
 * inside the first `Endpoint.bind`. A library whose failure mode is "your app dies with an abort
 * message about a crate you have never heard of" should not rely on its consumers reading the
 * documentation, so the manifest entry that names this class initialises iroh4k for them.
 *
 * The work is a few JNI calls and no I/O, so it costs nothing measurable at startup.
 *
 * To opt out — for an app that initialises iroh4k itself, or one that wants no startup provider at
 * all — remove it in the app's manifest and call [Iroh4kAndroid.install] before creating any
 * endpoint:
 *
 * ```xml
 * <provider
 *     android:name="androidx.startup.InitializationProvider"
 *     android:authorities="${applicationId}.androidx-startup"
 *     tools:node="merge">
 *     <meta-data
 *         android:name="tech.annexflow.iroh4k.Iroh4kInitializer"
 *         tools:node="remove" />
 * </provider>
 * ```
 */
class Iroh4kInitializer : Initializer<Iroh4kAndroid> {

    override fun create(context: Context): Iroh4kAndroid {
        Iroh4kAndroid.install(context)
        return Iroh4kAndroid
    }

    /** Nothing to order against: this is the first thing iroh4k does in a process. */
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
