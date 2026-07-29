package tech.annexflow.iroh4k

/**
 * Raw JNI entry points for the stream domain.
 *
 * A separate object from [ConnectionJni] so this domain owns its own symbols, beside the Rust exports
 * in `stream.rs` that implement them. The symbol names are part of the ABI: they must match the
 * `Java_tech_annexflow_iroh4k_StreamJni_*` exports there.
 *
 * The split follows iroh's own shape, as [ConnectionJni]'s does. `finish`, `reset`, `stop` and the
 * priority, id and byte-count accessors are synchronous because they are synchronous in iroh; the I/O
 * is split into **start / await / cancel** as [Iroh4kJni] describes, because a single blocking call
 * would pin a `Dispatchers.IO` thread with nothing able to interrupt it — and [acceptBiStart],
 * [recvReadStart] and [sendStoppedStart] in particular may never finish on their own.
 *
 * Handles cross as `Long`, which is the only pointer-sized integer the JVM has.
 */
internal object StreamJni {
    init {
        // As every JNI object here: the loader is idempotent, so objects need no ordering between them.
        ensureNativeLoaded()
    }

    // ── Handle lifecycle ─────────────────────────────────────────────────────────────────────

    external fun liveHandleCount(): Long

    external fun free(handle: Long)

    /** A second handle to the same payload. Returns the handle directly — it cannot fail. */
    external fun share(handle: Long): Long

    // ── The send half — synchronous ───────────────────────────────────────────────────────────

    external fun sendFinish(handle: Long): ByteArray

    external fun sendReset(handle: Long, errorCode: Long): ByteArray

    external fun sendSetPriority(handle: Long, priority: Int): ByteArray

    external fun sendPriority(handle: Long): ByteArray

    external fun sendId(handle: Long): ByteArray

    // ── The receive half — synchronous ───────────────────────────────────────────────────────

    external fun recvStop(handle: Long, errorCode: Long): ByteArray

    external fun recvBytesRead(handle: Long): ByteArray

    external fun recvIs0Rtt(handle: Long): ByteArray

    external fun recvId(handle: Long): ByteArray

    // ── Asynchronous — each returns an operation id for Iroh4kJni.opAwait/opCancel ─────────────

    external fun openBiStart(connection: Long): Long

    external fun acceptBiStart(connection: Long): Long

    external fun openUniStart(connection: Long): Long

    external fun acceptUniStart(connection: Long): Long

    external fun sendWriteStart(handle: Long, payload: ByteArray): Long

    external fun sendWriteAllStart(handle: Long, payload: ByteArray): Long

    external fun sendStoppedStart(handle: Long): Long

    external fun recvReadStart(handle: Long, sizeLimit: Long): Long

    external fun recvReadExactStart(handle: Long, size: Long): Long

    external fun recvReadToEndStart(handle: Long, sizeLimit: Long): Long

    external fun recvReceivedResetStart(handle: Long): Long
}

/**
 * The payload of a read that may have found the end of the stream.
 *
 * The end of a stream is neither an error nor an empty array — an empty array is a real read — so the
 * two have to be distinguishable. Rust says "end of stream" with `-1` in `i64_val` and no payload,
 * which `stream.rs` documents; this is the JNI side of that, the counterpart of
 * `Stream.native.kt`'s `optionalBytesOrThrow`.
 */
private fun JniResult.optionalPayload(): ByteArray? =
    if (longValue < 0) null else bytes ?: ByteArray(0)

// ── Handle lifecycle ──────────────────────────────────────────────────────────────────────────

internal actual fun nativeStreamLiveHandleCount(): Long = StreamJni.liveHandleCount()

internal actual fun nativeStreamFree(handle: Long) = StreamJni.free(handle)

internal actual fun nativeStreamShare(handle: Long): Long = StreamJni.share(handle)

// ── The send half — synchronous ────────────────────────────────────────────────────────────────

internal actual fun nativeSendStreamFinish(handle: Long) = StreamJni.sendFinish(handle).jniOrThrow()

internal actual fun nativeSendStreamReset(handle: Long, errorCode: Long) =
    StreamJni.sendReset(handle, errorCode).jniOrThrow()

internal actual fun nativeSendStreamSetPriority(handle: Long, priority: Int) =
    StreamJni.sendSetPriority(handle, priority).jniOrThrow()

internal actual fun nativeSendStreamPriority(handle: Long): Int =
    StreamJni.sendPriority(handle).jniLongOrThrow().toInt()

internal actual fun nativeSendStreamId(handle: Long): Long =
    StreamJni.sendId(handle).jniLongOrThrow()

// ── The receive half — synchronous ─────────────────────────────────────────────────────────────

internal actual fun nativeRecvStreamStop(handle: Long, errorCode: Long) =
    StreamJni.recvStop(handle, errorCode).jniOrThrow()

internal actual fun nativeRecvStreamBytesRead(handle: Long): Long =
    StreamJni.recvBytesRead(handle).jniLongOrThrow()

internal actual fun nativeRecvStreamIs0Rtt(handle: Long): Boolean =
    StreamJni.recvIs0Rtt(handle).jniLongOrThrow() != 0L

internal actual fun nativeRecvStreamId(handle: Long): Long =
    StreamJni.recvId(handle).jniLongOrThrow()

// ── Asynchronous ──────────────────────────────────────────────────────────────────────────────
//
// All through the shared `jniOp`. The four openers pass `StreamJni::free`, because each produces a
// handle: a cancel that arrives just after the stream was opened releases it rather than stranding an
// open QUIC stream — and, through it, the connection it belongs to.

internal actual suspend fun nativeConnectionOpenBi(handle: Long): Long =
    jniOp({ StreamJni.openBiStart(handle) }, StreamJni::free) { it.handle }

internal actual suspend fun nativeConnectionAcceptBi(handle: Long): Long =
    jniOp({ StreamJni.acceptBiStart(handle) }, StreamJni::free) { it.handle }

internal actual suspend fun nativeConnectionOpenUni(handle: Long): Long =
    jniOp({ StreamJni.openUniStart(handle) }, StreamJni::free) { it.handle }

internal actual suspend fun nativeConnectionAcceptUni(handle: Long): Long =
    jniOp({ StreamJni.acceptUniStart(handle) }, StreamJni::free) { it.handle }

internal actual suspend fun nativeSendStreamWrite(handle: Long, payload: ByteArray): Long =
    jniOp({ StreamJni.sendWriteStart(handle, payload) }) { it.longValue }

internal actual suspend fun nativeSendStreamWriteAll(handle: Long, payload: ByteArray) {
    jniOp({ StreamJni.sendWriteAllStart(handle, payload) }) { }
}

internal actual suspend fun nativeSendStreamStopped(handle: Long): Long =
    jniOp({ StreamJni.sendStoppedStart(handle) }) { it.longValue }

internal actual suspend fun nativeRecvStreamRead(handle: Long, sizeLimit: Long): ByteArray? =
    jniOp({ StreamJni.recvReadStart(handle, sizeLimit) }) { it.optionalPayload() }

internal actual suspend fun nativeRecvStreamReadExact(handle: Long, size: Long): ByteArray =
    jniOp({ StreamJni.recvReadExactStart(handle, size) }) { it.payload() }

internal actual suspend fun nativeRecvStreamReadToEnd(handle: Long, sizeLimit: Long): ByteArray =
    jniOp({ StreamJni.recvReadToEndStart(handle, sizeLimit) }) { it.payload() }

internal actual suspend fun nativeRecvStreamReceivedReset(handle: Long): Long =
    jniOp({ StreamJni.recvReceivedResetStart(handle) }) { it.longValue }
