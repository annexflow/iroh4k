package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Test bodies shared by every target, so the FFI (Kotlin/Native) and JNI (JVM/Android) facades are
 * held to identical behaviour for the router.
 *
 * Platform test classes construct this and delegate one `@Test` per method — see
 * `nativeTest`/`jvmTest`.
 *
 * ## The router is pure Kotlin, and these tests still run twice
 *
 * `Router.kt` has no FFI surface of its own: it is an accept loop over [Endpoint.acceptNext]. So
 * nothing here exercises a per-facade `expect`/`actual` pair directly — but everything here runs on
 * top of one, and the point of running the suite on both facades is that a difference in the *timing*
 * or *cancellation* of the accept chain (a JNI op that does not abort, a native continuation resumed
 * twice) would show up as a hung or flaky router rather than as a failing codec test.
 *
 * ## Hermetic, and bounded
 *
 * Every endpoint is bound exactly as `CommonConnectionTests` binds them — [EndpointPreset.Minimal],
 * [RelayMode.Disabled], `127.0.0.1:0` — so nothing leaves the machine, and two of them still reach
 * each other over loopback.
 *
 * Every body runs under [bounded]: a real dispatcher and a real-time [withTimeout]. That is
 * load-bearing here. A router whose loop is blocked by a slow handler does not fail an assertion, it
 * simply never serves the next connection — so the shape of these tests is "the latch must open",
 * and the timeout is what turns "never" into a failure instead of a hung CI job. For the same reason
 * the waiting is done with [CompletableDeferred] latches rather than sleeps: nothing here passes or
 * fails on a race between two delays.
 */
class CommonRouterTests {

    /** The protocol the routers below serve. */
    private val alpn: ByteArray get() = "iroh4k/m8/1".encodeToByteArray()

    /** A second protocol, for the multi-protocol and unroutable cases. */
    private val otherAlpn: ByteArray get() = "iroh4k/m8/2".encodeToByteArray()

    /** A configuration that reaches nothing but loopback — see the class documentation. */
    private fun config(alpns: List<ByteArray> = emptyList()) = EndpointConfig(
        preset = EndpointPreset.Minimal,
        alpns = alpns,
        relayMode = RelayMode.Disabled,
        bindAddrs = listOf(SocketAddr.parse("127.0.0.1:0")),
    )

    // ── Dispatch ─────────────────────────────────────────────────────────────────────────────

    fun `a router dispatches a connection to its handler`() = bounded {
        // Bound with no ALPNs at all, deliberately: the only reason the dial below can negotiate
        // anything is that `spawn` advertised the router's protocol on the endpoint.
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val served = CompletableDeferred<Pair<EndpointId, ByteArray>>()
                val router = Router.builder(server)
                    .accept(alpn) { connection ->
                        served.complete(connection.remoteId() to connection.alpn())
                    }
                    .spawn()

                router.use {
                    // The endpoint is the caller's object, handed over rather than copied.
                    assertThat(router.endpoint === server).isTrue()
                    assertThat(router.isShutdown).isFalse()
                    assertThat(router.toString()).isEqualTo("Router(accepting, 1 protocols)")

                    val outbound = dial(client, server.addr()).await()
                    try {
                        val (remoteId, negotiated) = served.await()
                        // The identity the handler sees is the dialler's, established by the
                        // certificate it presented — the whole reason a handler is given a
                        // `Connection` rather than a socket.
                        assertThat(remoteId).isEqualTo(client.id)
                        assertThat(negotiated).isEqualTo(alpn)
                        assertThat(outbound.remoteId()).isEqualTo(server.id)
                    } finally {
                        outbound.close()
                    }
                    router.shutdown()
                }
            }
        }
    }

    fun `two protocols dispatch to their own handlers`() = bounded {
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val first = CompletableDeferred<ByteArray>()
                val second = CompletableDeferred<ByteArray>()
                val router = Router.builder(server)
                    .accept(alpn) { first.complete(it.alpn()) }
                    .accept(otherAlpn) { second.complete(it.alpn()) }
                    .spawn()

                router.use {
                    val addr = server.addr()
                    val one = dial(client, addr, alpn).await()
                    val two = dial(client, addr, otherAlpn).await()
                    try {
                        // Each handler saw its own protocol and nothing else, which is the whole
                        // claim: dispatch is by ALPN, not by arrival order.
                        assertThat(first.await()).isEqualTo(alpn)
                        assertThat(second.await()).isEqualTo(otherAlpn)
                    } finally {
                        one.close()
                        two.close()
                    }
                    router.shutdown()
                }
            }
        }
    }

    fun `registering an alpn twice replaces the first handler`() = bounded {
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val stale = CompletableDeferred<Unit>()
                val current = CompletableDeferred<Unit>()
                val router = Router.builder(server)
                    .accept(alpn) { stale.complete(Unit) }
                    .accept(alpn) { current.complete(Unit) }
                    .spawn()

                router.use {
                    // One protocol, not two: the second registration replaced the first, as
                    // inserting into iroh's protocol map does.
                    assertThat(router.toString()).isEqualTo("Router(accepting, 1 protocols)")

                    val outbound = dial(client, server.addr()).await()
                    try {
                        current.await()
                        assertThat(stale.isCompleted).isFalse()
                    } finally {
                        outbound.close()
                    }
                    router.shutdown()
                }
            }
        }
    }

    // ── Failures the router must survive ─────────────────────────────────────────────────────

    fun `an unregistered alpn is refused and the router keeps accepting`() = bounded {
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val served = CompletableDeferred<EndpointId>()
                val reported = CompletableDeferred<Pair<ByteArray?, Throwable>>()
                val router = Router.builder(server)
                    .accept(alpn) { served.complete(it.remoteId()) }
                    .onFailure { failedAlpn, failure -> reported.complete(failedAlpn to failure) }
                    .spawn()

                router.use {
                    // Widen what the *endpoint* advertises past what the router routes. Without this
                    // the case is unreachable — `spawn` set the endpoint's ALPNs to exactly the
                    // registered ones, so iroh itself would turn `otherAlpn` away before the router
                    // ever saw it. This is the backstop being tested.
                    server.setAlpns(listOf(alpn, otherAlpn))
                    val addr = server.addr()

                    // Refused, not left hanging — and told why. The dialler's own handshake has
                    // already completed by the time the router reads the ALPN, so what it observes
                    // is a connection closed immediately with the router's error code and reason.
                    // That is exactly the distinction `Router.refuse` exists to draw: this is not a
                    // connection that mysteriously died.
                    val refused = dial(client, addr, otherAlpn).await()
                    try {
                        awaitUntil { refused.isClosed }
                        assertThat(refused.closeReason!!.contains("unsupported ALPN")).isTrue()
                    } finally {
                        refused.close()
                    }

                    val (reportedAlpn, failure) = reported.await()
                    assertThat(reportedAlpn!!.contentEquals(otherAlpn)).isTrue()
                    assertThat((failure as IrohError).code).isEqualTo(IrohError.Code.Accept)

                    // And the router is still serving the protocol it does know.
                    val outbound = dial(client, addr, alpn).await()
                    try {
                        assertThat(served.await()).isEqualTo(client.id)
                    } finally {
                        outbound.close()
                    }
                    router.shutdown()
                }
            }
        }
    }

    fun `a failed handshake does not stop the loop`() = bounded {
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val served = CompletableDeferred<EndpointId>()
                val reported = CompletableDeferred<Pair<ByteArray?, Throwable>>()
                val router = Router.builder(server)
                    .accept(alpn) { served.complete(it.remoteId()) }
                    .onFailure { failedAlpn, failure -> reported.complete(failedAlpn to failure) }
                    .spawn()

                router.use {
                    val addr = server.addr()

                    // The endpoint advertises only the router's protocol, so this dial reaches iroh
                    // and is refused *inside the accept chain*: `Incoming.accept` fails with
                    // `Accept` before any handshake begins, which is what M4 established for a
                    // mismatched ALPN. The router has to survive that, not just a throwing handler.
                    val mismatched = dial(client, addr, otherAlpn)
                    assertFailsWith<IrohError> { mismatched.await() }

                    val (reportedAlpn, failure) = reported.await()
                    // No ALPN was ever negotiated, so there is none to report.
                    assertThat(reportedAlpn).isNull()
                    assertThat((failure as IrohError).code).isEqualTo(IrohError.Code.Accept)

                    val outbound = dial(client, addr).await()
                    try {
                        assertThat(served.await()).isEqualTo(client.id)
                    } finally {
                        outbound.close()
                    }
                    router.shutdown()
                }
            }
        }
    }

    fun `a handler that throws does not stop the router`() = bounded {
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                // `complete` answers `true` only for the caller that wins, so this is an atomic
                // "am I the first connection?" without a lock or an atomic counter.
                val firstArrived = CompletableDeferred<Unit>()
                val secondServed = CompletableDeferred<EndpointId>()
                val reported = CompletableDeferred<Throwable>()
                val router = Router.builder(server)
                    .accept(alpn) { connection ->
                        if (firstArrived.complete(Unit)) error("deliberate handler failure")
                        secondServed.complete(connection.remoteId())
                    }
                    .onFailure { _, failure -> reported.complete(failure) }
                    .spawn()

                router.use {
                    val addr = server.addr()

                    // The handshake succeeded, so the dialler gets its connection; the handler blows
                    // up afterwards, which is the router's problem and not the peer's.
                    val doomed = dial(client, addr).await()
                    doomed.close()
                    assertThat(reported.await().message!!.contains("deliberate handler failure"))
                        .isTrue()

                    // The claim: the loop is still there. A handler runs on its own coroutine under
                    // a supervisor, so its failure cannot reach the loop or its siblings.
                    val outbound = dial(client, addr).await()
                    try {
                        assertThat(secondServed.await()).isEqualTo(client.id)
                    } finally {
                        outbound.close()
                    }
                    assertThat(router.isShutdown).isFalse()
                    router.shutdown()
                }
            }
        }
    }

    fun `a failing failure sink does not stop the router`() = bounded {
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val firstArrived = CompletableDeferred<Unit>()
                val secondServed = CompletableDeferred<EndpointId>()
                val sinkCalled = CompletableDeferred<Unit>()
                val router = Router.builder(server)
                    .accept(alpn) { connection ->
                        if (firstArrived.complete(Unit)) error("deliberate handler failure")
                        secondServed.complete(connection.remoteId())
                    }
                    // The reporter is the last place a router can afford to trust: a sink that
                    // throws has nowhere to report to, so it is swallowed rather than allowed to
                    // turn a logged failure into a dead router.
                    .onFailure { _, _ ->
                        sinkCalled.complete(Unit)
                        error("deliberate sink failure")
                    }
                    .spawn()

                router.use {
                    val addr = server.addr()
                    dial(client, addr).await().close()
                    sinkCalled.await()

                    val outbound = dial(client, addr).await()
                    try {
                        assertThat(secondServed.await()).isEqualTo(client.id)
                    } finally {
                        outbound.close()
                    }
                    router.shutdown()
                }
            }
        }
    }

    // ── Concurrency ──────────────────────────────────────────────────────────────────────────

    fun `a suspended handler does not delay another connection`() = bounded {
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val gate = CompletableDeferred<Unit>()
                val firstArrived = CompletableDeferred<Unit>()
                val firstFinished = CompletableDeferred<Unit>()
                val secondServed = CompletableDeferred<Unit>()
                val router = Router.builder(server)
                    .accept(alpn) { _ ->
                        if (firstArrived.complete(Unit)) {
                            gate.await()
                            firstFinished.complete(Unit)
                        } else {
                            secondServed.complete(Unit)
                        }
                    }
                    .spawn()

                router.use {
                    val addr = server.addr()
                    val first = dial(client, addr).await()
                    firstArrived.await()

                    // Nothing here is a race against a clock: if the accept loop awaited its
                    // handlers, the handshake below would never complete — the loop would still be
                    // parked on `gate` — and this test would fail on its timeout rather than on a
                    // lucky ordering.
                    val second = dial(client, addr).await()
                    secondServed.await()

                    // And the first handler really is still parked, so the second was served
                    // *concurrently* rather than after it.
                    assertThat(firstFinished.isCompleted).isFalse()

                    gate.complete(Unit)
                    firstFinished.await()

                    first.close()
                    second.close()
                    router.shutdown()
                }
            }
        }
    }

    // ── Shutdown ─────────────────────────────────────────────────────────────────────────────

    fun `shutdown cancels in-flight handlers and leaves no coroutine`() = bounded {
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val firstArrived = CompletableDeferred<Unit>()
                val firstUnwound = CompletableDeferred<Unit>()
                val servedAfterShutdown = CompletableDeferred<Unit>()
                val router = Router.builder(server)
                    .accept(alpn) { _ ->
                        if (firstArrived.complete(Unit)) {
                            try {
                                // Parks until cancelled, which is what a shutdown has to be able to
                                // interrupt.
                                CompletableDeferred<Unit>().await()
                            } finally {
                                firstUnwound.complete(Unit)
                            }
                        } else {
                            servedAfterShutdown.complete(Unit)
                        }
                    }
                    .spawn()

                val addr = server.addr()
                val outbound = dial(client, addr).await()
                firstArrived.await()
                assertThat(router.isShutdown).isFalse()

                router.shutdown()

                // Cancelled *and* waited for: `shutdown` returning while a handler was still
                // unwinding would be a connection released after the router claimed to be done.
                assertThat(router.isShutdown).isTrue()
                assertThat(firstUnwound.isCompleted).isTrue()
                // The structural claim, which no behavioural assertion can make: the scope is empty.
                assertThat(router.supervisor.isCompleted).isTrue()
                assertThat(router.supervisor.children.count()).isEqualTo(0)

                // Idempotent, and still idempotent from a second coroutine's point of view.
                router.shutdown()
                router.shutdown()
                assertThat(router.isShutdown).isTrue()

                // Nothing is accepted any more. Proving an absence needs a wait rather than a latch;
                // the dial itself never completes, because the loop that would answer it is gone.
                val orphan = dialAndRelease(client, addr)
                assertThat(withTimeoutOrNull(2.seconds) { servedAfterShutdown.await() }).isNull()

                // The attempt is still sitting there to be answered by hand, which is the other half
                // of the claim: it is the *router* that stopped, not the endpoint. Answering it is
                // also how the dial is ended without cancelling it — a cancelled dial abandons
                // operations mid-flight in Rust, and this test is not about that.
                (server.acceptNext() ?: error("the endpoint was shut down")).use { it.refuse() }
                orphan.join()

                outbound.close()
                // The endpoint is untouched by all of that: the router never owned it.
                assertThat(server.isClosed).isFalse()
            }
        }
    }

    fun `close ends the router without waiting`() = bounded {
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val arrived = CompletableDeferred<Unit>()
                val unwound = CompletableDeferred<Unit>()
                val router = Router.builder(server)
                    .accept(alpn) { _ ->
                        arrived.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                        } finally {
                            unwound.complete(Unit)
                        }
                    }
                    .spawn()

                val outbound = dial(client, server.addr()).await()
                arrived.await()

                // `close` cancels and returns; the handler may still be unwinding, which is the
                // difference from `shutdown` and the reason `shutdown` exists.
                router.close()
                assertThat(router.isShutdown).isTrue()
                awaitUntil { unwound.isCompleted }

                router.close()
                router.shutdown()
                assertThat(router.supervisor.isCompleted).isTrue()

                outbound.close()
            }
        }
    }

    fun `a router with no protocols shuts down cleanly and leaves the endpoint alone`() = bounded {
        // Upstream's own `test_shutdown`, with the one assertion inverted: iroh's router ends by
        // closing its endpoint, and this one deliberately does not — it was handed the caller's
        // endpoint rather than a clone of it. See the `Router.kt` header.
        Endpoint.bind(config(alpns = listOf(alpn))).use { endpoint ->
            val router = Router.builder(endpoint).spawn()
            router.use {
                assertThat(router.isShutdown).isFalse()
                assertThat(endpoint.isClosed).isFalse()

                router.shutdown()

                assertThat(router.isShutdown).isTrue()
                assertThat(endpoint.isClosed).isFalse()
                assertThat(router.toString()).isEqualTo("Router(shutdown, 0 protocols)")
                // Still the caller's to end, and still able to do it.
                endpoint.shutdown()
                assertThat(endpoint.isClosed).isTrue()
            }
        }
    }

    fun `a shut-down endpoint ends the accept loop`() = bounded {
        Endpoint.bind(config()).use { endpoint ->
            val router = Router.builder(endpoint).accept(alpn) { }.spawn()
            router.use {
                assertThat(router.isShutdown).isFalse()

                // iroh's `close`: `acceptNext` now answers `null`, which is an ending rather than a
                // failure — so the loop stops instead of spinning on an endpoint that will never
                // accept again, and the router reports itself shut down without anyone asking.
                endpoint.shutdown()

                awaitUntil { router.isShutdown }
                awaitUntil { router.supervisor.isCompleted }
                assertThat(router.supervisor.children.count()).isEqualTo(0)

                // And shutting down a router that already ended is still just a wait.
                router.shutdown()
            }
        }
    }

    fun `the endpoint outlives its router and can carry another one`() = bounded {
        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val addr = server.addr()

                val firstServed = CompletableDeferred<Unit>()
                Router.builder(server).accept(alpn) { firstServed.complete(Unit) }.spawn().use {
                    dial(client, addr).await().close()
                    firstServed.await()
                    it.shutdown()
                }

                // The endpoint is still bound, still identifiable and still accepting — which is
                // exactly what "the router does not close the endpoint" has to mean in practice.
                assertThat(server.isClosed).isFalse()
                assertThat(server.id).isEqualTo(addr.id)

                val secondServed = CompletableDeferred<Unit>()
                Router.builder(server).accept(otherAlpn) { secondServed.complete(Unit) }.spawn()
                    .use {
                        // A second router, a different protocol: `spawn` re-advertised the ALPNs, so
                        // the *old* protocol is gone and the new one works.
                        val outbound = dial(client, addr, otherAlpn).await()
                        secondServed.await()
                        outbound.close()
                        it.shutdown()
                    }
            }
        }
    }

    // ── Leaks ────────────────────────────────────────────────────────────────────────────────

    /**
     * Both counters are process-wide, so both are read through [LiveCounter.awaitAtMost]: a *ceiling*
     * to come back down to rather than a number to match exactly.
     *
     * A handle Kotlin released a millisecond ago may not have been dropped in Rust yet, so a baseline
     * taken at the start of a test can include the tail of the test before it. A ceiling still makes
     * the claim that matters — every handle and every operation this test created was released — and
     * makes it without depending on what the previous test left draining.
     *
     * [LiveCounters.settle] closes the other half of the same problem: a contaminated baseline makes
     * the ceiling *slack* by however much was still draining, which is a leak detector that has
     * quietly stopped detecting small leaks. Settling first makes the ceiling tight again.
     */
    fun `a routed connection leaks neither handles nor operations`() = bounded {
        val connections = LiveCounters.connectionHandles
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val handleBaseline = connections.value
        val opBaseline = ops.value

        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val served = Channel<Unit>(Channel.UNLIMITED)
                val router = Router.builder(server)
                    .accept(alpn) { connection ->
                        // Reading, not retaining: the router releases this handle the moment the
                        // handler returns, so a handler that kept it would be reading a released
                        // one — and the counter below is what proves the release happens.
                        connection.remoteId()
                        served.send(Unit)
                    }
                    .spawn()

                router.use {
                    val addr = server.addr()
                    repeat(4) {
                        val outbound = dial(client, addr).await()
                        outbound.close()
                    }
                    // Every handler has run before the shutdown, which is the point of counting the
                    // arrivals rather than waiting for the first: a shutdown landing on a handshake
                    // still in flight would be measuring a cancellation, and cancellation is the
                    // subject of the test below rather than of this one.
                    repeat(4) { served.receive() }
                    router.shutdown()

                    // Four `Incoming`s, four `Accepting`s and four `Connection`s came and went
                    // inside the router. Nothing but this counter would notice if the loop had
                    // stranded any of them.
                    connections.awaitAtMost(handleBaseline)

                    // And the accept the loop was suspended in when it was cancelled is gone from
                    // the Rust op registry, rather than left running with its result discarded.
                    ops.awaitAtMost(opBaseline)
                }
            }
        }

        connections.awaitAtMost(handleBaseline)
        ops.awaitAtMost(opBaseline)
    }

    fun `shutdown releases every connection it was serving`() = bounded {
        val connections = LiveCounters.connectionHandles
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val handleBaseline = connections.value
        val opBaseline = ops.value

        Endpoint.bind(config()).use { server ->
            Endpoint.bind(config()).use { client ->
                val arrivals = Channel<Unit>(Channel.UNLIMITED)
                val router = Router.builder(server)
                    .accept(alpn) { _ ->
                        arrivals.send(Unit)
                        // Parks with the connection open, so shutdown has to cancel a handler that
                        // is holding a live native handle rather than one about to return anyway.
                        CompletableDeferred<Unit>().await()
                    }
                    .spawn()

                val addr = server.addr()
                // Three connections, all served, all parked. Each dial is completed before the next
                // begins, so nothing is in mid-handshake when the shutdown lands: what is being
                // tested is a shutdown that has to unwind three handlers at once, not a race.
                val outbound = List(3) { dial(client, addr).await() }
                repeat(3) { arrivals.receive() }

                router.shutdown()

                assertThat(router.supervisor.isCompleted).isTrue()
                assertThat(router.supervisor.children.count()).isEqualTo(0)

                // Every one of the three connections the router owned was released as its handler
                // was cancelled — which the *peer* can see, because releasing an open connection
                // closes it. This is the ownership contract observed from the other end of the wire.
                for (connection in outbound) {
                    awaitUntil { connection.isClosed }
                    connection.close()
                }

                // Read as ceilings, for the reason the test above documents.
                connections.awaitAtMost(handleBaseline)
                ops.awaitAtMost(opBaseline)
            }
        }
    }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────

    /**
     * Runs [block] on a real dispatcher under a real-time timeout — `CommonConnectionTests.bounded`,
     * for the same two reasons: `runTest`'s virtual clock would skip a [withTimeout] rather than
     * apply it, and these bodies wait on genuine network events.
     *
     * [supervisorScope] because several tests start a dialler that is *expected* to fail; under a
     * plain scope that failure would cancel the router's own test coroutine alongside it.
     */
    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(60.seconds) { supervisorScope { block() } }
        }
    }

    /** Dials [addr] from [client] in the background, so the router can answer concurrently. */
    private fun CoroutineScope.dial(
        client: Endpoint,
        addr: EndpointAddr,
        protocol: ByteArray = alpn,
    ): Deferred<Connection> =
        async { client.startConnect(addr, protocol).use { it.connect() } }

    /**
     * Dials in the background and releases whatever it gets, for a dial nobody awaits.
     *
     * There is no suspension point between the connection appearing and its release, so this job
     * never leaves a connection nobody owns — and it is meant to end by being *answered* rather than
     * cancelled: cancelling a dial abandons its Rust operations mid-flight, which the counter-based
     * tests cannot tell apart from something the router stranded.
     */
    private fun CoroutineScope.dialAndRelease(
        client: Endpoint,
        addr: EndpointAddr,
        protocol: ByteArray = alpn,
    ): Job = launch {
        try {
            client.startConnect(addr, protocol).use { it.connect() }.close()
        } catch (refused: IrohError) {
            // Refused or abandoned, which is what these dials are for.
        }
    }

    /** Polls [condition] on a real clock, since `runTest` would otherwise skip the delays. */
    private suspend fun awaitUntil(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                while (!condition()) delay(10)
            }
        }
    }
}
