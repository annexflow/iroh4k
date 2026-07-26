package tech.annexflow.iroh4k

/**
 * Raw JNI entry points into the Rust shared library.
 *
 * Synchronous calls return their result directly. Asynchronous operations are split into
 * **start / await / cancel**: `*Start` returns an operation id immediately, [opAwait] blocks
 * until the operation finishes, and [opCancel] aborts it. The split exists so cancellation is
 * possible at all — a single blocking call would pin a `Dispatchers.IO` thread with nothing able
 * to interrupt it, and for an operation like `accept` that means forever.
 *
 * The symbol names here are part of the ABI: they must match the
 * `Java_tech_annexflow_iroh4k_Iroh4kJni_*` exports in the Rust `jni.rs`.
 */
internal object Iroh4kJni {
    init {
        ensureNativeLoaded()
    }

    // ── Operation control ────────────────────────────────────────────────────────────────────

    /** Blocks the calling thread until the operation completes or is cancelled. */
    external fun opAwait(op: Long): ByteArray

    /** Aborts a pending operation; a no-op if it already completed. */
    external fun opCancel(op: Long)

    external fun liveOpCount(): Long

    external fun smokeSleepCompletions(): Long

    // ── Synchronous ──────────────────────────────────────────────────────────────────────────

    external fun version(): ByteArray

    external fun smokeRecord(): ByteArray

    // ── Asynchronous — each returns an operation id ───────────────────────────────────────────

    external fun smokeAsyncEchoStart(value: Long): Long

    external fun smokeAsyncErrorStart(): Long

    external fun smokeAsyncSleepStart(millis: Long): Long
}

/**
 * Loads the `iroh4k` native library exactly once. The JVM extracts it from a jar resource (or a
 * Gradle-provided path under test) and `System.load`s it; Android resolves the bundled `.so`
 * via `System.loadLibrary`.
 */
internal expect fun ensureNativeLoaded()
