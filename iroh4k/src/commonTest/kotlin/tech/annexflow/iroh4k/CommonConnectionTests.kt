package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Test bodies shared by every target, so the FFI (Kotlin/Native) and JNI (JVM/Android) facades are
 * held to identical behaviour for the connection domain.
 *
 * Platform test classes construct this and delegate one `@Test` per method — see
 * `nativeTest`/`jvmTest`.
 *
 * ## Hermetic, and now genuinely connected
 *
 * Every endpoint here is bound exactly as `CommonEndpointTests` binds them:
 * [EndpointPreset.Minimal], [RelayMode.Disabled] and `127.0.0.1:0`. That combination has no address
 * lookup and no relay, so nothing in this file touches anything outside the machine — but two such
 * endpoints *can* still reach each other over loopback, given each other's [Endpoint.addr]. That is
 * the whole reason this suite dials: the accept chain cannot be tested without something to accept,
 * and a second endpoint in the same process is the only offline way to provide one.
 *
 * ## Every test is bounded
 *
 * [bounded] runs each body on a real dispatcher under a real-time [withTimeout]. That is not
 * belt-and-braces: [Endpoint.acceptNext] waits forever when nobody connects, and so does a dialler
 * whose peer chose [Incoming.ignore]. A regression in either has to fail the suite rather than hang
 * it, and `runTest`'s virtual clock would skip the timeout instead of applying it.
 *
 * ## Both dialling forms
 *
 * [Endpoint.startConnect] is the two-step form and [Endpoint.connect] the one-shot; most of the bodies
 * here use whichever is shorter for what they are testing, and the two are compared directly in
 * `connect reaches a connection in a single step`. Streams have their own suite — see
 * [CommonStreamTests] — and share this file's [Loopback] harness.
 */
class CommonConnectionTests {

    /** The protocol the accepting endpoints below advertise. */
    private val alpn: ByteArray get() = Loopback.alpn

    /** A second protocol, for the mismatch and multi-ALPN cases. */
    private val otherAlpn: ByteArray get() = "iroh4k/m5/2".encodeToByteArray()

    /** A configuration that reaches nothing but loopback — see [Loopback]. */
    private fun config(alpns: List<ByteArray> = emptyList()) = Loopback.config(alpns)

    // ── The handshake, end to end ────────────────────────────────────────────────────────────

    fun `a loopback handshake reaches a connection on both sides`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            Endpoint.bind(config()).use { client ->
                val accepted = async { server.acceptOne() }
                val dialled = dial(client, server.addr())

                val inbound = accepted.await()
                val outbound = dialled.await()
                try {
                    // The assertion the whole chain exists for: each side learned the other's
                    // identity from the certificate it presented, so these are cryptographically
                    // established rather than claimed.
                    assertThat(inbound.remoteId()).isEqualTo(client.id)
                    assertThat(outbound.remoteId()).isEqualTo(server.id)
                    assertThat(inbound.remoteId()).isNotEqualTo(outbound.remoteId())

                    // Both sides agree on the protocol they negotiated.
                    assertThat(inbound.alpn()).isEqualTo(alpn)
                    assertThat(outbound.alpn()).isEqualTo(alpn)

                    // And it is one live connection, not two half-open ones.
                    assertThat(inbound.isClosed).isFalse()
                    assertThat(outbound.isClosed).isFalse()
                    assertThat(inbound.closeReason).isNull()
                    assertThat(inbound.stableId()).isNotEqualTo(0L)
                } finally {
                    inbound.close()
                    outbound.close()
                }
            }
        }
    }

    fun `the negotiated alpn is readable before the handshake completes`() = bounded {
        // The server offers two protocols, so which one was chosen is a real question rather than a
        // foregone conclusion — that is the case `Accepting.alpn` exists for.
        Endpoint.bind(config(alpns = listOf(otherAlpn, alpn))).use { server ->
            Endpoint.bind(config()).use { client ->
                val accepted = async {
                    val incoming = server.acceptNext() ?: error("the server was shut down")
                    incoming.use { pending ->
                        pending.accept().use { accepting ->
                            assertThat(accepting.alpn()).isEqualTo(alpn)
                            // Reading the ALPN did not consume the handshake: it still completes.
                            accepting.connect()
                        }
                    }
                }

                val outbound = client.startConnect(server.addr(), alpn).use { connecting ->
                    // Known before anything is negotiated, because this side chose it.
                    assertThat(connecting.remoteId()).isEqualTo(server.id)
                    assertThat(connecting.alpn()).isEqualTo(alpn)
                    connecting.connect()
                }
                val inbound = accepted.await()
                try {
                    assertThat(inbound.alpn()).isEqualTo(alpn)
                    assertThat(outbound.alpn()).isEqualTo(alpn)
                } finally {
                    inbound.close()
                    outbound.close()
                }
            }
        }
    }

    fun `a mismatched alpn fails the handshake rather than hanging`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            Endpoint.bind(config()).use { client ->
                // The server must still drive its side, or nothing would negotiate at all and the
                // test would prove only that an unaccepted dial waits.
                val accepted = async { assertFailsWith<IrohError> { server.acceptOne() } }
                val failure = assertFailsWith<IrohError> {
                    client.startConnect(server.addr(), otherAlpn).use { it.connect() }
                }

                // The dialler's handshake is what fails, so it reports `Connect`.
                assertThat(failure.code).isEqualTo(IrohError.Code.Connect)
                // The accepting side fails one step *earlier* and so reports `Accept`: iroh reads the
                // offered ALPNs out of the Initial packet, finds none it advertises, and refuses at
                // `Incoming.accept` before any handshake begins. That is the distinction the two
                // codes draw — `Accept` for deciding what to do with an attempt, `Connect` for the
                // handshake itself — and it is why both exist.
                assertThat(accepted.await().code).isEqualTo(IrohError.Code.Accept)

                // Neither endpoint is damaged by a failed negotiation.
                assertThat(server.isClosed).isFalse()
                assertThat(client.isClosed).isFalse()
            }
        }
    }

    // ── Answering an incoming connection ─────────────────────────────────────────────────────

    fun `refusing and ignoring an incoming connection leave the endpoint accepting`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            val addr = server.addr()
            Endpoint.bind(config()).use { client ->
                // Refused: the peer is told, so its dial fails promptly.
                val refused = dial(client, addr)
                (server.acceptNext() ?: error("the server was shut down")).use { it.refuse() }
                assertThat(assertFailsWith<IrohError> { refused.await() }.code)
                    .isEqualTo(IrohError.Code.Connect)

                // Ignored: no packet goes back at all, so the dialler simply keeps waiting. It is
                // cancelled rather than awaited — which is exactly why every test here is bounded.
                val ignored = dial(client, addr)
                (server.acceptNext() ?: error("the server was shut down")).use { it.ignore() }
                ignored.cancelAndJoin()

                // The point of the test: neither answer broke the accepting endpoint.
                val accepted = async { server.acceptOne() }
                val dialled = dial(client, addr)
                val inbound = accepted.await()
                val outbound = dialled.await()
                try {
                    assertThat(inbound.remoteId()).isEqualTo(client.id)
                    assertThat(outbound.remoteId()).isEqualTo(server.id)
                } finally {
                    inbound.close()
                    outbound.close()
                }
            }
        }
    }

    fun `retry makes the peer validate its address`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            val addr = server.addr()
            Endpoint.bind(config()).use { client ->
                val dialled = dial(client, addr)

                // First contact: the sender has proved nothing about where it can receive traffic,
                // which is exactly the situation `retry` exists for.
                (server.acceptNext() ?: error("the server was shut down")).use { first ->
                    assertThat(first.remoteAddrValidated()).isFalse()
                    first.retry()
                }

                // A QUIC client answers a Retry packet transparently, so the same dial arrives again
                // carrying the token — and this time the address is validated.
                (server.acceptNext() ?: error("the server was shut down")).use { second ->
                    assertThat(second.remoteAddrValidated()).isTrue()
                    // iroh's documented failure for `retry`: there is nothing left to validate.
                    assertThat(assertFailsWith<IrohError> { second.retry() }.code)
                        .isEqualTo(IrohError.Code.Accept)
                }

                // That failed retry dropped the attempt, which refuses it — so the dial ends rather
                // than hanging, and the endpoint is still alive.
                assertFailsWith<IrohError> { dialled.await() }
                assertThat(server.isClosed).isFalse()
            }
        }
    }

    fun `an incoming connection describes itself before it is consumed`() = bounded {
        withIncoming { incoming ->
            // A direct loopback dial, so this is an IP address rather than a relayed one.
            val remote = incoming.remoteAddr()
            assertThat(remote is IncomingAddr.Ip).isTrue()
            assertThat((remote as IncomingAddr.Ip).addr.ip).isEqualTo("127.0.0.1")
            assertThat(remote.addr.port).isNotEqualTo(0)

            // The local end of the same path. The OS may or may not report which interface received
            // it, so the address is optional — but the *kind* is not.
            val local = incoming.localAddr()
            assertThat(local is LocalTransportAddr.Ip).isTrue()

            // Nothing has been proved about the sender's address yet; see `retry`.
            assertThat(incoming.remoteAddrValidated()).isFalse()

            // Inspecting is not consuming: all three still answer, repeatedly.
            assertThat(incoming.remoteAddr()).isEqualTo(remote)
            assertThat(incoming.localAddr()).isEqualTo(local)
            assertThat(incoming.toString()).isEqualTo("Incoming()")
        }
    }

    fun `an incoming connection can only be consumed once`() = bounded {
        withIncoming { incoming ->
            // Consumed by `accept`. The `Accepting` it produced is released straight away, which
            // abandons the handshake — this test is about the `Incoming`, not the connection.
            incoming.accept().close()

            // Every other consuming member now reports the value is gone, rather than unwrapping a
            // `None` in Rust — which, with `panic = "abort"`, would take the whole process down.
            val consuming: List<Pair<String, () -> Unit>> = listOf(
                "accept" to { incoming.accept().close() },
                "refuse" to { incoming.refuse() },
                "retry" to { incoming.retry() },
                "ignore" to { incoming.ignore() },
            )
            for ((name, call) in consuming) {
                assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                    .isEqualTo(IrohError.Code.Closed)
            }

            // And so do the inspectors, which read the value that is no longer there.
            val inspecting: List<Pair<String, () -> Any?>> = listOf(
                "localAddr" to { incoming.localAddr() },
                "remoteAddr" to { incoming.remoteAddr() },
                "remoteAddrValidated" to { incoming.remoteAddrValidated() },
            )
            for ((name, call) in inspecting) {
                assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                    .isEqualTo(IrohError.Code.Closed)
            }

            // The handle itself is still perfectly valid: consuming empties it, it does not free it.
            assertThat(incoming.isReleased).isFalse()
        }
    }

    // ── Accepting on a closed or released endpoint ────────────────────────────────────────────

    fun `acceptNext answers null once the endpoint is shut down`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { endpoint ->
            endpoint.shutdown()
            assertThat(endpoint.isClosed).isTrue()

            // iroh's own answer for a closed endpoint, and not an error it reports — so an accept
            // loop ends on `null` rather than on an exception. Repeatable, as iroh's is.
            assertThat(endpoint.acceptNext()).isNull()
            assertThat(endpoint.acceptNext()).isNull()
        }
    }

    fun `accepting or dialling on a released endpoint raises Closed`() = bounded {
        val endpoint = Endpoint.bind(config(alpns = listOf(alpn)))
        val stranger = EndpointAddr.of(SecretKey.generate().public())
        endpoint.close()

        // The extensions go through the endpoint's own guard, so a released endpoint refuses them
        // exactly as it refuses its members.
        assertThat(assertFailsWith<IrohError> { endpoint.acceptNext() }.code)
            .isEqualTo(IrohError.Code.Closed)
        assertThat(assertFailsWith<IrohError> { endpoint.startConnect(stranger, alpn) }.code)
            .isEqualTo(IrohError.Code.Closed)
    }

    fun `dialling something impossible is refused rather than left hanging`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { endpoint ->
            // iroh refuses to connect an endpoint to itself.
            assertThat(assertFailsWith<IrohError> { endpoint.startConnect(endpoint.addr(), alpn) }.code)
                .isEqualTo(IrohError.Code.Connect)

            // And an empty ALPN, since there would be nothing to negotiate.
            val stranger = EndpointAddr.of(SecretKey.generate().public())
            assertThat(assertFailsWith<IrohError> { endpoint.startConnect(stranger, ByteArray(0)) }.code)
                .isEqualTo(IrohError.Code.Connect)
        }
    }

    // ── Cancellation ─────────────────────────────────────────────────────────────────────────

    fun `cancelling acceptNext returns promptly and drains the op registry`() = runTest {
        // The op registry is process-global, so the baseline is taken once the counters have gone
        // quiet and every claim about it is a ceiling — see `LiveCounter` for the three races that
        // `awaitUntil { count == baseline }` plus a re-read walks into.
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val baseline = ops.value

        Endpoint.bind(config(alpns = listOf(alpn))).use { endpoint ->
            // Nobody is dialling, so this waits forever. Nothing but cancellation can end it, which
            // is the property every long iroh operation has and why the ops registry exists.
            val job = launch(Dispatchers.Default) { endpoint.acceptNext() }
            // The accept has to be *in* the registry before it is cancelled, or the cancel would
            // have nothing to abort and the drain below would be vacuous.
            ops.awaitAtLeast(baseline + 1)

            val elapsed = measureTime { job.cancelAndJoin() }

            assertThat(job.isCancelled).isTrue()
            // Must not wait out an operation that never finishes.
            assertThat(elapsed < 5.seconds).isTrue()
            // An abort that left its entry behind would hold the count above the baseline forever,
            // and this fails by timeout naming the counter and the reading.
            ops.awaitAtMost(baseline)

            // The endpoint survived having an accept aborted under it, and still accepts.
            assertThat(endpoint.isClosed).isFalse()
            val again = launch(Dispatchers.Default) { endpoint.acceptNext() }
            ops.awaitAtLeast(baseline + 1)
            again.cancelAndJoin()
        }

        ops.awaitAtMost(baseline)
    }

    // ── Connection lifecycle ─────────────────────────────────────────────────────────────────

    fun `a connection can be closed with an error code and a reason`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            Endpoint.bind(config()).use { client ->
                val accepted = async { server.acceptOne() }
                val outbound = dial(client, server.addr()).await()
                val inbound = accepted.await()
                try {
                    assertThat(inbound.isClosed).isFalse()

                    inbound.close(42L, "deliberate".encodeToByteArray())

                    // Locally closed, immediately and observably.
                    assertThat(inbound.isClosed).isTrue()
                    assertThat(inbound.closeReason).isNotNull()

                    // The peer finds out, and learns why — the code and reason cross the wire
                    // verbatim, which is what makes them worth sending.
                    awaitUntil { outbound.isClosed }
                    val reason = outbound.closeReason
                    assertThat(reason).isNotNull()
                    assertThat(reason!!.contains("deliberate")).isTrue()

                    // A closed connection is still identifiable, which is what a log line needs.
                    assertThat(inbound.remoteId()).isEqualTo(client.id)
                    assertThat(inbound.alpn()).isEqualTo(alpn)

                    // Closing again is harmless; iroh ignores it.
                    inbound.close(0L)
                } finally {
                    inbound.close()
                    outbound.close()
                }
            }
        }
    }

    fun `an impossible close error code is rejected`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            Endpoint.bind(config()).use { client ->
                val accepted = async { server.acceptOne() }
                val outbound = dial(client, server.addr()).await()
                val inbound = accepted.await()
                try {
                    // A QUIC application error code is a 62-bit varint; Kotlin's `Long` is wider and
                    // signed, so the out-of-range values are caller input to report rather than to
                    // truncate into a different code than the caller asked for.
                    for (code in listOf(-1L, Long.MIN_VALUE, 1L shl 62, Long.MAX_VALUE)) {
                        val failure = assertFailsWith<IrohError>("expected $code to be rejected") {
                            inbound.close(code, ByteArray(0))
                        }
                        assertThat(failure.code).isEqualTo(IrohError.Code.InvalidArgument)
                    }
                    // Nothing was closed by any of that.
                    assertThat(inbound.isClosed).isFalse()

                    // The largest legal code is accepted.
                    inbound.close((1L shl 62) - 1L)
                    assertThat(inbound.isClosed).isTrue()
                } finally {
                    inbound.close()
                    outbound.close()
                }
            }
        }
    }

    // ── The one-shot connect ─────────────────────────────────────────────────────────────────

    fun `connect reaches a connection in a single step`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            Endpoint.bind(config()).use { client ->
                val accepted = async { server.acceptOne() }
                val outbound = client.connect(server.addr(), alpn)
                val inbound = accepted.await()
                try {
                    // The same connection `startConnect` + `Connecting.connect` reaches, in one call.
                    assertThat(outbound.remoteId()).isEqualTo(server.id)
                    assertThat(inbound.remoteId()).isEqualTo(client.id)
                    assertThat(outbound.alpn()).isEqualTo(alpn)
                    assertThat(outbound.isClosed).isFalse()
                } finally {
                    inbound.close()
                    outbound.close()
                }
            }
        }
    }

    fun `connect refuses the impossible rather than leaving it hanging`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { endpoint ->
            // iroh refuses to connect an endpoint to itself, and refuses an empty ALPN.
            assertThat(assertFailsWith<IrohError> { endpoint.connect(endpoint.addr(), alpn) }.code)
                .isEqualTo(IrohError.Code.Connect)
            val stranger = EndpointAddr.of(SecretKey.generate().public())
            assertThat(assertFailsWith<IrohError> { endpoint.connect(stranger, ByteArray(0)) }.code)
                .isEqualTo(IrohError.Code.Connect)

            // And a released endpoint refuses it exactly as it refuses `startConnect`.
            endpoint.close()
            assertThat(assertFailsWith<IrohError> { endpoint.connect(stranger, alpn) }.code)
                .isEqualTo(IrohError.Code.Closed)
        }
    }

    // ── Identity, measurement and paths ──────────────────────────────────────────────────────

    fun `side tells the two ends of one connection apart`() = bounded {
        Loopback.connected { client, server ->
            assertThat(client.side()).isEqualTo(ConnectionSide.Client)
            assertThat(server.side()).isEqualTo(ConnectionSide.Server)
            // The same connection, seen from both ends — so the stable ids describe different objects.
            assertThat(client.alpn()).isEqualTo(server.alpn())
        }
    }

    fun `stats count a transfer and rtt is plausible`() = bounded {
        Loopback.connected { client, server ->
            // Nothing has been sent yet beyond the handshake, but that is already traffic.
            val before = client.stats()
            assertThat(before.udpTx.datagrams > 0L).isTrue()

            // Move a megabyte, which is far more than the handshake, so the growth is unmistakable.
            val payload = ByteArray(1024 * 1024) { (it and 0x7F).toByte() }
            val drained = async { server.acceptUni().use { it.readToEnd(payload.size) } }
            client.openUni().use { send ->
                send.writeAll(payload)
                send.finish()
                assertThat(drained.await().size).isEqualTo(payload.size)
            }

            val after = client.stats()
            assertThat(after.udpTx.bytes > before.udpTx.bytes + payload.size).isTrue()
            assertThat(after.udpTx.datagrams > before.udpTx.datagrams).isTrue()
            // The receiving side saw it arrive, and acknowledged it — so both directions moved bytes.
            val received = server.stats()
            assertThat(received.udpRx.bytes > payload.size.toLong()).isTrue()
            assertThat(received.udpTx.bytes > 0L).isTrue()

            // Loopback, so the latency is small but it is measured rather than assumed: what matters is
            // that a real number comes back rather than the absent sentinel.
            val rtt = client.rtt()
            assertThat(rtt).isNotNull()
            assertThat(rtt!! >= Duration.ZERO).isTrue()
            assertThat(rtt < 10.seconds).isTrue()
        }
    }

    fun `paths describe the loopback path`() = bounded {
        Loopback.connected { client, _ ->
            val paths = client.paths()
            // No relay and no hole punching to do, so the connection has the one direct path — and
            // exactly one of its paths is the one traffic is going over.
            assertThat(paths.isNotEmpty()).isTrue()
            assertThat(paths.count { it.isSelected }).isEqualTo(1)
            val path = paths.single { it.isSelected }
            assertThat(path.remoteAddr is TransportAddr.Ip).isTrue()
            assertThat((path.remoteAddr as TransportAddr.Ip).addr.ip).isEqualTo("127.0.0.1")
            assertThat(path.pathId >= 0L).isTrue()
            // A path that is carrying traffic has an MTU and a congestion window.
            assertThat(path.currentMtu > 0).isTrue()
            assertThat(path.congestionWindow > 0L).isTrue()
            assertThat(path.rtt >= Duration.ZERO).isTrue()
            assertThat(path.udpTx.datagrams > 0L).isTrue()
            // And it renders as something a log line can use.
            assertThat(path.toString().contains("selected")).isTrue()
        }
    }

    // ── Datagrams ────────────────────────────────────────────────────────────────────────────

    fun `datagrams round trip both ways`() = bounded {
        Loopback.connected { client, server ->
            // Datagrams are unreliable, so this is the one place a test has to be honest about what it
            // proves: over loopback with no congestion nothing is dropped, and that is why it is a
            // usable assertion here and would not be over a real network.
            val ping = "ping".encodeToByteArray()
            client.sendDatagram(ping)
            assertThat(server.readDatagram()).isEqualTo(ping)

            // The waiting form, and the other direction.
            val pong = "pong".encodeToByteArray()
            server.sendDatagramWait(pong)
            assertThat(client.readDatagram()).isEqualTo(pong)

            // Buffer space is a number, and the maximum is the kilobyte-plus iroh guarantees.
            assertThat(client.datagramSendBufferSpace() >= 0L).isTrue()
            val max = client.maxDatagramSize()
            assertThat(max).isNotNull()
            assertThat(max!! > 1000L).isTrue()
        }
    }

    fun `a datagram over the maximum size is refused cleanly`() = bounded {
        Loopback.connected { client, server ->
            assertThat(client.maxDatagramSize()).isNotNull()

            // Deliberately not `maxDatagramSize() + 1`. That number is a *live* path-MTU estimate and it
            // grows: on loopback iroh's MTU discovery raises it within milliseconds of the handshake, so
            // a size read one moment and sent the next is a race that fails intermittently — which is
            // exactly the flake this suite exists to avoid. 64 KiB can never fit: it is larger than a UDP
            // datagram's own maximum payload, whatever QUIC negotiates on top.
            val tooLarge = ByteArray(64 * 1024)

            // Reported rather than fragmented, because a datagram has to fit one packet.
            val failure = assertFailsWith<IrohError> { client.sendDatagram(tooLarge) }
            assertThat(failure.code).isEqualTo(IrohError.Code.Write)
            // And waiting for buffer space does not make an over-sized datagram fit.
            assertThat(assertFailsWith<IrohError> { client.sendDatagramWait(tooLarge) }.code)
                .isEqualTo(IrohError.Code.Write)

            // The connection is undamaged: a datagram comfortably under the kilobyte-plus iroh
            // guarantees still goes through.
            val ordinary = ByteArray(1024) { 7 }
            client.sendDatagram(ordinary)
            assertThat(server.readDatagram()).isEqualTo(ordinary)
            assertThat(client.isClosed).isFalse()
        }
    }

    // ── Limits ───────────────────────────────────────────────────────────────────────────────

    fun `the connection limits take a varint and reject anything else`() = bounded {
        Loopback.connected { client, _ ->
            val setters: List<Pair<String, (Long) -> Unit>> = listOf(
                "setMaxConcurrentBiStreams" to { value -> client.setMaxConcurrentBiStreams(value) },
                "setMaxConcurrentUniStreams" to { value -> client.setMaxConcurrentUniStreams(value) },
                "setReceiveWindow" to { value -> client.setReceiveWindow(value) },
            )
            for ((name, apply) in setters) {
                // Legal values are applied and report nothing, which is iroh's own shape for them. They
                // only ever go *up*, because both a stream limit and a flow-control window are promises
                // to the peer that QUIC does not allow to be withdrawn.
                apply(64L)
                apply(4096L)
                apply(1L shl 20)

                // A QUIC varint is 62 bits and unsigned; Kotlin's `Long` is wider and signed, so both
                // ends of the range are the caller's mistake to hear about rather than ours to truncate.
                // Note the *varint* maximum is not applied to a live connection here even though iroh4k
                // accepts it: a stream limit above 2^60 is a protocol violation the peer answers by
                // closing, so what would be under test is QUIC's rule rather than this binding's.
                for (value in listOf(-1L, Long.MIN_VALUE, 1L shl 62, Long.MAX_VALUE)) {
                    val failure = assertFailsWith<IrohError>("expected $name($value) to be rejected") {
                        apply(value)
                    }
                    assertThat(failure.code).isEqualTo(IrohError.Code.InvalidArgument)
                }
            }
            // None of that closed anything, which is what says the rejections never reached iroh.
            assertThat(client.isClosed).isFalse()
        }
    }

    // ── Waiting for the end ──────────────────────────────────────────────────────────────────

    fun `closed resolves when the peer closes`() = bounded {
        Loopback.connected { client, server ->
            val waiting = async { client.closed() }

            // Nothing has happened yet, so the wait is genuinely a wait.
            assertThat(client.isClosed).isFalse()
            server.close(11L, "peer is done".encodeToByteArray())

            val reason = waiting.await()
            // iroh's own text for the ending, which carries the code the peer chose.
            assertThat(reason.isNotEmpty()).isTrue()
            assertThat(reason.contains("11")).isTrue()
            // And by then the synchronous view agrees.
            awaitUntil { client.isClosed }
            assertThat(client.closeReason).isEqualTo(reason)

            // Already closed: it answers at once rather than waiting for a second ending.
            assertThat(client.closed()).isEqualTo(reason)
        }
    }

    fun `cancelling closed returns promptly and drains the op registry`() = bounded {
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val baseline = ops.value
        Loopback.connected { client, _ ->
            // The peer is alive and quiet, so this waits forever. Only cancellation ends it.
            val job = launch(Dispatchers.Default) { client.closed() }
            ops.awaitAtLeast(baseline + 1)

            val elapsed = measureTime { job.cancelAndJoin() }

            assertThat(job.isCancelled).isTrue()
            assertThat(elapsed < 5.seconds).isTrue()
            ops.awaitAtMost(baseline)

            // The connection survived having a `closed` aborted under it, and is still usable.
            assertThat(client.isClosed).isFalse()
            assertThat(client.stats().udpTx.datagrams > 0L).isTrue()
        }
        ops.awaitAtMost(baseline)
    }

    // ── Release ──────────────────────────────────────────────────────────────────────────────

    fun `a released incoming connection raises Closed from every member`() = bounded {
        withIncoming { incoming ->
            incoming.close()
            assertThat(incoming.isReleased).isTrue()

            // Every member, not one: a guard that only covered the first method anybody tried would
            // pass a narrower test and still dereference a freed pointer on the next call.
            val members: List<Pair<String, () -> Any?>> = listOf(
                "accept" to { incoming.accept() },
                "refuse" to { incoming.refuse() },
                "retry" to { incoming.retry() },
                "ignore" to { incoming.ignore() },
                "localAddr" to { incoming.localAddr() },
                "remoteAddr" to { incoming.remoteAddr() },
                "remoteAddrValidated" to { incoming.remoteAddrValidated() },
            )
            for ((name, call) in members) {
                assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                    .isEqualTo(IrohError.Code.Closed)
            }

            // `close()` stays idempotent and `isReleased` keeps answering rather than raising.
            incoming.close()
            incoming.close()
            assertThat(incoming.isReleased).isTrue()
            assertThat(incoming.toString()).isEqualTo("Incoming(released)")
        }
    }

    fun `a released connection attempt raises Closed from every member`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            Endpoint.bind(config()).use { client ->
                // A `Connecting` that is never awaited, released while the attempt is still in
                // flight — which abandons it, as dropping iroh's own `Connecting` does.
                val connecting = client.startConnect(server.addr(), alpn)
                // Readable while it is alive, and the id it reports is the one that was dialled.
                assertThat(connecting.remoteId()).isEqualTo(server.id)
                connecting.close()
                assertThat(connecting.isReleased).isTrue()

                val members: List<Pair<String, suspend () -> Any?>> = listOf(
                    "connect" to { connecting.connect() },
                    "alpn" to { connecting.alpn() },
                    "remoteId" to { connecting.remoteId() },
                )
                for ((name, call) in members) {
                    assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                        .isEqualTo(IrohError.Code.Closed)
                }

                connecting.close()
                assertThat(connecting.toString()).isEqualTo("Connecting(released)")
            }
        }
    }

    fun `a released accepting handshake raises Closed from every member`() = bounded {
        withIncoming { incoming ->
            val accepting = incoming.accept()
            accepting.close()
            assertThat(accepting.isReleased).isTrue()

            val members: List<Pair<String, suspend () -> Any?>> = listOf(
                "connect" to { accepting.connect() },
                "alpn" to { accepting.alpn() },
            )
            for ((name, call) in members) {
                assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                    .isEqualTo(IrohError.Code.Closed)
            }

            accepting.close()
            assertThat(accepting.toString()).isEqualTo("Accepting(released)")
        }
    }

    fun `a released connection raises Closed from every member`() = bounded {
        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            Endpoint.bind(config()).use { client ->
                val accepted = async { server.acceptOne() }
                val outbound = dial(client, server.addr()).await()
                val inbound = accepted.await()
                outbound.close()

                inbound.close()
                assertThat(inbound.isReleased).isTrue()

                val members: List<Pair<String, () -> Any?>> = listOf(
                    "alpn" to { inbound.alpn() },
                    "remoteId" to { inbound.remoteId() },
                    "stableId" to { inbound.stableId() },
                    "closeReason" to { inbound.closeReason },
                    "isClosed" to { inbound.isClosed },
                    "close(code)" to { inbound.close(0L) },
                    "close(code, reason)" to { inbound.close(0L, ByteArray(0)) },
                    "side" to { inbound.side() },
                    "stats" to { inbound.stats() },
                    "rtt" to { inbound.rtt() },
                    "paths" to { inbound.paths() },
                    "maxDatagramSize" to { inbound.maxDatagramSize() },
                    "datagramSendBufferSpace" to { inbound.datagramSendBufferSpace() },
                    "sendDatagram" to { inbound.sendDatagram(ByteArray(1)) },
                    "setMaxConcurrentBiStreams" to { inbound.setMaxConcurrentBiStreams(1L) },
                    "setMaxConcurrentUniStreams" to { inbound.setMaxConcurrentUniStreams(1L) },
                    "setReceiveWindow" to { inbound.setReceiveWindow(1L) },
                )
                for ((name, call) in members) {
                    assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                        .isEqualTo(IrohError.Code.Closed)
                }

                // And the suspending ones, including the four stream openers — every one of which goes
                // through the same guard, so a released connection cannot start work it could not finish.
                val suspending: List<Pair<String, suspend () -> Any?>> = listOf(
                    "closed" to { inbound.closed() },
                    "sendDatagramWait" to { inbound.sendDatagramWait(ByteArray(1)) },
                    "readDatagram" to { inbound.readDatagram() },
                    "openBi" to { inbound.openBi() },
                    "acceptBi" to { inbound.acceptBi() },
                    "openUni" to { inbound.openUni() },
                    "acceptUni" to { inbound.acceptUni() },
                )
                for ((name, call) in suspending) {
                    assertThat(assertFailsWith<IrohError>("expected $name to report Closed") { call() }.code)
                        .isEqualTo(IrohError.Code.Closed)
                }

                inbound.close()
                assertThat(inbound.toString()).isEqualTo("Connection(released)")
            }
        }
    }

    // ── Leaks ────────────────────────────────────────────────────────────────────────────────

    fun `repeated accept cycles leak neither handles nor operations`() = bounded {
        val connections = LiveCounters.connectionHandles
        val endpoints = LiveCounters.endpointHandles
        val ops = LiveCounters.operations
        // Quiescent baselines, or the ceilings below would be slack by however much the previous
        // test was still dropping — which is how a leak detector quietly stops detecting.
        LiveCounters.settle()
        val handleBaseline = connections.value
        val endpointBaseline = endpoints.value
        val opBaseline = ops.value

        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            val addr = server.addr()
            Endpoint.bind(config()).use { client ->
                repeat(4) {
                    val accepted = async { server.acceptOne() }
                    val outbound = dial(client, addr).await()
                    val inbound = accepted.await()
                    inbound.close()
                    outbound.close()
                }
                // Four connections' worth of `Incoming`, `Accepting`, `Connecting` and `Connection`
                // handles have come and gone. Without this counter a leak would be invisible from
                // Kotlin: the loop above would pass while every cycle stranded a QUIC connection.
                connections.awaitAtMost(handleBaseline)
            }
        }

        connections.awaitAtMost(handleBaseline)
        endpoints.awaitAtMost(endpointBaseline)
        ops.awaitAtMost(opBaseline)
    }

    // ── Value types ──────────────────────────────────────────────────────────────────────────

    fun `incoming and local addresses are values`() {
        // Pure Kotlin, no endpoint needed: these are the shapes the payload decoders produce, and an
        // address that compared by reference would be quietly wrong for every set and map they land
        // in — the reason `CustomAddr` hand-writes its equality.
        val url = RelayUrl.parse("https://relay.example.com")
        val id = SecretKey.generate().public()
        val other = SecretKey.generate().public()

        assertThat(IncomingAddr.Relay(url, id)).isEqualTo(IncomingAddr.Relay(url, id))
        assertThat(IncomingAddr.Relay(url, id)).isNotEqualTo(IncomingAddr.Relay(url, other))
        assertThat(IncomingAddr.Ip(SocketAddr.parse("127.0.0.1:1")))
            .isNotEqualTo(IncomingAddr.Ip(SocketAddr.parse("127.0.0.1:2")))
        assertThat(IncomingAddr.Custom(CustomAddr(7, byteArrayOf(1, 2))))
            .isEqualTo(IncomingAddr.Custom(CustomAddr(7, byteArrayOf(1, 2))))
        assertThat(IncomingAddr.Custom(CustomAddr(7, byteArrayOf(1, 2))))
            .isNotEqualTo(IncomingAddr.Custom(CustomAddr(7, byteArrayOf(2, 1))))

        // Both optional variants render their absence rather than printing `null`, because "the OS
        // did not say" is the common case and reads as information, not as a bug.
        assertThat(LocalTransportAddr.Ip(null).toString()).isEqualTo("ip:unknown")
        assertThat(LocalTransportAddr.Custom(null).toString()).isEqualTo("custom:unknown")
        assertThat(LocalTransportAddr.Ip("127.0.0.1")).isEqualTo(LocalTransportAddr.Ip("127.0.0.1"))
        assertThat(LocalTransportAddr.Ip("127.0.0.1")).isNotEqualTo(LocalTransportAddr.Ip(null))
        assertThat(LocalTransportAddr.Relay(url).toString()).isEqualTo("relay:$url")

        // The `Unknown` arms exist so a newer iroh's variant is surfaced rather than dropped.
        assertThat(IncomingAddr.Unknown("Something(1)").toString()).isEqualTo("unknown:Something(1)")
        assertThat(LocalTransportAddr.Unknown("Something(1)").description).isEqualTo("Something(1)")
    }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────

    /** Runs [block] on a real dispatcher under a real-time timeout — see [Loopback.bounded]. */
    private fun bounded(block: suspend CoroutineScope.() -> Unit) = Loopback.bounded(block)


    /** Dials [addr] from [client] in the background, so the accepting side can run concurrently. */
    private fun CoroutineScope.dial(
        client: Endpoint,
        addr: EndpointAddr,
        protocol: ByteArray = alpn,
    ): Deferred<Connection> =
        async { client.startConnect(addr, protocol).use { it.connect() } }

    /**
     * Binds a server and a client, has the client dial, and hands [block] the resulting [Incoming].
     *
     * The dial is deliberately left in flight and cancelled afterwards: these tests are about what an
     * `Incoming` does *before* anyone decides its fate, so completing the handshake would defeat
     * them. The dialler's own failure is expected and swallowed for the same reason.
     */
    private suspend fun withIncoming(block: suspend (Incoming) -> Unit) {
        Endpoint.bind(config(alpns = listOf(alpn))).use { server ->
            Endpoint.bind(config()).use { client ->
                val addr = server.addr()
                coroutineScope {
                    val dialler = launch {
                        try {
                            client.startConnect(addr, alpn).use { it.connect() }.close()
                        } catch (refused: IrohError) {
                            // Refused, ignored or abandoned — all of which the callers arrange.
                        }
                    }
                    try {
                        val incoming =
                            server.acceptNext() ?: error("the server was shut down while accepting")
                        incoming.use { block(it) }
                    } finally {
                        dialler.cancelAndJoin()
                    }
                }
            }
        }
    }

    /** Polls [condition] on a real clock — see [Loopback.awaitUntil]. */
    private suspend fun awaitUntil(condition: () -> Boolean) = Loopback.awaitUntil(condition)
}

/**
 * The hermetic two-endpoint harness the connection and stream suites share.
 *
 * One implementation rather than two: every test in both suites needs the same three things — a
 * configuration that reaches nothing but loopback, a real-time timeout so a regression fails instead of
 * hanging CI, and a poll that does not get skipped by `runTest`'s virtual clock. A second copy would
 * eventually differ from this one in exactly the way that makes a flake hard to explain.
 *
 * ## Hermetic
 *
 * [config] binds with [EndpointPreset.Minimal], [RelayMode.Disabled] and `127.0.0.1:0`. That
 * combination has no relay, and its only address lookup is the in-memory book behind
 * [Endpoint.addEndpointAddr] — which resolves what Kotlin put into it and publishes nowhere — so
 * nothing here touches anything outside the machine. Two such endpoints *can* still reach each other
 * over loopback, given each other's [Endpoint.addr]. That is the only offline way to have something
 * to accept.
 */
internal object Loopback {

    /** The protocol the endpoints below advertise. */
    val alpn: ByteArray get() = "iroh4k/m5/1".encodeToByteArray()

    /** A configuration that reaches nothing but loopback. */
    fun config(alpns: List<ByteArray> = emptyList()) = EndpointConfig(
        preset = EndpointPreset.Minimal,
        alpns = alpns,
        relayMode = RelayMode.Disabled,
        bindAddrs = listOf(SocketAddr.parse("127.0.0.1:0")),
    )

    /**
     * Runs [block] on a real dispatcher under a real-time timeout.
     *
     * Both halves matter. `runTest`'s virtual clock would skip a `withTimeout` instead of applying it,
     * and these bodies wait on genuine network events rather than on `delay`. The timeout is generous —
     * the point is that a regression *fails* rather than hanging CI, not that it fails quickly.
     *
     * [supervisorScope] rather than `coroutineScope`, because several tests start a peer that is
     * *expected* to fail: under a plain scope that failure would cancel the other side too, before
     * anything could be asserted about it.
     */
    fun bounded(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(60.seconds) { supervisorScope { block() } }
        }
    }

    /**
     * Binds two endpoints, connects them, and hands [block] both ends of the one connection.
     *
     * `server` is the accepting side's view and `client` the dialling side's — two [Connection] objects
     * for the same QUIC connection, which is what makes an in-process round trip possible. Everything is
     * released afterwards however [block] ends.
     */
    suspend fun connected(block: suspend CoroutineScope.(client: Connection, server: Connection) -> Unit) {
        Endpoint.bind(config(alpns = listOf(alpn))).use { serverEndpoint ->
            Endpoint.bind(config()).use { clientEndpoint ->
                coroutineScope {
                    val accepted = async { serverEndpoint.acceptOne() }
                    val client = clientEndpoint.connect(serverEndpoint.addr(), alpn)
                    val server = accepted.await()
                    try {
                        block(client, server)
                    } finally {
                        client.close()
                        server.close()
                    }
                }
            }
        }
    }

    /** Polls [condition] on a real clock, since `runTest` would otherwise skip the delays. */
    suspend fun awaitUntil(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) {
                while (!condition()) delay(10)
            }
        }
    }
}

/**
 * Accepts one inbound connection and completes its handshake, releasing the chain behind it.
 *
 * Top-level rather than a member of [Loopback] so both suites can write `server.acceptOne()` without
 * bringing the object into scope.
 */
internal suspend fun Endpoint.acceptOne(): Connection {
    val incoming = acceptNext() ?: error("the endpoint was shut down while accepting")
    return incoming.use { pending -> pending.accept().use { it.connect() } }
}
