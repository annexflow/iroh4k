@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.Iroh4kResult
import iroh4k.ffi.iroh4k_connection_accept_bi
import iroh4k.ffi.iroh4k_connection_accept_uni
import iroh4k.ffi.iroh4k_connection_open_bi
import iroh4k.ffi.iroh4k_connection_open_uni
import iroh4k.ffi.iroh4k_free_result
import iroh4k.ffi.iroh4k_recv_stream_bytes_read
import iroh4k.ffi.iroh4k_recv_stream_id
import iroh4k.ffi.iroh4k_recv_stream_read
import iroh4k.ffi.iroh4k_recv_stream_read_exact
import iroh4k.ffi.iroh4k_recv_stream_read_to_end
import iroh4k.ffi.iroh4k_recv_stream_received_reset
import iroh4k.ffi.iroh4k_recv_stream_stop
import iroh4k.ffi.iroh4k_send_stream_finish
import iroh4k.ffi.iroh4k_send_stream_id
import iroh4k.ffi.iroh4k_send_stream_priority
import iroh4k.ffi.iroh4k_send_stream_reset
import iroh4k.ffi.iroh4k_send_stream_set_priority
import iroh4k.ffi.iroh4k_send_stream_stopped
import iroh4k.ffi.iroh4k_send_stream_write
import iroh4k.ffi.iroh4k_send_stream_write_all
import iroh4k.ffi.iroh4k_stream_free
import iroh4k.ffi.iroh4k_stream_live_handle_count
import iroh4k.ffi.iroh4k_stream_share
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned

/**
 * The stream surface over the C ABI.
 *
 * Follows `Connection.native.kt` in every respect — handles as `Long`, payloads pinned for the
 * synchronous exports and copied by Rust for the asynchronous ones, `iroh { }` around every
 * asynchronous call so cancellation reaches the tokio task. It adds one reader the other facades did
 * not need: a read that may find the end of the stream, which is a *value* rather than a failure. See
 * [optionalBytesOrThrow].
 */

/** As `Connection.native.kt`'s: `0` maps to `null`, which every export answers with `ERROR_CLOSED`. */
private fun Long.asHandle(): COpaquePointer? = toCPointer<CPointed>()

/** As `Connection.native.kt`'s: an empty array is lent as `null`/0. */
private inline fun <T> ByteArray.usePtr(block: (CPointer<UByteVar>?, Int) -> T): T =
    if (isEmpty()) {
        block(null, 0)
    } else {
        usePinned { block(it.addressOf(0).reinterpret<UByteVar>(), size) }
    }

/** As `Connection.native.kt`'s, for the handle a stream open produces. */
private fun CPointer<Iroh4kResult>?.handleOrThrow(): Long {
    try {
        val result = this?.pointed
            ?: throw IllegalStateException("Invalid Iroh4kResult pointer: cannot dereference null")
        if (result.error != IrohError.OK) {
            IrohError(IrohError.Code.of(result.error), result.error_message?.toKString()).raise()
        }
        return result.handle?.toLong() ?: 0L
    } finally {
        iroh4k_free_result(this)
    }
}

/**
 * Reads a payload that may legitimately be absent, then frees the result — always, even on failure.
 *
 * The end of a stream is not an error and not an empty array either: an empty array is a real (if
 * pathological) read, so the two have to be distinguishable. Rust says "end of stream" by putting `-1`
 * in `i64_val` and no payload, which `stream.rs` documents; this is the Kotlin side of that.
 */
private fun CPointer<Iroh4kResult>?.optionalBytesOrThrow(): ByteArray? {
    try {
        val result = this?.pointed
            ?: throw IllegalStateException("Invalid Iroh4kResult pointer: cannot dereference null")
        if (result.error != IrohError.OK) {
            IrohError(IrohError.Code.of(result.error), result.error_message?.toKString()).raise()
        }
        if (result.i64_val < 0) return null
        val len = result.bytes_len
        return if (len <= 0) ByteArray(0) else result.bytes?.readBytes(len) ?: ByteArray(0)
    } finally {
        iroh4k_free_result(this)
    }
}

// ── Handle lifecycle ──────────────────────────────────────────────────────────────────────────

internal actual fun nativeStreamLiveHandleCount(): Long = iroh4k_stream_live_handle_count()

internal actual fun nativeStreamFree(handle: Long) {
    iroh4k_stream_free(handle.asHandle())
}

internal actual fun nativeStreamShare(handle: Long): Long =
    iroh4k_stream_share(handle.asHandle())?.toLong() ?: 0L

// ── The send half — synchronous, as these are in iroh ──────────────────────────────────────────

internal actual fun nativeSendStreamFinish(handle: Long) {
    iroh4k_send_stream_finish(handle.asHandle()).orThrow()
}

internal actual fun nativeSendStreamReset(handle: Long, errorCode: Long) {
    iroh4k_send_stream_reset(handle.asHandle(), errorCode).orThrow()
}

internal actual fun nativeSendStreamSetPriority(handle: Long, priority: Int) {
    iroh4k_send_stream_set_priority(handle.asHandle(), priority).orThrow()
}

internal actual fun nativeSendStreamPriority(handle: Long): Int =
    iroh4k_send_stream_priority(handle.asHandle()).longOrThrow().toInt()

internal actual fun nativeSendStreamId(handle: Long): Long =
    iroh4k_send_stream_id(handle.asHandle()).longOrThrow()

// ── The receive half — synchronous ─────────────────────────────────────────────────────────────

internal actual fun nativeRecvStreamStop(handle: Long, errorCode: Long) {
    iroh4k_recv_stream_stop(handle.asHandle(), errorCode).orThrow()
}

internal actual fun nativeRecvStreamBytesRead(handle: Long): Long =
    iroh4k_recv_stream_bytes_read(handle.asHandle()).longOrThrow()

internal actual fun nativeRecvStreamId(handle: Long): Long =
    iroh4k_recv_stream_id(handle.asHandle()).longOrThrow()

// ── Asynchronous ──────────────────────────────────────────────────────────────────────────────
//
// Every stream open produces a handle, so each of the four passes `iroh4k_stream_free` as the release
// function: a cancel landing in the instant the stream was opened releases it rather than stranding an
// open QUIC stream — and, through it, the connection it belongs to.

internal actual suspend fun nativeConnectionOpenBi(handle: Long): Long =
    iroh(::iroh4k_stream_free) { c ->
        iroh4k_connection_open_bi(handle.asHandle(), c, completion)
    }.handleOrThrow()

internal actual suspend fun nativeConnectionAcceptBi(handle: Long): Long =
    iroh(::iroh4k_stream_free) { c ->
        iroh4k_connection_accept_bi(handle.asHandle(), c, completion)
    }.handleOrThrow()

internal actual suspend fun nativeConnectionOpenUni(handle: Long): Long =
    iroh(::iroh4k_stream_free) { c ->
        iroh4k_connection_open_uni(handle.asHandle(), c, completion)
    }.handleOrThrow()

internal actual suspend fun nativeConnectionAcceptUni(handle: Long): Long =
    iroh(::iroh4k_stream_free) { c ->
        iroh4k_connection_accept_uni(handle.asHandle(), c, completion)
    }.handleOrThrow()

internal actual suspend fun nativeSendStreamWrite(handle: Long, payload: ByteArray): Long =
    iroh { c ->
        // Pinned only for the call: Rust copies the buffer before spawning the future.
        payload.usePtr { ptr, len ->
            iroh4k_send_stream_write(handle.asHandle(), ptr, len, c, completion)
        }
    }.longOrThrow()

internal actual suspend fun nativeSendStreamWriteAll(handle: Long, payload: ByteArray) {
    iroh { c ->
        payload.usePtr { ptr, len ->
            iroh4k_send_stream_write_all(handle.asHandle(), ptr, len, c, completion)
        }
    }.orThrow()
}

internal actual suspend fun nativeSendStreamStopped(handle: Long): Long =
    iroh { c -> iroh4k_send_stream_stopped(handle.asHandle(), c, completion) }.longOrThrow()

internal actual suspend fun nativeRecvStreamRead(handle: Long, sizeLimit: Long): ByteArray? =
    iroh { c ->
        iroh4k_recv_stream_read(handle.asHandle(), sizeLimit, c, completion)
    }.optionalBytesOrThrow()

internal actual suspend fun nativeRecvStreamReadExact(handle: Long, size: Long): ByteArray =
    iroh { c ->
        iroh4k_recv_stream_read_exact(handle.asHandle(), size, c, completion)
    }.bytesOrThrow()

internal actual suspend fun nativeRecvStreamReadToEnd(handle: Long, sizeLimit: Long): ByteArray =
    iroh { c ->
        iroh4k_recv_stream_read_to_end(handle.asHandle(), sizeLimit, c, completion)
    }.bytesOrThrow()

internal actual suspend fun nativeRecvStreamReceivedReset(handle: Long): Long =
    iroh { c -> iroh4k_recv_stream_received_reset(handle.asHandle(), c, completion) }.longOrThrow()
