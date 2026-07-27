@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.Iroh4kResult
import iroh4k.ffi.iroh4k_connection_watch_path_events
import iroh4k.ffi.iroh4k_connection_watch_paths
import iroh4k.ffi.iroh4k_endpoint_watch_addr
import iroh4k.ffi.iroh4k_endpoint_watch_home_relay
import iroh4k.ffi.iroh4k_free_result
import iroh4k.ffi.iroh4k_watch_free
import iroh4k.ffi.iroh4k_watch_live_handle_count
import iroh4k.ffi.iroh4k_watch_next
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong

/**
 * The watch surface over the C ABI.
 *
 * Follows `Stream.native.kt` in every respect: handles as `Long`, `iroh { }` around the one
 * asynchronous call so cancelling a collector reaches the tokio task. The four creators are
 * synchronous because building a watcher waits on nothing — the forwarding task `watch.rs` spawns is
 * what does the waiting.
 */

/** As `Stream.native.kt`'s: `0` maps to `null`, which every export answers with `ERROR_CLOSED`. */
private fun Long.asHandle(): COpaquePointer? = toCPointer<CPointed>()

/** As `Stream.native.kt`'s, for the handle a watcher creator produces. */
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
 * Reads a watcher item that may be the end of the stream, then frees the result — always.
 *
 * The same arrangement `Stream.native.kt` needs for a read that found the end of a stream, and for
 * the same reason: an *empty* payload is a real value here — `watchPaths` on a connection with no
 * open paths, `watchNetworkChange`'s `Unit` — so absence cannot be signalled by the payload alone.
 * Rust says "ended" with `-1` in `i64_val` and no payload, which `watch.rs` documents.
 */
private fun CPointer<Iroh4kResult>?.itemOrThrow(): ByteArray? {
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

internal actual fun nativeWatchLiveHandleCount(): Long = iroh4k_watch_live_handle_count()

internal actual fun nativeWatchFree(handle: Long) {
    iroh4k_watch_free(handle.asHandle())
}

// ── Starting a watcher — synchronous, as building one is in iroh ───────────────────────────────

internal actual fun nativeEndpointWatchAddr(handle: Long): Long =
    iroh4k_endpoint_watch_addr(handle.asHandle()).handleOrThrow()

internal actual fun nativeEndpointWatchHomeRelay(handle: Long): Long =
    iroh4k_endpoint_watch_home_relay(handle.asHandle()).handleOrThrow()

internal actual fun nativeConnectionWatchPaths(handle: Long): Long =
    iroh4k_connection_watch_paths(handle.asHandle()).handleOrThrow()

internal actual fun nativeConnectionWatchPathEvents(handle: Long): Long =
    iroh4k_connection_watch_path_events(handle.asHandle()).handleOrThrow()

// ── Asynchronous ──────────────────────────────────────────────────────────────────────────────
//
// No release function is passed to `iroh { }`: `next` produces a payload, never a handle, so a
// cancelled item has nothing to strand.

internal actual suspend fun nativeWatchNext(handle: Long): ByteArray? =
    iroh { c -> iroh4k_watch_next(handle.asHandle(), c, completion) }.itemOrThrow()
