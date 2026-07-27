@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.iroh4k_addr_socket_parse
import iroh4k.ffi.iroh4k_addr_ticket_from_addr
import iroh4k.ffi.iroh4k_addr_ticket_from_string
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

/**
 * The addressing surface over the C ABI.
 *
 * cinterop maps each `const char *` parameter to a Kotlin `String`, copying it into a temporary
 * nul-terminated UTF-8 buffer that lives for the duration of the call — exactly as long as Rust
 * reads it, since every export here parses its argument and keeps nothing, returning freshly owned
 * allocations in the result.
 */

/**
 * Pins the payload and lends Rust a pointer to it plus its length, for the duration of [block].
 *
 * Pinning rather than copying is safe because every addressing export is synchronous: it has
 * returned by the time [block] does, and it decodes the payload into owned iroh values before
 * returning anything. That is the opposite of the async operations, which is why `core::owned_bytes`
 * copies and `addr::borrowed` does not.
 *
 * An empty array is lent as `null`/0 — `Pinned.addressOf(0)` is out of bounds for one — and Rust's
 * `borrowed` maps that back to an empty slice, which its payload reader then reports as malformed
 * rather than crashing on.
 */
private inline fun <T> ByteArray.usePtr(block: (CPointer<UByteVar>?, Int) -> T): T =
    if (isEmpty()) {
        block(null, 0)
    } else {
        usePinned { block(it.addressOf(0).reinterpret<UByteVar>(), size) }
    }

internal actual fun nativeAddrSocketParse(text: String): String =
    iroh4k_addr_socket_parse(text).bytesOrThrow().decodeToString()

internal actual fun nativeAddrTicketFromAddr(payload: ByteArray): ByteArray =
    payload.usePtr { ptr, len -> iroh4k_addr_ticket_from_addr(ptr, len) }.bytesOrThrow()

internal actual fun nativeAddrTicketFromString(text: String): ByteArray =
    iroh4k_addr_ticket_from_string(text).bytesOrThrow()
