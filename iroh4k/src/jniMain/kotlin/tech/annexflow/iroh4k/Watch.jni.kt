package tech.annexflow.iroh4k

/**
 * Raw JNI entry points for the watch domain.
 *
 * A separate object from [ConnectionJni] and [StreamJni] so this domain owns its own symbols, beside
 * the Rust exports in `watch.rs` that implement them. The symbol names are part of the ABI: they must
 * match the `Java_tech_annexflow_iroh4k_WatchJni_*` exports there.
 *
 * The split follows iroh's own shape, as the other domains' do. Starting a watcher is synchronous
 * because building one waits on nothing — the forwarding task `watch.rs` spawns is what waits — while
 * [nextStart] is split into **start / await / cancel** as [Iroh4kJni] describes, because it is the
 * one call here that may never finish on its own: a watcher can sit idle for the life of the process.
 *
 * Handles cross as `Long`, which is the only pointer-sized integer the JVM has.
 */
internal object WatchJni {
    init {
        // As every JNI object here: the loader is idempotent, so objects need no ordering between them.
        ensureNativeLoaded()
    }

    // ── Handle lifecycle ─────────────────────────────────────────────────────────────────────

    external fun liveHandleCount(): Long

    external fun free(handle: Long)

    // ── Starting a watcher — synchronous ─────────────────────────────────────────────────────

    external fun endpointWatchAddr(endpoint: Long): ByteArray

    external fun endpointWatchHomeRelay(endpoint: Long): ByteArray

    external fun connectionWatchPaths(connection: Long): ByteArray

    external fun connectionWatchPathEvents(connection: Long): ByteArray

    // ── Asynchronous — returns an operation id for Iroh4kJni.opAwait/opCancel ─────────────────

    external fun nextStart(handle: Long): Long
}

/**
 * The handle a watcher creator produced, or a raised failure.
 *
 * The creators are synchronous, so this reads the envelope directly rather than going through
 * `jniOp` — there is no operation to await and nothing to cancel.
 */
private fun ByteArray.jniHandleOrThrow(): Long = decodeResult().let {
    it.throwIfError()
    it.handle
}

/**
 * The payload of a watcher item that may be the end of the stream.
 *
 * The counterpart of `Watch.native.kt`'s `itemOrThrow`, and the same arrangement `Stream.jni.kt`
 * needs for a read that found the end of a stream: an *empty* payload is a real value here, so
 * absence cannot be signalled by the payload alone. Rust says "ended" with `-1` in `i64_val` and no
 * payload, which `watch.rs` documents.
 */
private fun JniResult.item(): ByteArray? = if (longValue < 0) null else bytes ?: ByteArray(0)

// ── Handle lifecycle ──────────────────────────────────────────────────────────────────────────

internal actual fun nativeWatchLiveHandleCount(): Long = WatchJni.liveHandleCount()

internal actual fun nativeWatchFree(handle: Long) = WatchJni.free(handle)

// ── Starting a watcher — synchronous ───────────────────────────────────────────────────────────

internal actual fun nativeEndpointWatchAddr(handle: Long): Long =
    WatchJni.endpointWatchAddr(handle).jniHandleOrThrow()

internal actual fun nativeEndpointWatchHomeRelay(handle: Long): Long =
    WatchJni.endpointWatchHomeRelay(handle).jniHandleOrThrow()

internal actual fun nativeConnectionWatchPaths(handle: Long): Long =
    WatchJni.connectionWatchPaths(handle).jniHandleOrThrow()

internal actual fun nativeConnectionWatchPathEvents(handle: Long): Long =
    WatchJni.connectionWatchPathEvents(handle).jniHandleOrThrow()

// ── Asynchronous ──────────────────────────────────────────────────────────────────────────────
//
// No release function is passed to `jniOp`: `next` produces a payload, never a handle, so a cancelled
// item has nothing to strand.

internal actual suspend fun nativeWatchNext(handle: Long): ByteArray? =
    jniOp({ WatchJni.nextStart(handle) }) { it.item() }
