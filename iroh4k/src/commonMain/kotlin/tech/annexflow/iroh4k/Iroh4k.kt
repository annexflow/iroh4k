package tech.annexflow.iroh4k

import tech.annexflow.iroh4k.internal.BinaryReader

/**
 * A record covering every codec primitive, decoded from the buffer produced by Rust's
 * `core::smoke_record`.
 *
 * Its only job is to prove the Rust encoder and this decoder agree — a mismatch in field order,
 * width, or endianness shows up here rather than inside a real `ConnectionStats` later.
 * Not public API: it exists purely as a conformance check, and the real records
 * (`ConnectionStats`, `PathSnapshot`, …) are what callers actually see.
 */
internal data class SmokeRecord(
    val flag: Boolean,
    val byte: Int,
    val int32: Int,
    val int64: Long,
    val real: Double,
    val text: String,
    val present: String?,
    val absent: String?,
    val numbers: List<Int>,
) {
    internal companion object {
        fun decode(bytes: ByteArray): SmokeRecord = with(BinaryReader(bytes)) {
            SmokeRecord(
                flag = bool(),
                byte = u8(),
                int32 = i32(),
                int64 = i64(),
                real = f64(),
                text = string(),
                present = optString(),
                absent = optString(),
                numbers = seq { it.i32() },
            )
        }
    }
}

/**
 * Entry point to the iroh4k native core.
 *
 * Backed by one Rust crate reached two ways: Kotlin/Native targets link it statically and call
 * it over the C ABI, while the JVM and Android load it as a shared library over JNI. Both paths
 * share the same Rust core, so behaviour is identical.
 *
 * At M0 this exposes only what the smoke tests need; the endpoint/QUIC surface lands in M3–M5.
 */
object Iroh4k {
    /** `"<iroh4k version>+iroh<iroh version>"`, e.g. `"0.1.0+iroh1.0.3"`. */
    val version: String get() = nativeVersion()

    /**
     * Round-trips [value] through the Rust tokio runtime.
     *
     * Exercises the async bridge end to end — on native that means a `StableRef` continuation
     * resumed from a `staticCFunction` on a tokio worker thread; on the JVM a blocking call
     * dispatched to `Dispatchers.IO`.
     *
     * Kept, though the endpoint and stream operations now exercise the same bridge: this is the
     * only test of it in isolation, so a bridge regression fails here rather than as a confusing
     * symptom three domains away.
     */
    internal suspend fun smokeEcho(value: Long): Long = nativeSmokeAsyncEcho(value)

    /**
     * Always fails with [IrohError.Code.InvalidArgument], covering error propagation and the
     * ordinal decoding of [IrohError.Code]. Kept for the reason given on [smokeEcho].
     */
    internal suspend fun smokeError(): Nothing = nativeSmokeAsyncError()

    /**
     * Sleeps in Rust for [millis], then returns it.
     *
     * Stands in for iroh's genuinely long-running operations so cancellation can be exercised:
     * cancelling the calling coroutine aborts the Rust task instead of leaving it running.
     * Kept for the reason given on [smokeEcho].
     */
    internal suspend fun smokeSleep(millis: Long): Long = nativeSmokeAsyncSleep(millis)

    /** Decodes a [SmokeRecord] encoded by Rust, verifying the codec across languages. */
    internal fun smokeRecord(): SmokeRecord = SmokeRecord.decode(nativeSmokeRecord())

    /**
     * Operations still registered in the Rust op registry.
     *
     * Exposed for tests: it must return to its baseline after operations complete or are
     * cancelled, which is how a registry leak would be caught.
     */
    internal val liveOpCount: Long get() = nativeLiveOpCount()

    /**
     * How many [smokeSleep] calls ran past their sleep in Rust.
     *
     * Exposed for tests: after cancelling a sleep this must not increase even once the sleep's
     * duration has elapsed, which is what distinguishes a genuinely aborted tokio task from one
     * still running whose result was merely discarded.
     */
    internal val smokeSleepCompletions: Long get() = nativeSmokeSleepCompletions()
}

internal expect fun nativeVersion(): String

internal expect fun nativeSmokeRecord(): ByteArray

internal expect fun nativeLiveOpCount(): Long

internal expect fun nativeSmokeSleepCompletions(): Long

internal expect suspend fun nativeSmokeAsyncEcho(value: Long): Long

internal expect suspend fun nativeSmokeAsyncError(): Nothing

internal expect suspend fun nativeSmokeAsyncSleep(millis: Long): Long
