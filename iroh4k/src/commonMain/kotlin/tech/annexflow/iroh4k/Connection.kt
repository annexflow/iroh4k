package tech.annexflow.iroh4k

import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import tech.annexflow.iroh4k.internal.BinaryReader
import tech.annexflow.iroh4k.internal.BinaryWriter
import tech.annexflow.iroh4k.internal.NativeHandle

/**
 * The accept side: [Endpoint.acceptNext] → [Incoming] → [Accepting] → [Connection], plus
 * [Connecting] for the outbound half of the same handshake.
 *
 * See `connection.rs` for the Rust half. Everything here holds native resources and must be released
 * — with [AutoCloseable.close], or by a `use { }` block.
 *
 * ## Consumed once
 *
 * This is the first domain whose objects are *used up*. In iroh, `Incoming::accept`, `refuse`,
 * `retry` and `ignore` all take `self`, and `Accepting`/`Connecting` are futures, so awaiting them
 * consumes them too. iroh4k keeps that: [Incoming.accept], [Incoming.refuse], [Incoming.retry] and
 * [Incoming.ignore] are mutually exclusive and each may be called **once**, and so may
 * [Accepting.connect] and [Connecting.connect].
 *
 * A second call raises [IrohError] with [IrohError.Code.Closed], and so does an inspector
 * ([Incoming.localAddr], [Incoming.remoteAddr], [Incoming.remoteAddrValidated]) called after the
 * value is gone. That is a deliberate error rather than a crash: the Rust crate aborts on panic, so
 * "already consumed" has to be a value on the way back and never an assertion.
 *
 * Consuming an object does **not** release its handle — `close()` is still the single release point,
 * exactly as for [Endpoint]. So the full chain is three nested `use` blocks:
 *
 * ```kotlin
 * val connection = endpoint.acceptNext()?.use { incoming ->
 *     incoming.accept().use { accepting -> accepting.connect() }
 * }
 * ```
 *
 * ## What is not here yet
 *
 * [Connection.paths] is the snapshot form; the streaming form lives in `Watch.kt` as
 * `Connection.watchPaths()` and `Connection.watchPathEvents()`. 0-RTT is below, both sides:
 * [Connecting.zeroRtt] and [OutgoingZeroRttConnection] for the dialling side, [Accepting.zeroRtt] and
 * [IncomingZeroRttConnection] for the accepting one.
 */

// ── Addresses ─────────────────────────────────────────────────────────────────────────────────

/**
 * Where an inbound connection attempt came from, mirroring `iroh::endpoint::IncomingAddr`.
 *
 * A value type, like [TransportAddr], and modelled faithfully for the same reason: a binding that
 * flattens an address into a string is a binding that loses information without saying so.
 *
 * Note this is *not* a [TransportAddr], and the difference is [Relay]: for a relayed connection iroh
 * knows the remote's [EndpointId] as well as the relay it came through, which a
 * [TransportAddr.Relay] has nowhere to put.
 */
sealed interface IncomingAddr {

    /** Arrived through a relay, which also reveals who sent it. */
    data class Relay(val url: RelayUrl, val endpointId: EndpointId) : IncomingAddr {
        override fun toString(): String = "relay:$url/$endpointId"
    }

    /** Arrived directly from an IP address and port. */
    data class Ip(val addr: SocketAddr) : IncomingAddr {
        override fun toString(): String = "ip:$addr"
    }

    /** Arrived over a transport iroh itself does not implement. */
    data class Custom(val addr: CustomAddr) : IncomingAddr {
        override fun toString(): String = "custom:$addr"
    }

    /**
     * An address kind this build of iroh4k does not know, preserved for inspection.
     *
     * `IncomingAddr` is `#[non_exhaustive]` upstream, so a newer iroh can introduce a variant this
     * build has never heard of. It is surfaced rather than dropped — the same choice [TransportAddr]
     * makes — except that `IncomingAddr` has no `Display`, so [description] is iroh's `Debug` text.
     */
    data class Unknown(val description: String) : IncomingAddr {
        override fun toString(): String = "unknown:$description"
    }
}

/**
 * The local end of a network path, mirroring `iroh::endpoint::LocalTransportAddr`.
 *
 * Two of the variants are optional, and both are iroh's own: the operating system does not always
 * report which local address a datagram arrived on, and a custom transport need not report one
 * either.
 */
sealed interface LocalTransportAddr {

    /** The relay this path runs over. */
    data class Relay(val url: RelayUrl) : LocalTransportAddr {
        override fun toString(): String = "relay:$url"
    }

    /**
     * The local interface address, if the OS surfaced it.
     *
     * An IP address without a port — which is why it is a [String] rather than a [SocketAddr]: iroh
     * reports the interface, not the socket. `null` means the OS did not say.
     */
    data class Ip(val ip: String?) : LocalTransportAddr {
        override fun toString(): String = "ip:${ip ?: "unknown"}"
    }

    /** The local custom address, if the transport reports one. */
    data class Custom(val addr: CustomAddr?) : LocalTransportAddr {
        override fun toString(): String = "custom:${addr ?: "unknown"}"
    }

    /** An address kind this build does not know — see [IncomingAddr.Unknown]. */
    data class Unknown(val description: String) : LocalTransportAddr {
        override fun toString(): String = "unknown:$description"
    }
}

// ── Statistics and paths ──────────────────────────────────────────────────────────────────────

/** Which end of a connection this one is. */
enum class ConnectionSide {
    /** This endpoint dialled. */
    Client,

    /** This endpoint accepted. */
    Server,
    ;

    internal companion object {
        /** The ordinals are the wire protocol shared with `connection.rs`'s `connection_side`. */
        fun of(value: Long): ConnectionSide = if (value == 0L) Client else Server
    }
}

/**
 * UDP datagrams and bytes moved in one direction, mirroring `noq::UdpStats`.
 *
 * Every QUIC packet rides in a UDP datagram, so these cover all traffic — stream data, datagrams,
 * acknowledgements and handshake alike — rather than only what the application sent.
 *
 * iroh's `ios` field is not carried: upstream deprecated it and documents it as always zero.
 */
data class UdpStats(
    /** Datagrams observed. */
    val datagrams: Long,
    /** Bytes carried inside those datagrams. */
    val bytes: Long,
)

/**
 * A connection's totals, mirroring `noq::ConnectionStats` and summed over every path it has used.
 *
 * The per-frame-type counters iroh's `FrameStats` carries are deliberately left out — see
 * `connection.rs`, which explains why. What is here is what an application measures itself against.
 */
data class ConnectionStats(
    /** Sent. */
    val udpTx: UdpStats,
    /** Received. */
    val udpRx: UdpStats,
    /** Packets the peer never acknowledged and that had to be retransmitted. */
    val lostPackets: Long,
    /** Bytes in those packets. */
    val lostBytes: Long,
)

/**
 * One open network path, as it was at the moment [Connection.paths] was called.
 *
 * A connection usually has a relayed path and, once hole punching succeeds, a direct one; [isSelected]
 * says which of them application data is currently going over. Paths that have already closed are not
 * in the snapshot, and a snapshot never changes after it is taken.
 */
data class PathSnapshot(
    /**
     * iroh's identifier for this path within the connection.
     *
     * `-1` if this build could not read it. noq keeps the accessor for the number crate-private, so
     * it is recovered from the documented `Display` form — see `write_path` in `connection.rs`.
     */
    val pathId: Long,
    /** The remote end. */
    val remoteAddr: TransportAddr,
    /** The local end, which the OS does not always report. */
    val localAddr: LocalTransportAddr,
    /** Whether application data is currently being transmitted over this path. */
    val isSelected: Boolean,
    /** Current round-trip-time estimate for this path. */
    val rtt: Duration,
    /** Current congestion window, in bytes. */
    val congestionWindow: Long,
    /** Largest UDP payload this path currently supports, in bytes. */
    val currentMtu: Int,
    /** Packets lost on this path. */
    val lostPackets: Long,
    /** Bytes lost on this path. */
    val lostBytes: Long,
    /** Congestion events observed on this path. */
    val congestionEvents: Long,
    /** Sent over this path. */
    val udpTx: UdpStats,
    /** Received over this path. */
    val udpRx: UdpStats,
) {
    /** `relay` or `ip` and the remote address, plus a marker for the selected path. */
    override fun toString(): String =
        "Path($pathId, $remoteAddr, rtt=$rtt${if (isSelected) ", selected" else ""})"
}

// ── Incoming ──────────────────────────────────────────────────────────────────────────────────

/**
 * An inbound connection attempt that has not been answered yet.
 *
 * Produced by [Endpoint.acceptNext]. Exactly one of [accept], [acceptWith], [refuse], [retry] and
 * [ignore] decides what happens to it; the inspectors below are what that decision can be based on.
 *
 * **Thread-safe**, as [Endpoint] is: any member may be called from any thread or coroutine,
 * including concurrently with [close], and a released handle is never dereferenced.
 *
 * Releasing an `Incoming` that was never answered *refuses* the connection, because that is what
 * dropping an `Incoming` means in iroh.
 */
class Incoming internal constructor(
    private val guard: NativeHandle,
    private val endpoint: Endpoint,
) : AutoCloseable {

    /**
     * Begins this endpoint's half of the handshake, yielding an [Accepting] to await.
     *
     * Consumes this incoming connection: every later [accept], [acceptWith], [refuse], [retry] or
     * [ignore] raises [IrohError] with [IrohError.Code.Closed].
     *
     * @throws IrohError with [IrohError.Code.Accept] if iroh refuses the attempt. That is routine
     *   rather than alarming: a QUIC endpoint listens on an ordinary UDP socket, anything on the
     *   network can send it datagrams that merely look like QUIC, and the accepting endpoint stays
     *   perfectly usable afterwards. iroh documents these as errors to log.
     * @throws IrohError with [IrohError.Code.Closed] if this incoming connection was already used.
     */
    fun accept(): Accepting = guard.use { handle ->
        Accepting(NativeHandle(nativeIncomingAccept(handle), ACCEPTING, ::nativeAcceptingFree))
    }

    /**
     * Begins this endpoint's half of the handshake, offering [alpns] for this connection alone
     * and a transport configuration for it too, yielding an [Accepting] to await.
     *
     * Consumes this incoming connection, exactly as [accept] does: every later [accept],
     * [acceptWith], [refuse], [retry] or [ignore] raises [IrohError] with [IrohError.Code.Closed].
     * The one exception is [alpns] itself: an empty list is rejected before this incoming
     * connection is touched at all, so it is still usable afterwards — see below.
     *
     * [transportConfig] applies to this connection alone — it changes nothing about any other
     * connection this endpoint accepts or dials. It **replaces** the endpoint's own
     * [EndpointConfig.transportConfig] outright rather than merging with it, exactly as
     * [Endpoint.startConnect]'s does: setting one field here does not inherit the other 28 from
     * whatever the endpoint was given. `null`, the default, uses the endpoint's — the fresh
     * `ServerConfig` [alpns] requires is built with the endpoint's own transport configuration
     * already baked in (`create_server_config`), and [transportConfig] either leaves that starting
     * point alone or replaces it wholesale, the same choice [Endpoint.startConnect] offers a dial.
     *
     * [alpns] is *not* the protocol this connection negotiated — nothing has been negotiated yet at
     * this stage; [alpns] is what determines what still can be. Accepting with a configuration means
     * building a fresh `ServerConfig` through the endpoint's own `create_server_config_builder`, and
     * that builder has no way to add an ALPN afterwards — its list is fixed at creation, replacing
     * whatever the endpoint itself advertises for every other connection. A list with no protocol the
     * peer actually offered does **not** fail silently — established empirically, not assumed: it
     * fails exactly as loudly as an endpoint-wide ALPN mismatch does, raising [IrohError] with
     * [IrohError.Code.Accept] from this call itself, before an [Accepting] is ever produced.
     * `emptyList()` is not a special "accept anything" case either: an empty list can never overlap
     * with anything a peer could offer, so accepting with one would fail that same way on every
     * single call — which is why it is refused up front instead.
     *
     * @throws IrohError with [IrohError.Code.Accept] if iroh refuses the attempt — [alpns] holding no
     *   protocol the peer actually offered, or any of the reasons [accept] describes.
     * @throws IrohError with [IrohError.Code.Closed] if this incoming connection was already used,
     *   or if the endpoint it came from has been released.
     * @throws IrohError with [IrohError.Code.InvalidArgument] if [alpns] is empty, or if
     *   [transportConfig] holds a value iroh refuses — a negative duration, say.
     */
    fun acceptWith(
        alpns: List<ByteArray>,
        transportConfig: TransportConfig? = null,
    ): Accepting {
        if (alpns.isEmpty()) {
            IrohError(
                IrohError.Code.InvalidArgument,
                "acceptWith requires at least one ALPN: an empty list can never overlap with " +
                    "anything a peer offers, so every connection accepted this way would be refused",
            ).raise()
        }
        return guard.use { handle ->
            val w = BinaryWriter()
            w.i32(alpns.size)
            for (alpn in alpns) w.bytes(alpn)
            w.writeOptionalTransportConfig(transportConfig)
            Accepting(
                NativeHandle(
                    endpoint.useHandle { ep -> nativeIncomingAcceptWith(handle, ep, w.finish()) },
                    ACCEPTING,
                    ::nativeAcceptingFree,
                )
            )
        }
    }

    /**
     * Rejects the attempt, telling the peer so.
     *
     * Consumes this incoming connection, as [accept] does.
     */
    fun refuse() = guard.use { nativeIncomingRefuse(it) }

    /**
     * Asks the peer to retry with address validation, which is how a flood of spoofed-source
     * attempts is made expensive.
     *
     * Consumes this incoming connection. Unlike iroh — whose `RetryError` hands the `Incoming` back
     * so a caller can carry on with it — a failure here leaves nothing to retry with: Kotlin still
     * points at the handle whose value was just taken, and putting the value back would break the
     * consumed-once contract every other member relies on. The attempt is refused instead, which is
     * also what letting a `RetryError` fall out of scope does in Rust.
     *
     * @throws IrohError with [IrohError.Code.Accept] if the peer's address is already validated,
     *   which is iroh's documented failure for this.
     */
    fun retry() = guard.use { nativeIncomingRetry(it) }

    /**
     * Drops the attempt without answering it at all — no refusal, no packet.
     *
     * Consumes this incoming connection. The right response to traffic that should not be
     * acknowledged, since a refusal is itself a reply that confirms something is listening.
     */
    fun ignore() = guard.use { nativeIncomingIgnore(it) }

    /**
     * The local end of the path this attempt arrived on.
     *
     * @throws IrohError with [IrohError.Code.Closed] once this incoming connection has been
     *   consumed — the value it was read from is gone.
     */
    fun localAddr(): LocalTransportAddr = guard.use { handle ->
        decodeLocalTransportAddr(BinaryReader(nativeIncomingLocalAddr(handle)))
    }

    /** Where the attempt came from. As [localAddr], this only answers before the value is consumed. */
    fun remoteAddr(): IncomingAddr = guard.use { handle ->
        decodeIncomingAddr(BinaryReader(nativeIncomingRemoteAddr(handle)))
    }

    /**
     * Whether the peer has proved it can receive traffic at the address it dialled from.
     *
     * `false` for a first contact that has not been through a [retry]. As [localAddr], this only
     * answers before the value is consumed.
     */
    fun remoteAddrValidated(): Boolean = guard.use { nativeIncomingRemoteAddrValidated(it) }

    /** Whether [close] has released this handle, after which every other member raises. */
    val isReleased: Boolean get() = guard.isReleased

    /**
     * Releases the handle. Idempotent, and safe to call while other threads are inside it.
     *
     * Required even after [accept] or [acceptWith]: consuming the value empties the handle but does
     * not free it.
     */
    override fun close() = guard.close()

    override fun toString(): String = if (guard.isReleased) "Incoming(released)" else "Incoming()"
}

// ── Accepting and Connecting ──────────────────────────────────────────────────────────────────

/**
 * An inbound handshake in progress, from [Incoming.accept].
 *
 * **Cancellable.** Cancelling a coroutine suspended in [connect] or [alpn] aborts the Rust task
 * rather than leaving it running.
 */
class Accepting internal constructor(private val guard: NativeHandle) : AutoCloseable {

    /**
     * Completes the handshake.
     *
     * Consumes this handshake: a second call raises [IrohError] with [IrohError.Code.Closed], and so
     * does a call after the first was **cancelled** — a cancelled `connect` has already taken the
     * value and cannot put it back, so the honest answer is that the handshake is gone.
     *
     * @throws IrohError with [IrohError.Code.Connect] if the handshake fails — a mismatched ALPN, a
     *   peer that went away, a rejection by an endpoint hook.
     */
    suspend fun connect(): Connection = guard.useSuspending { handle ->
        Connection(NativeHandle(nativeAcceptingConnect(handle), CONNECTION, ::nativeConnectionFree))
    }

    /**
     * The ALPN protocol the handshake settled on, read *without* consuming it.
     *
     * This is how an endpoint that advertises several protocols decides what to do with a connection
     * before completing it. Raw bytes, because an ALPN is an opaque octet string in TLS.
     *
     * @throws IrohError with [IrohError.Code.Connect] if the handshake failed, or completed without
     *   an ALPN.
     */
    suspend fun alpn(): ByteArray = guard.useSuspending { nativeAcceptingAlpn(it) }

    /** Whether [close] has released this handle, after which every other member raises. */
    val isReleased: Boolean get() = guard.isReleased

    /** Releases the handle, abandoning the handshake if it was never completed. Idempotent. */
    override fun close() = guard.close()

    override fun toString(): String = if (guard.isReleased) "Accepting(released)" else "Accepting()"

    /**
     * Runs [block] against this handshake's native handle, holding the guard across it.
     *
     * The counterpart of [Connecting.useHandle]: [zeroRtt] needs to reach the handle without [guard]
     * itself becoming visible outside this class.
     */
    internal suspend fun <T> useHandle(block: suspend (Long) -> T): T = guard.useSuspending(block)
}

/**
 * An outbound handshake in progress, from [Endpoint.startConnect].
 *
 * The mirror image of [Accepting], plus [remoteId] — an outbound attempt knows who it is dialling
 * before anything is negotiated, whereas an inbound one learns it from the peer's certificate.
 */
class Connecting internal constructor(private val guard: NativeHandle) : AutoCloseable {

    /**
     * Completes the handshake.
     *
     * Consumed once, with the same cancellation caveat as [Accepting.connect].
     *
     * @throws IrohError with [IrohError.Code.Connect] if the handshake fails.
     */
    suspend fun connect(): Connection = guard.useSuspending { handle ->
        Connection(NativeHandle(nativeConnectingConnect(handle), CONNECTION, ::nativeConnectionFree))
    }

    /** The ALPN this handshake settled on, read without consuming it — see [Accepting.alpn]. */
    suspend fun alpn(): ByteArray = guard.useSuspending { nativeConnectingAlpn(it) }

    /**
     * The [EndpointId] this attempt is aimed at.
     *
     * Answers even after [connect] has consumed the handshake: it is the id that was dialled, and
     * that stays true whatever became of the attempt.
     */
    fun remoteId(): EndpointId = guard.use { EndpointId.validated(nativeConnectingRemoteId(it)) }

    /** Whether [close] has released this handle, after which every other member raises. */
    val isReleased: Boolean get() = guard.isReleased

    /** Releases the handle, abandoning the attempt if it was never completed. Idempotent. */
    override fun close() = guard.close()

    override fun toString(): String =
        runCatching { "Connecting(${remoteId()})" }.getOrElse { "Connecting(released)" }

    /**
     * Runs [block] against this attempt's native handle, holding the guard across it.
     *
     * The hook [zeroRtt] needs to reach the handle without [guard] itself becoming visible outside
     * this class — the exact counterpart of [QuicConnection.withHandle], which does the same job for
     * [Connection]'s own extension functions.
     */
    internal suspend fun <T> useHandle(block: suspend (Long) -> T): T = guard.useSuspending(block)
}

// ── Connection ────────────────────────────────────────────────────────────────────────────────

/**
 * What every QUIC connection can do, whatever state its handshake is in.
 *
 * This is upstream's `impl<T: ConnectionState> Connection<T>` (`iroh-1.0.3/src/endpoint/connection.rs:811`)
 * expressed in Kotlin: the members here are exactly the ones that do not need a completed handshake, and
 * they are therefore the ones a 0-RTT connection can offer. [Connection] adds what only a completed
 * handshake has — identity, side and paths.
 *
 * A **class** rather than an interface, deliberately: Kotlin forbids `internal` interface members, and
 * `Stream.kt` reaches the handle through [withHandle], which must not be public API. Sealed, so the three
 * subclasses below are the whole world and a `when` over them is exhaustive.
 *
 * **Thread-safe**, as every handle-owning type here is.
 */
sealed class QuicConnection protected constructor(internal val guard: NativeHandle) : AutoCloseable {

    /**
     * An identifier that stays fixed for the connection's whole life.
     *
     * Addresses and QUIC connection ids both change as paths migrate; this does not, which makes it
     * the thing to key a log line or a session map on. iroh types it as a `usize`; it is a [Long]
     * here, which is lossless on every supported target.
     */
    fun stableId(): Long = guard.use { nativeConnectionStableId(it) }

    /**
     * Why the connection closed, or `null` while it is still open.
     *
     * iroh's own text for the reason. Deliberately not an enum: `ConnectionError` is
     * `#[non_exhaustive]` upstream with a dozen variants, whose ordinals would be a second wire
     * protocol to keep in step for a value callers read rather than branch on.
     */
    val closeReason: String?
        get() = guard.use { handle ->
            val reader = BinaryReader(nativeConnectionCloseReason(handle))
            if (reader.u8() == ABSENT) null else reader.string()
        }

    /**
     * Whether the connection has closed, for any reason — locally, by the peer, or by timing out.
     *
     * A snapshot of `closeReason != null`, and it costs the same single crossing.
     */
    val isClosed: Boolean get() = closeReason != null

    /**
     * Closes the connection immediately, handing [errorCode] and [reason] to the peer verbatim.
     *
     * Neither value is interpreted by iroh or by iroh4k. [reason] is truncated to fit one packet, so
     * keep it under a kilobyte if it matters that the peer sees all of it. Pending operations fail at
     * once and buffered data may be dropped — see [Connection] for the note on closing gracefully.
     *
     * Does **not** release the handle; the no-argument [close] does that.
     *
     * Note there is no default for [reason]: `close(errorCode)` is the overload for an empty one, and
     * a defaulted parameter here would make the bare `close()` of [AutoCloseable] ambiguous with it.
     *
     * @throws IrohError with [IrohError.Code.InvalidArgument] if [errorCode] is negative or does not
     *   fit in a QUIC varint (62 bits).
     */
    fun close(errorCode: Long, reason: ByteArray) = guard.use {
        nativeConnectionClose(it, errorCode, reason)
    }

    /** Closes the connection with [errorCode] and no reason — see [close]. */
    fun close(errorCode: Long) = close(errorCode, ByteArray(0))

    /**
     * Suspends until the connection closes, for any reason, and answers with iroh's text for it.
     *
     * Not a failure: iroh reports *every* ending this way, including the routine ones — the peer
     * closing deliberately, or this side having called [close]. So the reason comes back as a value.
     * If the connection is already closed this returns at once with the same text [closeReason] gives.
     *
     * **This suspends indefinitely** while the connection stays up. Cancellation ends it and reaches
     * into Rust: the tokio task is aborted rather than left waiting.
     */
    suspend fun closed(): String = guard.useSuspending { nativeConnectionClosed(it) }

    /**
     * The connection's totals so far.
     *
     * A snapshot: read it twice to measure an interval. Covers all traffic on the connection, not only
     * what the application sent — see [UdpStats].
     */
    fun stats(): ConnectionStats = guard.use { handle ->
        decodeConnectionStats(BinaryReader(nativeConnectionStats(handle)))
    }

    /**
     * The largest datagram [sendDatagram] will currently accept, or `null` if it will accept none.
     *
     * `null` means datagrams are switched off locally or unsupported by the peer. Otherwise the value
     * *changes over the connection's life* as the path MTU estimate moves, so it is asked rather than
     * remembered — though when the peer's own limit is generous it is guaranteed to stay a little over
     * a kilobyte.
     */
    fun maxDatagramSize(): Long? = guard.use { handle ->
        nativeConnectionMaxDatagramSize(handle).takeIf { it >= 0 }
    }

    /**
     * Bytes free in the outgoing datagram buffer.
     *
     * While this is above zero, a [sendDatagram] of at most this many bytes will not push an older
     * datagram out of the queue. Datagrams are unreliable, so exceeding it loses the oldest rather
     * than failing.
     */
    fun datagramSendBufferSpace(): Long = guard.use { nativeConnectionDatagramSendBufferSpace(it) }

    /**
     * Sends [payload] as one unreliable, unordered datagram, without waiting for buffer space.
     *
     * @throws IrohError with [IrohError.Code.Write] if [payload] is larger than [maxDatagramSize], or
     *   if datagrams are disabled locally or unsupported by the peer.
     * @throws IrohError with [IrohError.Code.Closed] if the connection has ended.
     */
    fun sendDatagram(payload: ByteArray) = guard.use { nativeConnectionSendDatagram(it, payload) }

    /**
     * As [sendDatagram], but waits for buffer space instead of displacing an older datagram.
     *
     * Which effectively prioritises the datagrams already queued over this one. Cancellable.
     *
     * @throws IrohError as [sendDatagram] — waiting does not make an over-sized datagram fit.
     */
    suspend fun sendDatagramWait(payload: ByteArray) = guard.useSuspending {
        nativeConnectionSendDatagramWait(it, payload)
    }

    /**
     * Suspends until a datagram arrives from the peer, and answers with its bytes.
     *
     * **This suspends indefinitely** if the peer sends none. Cancellation ends it, and aborts the Rust
     * task rather than leaving it waiting.
     *
     * @throws IrohError with [IrohError.Code.Closed] if the connection ends first.
     */
    suspend fun readDatagram(): ByteArray = guard.useSuspending { nativeConnectionReadDatagram(it) }

    /**
     * Limits how many bidirectional streams the **peer** may have open at once.
     *
     * The peer cannot open one unless fewer than [count] are already open, so this is the knob that
     * bounds what a remote can make this side allocate. Large values raise both typical and worst-case
     * memory use.
     *
     * @throws IrohError with [IrohError.Code.InvalidArgument] if [count] is negative or does not fit in
     *   a QUIC varint (62 bits).
     */
    fun setMaxConcurrentBiStreams(count: Long) = guard.use {
        nativeConnectionSetMaxConcurrentBiStreams(it, count)
    }

    /** As [setMaxConcurrentBiStreams], for unidirectional streams. */
    fun setMaxConcurrentUniStreams(count: Long) = guard.use {
        nativeConnectionSetMaxConcurrentUniStreams(it, count)
    }

    /**
     * Sets the connection-level flow-control receive window, in bytes.
     *
     * The ceiling on data the peer may have in flight across *all* streams together, so it bounds
     * buffering the same way [setMaxConcurrentBiStreams] bounds stream count.
     *
     * @throws IrohError with [IrohError.Code.InvalidArgument] as [setMaxConcurrentBiStreams].
     */
    fun setReceiveWindow(bytes: Long) = guard.use { nativeConnectionSetReceiveWindow(it, bytes) }

    /** Whether [close] has released this handle, after which every other member raises. */
    val isReleased: Boolean get() = guard.isReleased

    /**
     * Releases the handle.
     *
     * If the connection is still open, dropping the last reference closes it with error code `0` and
     * an empty reason — iroh's own behaviour. Call [close(errorCode, reason)][close] first to say
     * something more useful. Idempotent.
     */
    override fun close() = guard.close()

    /**
     * Runs [block] against this connection's native handle, holding the guard across it.
     *
     * The hook the stream domain needs, and the exact counterpart of [Endpoint.withHandle]. The four
     * stream openers live in `Stream.kt` because everything they produce belongs to that domain, and
     * all they need from a connection is its handle — lent through the guard rather than handed over,
     * so the guard stays the single arbiter of when the handle may be touched. Closing a connection
     * while an `acceptBi` is suspended is therefore as safe as closing it while [closed] is.
     */
    internal suspend fun <T> withHandle(block: suspend (Long) -> T): T = guard.useSuspending(block)
}

/**
 * An established QUIC connection to another endpoint.
 *
 * Carries identity ([alpn], [remoteId], [stableId], [side]), lifecycle ([close], [isClosed],
 * [closeReason], [closed]), measurement ([stats], [rtt], [paths]), unreliable datagrams
 * ([sendDatagram], [readDatagram]), and — through `openBi`, `acceptBi`, `openUni` and `acceptUni` in
 * `Stream.kt` — the reliable ordered byte streams most applications actually use.
 *
 * **Thread-safe**, as every handle-owning type here is: any member may be called from any thread or
 * coroutine, including concurrently with [close].
 *
 * Note the two `close`es, the same split [Endpoint] makes and for the same reason:
 * [close(errorCode, reason)][close] is iroh's `Connection::close`, which tells the peer why; the
 * no-argument [close] is [AutoCloseable]'s, which releases the handle. Releasing without closing
 * first is legal — iroh then closes with error code `0` and an empty reason — but says nothing.
 *
 * ## Streams or datagrams
 *
 * A stream is reliable, ordered and flow-controlled, and there is no cost to opening one — QUIC
 * multiplexes as many as wanted onto the connection without head-of-line blocking between them. A
 * datagram is none of those things: it may be lost, may arrive out of order, and must fit in a single
 * packet ([maxDatagramSize]). Reach for a stream unless losing the data is genuinely acceptable.
 */
class Connection internal constructor(guard: NativeHandle) : QuicConnection(guard) {

    /**
     * The negotiated ALPN protocol, as raw bytes.
     *
     * Always present: iroh refuses a connection that completed without one, so an established
     * [Connection] always has an ALPN both sides agreed on.
     */
    fun alpn(): ByteArray = guard.use { nativeConnectionAlpn(it) }

    /**
     * The remote's [EndpointId], taken from the certificate it presented during the handshake.
     *
     * Cryptographically established rather than claimed, which is what makes it the identity to
     * authorise against.
     */
    fun remoteId(): EndpointId = guard.use { EndpointId.validated(nativeConnectionRemoteId(it)) }

    /**
     * Which end of the connection this is.
     *
     * [ConnectionSide.Client] for the side that dialled, [ConnectionSide.Server] for the side that
     * accepted. Worth knowing because QUIC stream ids encode it, and because a protocol usually gives
     * the two sides different jobs.
     */
    fun side(): ConnectionSide = guard.use { ConnectionSide.of(nativeConnectionSide(it)) }

    /**
     * Current round-trip-time estimate for the path traffic is taking, or `null` if no path reports
     * one yet.
     *
     * iroh's own `rtt` takes a path id; this one uses the *selected* path — the one application data is
     * actually going over — falling back to the first open path when nothing is selected yet. Per-path
     * figures are in [paths].
     */
    fun rtt(): Duration? = guard.use { handle ->
        nativeConnectionRtt(handle).takeIf { it >= 0 }?.microseconds
    }

    /**
     * The connection's open network paths, as they are right now.
     *
     * Usually one — a direct loopback or LAN connection has nothing else — and up to two once a relayed
     * connection has hole-punched its way to a direct path. Empty for a connection that has closed.
     *
     * A snapshot, not a subscription: paths that open or close afterwards are not in it. Use
     * `Connection.watchPaths()` in `Watch.kt` to observe the changes over time.
     */
    fun paths(): List<PathSnapshot> = guard.use { handle ->
        BinaryReader(nativeConnectionPaths(handle)).seq { decodePathSnapshot(it) }
    }

    override fun toString(): String =
        runCatching { "Connection(${remoteId()}, ${stableId()})" }
            .getOrElse { "Connection(released)" }

    companion object {
        /**
         * Connection-domain handles still alive in Rust — [Incoming], [Accepting], [Connecting],
         * [Connection], [OutgoingZeroRttConnection] and [IncomingZeroRttConnection] together.
         *
         * Exposed for tests, as [Endpoint.liveHandleCount] is: it must return to its baseline once
         * every handle is closed, which is how a leak in the accept chain would be caught.
         */
        internal val liveHandleCount: Long get() = nativeConnectionLiveHandleCount()
    }
}

// ── 0-RTT, dialling side ──────────────────────────────────────────────────────────────────────

/**
 * A connection that may carry application data sent before the handshake completed.
 *
 * From [Connecting.zeroRtt]. Everything on [QuicConnection] works — streams above all, since sending
 * early data *is* opening a stream — and [alpn] and [remoteId] answer `null` until the handshake
 * settles them, which is exactly why this is not a [Connection].
 *
 * ## Two hazards, both upstream's own words
 *
 * 0-RTT data **is vulnerable to replay attacks and must never invoke non-idempotent operations**. And a
 * server may reject it at its discretion: accepting requires resumption state the server may limit or
 * lose, and [awaitHandshake] is where you find out.
 *
 * ## Closing
 *
 * Once [awaitHandshake] has answered, you hold two handles over one connection, and this one keeps it
 * alive: the future behind it caches its own output, which contains that [Connection]. **Close this
 * handle once you hold the [Connection]** — releasing only the [Connection] leaves the connection open
 * indefinitely. [close] with an error code closes it for both.
 */
class OutgoingZeroRttConnection internal constructor(guard: NativeHandle) : QuicConnection(guard) {
    /** The negotiated ALPN, or `null` while the handshake has not settled it. */
    fun alpn(): ByteArray? = guard.use { nativeZeroRttAlpn(it) }

    /** The peer's [EndpointId], or `null` while the handshake has not established it. */
    fun remoteId(): EndpointId? = guard.use { nativeZeroRttRemoteId(it)?.let(EndpointId::validated) }

    override fun toString(): String =
        if (guard.isReleased) "OutgoingZeroRttConnection(released)" else "OutgoingZeroRttConnection()"
}

/** What the handshake decided about the early data — see [OutgoingZeroRttConnection.awaitHandshake]. */
sealed interface ZeroRttStatus {
    /** The connection, established either way. */
    val connection: Connection

    /** The peer read the early data. Streams opened before the handshake stay usable. */
    data class Accepted(override val connection: Connection) : ZeroRttStatus

    /**
     * The peer refused the early data — it had lost the resumption state, most likely by restarting.
     * Streams opened before the handshake are dead and everything written on them must be resent on
     * [connection]; reading or writing one raises an `IrohError` naming the rejection.
     */
    data class Rejected(override val connection: Connection) : ZeroRttStatus
}

/**
 * Turns this attempt into a 0-RTT connection, or answers `null` when 0-RTT is not available.
 *
 * `null` means this endpoint holds no TLS session ticket for the peer — it has not spoken to it before,
 * or the ticket has been evicted from the endpoint's ticket cache. **This attempt is then untouched
 * and [Connecting.connect] still completes it**, which is the whole reason the answer is nullable rather
 * than an error. A non-null answer consumes the attempt: a later [Connecting.connect] raises
 * [IrohError] with [IrohError.Code.Closed]. [Connecting.remoteId] answers either way.
 *
 * **A cancelled call can consume the attempt too, without ever handing back a non-null answer.** On
 * the has-a-ticket branch, the `Connecting` is taken out of its slot before the resulting
 * [OutgoingZeroRttConnection] handle is delivered back to Kotlin; if the coroutine calling this is
 * cancelled while that delivery is in flight, the handle is freed unseen (see `ops.rs`'s module
 * header for why that is not a leak) but the slot stays empty regardless — the attempt was already
 * spent. A [Connecting.connect] made afterwards then answers `Closed` the same as it would after a
 * normal non-null answer, even though the caller here only ever observed a cancellation.
 *
 * The ticket cache lives in the [Endpoint] and dies with it, so 0-RTT is only ever available on a second
 * or later dial **from the same endpoint**.
 */
suspend fun Connecting.zeroRtt(): OutgoingZeroRttConnection? = useHandle { handle ->
    val zero = nativeConnectingZeroRtt(handle)
    if (zero == 0L) null
    else OutgoingZeroRttConnection(
        NativeHandle(zero, OUTGOING_ZERO_RTT, ::nativeOutgoingZeroRttFree),
    )
}

/**
 * Waits for the handshake and reports what became of the early data.
 *
 * **Re-awaitable**, unlike every other transition in this domain: the future behind it is shared, so a
 * cancelled call may simply be made again where [Connecting.connect] would answer
 * [IrohError.Code.Closed]. Each success mints a **fresh** [Connection] handle with its own lifetime —
 * close each one you take.
 *
 * @throws IrohError with [IrohError.Code.Connect] if the handshake fails outright.
 */
suspend fun OutgoingZeroRttConnection.awaitHandshake(): ZeroRttStatus = withHandle { handle ->
    val (connection, accepted) = nativeOutgoingZeroRttAwaitHandshake(handle)
    val completed = Connection(NativeHandle(connection, CONNECTION, ::nativeConnectionFree))
    if (accepted) ZeroRttStatus.Accepted(completed) else ZeroRttStatus.Rejected(completed)
}

// ── 0-RTT, accepting side ─────────────────────────────────────────────────────────────────────

/**
 * A connection that may carry application data read before its handshake completed, from the
 * accepting side.
 *
 * From [Accepting.zeroRtt]. Mirrors [OutgoingZeroRttConnection] in nearly everything: [alpn] and
 * [remoteId] answer `null` until the handshake settles them, for the same reason on both sides — and
 * are read through the exact same pair of exports [OutgoingZeroRttConnection] already uses
 * (`iroh4k_zero_rtt_alpn`/`iroh4k_zero_rtt_remote_id`), which probe either 0-RTT handle kind. There is
 * no `IncomingZeroRttConnection`-specific pair to add.
 *
 * ## Two hazards, both upstream's own words
 *
 * As on the dialling side, 0-RTT data **is vulnerable to replay attacks and must never invoke
 * non-idempotent operations**. This side carries a hazard the dialling side does not: because iroh
 * accepts 0-RTT at the TLS layer unconditionally (see [Accepting.zeroRtt]'s doc), **0.5-RTT data this
 * side sends back may reach the peer before that peer is authenticated** — this endpoint's own
 * certificate has already gone out over the wire, but the identity behind the *client's* certificate
 * has not yet been proven. Code that replies to early data before [awaitHandshake] resolves is
 * replying to someone it has not yet verified.
 *
 * ## Closing
 *
 * Once [awaitHandshake] has answered, you hold two handles over one connection — exactly as
 * [OutgoingZeroRttConnection] documents, and for the same reason: the future behind it caches its own
 * output, which contains the resulting [Connection]. **Close this handle once you hold the
 * [Connection]** — releasing only the [Connection] leaves the connection open indefinitely.
 */
class IncomingZeroRttConnection internal constructor(guard: NativeHandle) : QuicConnection(guard) {
    /** The negotiated ALPN, or `null` while the handshake has not settled it. */
    fun alpn(): ByteArray? = guard.use { nativeZeroRttAlpn(it) }

    /** The peer's [EndpointId], or `null` while the handshake has not established it. */
    fun remoteId(): EndpointId? = guard.use { nativeZeroRttRemoteId(it)?.let(EndpointId::validated) }

    override fun toString(): String =
        if (guard.isReleased) "IncomingZeroRttConnection(released)" else "IncomingZeroRttConnection()"
}

/**
 * Turns this accepted handshake into a 0-RTT connection, so early data can be read before it
 * completes.
 *
 * **Infallible**, unlike [Connecting.zeroRtt]: iroh accepts 0-RTT at the TLS layer on every endpoint —
 * `max_early_data_size` is hardcoded (`iroh-1.0.3/src/tls.rs:118`) — so the only decision available to
 * an application is whether it *reads* the early data at all. There is nothing to configure and
 * nothing to refuse, which is also why this answers [IncomingZeroRttConnection] directly rather than a
 * nullable one: unlike a dial, an accepted handshake never has "no ticket" to fail on.
 *
 * That infallibility is upstream's own guarantee, not one this binding derives independently: it rests
 * on an internal `.expect("incoming connections can always be converted to 0-RTT")`
 * (`iroh-1.0.3/src/endpoint/connection.rs:624`). Under this crate's `panic = "abort"` (see
 * `AGENTS.md`), that `expect` failing would abort the host process rather than raise a Kotlin
 * exception — the one place in this operation where a change to upstream's own invariant, not to
 * anything iroh4k controls, could turn "always succeeds" into "always aborts."
 *
 * Consumes this handshake: a later [Accepting.connect] raises [IrohError] with [IrohError.Code.Closed].
 */
suspend fun Accepting.zeroRtt(): IncomingZeroRttConnection = useHandle { handle ->
    IncomingZeroRttConnection(
        NativeHandle(nativeAcceptingZeroRtt(handle), INCOMING_ZERO_RTT, ::nativeIncomingZeroRttFree),
    )
}

/**
 * Waits for the handshake and answers the established [Connection].
 *
 * There is no accepted/rejected distinction here, unlike [OutgoingZeroRttConnection.awaitHandshake]:
 * that question belongs to the dialling side, which is the one that finds out whether its early data
 * was actually read. `IncomingZeroRttConnection::handshake_completed` answers a bare `Connection`
 * upstream (`iroh-1.0.3/src/endpoint/connection.rs:1217`) rather than the two-armed status the dialling
 * side gets, and this mirrors that shape exactly.
 *
 * Re-awaitable and two-handled exactly as [OutgoingZeroRttConnection.awaitHandshake]: the future
 * behind it is shared, so a cancelled call may simply be made again, and each success mints a fresh
 * [Connection] handle with its own lifetime — close this handle once you hold the [Connection].
 *
 * @throws IrohError with [IrohError.Code.Connect] if the handshake fails.
 */
suspend fun IncomingZeroRttConnection.awaitHandshake(): Connection = withHandle { handle ->
    Connection(NativeHandle(nativeIncomingZeroRttAwaitHandshake(handle), CONNECTION, ::nativeConnectionFree))
}

// ── The endpoint's accept and dial entry points ────────────────────────────────────────────────
//
// Extensions rather than members of `Endpoint`, because the accept chain is this domain's: everything
// they need from an endpoint is its handle, which `Endpoint.withHandle` lends under the endpoint's own
// guard. So closing an endpoint while an `acceptNext` is suspended is as safe as closing it while
// `online()` is — the handle survives until the call returns.

/**
 * Suspends until another endpoint connects, and answers with the attempt.
 *
 * `null` means this endpoint has been shut down, which is iroh's own answer rather than a failure —
 * so an accept loop is `while (true) { val incoming = endpoint.acceptNext() ?: break; … }`.
 *
 * Only attempts whose ALPN is one of this endpoint's are ever offered here; see
 * [EndpointConfig.alpns] and [Endpoint.setAlpns].
 *
 * **This suspends indefinitely** when nobody connects — there is nothing to wait for and no timeout
 * involved. Cancellation is what ends it, and it reaches all the way into Rust: the tokio task is
 * aborted rather than abandoned, and an [Incoming] that had just been produced is released rather
 * than stranded.
 *
 * @throws IrohError with [IrohError.Code.Closed] if this endpoint's handle has been released.
 */
suspend fun Endpoint.acceptNext(): Incoming? = withHandle { handle ->
    val incoming = nativeEndpointAcceptNext(handle)
    // A null handle is the `None` iroh answers with for a closed endpoint, not an error.
    if (incoming == 0L) null
    else Incoming(NativeHandle(incoming, INCOMING, ::nativeIncomingFree), this)
}

/**
 * Connects to [addr] with the protocol [alpn] and answers with the established [Connection].
 *
 * The one-shot form, and what almost every caller wants: it is [startConnect] followed by
 * [Connecting.connect], which is exactly what iroh's own `Endpoint::connect` is. Use [startConnect]
 * instead when something has to happen *between* those two steps — reading the negotiated ALPN before
 * committing, most obviously.
 *
 * [addr] needs at least one address iroh can actually reach, since an [EndpointConfig] with no address
 * lookup configured has no other way to find the remote. `endpoint.addr()` on the other side is
 * exactly that.
 *
 * Cancellable, and the cancellation is complete: a cancel landing in the instant the handshake
 * succeeded releases the connection rather than stranding it.
 *
 * @throws IrohError with [IrohError.Code.Connect] if the attempt cannot be started or the handshake
 *   fails — dialling this endpoint's own id, an empty [alpn], a shut-down endpoint, an address with
 *   nothing reachable in it, a mismatched ALPN, or a peer that went away.
 * @throws IrohError with [IrohError.Code.Addr] or [IrohError.Code.Key] if [addr] cannot be encoded,
 *   which means it holds a [TransportAddr.Unknown] this build cannot re-encode.
 */
suspend fun Endpoint.connect(addr: EndpointAddr, alpn: ByteArray): Connection = withHandle { handle ->
    val connected = nativeEndpointConnect(handle, encodeEndpointAddr(addr), alpn)
    Connection(NativeHandle(connected, CONNECTION, ::nativeConnectionFree))
}

/**
 * Starts connecting to [addr] with the protocol [alpn], answering with the handshake in progress.
 *
 * The two-step form of [connect]: this returns once iroh has *started* the attempt, and
 * [Connecting.connect] completes it. Between the two, [Connecting.alpn] can be read and the attempt
 * can simply be abandoned by releasing it — which is the whole reason both exist. Prefer [connect]
 * when neither of those matters.
 *
 * Maps onto iroh's `connect_with_opts`, which is also why [transportConfig] lives here and not on
 * [connect]: upstream offers per-attempt options on that call alone. 0-RTT does not travel through
 * `connect_with_opts` or [transportConfig] at all — call [Connecting.zeroRtt] on what this returns,
 * before [Connecting.connect].
 *
 * @param transportConfig QUIC transport parameters for this connection alone. It **replaces** the
 *   endpoint's own [EndpointConfig.transportConfig] rather than merging with it — upstream's
 *   behaviour, and the opposite of what most people expect, so a caller who wants the endpoint's
 *   settings plus one change has to restate the settings. `null`, the default, uses the endpoint's.
 * @throws IrohError as [connect], except that a handshake failure surfaces from [Connecting.connect]
 *   rather than from here.
 * @throws IrohError with [IrohError.Code.InvalidArgument] if [transportConfig] holds a value iroh
 *   refuses — a negative duration, say.
 */
suspend fun Endpoint.startConnect(
    addr: EndpointAddr,
    alpn: ByteArray,
    transportConfig: TransportConfig? = null,
): Connecting = withHandle { handle ->
    val opts = BinaryWriter().apply { writeOptionalTransportConfig(transportConfig) }.finish()
    val started = nativeEndpointStartConnect(handle, encodeEndpointAddr(addr), alpn, opts)
    Connecting(NativeHandle(started, CONNECTING, ::nativeConnectingFree))
}

// ── The guard ─────────────────────────────────────────────────────────────────────────────────
//
// Every handle-owning type in this file is guarded by the one shared
// `internal/NativeHandle.kt` — the atomic closed-bit-plus-caller-count design documented there —
// parameterised per handle type with its own release export and its own name for the error message.

/** What each handle type calls itself in an [IrohError.Code.Closed] message. */
private const val INCOMING = "incoming connection"
private const val ACCEPTING = "accepting connection"
private const val CONNECTING = "connection attempt"
private const val CONNECTION = "connection"
private const val OUTGOING_ZERO_RTT = "outgoing 0-RTT connection"
private const val INCOMING_ZERO_RTT = "incoming 0-RTT connection"

// ── The connection codec ──────────────────────────────────────────────────────────────────────
//
// The layout is defined in `connection.rs`, which documents it in full; this is its Kotlin mirror and
// the two must be changed together. The transport-address tags are the same numbers `Addr.kt` and
// `Endpoint.kt` use, deliberately: the whole binding has one tag family for
// "relay / ip / custom / something newer".

private const val ADDR_TAG_RELAY = 0
private const val ADDR_TAG_IP = 1
private const val ADDR_TAG_CUSTOM = 2
private const val ADDR_TAG_UNKNOWN = 3

/** Discriminators for an optional record. */
private const val ABSENT = 0


/** Decodes an [IncomingAddr] written by `connection.rs`'s `write_incoming_addr`. */
private fun decodeIncomingAddr(reader: BinaryReader): IncomingAddr = when (val tag = reader.u8()) {
    // Left-to-right argument evaluation is what keeps these two reads in the encoded order.
    ADDR_TAG_RELAY ->
        IncomingAddr.Relay(RelayUrl.trusted(reader.string()), EndpointId.validated(reader.bytes()))

    ADDR_TAG_IP -> IncomingAddr.Ip(SocketAddr.trusted(reader.string()))
    ADDR_TAG_CUSTOM -> IncomingAddr.Custom(CustomAddr(reader.i64(), reader.bytes()))
    ADDR_TAG_UNKNOWN -> IncomingAddr.Unknown(reader.string())
    else -> error("Malformed connection payload: unknown incoming address tag $tag")
}

/** Decodes a [LocalTransportAddr] written by `connection.rs`'s `write_local_addr`. */
private fun decodeLocalTransportAddr(reader: BinaryReader): LocalTransportAddr =
    when (val tag = reader.u8()) {
        ADDR_TAG_RELAY -> LocalTransportAddr.Relay(RelayUrl.trusted(reader.string()))
        ADDR_TAG_IP -> LocalTransportAddr.Ip(reader.optString())
        ADDR_TAG_CUSTOM -> LocalTransportAddr.Custom(
            if (reader.u8() == ABSENT) null else CustomAddr(reader.i64(), reader.bytes())
        )

        ADDR_TAG_UNKNOWN -> LocalTransportAddr.Unknown(reader.string())
        else -> error("Malformed connection payload: unknown local address tag $tag")
    }

/**
 * Decodes a [TransportAddr] written by `connection.rs`'s `write_transport_addr`.
 *
 * The same layout `Endpoint.kt` decodes, which is the point — one shape for the type across the whole
 * binding. Both should call one shared reader once the addressing codec is `internal` rather than
 * file-private.
 */
private fun decodeTransportAddr(reader: BinaryReader): TransportAddr = when (val tag = reader.u8()) {
    ADDR_TAG_RELAY -> TransportAddr.Relay(RelayUrl.trusted(reader.string()))
    ADDR_TAG_IP -> TransportAddr.Ip(SocketAddr.trusted(reader.string()))
    // Left-to-right argument evaluation is what keeps these two reads in the encoded order.
    ADDR_TAG_CUSTOM -> TransportAddr.Custom(CustomAddr(reader.i64(), reader.bytes()))
    ADDR_TAG_UNKNOWN -> TransportAddr.Unknown(reader.string())
    else -> error("Malformed connection payload: unknown transport address tag $tag")
}

/** Decodes the [UdpStats] pair written by `connection.rs`'s `write_udp_stats`. */
private fun decodeUdpStats(reader: BinaryReader): UdpStats =
    UdpStats(datagrams = reader.i64(), bytes = reader.i64())

/** Decodes a [ConnectionStats] written by `connection.rs`'s `connection_stats`. */
private fun decodeConnectionStats(reader: BinaryReader): ConnectionStats = ConnectionStats(
    udpTx = decodeUdpStats(reader),
    udpRx = decodeUdpStats(reader),
    lostPackets = reader.i64(),
    lostBytes = reader.i64(),
)

/** Decodes a [PathSnapshot] written by `connection.rs`'s `write_path`. */
private fun decodePathSnapshot(reader: BinaryReader): PathSnapshot = PathSnapshot(
    pathId = reader.i64(),
    remoteAddr = decodeTransportAddr(reader),
    localAddr = decodeLocalTransportAddr(reader),
    isSelected = reader.bool(),
    rtt = reader.i64().microseconds,
    congestionWindow = reader.i64(),
    currentMtu = reader.i32(),
    lostPackets = reader.i64(),
    lostBytes = reader.i64(),
    congestionEvents = reader.i64(),
    udpTx = decodeUdpStats(reader),
    udpRx = decodeUdpStats(reader),
)

// ── The connection domain's FFI surface, implemented per facade ─────────────────────────────────
//
// Names are prefixed `nativeIncoming`/`nativeAccepting`/`nativeConnecting`/`nativeConnection` so each
// domain's expect declarations stay distinct in this shared package. The two that take an *endpoint*
// handle keep the `nativeEndpoint` prefix of the handle they are given, since that is whose handle it
// is; the operations themselves live in `connection.rs`.

internal expect fun nativeConnectionLiveHandleCount(): Long

internal expect fun nativeIncomingFree(handle: Long)

internal expect fun nativeAcceptingFree(handle: Long)

internal expect fun nativeConnectingFree(handle: Long)

internal expect fun nativeConnectionFree(handle: Long)

internal expect fun nativeOutgoingZeroRttFree(handle: Long)

internal expect fun nativeIncomingZeroRttFree(handle: Long)

/** Returns the handle of the [Accepting] this produced. */
internal expect fun nativeIncomingAccept(handle: Long): Long

internal expect fun nativeIncomingRefuse(handle: Long)

internal expect fun nativeIncomingRetry(handle: Long)

internal expect fun nativeIncomingIgnore(handle: Long)

internal expect fun nativeIncomingLocalAddr(handle: Long): ByteArray

internal expect fun nativeIncomingRemoteAddr(handle: Long): ByteArray

internal expect fun nativeIncomingRemoteAddrValidated(handle: Long): Boolean

/** Returns the handle of the [Accepting] this produced; `endpoint` is the endpoint's own handle. */
internal expect fun nativeIncomingAcceptWith(handle: Long, endpoint: Long, opts: ByteArray): Long

internal expect fun nativeConnectingRemoteId(handle: Long): ByteArray

/** The negotiated ALPN of a 0-RTT connection, or `null` while the handshake has not settled it. */
internal expect fun nativeZeroRttAlpn(handle: Long): ByteArray?

/** The remote's endpoint id of a 0-RTT connection, or `null` while the handshake has not settled it. */
internal expect fun nativeZeroRttRemoteId(handle: Long): ByteArray?

internal expect fun nativeConnectionAlpn(handle: Long): ByteArray

internal expect fun nativeConnectionRemoteId(handle: Long): ByteArray

internal expect fun nativeConnectionStableId(handle: Long): Long

internal expect fun nativeConnectionCloseReason(handle: Long): ByteArray

internal expect fun nativeConnectionClose(handle: Long, errorCode: Long, reason: ByteArray)

/** `0` for the dialling side, `1` for the accepting one. */
internal expect fun nativeConnectionSide(handle: Long): Long

/** Microseconds, or `-1` when no path reports a round-trip time. */
internal expect fun nativeConnectionRtt(handle: Long): Long

internal expect fun nativeConnectionStats(handle: Long): ByteArray

internal expect fun nativeConnectionPaths(handle: Long): ByteArray

/** Bytes, or `-1` when datagrams cannot be sent at all. */
internal expect fun nativeConnectionMaxDatagramSize(handle: Long): Long

internal expect fun nativeConnectionDatagramSendBufferSpace(handle: Long): Long

internal expect fun nativeConnectionSendDatagram(handle: Long, payload: ByteArray)

internal expect fun nativeConnectionSetMaxConcurrentBiStreams(handle: Long, count: Long)

internal expect fun nativeConnectionSetMaxConcurrentUniStreams(handle: Long, count: Long)

internal expect fun nativeConnectionSetReceiveWindow(handle: Long, bytes: Long)

/** `0` for iroh's `None`, meaning the endpoint has been shut down. */
internal expect suspend fun nativeEndpointAcceptNext(handle: Long): Long

internal expect suspend fun nativeEndpointStartConnect(
    handle: Long,
    addr: ByteArray,
    alpn: ByteArray,
    opts: ByteArray,
): Long

/** Returns the handle of the established [Connection]. */
internal expect suspend fun nativeEndpointConnect(
    handle: Long,
    addr: ByteArray,
    alpn: ByteArray,
): Long

internal expect suspend fun nativeConnectionClosed(handle: Long): String

internal expect suspend fun nativeConnectionSendDatagramWait(handle: Long, payload: ByteArray)

internal expect suspend fun nativeConnectionReadDatagram(handle: Long): ByteArray

internal expect suspend fun nativeAcceptingConnect(handle: Long): Long

internal expect suspend fun nativeAcceptingAlpn(handle: Long): ByteArray

/** Returns the handle of the [IncomingZeroRttConnection] this produced — see [Accepting.zeroRtt]. */
internal expect suspend fun nativeAcceptingZeroRtt(handle: Long): Long

internal expect suspend fun nativeConnectingConnect(handle: Long): Long

internal expect suspend fun nativeConnectingAlpn(handle: Long): ByteArray

/** `0` when this endpoint holds no TLS session ticket for the peer — see [Connecting.zeroRtt]. */
internal expect suspend fun nativeConnectingZeroRtt(handle: Long): Long

/** The handle of the fresh [Connection], and whether the early data was accepted. */
internal expect suspend fun nativeOutgoingZeroRttAwaitHandshake(handle: Long): Pair<Long, Boolean>

/**
 * Returns the handle of the fresh [Connection] — see [IncomingZeroRttConnection.awaitHandshake]. No
 * accepted/rejected pair here: that distinction is the dialling side's alone.
 */
internal expect suspend fun nativeIncomingZeroRttAwaitHandshake(handle: Long): Long
