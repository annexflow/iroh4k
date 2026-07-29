package tech.annexflow.iroh4k

import tech.annexflow.iroh4k.internal.NativeHandle

/**
 * QUIC streams: [SendStream], [RecvStream] and the [BiStream] pair, plus the four ways to get one
 * from a [Connection].
 *
 * See `stream.rs` for the Rust half. Everything here holds native resources and must be released —
 * with [AutoCloseable.close], or by a `use { }` block.
 *
 * ## What a stream is
 *
 * A reliable, ordered, flow-controlled sequence of **bytes**. Not messages: the boundaries a
 * [SendStream.write] produced are not the boundaries a [RecvStream.read] sees, so a protocol that
 * needs framing has to put it in the bytes. QUIC multiplexes as many streams as wanted onto one
 * connection, they cost nothing to open, and loss on one does not delay another.
 *
 * ```kotlin
 * connection.openBi().use { stream ->
 *     stream.send.writeAll("ping".encodeToByteArray())
 *     stream.send.finish()                       // tells the peer that is all
 *     val answer = stream.recv.readToEnd(64 * 1024)
 * }
 * ```
 *
 * ## Short reads and short writes are normal
 *
 * [SendStream.write] returns how much it took, which may be less than it was given; [RecvStream.read]
 * returns what had arrived, which may be less than the limit. Both are congestion and flow control
 * doing their job, not errors. [SendStream.writeAll], [RecvStream.readExact] and
 * [RecvStream.readToEnd] are the loops over them for callers who would rather not write one.
 *
 * ## Ending a stream, three ways
 *
 * - [SendStream.finish] — "that is all the data". The peer's [RecvStream.read] then answers `null`,
 *   and its [RecvStream.readToEnd] returns. This is the graceful ending.
 * - [SendStream.reset] — "forget it". Anything still buffered is dropped and the peer's reads fail;
 *   its [RecvStream.receivedReset] reports the code.
 * - [RecvStream.stop] — "stop sending". The peer's writes fail and its [SendStream.stopped] reports
 *   the code.
 *
 * Releasing a handle without any of those does the same thing implicitly, which is legal but tells a
 * peer nothing about why.
 *
 * ## Cancellation
 *
 * Every suspending member here is cancellable, and cancelling aborts the Rust task rather than leaving
 * it running. Whether the *stream* is still usable afterwards is iroh's answer, not iroh4k's, and it
 * differs per operation: [SendStream.write], [RecvStream.read] and [RecvStream.receivedReset] are
 * cancel-safe — nothing was consumed or transmitted if they did not return — while
 * [SendStream.writeAll], [RecvStream.readExact] and [RecvStream.readToEnd] are not, and a cancelled one
 * may have moved a prefix. Each says so.
 *
 * ## Concurrency
 *
 * **Thread-safe**, as every handle-owning type here is. Operations on one half of a stream *serialise*
 * rather than interleave, which is the only coherent answer for a byte sequence — two concurrent writes
 * would otherwise produce interleaved nonsense. The two halves of a [BiStream] are independent, so
 * reading and writing at the same time is genuinely concurrent, which is what makes an echo over a
 * single stream work.
 *
 * A synchronous member ([SendStream.finish], [SendStream.reset], [RecvStream.stop], the priority and
 * id accessors) will not *block* waiting for that serialisation: if an asynchronous operation is in
 * flight on the same half it raises [IrohError] with [IrohError.Code.Write] rather than parking a
 * thread on a network round trip. Wait for the operation, then call it.
 */

// ── The send half ─────────────────────────────────────────────────────────────────────────────

/**
 * The writing end of a stream.
 *
 * From [Connection.openUni], or as [BiStream.send].
 */
class SendStream internal constructor(private val guard: NativeHandle) : AutoCloseable {

    /**
     * Writes as much of [payload] as the stream will take right now, and answers how much that was.
     *
     * A short write is the normal case rather than a problem — congestion and flow control decide how
     * much fits — so a caller either loops or uses [writeAll]. Never `0` for a non-empty [payload]:
     * this suspends until at least one byte can go.
     *
     * Cancel-safe: if it does not return, nothing was written.
     *
     * @throws IrohError with [IrohError.Code.Write] if the peer stopped the stream — the message
     *   carries the code it sent.
     * @throws IrohError with [IrohError.Code.Closed] if this half was already finished or reset, or if
     *   the connection has ended.
     */
    suspend fun write(payload: ByteArray): Long = guard.useSuspending { nativeSendStreamWrite(it, payload) }

    /**
     * Writes all of [payload], however many round trips that takes.
     *
     * **Not** cancel-safe: a cancelled `writeAll` may have transmitted a prefix, and there is no way to
     * find out how much. Use [write] in a loop when that matters.
     *
     * @throws IrohError as [write].
     */
    suspend fun writeAll(payload: ByteArray) = guard.useSuspending { nativeSendStreamWriteAll(it, payload) }

    /**
     * Tells the peer no more data will be written to this stream.
     *
     * Does not wait for what is already buffered to arrive — [stopped] is what does that — and does not
     * release the handle. After it, [write] raises; [reset] is still allowed, and abandons whatever was
     * still in flight.
     *
     * @throws IrohError with [IrohError.Code.Closed] if this half was already finished or reset. iroh
     *   documents that as harmless: it says only that the caller had the wrong idea about the state.
     */
    fun finish() = guard.use { nativeSendStreamFinish(it) }

    /**
     * Abandons this stream, handing [errorCode] to the peer.
     *
     * Anything buffered locally is dropped and nothing already sent will be retransmitted if it was
     * lost. The peer's [RecvStream.receivedReset] reports [errorCode]. Legal after [finish], which is
     * how a caller changes its mind about data it has already handed over.
     *
     * @throws IrohError with [IrohError.Code.InvalidArgument] if [errorCode] is negative or does not fit
     *   in a QUIC varint (62 bits).
     * @throws IrohError with [IrohError.Code.Closed] if this half was already finished or reset.
     */
    fun reset(errorCode: Long) = guard.use { nativeSendStreamReset(it, errorCode) }

    /**
     * Sets this stream's priority relative to the connection's other streams.
     *
     * Buffered data from higher-priority streams is transmitted first. Every stream starts at `0`.
     * Changing the priority of a stream that already has data queued may only take effect once that
     * data has gone, and using many distinct levels on one connection can cost performance.
     *
     * @throws IrohError with [IrohError.Code.Closed] if this half was already finished or reset.
     */
    fun setPriority(priority: Int) = guard.use { nativeSendStreamSetPriority(it, priority) }

    /** This stream's priority — see [setPriority]. */
    fun priority(): Int = guard.use { nativeSendStreamPriority(it) }

    /**
     * Suspends until the peer either stops this stream or has received all of it.
     *
     * Answers the peer's stop code, or `null` when it acknowledged everything after a [finish] — after
     * which it can no longer meaningfully stop the stream. So `null` is the good outcome, and the way
     * to know a finished stream really arrived.
     *
     * Note the acknowledgement is of *receipt* by the peer's QUIC stack, not of processing by its
     * application, and a peer need not acknowledge immediately — an application-level reply is a lower
     * latency way to learn the same thing.
     *
     * **This suspends indefinitely** while the peer neither stops nor finishes acknowledging.
     *
     * @throws IrohError with [IrohError.Code.Closed] if the connection ends first.
     */
    suspend fun stopped(): Long? = guard.useSuspending { nativeSendStreamStopped(it) }.takeIf { it >= 0 }

    /**
     * This stream's QUIC id, unique within the connection.
     *
     * The low bits encode which side opened it and whether it is bidirectional, which is why the ids a
     * connection hands out are not consecutive.
     */
    fun id(): Long = guard.use { nativeSendStreamId(it) }

    /** Whether [close] has released this handle, after which every other member raises. */
    val isReleased: Boolean get() = guard.isReleased

    /**
     * Releases the handle, [finish]ing the stream if it was not already finished or reset.
     *
     * That is iroh's own behaviour for dropping a `SendStream`, and it is worth knowing rather than
     * relying on: releasing a half-written stream tells the peer the data was *complete*. Call [reset]
     * first to say the opposite. (The one exception is a peer that had already stopped the stream, in
     * which case iroh resets instead.)
     *
     * Idempotent, and safe to call while other threads are inside it. For the receiving half of a
     * [BiStream] the release is deferred until both halves are gone — see [RecvStream.close].
     */
    override fun close() = guard.close()

    override fun toString(): String =
        runCatching { "SendStream(${id()})" }.getOrElse { "SendStream(released)" }
}

// ── The receive half ──────────────────────────────────────────────────────────────────────────

/**
 * The reading end of a stream.
 *
 * From [Connection.acceptUni], or as [BiStream.recv].
 */
class RecvStream internal constructor(private val guard: NativeHandle) : AutoCloseable {

    /**
     * Reads whatever has arrived, up to [sizeLimit] bytes, and answers `null` at the end of the stream.
     *
     * `null` means the peer called [SendStream.finish] and everything it sent has been read — the
     * ordinary ending, not a failure. Otherwise the array is non-empty but may be much shorter than
     * [sizeLimit]; the bytes are contiguous and in order, but the boundaries have nothing to do with
     * the peer's writes, so they cannot be used as framing.
     *
     * Cancel-safe: if it does not return, nothing was consumed.
     *
     * @throws IrohError with [IrohError.Code.InvalidArgument] if [sizeLimit] is not positive.
     * @throws IrohError with [IrohError.Code.Read] if the peer reset the stream — the message carries
     *   the code it sent.
     * @throws IrohError with [IrohError.Code.Closed] if this half was already stopped, or if the
     *   connection has ended.
     */
    suspend fun read(sizeLimit: Int): ByteArray? =
        guard.useSuspending { nativeRecvStreamRead(it, sizeLimit.toLong()) }

    /**
     * Reads exactly [size] bytes.
     *
     * The counterpart to a protocol that says how long its next piece is: a stream that ends early
     * *fails* here rather than leaving the caller waiting for bytes that will never arrive.
     *
     * **Not** cancel-safe: a cancelled `readExact` may have consumed a prefix of the stream.
     *
     * @throws IrohError with [IrohError.Code.InvalidArgument] if [size] is not positive.
     * @throws IrohError with [IrohError.Code.Read] if the stream finished before [size] bytes arrived,
     *   or if the peer reset it.
     * @throws IrohError with [IrohError.Code.Closed] as [read].
     */
    suspend fun readExact(size: Int): ByteArray =
        guard.useSuspending { nativeRecvStreamReadExact(it, size.toLong()) }

    /**
     * Reads until the peer finishes the stream, refusing to buffer more than [sizeLimit] bytes.
     *
     * The limit is not a nicety: without one, a peer decides how much memory this side allocates.
     * Exceeding it fails rather than truncating, because a silently truncated stream is worse than a
     * reported one.
     *
     * **Not** cancel-safe, as [readExact].
     *
     * @throws IrohError with [IrohError.Code.InvalidArgument] if [sizeLimit] is not positive.
     * @throws IrohError with [IrohError.Code.Read] if the stream is longer than [sizeLimit], or if the
     *   peer reset it.
     * @throws IrohError with [IrohError.Code.Closed] as [read].
     */
    suspend fun readToEnd(sizeLimit: Int): ByteArray =
        guard.useSuspending { nativeRecvStreamReadToEnd(it, sizeLimit.toLong()) }

    /**
     * Stops accepting data, discarding what was unread and telling the peer [errorCode].
     *
     * The peer's writes then fail and its [SendStream.stopped] reports [errorCode]. A [read] afterwards
     * answers `null` rather than raising: from this side's point of view there is no more data, which is
     * what it asked for.
     *
     * @throws IrohError with [IrohError.Code.InvalidArgument] if [errorCode] is negative or does not fit
     *   in a QUIC varint (62 bits).
     * @throws IrohError with [IrohError.Code.Closed] if the stream was already stopped or finished.
     */
    fun stop(errorCode: Long) = guard.use { nativeRecvStreamStop(it, errorCode) }

    /**
     * Suspends until the peer resets this stream, and answers the code it sent.
     *
     * `null` for the two endings that make a reset meaningless afterwards: this side called [stop], or
     * the peer finished the stream and all of it has been read.
     *
     * **This suspends indefinitely** while the stream is simply open. Cancel-safe.
     *
     * @throws IrohError with [IrohError.Code.Closed] if the connection ends first.
     */
    suspend fun receivedReset(): Long? =
        guard.useSuspending { nativeRecvStreamReceivedReset(it) }.takeIf { it >= 0 }

    /**
     * How many bytes of this stream have been consumed.
     *
     * The offset of the next byte to be read, so it counts what [read], [readExact] and [readToEnd]
     * took — not what has arrived and is still buffered.
     *
     * @throws IrohError with [IrohError.Code.Closed] if the stream was stopped, or read to its end.
     */
    fun bytesRead(): Long = guard.use { nativeRecvStreamBytesRead(it) }

    /**
     * Whether this side obtained this stream while its handshake was still in progress.
     *
     * `true` means the data this stream carries arrived as 0-RTT early data, before the handshake
     * vouched for the peer's identity — it **is vulnerable to replay attacks and must never drive
     * non-idempotent work** until the connection it belongs to has authenticated. See [ZeroRttStatus]
     * for the same hazard on the connection as a whole.
     *
     * The flag is decided once and only ever read back afterwards — for a stream from
     * [Connection.acceptBi]/[Connection.acceptUni], at the moment this side *accepted* it; for one
     * from [Connection.openBi], at the moment this side *opened* it. Either way `noq` samples whether
     * the handshake was still running right then, not when the stream was first created on the wire.
     * For the accepting case in particular that timing means **`false` does not prove the data was not
     * sent early**: the peer can write to a stream before its own handshake completes, and if this side
     * does not get around to accepting it until after its own handshake has settled, this reports
     * `false` for data that genuinely raced the handshake. It only proves this side accepted the stream
     * once its own handshake had already settled — which on a fast loopback connection is the common
     * case, so most ordinary streams read `false` even when nothing was ever rejected.
     *
     * Unlike [bytesRead], reading this never fails because the stream was stopped or finished — `noq`
     * remembers the flag on the `RecvStream` value itself rather than asking the connection for it, so
     * the only way to raise [IrohError.Code.Closed] here is calling it after [close].
     */
    fun is0Rtt(): Boolean = guard.use { nativeRecvStreamIs0Rtt(it) }

    /** This stream's QUIC id — see [SendStream.id]. For a [BiStream] it is the same id on both halves. */
    fun id(): Long = guard.use { nativeRecvStreamId(it) }

    /** Whether [close] has released this handle, after which every other member raises. */
    val isReleased: Boolean get() = guard.isReleased

    /**
     * Releases the handle.
     *
     * Releasing a receive half tells the peer to stop sending, which is what dropping iroh's own
     * `RecvStream` does. For the receiving half of a [BiStream] that only happens once *both* halves are
     * released — see `stream.rs` on why they share one native payload — so call [stop] to say it sooner.
     * Idempotent.
     */
    override fun close() = guard.close()

    override fun toString(): String =
        runCatching { "RecvStream(${id()})" }.getOrElse { "RecvStream(released)" }
}

// ── The pair ──────────────────────────────────────────────────────────────────────────────────

/**
 * Both halves of a bidirectional stream, from [Connection.openBi] or [Connection.acceptBi].
 *
 * Two independent halves rather than one read-write object, because that is what QUIC has: [send] and
 * [recv] are separately flow-controlled, separately ended, and can be used at the same time from
 * different coroutines. They do share a stream [id].
 *
 * Closing this closes both. Closing one half individually is allowed and leaves the other usable.
 */
class BiStream internal constructor(
    /** The half this side writes to. */
    val send: SendStream,
    /** The half this side reads from. */
    val recv: RecvStream,
) : AutoCloseable {

    /** This stream's QUIC id, shared by both halves. */
    fun id(): Long = send.id()

    /** Whether both halves have been released. */
    val isReleased: Boolean get() = send.isReleased && recv.isReleased

    /**
     * Releases both halves. Idempotent, and independent of releasing either one directly.
     *
     * Does not [SendStream.finish] first: a caller who wants the peer to see a graceful ending has to
     * say so, because iroh4k cannot tell whether the data was complete.
     */
    override fun close() {
        // Both, even if the first throws — a half-released pair would leak the other handle. Neither
        // `close()` can actually throw today, and this costs nothing if that ever changes.
        try {
            send.close()
        } finally {
            recv.close()
        }
    }

    override fun toString(): String =
        runCatching { "BiStream(${id()})" }.getOrElse { "BiStream(released)" }
}

// ── Test hooks ────────────────────────────────────────────────────────────────────────────────

/**
 * Stream handles still alive in Rust.
 *
 * Exposed for tests, as [Connection.liveHandleCount] is, and counted separately from it: a stream
 * leaked by an open/close loop has to be visible even while connections come and go around it. It must
 * return to its baseline once every stream is closed.
 *
 * Counts *streams*, not handles: a [BiStream] holds two handles over one native payload — see
 * `stream.rs` — and moves this by one, dropping back only once both halves are released.
 */
internal object Streams {
    val liveHandleCount: Long get() = nativeStreamLiveHandleCount()
}

// ── The connection's stream entry points ───────────────────────────────────────────────────────
//
// Extensions rather than members of `QuicConnection`, because everything they produce belongs to this
// domain: all they need from a connection is its handle, which `QuicConnection.withHandle` lends under
// the connection's own guard. So closing a connection while an `acceptBi` is suspended is as safe as
// closing it while `closed()` is — the handle survives until the call returns.

/**
 * Opens a bidirectional stream. Works on any connection, 0-RTT included.
 *
 * Returns as soon as QUIC has an id for it — no round trip is involved, and the peer does not learn the
 * stream exists until data is written to it. Which means an [openBi] followed by nothing will never
 * appear at the peer's [acceptBi]; write something, even one byte.
 *
 * Suspends only while the connection is at its bidirectional stream limit and the peer has not yet
 * allowed more; see [Connection.setMaxConcurrentBiStreams] for the other side of that.
 *
 * @throws IrohError with [IrohError.Code.Closed] if the connection has ended or its handle has been
 *   released.
 */
suspend fun QuicConnection.openBi(): BiStream = withHandle { handle ->
    biStream(nativeConnectionOpenBi(handle))
}

/**
 * Suspends until the peer opens a bidirectional stream, and answers with it. Works on any connection,
 * 0-RTT included.
 *
 * **This suspends indefinitely** while the peer opens none. Cancellation ends it and aborts the Rust
 * task; a stream that had just been accepted is released rather than stranded.
 *
 * @throws IrohError with [IrohError.Code.Closed] when the connection ends, which is how an accept loop
 *   terminates.
 */
suspend fun QuicConnection.acceptBi(): BiStream = withHandle { handle ->
    biStream(nativeConnectionAcceptBi(handle))
}

/**
 * Opens a unidirectional stream, which this side can only write to. Works on any connection, 0-RTT
 * included.
 *
 * As [openBi] in every other respect, including that the peer learns nothing until data is written.
 *
 * @throws IrohError with [IrohError.Code.Closed] as [openBi].
 */
suspend fun QuicConnection.openUni(): SendStream = withHandle { handle ->
    SendStream(NativeHandle(nativeConnectionOpenUni(handle), SEND_STREAM, ::nativeStreamFree))
}

/**
 * Suspends until the peer opens a unidirectional stream, and answers with the end this side reads.
 * Works on any connection, 0-RTT included.
 *
 * @throws IrohError with [IrohError.Code.Closed] as [acceptBi].
 */
suspend fun QuicConnection.acceptUni(): RecvStream = withHandle { handle ->
    RecvStream(NativeHandle(nativeConnectionAcceptUni(handle), RECV_STREAM, ::nativeStreamFree))
}

/**
 * Wraps one native stream handle as a [BiStream].
 *
 * The two halves need independent lifetimes, and Rust hands back a single handle — because an operation
 * that produced two could strand the second if a cancel won the deliver-once race. So a second handle
 * to the same payload is taken here with [nativeStreamShare], which is an `Arc` refcount bump and
 * cannot fail. `stream.rs` documents the whole arrangement.
 */
private fun biStream(handle: Long): BiStream = BiStream(
    send = SendStream(NativeHandle(handle, SEND_STREAM, ::nativeStreamFree)),
    recv = RecvStream(NativeHandle(nativeStreamShare(handle), RECV_STREAM, ::nativeStreamFree)),
)

/** What each half calls itself in an [IrohError.Code.Closed] message. */
private const val SEND_STREAM = "send stream"
private const val RECV_STREAM = "receive stream"

// ── The stream domain's FFI surface, implemented per facade ─────────────────────────────────────
//
// Names are prefixed `nativeStream`/`nativeSendStream`/`nativeRecvStream` so this domain's expect
// declarations stay distinct in the shared package. The four openers keep the `nativeConnection` prefix
// of the handle they are given, since that is whose handle it is; the operations themselves live in
// `stream.rs`.

internal expect fun nativeStreamLiveHandleCount(): Long

internal expect fun nativeStreamFree(handle: Long)

/** A second handle to the same native stream payload. Cannot fail; `0` only for a `0` input. */
internal expect fun nativeStreamShare(handle: Long): Long

internal expect fun nativeSendStreamFinish(handle: Long)

internal expect fun nativeSendStreamReset(handle: Long, errorCode: Long)

internal expect fun nativeSendStreamSetPriority(handle: Long, priority: Int)

internal expect fun nativeSendStreamPriority(handle: Long): Int

internal expect fun nativeSendStreamId(handle: Long): Long

internal expect fun nativeRecvStreamStop(handle: Long, errorCode: Long)

internal expect fun nativeRecvStreamBytesRead(handle: Long): Long

internal expect fun nativeRecvStreamIs0Rtt(handle: Long): Boolean

internal expect fun nativeRecvStreamId(handle: Long): Long

/** Returns the handle of the stream this produced. */
internal expect suspend fun nativeConnectionOpenBi(handle: Long): Long

internal expect suspend fun nativeConnectionAcceptBi(handle: Long): Long

internal expect suspend fun nativeConnectionOpenUni(handle: Long): Long

internal expect suspend fun nativeConnectionAcceptUni(handle: Long): Long

/** Returns how many bytes of `payload` were taken. */
internal expect suspend fun nativeSendStreamWrite(handle: Long, payload: ByteArray): Long

internal expect suspend fun nativeSendStreamWriteAll(handle: Long, payload: ByteArray)

/** The peer's stop code, or `-1` when it received the whole stream instead. */
internal expect suspend fun nativeSendStreamStopped(handle: Long): Long

/** `null` at the end of the stream, which Rust reports as `-1` in `i64_val` and no payload. */
internal expect suspend fun nativeRecvStreamRead(handle: Long, sizeLimit: Long): ByteArray?

internal expect suspend fun nativeRecvStreamReadExact(handle: Long, size: Long): ByteArray

internal expect suspend fun nativeRecvStreamReadToEnd(handle: Long, sizeLimit: Long): ByteArray

/** The peer's reset code, or `-1` when the stream ended in a way a reset can no longer follow. */
internal expect suspend fun nativeRecvStreamReceivedReset(handle: Long): Long
