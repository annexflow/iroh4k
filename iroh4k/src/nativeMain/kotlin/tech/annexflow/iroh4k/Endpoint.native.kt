@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.iroh4k_endpoint_add_endpoint_addr
import iroh4k.ffi.iroh4k_endpoint_add_external_addr
import iroh4k.ffi.iroh4k_endpoint_addr
import iroh4k.ffi.iroh4k_endpoint_bind
import iroh4k.ffi.iroh4k_endpoint_bound_sockets
import iroh4k.ffi.iroh4k_endpoint_free
import iroh4k.ffi.iroh4k_endpoint_id
import iroh4k.ffi.iroh4k_endpoint_insert_relay
import iroh4k.ffi.iroh4k_endpoint_is_closed
import iroh4k.ffi.iroh4k_endpoint_live_handle_count
import iroh4k.ffi.iroh4k_endpoint_network_change
import iroh4k.ffi.iroh4k_endpoint_new
import iroh4k.ffi.iroh4k_endpoint_online
import iroh4k.ffi.iroh4k_endpoint_remote_addr
import iroh4k.ffi.iroh4k_endpoint_remove_endpoint_addr
import iroh4k.ffi.iroh4k_endpoint_remove_external_addr
import iroh4k.ffi.iroh4k_endpoint_remove_relay
import iroh4k.ffi.iroh4k_endpoint_secret_key
import iroh4k.ffi.iroh4k_endpoint_set_alpns
import iroh4k.ffi.iroh4k_endpoint_shutdown
import iroh4k.ffi.iroh4k_endpoint_stats
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned

/**
 * The endpoint surface over the C ABI.
 *
 * Two things differ from the value domains (`Keys`, `Relay`, `Addr`):
 *
 * - **Handles.** `commonMain` holds the endpoint handle as a `Long` — the JVM has no pointer type,
 *   so that is the only width both facades can share — and it is reinterpreted here. The pointer
 *   came from Rust's `into_handle`, is never dereferenced by Kotlin, and is only ever handed back;
 *   `Endpoint`'s guard is what guarantees it is still live when it is.
 * - **Buffer lifetimes.** A synchronous export finishes before it returns, so its payload can be
 *   *pinned* for the duration of the call. An asynchronous one returns immediately and its future
 *   reads the payload later on a tokio thread, by which time a pinned Kotlin array would be gone —
 *   so Rust copies those arguments, and pinning is still enough to lend them for the copy.
 */

/**
 * Reinterprets a handle from `commonMain` as the pointer Rust produced.
 *
 * `0` maps to `null`, which every export answers with `ERROR_CLOSED` rather than dereferencing —
 * unreachable through `Endpoint`, whose handle is non-null from construction, but the boundary does
 * not rely on that.
 */
private fun Long.asHandle(): COpaquePointer? = toCPointer<CPointed>()

/**
 * Pins the payload and lends Rust a pointer to it plus its length, for the duration of [block].
 *
 * An empty array is lent as `null`/0, since `Pinned.addressOf(0)` is out of bounds for one; Rust
 * maps that back to an empty slice, which its payload reader then reports as malformed rather than
 * crashing on.
 */
private inline fun <T> ByteArray.usePtr(block: (CPointer<UByteVar>?, Int) -> T): T =
    if (isEmpty()) {
        block(null, 0)
    } else {
        usePinned { block(it.addressOf(0).reinterpret<UByteVar>(), size) }
    }

// ── Handle lifecycle ──────────────────────────────────────────────────────────────────────────

/**
 * Allocates an unbound endpoint slot in Rust.
 *
 * `into_handle` cannot fail, so a null here would mean the library is not the one this was compiled
 * against; it is reported rather than passed on as a handle that every later call would reject.
 */
internal actual fun nativeEndpointNew(): Long =
    iroh4k_endpoint_new()?.toLong()
        ?: IrohError(IrohError.Code.Bind, "the native library returned no endpoint handle").raise()

internal actual fun nativeEndpointFree(handle: Long) {
    iroh4k_endpoint_free(handle.asHandle())
}

internal actual fun nativeEndpointLiveHandleCount(): Long = iroh4k_endpoint_live_handle_count()

// ── Synchronous ───────────────────────────────────────────────────────────────────────────────

internal actual fun nativeEndpointId(handle: Long): ByteArray =
    iroh4k_endpoint_id(handle.asHandle()).bytesOrThrow()

internal actual fun nativeEndpointAddr(handle: Long): ByteArray =
    iroh4k_endpoint_addr(handle.asHandle()).bytesOrThrow()

internal actual fun nativeEndpointSecretKey(handle: Long): ByteArray =
    iroh4k_endpoint_secret_key(handle.asHandle()).bytesOrThrow()

internal actual fun nativeEndpointSetAlpns(handle: Long, payload: ByteArray) {
    payload.usePtr { ptr, len -> iroh4k_endpoint_set_alpns(handle.asHandle(), ptr, len) }.orThrow()
}

internal actual fun nativeEndpointAddEndpointAddr(handle: Long, payload: ByteArray) {
    payload.usePtr { ptr, len ->
        iroh4k_endpoint_add_endpoint_addr(handle.asHandle(), ptr, len)
    }.orThrow()
}

internal actual fun nativeEndpointRemoveEndpointAddr(handle: Long, id: ByteArray): Boolean =
    id.usePtr { ptr, len ->
        iroh4k_endpoint_remove_endpoint_addr(handle.asHandle(), ptr, len)
    }.longOrThrow() != 0L

internal actual fun nativeEndpointBoundSockets(handle: Long): ByteArray =
    iroh4k_endpoint_bound_sockets(handle.asHandle()).bytesOrThrow()

internal actual fun nativeEndpointIsClosed(handle: Long): Boolean =
    iroh4k_endpoint_is_closed(handle.asHandle()).longOrThrow() != 0L

internal actual fun nativeEndpointStats(handle: Long): ByteArray =
    iroh4k_endpoint_stats(handle.asHandle()).bytesOrThrow()

// ── Asynchronous ──────────────────────────────────────────────────────────────────────────────
//
// Each one goes through `iroh { }`, which pins the continuation, hands Rust the operation id and
// registers `iroh4k_op_cancel` on cancellation — so cancelling any of these aborts the tokio task
// rather than abandoning it.

internal actual suspend fun nativeEndpointBind(handle: Long, payload: ByteArray) {
    iroh { c ->
        payload.usePtr { ptr, len -> iroh4k_endpoint_bind(handle.asHandle(), ptr, len, c, completion) }
    }.orThrow()
}

internal actual suspend fun nativeEndpointShutdown(handle: Long) {
    iroh { c -> iroh4k_endpoint_shutdown(handle.asHandle(), c, completion) }.orThrow()
}

internal actual suspend fun nativeEndpointOnline(handle: Long) {
    iroh { c -> iroh4k_endpoint_online(handle.asHandle(), c, completion) }.orThrow()
}

internal actual suspend fun nativeEndpointRemoteAddr(handle: Long, id: ByteArray): ByteArray =
    iroh { c ->
        id.usePtr { ptr, len -> iroh4k_endpoint_remote_addr(handle.asHandle(), ptr, len, c, completion) }
    }.bytesOrThrow()

internal actual suspend fun nativeEndpointAddExternalAddr(handle: Long, addr: String) {
    iroh { c -> iroh4k_endpoint_add_external_addr(handle.asHandle(), addr, c, completion) }.orThrow()
}

internal actual suspend fun nativeEndpointRemoveExternalAddr(handle: Long, addr: String): Boolean =
    iroh { c ->
        iroh4k_endpoint_remove_external_addr(handle.asHandle(), addr, c, completion)
    }.longOrThrow() != 0L

internal actual suspend fun nativeEndpointInsertRelay(handle: Long, payload: ByteArray): ByteArray =
    iroh { c ->
        payload.usePtr { ptr, len ->
            iroh4k_endpoint_insert_relay(handle.asHandle(), ptr, len, c, completion)
        }
    }.bytesOrThrow()

internal actual suspend fun nativeEndpointRemoveRelay(handle: Long, url: String): ByteArray =
    iroh { c -> iroh4k_endpoint_remove_relay(handle.asHandle(), url, c, completion) }.bytesOrThrow()

internal actual suspend fun nativeEndpointNetworkChange(handle: Long) {
    iroh { c -> iroh4k_endpoint_network_change(handle.asHandle(), c, completion) }.orThrow()
}
