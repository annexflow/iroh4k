@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.Iroh4kResult
import iroh4k.ffi.iroh4k_accepting_alpn
import iroh4k.ffi.iroh4k_accepting_connect
import iroh4k.ffi.iroh4k_accepting_free
import iroh4k.ffi.iroh4k_accepting_zero_rtt
import iroh4k.ffi.iroh4k_connecting_alpn
import iroh4k.ffi.iroh4k_connecting_connect
import iroh4k.ffi.iroh4k_connecting_free
import iroh4k.ffi.iroh4k_connecting_remote_id
import iroh4k.ffi.iroh4k_connecting_zero_rtt
import iroh4k.ffi.iroh4k_connection_alpn
import iroh4k.ffi.iroh4k_connection_close
import iroh4k.ffi.iroh4k_connection_close_reason
import iroh4k.ffi.iroh4k_connection_closed
import iroh4k.ffi.iroh4k_connection_datagram_send_buffer_space
import iroh4k.ffi.iroh4k_connection_free
import iroh4k.ffi.iroh4k_connection_live_handle_count
import iroh4k.ffi.iroh4k_connection_max_datagram_size
import iroh4k.ffi.iroh4k_connection_paths
import iroh4k.ffi.iroh4k_connection_read_datagram
import iroh4k.ffi.iroh4k_connection_remote_id
import iroh4k.ffi.iroh4k_connection_rtt
import iroh4k.ffi.iroh4k_connection_send_datagram
import iroh4k.ffi.iroh4k_connection_send_datagram_wait
import iroh4k.ffi.iroh4k_connection_set_max_concurrent_bi_streams
import iroh4k.ffi.iroh4k_connection_set_max_concurrent_uni_streams
import iroh4k.ffi.iroh4k_connection_set_receive_window
import iroh4k.ffi.iroh4k_connection_side
import iroh4k.ffi.iroh4k_connection_stable_id
import iroh4k.ffi.iroh4k_connection_stats
import iroh4k.ffi.iroh4k_endpoint_accept_next
import iroh4k.ffi.iroh4k_endpoint_connect
import iroh4k.ffi.iroh4k_endpoint_start_connect
import iroh4k.ffi.iroh4k_free_result
import iroh4k.ffi.iroh4k_incoming_accept
import iroh4k.ffi.iroh4k_incoming_accept_with
import iroh4k.ffi.iroh4k_incoming_free
import iroh4k.ffi.iroh4k_incoming_ignore
import iroh4k.ffi.iroh4k_incoming_local_addr
import iroh4k.ffi.iroh4k_incoming_refuse
import iroh4k.ffi.iroh4k_incoming_remote_addr
import iroh4k.ffi.iroh4k_incoming_remote_addr_validated
import iroh4k.ffi.iroh4k_incoming_retry
import iroh4k.ffi.iroh4k_incoming_zero_rtt_await_handshake
import iroh4k.ffi.iroh4k_incoming_zero_rtt_free
import iroh4k.ffi.iroh4k_outgoing_zero_rtt_await_handshake
import iroh4k.ffi.iroh4k_outgoing_zero_rtt_free
import iroh4k.ffi.iroh4k_zero_rtt_alpn
import iroh4k.ffi.iroh4k_zero_rtt_remote_id
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned

/**
 * The connection surface over the C ABI.
 *
 * Follows `Endpoint.native.kt` in every respect — handles as `Long`, payloads pinned for the
 * synchronous exports and copied by Rust for the asynchronous ones — with one addition: this is the
 * first domain whose operations *return* handles, so it needs to read the `handle` field of an
 * `Iroh4kResult` and not only `bytes`/`i64_val`. See [handleOrThrow].
 */

/**
 * Reinterprets a handle from `commonMain` as the pointer Rust produced.
 *
 * `0` maps to `null`, which every export answers with `ERROR_CLOSED` rather than dereferencing.
 *
 * A near-copy of `Endpoint.native.kt`'s, which is private to that file; both should be one shared
 * helper once the native facade has a common file.
 */
private fun Long.asHandle(): COpaquePointer? = toCPointer<CPointed>()

/**
 * Pins the payload and lends Rust a pointer to it plus its length, for the duration of [block].
 *
 * An empty array is lent as `null`/0, since `Pinned.addressOf(0)` is out of bounds for one. As
 * [asHandle], a near-copy of the one in `Endpoint.native.kt`.
 */
private inline fun <T> ByteArray.usePtr(block: (CPointer<UByteVar>?, Int) -> T): T =
    if (isEmpty()) {
        block(null, 0)
    } else {
        usePinned { block(it.addressOf(0).reinterpret<UByteVar>(), size) }
    }

/**
 * Reads `handle` and `i64_val` from the same result and frees it once — what
 * [nativeOutgoingZeroRttAwaitHandshake] needs, since `outgoing_zero_rtt_await_handshake` answers with
 * both the fresh [Connection] handle and the accepted bit in the one envelope. Chaining [handleOrThrow]
 * with a second read of `i64_val` would free the result after the first call's `finally` and then read
 * a field on the now-freed pointer, so both fields are taken here before the single free.
 *
 * This is also the only place in the file that performs the null-check / error-raise / free sequence
 * on a handle-bearing result — [handleOrThrow] is expressed over it rather than repeating that
 * sequence a second time, so a future fix to the teardown (a different free ordering, a new guard on
 * a null handle) only has one place to land.
 */
private fun CPointer<Iroh4kResult>?.handleAndAcceptedOrThrow(): Pair<Long, Boolean> {
    try {
        val result = this?.pointed
            ?: throw IllegalStateException("Invalid Iroh4kResult pointer: cannot dereference null")
        if (result.error != IrohError.OK) {
            IrohError(IrohError.Code.of(result.error), result.error_message?.toKString()).raise()
        }
        return (result.handle?.toLong() ?: 0L) to (result.i64_val != 0L)
    } finally {
        iroh4k_free_result(this)
    }
}

/**
 * Reads the `handle` field of a result, then frees the result — always, even on failure.
 *
 * `ffi.kt` has `orThrow`, `longOrThrow` and `bytesOrThrow` but no handle reader, because until this
 * domain nothing produced a handle from an operation. It belongs beside those three; it is here for
 * now because they share a private `use { }` helper this file cannot reach.
 *
 * `0` for a null handle. That is not an error: it is how `acceptNext` reports iroh's `None` for an
 * endpoint that has been shut down — and, for [nativeConnectingZeroRtt], how a peer with no cached
 * session ticket is reported; see the comment there.
 */
private fun CPointer<Iroh4kResult>?.handleOrThrow(): Long = handleAndAcceptedOrThrow().first

// ── Handle lifecycle ──────────────────────────────────────────────────────────────────────────

internal actual fun nativeConnectionLiveHandleCount(): Long = iroh4k_connection_live_handle_count()

internal actual fun nativeIncomingFree(handle: Long) {
    iroh4k_incoming_free(handle.asHandle())
}

internal actual fun nativeAcceptingFree(handle: Long) {
    iroh4k_accepting_free(handle.asHandle())
}

internal actual fun nativeConnectingFree(handle: Long) {
    iroh4k_connecting_free(handle.asHandle())
}

internal actual fun nativeConnectionFree(handle: Long) {
    iroh4k_connection_free(handle.asHandle())
}

internal actual fun nativeOutgoingZeroRttFree(handle: Long) {
    iroh4k_outgoing_zero_rtt_free(handle.asHandle())
}

internal actual fun nativeIncomingZeroRttFree(handle: Long) {
    iroh4k_incoming_zero_rtt_free(handle.asHandle())
}

// ── Incoming — synchronous, as every one of these is in iroh ───────────────────────────────────

internal actual fun nativeIncomingAccept(handle: Long): Long =
    iroh4k_incoming_accept(handle.asHandle()).handleOrThrow()

internal actual fun nativeIncomingRefuse(handle: Long) {
    iroh4k_incoming_refuse(handle.asHandle()).orThrow()
}

internal actual fun nativeIncomingRetry(handle: Long) {
    iroh4k_incoming_retry(handle.asHandle()).orThrow()
}

internal actual fun nativeIncomingIgnore(handle: Long) {
    iroh4k_incoming_ignore(handle.asHandle()).orThrow()
}

internal actual fun nativeIncomingLocalAddr(handle: Long): ByteArray =
    iroh4k_incoming_local_addr(handle.asHandle()).bytesOrThrow()

internal actual fun nativeIncomingRemoteAddr(handle: Long): ByteArray =
    iroh4k_incoming_remote_addr(handle.asHandle()).bytesOrThrow()

internal actual fun nativeIncomingRemoteAddrValidated(handle: Long): Boolean =
    iroh4k_incoming_remote_addr_validated(handle.asHandle()).longOrThrow() != 0L

internal actual fun nativeIncomingAcceptWith(handle: Long, endpoint: Long, opts: ByteArray): Long =
    opts.usePtr { ptr, len ->
        iroh4k_incoming_accept_with(handle.asHandle(), endpoint.asHandle(), ptr, len)
    }.handleOrThrow()

// ── Connecting and Connection — synchronous ───────────────────────────────────────────────────

internal actual fun nativeConnectingRemoteId(handle: Long): ByteArray =
    iroh4k_connecting_remote_id(handle.asHandle()).bytesOrThrow()

internal actual fun nativeZeroRttAlpn(handle: Long): ByteArray? =
    iroh4k_zero_rtt_alpn(handle.asHandle()).bytesOrNull()

internal actual fun nativeZeroRttRemoteId(handle: Long): ByteArray? =
    iroh4k_zero_rtt_remote_id(handle.asHandle()).bytesOrNull()

internal actual fun nativeConnectionAlpn(handle: Long): ByteArray =
    iroh4k_connection_alpn(handle.asHandle()).bytesOrThrow()

internal actual fun nativeConnectionRemoteId(handle: Long): ByteArray =
    iroh4k_connection_remote_id(handle.asHandle()).bytesOrThrow()

internal actual fun nativeConnectionStableId(handle: Long): Long =
    iroh4k_connection_stable_id(handle.asHandle()).longOrThrow()

internal actual fun nativeConnectionCloseReason(handle: Long): ByteArray =
    iroh4k_connection_close_reason(handle.asHandle()).bytesOrThrow()

internal actual fun nativeConnectionClose(handle: Long, errorCode: Long, reason: ByteArray) {
    reason.usePtr { ptr, len ->
        iroh4k_connection_close(handle.asHandle(), errorCode, ptr, len)
    }.orThrow()
}

internal actual fun nativeConnectionSide(handle: Long): Long =
    iroh4k_connection_side(handle.asHandle()).longOrThrow()

internal actual fun nativeConnectionRtt(handle: Long): Long =
    iroh4k_connection_rtt(handle.asHandle()).longOrThrow()

internal actual fun nativeConnectionStats(handle: Long): ByteArray =
    iroh4k_connection_stats(handle.asHandle()).bytesOrThrow()

internal actual fun nativeConnectionPaths(handle: Long): ByteArray =
    iroh4k_connection_paths(handle.asHandle()).bytesOrThrow()

internal actual fun nativeConnectionMaxDatagramSize(handle: Long): Long =
    iroh4k_connection_max_datagram_size(handle.asHandle()).longOrThrow()

internal actual fun nativeConnectionDatagramSendBufferSpace(handle: Long): Long =
    iroh4k_connection_datagram_send_buffer_space(handle.asHandle()).longOrThrow()

internal actual fun nativeConnectionSendDatagram(handle: Long, payload: ByteArray) {
    payload.usePtr { ptr, len ->
        iroh4k_connection_send_datagram(handle.asHandle(), ptr, len)
    }.orThrow()
}

internal actual fun nativeConnectionSetMaxConcurrentBiStreams(handle: Long, count: Long) {
    iroh4k_connection_set_max_concurrent_bi_streams(handle.asHandle(), count).orThrow()
}

internal actual fun nativeConnectionSetMaxConcurrentUniStreams(handle: Long, count: Long) {
    iroh4k_connection_set_max_concurrent_uni_streams(handle.asHandle(), count).orThrow()
}

internal actual fun nativeConnectionSetReceiveWindow(handle: Long, bytes: Long) {
    iroh4k_connection_set_receive_window(handle.asHandle(), bytes).orThrow()
}

// ── Asynchronous ──────────────────────────────────────────────────────────────────────────────
//
// Each one goes through `iroh { }`, which pins the continuation, hands Rust the operation id and
// registers `iroh4k_op_cancel` on cancellation — so cancelling an `acceptNext` that may never
// complete aborts the tokio task rather than abandoning it.

internal actual suspend fun nativeEndpointAcceptNext(handle: Long): Long =
    iroh(::iroh4k_incoming_free) { c ->
        iroh4k_endpoint_accept_next(handle.asHandle(), c, completion)
    }.handleOrThrow()

internal actual suspend fun nativeEndpointStartConnect(
    handle: Long,
    addr: ByteArray,
    alpn: ByteArray,
    opts: ByteArray,
): Long = iroh(::iroh4k_connecting_free) { c ->
    // All three buffers are pinned only for the call: Rust copies them before spawning the future.
    addr.usePtr { addrPtr, addrLen ->
        alpn.usePtr { alpnPtr, alpnLen ->
            opts.usePtr { optsPtr, optsLen ->
                iroh4k_endpoint_start_connect(
                    handle.asHandle(), addrPtr, addrLen, alpnPtr, alpnLen, optsPtr, optsLen,
                    c, completion,
                )
            }
        }
    }
}.handleOrThrow()

internal actual suspend fun nativeAcceptingConnect(handle: Long): Long =
    iroh(::iroh4k_connection_free) { c ->
        iroh4k_accepting_connect(handle.asHandle(), c, completion)
    }.handleOrThrow()

internal actual suspend fun nativeAcceptingAlpn(handle: Long): ByteArray =
    iroh { c -> iroh4k_accepting_alpn(handle.asHandle(), c, completion) }.bytesOrThrow()

// Unlike `nativeConnectingZeroRtt`, this never answers `0`: `iroh4k_accepting_zero_rtt` is infallible
// upstream — see `connection.rs`'s `accepting_zero_rtt` — so the handle it produces is always real.
internal actual suspend fun nativeAcceptingZeroRtt(handle: Long): Long =
    iroh(::iroh4k_incoming_zero_rtt_free) { c ->
        iroh4k_accepting_zero_rtt(handle.asHandle(), c, completion)
    }.handleOrThrow()

internal actual suspend fun nativeConnectingConnect(handle: Long): Long =
    iroh(::iroh4k_connection_free) { c ->
        iroh4k_connecting_connect(handle.asHandle(), c, completion)
    }.handleOrThrow()

internal actual suspend fun nativeConnectingAlpn(handle: Long): ByteArray =
    iroh { c -> iroh4k_connecting_alpn(handle.asHandle(), c, completion) }.bytesOrThrow()

// A `0` handle from `handleOrThrow()` here is the answer, not a fault: `iroh4k_connecting_zero_rtt`
// never sets an error for "no session ticket for this peer", it just leaves the result's `handle`
// null, so `handleOrThrow`'s ordinary null-handle-means-zero reading already says exactly what
// [Connecting.zeroRtt] needs — the caller is the one that decides a `0` here means "try `connect`
// instead" rather than "shut down", the same way it already reads `acceptNext`'s `0` as "endpoint
// closed" and not the other way round.
internal actual suspend fun nativeConnectingZeroRtt(handle: Long): Long =
    iroh(::iroh4k_outgoing_zero_rtt_free) { c ->
        iroh4k_connecting_zero_rtt(handle.asHandle(), c, completion)
    }.handleOrThrow()

internal actual suspend fun nativeOutgoingZeroRttAwaitHandshake(handle: Long): Pair<Long, Boolean> =
    iroh(::iroh4k_connection_free) { c ->
        iroh4k_outgoing_zero_rtt_await_handshake(handle.asHandle(), c, completion)
    }.handleAndAcceptedOrThrow()

// No accepted/rejected pair to read here — `incoming_zero_rtt_await_handshake` answers a bare handle
// through `finish_handshake`, exactly like `nativeAcceptingConnect` and `nativeConnectingConnect`.
internal actual suspend fun nativeIncomingZeroRttAwaitHandshake(handle: Long): Long =
    iroh(::iroh4k_connection_free) { c ->
        iroh4k_incoming_zero_rtt_await_handshake(handle.asHandle(), c, completion)
    }.handleOrThrow()

internal actual suspend fun nativeEndpointConnect(
    handle: Long,
    addr: ByteArray,
    alpn: ByteArray,
): Long = iroh(::iroh4k_connection_free) { c ->
    // Both buffers are pinned only for the call: Rust copies them before spawning the future.
    addr.usePtr { addrPtr, addrLen ->
        alpn.usePtr { alpnPtr, alpnLen ->
            iroh4k_endpoint_connect(
                handle.asHandle(), addrPtr, addrLen, alpnPtr, alpnLen, c, completion,
            )
        }
    }
}.handleOrThrow()

internal actual suspend fun nativeConnectionClosed(handle: Long): String =
    iroh { c -> iroh4k_connection_closed(handle.asHandle(), c, completion) }
        .bytesOrThrow()
        .decodeToString()

internal actual suspend fun nativeConnectionSendDatagramWait(handle: Long, payload: ByteArray) {
    iroh { c ->
        payload.usePtr { ptr, len ->
            iroh4k_connection_send_datagram_wait(handle.asHandle(), ptr, len, c, completion)
        }
    }.orThrow()
}

internal actual suspend fun nativeConnectionReadDatagram(handle: Long): ByteArray =
    iroh { c -> iroh4k_connection_read_datagram(handle.asHandle(), c, completion) }.bytesOrThrow()
