package tech.annexflow.iroh4k

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tech.annexflow.iroh4k.internal.BinaryReader

/**
 * A decoded JNI result envelope — the single decoder for the layout Rust's
 * `core::serialize_result` writes.
 *
 * The native facade reads the `Iroh4kResult` struct fields directly through cinterop; JNI has no
 * struct access, so Rust flattens the same fields into a byte buffer and they are parsed here.
 * The [bytes] payload inside is the shared `commonMain` codec format, decoded by [BinaryReader].
 *
 * Deliberately shared by every domain (`Iroh4k`, `Keys`, `Relay`, …) rather than re-implemented
 * per file. One Rust writer must have exactly one Kotlin reader: a second copy would keep working
 * right up until the envelope gained a field, and then disagree silently on every operation in
 * whichever domains had not been updated.
 */
internal class JniResult(buffer: ByteArray) {
    val error: Int
    val errorMessage: String?
    val handle: Long
    val longValue: Long
    val doubleValue: Double
    val bytes: ByteArray?

    init {
        val r = BinaryReader(buffer)
        error = r.i32()
        errorMessage = r.optString()
        handle = r.i64()
        longValue = r.i64()
        doubleValue = r.f64()
        bytes = r.optBytes()
    }

    /**
     * Raises the Rust-reported failure, if any.
     *
     * @param code the [IrohError.Code] to report when Rust did not supply a more specific one.
     *   Rust always sends a code, so this is only a floor for an unrecognised value.
     */
    fun throwIfError() {
        if (error != IrohError.OK) IrohError(IrohError.Code.of(error), errorMessage).raise()
    }
}

internal fun ByteArray.decodeResult(): JniResult = JniResult(this)

/** Decodes the envelope and raises on failure, discarding any payload. */
internal fun ByteArray.jniOrThrow() {
    decodeResult().throwIfError()
}

/** The codec payload, or an empty array when Rust sent none. */
internal fun ByteArray.jniBytesOrThrow(): ByteArray = decodeResult().let {
    it.throwIfError()
    it.bytes ?: ByteArray(0)
}

/**
 * The codec payload, or `null` when Rust sent none.
 *
 * [jniBytesOrThrow] and [payload] both collapse that case to an empty array, which is right for a
 * payload that was never optional. The connection domain's 0-RTT `alpn`/`remoteId` need the
 * distinction kept — [JniResult.bytes] is already `ByteArray?`, decoded from `serialize_result`'s `i32
 * -1` for a null `bytes` pointer (`core.rs:184`), so this is the one reader that answers with it
 * rather than flattening it away.
 */
internal fun ByteArray.jniBytesOrNull(): ByteArray? = decodeResult().let {
    it.throwIfError()
    it.bytes
}

/** The `i64_val` field. */
internal fun ByteArray.jniLongOrThrow(): Long = decodeResult().let {
    it.throwIfError()
    it.longValue
}

/** The codec payload of a completed operation, or an empty array when Rust sent none. */
internal fun JniResult.payload(): ByteArray = bytes ?: ByteArray(0)

/**
 * Runs an asynchronous Rust operation, propagating coroutine cancellation into it.
 *
 * [start] must return an operation id. The blocking await runs on [Dispatchers.IO] inside an
 * `async` so cancellation can be observed *while* the thread is still blocked: on cancellation
 * `opCancel` aborts the Rust task, which makes the awaiting thread's channel yield
 * `ERROR_CANCELLED` and return.
 *
 * Awaiting directly with `withContext` would **deadlock** — it waits for its block to finish, the
 * block is parked in an uninterruptible native call, and the cancel that would release it never
 * runs. That subtlety is why this lives in one shared place rather than being written per domain:
 * an `online()` or an `accept()` that got it wrong would hang forever.
 */
internal suspend fun <T> jniOp(
    start: () -> Long,
    freeHandle: ((Long) -> Unit)? = null,
    decode: (JniResult) -> T,
): T {
    val op = start()
    if (op <= 0L) IrohError(IrohError.Code.Unknown, "failed to start native operation").raise()
    return coroutineScope {
        val pending = async(Dispatchers.IO) { Iroh4kJni.opAwait(op).decodeResult() }
        val result = try {
            pending.await()
        } catch (cancellation: CancellationException) {
            // Unblocks the IO thread sitting in opAwait; without this it would leak until the
            // operation finished on its own — which, for `online()` without a relay, is never.
            Iroh4kJni.opCancel(op)
            // `await` can throw cancellation while the operation itself *succeeded*, so a result
            // may still arrive that no caller will ever receive. If it carries an object, release
            // it here — the sibling case, a cancel beating the operation, is handled in Rust by
            // `ops::OpResult::discard`.
            //
            // Registered only on this path on purpose: a handler attached before the `await` would
            // race it and could free a handle the caller is about to be given.
            //
            // Note this handler is not the only safety net. If the scope is cancelled before the
            // `async` body ever dispatches, `opAwait` is never entered at all — so `ops::cancel_op`
            // also drops the registry entry itself, and a result left queued in a channel whose
            // receiver dies is freed by `OpResult`'s `Drop`. All three are needed: this one covers
            // "completed but nobody took it", the other two cover "never awaited at all".
            if (freeHandle != null) {
                pending.invokeOnCompletion { cause ->
                    if (cause == null) {
                        @Suppress("OPT_IN_USAGE")
                        val produced = pending.getCompleted()
                        if (produced.handle != 0L) freeHandle(produced.handle)
                    }
                }
            }
            throw cancellation
        }
        result.throwIfError()
        decode(result)
    }
}
