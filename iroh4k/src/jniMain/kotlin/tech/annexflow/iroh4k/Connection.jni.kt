package tech.annexflow.iroh4k

/**
 * Raw JNI entry points for the connection domain.
 *
 * A separate object from [Iroh4kJni] and [EndpointJni] so this domain owns its own symbols, beside
 * the Rust exports in `connection.rs` that implement them. The symbol names are part of the ABI: they
 * must match the `Java_tech_annexflow_iroh4k_ConnectionJni_*` exports there.
 *
 * The split follows iroh's own shape rather than a rule of thumb. Everything on [Incoming] is
 * synchronous, because every method of iroh's `Incoming` is; the handshake operations are split into
 * **start / await / cancel** as [Iroh4kJni] describes, because a single blocking call would pin a
 * `Dispatchers.IO` thread with nothing able to interrupt it — and [acceptNextStart] in particular may
 * never finish on its own.
 *
 * Handles cross as `Long`, which is the only pointer-sized integer the JVM has.
 */
internal object ConnectionJni {
    init {
        // Each JNI object must ensure the shared library is loaded before its first `external` call;
        // the loader itself is idempotent, so objects do not depend on each other's order.
        ensureNativeLoaded()
    }

    // ── Handle lifecycle ─────────────────────────────────────────────────────────────────────

    external fun liveHandleCount(): Long

    external fun freeIncoming(handle: Long)

    external fun freeAccepting(handle: Long)

    external fun freeConnecting(handle: Long)

    external fun freeConnection(handle: Long)

    // ── Incoming — synchronous ───────────────────────────────────────────────────────────────

    external fun incomingAccept(handle: Long): ByteArray

    external fun incomingRefuse(handle: Long): ByteArray

    external fun incomingRetry(handle: Long): ByteArray

    external fun incomingIgnore(handle: Long): ByteArray

    external fun incomingLocalAddr(handle: Long): ByteArray

    external fun incomingRemoteAddr(handle: Long): ByteArray

    external fun incomingRemoteAddrValidated(handle: Long): ByteArray

    // ── Connecting and Connection — synchronous ──────────────────────────────────────────────

    external fun connectingRemoteId(handle: Long): ByteArray

    external fun connectionAlpn(handle: Long): ByteArray

    external fun connectionRemoteId(handle: Long): ByteArray

    external fun connectionStableId(handle: Long): ByteArray

    external fun connectionCloseReason(handle: Long): ByteArray

    external fun connectionClose(handle: Long, errorCode: Long, reason: ByteArray): ByteArray

    external fun connectionSide(handle: Long): ByteArray

    external fun connectionRtt(handle: Long): ByteArray

    external fun connectionStats(handle: Long): ByteArray

    external fun connectionPaths(handle: Long): ByteArray

    external fun connectionMaxDatagramSize(handle: Long): ByteArray

    external fun connectionDatagramSendBufferSpace(handle: Long): ByteArray

    external fun connectionSendDatagram(handle: Long, payload: ByteArray): ByteArray

    external fun connectionSetMaxConcurrentBiStreams(handle: Long, count: Long): ByteArray

    external fun connectionSetMaxConcurrentUniStreams(handle: Long, count: Long): ByteArray

    external fun connectionSetReceiveWindow(handle: Long, bytes: Long): ByteArray

    // ── Asynchronous — each returns an operation id for Iroh4kJni.opAwait/opCancel ─────────────

    external fun acceptNextStart(endpoint: Long): Long

    external fun startConnectStart(endpoint: Long, addr: ByteArray, alpn: ByteArray): Long

    external fun connectStart(endpoint: Long, addr: ByteArray, alpn: ByteArray): Long

    external fun acceptingConnectStart(handle: Long): Long

    external fun acceptingAlpnStart(handle: Long): Long

    external fun connectingConnectStart(handle: Long): Long

    external fun connectingAlpnStart(handle: Long): Long

    external fun connectionClosedStart(handle: Long): Long

    external fun connectionSendDatagramWaitStart(handle: Long, payload: ByteArray): Long

    external fun connectionReadDatagramStart(handle: Long): Long
}

/**
 * The `handle` field of a result envelope, raising on failure.
 *
 * `JniResult.kt` has the readers for the other fields; this one is here because until this domain
 * nothing produced a handle from an operation. It belongs beside them.
 *
 * `0` for a null handle, which is not an error: it is how `acceptNext` reports iroh's `None` for an
 * endpoint that has been shut down.
 */
private fun ByteArray.jniHandleOrThrow(): Long = decodeResult().let {
    it.throwIfError()
    it.handle
}

// ── Handle lifecycle ──────────────────────────────────────────────────────────────────────────

internal actual fun nativeConnectionLiveHandleCount(): Long = ConnectionJni.liveHandleCount()

internal actual fun nativeIncomingFree(handle: Long) = ConnectionJni.freeIncoming(handle)

internal actual fun nativeAcceptingFree(handle: Long) = ConnectionJni.freeAccepting(handle)

internal actual fun nativeConnectingFree(handle: Long) = ConnectionJni.freeConnecting(handle)

internal actual fun nativeConnectionFree(handle: Long) = ConnectionJni.freeConnection(handle)

// ── Incoming — synchronous ────────────────────────────────────────────────────────────────────

internal actual fun nativeIncomingAccept(handle: Long): Long =
    ConnectionJni.incomingAccept(handle).jniHandleOrThrow()

internal actual fun nativeIncomingRefuse(handle: Long) =
    ConnectionJni.incomingRefuse(handle).jniOrThrow()

internal actual fun nativeIncomingRetry(handle: Long) =
    ConnectionJni.incomingRetry(handle).jniOrThrow()

internal actual fun nativeIncomingIgnore(handle: Long) =
    ConnectionJni.incomingIgnore(handle).jniOrThrow()

internal actual fun nativeIncomingLocalAddr(handle: Long): ByteArray =
    ConnectionJni.incomingLocalAddr(handle).jniBytesOrThrow()

internal actual fun nativeIncomingRemoteAddr(handle: Long): ByteArray =
    ConnectionJni.incomingRemoteAddr(handle).jniBytesOrThrow()

internal actual fun nativeIncomingRemoteAddrValidated(handle: Long): Boolean =
    ConnectionJni.incomingRemoteAddrValidated(handle).jniLongOrThrow() != 0L

// ── Connecting and Connection — synchronous ───────────────────────────────────────────────────

internal actual fun nativeConnectingRemoteId(handle: Long): ByteArray =
    ConnectionJni.connectingRemoteId(handle).jniBytesOrThrow()

internal actual fun nativeConnectionAlpn(handle: Long): ByteArray =
    ConnectionJni.connectionAlpn(handle).jniBytesOrThrow()

internal actual fun nativeConnectionRemoteId(handle: Long): ByteArray =
    ConnectionJni.connectionRemoteId(handle).jniBytesOrThrow()

internal actual fun nativeConnectionStableId(handle: Long): Long =
    ConnectionJni.connectionStableId(handle).jniLongOrThrow()

internal actual fun nativeConnectionCloseReason(handle: Long): ByteArray =
    ConnectionJni.connectionCloseReason(handle).jniBytesOrThrow()

internal actual fun nativeConnectionClose(handle: Long, errorCode: Long, reason: ByteArray) =
    ConnectionJni.connectionClose(handle, errorCode, reason).jniOrThrow()

internal actual fun nativeConnectionSide(handle: Long): Long =
    ConnectionJni.connectionSide(handle).jniLongOrThrow()

internal actual fun nativeConnectionRtt(handle: Long): Long =
    ConnectionJni.connectionRtt(handle).jniLongOrThrow()

internal actual fun nativeConnectionStats(handle: Long): ByteArray =
    ConnectionJni.connectionStats(handle).jniBytesOrThrow()

internal actual fun nativeConnectionPaths(handle: Long): ByteArray =
    ConnectionJni.connectionPaths(handle).jniBytesOrThrow()

internal actual fun nativeConnectionMaxDatagramSize(handle: Long): Long =
    ConnectionJni.connectionMaxDatagramSize(handle).jniLongOrThrow()

internal actual fun nativeConnectionDatagramSendBufferSpace(handle: Long): Long =
    ConnectionJni.connectionDatagramSendBufferSpace(handle).jniLongOrThrow()

internal actual fun nativeConnectionSendDatagram(handle: Long, payload: ByteArray) =
    ConnectionJni.connectionSendDatagram(handle, payload).jniOrThrow()

internal actual fun nativeConnectionSetMaxConcurrentBiStreams(handle: Long, count: Long) =
    ConnectionJni.connectionSetMaxConcurrentBiStreams(handle, count).jniOrThrow()

internal actual fun nativeConnectionSetMaxConcurrentUniStreams(handle: Long, count: Long) =
    ConnectionJni.connectionSetMaxConcurrentUniStreams(handle, count).jniOrThrow()

internal actual fun nativeConnectionSetReceiveWindow(handle: Long, bytes: Long) =
    ConnectionJni.connectionSetReceiveWindow(handle, bytes).jniOrThrow()

// ── Asynchronous ──────────────────────────────────────────────────────────────────────────────
//
// All through the shared `jniOp`, which starts the operation, awaits it on `Dispatchers.IO` inside an
// `async` and cancels it in Rust if the caller's coroutine is cancelled. Writing the await with
// `withContext` instead would deadlock — see the note on `jniOp`.

internal actual suspend fun nativeEndpointAcceptNext(handle: Long): Long =
    jniOp({ ConnectionJni.acceptNextStart(handle) }, ConnectionJni::freeIncoming) { it.handle }

internal actual suspend fun nativeEndpointStartConnect(
    handle: Long,
    addr: ByteArray,
    alpn: ByteArray,
): Long = jniOp(
    { ConnectionJni.startConnectStart(handle, addr, alpn) },
    ConnectionJni::freeConnecting,
) { it.handle }

internal actual suspend fun nativeAcceptingConnect(handle: Long): Long =
    jniOp({ ConnectionJni.acceptingConnectStart(handle) }, ConnectionJni::freeConnection) { it.handle }

internal actual suspend fun nativeAcceptingAlpn(handle: Long): ByteArray =
    jniOp({ ConnectionJni.acceptingAlpnStart(handle) }) { it.payload() }

internal actual suspend fun nativeConnectingConnect(handle: Long): Long =
    jniOp({ ConnectionJni.connectingConnectStart(handle) }, ConnectionJni::freeConnection) { it.handle }

internal actual suspend fun nativeConnectingAlpn(handle: Long): ByteArray =
    jniOp({ ConnectionJni.connectingAlpnStart(handle) }) { it.payload() }

internal actual suspend fun nativeEndpointConnect(
    handle: Long,
    addr: ByteArray,
    alpn: ByteArray,
): Long = jniOp(
    { ConnectionJni.connectStart(handle, addr, alpn) },
    ConnectionJni::freeConnection,
) { it.handle }

internal actual suspend fun nativeConnectionClosed(handle: Long): String =
    jniOp({ ConnectionJni.connectionClosedStart(handle) }) { it.payload().decodeToString() }

internal actual suspend fun nativeConnectionSendDatagramWait(handle: Long, payload: ByteArray) {
    jniOp({ ConnectionJni.connectionSendDatagramWaitStart(handle, payload) }) { }
}

internal actual suspend fun nativeConnectionReadDatagram(handle: Long): ByteArray =
    jniOp({ ConnectionJni.connectionReadDatagramStart(handle) }) { it.payload() }
