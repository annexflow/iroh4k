package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Test bodies shared by every target, so the FFI (Kotlin/Native) and JNI (JVM/Android) facades are held
 * to identical behaviour for watchers.
 *
 * Platform test classes construct this and delegate one `@Test` per method — see `nativeTest`/`jvmTest`.
 *
 * ## Hermetic, bounded, and honest about what is observable
 *
 * Everything runs over the loopback harness in `CommonConnectionTests.kt` — see [Loopback] — with
 * relays disabled, so nothing leaves the machine. Every body is under a real-time timeout, which
 * matters more here than anywhere: a watcher waits forever by design, so a regression has to *fail*
 * rather than hang CI.
 *
 * That constraint also decides what is asserted. With [RelayMode.Disabled] there is no relay to
 * connect to, so [Endpoint.watchHomeRelay] can only ever produce the empty initial value — the test
 * below asserts exactly that and nothing more, because waiting for a *connected* relay here would be
 * waiting for something that cannot happen. The same discipline applies to
 * [Connection.watchPathEvents]: a loopback connection opens its one path during the handshake, before
 * a watcher on the resulting [Connection] can exist, so `Opened` and `Selected` are unobservable here
 * and only the `Closed` that closing the connection produces is asserted.
 *
 * ## Waiting without sleeping
 *
 * Where a test needs a watcher to be *running* before it changes something, it does not guess: it
 * repeats the change on a real clock until the change is observed. [addressChangesUntilObserved] is
 * that pattern, and it terminates because every iteration supplies a genuinely new address.
 */
class CommonWatchTests {

    // ── The headline: watchers observe ───────────────────────────────────────────────────────

    fun `watchAddr emits the endpoint's own address`() = Loopback.bounded {
        Endpoint.bind(Loopback.config()).use { endpoint ->
            // The first emission is a watcher's *current* value, so it arrives without anything having
            // to change — which is why this needs no trigger and no relay.
            val addr = withTimeout(TIMEOUT) { endpoint.watchAddr().first() }
            assertThat(addr.id).isEqualTo(endpoint.id)
        }
    }

    fun `watchAddr reports an address added while it is being collected`() = Loopback.bounded {
        Endpoint.bind(Loopback.config()).use { endpoint ->
            val external = SocketAddr.parse("203.0.113.7:41337")
            val wanted = TransportAddr.Ip(external)

            val seen = async(Dispatchers.Default) {
                // `first { }` rather than `drop(1).first()`: if the change happened to land before the
                // watcher started, it is already in the initial value, and either way the assertion is
                // the same — the address is observed through the flow.
                endpoint.watchAddr().first { wanted in it }
            }
            endpoint.addExternalAddr(external)

            val addr = withTimeout(TIMEOUT) { seen.await() }
            assertThat(addr.id).isEqualTo(endpoint.id)
            assertThat(wanted in addr).isTrue()
        }
    }

    fun `watchHomeRelay reports no relays for an endpoint that has none`() = Loopback.bounded {
        Endpoint.bind(Loopback.config()).use { endpoint ->
            // The whole of what is observable offline. A relay-less endpoint never connects to one, so
            // the watcher's initial (empty) value is also its last — asserting anything about a
            // *connected* relay here would be asserting on an event that cannot happen.
            val statuses = withTimeout(TIMEOUT) { endpoint.watchHomeRelay().first() }
            assertThat(statuses).isEqualTo(emptyList<RelayStatus>())
        }
    }

    fun `watchNetworkChange emits when the endpoint's reachability moves`() = Loopback.bounded {
        Endpoint.bind(Loopback.config()).use { endpoint ->
            val changed = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) {
                endpoint.watchNetworkChange().first()
                changed.complete(Unit)
            }

            // This flow drops the watcher's initial value, so the change has to happen after the
            // watcher is running. See [addressChangesUntilObserved].
            addressChangesUntilObserved(endpoint, changed)

            assertThat(changed.isCompleted).isTrue()
            collector.join()
        }
    }

    fun `watchPaths emits the live paths of a loopback connection`() = Loopback.bounded {
        Loopback.connected { client, _ ->
            val paths = withTimeout(TIMEOUT) { client.watchPaths().first() }

            // A connected loopback pair always has at least the direct path it handshook over, and with
            // relays disabled that is the only kind it can have.
            assertThat(paths.isNotEmpty()).isTrue()
            val selected = paths.singleOrNull { it.isSelected }
            assertThat(selected).isNotNull()
            assertThat(selected!!.remoteAddr is TransportAddr.Ip).isTrue()

            // And it is the same view `paths()` answers, decoded by a second reader against the same
            // layout — which is what would catch the two decoders drifting apart.
            assertThat(paths.map { it.pathId }.toSet())
                .isEqualTo(client.paths().map { it.pathId }.toSet())
        }
    }

    fun `watchPathEvents reports the closing path and then ends`() = Loopback.bounded {
        Loopback.connected { client, _ ->
            val ops = LiveCounters.operations
            LiveCounters.settle()
            val opBaseline = ops.value
            val events = async(Dispatchers.Default) { client.watchPathEvents().toList() }
            // Parked in `next()`, so the watcher exists and its forwarding task is running. Only
            // this counter can say that: the flow is collected on another dispatcher and there is
            // no Kotlin-side signal for "the Rust watcher has subscribed".
            ops.awaitAtLeast(opBaseline + 1)

            client.close(CLOSE_CODE, "watched".encodeToByteArray())

            // Closing is the one path transition this harness can *cause*: the connection's only path
            // was opened during the handshake, before a watcher on the resulting `Connection` could
            // exist, so `Opened` and `Selected` are already history by the time anything can subscribe.
            // iroh emits a final `Closed`, with the path's last statistics, for every path still open
            // when the connection goes — so that is what is asserted, and then that the flow ends.
            val delivered = withTimeout(TIMEOUT) { events.await() }
            assertThat(delivered.any { it is PathEvent.Closed }).isTrue()
            for (event in delivered) {
                // An unrecognised variant means the encoder and the decoder disagree.
                assertThat(event is PathEvent.Unknown).isFalse()
                if (event is PathEvent.Closed) {
                    assertThat(event.path.isSelected).isFalse()
                    assertThat(event.path.remoteAddr is TransportAddr.Ip).isTrue()
                }
            }
        }
    }

    // ── Cold, and one watcher per collector ──────────────────────────────────────────────────

    fun `two collectors on one endpoint both see its address`() = Loopback.bounded {
        Endpoint.bind(Loopback.config()).use { endpoint ->
            val watchers = LiveCounters.watcherHandles
            LiveCounters.settle()
            val baseline = watchers.value

            val first = async(Dispatchers.Default) { endpoint.watchAddr().first() }
            val second = async(Dispatchers.Default) { endpoint.watchAddr().first() }

            assertThat(withTimeout(TIMEOUT) { first.await() }.id).isEqualTo(endpoint.id)
            assertThat(withTimeout(TIMEOUT) { second.await() }.id).isEqualTo(endpoint.id)

            // Cold: each collector had its own native watcher, and both are gone again. A ceiling
            // on a settled baseline, because the counter is process-global — see `LiveCounter`.
            watchers.awaitAtMost(baseline)
        }
    }

    fun `a flow that is never collected creates no watcher`() = Loopback.bounded {
        Endpoint.bind(Loopback.config()).use { endpoint ->
            val watchers = LiveCounters.watcherHandles
            LiveCounters.settle()
            val baseline = watchers.value
            // Building the flow is pure Kotlin; nothing crosses the boundary until someone collects.
            endpoint.watchAddr()
            endpoint.watchHomeRelay()
            endpoint.watchNetworkChange()
            // Exact and immediate, deliberately: the claim is that *nothing* was created, and a
            // wait would let a watcher that appears and is dropped again slip through. Sound only
            // because the baseline was settled — an unsettled one can drift downwards under it.
            assertThat(watchers.value, "$watchers with nothing collected").isEqualTo(baseline)
        }
    }

    // ── Cancellation ─────────────────────────────────────────────────────────────────────────

    fun `cancelling a collector returns promptly and drains the op registry`() = Loopback.bounded {
        Endpoint.bind(Loopback.config()).use { endpoint ->
            val ops = LiveCounters.operations
            val watchers = LiveCounters.watcherHandles
            LiveCounters.settle()
            val opBaseline = ops.value
            val handleBaseline = watchers.value

            // Nothing changes after the initial value, so the second `next()` waits indefinitely.
            val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                endpoint.watchAddr().collect { started.complete(Unit) }
            }
            withTimeout(TIMEOUT) { started.await() }
            // `started` proves a value arrived; the *next* `next()` is the one being cancelled, and
            // this is the only signal that it has reached the registry.
            ops.awaitAtLeast(opBaseline + 1)

            val elapsed = measureTime { job.cancelAndJoin() }

            assertThat(job.isCancelled).isTrue()
            assertThat(elapsed < 5.seconds).isTrue()
            ops.awaitAtMost(opBaseline)
            // And the watcher handle went with it, which is what ends the Rust forwarding task.
            watchers.awaitAtMost(handleBaseline)
        }
    }

    fun `take ends a collection and releases its watcher`() = Loopback.bounded {
        Endpoint.bind(Loopback.config()).use { endpoint ->
            val watchers = LiveCounters.watcherHandles
            val ops = LiveCounters.operations
            LiveCounters.settle()
            val handleBaseline = watchers.value
            val opBaseline = ops.value

            // `take(1)` cancels the flow from the inside once it has what it wanted, which is the same
            // teardown path as a cancelled collector and the one an ordinary caller hits most.
            val addrs = withTimeout(TIMEOUT) { endpoint.watchAddr().take(1).toList() }
            assertThat(addrs.size).isEqualTo(1)

            // A `take` that ended the flow without releasing its watcher leaves this above the
            // settled baseline permanently, which is exactly what the wait fails on.
            watchers.awaitAtMost(handleBaseline)
            ops.awaitAtMost(opBaseline)
        }
    }

    // ── Ending ───────────────────────────────────────────────────────────────────────────────

    fun `shutting the endpoint down ends its watchers without throwing`() = Loopback.bounded {
        val endpoint = Endpoint.bind(Loopback.config())
        try {
            val ops = LiveCounters.operations
            LiveCounters.settle()
            val opBaseline = ops.value
            val started = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                endpoint.watchAddr().collect { started.complete(Unit) }
                // Reached only if the flow *completed*. A failure would propagate instead.
                ended.complete(Unit)
            }
            withTimeout(TIMEOUT) { started.await() }
            // The shutdown has to land on a watcher that is genuinely parked in `next()`, which is
            // what this waits for; landing before that would test nothing.
            ops.awaitAtLeast(opBaseline + 1)

            endpoint.shutdown()

            withTimeout(TIMEOUT) { ended.await() }
            job.join()
            assertThat(job.isCancelled).isFalse()
        } finally {
            endpoint.close()
        }
    }

    fun `closing the connection ends watchPaths without throwing`() = Loopback.bounded {
        Loopback.connected { client, _ ->
            val ops = LiveCounters.operations
            LiveCounters.settle()
            val opBaseline = ops.value
            val started = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                client.watchPaths().collect { started.complete(Unit) }
                ended.complete(Unit)
            }
            withTimeout(TIMEOUT) { started.await() }
            // As above: the close must land on a watcher parked in `next()`.
            ops.awaitAtLeast(opBaseline + 1)

            // The QUIC close, not merely releasing the handle: `watchPaths` holds a clone of the
            // connection because iroh's `paths_stream` borrows it, and `Watch.kt` documents that.
            client.close(CLOSE_CODE, "done".encodeToByteArray())

            withTimeout(TIMEOUT) { ended.await() }
            job.join()
            assertThat(job.isCancelled).isFalse()
        }
    }

    // ── Use after close ──────────────────────────────────────────────────────────────────────

    fun `watching a released endpoint raises Closed`() = Loopback.bounded {
        val endpoint = Endpoint.bind(Loopback.config())
        endpoint.close()
        assertThat(endpoint.isReleased).isTrue()

        assertThat(assertFailsWith<IrohError> { endpoint.watchAddr().first() }.code)
            .isEqualTo(IrohError.Code.Closed)
        assertThat(assertFailsWith<IrohError> { endpoint.watchHomeRelay().first() }.code)
            .isEqualTo(IrohError.Code.Closed)
        assertThat(assertFailsWith<IrohError> { endpoint.watchNetworkChange().first() }.code)
            .isEqualTo(IrohError.Code.Closed)
    }

    fun `watching a released connection raises Closed`() = Loopback.bounded {
        Loopback.connected { client, _ ->
            client.close()
            assertThat(client.isReleased).isTrue()

            assertThat(assertFailsWith<IrohError> { client.watchPaths().first() }.code)
                .isEqualTo(IrohError.Code.Closed)
            assertThat(assertFailsWith<IrohError> { client.watchPathEvents().first() }.code)
                .isEqualTo(IrohError.Code.Closed)
        }
    }

    // ── Leaks ────────────────────────────────────────────────────────────────────────────────

    fun `repeated collect and cancel cycles leak neither watchers nor operations`() = Loopback.bounded {
        val watchers = LiveCounters.watcherHandles
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val handleBaseline = watchers.value
        val opBaseline = ops.value

        Loopback.connected { client, _ ->
            repeat(4) {
                // One of each shape: the two endpoint watchers, the borrowing connection watcher, and
                // the event stream — so a leak in any single forwarding task would show up here.
                val endpointAddr = async(Dispatchers.Default) { client.watchPaths().first() }
                withTimeout(TIMEOUT) { endpointAddr.await() }

                val events = launch(Dispatchers.Default) { client.watchPathEvents().collect { } }
                // The watcher has to exist before it is cancelled; each round ends back at the
                // baseline, so the same floor holds on every iteration.
                watchers.awaitAtLeast(handleBaseline + 1)
                events.cancelAndJoin()

                watchers.awaitAtMost(handleBaseline)
            }
        }

        watchers.awaitAtMost(handleBaseline)
        ops.awaitAtMost(opBaseline)
    }

    fun `repeated endpoint watcher cycles leak neither watchers nor operations`() = Loopback.bounded {
        val watchers = LiveCounters.watcherHandles
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val handleBaseline = watchers.value
        val opBaseline = ops.value

        Endpoint.bind(Loopback.config()).use { endpoint ->
            repeat(4) {
                assertThat(withTimeout(TIMEOUT) { endpoint.watchAddr().first() }.id)
                    .isEqualTo(endpoint.id)
                assertThat(withTimeout(TIMEOUT) { endpoint.watchHomeRelay().first() })
                    .isEqualTo(emptyList<RelayStatus>())
            }
            // Eight collections over four rounds: a watcher stranded by any one of them keeps
            // this above the settled baseline and the wait fails naming the count.
            watchers.awaitAtMost(handleBaseline)
        }

        watchers.awaitAtMost(handleBaseline)
        ops.awaitAtMost(opBaseline)
    }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────

    /**
     * Changes [endpoint]'s addresses, over and over, until [observed] completes.
     *
     * A flow that drops its watcher's initial value can only be triggered by a change that happens
     * *after* the watcher is running, and there is no observable moment at which that becomes true —
     * the watcher handle exists before its Rust forwarding task has built its stream. Rather than
     * sleep and hope, this keeps supplying genuinely new addresses on a real clock, so the first one
     * that lands after the watcher started is the one that fires it.
     *
     * Terminates: each round adds an address no previous round used, and the loop is bounded both by
     * [ROUNDS] and by the surrounding [Loopback.bounded] timeout.
     */
    private suspend fun addressChangesUntilObserved(
        endpoint: Endpoint,
        observed: CompletableDeferred<Unit>,
    ) {
        for (round in 1..ROUNDS) {
            if (observed.isCompleted) return
            endpoint.addExternalAddr(SocketAddr.parse("198.51.100.$round:5555"))
            withTimeoutOrNull(SETTLE) { observed.await() }
        }
        assertThat(observed.isCompleted).isTrue()
    }

    private companion object {
        /** Generous, so a regression fails rather than hanging — never reached on a healthy build. */
        val TIMEOUT = 30.seconds

        /** How long one address change is given to reach a watcher before another is made. */
        val SETTLE = 250.milliseconds

        /** An upper bound on [addressChangesUntilObserved], well past what a healthy build needs. */
        const val ROUNDS = 60

        /** An arbitrary QUIC application close code, chosen so the message is recognisable. */
        const val CLOSE_CODE = 7_070L
    }
}
