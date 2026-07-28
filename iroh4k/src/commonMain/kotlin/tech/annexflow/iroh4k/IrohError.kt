package tech.annexflow.iroh4k

/**
 * Every failure crossing the FFI boundary.
 *
 * The Rust side returns an integer code which is decoded here by [Code] **ordinal**, so the
 * enum order is part of the wire protocol shared with `core.rs`.
 */
class IrohError(
    val code: Code,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(if (message != null) "[$code] :: $message" else "[$code]", cause) {

    fun raise(): Nothing = throw this

    enum class Code {
        // IMPORTANT: Do not change the order of the errors — these ordinals are matched
        // against the `ERROR_*` constants in the Rust `core.rs`. Append only.
        Unknown,
        Cancelled,
        InvalidArgument,
        Bind,
        Connect,
        Accept,
        Read,
        Write,
        Closed,
        Key,
        Addr,
        Ticket,
        Relay,
        Services,
        Discovery,
        ;

        companion object {
            /** Maps a Rust `ERROR_*` code onto a [Code], falling back to [Unknown]. */
            fun of(code: Int): Code = entries.getOrElse(code) { Unknown }
        }
    }

    companion object {
        /** The Rust `OK` sentinel: any other value is an error code. */
        const val OK: Int = -1
    }
}
