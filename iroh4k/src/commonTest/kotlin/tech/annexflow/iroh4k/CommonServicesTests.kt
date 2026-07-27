package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Test bodies shared by every target, so the FFI (Kotlin/Native) and JNI (JVM/Android) facades are
 * held to identical behaviour for the services domain.
 *
 * Platform test classes construct this and delegate one `@Test` per method — see
 * `nativeTest`/`jvmTest`.
 *
 * ## What is and is not verified here, and why
 *
 * This is the first domain in iroh4k that is **not hermetically testable end to end**. A
 * [ServicesClient] talks to n0's hosted service at services.iroh.computer and needs a project
 * credential, so `ping`, `setName`, `pushMetrics`, `grantCapability` and an uploading
 * `netDiagnostics` have no offline success path at all. Nothing here pretends otherwise: there is no
 * test that would pass only on a machine with `IROH_SERVICES_API_SECRET` set, no test that reaches
 * the network, and no assertion that implies coverage of a successful service round trip.
 *
 * What *is* verifiable offline turns out to be most of the binding, because
 * `ClientBuilder::build()` performs no I/O:
 *
 * - **Configuration and value types.** Credential encoding, name validation (which is on *bytes*),
 *   metrics-interval range, the capability vocabulary against Rust's own list, and every derived
 *   accessor on [NetReport].
 * - **Error mapping.** Every rejected configuration reports the code that fits it — mostly
 *   [IrohError.Code.Services], but [IrohError.Code.Addr] for a `remote` this build cannot represent.
 * - **Handle lifetime.** Creation, release, use-after-close on every member, and that neither the
 *   handle registry nor the operation registry grows across cycles. A leaked services client is
 *   worse than a leaked endpoint: it keeps a tokio task pushing metrics for the life of the process.
 * - **Cancellation.** [ServicesClient.netDiagnostics] takes over twenty seconds even when it
 *   succeeds, so it is the ideal long operation to abort — the counterpart of `Endpoint.online` in
 *   the endpoint suite.
 * - **The offline failure mode itself.** Every service operation fails *promptly*, in microseconds,
 *   because the fake remote has no reachable address and the endpoint has no relay or discovery to
 *   find one with. An expected, bounded failure is a real assertion, and it is the one that would
 *   catch a marshalling bug on any of these operations.
 *
 * ## Hermetic by construction
 *
 * The endpoint every client here is built on uses [EndpointPreset.Minimal], [RelayMode.Disabled] and
 * an explicit loopback bind address — the same combination `CommonEndpointTests` documents. `Minimal`
 * installs a crypto provider and nothing else, so there is no DNS or pkarr lookup; `Disabled` means
 * no relay; and the loopback address replaces iroh's `0.0.0.0`/`[::]` defaults rather than adding to
 * them.
 *
 * The credential is [fakeApiSecret], a well-formed `services…` ticket whose remote endpoint id is
 * derived from a fixed byte pattern and carries **no transport addresses at all**. Combined with the
 * endpoint above, that means iroh has nowhere to send a packet and says so immediately rather than
 * timing out — which is what makes the failure-path assertions fast as well as offline.
 *
 * Every test that touches Rust is wrapped in [bounded], which runs it on a real dispatcher under a
 * real-clock [withTimeout]. `runTest`'s virtual clock does not bound a blocking native call, so a
 * plain `withTimeout` inside it would be decoration; and a test that can hang on a DNS lookup is
 * worse than no test.
 */
class CommonServicesTests {

    // ── The hermetic fixture ─────────────────────────────────────────────────────────────────

    /**
     * A well-formed API secret whose service endpoint does not exist.
     *
     * Generated from a fixed secret key and a fixed remote endpoint id, so it is deterministic and
     * grants nothing anywhere. The remote it names has no relay URL and no IP address, which is
     * exactly why every operation against it fails at once instead of waiting for a timeout.
     */
    private val fakeApiSecret
        get() = ServicesCredential.ApiSecret(
            "servicesaaqaobyha4dqobyha4dqobyha4dqobyha4dqobyha4dqobyha4dqob75c4sdqwvay5nwj6" +
                "3yzvqc7iozsh66x53lcpcy5vyc5ledl2pwdaaa"
        )

    /** The loopback bind address every endpoint here uses; port `0` asks the OS for a free one. */
    private val loopback: SocketAddr get() = SocketAddr.parse("127.0.0.1:0")

    /** An endpoint configuration that cannot reach the network — see the class documentation. */
    private fun endpointConfig() = EndpointConfig(
        preset = EndpointPreset.Minimal,
        relayMode = RelayMode.Disabled,
        bindAddrs = listOf(loopback),
    )

    /**
     * A client configuration that is valid but points at nothing.
     *
     * Metrics pushes are disabled by default here so a test's client cannot start a background dial
     * on a timer while the test is doing something else.
     */
    private fun clientConfig(
        credential: ServicesCredential = fakeApiSecret,
        name: String? = null,
        remote: EndpointAddr? = null,
        metricsPush: MetricsPush = MetricsPush.Disabled,
    ) = ServicesConfig(credential, name, remote, metricsPush)

    // ── Capability vocabulary ────────────────────────────────────────────────────────────────

    fun `the capability vocabulary matches the linked rust build`() = runTest(timeout = TEST_TIMEOUT) {
        // The one place a Kotlin enum has to agree with a string vocabulary defined upstream. A
        // renamed or added capability in iroh-services would otherwise surface as a runtime
        // "unknown capability" from `grantCapability` — an operation no offline test can reach.
        val fromRust = bounded { ServicesClient.capabilityNames }
        val fromKotlin = ServicesCapability.entries.map { it.canonical }

        assertThat(fromRust.toSet()).isEqualTo(fromKotlin.toSet())
        // No duplicates on either side: a duplicate would make the set comparison above pass while
        // one entry silently shadowed another.
        assertThat(fromRust).hasSize(fromRust.toSet().size)
        assertThat(fromKotlin).hasSize(fromKotlin.toSet().size)

        // And the canonical strings really are what upstream renders, not a plausible guess.
        assertThat(fromRust).contains("all")
        assertThat(fromRust).contains("client")
        assertThat(fromRust).contains("relay:use")
        assertThat(fromRust).contains("metrics:put-any")
        assertThat(fromRust).contains("net-diagnostics:put-any")
        assertThat(fromRust).contains("net-diagnostics:get-any")

        // `toString` is the canonical string too, so a capability logged by name is greppable
        // against iroh-services' own documentation.
        assertThat(ServicesCapability.MetricsPutAny.toString()).isEqualTo("metrics:put-any")
    }

    // ── Creation ─────────────────────────────────────────────────────────────────────────────

    fun `a client is created without touching the network`() = runTest(timeout = TEST_TIMEOUT) {
        // Settled baseline, then a ceiling at the end: the handle counter is process-global, and
        // `LiveCounter` documents the three races an equality against it walks into.
        val clients = LiveCounters.servicesHandles
        LiveCounters.settle()
        val baseline = clients.value

        bounded {
            Endpoint.bind(endpointConfig()).use { endpoint ->
                // `build()` validates the credential, wraps the endpoint in a lazily-dialled
                // connection and starts the client actor. No packet leaves, which is why this
                // succeeds with a credential that authenticates against nothing.
                ServicesClient.create(endpoint, clientConfig()).use { client ->
                    assertThat(client.isReleased).isFalse()
                    // Exact, not a ceiling: the claim is that `create` registered exactly one
                    // handle, which a ceiling could not distinguish from registering none.
                    assertThat(clients.value, "$clients with one client open").isEqualTo(baseline + 1)
                    assertThat(client.toString()).isEqualTo("ServicesClient()")
                }
            }
        }

        clients.awaitAtMost(baseline)
    }

    fun `creating a client is fast because it performs no io`() = runTest(timeout = TEST_TIMEOUT) {
        bounded {
            Endpoint.bind(endpointConfig()).use { endpoint ->
                // Not a benchmark — a *property*. If `create` ever started dialling, this would go
                // from microseconds to a connection timeout, and the documentation promising that a
                // successful `create` says nothing about reachability would have become a lie.
                val elapsed = measureTime {
                    ServicesClient.create(endpoint, clientConfig()).close()
                }
                assertThat(elapsed < 2.seconds).isTrue()
            }
        }
    }

    fun `a client can be created on top of an api secret with an explicit remote`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The builder option iroh-ffi omits. Both transport kinds are exercised, so the
            // `EndpointAddr` encoder in this domain is covered rather than merely present.
            val remote = EndpointAddr.of(SecretKey.generate().public())
                .withRelayUrl(RelayUrl.parse("https://services.example.com"))
                .withIpAddr(SocketAddr.parse("198.51.100.4:4433"))
                .withAddr(TransportAddr.Custom(CustomAddr(0x1234_5678L, byteArrayOf(1, 2, 3))))

            bounded {
                Endpoint.bind(endpointConfig()).use { endpoint ->
                    ServicesClient.create(endpoint, clientConfig(remote = remote)).use { client ->
                        assertThat(client.isReleased).isFalse()
                    }
                }
            }
        }

    fun `a remote address this build cannot represent is refused as an Addr error`() =
        runTest(timeout = TEST_TIMEOUT) {
            // `TransportAddr.Unknown` only ever comes *from* a newer Rust core. Sending one back is
            // refused rather than silently dropped — the rule `Addr.kt` documents — and the code is
            // `Addr`, not `Services`, which is the point: the configuration crosses in one payload
            // and the error still names the part that was wrong.
            val remote = EndpointAddr.of(SecretKey.generate().public())
                .withAddr(TransportAddr.Unknown("some-future-transport://host"))

            bounded {
                Endpoint.bind(endpointConfig()).use { endpoint ->
                    val error = assertFailsWith<IrohError> {
                        ServicesClient.create(endpoint, clientConfig(remote = remote))
                    }
                    assertThat(error.code).isEqualTo(IrohError.Code.Addr)
                }
            }
        }

    fun `a malformed credential is refused and leaks nothing`() = runTest(timeout = TEST_TIMEOUT) {
        val clients = LiveCounters.servicesHandles
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val handleBaseline = clients.value
        val opBaseline = ops.value

        val rejected: List<Pair<String, ServicesCredential>> = listOf(
            "not a ticket at all" to ServicesCredential.ApiSecret("not-a-valid-ticket"),
            "an empty secret" to ServicesCredential.ApiSecret(""),
            // Right prefix, corrupt body: proves the decode really runs rather than the prefix being
            // pattern-matched.
            "a truncated ticket" to ServicesCredential.ApiSecret("servicesaaqaobyha4dq"),
            "not a PEM" to ServicesCredential.SshKey("-----BEGIN NOTHING-----"),
            "an empty PEM" to ServicesCredential.SshKey(""),
            "a missing key file" to
                ServicesCredential.SshKeyFile("/nonexistent/iroh4k/no-such-key"),
        )

        bounded {
            Endpoint.bind(endpointConfig()).use { endpoint ->
                for ((what, credential) in rejected) {
                    val error = assertFailsWith<IrohError>("expected $what to be refused") {
                        ServicesClient.create(endpoint, clientConfig(credential = credential))
                    }
                    assertThat(error.code, "code for $what").isEqualTo(IrohError.Code.Services)
                }
            }
        }

        // Each failed create released the handle it had already allocated, so nothing was stranded.
        // Six rejected credentials, so a per-failure leak overshoots the ceiling by six.
        clients.awaitAtMost(handleBaseline)
        ops.awaitAtMost(opBaseline)
    }

    fun `reading the credential from the environment is bounded either way`() =
        runTest(timeout = TEST_TIMEOUT) {
            val clients = LiveCounters.servicesHandles
            LiveCounters.settle()
            val baseline = clients.value

            // Deliberately not asserted to fail. `IROH_SERVICES_API_SECRET` is read in Rust, and a
            // developer running this suite may legitimately have it set — a test that demanded
            // failure would be a test that only passes on some machines. What is asserted is what
            // holds either way, and it is what would actually break: the variable is read, the call
            // returns promptly rather than dialling, and no handle is stranded on the failure path.
            bounded {
                Endpoint.bind(endpointConfig()).use { endpoint ->
                    val client = try {
                        ServicesClient.create(
                            endpoint,
                            clientConfig(credential = ServicesCredential.ApiSecretFromEnv),
                        )
                    } catch (error: IrohError) {
                        assertThat(error.code).isEqualTo(IrohError.Code.Services)
                        // The message names the environment variable, which is the only actionable
                        // thing a caller can be told here.
                        assertThat(error.message!!).contains("IROH_SERVICES_API_SECRET")
                        null
                    }
                    client?.close()
                }
            }

            // Whichever way it went, nothing was stranded on the way out.
            clients.awaitAtMost(baseline)
        }

    fun `creating a client on a released endpoint reports Closed`() = runTest(timeout = TEST_TIMEOUT) {
        val clients = LiveCounters.servicesHandles
        LiveCounters.settle()
        val baseline = clients.value

        bounded {
            val endpoint = Endpoint.bind(endpointConfig())
            endpoint.close()

            // The endpoint's own guard refuses before this domain is ever reached, which is exactly
            // why `create` borrows the handle through `Endpoint.withHandle` rather than reading it.
            val error = assertFailsWith<IrohError> {
                ServicesClient.create(endpoint, clientConfig())
            }
            assertThat(error.code).isEqualTo(IrohError.Code.Closed)
        }

        // Refused before this domain allocated anything, and the counter has to agree.
        clients.awaitAtMost(baseline)
    }

    // ── Names ────────────────────────────────────────────────────────────────────────────────

    fun `a name outside two to 128 bytes is refused`() = runTest(timeout = TEST_TIMEOUT) {
        bounded {
            Endpoint.bind(endpointConfig()).use { endpoint ->
                // The limit upstream enforces is on **bytes**, not characters, and that distinction
                // is the whole reason this test exists: "é" is two bytes and passes where the
                // single-character "a" does not, and 33 waving hands are 132 bytes.
                for (name in listOf("", "a", "9")) {
                    val error = assertFailsWith<IrohError>("expected ${name.length} bytes refused") {
                        ServicesClient.create(endpoint, clientConfig(name = name))
                    }
                    assertThat(error.code).isEqualTo(IrohError.Code.Services)
                }

                val tooLong = listOf("x".repeat(129), "👋".repeat(33))
                for (name in tooLong) {
                    val error = assertFailsWith<IrohError>("expected a long name refused") {
                        ServicesClient.create(endpoint, clientConfig(name = name))
                    }
                    assertThat(error.code).isEqualTo(IrohError.Code.Services)
                }

                // The boundaries themselves are accepted: two bytes as one character, 128 bytes as
                // ASCII, and 128 bytes as 32 four-byte emoji.
                val accepted = listOf("é", "ab", "x".repeat(128), "👋".repeat(32))
                for (name in accepted) {
                    ServicesClient.create(endpoint, clientConfig(name = name)).close()
                }
            }
        }
    }

    fun `name reports what the client was configured with`() = runTest(timeout = TEST_TIMEOUT) {
        bounded {
            Endpoint.bind(endpointConfig()).use { endpoint ->
                // `name()` asks the client's own actor, not the service, so it is the single
                // services operation with an offline answer. A client built without a name has none.
                ServicesClient.create(endpoint, clientConfig()).use { client ->
                    assertThat(client.name()).isNull()
                }

                // And one built with a name reports it, even though the attempt to register that
                // name service-side failed — upstream seeds the actor's state from the builder and
                // only logs the send failure, which is precisely why `setName` exists.
                ServicesClient.create(endpoint, clientConfig(name = "iroh4k-under-test"))
                    .use { client ->
                        assertThat(client.name()).isEqualTo("iroh4k-under-test")

                        // A `setName` that cannot reach the service leaves the previous name alone
                        // rather than optimistically adopting the new one.
                        assertFailsWith<IrohError> { client.setName("a-different-name") }
                        assertThat(client.name()).isEqualTo("iroh4k-under-test")
                    }
            }
        }
    }

    fun `setName rejects an out of range name before it reaches the service`() =
        runTest(timeout = TEST_TIMEOUT) {
            bounded {
                Endpoint.bind(endpointConfig()).use { endpoint ->
                    ServicesClient.create(endpoint, clientConfig(name = "starting-name"))
                        .use { client ->
                            // Every one of these fails, but so does a well-formed name against an
                            // unreachable service — so what this pins is the *code*, which is the
                            // same either way, and that the name survived.
                            for (name in listOf("", "a", "x".repeat(129))) {
                                val error = assertFailsWith<IrohError> { client.setName(name) }
                                assertThat(error.code).isEqualTo(IrohError.Code.Services)
                            }
                            assertThat(client.name()).isEqualTo("starting-name")
                        }
                }
            }
        }

    // ── Metrics push configuration ───────────────────────────────────────────────────────────

    fun `a metrics push interval below one millisecond is refused`() = runTest(timeout = TEST_TIMEOUT) {
        bounded {
            Endpoint.bind(endpointConfig()).use { endpoint ->
                // Not pedantry: upstream hands the interval straight to a tokio interval timer,
                // which **panics** on a zero period, and this crate is built with `panic = "abort"`
                // — so a sub-millisecond duration that reached Rust unchecked would take the host
                // process down rather than raise. The check has to be before the timer.
                for (interval in listOf(Duration.ZERO, 1.nanoseconds, 999.microseconds)) {
                    val error = assertFailsWith<IrohError>("expected $interval to be refused") {
                        ServicesClient.create(
                            endpoint,
                            clientConfig(metricsPush = MetricsPush.Every(interval)),
                        )
                    }
                    assertThat(error.code).isEqualTo(IrohError.Code.Services)
                }

                // A millisecond is the floor, and it is accepted. Closed immediately so the timer
                // never gets a chance to fire.
                ServicesClient.create(
                    endpoint,
                    clientConfig(metricsPush = MetricsPush.Every(1.milliseconds)),
                ).close()
                ServicesClient.create(
                    endpoint,
                    clientConfig(metricsPush = MetricsPush.Every(30.seconds)),
                ).close()
                // And so are both no-interval cases, which are distinct on the wire.
                ServicesClient.create(endpoint, clientConfig(metricsPush = MetricsPush.Default))
                    .close()
                ServicesClient.create(endpoint, clientConfig(metricsPush = MetricsPush.Disabled))
                    .close()
            }
        }
    }

    // ── The offline failure path ─────────────────────────────────────────────────────────────

    fun `every service operation fails promptly when the service is unreachable`() =
        runTest(timeout = TEST_TIMEOUT) {
            val ops = LiveCounters.operations
            LiveCounters.settle()
            val opBaseline = ops.value

            bounded {
                Endpoint.bind(endpointConfig()).use { endpoint ->
                    ServicesClient.create(endpoint, clientConfig()).use { client ->
                        val operations: List<Pair<String, suspend () -> Any?>> = listOf(
                            "ping" to { client.ping() },
                            "setName" to { client.setName("a-valid-name") },
                            "pushMetrics" to { client.pushMetrics() },
                            "grantCapability" to {
                                client.grantCapability(
                                    SecretKey.generate().public(),
                                    setOf(ServicesCapability.MetricsPutAny),
                                )
                            },
                        )

                        // This is the honest extent of the coverage for these four: they are
                        // exercised only against the failure path. What that still proves is real —
                        // the payload marshalled, the operation ran, Rust's error reached Kotlin
                        // under the right code, and the registry drained — and it is bounded,
                        // because an endpoint with no relay and no discovery cannot even begin to
                        // dial an address that does not exist.
                        for ((name, call) in operations) {
                            val elapsed = measureTime {
                                val error =
                                    assertFailsWith<IrohError>("expected $name to fail offline") {
                                        call()
                                    }
                                assertThat(error.code, "code for $name")
                                    .isEqualTo(IrohError.Code.Services)
                            }
                            assertThat(elapsed < 5.seconds, "$name should fail promptly").isTrue()
                        }
                    }
                }
            }

            // Four operations, all failed: an error path that forgot to remove its registry entry
            // would leave the count above the settled baseline and never come back down.
            ops.awaitAtMost(opBaseline)
        }

    fun `granting no capability at all is refused`() = runTest(timeout = TEST_TIMEOUT) {
        bounded {
            Endpoint.bind(endpointConfig()).use { endpoint ->
                ServicesClient.create(endpoint, clientConfig()).use { client ->
                    // An empty set would sign a token permitting nothing, which is never what
                    // "grant" meant. Refused in Rust before any token is created — so unlike the
                    // failure-path assertions above, this one is about the request and not the
                    // network.
                    val error = assertFailsWith<IrohError> {
                        client.grantCapability(SecretKey.generate().public(), emptySet())
                    }
                    assertThat(error.code).isEqualTo(IrohError.Code.Services)
                    assertThat(error.message!!).contains("at least one capability")
                }
            }
        }
    }

    fun `a client outlives the endpoint handle it was created from`() = runTest(timeout = TEST_TIMEOUT) {
        bounded {
            val endpoint = Endpoint.bind(endpointConfig())
            val client = ServicesClient.create(endpoint, clientConfig())
            try {
                // Upstream's `Client` keeps its own `Endpoint` clone, and `iroh::Endpoint` is an
                // `Arc` inside — so releasing Kotlin's handle does not take the sockets away. The
                // client therefore still reports `Services` (its own domain failing) rather than
                // `Closed` (its endpoint being gone), which is the documented behaviour and the
                // reason closing an endpoint is not enough to stop metric pushes.
                endpoint.close()
                assertThat(endpoint.isReleased).isTrue()

                assertThat(client.name()).isNull()
                val error = assertFailsWith<IrohError> { client.ping() }
                assertThat(error.code).isEqualTo(IrohError.Code.Services)
            } finally {
                client.close()
            }
        }
    }

    // ── Release ──────────────────────────────────────────────────────────────────────────────

    fun `using a released client raises Closed rather than crashing`() = runTest(timeout = TEST_TIMEOUT) {
        bounded {
            Endpoint.bind(endpointConfig()).use { endpoint ->
                val client = ServicesClient.create(endpoint, clientConfig())
                client.close()

                assertThat(client.isReleased).isTrue()

                // Every member, not one: a guard that only covered the first method anybody tried
                // would pass a narrower test and still dereference a freed pointer on the next call.
                val operations: List<Pair<String, suspend () -> Any?>> = listOf(
                    "name" to { client.name() },
                    "setName" to { client.setName("a-valid-name") },
                    "ping" to { client.ping() },
                    "pushMetrics" to { client.pushMetrics() },
                    "grantCapability" to {
                        client.grantCapability(endpoint.id, setOf(ServicesCapability.Client))
                    },
                    "netDiagnostics" to { client.netDiagnostics() },
                )
                for ((name, call) in operations) {
                    val error = assertFailsWith<IrohError>("expected $name to report Closed") {
                        call()
                    }
                    assertThat(error.code, "code for $name").isEqualTo(IrohError.Code.Closed)
                }

                // `close()` stays idempotent, and `isReleased` keeps answering rather than raising —
                // it is the one member that can be asked about a released client.
                client.close()
                client.close()
                assertThat(client.isReleased).isTrue()
                assertThat(client.toString()).isEqualTo("ServicesClient(released)")
            }
        }
    }

    fun `a client works as an AutoCloseable resource`() = runTest(timeout = TEST_TIMEOUT) {
        val clients = LiveCounters.servicesHandles
        LiveCounters.settle()
        val baseline = clients.value

        bounded {
            Endpoint.bind(endpointConfig()).use { endpoint ->
                ServicesClient.create(endpoint, clientConfig()).use {
                    assertThat(clients.value, "$clients inside use").isEqualTo(baseline + 1)
                }
                // Leaving the block released it, which is the half of `use` this test is about.
                clients.awaitAtMost(baseline)

                // And `use` propagates a failure while still releasing.
                val boom = assertFailsWith<IllegalStateException> {
                    ServicesClient.create(endpoint, clientConfig()).use { error("deliberate") }
                }
                assertThat(boom.message!!).isEqualTo("deliberate")
            }
        }

        clients.awaitAtMost(baseline)
    }

    // ── Cancellation and leaks ───────────────────────────────────────────────────────────────

    fun `cancelling net diagnostics returns promptly and drains the op registry`() =
        runTest(timeout = TEST_TIMEOUT) {
            val ops = LiveCounters.operations
            LiveCounters.settle()
            val opBaseline = ops.value

            bounded(30.seconds) {
                Endpoint.bind(endpointConfig()).use { endpoint ->
                    ServicesClient.create(endpoint, clientConfig()).use { client ->
                        // `netDiagnostics` is this domain's `Endpoint.online`: upstream waits up to
                        // 10s for a home relay — which, with relays disabled, will never arrive —
                        // then up to 10s more for a net report, then probes the LAN. Cancellation is
                        // the only thing that ends it early.
                        //
                        // Cancelling within milliseconds also keeps this test hermetic: the only
                        // step that has begun is the local relay watch. The LAN port-mapping probe
                        // sits twenty seconds further on and is never reached.
                        val job = launch(Dispatchers.Default) { client.netDiagnostics(send = false) }
                        // Registered before it is cancelled, or the abort path is never reached.
                        ops.awaitAtLeast(opBaseline + 1)

                        val elapsed = measureTime { job.cancelAndJoin() }

                        assertThat(job.isCancelled).isTrue()
                        // Must not wait out an operation that would have taken twenty seconds.
                        assertThat(elapsed < 5.seconds).isTrue()
                        ops.awaitAtMost(opBaseline)

                        // The client survived having an operation aborted under it.
                        assertThat(client.isReleased).isFalse()
                        assertThat(client.name()).isNull()
                    }
                }
            }

            ops.awaitAtMost(opBaseline)
        }

    fun `closing a client while a call is in flight is safe`() = runTest(timeout = TEST_TIMEOUT) {
        val clients = LiveCounters.servicesHandles
        val ops = LiveCounters.operations
        // Settled, because the assertion this test exists for is an exact one and an unsettled
        // baseline can drift under it — reporting a working use-after-free guard as broken.
        LiveCounters.settle()
        val handleBaseline = clients.value
        val opBaseline = ops.value

        bounded(30.seconds) {
            Endpoint.bind(endpointConfig()).use { endpoint ->
                val client = ServicesClient.create(endpoint, clientConfig())

                val job = launch(Dispatchers.Default) { client.netDiagnostics(send = false) }
                ops.awaitAtLeast(opBaseline + 1)

                // The race the guard exists for: a release arriving while another coroutine is
                // inside the handle. The handle must survive until that call returns — a plain
                // closed flag would let the free happen here and the in-flight call dereference
                // freed memory.
                client.close()
                assertThat(client.isReleased).isTrue()
                // Exact and instantaneous on purpose: it must fail if the handle was freed *at
                // this moment*, so it can be neither a ceiling nor a wait — both would be
                // satisfied by a handle that is already gone.
                assertThat(clients.value, "$clients while a call is in flight")
                    .isEqualTo(handleBaseline + 1)

                // Meanwhile a new call is already refused.
                assertFailsWith<IrohError> { client.name() }

                job.cancelAndJoin()

                // Only once the in-flight call has finished is the handle actually freed — and with
                // it the tokio actor that would otherwise keep pushing metrics.
                clients.awaitAtMost(handleBaseline)
            }
        }

        ops.awaitAtMost(opBaseline)
    }

    fun `repeated create and close cycles leak neither handles nor operations`() =
        runTest(timeout = TEST_TIMEOUT) {
            val clients = LiveCounters.servicesHandles
            val ops = LiveCounters.operations
            LiveCounters.settle()
            val handleBaseline = clients.value
            val opBaseline = ops.value

            bounded {
                Endpoint.bind(endpointConfig()).use { endpoint ->
                    repeat(6) {
                        val client = ServicesClient.create(endpoint, clientConfig())
                        assertThat(client.name()).isNull()
                        client.close()
                    }
                }
            }

            // Both registries must come back down to where they started. Without the handle counter
            // a leak here would be entirely invisible: the suite would pass while every cycle
            // stranded a client whose actor kept pushing metrics. Six cycles, so a per-cycle leak
            // overshoots the ceiling by six.
            clients.awaitAtMost(handleBaseline)
            ops.awaitAtMost(opBaseline)
        }

    // ── Value types ──────────────────────────────────────────────────────────────────────────

    fun `a credential never renders its secret`() {
        // Pure Kotlin, no client needed. A `toString` in a log line is how secrets escape — the same
        // reason `SecretKey.toString` and `RelayConfig.toString` render nothing.
        val secret = "servicesaaqaobyha4dqobyha4dq"
        assertThat(ServicesCredential.ApiSecret(secret).toString()).isEqualTo("ApiSecret(..)")
        assertThat(ServicesCredential.ApiSecret(secret).toString().contains(secret)).isFalse()

        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nsecret\n"
        assertThat(ServicesCredential.SshKey(pem).toString()).isEqualTo("SshKey(..)")
        assertThat(ServicesCredential.SshKey(pem).toString().contains("secret")).isFalse()

        // A file *path* is not itself a credential, so it is rendered — it is what a caller needs to
        // see in a "could not read the key" report.
        assertThat(ServicesCredential.SshKeyFile("/home/me/.ssh/id_ed25519").path)
            .isEqualTo("/home/me/.ssh/id_ed25519")

        // The value survives the hiding, or the client could not be built at all.
        assertThat(ServicesCredential.ApiSecret(secret).encoded).isEqualTo(secret)
        assertThat(ServicesCredential.SshKey(pem).pem).isEqualTo(pem)
    }

    fun `metrics push distinguishes the default from an explicit interval`() {
        // Three cases, not iroh-ffi's nullable-millis-where-zero-means-off. `Default` and
        // `Every(60s)` must not be the same object, or a change to upstream's default would silently
        // change the meaning of a configuration that never asked for 60 seconds.
        assertThat(MetricsPush.Default as MetricsPush)
            .isNotEqualTo(MetricsPush.Every(60.seconds) as MetricsPush)
        assertThat(MetricsPush.Default as MetricsPush)
            .isNotEqualTo(MetricsPush.Disabled as MetricsPush)
        assertThat(MetricsPush.Every(5.seconds).interval).isEqualTo(5.seconds)
        assertThat(MetricsPush.Every(5.seconds).toString()).isEqualTo("Every(5s)")
    }

    fun `a pong is a value and renders as hex`() {
        val bytes = ByteArray(16) { (it * 17).toByte() }
        val pong = Pong(bytes)

        assertThat(pong).isEqualTo(Pong(bytes.copyOf()))
        assertThat(pong.hashCode()).isEqualTo(Pong(bytes.copyOf()).hashCode())
        assertThat(pong).isNotEqualTo(Pong(ByteArray(16)))
        // Copied out, so a caller cannot mutate the value under itself.
        pong.requestId()[0] = 99
        assertThat(pong.requestId()).isEqualTo(bytes)
        // Lowercase hex, as `Signature` renders itself. 0x00, 0x11, 0x22 … 0xF0.
        assertThat(pong.toString()).isEqualTo("00112233445566778899aabbccddeeff")
    }

    fun `a net report derives what upstream derives`() {
        // The two accessors iroh computes rather than stores, reimplemented here because the report
        // crosses as data. Pure Kotlin, so this is verifiable without a service — which is exactly
        // why iroh-ffi's decision to drop the whole net report is a loss and not a simplification.
        fun report(
            udpIpv4: Boolean = false,
            udpIpv6: Boolean = false,
            variesV4: Boolean? = null,
            variesV6: Boolean? = null,
        ) = NetReport(
            udpIpv4 = udpIpv4,
            udpIpv6 = udpIpv6,
            mappingVariesByDestIpv4 = variesV4,
            mappingVariesByDestIpv6 = variesV6,
            captivePortal = null,
            preferredRelay = null,
            globalIpv4 = null,
            globalIpv6 = null,
            relayLatencies = emptyList(),
        )

        assertThat(report().hasUdp).isFalse()
        assertThat(report(udpIpv4 = true).hasUdp).isTrue()
        assertThat(report(udpIpv6 = true).hasUdp).isTrue()

        // `null` is a third answer, not a `false`: "too few probes completed to tell" and "the
        // address does not vary" mean different things to anyone debugging hole punching.
        assertThat(report().mappingVariesByDest).isNull()
        assertThat(report(variesV4 = false).mappingVariesByDest).isEqualTo(false)
        assertThat(report(variesV4 = true).mappingVariesByDest).isEqualTo(true)
        assertThat(report(variesV4 = false, variesV6 = true).mappingVariesByDest).isEqualTo(true)
        assertThat(report(variesV4 = null, variesV6 = false).mappingVariesByDest).isEqualTo(false)
    }

    fun `diagnostics values are comparable`() {
        assertThat(PortMapProbe(upnp = true, pcp = false, natPmp = true))
            .isEqualTo(PortMapProbe(upnp = true, pcp = false, natPmp = true))
        assertThat(PortMapProbe(upnp = true, pcp = false, natPmp = true))
            .isNotEqualTo(PortMapProbe(upnp = true, pcp = true, natPmp = true))

        val url = RelayUrl.parse("https://relay.example.com")
        assertThat(RelayLatency(LatencyProbe.Https, url, 12.milliseconds))
            .isEqualTo(RelayLatency(LatencyProbe.Https, url, 12.milliseconds))
        // The probe is part of the identity: the same relay measured two ways is two measurements.
        assertThat(RelayLatency(LatencyProbe.Https, url, 12.milliseconds))
            .isNotEqualTo(RelayLatency(LatencyProbe.QuicIpv4, url, 12.milliseconds))

        // `Unknown` exists so a future iroh probe kind still reports its latency — the same choice
        // `TransportAddr.Unknown` makes in the addressing domain.
        assertThat(LatencyProbe.entries).containsExactly(
            LatencyProbe.Https,
            LatencyProbe.QuicIpv4,
            LatencyProbe.QuicIpv6,
            LatencyProbe.Unknown,
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    /**
     * Runs [block] on a real dispatcher under a real-clock timeout.
     *
     * Both halves matter. `runTest` drives a *virtual* clock, so a `withTimeout` inside it would not
     * bound a native call that blocks a thread; and every test in this file talks to Rust, where an
     * operation left unbounded could in principle wait on a socket. The whole point of this domain's
     * test suite is that it never hangs.
     */
    private suspend fun <T> bounded(timeout: Duration = 20.seconds, block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(timeout) { block() } }

    private companion object {
        /**
         * The wall-clock ceiling on any single test here.
         *
         * `runTest`'s own default is 60 seconds; this is tighter because nothing in this file should
         * come close to it — the slowest thing any test does is start a `netDiagnostics` and cancel
         * it milliseconds later.
         */
        val TEST_TIMEOUT: Duration = 45.seconds
    }
}
