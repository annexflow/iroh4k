package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Test bodies shared by every target, so the FFI (Kotlin/Native) and JNI (JVM/Android) facades are
 * held to identical behaviour for the endpoint domain.
 *
 * Platform test classes construct this and delegate one `@Test` per method — see
 * `nativeTest`/`jvmTest`.
 *
 * ## Hermetic by construction
 *
 * Every endpoint here is bound with [EndpointPreset.Minimal], [RelayMode.Disabled] and an explicit
 * loopback bind address. That combination is what makes the suite offline rather than merely
 * *usually* offline:
 *
 * - `Minimal` installs a crypto provider and nothing else, so nothing publishes to or queries n0's
 *   DNS servers. (`N0` and even `N0DisableRelay` both do.) The one address lookup an iroh4k endpoint
 *   always carries is the in-memory book behind [Endpoint.addEndpointAddr]: it resolves only what
 *   Kotlin put into it and publishes nowhere, so it sends no packet of its own.
 * - `RelayMode.Disabled` means there is no relay to dial.
 * - `bindAddrs = [127.0.0.1:0]` replaces iroh's default `0.0.0.0` and `[::]` sockets, so the only
 *   socket that exists is on loopback.
 *
 * The consequence to keep in mind while reading: [Endpoint.online] can never complete under this
 * configuration, because there is no home relay to connect to. That is not a defect of the test
 * setup — it is the property the cancellation test relies on.
 */
class CommonEndpointTests {

    /** The loopback bind address every endpoint here uses; port `0` asks the OS for a free one. */
    private val loopback: SocketAddr get() = SocketAddr.parse("127.0.0.1:0")

    /** A configuration that cannot reach the network — see the class documentation. */
    private fun config(
        secretKey: SecretKey? = null,
        alpns: List<ByteArray> = emptyList(),
        bindAddrs: List<SocketAddr> = listOf(loopback),
        externalAddrs: List<SocketAddr> = emptyList(),
    ) = EndpointConfig(
        preset = EndpointPreset.Minimal,
        secretKey = secretKey,
        alpns = alpns,
        relayMode = RelayMode.Disabled,
        bindAddrs = bindAddrs,
        externalAddrs = externalAddrs,
    )

    // ── Binding and identity ─────────────────────────────────────────────────────────────────

    fun `an endpoint binds with the identity it was given`() = runTest {
        val secret = SecretKey.generate()
        val expected = secret.public()

        Endpoint.bind(config(secretKey = secret)).use { endpoint ->
            // The id is derived from the key, so it must be exactly the one the caller can
            // pre-compute — that is what makes an identity survive a restart.
            assertThat(endpoint.id).isEqualTo(expected)
            assertThat(endpoint.secretKey()).isEqualTo(secret)
            // And the address agrees with it. An `EndpointAddr` whose id differed from the
            // endpoint's own would be undialable, so this is not a redundant assertion.
            assertThat(endpoint.addr().id).isEqualTo(expected)
            assertThat(endpoint.isClosed).isFalse()
        }
    }

    fun `an endpoint without a secret key gets a fresh identity`() = runTest {
        // Two binds with no key must not accidentally share one, which is what a mishandled
        // `Option<SecretKey>` on the Rust side would produce.
        val first = Endpoint.bind(config())
        val second = Endpoint.bind(config())
        try {
            assertThat(first.id).isNotEqualTo(second.id)
            assertThat(first.secretKey()).isNotEqualTo(second.secretKey())
            // Each still reports the id its own key derives.
            assertThat(first.secretKey().public()).isEqualTo(first.id)
            assertThat(second.secretKey().public()).isEqualTo(second.id)
        } finally {
            first.close()
            second.close()
        }
    }

    fun `two endpoints bind to distinct real ports`() = runTest {
        val first = Endpoint.bind(config())
        val second = Endpoint.bind(config())
        try {
            val firstSockets = first.boundSockets()
            val secondSockets = second.boundSockets()

            // One socket each, because the loopback bind address *replaced* iroh's defaults rather
            // than being added to them — the deviation from iroh-ffi documented on
            // `EndpointConfig.bindAddrs`.
            assertThat(firstSockets).hasSize(1)
            assertThat(secondSockets).hasSize(1)

            for (socket in firstSockets + secondSockets) {
                assertThat(socket.ip).isEqualTo("127.0.0.1")
                // Port 0 was a request, not an answer: the OS assigned a real one.
                assertThat(socket.port).isGreaterThan(0)
            }
            assertThat(firstSockets.single().port).isNotEqualTo(secondSockets.single().port)

            // Each endpoint's address carries its own bound socket, so the two can see each other's
            // addressing information without a connection ever being made.
            assertThat(first.addr().ipAddrs).contains(firstSockets.single())
            assertThat(second.addr().ipAddrs).contains(secondSockets.single())
            assertThat(first.addr()).isNotEqualTo(second.addr())
        } finally {
            first.close()
            second.close()
        }
    }

    fun `binding on a port already in use raises a Bind error`() = runTest {
        Endpoint.bind(config()).use { held ->
            val endpoints = LiveCounters.endpointHandles
            LiveCounters.settle()
            val baseline = endpoints.value
            val taken = held.boundSockets().single()

            // `is_required` defaults to true for an explicitly named bind address, so the second
            // bind must fail rather than quietly falling back to another port.
            val error = assertFailsWith<IrohError> {
                Endpoint.bind(config(bindAddrs = listOf(SocketAddr.parse(taken.toString()))))
            }
            assertThat(error.code).isEqualTo(IrohError.Code.Bind)

            // The failed bind released its handle, so nothing was stranded in Rust. A ceiling on a
            // settled baseline, not an equality: the counter is process-global. See `LiveCounter`.
            endpoints.awaitAtMost(baseline)
        }
    }

    fun `the Empty preset cannot bind`() = runTest {
        val endpoints = LiveCounters.endpointHandles
        LiveCounters.settle()
        val baseline = endpoints.value

        // Documented upstream behaviour rather than a limitation here: `presets::Empty` installs no
        // TLS crypto provider, and `Builder::bind` requires one. The preset is still offered
        // because it is the honest baseline for a fully explicit configuration.
        val error = assertFailsWith<IrohError> {
            Endpoint.bind(
                EndpointConfig(
                    preset = EndpointPreset.Empty,
                    relayMode = RelayMode.Disabled,
                    bindAddrs = listOf(loopback),
                )
            )
        }
        assertThat(error.code).isEqualTo(IrohError.Code.Bind)
        // And it leaked nothing on the way out.
        endpoints.awaitAtMost(baseline)
    }

    fun `an external address given at bind time is accepted`() = runTest {
        val external = SocketAddr.parse("203.0.113.7:4433")
        Endpoint.bind(config(externalAddrs = listOf(external))).use { endpoint ->
            // The builder path and the runtime path share one set, so removing it here proves the
            // address really went in at bind time rather than being silently dropped.
            assertThat(endpoint.removeExternalAddr(external)).isTrue()
            assertThat(endpoint.removeExternalAddr(external)).isFalse()
        }
    }

    // ── ALPNs ────────────────────────────────────────────────────────────────────────────────

    fun `alpns are configurable at bind time and replaceable afterwards`() = runTest {
        val alpns = listOf("iroh4k/test/1".encodeToByteArray(), byteArrayOf(0, 1, 2, 0xFF.toByte()))

        Endpoint.bind(config(alpns = alpns)).use { endpoint ->
            // iroh exposes no getter for the negotiated ALPN list, so what is verifiable here is
            // that both paths accept the values — including a non-UTF-8 one, which is why an ALPN
            // is `ByteArray` and not `String`.
            endpoint.setAlpns(listOf("iroh4k/test/2".encodeToByteArray()))
            endpoint.setAlpns(emptyList())
            endpoint.setAlpns(alpns)
            // Still alive and unchanged in identity after all that.
            assertThat(endpoint.isClosed).isFalse()
        }
    }

    fun `an endpoint config copies its alpns`() {
        // Pure Kotlin, no endpoint needed: a configuration that changed under the caller after it
        // was built would be a confusing bug, so the arrays are copied in and out.
        val mutable = byteArrayOf(1, 2, 3)
        val configuration = config(alpns = listOf(mutable))

        mutable[0] = 42
        assertThat(configuration.alpns.single()).isEqualTo(byteArrayOf(1, 2, 3))
        configuration.alpns.single()[0] = 42
        assertThat(configuration.alpns.single()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    // ── Metrics ──────────────────────────────────────────────────────────────────────────────

    fun `stats reports iroh's own metrics`() = runTest {
        Endpoint.bind(config()).use { endpoint ->
            val stats = endpoint.stats()

            // A bound endpoint always has metrics; an empty map would mean the `metrics` feature is
            // off or the group iteration broke.
            assertThat(stats.isEmpty()).isFalse()
            assertThat(stats.size).isGreaterThan(5)

            for ((name, counter) in stats) {
                // Keyed "<group>:<metric>", as iroh-ffi keys them.
                assertThat(name).contains(":")
                assertThat(name.substringBefore(':').isNotEmpty()).isTrue()
                assertThat(name.substringAfter(':').isNotEmpty()).isTrue()
                // Every metric carries iroh's help text, which is what makes the map readable.
                assertThat(counter.description.isNotEmpty()).isTrue()
            }

            // The key set is stable across snapshots — only the values move — so a caller can
            // sample the same metric twice.
            assertThat(endpoint.stats().keys).isEqualTo(stats.keys)
        }
    }

    // ── Relay configuration at runtime ───────────────────────────────────────────────────────

    fun `relay configurations can be inserted and removed`() = runTest {
        val url = RelayUrl.parse("https://relay.example.com")
        val configuration = RelayConfig(url, quicPort = 7842, authToken = "hunter2")

        Endpoint.bind(config()).use { endpoint ->
            // Nothing was configured for this relay, so nothing is replaced.
            assertThat(endpoint.insertRelay(configuration)).isNull()

            // Inserting again replaces it, and what comes back is the configuration that was there
            // — round-tripped through iroh's own `RelayConfig`, so this also pins the codec for the
            // optional QUIC port and the auth token.
            val replaced = endpoint.insertRelay(RelayConfig(url))
            assertThat(replaced).isNotNull()
            assertThat(replaced!!.url).isEqualTo(url)
            assertThat(replaced.quicPort).isEqualTo(7842)
            assertThat(replaced.authToken).isEqualTo("hunter2")

            // The replacement is what is there now: no QUIC port, no token.
            val removed = endpoint.removeRelay(url)
            assertThat(removed).isNotNull()
            assertThat(removed!!.quicPort).isNull()
            assertThat(removed.authToken).isNull()

            // Removing again finds nothing, and so does removing one that never existed.
            assertThat(endpoint.removeRelay(url)).isNull()
            assertThat(endpoint.removeRelay(RelayUrl.parse("https://other.example.com"))).isNull()
        }
    }

    fun `a relay configuration with an impossible quic port is rejected`() = runTest {
        Endpoint.bind(config()).use { endpoint ->
            // A port is a u16 in iroh; the Kotlin field is `Int?` because there is no unsigned
            // short in the public API, so the range check lives in Rust and reports `Relay`.
            for (port in listOf(70_000, -2, Int.MAX_VALUE)) {
                val error = assertFailsWith<IrohError>("expected port $port to be rejected") {
                    endpoint.insertRelay(RelayConfig(RelayUrl.parse("https://r.example.com"), port))
                }
                assertThat(error.code).isEqualTo(IrohError.Code.Relay)
            }
            // `-1` is the wire sentinel for "absent" and so is *not* an error; it is simply how
            // `quicPort = null` is encoded, and the check must not confuse the two.
            assertThat(endpoint.insertRelay(RelayConfig(RelayUrl.parse("https://r.example.com"))))
                .isNull()
        }
    }

    // ── External addresses and network changes ───────────────────────────────────────────────

    fun `external addresses can be added and removed at runtime`() = runTest {
        val v4 = SocketAddr.parse("198.51.100.9:4433")
        val v6 = SocketAddr.parse("[2001:db8::9]:4433")

        Endpoint.bind(config()).use { endpoint ->
            // Nothing to remove yet.
            assertThat(endpoint.removeExternalAddr(v4)).isFalse()

            endpoint.addExternalAddr(v4)
            endpoint.addExternalAddr(v6)
            // Adding twice is not an error: the addresses are a set.
            endpoint.addExternalAddr(v4)

            assertThat(endpoint.removeExternalAddr(v4)).isTrue()
            assertThat(endpoint.removeExternalAddr(v4)).isFalse()
            assertThat(endpoint.removeExternalAddr(v6)).isTrue()
        }
    }

    fun `notifying a network change is harmless`() = runTest {
        Endpoint.bind(config()).use { endpoint ->
            // iroh documents this as safe to call whenever, which is what Android's
            // ConnectivityManager callback needs. Twice, to cover the repeat.
            endpoint.networkChange()
            endpoint.networkChange()
            assertThat(endpoint.isClosed).isFalse()
        }
    }

    // ── The address book ─────────────────────────────────────────────────────────────────────

    fun `an added endpoint address lets an id-only dial resolve`() = Loopback.bounded {
        Endpoint.bind(Loopback.config(alpns = listOf(Loopback.alpn))).use { server ->
            // An id and nothing else: no relay URL, no IP socket. The only lookup a `Minimal`
            // endpoint has is its own address book, which starts empty and queries nothing, so
            // the only thing that can turn this into somewhere to send a packet is an entry
            // `addEndpointAddr` put there.
            val idOnly = EndpointAddr.of(server.id)

            Endpoint.bind(Loopback.config()).use { client ->
                client.addEndpointAddr(server.addr())

                val accepted = async { server.acceptOne() }
                client.connect(idOnly, Loopback.alpn).use { connection ->
                    accepted.await().use { served ->
                        // Both ends really are this pair, so the id resolved to the right endpoint
                        // rather than to whatever happened to be listening on loopback.
                        assertThat(connection.remoteId()).isEqualTo(server.id)
                        assertThat(served.remoteId()).isEqualTo(client.id)
                    }
                }
            }

            // The control, and the reason the assertion above means anything: a client that never
            // recorded the address cannot dial the same id. Without this the test would pass with
            // an `addEndpointAddr` that did nothing at all, because iroh would have found the
            // server some other way.
            Endpoint.bind(Loopback.config()).use { stranger ->
                // The server keeps accepting across the failing dial, for the reason spelled out
                // in `a removed endpoint address stops resolving an id-only dial`: with nobody
                // answering, this dial fails on a handshake timeout whatever the address book
                // holds, and the control would then hold against an `addEndpointAddr` that did
                // nothing at all — which is the one thing it exists to rule out.
                val accepting = async { server.acceptOne() }
                try {
                    val error =
                        assertFailsWith<IrohError> { stranger.connect(idOnly, Loopback.alpn) }
                    assertThat(error.code).isEqualTo(IrohError.Code.Connect)
                } finally {
                    accepting.cancelAndJoin()
                }
            }
        }
    }

    fun `adding an endpoint address accumulates rather than replacing`() = Loopback.bounded {
        Endpoint.bind(Loopback.config(alpns = listOf(Loopback.alpn))).use { server ->
            Endpoint.bind(Loopback.config()).use { client ->
                client.addEndpointAddr(server.addr())
                // Adding the same address twice is not an error — the addresses are a set, as they
                // are for `addExternalAddr`.
                client.addEndpointAddr(server.addr())

                // Then a second entry for the *same* id carrying nothing but a dead loopback port.
                // If the insert replaced, the reachable address would be gone and the dial below
                // could only fail; it succeeding is what "the new addresses are added to the ones
                // already recorded" looks like from outside. Port 1 on loopback so a wrong answer
                // costs one refused datagram on this host rather than a packet on the network.
                val dead = EndpointAddr.of(server.id).withIpAddr(SocketAddr.parse("127.0.0.1:1"))
                client.addEndpointAddr(dead)

                val accepted = async { server.acceptOne() }
                client.connect(EndpointAddr.of(server.id), Loopback.alpn).use { connection ->
                    accepted.await().use { served ->
                        assertThat(served.remoteId()).isEqualTo(client.id)
                    }
                }
            }
        }
    }

    fun `a removed endpoint address stops resolving an id-only dial`() = Loopback.bounded {
        Endpoint.bind(Loopback.config(alpns = listOf(Loopback.alpn))).use { server ->
            val idOnly = EndpointAddr.of(server.id)

            // Two clients, differing in exactly one call, rather than one client dialling twice.
            // A client that has already connected keeps the path it learned for that peer and
            // resolves the second dial from it whatever the address book now says, so reusing one
            // would prove nothing about the removal.
            Endpoint.bind(Loopback.config()).use { keeper ->
                keeper.addEndpointAddr(server.addr())

                val accepted = async { server.acceptOne() }
                keeper.connect(idOnly, Loopback.alpn).use { connection ->
                    accepted.await().use { served ->
                        assertThat(connection.remoteId()).isEqualTo(server.id)
                        assertThat(served.remoteId()).isEqualTo(keeper.id)
                    }
                }
            }

            Endpoint.bind(Loopback.config()).use { forgetful ->
                forgetful.addEndpointAddr(server.addr())
                // There was an entry to remove, which is what makes the failure below a retraction
                // rather than an address that was never recorded in the first place.
                assertThat(forgetful.removeEndpointAddr(server.id)).isTrue()

                // The server keeps accepting across the failing dial, so the failure below is for
                // want of an address rather than for want of anybody to answer. Without it the dial
                // fails either way — after thirty seconds, as a handshake timeout — and the
                // assertion would hold just as well against a `removeEndpointAddr` that did
                // nothing.
                val accepting = async { server.acceptOne() }
                try {
                    val error =
                        assertFailsWith<IrohError> { forgetful.connect(idOnly, Loopback.alpn) }
                    assertThat(error.code).isEqualTo(IrohError.Code.Connect)
                } finally {
                    accepting.cancelAndJoin()
                }
            }
        }
    }

    fun `removing an endpoint address reports whether there was one`() = runTest {
        val peer = EndpointAddr.of(SecretKey.generate().public())
            .withIpAddr(SocketAddr.parse("198.51.100.9:4433"))
        val other = EndpointAddr.of(SecretKey.generate().public())
            .withIpAddr(SocketAddr.parse("198.51.100.10:4433"))

        Endpoint.bind(config()).use { endpoint ->
            // Nothing recorded yet, so there is nothing to retract. Not an error — the same answer
            // `removeExternalAddr` gives for an address that was never advertised.
            assertThat(endpoint.removeEndpointAddr(peer.id)).isFalse()

            endpoint.addEndpointAddr(peer)
            assertThat(endpoint.removeEndpointAddr(peer.id)).isTrue()
            // The book holds one record per peer and the removal takes all of it, so a second
            // attempt finds nothing rather than a remnant of the first entry.
            assertThat(endpoint.removeEndpointAddr(peer.id)).isFalse()

            // Entries are keyed by id, so removing one peer's leaves every other peer's alone.
            endpoint.addEndpointAddr(peer)
            endpoint.addEndpointAddr(other)
            assertThat(endpoint.removeEndpointAddr(peer.id)).isTrue()
            assertThat(endpoint.removeEndpointAddr(other.id)).isTrue()

            // Its own id is not in there either: the address book is about remotes, and nothing
            // records an endpoint in its own.
            assertThat(endpoint.removeEndpointAddr(endpoint.id)).isFalse()
        }
    }

    fun `an endpoint address this build cannot re-encode is refused`() = runTest {
        val unknown = TransportAddr.Unknown("some-future-transport://host")
        val addr = EndpointAddr.of(SecretKey.generate().public())
            .withIpAddr(SocketAddr.parse("127.0.0.1:4433"))
            .withAddr(unknown)

        Endpoint.bind(config()).use { endpoint ->
            // `Unknown` only ever comes *from* a newer Rust core, and sending one back is refused
            // rather than silently dropped — the rule `Addr.kt` documents. Recording an address
            // whose reachable half was quietly discarded is exactly the failure this refusal is
            // for, since the caller would then be told the peer is unreachable.
            val error = assertFailsWith<IrohError> { endpoint.addEndpointAddr(addr) }
            assertThat(error.code).isEqualTo(IrohError.Code.Addr)
            assertThat(error.message!!).contains("some-future-transport://host")

            // Without that one member the very same address is accepted, so the refusal is about
            // the transport rather than about the address.
            endpoint.addEndpointAddr(addr.without(unknown))
        }
    }

    fun `an unknown remote has no address`() = runTest {
        val stranger = SecretKey.generate().public()
        Endpoint.bind(config()).use { endpoint ->
            // Never contacted, so there is nothing cached — the absent arm of the optional payload.
            assertThat(endpoint.remoteAddr(stranger)).isNull()
            // Not even about itself: `remote_info` is about *remotes*.
            assertThat(endpoint.remoteAddr(endpoint.id)).isNull()
        }
    }

    // ── Shutdown ─────────────────────────────────────────────────────────────────────────────

    fun `shutdown closes the endpoint and is safe to repeat`() = runTest {
        Endpoint.bind(config()).use { endpoint ->
            assertThat(endpoint.isClosed).isFalse()

            endpoint.shutdown()
            assertThat(endpoint.isClosed).isTrue()

            // Idempotent. This is iroh's async `close()`, renamed because `AutoCloseable.close` is
            // synchronous; calling it twice must not raise.
            endpoint.shutdown()
            endpoint.shutdown()
            assertThat(endpoint.isClosed).isTrue()

            // The endpoint stays *queryable* after shutdown, exactly as iroh's own does — only
            // `close()` takes that away.
            assertThat(endpoint.id).isEqualTo(endpoint.secretKey().public())
            assertThat(endpoint.isReleased).isFalse()
        }
    }

    fun `a shut down endpoint reports iroh's own no-op answers`() = runTest {
        Endpoint.bind(config()).use { endpoint ->
            val url = RelayUrl.parse("https://relay.example.com")
            val addr = SocketAddr.parse("198.51.100.1:1234")
            endpoint.addExternalAddr(addr)
            endpoint.shutdown()

            // iroh answers `None`/`false` for these on a closed endpoint and logs a warning rather
            // than failing, so neither does iroh4k. `null` here therefore means "no entry, or the
            // endpoint is closed" — which is why `isClosed` exists to disambiguate.
            assertThat(endpoint.insertRelay(RelayConfig(url))).isNull()
            assertThat(endpoint.removeRelay(url)).isNull()
            assertThat(endpoint.removeExternalAddr(addr)).isFalse()
            assertThat(endpoint.remoteAddr(SecretKey.generate().public())).isNull()

            // These are ignored outright, and must not raise.
            endpoint.addExternalAddr(addr)
            endpoint.networkChange()
            endpoint.setAlpns(listOf("after/shutdown".encodeToByteArray()))
        }
    }

    // ── Release ──────────────────────────────────────────────────────────────────────────────

    fun `using a released endpoint raises Closed rather than crashing`() = runTest {
        val endpoint = Endpoint.bind(config())
        val id = endpoint.id
        endpoint.close()

        assertThat(endpoint.isReleased).isTrue()

        // Every member, not one: a guard that only covered the first method anybody tried would
        // pass a narrower test and still dereference a freed pointer on the next call.
        val synchronous: List<Pair<String, () -> Any?>> = listOf(
            "id" to { endpoint.id },
            "secretKey" to { endpoint.secretKey() },
            "addr" to { endpoint.addr() },
            "boundSockets" to { endpoint.boundSockets() },
            "stats" to { endpoint.stats() },
            "isClosed" to { endpoint.isClosed },
            "setAlpns" to { endpoint.setAlpns(emptyList()) },
            "addEndpointAddr" to { endpoint.addEndpointAddr(EndpointAddr.of(id)) },
            "removeEndpointAddr" to { endpoint.removeEndpointAddr(id) },
        )
        for ((name, call) in synchronous) {
            val error = assertFailsWith<IrohError>("expected $name to report Closed") { call() }
            assertThat(error.code).isEqualTo(IrohError.Code.Closed)
        }

        val suspending: List<Pair<String, suspend () -> Any?>> = listOf(
            "shutdown" to { endpoint.shutdown() },
            "online" to { endpoint.online() },
            "networkChange" to { endpoint.networkChange() },
            "remoteAddr" to { endpoint.remoteAddr(id) },
            "addExternalAddr" to { endpoint.addExternalAddr(loopback) },
            "removeExternalAddr" to { endpoint.removeExternalAddr(loopback) },
            "insertRelay" to { endpoint.insertRelay(RelayConfig(RelayUrl.parse("https://a.test"))) },
            "removeRelay" to { endpoint.removeRelay(RelayUrl.parse("https://a.test")) },
        )
        for ((name, call) in suspending) {
            val error = assertFailsWith<IrohError>("expected $name to report Closed") { call() }
            assertThat(error.code).isEqualTo(IrohError.Code.Closed)
        }

        // `close()` itself stays idempotent, and `isReleased` keeps answering rather than raising —
        // it is the one member that can be asked about a released endpoint.
        endpoint.close()
        endpoint.close()
        assertThat(endpoint.isReleased).isTrue()
        assertThat(endpoint.toString()).isEqualTo("Endpoint(released)")
    }

    fun `an endpoint works as an AutoCloseable resource`() = runTest {
        val endpoints = LiveCounters.endpointHandles
        LiveCounters.settle()
        val baseline = endpoints.value

        val id = Endpoint.bind(config()).use { endpoint ->
            // An exact reading, not a ceiling: the claim is that the bind registered exactly one
            // handle *right now*, and a wait would let a later unrelated increment satisfy it.
            // Sound because the baseline was settled first — nothing else in the process is
            // moving this counter while a single-threaded test holds one endpoint open.
            assertThat(endpoints.value, "$endpoints inside use").isEqualTo(baseline + 1)
            endpoint.id
        }

        // Leaving the block released the handle.
        endpoints.awaitAtMost(baseline)

        // And `use` propagates a failure while still releasing.
        val boom = assertFailsWith<IllegalStateException> {
            Endpoint.bind(config()).use { error("deliberate") }
        }
        assertThat(boom.message!!).isEqualTo("deliberate")
        endpoints.awaitAtMost(baseline)

        // The id read inside the block is still a perfectly good value afterwards: it was copied
        // out of Rust, unlike the endpoint itself.
        assertThat(id.toBytes().size).isEqualTo(32)
    }

    // ── Cancellation and leaks ───────────────────────────────────────────────────────────────

    fun `cancelling online returns promptly and drains the op registry`() = runTest {
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val baseline = ops.value

        Endpoint.bind(config()).use { endpoint ->
            // With relays disabled there is no home relay to wait for, so iroh's `online()` waits
            // on a watcher that will never update. Nothing but cancellation can end it, which is
            // exactly the property every long iroh operation has and why the ops registry exists.
            val job = launch(Dispatchers.Default) { endpoint.online() }
            // Registered before it is cancelled, or the abort path is never exercised at all.
            ops.awaitAtLeast(baseline + 1)

            val elapsed = measureTime { job.cancelAndJoin() }

            assertThat(job.isCancelled).isTrue()
            // Must not wait out an operation that never finishes.
            assertThat(elapsed < 5.seconds).isTrue()
            // The wait *is* the assertion; an abort that left its registry entry behind never
            // comes back down and this fails naming the counter and the reading.
            ops.awaitAtMost(baseline)

            // The endpoint survived having an operation aborted under it.
            assertThat(endpoint.isClosed).isFalse()
            assertThat(endpoint.boundSockets()).hasSize(1)
        }

        ops.awaitAtMost(baseline)
    }

    fun `online can be bounded by a timeout`() = runTest {
        Endpoint.bind(config()).use { endpoint ->
            val ops = LiveCounters.operations
            LiveCounters.settle()
            val baseline = ops.value
            // The idiom the documentation on `online()` points callers at, exercised end to end:
            // `withTimeout` cancels, cancellation reaches Rust, and the registry drains.
            val completed = withContext(Dispatchers.Default) {
                withTimeoutOrNull(300) { endpoint.online() }
            }
            assertThat(completed).isNull()
            ops.awaitAtMost(baseline)
        }
    }

    fun `repeated bind and shutdown cycles leak neither handles nor operations`() = runTest {
        val endpoints = LiveCounters.endpointHandles
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val handleBaseline = endpoints.value
        val opBaseline = ops.value

        repeat(6) {
            val endpoint = Endpoint.bind(config())
            assertThat(endpoint.boundSockets()).hasSize(1)
            endpoint.shutdown()
            assertThat(endpoint.isClosed).isTrue()
            endpoint.close()
        }

        // Both registries must come back down to where they started. Without the handle counter a
        // leak here would be entirely invisible: the test would pass while every cycle stranded a
        // socket. Six cycles, so a per-cycle leak overshoots the ceiling by six.
        endpoints.awaitAtMost(handleBaseline)
        ops.awaitAtMost(opBaseline)
    }

    fun `closing an endpoint while a call is in flight is safe`() = runTest {
        val endpoints = LiveCounters.endpointHandles
        val ops = LiveCounters.operations
        // Settled, because the assertion below this test's whole point is an *exact* one, and an
        // exact reading against a baseline still draining from the previous test would be a coin
        // toss in the direction that reports a use-after-free guard as broken when it is not.
        LiveCounters.settle()
        val handleBaseline = endpoints.value
        val opBaseline = ops.value
        val endpoint = Endpoint.bind(config())

        val job = launch(Dispatchers.Default) { endpoint.online() }
        ops.awaitAtLeast(opBaseline + 1)

        // The race the guard exists for: a release arriving while another coroutine is inside the
        // handle. The handle must survive until that call returns — a plain closed flag would let
        // the free happen here and the in-flight call dereference freed memory.
        endpoint.close()
        assertThat(endpoint.isReleased).isTrue()
        // Exact and instantaneous on purpose. This is the assertion that caught a use-after-free:
        // it must fail if the handle was freed *at this moment*, so it cannot become a ceiling or
        // a wait — either would be satisfied by a handle that is about to be freed, or already is.
        assertThat(endpoints.value, "$endpoints while a call is in flight")
            .isEqualTo(handleBaseline + 1)

        // Meanwhile a new call is already refused.
        assertFailsWith<IrohError> { endpoint.isClosed }

        job.cancelAndJoin()

        // Only once the in-flight call has finished is the handle actually freed.
        endpoints.awaitAtMost(handleBaseline)
        ops.awaitAtMost(opBaseline)
    }

    // ── Value types ──────────────────────────────────────────────────────────────────────────

    fun `relay config is a value and hides its token`() {
        val url = RelayUrl.parse("https://relay.example.com")

        assertThat(RelayConfig(url, 443, "t")).isEqualTo(RelayConfig(url, 443, "t"))
        assertThat(RelayConfig(url, 443, "t").hashCode())
            .isEqualTo(RelayConfig(url, 443, "t").hashCode())
        assertThat(RelayConfig(url, 443, "t")).isNotEqualTo(RelayConfig(url, 444, "t"))
        assertThat(RelayConfig(url, 443, "t")).isNotEqualTo(RelayConfig(url, 443, "u"))
        assertThat(RelayConfig(url, 443, "t")).isNotEqualTo(RelayConfig(url, null, "t"))
        assertThat(RelayConfig(url)).isNotEqualTo(RelayConfig(RelayUrl.parse("https://b.test")))

        // A token is a credential, so it never renders — for the reason `SecretKey.toString` does
        // not render key material: a `toString` in a log line is how secrets escape.
        assertThat(RelayConfig(url, 443, "hunter2").toString()).contains("quicPort=443")
        assertThat(RelayConfig(url, 443, "hunter2").toString().contains("hunter2")).isFalse()
        assertThat(RelayConfig(url).toString()).contains("authToken=null")
    }

    fun `mdns config is a value and is absent unless asked for`() {
        // No endpoint here binds with mDNS, and none anywhere in this suite does: joining a
        // multicast group would put traffic on the test host's local link, which is the one thing
        // "hermetic by construction" rules out. So what is testable is the value type and the
        // default — the wiring from here to a discovered peer is not covered, and the README says
        // so rather than implying otherwise.
        assertThat(EndpointConfig().mdns).isNull()

        // Advertising is the default because an endpoint that resolves peers without announcing
        // itself is the special case, and `null` leaves the service name upstream's to choose.
        assertThat(MdnsConfig().advertise).isTrue()
        assertThat(MdnsConfig().serviceName).isNull()

        assertThat(MdnsConfig()).isEqualTo(MdnsConfig(advertise = true, serviceName = null))
        assertThat(MdnsConfig(false, "swarm")).isEqualTo(MdnsConfig(false, "swarm"))
        assertThat(MdnsConfig(false, "swarm").hashCode())
            .isEqualTo(MdnsConfig(false, "swarm").hashCode())

        // Both fields participate, and an unset service name is not the same request as one that
        // happens to spell out upstream's current default.
        assertThat(MdnsConfig()).isNotEqualTo(MdnsConfig(advertise = false))
        assertThat(MdnsConfig()).isNotEqualTo(MdnsConfig(serviceName = "irohv1"))
        assertThat(MdnsConfig(serviceName = "a")).isNotEqualTo(MdnsConfig(serviceName = "b"))

        assertThat(MdnsConfig().toString()).isEqualTo("MdnsConfig(advertise=true, serviceName=null)")
        assertThat(MdnsConfig(false, "swarm").toString())
            .isEqualTo("MdnsConfig(advertise=false, serviceName=swarm)")
    }

    fun `discovery services are values`() {
        // Defaults: an endpoint says nothing about discovery unless asked, which is what keeps the
        // preset's own services in place — and what keeps this suite offline.
        assertThat(EndpointConfig().discovery).isEmpty()

        // Upstream publishes relay addresses only, to avoid handing IP addresses to a public pkarr
        // server. iroh4k keeps that default and lets a self-hoster override it.
        assertThat(Discovery.PkarrPublisher("https://pkarr.example/pkarr").published)
            .isEqualTo(PublishedAddrs.RelayOnly)

        // `n0()` is "not stated", not a copy of n0's URL: the choice, including upstream's own
        // prod/staging switch, stays upstream's to make at bind time.
        assertThat(Discovery.PkarrPublisher.n0().relayUrl).isNull()
        assertThat(Discovery.PkarrResolver.n0().relayUrl).isNull()
        assertThat(Discovery.Dns.n0().originDomain).isNull()

        assertThat(Discovery.PkarrResolver("https://a.example/pkarr"))
            .isEqualTo(Discovery.PkarrResolver("https://a.example/pkarr"))
        assertThat(Discovery.PkarrResolver("https://a.example/pkarr").hashCode())
            .isEqualTo(Discovery.PkarrResolver("https://a.example/pkarr").hashCode())
        assertThat(Discovery.PkarrResolver("https://a.example/pkarr"))
            .isNotEqualTo(Discovery.PkarrResolver("https://b.example/pkarr"))

        // A stated URL is not the same request as an unstated one, even if it happens to name the
        // same server today.
        assertThat(Discovery.Dns("dns.iroh.link")).isNotEqualTo(Discovery.Dns.n0())

        // Both fields participate in equality, so an override is never silently equal to a default.
        assertThat(Discovery.PkarrPublisher("https://a.example/pkarr"))
            .isNotEqualTo(Discovery.PkarrPublisher("https://a.example/pkarr", PublishedAddrs.IpOnly))

        assertThat(Discovery.Dns("dns.example").toString())
            .isEqualTo("Discovery.Dns(originDomain=dns.example)")
        assertThat(Discovery.PkarrResolver.n0().toString())
            .isEqualTo("Discovery.PkarrResolver(relayUrl=null)")
    }

    fun `an endpoint binds with discovery services configured`() = runTest {
        // A URL that parses but reaches nothing: port 1 on loopback is refused locally, so this
        // stays as offline as the rest of the suite while still exercising the whole encode,
        // decode and build path.
        Endpoint.bind(
            EndpointConfig(
                preset = EndpointPreset.Minimal,
                relayMode = RelayMode.Disabled,
                bindAddrs = listOf(loopback),
                discovery = listOf(
                    // PkarrPublisher is the only variant whose payload carries a third field on
                    // the wire — a `u8` for `published` after the optional relay URL — so it is
                    // the one variant whose layout a test can actually pin. `Unfiltered` rather
                    // than the default `RelayOnly`, so the encoder is caught carrying the real
                    // ordinal instead of one that happens to match whatever the default already is.
                    Discovery.PkarrPublisher("https://127.0.0.1:1/pkarr", PublishedAddrs.Unfiltered),
                    Discovery.PkarrResolver("https://127.0.0.1:1/pkarr"),
                    Discovery.Dns("dns.invalid"),
                ),
            )
        ).use { endpoint ->
            assertThat(endpoint.isClosed).isFalse()
        }
    }

    fun `a malformed discovery URL is refused`() = runTest {
        val error = assertFailsWith<IrohError> {
            Endpoint.bind(
                EndpointConfig(
                    preset = EndpointPreset.Minimal,
                    relayMode = RelayMode.Disabled,
                    bindAddrs = listOf(loopback),
                    discovery = listOf(Discovery.PkarrResolver("not a url")),
                )
            )
        }
        // Its own code: a pkarr relay is a plain URL upstream, not an iroh `RelayUrl`, so reporting
        // it as `Relay` would name the wrong thing.
        assertThat(error.code).isEqualTo(IrohError.Code.Discovery)
        assertThat(error.message!!).contains("not a url")
    }

    fun `the address book survives clearing the preset's lookup`() = Loopback.bounded {
        Endpoint.bind(Loopback.config(alpns = listOf(Loopback.alpn))).use { server ->
            // A non-empty list, so `configure` takes the clearing path. The URL parses and reaches
            // nothing: port 1 on loopback is refused locally.
            val client = EndpointConfig(
                preset = EndpointPreset.Minimal,
                relayMode = RelayMode.Disabled,
                bindAddrs = listOf(SocketAddr.parse("127.0.0.1:0")),
                discovery = listOf(Discovery.PkarrResolver("https://127.0.0.1:1/pkarr")),
            )

            Endpoint.bind(client).use { endpoint ->
                endpoint.addEndpointAddr(server.addr())

                // Resolving an id with no transports in it is only possible out of the address
                // book, so this dial passing is the proof that clearing the preset's services left
                // iroh4k's own `MemoryLookup` in place.
                val accepted = async { server.acceptOne() }
                endpoint.connect(EndpointAddr.of(server.id), Loopback.alpn).use { connection ->
                    accepted.await().use { served ->
                        assertThat(connection.remoteId()).isEqualTo(server.id)
                        assertThat(served.remoteId()).isEqualTo(endpoint.id)
                    }
                }
            }
        }
    }

    fun `PublishedAddrs ordinals match the Rust wire contract`() {
        // This ordinal is worth a test because it is a wire contract shared with `endpoint.rs`,
        // not an internal implementation detail: `Discovery.kt` writes `published.ordinal` as the
        // `u8` after a `PkarrPublisher`'s relay URL, and `endpoint.rs` reads that byte back through
        // its own `PUBLISHED_RELAY_ONLY`/`IP_ONLY`/`UNFILTERED` constants. A reorder here compiles
        // cleanly on both sides — nothing type-checks against the numbering — and the failure it
        // lets through is silent and privacy-shaped: an endpoint told to publish relay addresses
        // only would instead publish its IP addresses to whatever pkarr relay it talks to.
        assertThat(PublishedAddrs.entries.map { it.ordinal to it.name }).containsExactly(
            0 to "RelayOnly",
            1 to "IpOnly",
            2 to "Unfiltered",
        )
    }

    fun `transport config is a value and says nothing by default`() {
        // Every field absent means "I did not say", which leaves iroh's own defaults — including
        // the six it applies for hole punching before any caller sees the builder. An endpoint that
        // never mentions transport configuration is therefore unchanged by this feature existing.
        assertThat(EndpointConfig().transportConfig).isNull()
        assertThat(TransportConfig().maxIdleTimeout).isNull()
        assertThat(TransportConfig().congestionController).isNull()

        assertThat(TransportConfig(maxIdleTimeout = 30.seconds))
            .isEqualTo(TransportConfig(maxIdleTimeout = 30.seconds))
        assertThat(TransportConfig(maxIdleTimeout = 30.seconds).hashCode())
            .isEqualTo(TransportConfig(maxIdleTimeout = 30.seconds).hashCode())
        assertThat(TransportConfig(maxIdleTimeout = 30.seconds))
            .isNotEqualTo(TransportConfig(maxIdleTimeout = 31.seconds))
        // A field left unset is not the same request as one set to a value.
        assertThat(TransportConfig()).isNotEqualTo(TransportConfig(sendFairness = true))

        assertThat(MtuDiscovery(upperBound = 1400)).isEqualTo(MtuDiscovery(upperBound = 1400))
        assertThat(AckFrequency(reorderingThreshold = 3))
            .isEqualTo(AckFrequency(reorderingThreshold = 3))

        assertThat(TransportConfig(sendFairness = true).toString())
            .contains("sendFairness=true")
    }

    fun `CongestionController ordinals match the Rust wire contract`() {
        // The ordinals are a wire protocol shared with `transport.rs`. A reorder compiles cleanly on
        // both sides and would silently select the other algorithm, which is the kind of change
        // nobody notices until a throughput graph looks wrong months later.
        assertThat(CongestionController.entries.map { it.name })
            .containsExactly("Cubic", "NewReno")
    }
}
