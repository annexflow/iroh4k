package tech.annexflow.iroh4k

import tech.annexflow.iroh4k.internal.BinaryWriter
import tech.annexflow.iroh4k.internal.BinaryReader

/**
 * Endpoint addressing: [SocketAddr], [CustomAddr], [TransportAddr], [EndpointAddr] and
 * [EndpointTicket].
 *
 * All of them are **value types**, for the reason the keys and relay domains are: an address is
 * inert data with no live resources, so there is nothing to close and nothing to leak. See `addr.rs`
 * for the Rust half of the split — only `SocketAddr` parsing and ticket coding cross the boundary,
 * because only those two have to agree with iroh bit-for-bit.
 *
 * ## Faithful, not flattened
 *
 * iroh-ffi models an endpoint address as `{ id, relay_url: String?, addresses: List<String> }` and
 * converts *from* iroh lossily: a `Custom` transport address is silently dropped, and several relay
 * URLs collapse onto the last one. Reading a ticket and re-issuing it through that model therefore
 * loses addresses without saying so.
 *
 * iroh4k keeps iroh's own shape — an [EndpointId] plus a set of [TransportAddr] — so nothing is
 * discarded on the way through. The flat views iroh-ffi offers are genuinely convenient, so
 * [EndpointAddr.relayUrls], [EndpointAddr.ipAddrs] and [EndpointAddr.isEmpty] exist too; they are
 * just not the only view.
 */

/** Lowercase hex digits, for the pure-formatting conversions below. */
private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex, as iroh's `Display for CustomAddr` writes the opaque data. */
private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

/**
 * Lowercase hex of this `Long` read as a `u64`, with no leading zeros — Rust's `{:x}`.
 *
 * Written by hand rather than with `toString(16)`, which would print `-1` as `-1` instead of
 * `ffffffffffffffff`.
 */
private fun Long.toUnsignedHex(): String {
    val out = StringBuilder(16)
    for (shift in 60 downTo 0 step 4) out.append(HEX_DIGITS[((this ushr shift) and 0xF).toInt()])
    return out.toString().trimStart('0').ifEmpty { "0" }
}

/**
 * An IP address and port, validated by Rust.
 *
 * Instances only exist for strings `std::net::SocketAddr::from_str` accepted, and always hold its
 * **canonical** spelling. That matters for the same reason it matters for [RelayUrl]: an
 * [EndpointAddr] is a set, so `[2001:0db8:0000::0001]:443` and `[2001:db8::1]:443` have to be one
 * member rather than two.
 *
 * Parsing is delegated rather than reimplemented — see [SocketAddr.parse].
 */
class SocketAddr private constructor(private val canonical: String) {

    /** True for an IPv6 address, which the canonical form always brackets. */
    val isIpv6: Boolean get() = canonical.startsWith('[')

    /**
     * The address literal without the port, exactly as it appears in [toString] — so a zoned IPv6
     * address keeps its zone (`fe80::1%3`), and the brackets are not part of it.
     *
     * Split in Kotlin rather than asked of Rust because the canonical form makes it unambiguous:
     * it is never a hostname, and an IPv6 address is always bracketed, so the port is always what
     * follows the last colon. Pure formatting, like [Signature]'s hex.
     */
    val ip: String
        get() = if (isIpv6) canonical.substring(1, canonical.lastIndexOf(']')) else
            canonical.substringBeforeLast(':')

    /** The port, 0..65535. */
    val port: Int get() = canonical.substringAfterLast(':').toInt()

    /** The canonical form, exactly as Rust spells it: `127.0.0.1:1234` or `[2001:db8::1]:443`. */
    override fun toString(): String = canonical

    override fun equals(other: Any?): Boolean =
        this === other || (other is SocketAddr && other.canonical == canonical)

    override fun hashCode(): Int = canonical.hashCode()

    companion object {
        /**
         * Parses [text] as an IP address and port, returning it in canonical form.
         *
         * The work happens in Rust, in `std::net::SocketAddr::from_str`, so what iroh4k accepts is
         * by construction what iroh accepts: IPv6 in every spelling, a numeric zone id
         * (`[fe80::1%3]:80`), and nothing else — not a hostname, not a bare IP without a port, not
         * a port above 65535. A Kotlin-side IP parser would be a second opinion that could only
         * drift from the one that matters.
         *
         * @throws IrohError with [IrohError.Code.Addr] if [text] is not a valid socket address.
         */
        fun parse(text: String): SocketAddr = SocketAddr(nativeAddrSocketParse(text))

        /**
         * Wraps a string that is *already* canonical because Rust produced it — see
         * [RelayUrl.trusted]. Used when decoding an address payload, where re-parsing every entry
         * would mean one boundary crossing per address for no new information.
         */
        internal fun trusted(canonical: String): SocketAddr = SocketAddr(canonical)
    }
}

/**
 * A custom transport address: a transport id plus opaque address data.
 *
 * iroh's extension point for transports it does not implement itself (Bluetooth, Tor, …). Transport
 * ids are freely chosen; the well-known ones are registered in iroh's `TRANSPORTS.md`. The data is
 * neither validated nor size-limited by iroh, and so not by iroh4k either.
 *
 * iroh-ffi drops these on the floor when converting from iroh. Here they are carried through
 * intact, which is the point of modelling [TransportAddr] faithfully.
 */
class CustomAddr(
    /**
     * The transport id.
     *
     * iroh types this as a `u64`. Kotlin has no unsigned `Long` in its public API, so the same 64
     * bits are carried in a signed [Long]: an id above `Long.MAX_VALUE` reads back **negative**
     * here and is still the id iroh sees. Use `id.toULong()` for an unsigned view, or [toString],
     * which prints iroh's unsigned hex.
     */
    val id: Long,
    data: ByteArray,
) {
    // Copied in, and copied out by `data()`. `ByteArray` is mutable, so holding the caller's array
    // would let an address change under everyone already holding it — the same reason
    // `SecretKey.toBytes` copies.
    private val bytes: ByteArray = data.copyOf()

    /** The opaque address data, as a fresh copy. Any length, including empty. */
    fun data(): ByteArray = bytes.copyOf()

    /** How many bytes of address data this holds. */
    val size: Int get() = bytes.size

    // Content equality, hand-written for the reason given on `SecretKey.equals`: a `data class`
    // holding a `ByteArray` gets a referential `equals`, which would report two identical addresses
    // as different — quietly wrong for exactly the comparison an address set has to make.
    override fun equals(other: Any?): Boolean =
        this === other || (other is CustomAddr && other.id == id && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()

    /** iroh's `Display`: the unsigned hex id, an underscore, then the data as lowercase hex. */
    override fun toString(): String = "${id.toUnsignedHex()}_${bytes.toHex()}"
}

/**
 * One network-level address at which an endpoint might be reachable.
 *
 * Mirrors `iroh::TransportAddr`. That enum is `#[non_exhaustive]`, so a newer iroh can add a variant
 * this build has never heard of; such an address decodes to [Unknown] rather than being dropped,
 * which is the whole disagreement with iroh-ffi. [Unknown] is deliberately a dead end in the other
 * direction: an [EndpointAddr] containing one cannot be turned back into a ticket, because iroh4k
 * cannot re-encode what it could not interpret and quietly issuing a ticket with the address missing
 * would be worse than refusing.
 *
 * [toString] mirrors iroh's own `Display for TransportAddr` (`relay:…`, `ip:…`, `custom:…`).
 */
sealed interface TransportAddr {

    /** A relay server the endpoint can be reached through. */
    data class Relay(val url: RelayUrl) : TransportAddr {
        override fun toString(): String = "relay:$url"
    }

    /** A direct IP address and port. */
    data class Ip(val addr: SocketAddr) : TransportAddr {
        override fun toString(): String = "ip:$addr"
    }

    /** An address for a transport iroh itself does not implement. */
    data class Custom(val addr: CustomAddr) : TransportAddr {
        override fun toString(): String = "custom:$addr"
    }

    /**
     * An address of a transport kind this build of iroh4k does not know, preserved for inspection.
     *
     * Only ever produced when decoding — typically from a ticket issued by a newer iroh. Sending an
     * [EndpointAddr] that contains one back across the boundary raises [IrohError] with
     * [IrohError.Code.Addr]; the fix is to remove it or to upgrade iroh4k.
     *
     * @property description the address as iroh's `Display` rendered it.
     */
    data class Unknown(val description: String) : TransportAddr {
        override fun toString(): String = "unknown:$description"
    }
}

/**
 * An endpoint's id together with every network-level address at which it might be reached.
 *
 * Mirrors `iroh::EndpointAddr`: the [id] is required, the addresses are not — an endpoint with none
 * is still connectable via an address-lookup service.
 *
 * **Immutable.** [withRelayUrl], [withIpAddr], [withAddr] and [withAddrs] return a new value and
 * leave the receiver untouched, unlike iroh's own `EndpointAddr` (which mutates `self` and returns
 * it) and iroh-ffi's object wrapper. The reason is [RelayMap]'s: an address is configuration handed
 * to a connect call, and shared mutable configuration is a race rather than a feature.
 *
 * **Set semantics, deterministic order.** iroh keeps the addresses in a `BTreeSet`, so an address is
 * either present or not, order is not part of the value's identity, and a duplicate is a no-op. All
 * three hold here: [equals] and [hashCode] are content-based and order-insensitive, and adding an
 * address already present returns the receiver. What is *not* done is reimplementing Rust's derived
 * `Ord` in Kotlin to reproduce the `BTreeSet` order — that would be a second opinion about a total
 * order over URLs, IPv6 addresses with zone ids, and opaque byte strings, and it would drift from
 * iroh's the first time either side changed. Instead iteration follows insertion order (a
 * `LinkedHashSet`, as [RelayMap] uses), which is deterministic; and any address that came *back*
 * from Rust — from a ticket, say — carries iroh's own `BTreeSet` order, because that is the order
 * the payload was written in. Set-based equality is what makes the two interchangeable.
 */
class EndpointAddr private constructor(
    /** The endpoint's identifier. Required: without it there is nothing to connect to. */
    val id: EndpointId,
    private val addrSet: Set<TransportAddr>,
) {

    /** Every address, in iteration order. */
    val addrs: List<TransportAddr> get() = addrSet.toList()

    /** How many addresses this holds. */
    val size: Int get() = addrSet.size

    /** True when only the [id] is present, matching iroh's `is_empty`. */
    fun isEmpty(): Boolean = addrSet.isEmpty()

    /** True when [addr] is one of this address's transport addresses. */
    operator fun contains(addr: TransportAddr): Boolean = addr in addrSet

    /** The relay URLs, in iteration order. In practice zero or one home relay. */
    val relayUrls: List<RelayUrl>
        get() = addrSet.filterIsInstance<TransportAddr.Relay>().map { it.url }

    /** The direct IP addresses, in iteration order. */
    val ipAddrs: List<SocketAddr>
        get() = addrSet.filterIsInstance<TransportAddr.Ip>().map { it.addr }

    /** The custom transport addresses, in iteration order. */
    val customAddrs: List<CustomAddr>
        get() = addrSet.filterIsInstance<TransportAddr.Custom>().map { it.addr }

    /** This address plus the relay [url]. */
    fun withRelayUrl(url: RelayUrl): EndpointAddr = withAddr(TransportAddr.Relay(url))

    /** This address plus the direct address [addr]. */
    fun withIpAddr(addr: SocketAddr): EndpointAddr = withAddr(TransportAddr.Ip(addr))

    /**
     * This address plus [addr].
     *
     * Adding one already present returns the receiver: the addresses are a set, so a duplicate is
     * not an error and must neither grow the set nor reorder it.
     */
    fun withAddr(addr: TransportAddr): EndpointAddr =
        if (addr in addrSet) this
        else EndpointAddr(id, LinkedHashSet(addrSet).apply { add(addr) })

    /** This address plus every entry of [addrs], de-duplicated. */
    fun withAddrs(addrs: Iterable<TransportAddr>): EndpointAddr {
        val merged = LinkedHashSet(addrSet)
        if (!merged.addAll(addrs.toList())) return this
        return EndpointAddr(id, merged)
    }

    /** This address without [addr], or the receiver unchanged if it was not present. */
    fun without(addr: TransportAddr): EndpointAddr =
        if (addr in addrSet) EndpointAddr(id, LinkedHashSet(addrSet).apply { remove(addr) })
        else this

    override fun equals(other: Any?): Boolean =
        this === other || (other is EndpointAddr && other.id == id && other.addrSet == addrSet)

    override fun hashCode(): Int = 31 * id.hashCode() + addrSet.hashCode()

    override fun toString(): String =
        addrSet.joinToString(prefix = "EndpointAddr($id, [", postfix = "])")

    companion object {
        /** An address carrying only [id] — still usable with an address-lookup service. */
        fun of(id: EndpointId): EndpointAddr = EndpointAddr(id, emptySet())

        /** An address carrying [id] and [addrs], de-duplicated, in the order given. */
        fun fromParts(id: EndpointId, addrs: Iterable<TransportAddr>): EndpointAddr =
            EndpointAddr(id, LinkedHashSet(addrs.toList()))

        /**
         * Builds an address from parts iroh itself just produced, preserving their order.
         *
         * That order is iroh's `BTreeSet` order, so an address decoded from a ticket iterates the
         * way iroh does — see the note on set semantics above.
         */
        internal fun trusted(id: EndpointId, addrs: List<TransportAddr>): EndpointAddr =
            EndpointAddr(id, LinkedHashSet(addrs))
    }
}

/**
 * A token carrying everything needed to connect to an endpoint: its [EndpointAddr], in a form that
 * survives being pasted into a chat message.
 *
 * The string form is iroh's — the `"endpoint"` prefix followed by base32 of a `postcard` body — and
 * it is produced and parsed by iroh rather than reimplemented here, so a ticket from any iroh
 * binding is readable and vice versa.
 *
 * A ticket holds the string *and* the address it encodes, both as iroh returned them. That is why
 * `EndpointTicket.fromString(t.toString())` is a fixed point and why [endpointAddr] is the address
 * iroh actually parsed — normalised and de-duplicated by its own set — rather than the one that was
 * handed in.
 */
class EndpointTicket private constructor(
    private val text: String,
    /** The addressing information this ticket carries. */
    val endpointAddr: EndpointAddr,
) {

    /** The canonical ticket string, ready to be shared. Parsed back by [fromString]. */
    override fun toString(): String = text

    // Equality on the string alone: it is a total function of the address, so two tickets with the
    // same string necessarily carry the same address.
    override fun equals(other: Any?): Boolean =
        this === other || (other is EndpointTicket && other.text == text)

    override fun hashCode(): Int = text.hashCode()

    companion object {
        /**
         * Wraps [addr] as a ticket.
         *
         * @throws IrohError with [IrohError.Code.Addr] if [addr] holds a [TransportAddr.Unknown],
         *   which this build cannot re-encode.
         */
        fun fromAddr(addr: EndpointAddr): EndpointTicket =
            decode(nativeAddrTicketFromAddr(encodeEndpointAddr(addr)))

        /**
         * Parses the string form produced by [toString].
         *
         * @throws IrohError with [IrohError.Code.Ticket] if [text] is not an endpoint ticket —
         *   including an empty string, a ticket of another kind, and a corrupted body.
         */
        fun fromString(text: String): EndpointTicket = decode(nativeAddrTicketFromString(text))

        /** Decodes the ticket payload: the canonical string, then the address inside it. */
        private fun decode(payload: ByteArray): EndpointTicket {
            val reader = BinaryReader(payload)
            val text = reader.string()
            return EndpointTicket(text, reader.readEndpointAddr())
        }
    }
}

// ── The addressing codec ──────────────────────────────────────────────────────────────────────
//
// The layout is defined in `addr.rs`, which documents it in full; this is its Kotlin mirror and the
// two must be changed together.
//
//   EndpointAddr:  bytes id, i32 count, count × TransportAddr
//   TransportAddr: u8 tag, then
//                    0 Relay   — str canonical relay URL
//                    1 Ip      — str canonical socket address
//                    2 Custom  — i64 transport id (u64 bit pattern), bytes opaque data
//                    3 Unknown — str Display text  (decode only; Rust rejects it on the way in)
//   Ticket:        str canonical ticket string, then the EndpointAddr above

internal const val TAG_RELAY = 0
internal const val TAG_IP = 1
internal const val TAG_CUSTOM = 2
internal const val TAG_UNKNOWN = 3


/** Encodes an [EndpointAddr] as the payload `addr.rs`'s `read_endpoint_addr` expects. */
/**
 * Writes one transport address: `u8 tag` then its payload.
 *
 * The single definition of the transport-address wire shape on this side, mirroring `addr.rs`'s
 * `write_transport_addr`. Four domains encode an address; a private copy per domain is how the two
 * ends of one wire format quietly stop agreeing.
 */
internal fun BinaryWriter.writeTransportAddr(transport: TransportAddr) {
    when (transport) {
        is TransportAddr.Relay -> {
            u8(TAG_RELAY)
            string(transport.url.toString())
        }

        is TransportAddr.Ip -> {
            u8(TAG_IP)
            string(transport.addr.toString())
        }

        is TransportAddr.Custom -> {
            u8(TAG_CUSTOM)
            i64(transport.addr.id)
            bytes(transport.addr.data())
        }

        // Encoded rather than rejected here so that the one authority on what iroh4k can represent
        // stays on the Rust side, which answers with a single `Addr` error naming the address.
        is TransportAddr.Unknown -> {
            u8(TAG_UNKNOWN)
            string(transport.description)
        }
    }
}

/**
 * Writes an endpoint address **inline**: its id, then a counted sequence of transport addresses.
 *
 * Inline, not length-prefixed — the Rust readers consume these fields directly out of the
 * surrounding payload. Use this when an address is one field of a larger message;
 * [encodeEndpointAddr] is the standalone form.
 */
internal fun BinaryWriter.writeEndpointAddr(addr: EndpointAddr) {
    bytes(addr.id.toBytes())
    i32(addr.addrs.size)
    for (transport in addr.addrs) writeTransportAddr(transport)
}

/** An endpoint address as a standalone payload. */
internal fun encodeEndpointAddr(addr: EndpointAddr): ByteArray =
    BinaryWriter().apply { writeEndpointAddr(addr) }.finish()

/** Decodes an [EndpointAddr] written by `addr.rs`'s `write_endpoint_addr`. */
internal fun BinaryReader.readEndpointAddr(): EndpointAddr {
    // The id was validated by iroh before it was written, so it needs no second round trip.
    val id = EndpointId.validated(bytes())
    val addrs = seq { it.readTransportAddr() }
    return EndpointAddr.trusted(id, addrs)
}

internal fun BinaryReader.readTransportAddr(): TransportAddr = when (val tag = u8()) {
    TAG_RELAY -> TransportAddr.Relay(RelayUrl.trusted(string()))
    TAG_IP -> TransportAddr.Ip(SocketAddr.trusted(string()))
    // Left-to-right argument evaluation is what keeps these two reads in the encoded order.
    TAG_CUSTOM -> TransportAddr.Custom(CustomAddr(i64(), bytes()))
    TAG_UNKNOWN -> TransportAddr.Unknown(string())
    else -> error("Malformed address payload: unknown transport address tag $tag")
}

// ── The addressing domain's FFI surface, implemented per facade ────────────────────────────────
//
// Names are prefixed `nativeAddr` so each domain's expect declarations stay distinct in this
// shared package.

internal expect fun nativeAddrSocketParse(text: String): String

internal expect fun nativeAddrTicketFromAddr(payload: ByteArray): ByteArray

internal expect fun nativeAddrTicketFromString(text: String): ByteArray
