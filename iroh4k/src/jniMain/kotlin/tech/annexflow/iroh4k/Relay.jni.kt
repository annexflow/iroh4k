package tech.annexflow.iroh4k


/**
 * Raw JNI entry points for the relay and logging surface.
 *
 * A separate object from [Iroh4kJni] so the relay domain owns its own symbols, and because the
 * Rust exports for it live in `relay.rs` beside the logic they wrap. The symbol names are part of
 * the ABI: they must match the `Java_tech_annexflow_iroh4k_RelayJni_*` exports there.
 *
 * Every call here is synchronous. Nothing in this domain touches the network — URL parsing and
 * reading iroh's built-in relay lists are pure computation — so none of it needs the operation
 * registry or a `Dispatchers.IO` hop.
 */
internal object RelayJni {
    init {
        // Each JNI object must ensure the shared library is loaded before its first `external`
        // call; the loader itself is idempotent, so objects do not depend on each other's order.
        ensureNativeLoaded()
    }

    external fun relayUrlParse(url: String): ByteArray

    external fun relayDefaultUrls(): ByteArray

    external fun relayStagingUrls(): ByteArray

    external fun setLogLevel(level: Int): ByteArray
}


internal actual fun nativeRelayUrlParse(url: String): String =
    RelayJni.relayUrlParse(url).jniBytesOrThrow().decodeToString()

internal actual fun nativeRelayDefaultUrls(): ByteArray =
    RelayJni.relayDefaultUrls().jniBytesOrThrow()

internal actual fun nativeRelayStagingUrls(): ByteArray =
    RelayJni.relayStagingUrls().jniBytesOrThrow()

internal actual fun nativeSetLogLevel(level: Int) {
    RelayJni.setLogLevel(level).jniBytesOrThrow()
}
