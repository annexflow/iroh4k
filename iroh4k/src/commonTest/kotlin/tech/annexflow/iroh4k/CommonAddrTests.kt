package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import kotlin.test.assertFailsWith

/**
 * Test bodies shared by every target, so the FFI (Kotlin/Native) and JNI (JVM/Android) facades are
 * held to identical behaviour for the addressing domain.
 *
 * Platform test classes construct this and delegate one `@Test` per method — see
 * `nativeTest`/`jvmTest`.
 */
class CommonAddrTests {

    /** A real endpoint id, since every address needs one and it is not what is under test. */
    private val id: EndpointId = SecretKey.generate().public()

    /** A second id, for the assertions that two addresses differing only by id are not equal. */
    private val otherId: EndpointId = SecretKey.generate().public()

    // ── SocketAddr ───────────────────────────────────────────────────────────────────────────

    fun `socket addresses come back in Rust's canonical form`() {
        // Each expectation is what `std::net::SocketAddr`'s Display produces for what its FromStr
        // accepted. Kotlin must not second-guess any of it, because these canonical strings are
        // what an EndpointAddr's set is keyed on — two spellings of one address must be one member.
        val cases = mapOf(
            "127.0.0.1:1234" to "127.0.0.1:1234",
            "0.0.0.0:0" to "0.0.0.0:0",
            "255.255.255.255:65535" to "255.255.255.255:65535",
            "[::1]:80" to "[::1]:80",
            "[::]:0" to "[::]:0",
            // IPv6 is re-compressed, so a fully expanded spelling normalises onto the short one.
            "[2001:0db8:0000:0000:0000:0000:0000:0001]:443" to "[2001:db8::1]:443",
            "[2001:db8::1]:443" to "[2001:db8::1]:443",
            // An IPv4-mapped address keeps its dotted tail rather than becoming hex.
            "[::ffff:127.0.0.1]:8080" to "[::ffff:127.0.0.1]:8080",
            // A *numeric* zone id survives; this is one of the forms a hand-rolled Kotlin parser
            // would most likely get wrong.
            "[fe80::1%3]:80" to "[fe80::1%3]:80",
        )
        for ((input, canonical) in cases) {
            assertThat(SocketAddr.parse(input).toString()).isEqualTo(canonical)
        }
    }

    fun `invalid socket addresses raise an Addr error`() {
        val invalid = listOf(
            "",
            "   ",
            "garbage",
            // No port at all.
            "127.0.0.1",
            "[::1]",
            // Out of range: a port is a u16.
            "127.0.0.1:70000",
            "127.0.0.1:-1",
            // A hostname is not a socket address, however tempting.
            "example.com:80",
            // IPv6 must be bracketed, and only numeric zone ids are understood.
            "::1:80",
            "[fe80::1%eth0]:80",
            // Surrounding whitespace is not trimmed by Rust, and must not be trimmed here either.
            " 1.2.3.4:80",
            "1.2.3.4:80 ",
            // Leading zeros in an octet are rejected.
            "1.2.3.04:80",
        )
        for (input in invalid) {
            val error = assertFailsWith<IrohError>("expected $input to be rejected") {
                SocketAddr.parse(input)
            }
            assertThat(error.code).isEqualTo(IrohError.Code.Addr)
            // The message must name what was being parsed, or a failure is undiagnosable.
            assertThat(error.message!!).contains("socket address")
        }
    }

    fun `socket address accessors split the canonical form`() {
        val v4 = SocketAddr.parse("192.168.1.10:9999")
        assertThat(v4.isIpv6).isFalse()
        assertThat(v4.ip).isEqualTo("192.168.1.10")
        assertThat(v4.port).isEqualTo(9999)

        val v6 = SocketAddr.parse("[2001:db8::1]:443")
        assertThat(v6.isIpv6).isTrue()
        // The brackets belong to the socket-address form, not to the address literal.
        assertThat(v6.ip).isEqualTo("2001:db8::1")
        assertThat(v6.port).isEqualTo(443)

        // A zoned address keeps its zone in `ip`: this is the address literal exactly as it appears
        // in `toString`, which is deliberately *not* Rust's `.ip()` (that one drops the scope id).
        val zoned = SocketAddr.parse("[fe80::1%3]:80")
        assertThat(zoned.ip).isEqualTo("fe80::1%3")
        assertThat(zoned.port).isEqualTo(80)

        // Port 0 is legal and must not be confused with "no port".
        assertThat(SocketAddr.parse("[::]:0").port).isEqualTo(0)
    }

    fun `socket address equality follows canonicalisation`() {
        val expanded = SocketAddr.parse("[2001:0db8:0000:0000:0000:0000:0000:0001]:443")
        val compressed = SocketAddr.parse("[2001:db8::1]:443")

        // Two spellings of one address must be one value, or an EndpointAddr would hold it twice.
        assertThat(expanded).isEqualTo(compressed)
        assertThat(expanded.hashCode()).isEqualTo(compressed.hashCode())
        // Re-parsing a canonical form must be a fixed point.
        assertThat(SocketAddr.parse(compressed.toString())).isEqualTo(compressed)

        // The port is part of the identity, and so is the address family.
        assertThat(SocketAddr.parse("1.2.3.4:80")).isNotEqualTo(SocketAddr.parse("1.2.3.4:81"))
        assertThat(SocketAddr.parse("1.2.3.4:80")).isNotEqualTo(SocketAddr.parse("1.2.3.5:80"))
    }

    // ── CustomAddr ───────────────────────────────────────────────────────────────────────────

    fun `custom addresses are content-valued and print like iroh`() {
        val addr = CustomAddr(1, byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte()))
        assertThat(addr.size).isEqualTo(3)
        assertThat(addr.data()).isEqualTo(byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte()))
        // iroh's `Display for CustomAddr`: unsigned hex id, '_', then lowercase hex data.
        assertThat(addr.toString()).isEqualTo("1_a1b2c3")
        assertThat(CustomAddr(0x2A, ByteArray(0)).toString()).isEqualTo("2a_")
        // A `u64` above Long.MAX_VALUE reads back negative but still prints unsigned, as iroh does.
        assertThat(CustomAddr(-1L, byteArrayOf(1)).toString()).isEqualTo("ffffffffffffffff_01")

        // Content equality, not reference equality — a `data class` over a ByteArray would fail
        // this, which is why it is hand-written.
        assertThat(CustomAddr(7, byteArrayOf(1, 2))).isEqualTo(CustomAddr(7, byteArrayOf(1, 2)))
        assertThat(CustomAddr(7, byteArrayOf(1, 2)).hashCode())
            .isEqualTo(CustomAddr(7, byteArrayOf(1, 2)).hashCode())
        assertThat(CustomAddr(7, byteArrayOf(1, 2))).isNotEqualTo(CustomAddr(8, byteArrayOf(1, 2)))
        assertThat(CustomAddr(7, byteArrayOf(1, 2))).isNotEqualTo(CustomAddr(7, byteArrayOf(1, 3)))

        // The data is copied in and out, so a caller cannot mutate an address after building it.
        val mutable = byteArrayOf(1, 2, 3)
        val held = CustomAddr(9, mutable)
        mutable[0] = 42
        assertThat(held.data()).isEqualTo(byteArrayOf(1, 2, 3))
        held.data()[0] = 42
        assertThat(held.data()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    // ── EndpointAddr ─────────────────────────────────────────────────────────────────────────

    fun `an endpoint address starts out empty`() {
        val addr = EndpointAddr.of(id)
        assertThat(addr.id).isEqualTo(id)
        assertThat(addr.isEmpty()).isTrue()
        assertThat(addr.size).isEqualTo(0)
        assertThat(addr.addrs).isEmpty()
        assertThat(addr.relayUrls).isEmpty()
        assertThat(addr.ipAddrs).isEmpty()
        assertThat(addr.customAddrs).isEmpty()
        assertThat(EndpointAddr.fromParts(id, emptyList())).isEqualTo(addr)
    }

    fun `withRelayUrl and withIpAddr leave the receiver unchanged`() {
        val relay = RelayUrl.parse("https://relay.example.com")
        val ip = SocketAddr.parse("127.0.0.1:1234")

        val base = EndpointAddr.of(id)
        val withRelay = base.withRelayUrl(relay)
        val withBoth = withRelay.withIpAddr(ip)

        // The whole reason the type is immutable: neither call may have mutated its receiver.
        assertThat(base.isEmpty()).isTrue()
        assertThat(withRelay.size).isEqualTo(1)
        assertThat(withRelay.ipAddrs).isEmpty()
        assertThat(withBoth.size).isEqualTo(2)

        assertThat(withBoth.contains(TransportAddr.Relay(relay))).isTrue()
        assertThat(withBoth.contains(TransportAddr.Ip(ip))).isTrue()
        assertThat(base.contains(TransportAddr.Relay(relay))).isFalse()
        // Iteration follows insertion for an address built here.
        assertThat(withBoth.addrs)
            .containsExactly(TransportAddr.Relay(relay), TransportAddr.Ip(ip))

        // withAddrs merges, and also leaves its receiver alone.
        val custom = TransportAddr.Custom(CustomAddr(5, byteArrayOf(9)))
        val merged = withBoth.withAddrs(listOf(custom, TransportAddr.Ip(ip)))
        assertThat(merged.size).isEqualTo(3)
        assertThat(withBoth.size).isEqualTo(2)
    }

    fun `adding a duplicate address does not grow the set`() {
        val relay = RelayUrl.parse("https://relay.example.com")
        val ip = SocketAddr.parse("127.0.0.1:1234")
        val addr = EndpointAddr.of(id).withRelayUrl(relay).withIpAddr(ip)

        // The addresses are a set, exactly as iroh's BTreeSet is: a duplicate is not an error, must
        // not grow the address, and must not move the existing entry to the end. Each duplicate is
        // written in a *different* spelling, so this also proves canonicalisation is what makes the
        // two collide rather than raw string equality.
        val again = addr
            .withRelayUrl(RelayUrl.parse("HTTPS://Relay.Example.com:443"))
            .withIpAddr(SocketAddr.parse("127.0.0.1:1234"))
            .withAddr(TransportAddr.Relay(relay))
        assertThat(again.size).isEqualTo(2)
        assertThat(again.addrs).containsExactly(TransportAddr.Relay(relay), TransportAddr.Ip(ip))
        assertThat(again).isEqualTo(addr)

        // fromParts de-duplicates the same way.
        assertThat(
            EndpointAddr.fromParts(
                id,
                listOf(TransportAddr.Ip(ip), TransportAddr.Ip(ip), TransportAddr.Relay(relay)),
            ).size
        ).isEqualTo(2)
    }

    fun `endpoint address equality is content-based and order-insensitive`() {
        val relay = TransportAddr.Relay(RelayUrl.parse("https://relay.example.com"))
        val ip = TransportAddr.Ip(SocketAddr.parse("[::1]:80"))

        val forwards = EndpointAddr.fromParts(id, listOf(relay, ip))
        val backwards = EndpointAddr.fromParts(id, listOf(ip, relay))

        // Order is not part of the value's identity, because it is not part of iroh's: it keeps the
        // addresses in a BTreeSet. This is what lets an address built here compare equal to the
        // same address decoded from a ticket in iroh's own order.
        assertThat(forwards).isEqualTo(backwards)
        assertThat(forwards.hashCode()).isEqualTo(backwards.hashCode())
        // ...but iteration order still follows insertion, so output is deterministic.
        assertThat(backwards.addrs).containsExactly(ip, relay)

        // The id is part of the identity, and so are the addresses.
        assertThat(forwards).isNotEqualTo(EndpointAddr.fromParts(otherId, listOf(relay, ip)))
        assertThat(forwards).isNotEqualTo(EndpointAddr.fromParts(id, listOf(relay)))
        assertThat(EndpointAddr.of(id)).isNotEqualTo(EndpointAddr.of(otherId))
    }

    fun `the convenience views project each transport kind`() {
        val relayA = RelayUrl.parse("https://a.example.com")
        val relayB = RelayUrl.parse("https://b.example.com")
        val v4 = SocketAddr.parse("192.0.2.1:4433")
        val v6 = SocketAddr.parse("[2001:db8::1]:4433")
        val custom = CustomAddr(0x2A, byteArrayOf(1, 2, 3))

        val addr = EndpointAddr.fromParts(
            id,
            listOf(
                TransportAddr.Relay(relayA),
                TransportAddr.Relay(relayB),
                TransportAddr.Ip(v4),
                TransportAddr.Ip(v6),
                TransportAddr.Custom(custom),
            ),
        )

        assertThat(addr.size).isEqualTo(5)
        assertThat(addr.isEmpty()).isFalse()
        // Both relay URLs are visible. iroh-ffi's flat model keeps only the last one.
        assertThat(addr.relayUrls).containsExactly(relayA, relayB)
        assertThat(addr.ipAddrs).containsExactly(v4, v6)
        // And the custom address is a first-class member rather than something dropped in a `_` arm.
        assertThat(addr.customAddrs).containsExactly(custom)
        // The views partition the whole set: nothing is in two of them and nothing is missing.
        assertThat(addr.relayUrls.size + addr.ipAddrs.size + addr.customAddrs.size)
            .isEqualTo(addr.size)
    }

    fun `without removes exactly one address`() {
        val relay = TransportAddr.Relay(RelayUrl.parse("https://relay.example.com"))
        val ip = TransportAddr.Ip(SocketAddr.parse("127.0.0.1:1"))
        val absent = TransportAddr.Ip(SocketAddr.parse("127.0.0.1:2"))
        val addr = EndpointAddr.fromParts(id, listOf(relay, ip))

        val without = addr.without(relay)
        assertThat(without.addrs).containsExactly(ip)
        // The receiver is untouched.
        assertThat(addr.contains(relay)).isTrue()

        assertThat(addr.without(absent)).isEqualTo(addr)
        assertThat(without.without(ip).isEmpty()).isTrue()
        // The id survives removal of the last address.
        assertThat(without.without(ip).id).isEqualTo(id)
    }

    fun `toString mirrors iroh's rendering`() {
        val addr = EndpointAddr.of(id)
            .withRelayUrl(RelayUrl.parse("https://relay.example.com"))
            .withIpAddr(SocketAddr.parse("127.0.0.1:1234"))
            .withAddr(TransportAddr.Custom(CustomAddr(1, byteArrayOf(0xAB.toByte()))))

        // Each transport address renders as iroh's own `Display for TransportAddr` does.
        assertThat(addr.addrs.map { it.toString() }).containsExactly(
            "relay:https://relay.example.com/",
            "ip:127.0.0.1:1234",
            "custom:1_ab",
        )
        assertThat(addr.toString()).isEqualTo(
            "EndpointAddr($id, [relay:https://relay.example.com/, ip:127.0.0.1:1234, custom:1_ab])"
        )
        assertThat(EndpointAddr.of(id).toString()).isEqualTo("EndpointAddr($id, [])")
        assertThat(TransportAddr.Unknown("future:1").toString()).isEqualTo("unknown:future:1")
    }

    // ── EndpointTicket ───────────────────────────────────────────────────────────────────────

    fun `a ticket preserves every transport address`() {
        // The regression test for iroh-ffi's lossy conversion. Its flat model would lose the second
        // relay URL (only the last one survives) and drop the Custom address entirely, so if this
        // implementation ever flattens the same way, the equality assertion below fails.
        val relayA = RelayUrl.parse("https://a.relay.example.com")
        val relayB = RelayUrl.parse("https://b.relay.example.com:8443")
        val v4 = SocketAddr.parse("192.0.2.1:4433")
        val v6 = SocketAddr.parse("[2001:db8::1]:4433")
        val custom = CustomAddr(0x2A, byteArrayOf(1, 2, 3, 4, 5))

        val original = EndpointAddr.fromParts(
            id,
            listOf(
                TransportAddr.Relay(relayA),
                TransportAddr.Relay(relayB),
                TransportAddr.Ip(v4),
                TransportAddr.Ip(v6),
                TransportAddr.Custom(custom),
            ),
        )

        val ticket = EndpointTicket.fromAddr(original)
        val reread = EndpointTicket.fromString(ticket.toString()).endpointAddr

        // Nothing added, nothing lost, nothing altered — on both the direct and the re-parsed path.
        assertThat(ticket.endpointAddr).isEqualTo(original)
        assertThat(reread).isEqualTo(original)
        assertThat(reread.id).isEqualTo(id)
        assertThat(reread.size).isEqualTo(5)

        // Spelled out per kind, so a failure says *which* address went missing rather than only
        // that the sets differ.
        assertThat(reread.relayUrls).hasSize(2)
        assertThat(reread.relayUrls.toSet()).isEqualTo(setOf(relayA, relayB))
        assertThat(reread.ipAddrs.toSet()).isEqualTo(setOf(v4, v6))
        assertThat(reread.customAddrs).containsExactly(custom)
        assertThat(reread.customAddrs.single().data()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
    }

    fun `an IPv6 zone id does not survive a ticket`() {
        // Pinned rather than hidden. `SocketAddr.parse` keeps a numeric zone id because Rust does,
        // but a ticket cannot carry one: iroh serialises a `SocketAddrV6` with postcard, and serde's
        // compact form for it is `(octets, port)` — flowinfo and scope id have no place in the wire
        // format. So a zoned address comes back unzoned, and callers who care must not rely on a
        // link-local address surviving a ticket.
        //
        // This is upstream behaviour, not an iroh4k choice: the address is not *dropped* (which is
        // what this domain refuses to do), it comes back as the same address without its zone.
        val zoned = SocketAddr.parse("[fe80::1%3]:4433")
        val addr = EndpointAddr.of(id).withIpAddr(zoned)

        val reread = EndpointTicket.fromString(EndpointTicket.fromAddr(addr).toString()).endpointAddr

        assertThat(reread.ipAddrs).containsExactly(SocketAddr.parse("[fe80::1]:4433"))
        assertThat(reread.ipAddrs.single().ip).isEqualTo("fe80::1")
        assertThat(reread).isNotEqualTo(addr)
    }

    fun `ticket strings round-trip through fromString`() {
        val addr = EndpointAddr.of(id).withIpAddr(SocketAddr.parse("127.0.0.1:1234"))
        val ticket = EndpointTicket.fromAddr(addr)

        // iroh's canonical form: the lowercase kind prefix followed by base32 of the body.
        assertThat(ticket.toString()).startsWith("endpoint")
        assertThat(ticket.toString().drop("endpoint".length).all { it.isLowerCase() || it.isDigit() })
            .isTrue()

        // Parsing the string form must be a fixed point, in both the string and the value.
        val parsed = EndpointTicket.fromString(ticket.toString())
        assertThat(parsed.toString()).isEqualTo(ticket.toString())
        assertThat(parsed).isEqualTo(ticket)
        assertThat(parsed.hashCode()).isEqualTo(ticket.hashCode())
        assertThat(EndpointTicket.fromString(parsed.toString())).isEqualTo(ticket)

        // Re-issuing from the decoded address reproduces the same ticket, which is the property that
        // makes a ticket safe to read and forward.
        assertThat(EndpointTicket.fromAddr(parsed.endpointAddr)).isEqualTo(ticket)
        // A different address is a different ticket.
        assertThat(EndpointTicket.fromAddr(EndpointAddr.of(otherId))).isNotEqualTo(ticket)
    }

    fun `malformed ticket strings raise a Ticket error`() {
        val valid = EndpointTicket.fromAddr(
            EndpointAddr.of(id).withIpAddr(SocketAddr.parse("127.0.0.1:1234"))
        ).toString()

        val invalid = listOf(
            // Empty: no prefix at all.
            "",
            "   ",
            "garbage",
            // The right prefix, but a body that is not base32.
            "endpoint!!!!",
            // The right prefix and valid base32, but a body postcard cannot read as a ticket.
            "endpointaaaa",
            // A ticket of some other kind: the prefix names the wrong sort of thing.
            "blob" + valid.removePrefix("endpoint"),
            // The prefix must be exactly lowercase "endpoint".
            valid.uppercase(),
            // A truncated body.
            valid.dropLast(6),
            // The bare base32 body with no prefix.
            valid.removePrefix("endpoint"),
        )
        for (input in invalid) {
            val error = assertFailsWith<IrohError>("expected ${input.take(20)} to be rejected") {
                EndpointTicket.fromString(input)
            }
            assertThat(error.code).isEqualTo(IrohError.Code.Ticket)
            assertThat(error.message!!).contains("ticket")
        }
    }

    fun `an endpoint address with no transport addresses round-trips`() {
        // An id-only address is legal — it is still resolvable via an address-lookup service — so a
        // ticket for one has to survive the trip rather than being rejected as empty.
        val ticket = EndpointTicket.fromAddr(EndpointAddr.of(id))
        val reread = EndpointTicket.fromString(ticket.toString()).endpointAddr

        assertThat(reread.id).isEqualTo(id)
        assertThat(reread.isEmpty()).isTrue()
        assertThat(reread).isEqualTo(EndpointAddr.of(id))
    }

    fun `a custom address survives a ticket round trip with a large unsigned id`() {
        // 0xFFFFFFFFFFFFFFFF and 0x8000000000000000 are both above Long.MAX_VALUE, so they read
        // back negative in Kotlin. The bits are what has to survive, not the sign.
        val allOnes = CustomAddr(-1L, byteArrayOf(0x01, 0x02))
        val highBit = CustomAddr(Long.MIN_VALUE, byteArrayOf(0x7F))
        // iroh stores custom data inline below 30 bytes and on the heap above it; cover both.
        val inline = CustomAddr(1, ByteArray(30) { it.toByte() })
        val heap = CustomAddr(0xDEADBEEF, ByteArray(64) { (255 - it).toByte() })
        val empty = CustomAddr(0, ByteArray(0))

        val originals = listOf(allOnes, highBit, inline, heap, empty)
        val addr = EndpointAddr.fromParts(id, originals.map { TransportAddr.Custom(it) })
        val reread = EndpointTicket.fromString(
            EndpointTicket.fromAddr(addr).toString()
        ).endpointAddr

        assertThat(reread).isEqualTo(addr)
        assertThat(reread.customAddrs).hasSize(originals.size)
        val byId = reread.customAddrs.associateBy { it.id }
        for (original in originals) {
            val restored = byId[original.id]
            assertThat(restored).isEqualTo(original)
            assertThat(restored!!.data()).isEqualTo(original.data())
            assertThat(restored.id).isEqualTo(original.id)
        }
        // The negative Longs really are the unsigned ids iroh saw.
        assertThat(byId[-1L]!!.toString()).isEqualTo("ffffffffffffffff_0102")
        assertThat(byId[Long.MIN_VALUE]!!.toString()).isEqualTo("8000000000000000_7f")
    }

    fun `an unknown transport address cannot be re-encoded`() {
        // TransportAddr is `#[non_exhaustive]` upstream, so a newer iroh can hand us a kind this
        // build has never seen. It decodes to Unknown rather than being silently dropped — and
        // sending it back is refused, because issuing a ticket that quietly lacks an address the
        // caller put in it is the very bug this domain is written to avoid.
        val addr = EndpointAddr.of(id)
            .withIpAddr(SocketAddr.parse("127.0.0.1:1234"))
            .withAddr(TransportAddr.Unknown("bluetooth:00:11:22:33:44:55"))

        // The value itself is intact and inspectable.
        assertThat(addr.size).isEqualTo(2)
        assertThat(addr.contains(TransportAddr.Unknown("bluetooth:00:11:22:33:44:55"))).isTrue()

        val error = assertFailsWith<IrohError> { EndpointTicket.fromAddr(addr) }
        assertThat(error.code).isEqualTo(IrohError.Code.Addr)
        // The message has to name the offending address, or the caller cannot remove it.
        assertThat(error.message!!).contains("bluetooth:00:11:22:33:44:55")

        // Without it, the very same address encodes fine — so the refusal is about that one member.
        val ticket = EndpointTicket.fromAddr(addr.without(TransportAddr.Unknown("bluetooth:00:11:22:33:44:55")))
        assertThat(ticket.endpointAddr.size).isEqualTo(1)
    }

    fun `a ticket carries iroh's canonical address ordering`() {
        // Deliberately inserted in the reverse of iroh's order. What comes back out is ordered by
        // its BTreeSet — Relay before Ip before Custom — which is how an address decoded from a
        // ticket iterates. Kotlin does not reimplement that ordering; it receives it.
        val addr = EndpointAddr.fromParts(
            id,
            listOf(
                TransportAddr.Custom(CustomAddr(1, byteArrayOf(1))),
                TransportAddr.Ip(SocketAddr.parse("127.0.0.1:1")),
                TransportAddr.Relay(RelayUrl.parse("https://relay.example.com")),
            ),
        )
        val reread = EndpointTicket.fromAddr(addr).endpointAddr

        val ranks = reread.addrs.map {
            when (it) {
                is TransportAddr.Relay -> 0
                is TransportAddr.Ip -> 1
                is TransportAddr.Custom -> 2
                is TransportAddr.Unknown -> 3
            }
        }
        assertThat(ranks).containsExactly(0, 1, 2)
        // And the reordering does not change the value, because equality is set-based.
        assertThat(reread).isEqualTo(addr)
        assertThat(reread.addrs).isNotEqualTo(addr.addrs)
    }
}
