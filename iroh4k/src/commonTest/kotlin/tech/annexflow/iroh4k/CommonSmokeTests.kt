package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.startsWith
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Test bodies shared by every target, so the FFI (Kotlin/Native) and JNI (JVM/Android) facades
 * are held to identical behaviour.
 *
 * Platform test classes construct this and delegate one `@Test` per method — see
 * `nativeTest`/`jvmTest`.
 */
class CommonSmokeTests {

    fun `version reports both iroh4k and iroh versions`() {
        val version = Iroh4k.version
        // Format is "<iroh4k version>+iroh<iroh version>", e.g. "0.2.0+iroh1.0.3".
        assertThat(version).startsWith("0.2.0+iroh")
        assertThat(version).contains("+iroh1.")
    }

    fun `smokeEcho round-trips a value through the tokio runtime`() = runTest {
        assertThat(Iroh4k.smokeEcho(42L)).isEqualTo(42L)
        assertThat(Iroh4k.smokeEcho(-1L)).isEqualTo(-1L)
        assertThat(Iroh4k.smokeEcho(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE)
    }

    fun `many concurrent echoes each resume their own continuation`() = runTest {
        // Launched all at once so many continuations are outstanding simultaneously. Completion
        // callbacks arrive on tokio worker threads, so this is what catches a StableRef being
        // shared, disposed twice, or resumed with another operation's result.
        val results = coroutineScope {
            (1..200L).map { async { Iroh4k.smokeEcho(it) } }.awaitAll()
        }
        assertThat(results).isEqualTo((1..200L).toList())
    }

    fun `smokeError surfaces the Rust error code and message`() = runTest {
        val error = assertFailsWith<IrohError> { Iroh4k.smokeError() }
        assertThat(error.code).isEqualTo(IrohError.Code.InvalidArgument)
        assertThat(error.message!!).contains("smoke test failure")
    }

    fun `smokeRecord round-trips every codec primitive`() {
        // Values chosen to catch the mistakes that actually happen: endianness (int32/int64),
        // sign extension (int64 = Long.MIN_VALUE, negative int32), an unsigned byte above 127,
        // and Some vs None sharing one field encoding.
        assertThat(Iroh4k.smokeRecord()).isEqualTo(
            SmokeRecord(
                flag = true,
                byte = 200,
                int32 = -123_456,
                int64 = Long.MIN_VALUE,
                real = 0.5,
                text = "iroh4k",
                present = "present",
                absent = null,
                numbers = listOf(10, 20, 30),
            )
        )
    }

    fun `cancelling a pending operation returns promptly and drains the registry`() = runTest {
        // `settle()` before the baseline, and a ceiling rather than an equality afterwards: the op
        // registry is process-global, so both halves of that are what keep this from being a flake
        // rather than a leak detector. `LiveCounter` documents the three races involved.
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val baseline = ops.value
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            started.complete(Unit)
            // Far longer than the test should take, so completing normally cannot make the
            // timing assertion pass by accident.
            Iroh4k.smokeSleep(60_000)
        }
        started.await()
        // `started` only proves the coroutine ran; the registry entry appears inside the FFI call
        // after that. Cancelling before it exists would abort nothing and pass regardless.
        ops.awaitAtLeast(baseline + 1)

        val elapsed = measureTime { job.cancelAndJoin() }

        assertThat(job.isCancelled).isEqualTo(true)
        // Cancellation must not wait out the 60s sleep.
        assertThat(elapsed < 5.seconds).isEqualTo(true)
        // Still catches the leak this test exists for: a cancel that failed to remove its entry
        // leaves the count one above the baseline, permanently, and this fails by timeout.
        ops.awaitAtMost(baseline)
    }

    fun `cancelling a pending operation aborts the Rust task`() = runTest {
        // The test above would still pass if the tokio task kept running and merely lost the
        // deliver-once race. This one proves the task was actually aborted: the Rust counter is
        // incremented only *past* the sleep, so it must never move for a cancelled operation
        // even after the sleep duration has elapsed.
        val completionsBefore = Iroh4k.smokeSleepCompletions
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val baseline = ops.value
        val sleepMillis = 500L

        val job = launch(Dispatchers.Default) { Iroh4k.smokeSleep(sleepMillis) }
        // The whole test rests on this: cancelling before the operation registered would abort
        // nothing, and `smokeSleepCompletions` would stay put for the trivial reason.
        ops.awaitAtLeast(baseline + 1)
        job.cancelAndJoin()

        // Wait well past when the sleep would have finished had it not been aborted.
        realDelay(sleepMillis * 4)
        assertThat(Iroh4k.smokeSleepCompletions).isEqualTo(completionsBefore)

        // And confirm the counter does move when the operation is *not* cancelled, so the
        // assertion above is not passing simply because the counter never increments.
        assertThat(Iroh4k.smokeSleep(1)).isEqualTo(1L)
        assertThat(Iroh4k.smokeSleepCompletions).isEqualTo(completionsBefore + 1)
    }

    fun `completed operations do not accumulate in the registry`() = runTest {
        val ops = LiveCounters.operations
        LiveCounters.settle()
        val baseline = ops.value
        repeat(50) { assertThat(Iroh4k.smokeEcho(it.toLong())).isEqualTo(it.toLong()) }
        // Also covers the failure path, which takes a different branch out of the registry.
        repeat(10) { assertFailsWith<IrohError> { Iroh4k.smokeError() } }
        // Sixty entries came and went. A branch that forgot to remove one would leave the count
        // above the settled baseline and stay there, which is what this fails on.
        ops.awaitAtMost(baseline)
    }

    fun `a short sleep still completes normally`() = runTest {
        // Guards against the cancellation plumbing breaking the ordinary success path.
        assertThat(Iroh4k.smokeSleep(50)).isEqualTo(50L)
    }

    fun `ordinal 15 decodes to ZeroRttRejected`() {
        // `IrohError.Code` is decoded by ORDINAL against `core.rs`'s `ERROR_*` constants — the wire
        // contract the whole binding rests on (`Code.of(code) = entries.getOrElse(code) { Unknown }`).
        // Inserting a new entry anywhere but the end silently remaps every error already in the
        // binding, and nothing fails to compile when that happens: this is the one thing that would
        // notice. `stream.rs`'s own mapper tests pin that the four `ZeroRttRejected` arms produce
        // `ERROR_ZERO_RTT_REJECTED` (15); this pins the other half, that 15 decodes back to this
        // variant on the Kotlin side.
        assertThat(IrohError.Code.entries[15]).isEqualTo(IrohError.Code.ZeroRttRejected)
    }

    /** Delays on a real clock, since `runTest` would otherwise skip the delay entirely. */
    private suspend fun realDelay(millis: Long) {
        withContext(Dispatchers.Default) { delay(millis) }
    }
}
