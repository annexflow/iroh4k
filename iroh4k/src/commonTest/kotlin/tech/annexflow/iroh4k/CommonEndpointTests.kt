package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
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
 * - `Minimal` installs a crypto provider and nothing else — no address lookup, so nothing publishes
 *   to or queries n0's DNS servers. (`N0` and even `N0DisableRelay` both do.)
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
}
