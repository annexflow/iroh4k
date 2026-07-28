package tech.annexflow.iroh4k

import kotlin.time.Duration

/**
 * QUIC transport parameters, mirroring `iroh::endpoint::QuicTransportConfig`.
 *
 * A value type, like [RelayConfig] and [MdnsConfig]: it is inert data, read once — as
 * [EndpointConfig.transportConfig] it is read when the endpoint binds, and set on a single
 * connection it is read when that connection is opened. A per-connection config **replaces** the
 * endpoint's default outright rather than merging with it: setting one field there does not
 * inherit the other 28 from whatever the endpoint was given, upstream's `set_transport_config`
 * takes a whole [QuicTransportConfig] and there is nothing here that reads two objects together.
 *
 * Every field is nullable, and `null` always means "I did not say" — it leaves whatever upstream's
 * own default is, never "off" or "zero". That distinction matters most here because upstream's
 * private constructor does not start from *its* library defaults: iroh overrides six of them
 * before a caller ever sees the builder, to make hole punching work over QUIC multipath:
 *
 * - `keepAliveInterval` — 5 seconds
 * - `defaultPathKeepAliveInterval` — 5 seconds
 * - `defaultPathMaxIdleTimeout` — 15 seconds
 * - `maxConcurrentMultipathPaths` — 8
 * - `maxRemoteNatTraversalAddresses` — 32
 * - `server_handshake_migration` — `true` (not exposed as a field here; iroh always sets it)
 *
 * A reader who does not know that will misread every `null` in this class as "whatever the QUIC
 * library ships with", when for these six it is really "whatever iroh decided hole punching needs".
 */
class TransportConfig(
    /**
     * The maximum number of bidirectional streams a peer may have open on this connection at once.
     *
     * Must be nonzero for the peer to open any bidirectional stream at all. Worst-case memory use
     * is proportional to `maxConcurrentBidiStreams * streamReceiveWindow`, bounded above by
     * [receiveWindow].
     */
    val maxConcurrentBidiStreams: Long? = null,
    /** As [maxConcurrentBidiStreams], for unidirectional streams. */
    val maxConcurrentUniStreams: Long? = null,
    /**
     * The most a peer may send on any one stream before it must wait for acknowledgement.
     *
     * Worth setting to at least the expected round-trip time multiplied by the desired throughput.
     * Keeping this below [receiveWindow] stops one busy stream from claiming every receive buffer
     * while the application is momentarily not reading from it, starving the connection's other
     * streams.
     */
    val streamReceiveWindow: Long? = null,
    /**
     * The most a peer may send across every stream of this connection combined before it must wait
     * for acknowledgement.
     *
     * As with [streamReceiveWindow], worth setting to at least the expected round-trip time
     * multiplied by the desired throughput. Raising it past [streamReceiveWindow] lets one stream
     * use the full window while another is blocked.
     */
    val receiveWindow: Long? = null,
    /**
     * The most this side will transmit to a peer without acknowledgement.
     *
     * An upper bound on memory when talking to peers that grant a large amount of flow-control
     * credit — worth keeping low for anything that must handle many connections robustly, so that
     * every connection using its entire window at once cannot exhaust memory.
     */
    val sendWindow: Long? = null,
    /**
     * Whether outgoing streams of equal priority are scheduled round-robin rather than in the order
     * they were written to.
     *
     * Only affects streams that share a priority — a higher-priority stream always goes first
     * regardless. Turning fairness off can reduce fragmentation and protocol overhead for workloads
     * built from many small streams.
     */
    val sendFairness: Boolean? = null,
    /**
     * How long the connection may sit idle before it is considered dead.
     *
     * The timeout actually in effect is the smaller of this and whatever the peer asks for.
     * `null` leaves the true idle timeout at upstream's default of 30 seconds — it is **not** an
     * infinite timeout, unlike upstream's own `None`, which this binding cannot express because
     * `null` already means "unset" here.
     *
     * **Careful with a very large value.** If the peer or its network path misbehaves — or acts
     * maliciously — a timeout that never fires can leave a `suspend` call hung on this connection
     * indefinitely, with nothing but the caller's own cancellation left to end it.
     */
    val maxIdleTimeout: Duration? = null,
    /**
     * How often to send a keep-alive packet during inactivity.
     *
     * Keep-alives are what stop an otherwise healthy but quiet connection from timing out, and what
     * keep NAT bindings and firewall pinholes open. iroh already sets this to 5 seconds before any
     * caller sees the builder — see the class documentation — so `null` here means iroh's 5
     * seconds, not upstream's own unset default. To actually take effect it must stay below both
     * peers' [maxIdleTimeout].
     *
     * **Careful raising this.** Upstream's own docs warn it can affect connection stability, and
     * the mechanism is concrete: too long a gap between keep-alives is exactly what lets a NAT
     * binding expire, which is the one thing this setting exists to prevent.
     */
    val keepAliveInterval: Duration? = null,
    /** The round-trip time estimate used before any real sample has been taken. */
    val initialRtt: Duration? = null,
    /**
     * How much packet reordering is tolerated before FACK-style loss detection calls a packet lost.
     *
     * Per RFC 5681, should not be set below 3.
     */
    val packetThreshold: Int? = null,
    /**
     * How much reordering, expressed as a multiple of the round-trip time, is tolerated before
     * time-based loss detection calls a packet lost.
     */
    val timeThreshold: Float? = null,
    /** How many consecutive probe timeouts in a row mean the network has persistent congestion. */
    val persistentCongestionThreshold: Int? = null,
    /**
     * How aggressively this side asks the peer to acknowledge packets.
     *
     * Ignored outright by a peer that does not support the QUIC acknowledgement-frequency
     * extension. `null` disables asking for anything nonstandard — this side still supports the
     * extension either way, so a peer that asks *this* side to tune its own frequency is still
     * honoured.
     */
    val ackFrequency: AckFrequency? = null,
    /**
     * Which congestion control algorithm this connection uses. `null` leaves upstream's own
     * default. See [CongestionController] for what is actually available and why its ordinals
     * matter.
     */
    val congestionController: CongestionController? = null,
    /**
     * The maximum UDP payload size assumed before MTU discovery has run — see [mtuDiscovery].
     *
     * Must be at least 1200, upstream's own default and known-safe floor for the open internet.
     * Larger is more efficient but riskier: a value the path cannot actually carry eventually
     * triggers black-hole detection, which brings the effective MTU down to [minMtu].
     */
    val initialMtu: Int? = null,
    /**
     * The maximum UDP payload size this connection guarantees the network can carry.
     *
     * Must be at least 1200 (the default) and no larger than [initialMtu]. Real-world MTUs depend
     * on the ISP, any VPN, and links neither endpoint controls — raising this outside a network you
     * fully control risks unpredictable, unrepairable packet loss. Prefer tuning [initialMtu]
     * together with [mtuDiscovery] instead of raising this floor directly.
     */
    val minMtu: Int? = null,
    /**
     * Configuration for automatic MTU discovery. `null` leaves it at upstream's default, which is
     * enabled.
     */
    val mtuDiscovery: MtuDiscovery? = null,
    /**
     * Whether to pad application datagrams up to the current maximum UDP payload size.
     *
     * Disabled by default; loss-probe datagrams are never padded regardless. Padding mitigates
     * traffic analysis — without it, an observer able to see packet sizes can infer the exact
     * plaintext size of application datagrams and the size of stream write bursts, under an
     * uncongested connection or datagrams too large to coalesce. The cost is extra bandwidth.
     */
    val padToMtu: Boolean? = null,
    /**
     * The size of the buffer for received datagrams, or **`0` to refuse datagrams entirely**.
     *
     * That mapping is worth reading twice: upstream models this as an optional value where *absent*
     * means "no datagrams", but `null` here already means "I did not say", so the two cannot share a
     * spelling. `0` carries the refusal — a zero-byte receive buffer and refusing datagrams are the
     * same thing operationally. A caller who writes `0` expecting a very small buffer gets something
     * else.
     *
     * With datagrams refused, the peer's [Connection.maxDatagramSize] reports `null`.
     */
    val datagramReceiveBufferSize: Int? = null,
    /**
     * The size of the buffer for outgoing datagrams.
     *
     * An application can produce datagrams faster than the link — or the hardware underneath it —
     * can send them; this bounds the memory that can consume. Once the buffer is full, sending
     * another datagram drops the oldest buffered ones rather than blocking.
     */
    val datagramSendBufferSize: Int? = null,
    /** The most out-of-order handshake-layer crypto data this connection will buffer. */
    val cryptoBufferSize: Int? = null,
    /**
     * Whether this side may set the QUIC spin bit.
     *
     * The spin bit lets a passive network observer estimate round-trip time without decrypting
     * anything, which is useful for network operators but a small privacy cost for everyone else.
     */
    val allowSpin: Boolean? = null,
    /**
     * Whether to use Generic Segmentation Offload to accelerate sending, where the platform
     * supports it.
     *
     * `null` leaves upstream's default of enabled. GSO cuts CPU cost sharply when sending many
     * packets that share a header — bulk transfer, chiefly — but not every network driver or packet
     * inspection tool supports it; the underlying UDP layer tries to detect and fall back
     * automatically, which can itself cause a burst of packet loss right at startup.
     */
    val enableSegmentationOffload: Boolean? = null,
    /**
     * Whether to tell peers what address their packets appear to come from.
     *
     * Helps a peer behind a NAT work out its own reachable address, which it usually cannot learn
     * any other way.
     */
    val sendObservedAddressReports: Boolean? = null,
    /**
     * Whether to accept observed-address reports from peers that offer them.
     *
     * A peer that both supports the address-discovery extension and is willing to report is the
     * only source for these — and even then, an observed address is inherently something the peer
     * *claims*, not something this side can verify. It is still useful for exactly the case
     * [sendObservedAddressReports] describes: working out this endpoint's own reachable address
     * behind a NAT.
     */
    val receiveObservedAddressReports: Boolean? = null,
    /**
     * How many paths this connection may use at once, for iroh's multipath.
     *
     * **A value below 8 is ignored**, silently — upstream logs a warning and keeps its own. iroh4k
     * passes what you give it rather than rejecting, so that behaviour here matches the same call
     * from Rust exactly; the consequence is that setting 4 leaves you with 8 and no error.
     */
    val maxConcurrentMultipathPaths: Int? = null,
    /**
     * How long a single path may sit idle before it is dropped.
     *
     * **Clamped to 15 seconds**, silently: upstream logs a warning and uses its own value for
     * anything larger. See [maxConcurrentMultipathPaths] for why iroh4k passes it through anyway.
     */
    val defaultPathMaxIdleTimeout: Duration? = null,
    /**
     * How often to keep a single path alive.
     *
     * **A value above 5 seconds is ignored**, silently. See [maxConcurrentMultipathPaths] for why
     * iroh4k passes it through anyway.
     */
    val defaultPathKeepAliveInterval: Duration? = null,
    /**
     * How many NAT-traversal addresses this endpoint will let the remote peer advertise, enabling
     * iroh's own hole punching (loosely modelled on QUIC's NAT traversal extension draft).
     *
     * Requires multipath to be enabled too; if [maxConcurrentMultipathPaths] was never set, upstream
     * falls back to its own default of 8 rather than treating multipath as off. **A value below 8 is
     * ignored**, silently. See [maxConcurrentMultipathPaths] for why iroh4k passes it through
     * anyway.
     */
    val maxRemoteNatTraversalAddresses: Int? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is TransportConfig &&
                other.maxConcurrentBidiStreams == maxConcurrentBidiStreams &&
                other.maxConcurrentUniStreams == maxConcurrentUniStreams &&
                other.streamReceiveWindow == streamReceiveWindow &&
                other.receiveWindow == receiveWindow &&
                other.sendWindow == sendWindow &&
                other.sendFairness == sendFairness &&
                other.maxIdleTimeout == maxIdleTimeout &&
                other.keepAliveInterval == keepAliveInterval &&
                other.initialRtt == initialRtt &&
                other.packetThreshold == packetThreshold &&
                other.timeThreshold == timeThreshold &&
                other.persistentCongestionThreshold == persistentCongestionThreshold &&
                other.ackFrequency == ackFrequency &&
                other.congestionController == congestionController &&
                other.initialMtu == initialMtu &&
                other.minMtu == minMtu &&
                other.mtuDiscovery == mtuDiscovery &&
                other.padToMtu == padToMtu &&
                other.datagramReceiveBufferSize == datagramReceiveBufferSize &&
                other.datagramSendBufferSize == datagramSendBufferSize &&
                other.cryptoBufferSize == cryptoBufferSize &&
                other.allowSpin == allowSpin &&
                other.enableSegmentationOffload == enableSegmentationOffload &&
                other.sendObservedAddressReports == sendObservedAddressReports &&
                other.receiveObservedAddressReports == receiveObservedAddressReports &&
                other.maxConcurrentMultipathPaths == maxConcurrentMultipathPaths &&
                other.defaultPathMaxIdleTimeout == defaultPathMaxIdleTimeout &&
                other.defaultPathKeepAliveInterval == defaultPathKeepAliveInterval &&
                other.maxRemoteNatTraversalAddresses == maxRemoteNatTraversalAddresses)

    override fun hashCode(): Int {
        var result = maxConcurrentBidiStreams.hashCode()
        result = 31 * result + maxConcurrentUniStreams.hashCode()
        result = 31 * result + streamReceiveWindow.hashCode()
        result = 31 * result + receiveWindow.hashCode()
        result = 31 * result + sendWindow.hashCode()
        result = 31 * result + sendFairness.hashCode()
        result = 31 * result + maxIdleTimeout.hashCode()
        result = 31 * result + keepAliveInterval.hashCode()
        result = 31 * result + initialRtt.hashCode()
        result = 31 * result + packetThreshold.hashCode()
        result = 31 * result + timeThreshold.hashCode()
        result = 31 * result + persistentCongestionThreshold.hashCode()
        result = 31 * result + ackFrequency.hashCode()
        result = 31 * result + congestionController.hashCode()
        result = 31 * result + initialMtu.hashCode()
        result = 31 * result + minMtu.hashCode()
        result = 31 * result + mtuDiscovery.hashCode()
        result = 31 * result + padToMtu.hashCode()
        result = 31 * result + datagramReceiveBufferSize.hashCode()
        result = 31 * result + datagramSendBufferSize.hashCode()
        result = 31 * result + cryptoBufferSize.hashCode()
        result = 31 * result + allowSpin.hashCode()
        result = 31 * result + enableSegmentationOffload.hashCode()
        result = 31 * result + sendObservedAddressReports.hashCode()
        result = 31 * result + receiveObservedAddressReports.hashCode()
        result = 31 * result + maxConcurrentMultipathPaths.hashCode()
        result = 31 * result + defaultPathMaxIdleTimeout.hashCode()
        result = 31 * result + defaultPathKeepAliveInterval.hashCode()
        result = 31 * result + maxRemoteNatTraversalAddresses.hashCode()
        return result
    }

    override fun toString(): String = "TransportConfig(" +
            "maxConcurrentBidiStreams=$maxConcurrentBidiStreams, " +
            "maxConcurrentUniStreams=$maxConcurrentUniStreams, " +
            "streamReceiveWindow=$streamReceiveWindow, " +
            "receiveWindow=$receiveWindow, " +
            "sendWindow=$sendWindow, " +
            "sendFairness=$sendFairness, " +
            "maxIdleTimeout=$maxIdleTimeout, " +
            "keepAliveInterval=$keepAliveInterval, " +
            "initialRtt=$initialRtt, " +
            "packetThreshold=$packetThreshold, " +
            "timeThreshold=$timeThreshold, " +
            "persistentCongestionThreshold=$persistentCongestionThreshold, " +
            "ackFrequency=$ackFrequency, " +
            "congestionController=$congestionController, " +
            "initialMtu=$initialMtu, " +
            "minMtu=$minMtu, " +
            "mtuDiscovery=$mtuDiscovery, " +
            "padToMtu=$padToMtu, " +
            "datagramReceiveBufferSize=$datagramReceiveBufferSize, " +
            "datagramSendBufferSize=$datagramSendBufferSize, " +
            "cryptoBufferSize=$cryptoBufferSize, " +
            "allowSpin=$allowSpin, " +
            "enableSegmentationOffload=$enableSegmentationOffload, " +
            "sendObservedAddressReports=$sendObservedAddressReports, " +
            "receiveObservedAddressReports=$receiveObservedAddressReports, " +
            "maxConcurrentMultipathPaths=$maxConcurrentMultipathPaths, " +
            "defaultPathMaxIdleTimeout=$defaultPathMaxIdleTimeout, " +
            "defaultPathKeepAliveInterval=$defaultPathKeepAliveInterval, " +
            "maxRemoteNatTraversalAddresses=$maxRemoteNatTraversalAddresses" +
            ")"
}

/**
 * Configuration for QUIC's automatic MTU discovery, mirroring `noq`'s `MtuDiscoveryConfig`.
 *
 * A value type, like [TransportConfig] and read the same way: once, when the connection it applies
 * to is set up. Every field is nullable and `null` leaves upstream's own default — see
 * [TransportConfig.mtuDiscovery].
 */
class MtuDiscovery(
    val interval: Duration? = null,
    val upperBound: Int? = null,
    val blackHoleCooldown: Duration? = null,
    val minimumChange: Int? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is MtuDiscovery &&
                other.interval == interval &&
                other.upperBound == upperBound &&
                other.blackHoleCooldown == blackHoleCooldown &&
                other.minimumChange == minimumChange)

    override fun hashCode(): Int {
        var result = interval.hashCode()
        result = 31 * result + upperBound.hashCode()
        result = 31 * result + blackHoleCooldown.hashCode()
        result = 31 * result + minimumChange.hashCode()
        return result
    }

    override fun toString(): String =
        "MtuDiscovery(interval=$interval, upperBound=$upperBound, " +
                "blackHoleCooldown=$blackHoleCooldown, minimumChange=$minimumChange)"
}

/**
 * Configuration for QUIC's acknowledgement-frequency extension, mirroring `noq`'s
 * `AckFrequencyConfig`.
 *
 * A value type, like [TransportConfig] and read the same way: once, when the connection it applies
 * to is set up. Every field is nullable and `null` leaves upstream's own default — see
 * [TransportConfig.ackFrequency].
 */
class AckFrequency(
    val ackElicitingThreshold: Long? = null,
    val maxAckDelay: Duration? = null,
    val reorderingThreshold: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is AckFrequency &&
                other.ackElicitingThreshold == ackElicitingThreshold &&
                other.maxAckDelay == maxAckDelay &&
                other.reorderingThreshold == reorderingThreshold)

    override fun hashCode(): Int {
        var result = ackElicitingThreshold.hashCode()
        result = 31 * result + maxAckDelay.hashCode()
        result = 31 * result + reorderingThreshold.hashCode()
        return result
    }

    override fun toString(): String =
        "AckFrequency(ackElicitingThreshold=$ackElicitingThreshold, maxAckDelay=$maxAckDelay, " +
                "reorderingThreshold=$reorderingThreshold)"
}

/**
 * The congestion control algorithm.
 *
 * The **ordinals** are the wire protocol shared with `transport.rs`, exactly as [EndpointPreset]'s
 * are with `builder_for`: do not reorder, append only. Upstream takes a factory object here rather
 * than a name, but the *choice* of algorithm is a named choice and so crosses as data — the same
 * reasoning that lets a preset cross as an ordinal.
 *
 * These two are all that `noq` implements. There is no BBR.
 */
enum class CongestionController { Cubic, NewReno }
