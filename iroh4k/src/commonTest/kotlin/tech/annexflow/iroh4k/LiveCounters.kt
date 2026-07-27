package tech.annexflow.iroh4k

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The leak counters, and the only two ways to assert on one without a race.
 *
 * Every counter here is a `static AtomicI64` in Rust — **process-global**, shared by every test in
 * the facade. That is what makes the obvious assertion wrong:
 *
 * ```kotlin
 * val baseline = Iroh4k.liveOpCount        // may include the previous test's draining tail
 * …
 * awaitUntil { Iroh4k.liveOpCount == baseline }
 * assertThat(Iroh4k.liveOpCount).isEqualTo(baseline)   // re-reads a global: can see another value
 * ```
 *
 * Three separate defects live in those four lines, and all three are latent CI flakes rather than
 * leaks:
 *
 * 1. **The baseline is contaminated.** A handle Kotlin released a millisecond before the test
 *    started may not have been dropped in Rust yet, so the baseline can be *higher* than the
 *    quiescent count. When the tail finishes draining, the counter settles *below* the baseline,
 *    `== baseline` never becomes true, and the wait fails by timeout. [settle] is the fix: it
 *    waits for every counter to stop moving before a baseline is taken, so the number recorded is
 *    the quiescent one.
 * 2. **Equality is the wrong bar for a global.** A leak makes a counter grow and *stay* grown;
 *    anything else in the process makes it move too. [awaitAtMost] asserts the claim that is
 *    actually the test's — everything this test created was released — as a ceiling. Paired with a
 *    settled baseline the ceiling is tight, so it still fails on a single stranded handle.
 * 3. **The re-read.** `awaitUntil` has already proven the condition; reading the counter *again*
 *    afterwards samples a different instant, and a global can differ at that instant for reasons
 *    that have nothing to do with the test. [awaitAtMost] and [awaitAtLeast] therefore assert on
 *    the very observation that satisfied the wait, and never re-read.
 *
 * So: `settle()` once, take baselines, and finish with `awaitAtMost(baseline)`. Do not "simplify"
 * either of those back into `awaitUntil { counter == baseline }` followed by an `assertThat`.
 */
internal class LiveCounter internal constructor(
    private val label: String,
    private val read: () -> Long,
) {

    /** The counter's value right now. Only meaningful against a baseline taken after [settle]. */
    val value: Long get() = read()

    /**
     * Waits for the counter to come back down to at most [ceiling], and says what it was if it
     * does not.
     *
     * This *is* the assertion — there is deliberately nothing to add after it. It fails when the
     * counter is still above the ceiling after [DRAIN_TIMEOUT], which is exactly what a leaked
     * handle or a stranded operation looks like: a counter that goes up and stays up.
     *
     * The reading reported is the last one taken inside the wait, not a fresh one, so the number
     * in the failure message is the number that failed the condition.
     */
    suspend fun awaitAtMost(ceiling: Long) {
        var observed = read()
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(DRAIN_TIMEOUT) {
                while (observed > ceiling) {
                    delay(POLL)
                    observed = read()
                }
            }
        }
        if (observed > ceiling) {
            throw AssertionError("$label did not drain: expected at most $ceiling, still $observed")
        }
    }

    /**
     * Waits for the counter to reach at least [floor], and says what it was if it does not.
     *
     * Used before a cancellation, to know the Rust side has actually registered the operation
     * about to be aborted — otherwise a cancel that lands first would make the test pass without
     * ever exercising the abort path. There is no Kotlin-observable substitute: a
     * `CompletableDeferred` can prove the *coroutine* started, but the registry entry appears
     * inside the FFI call after that, and this counter is the only signal that it exists.
     */
    suspend fun awaitAtLeast(floor: Long) {
        var observed = read()
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(RISE_TIMEOUT) {
                while (observed < floor) {
                    delay(POLL)
                    observed = read()
                }
            }
        }
        if (observed < floor) {
            throw AssertionError("$label never reached $floor: last read $observed")
        }
    }

    override fun toString(): String = label

    private companion object {
        /** How often a counter is sampled while waiting. */
        val POLL = 10.milliseconds

        /** Generous: a healthy build drains in milliseconds, and a leak never drains at all. */
        val DRAIN_TIMEOUT = 10.seconds

        /** An operation that never registers is a regression, not slowness. */
        val RISE_TIMEOUT = 10.seconds
    }
}

/** Every process-global leak counter, so a test can name the one it means. See [LiveCounter]. */
internal object LiveCounters {

    /** Entries in the Rust async-operation registry — `ops.rs`. */
    val operations = LiveCounter("live operations") { Iroh4k.liveOpCount }

    val endpointHandles = LiveCounter("live endpoint handles") { Endpoint.liveHandleCount }

    val connectionHandles = LiveCounter("live connection handles") { Connection.liveHandleCount }

    val streamHandles = LiveCounter("live stream handles") { Streams.liveHandleCount }

    val watcherHandles = LiveCounter("live watcher handles") { Watchers.liveHandleCount }

    val servicesHandles = LiveCounter("live services handles") { ServicesClient.liveHandleCount }

    private val all = listOf(
        operations,
        endpointHandles,
        connectionHandles,
        streamHandles,
        watcherHandles,
        servicesHandles,
    )

    /**
     * Waits until every counter has stopped moving, so the baselines taken next are quiescent.
     *
     * Call this once, before reading any baseline. Without it a baseline can include the previous
     * test's tail — Rust drops that Kotlin has already asked for but that have not landed yet —
     * and every assertion built on that baseline inherits the error: a ceiling becomes slack by
     * however much was still draining, and an exact `baseline + 1` can be missed low when the tail
     * finishes.
     *
     * Stability is judged by [STABLE_READS] consecutive identical samples of *all* counters, which
     * is sound here because the facades run their tests one at a time: once the previous test's
     * teardown has landed there is nothing left to move them. Bounded by [SETTLE_TIMEOUT] and
     * deliberately silent on timeout — failing to settle is not itself a defect, it just leaves
     * the baseline as imprecise as it used to be.
     */
    suspend fun settle() {
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(SETTLE_TIMEOUT) {
                var previous = all.map { it.value }
                var stable = 0
                while (stable < STABLE_READS) {
                    delay(SETTLE_POLL)
                    val current = all.map { it.value }
                    stable = if (current == previous) stable + 1 else 0
                    previous = current
                }
            }
        }
    }

    /** How often the counters are sampled while settling. */
    private val SETTLE_POLL = 20.milliseconds

    /** Consecutive unchanged samples that count as quiescent — roughly 80ms of stillness. */
    private const val STABLE_READS = 4

    /** A cap, not an expectation: a teardown that takes this long is the test's own problem. */
    private val SETTLE_TIMEOUT: Duration = 5.seconds
}
