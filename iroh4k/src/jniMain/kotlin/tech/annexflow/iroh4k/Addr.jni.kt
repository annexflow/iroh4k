package tech.annexflow.iroh4k


/**
 * Raw JNI entry points for the addressing domain.
 *
 * A separate object from [Iroh4kJni] so this domain owns its own symbols, beside the Rust exports in
 * `addr.rs` that implement them. The symbol names are part of the ABI: they must match the
 * `Java_tech_annexflow_iroh4k_AddrJni_*` exports there.
 *
 * Every call is synchronous. Parsing a socket address and coding a ticket are pure computation —
 * nothing here touches the network — so none of the start/await/cancel machinery [Iroh4kJni] needs
 * applies, and no `Dispatchers.IO` hop is warranted.
 */
internal object AddrJni {
    init {
        // Each JNI object must ensure the shared library is loaded before its first `external`
        // call; the loader itself is idempotent, so objects do not depend on each other's order.
        ensureNativeLoaded()
    }

    external fun socketParse(addr: String): ByteArray

    external fun ticketFromAddr(payload: ByteArray): ByteArray

    external fun ticketFromString(ticket: String): ByteArray
}


internal actual fun nativeAddrSocketParse(text: String): String =
    AddrJni.socketParse(text).jniBytesOrThrow().decodeToString()

internal actual fun nativeAddrTicketFromAddr(payload: ByteArray): ByteArray =
    AddrJni.ticketFromAddr(payload).jniBytesOrThrow()

internal actual fun nativeAddrTicketFromString(text: String): ByteArray =
    AddrJni.ticketFromString(text).jniBytesOrThrow()
