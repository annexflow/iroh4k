package tech.annexflow.iroh4k

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope


/**
 * Runs an asynchronous Rust operation, propagating coroutine cancellation into it.
 *
 * [start] must return an operation id. The blocking await runs on [Dispatchers.IO] inside an
 * `async` so that cancellation can be observed *while* the thread is still blocked: on
 * cancellation `opCancel` aborts the Rust task, which makes the awaiting thread's channel yield
 * `ERROR_CANCELLED` and return. Awaiting directly with `withContext` would deadlock — it waits
 * for its block to finish, and the block cannot be interrupted.
 */
private suspend fun <T> jniOp(start: () -> Long, decode: (JniResult) -> T): T {
    val op = start()
    if (op <= 0L) IrohError(IrohError.Code.Unknown, "failed to start native operation").raise()
    return coroutineScope {
        val pending = async(Dispatchers.IO) { Iroh4kJni.opAwait(op).decodeResult() }
        val result = try {
            pending.await()
        } catch (e: CancellationException) {
            // Unblocks the IO thread sitting in opAwait; without this it would leak until the
            // operation finished on its own.
            Iroh4kJni.opCancel(op)
            throw e
        }
        result.throwIfError()
        decode(result)
    }
}


internal actual fun nativeVersion(): String =
    Iroh4kJni.version().jniBytesOrThrow().decodeToString()

internal actual fun nativeSmokeRecord(): ByteArray =
    Iroh4kJni.smokeRecord().jniBytesOrThrow()

internal actual fun nativeLiveOpCount(): Long = Iroh4kJni.liveOpCount()

internal actual fun nativeSmokeSleepCompletions(): Long = Iroh4kJni.smokeSleepCompletions()

internal actual suspend fun nativeSmokeAsyncEcho(value: Long): Long =
    jniOp({ Iroh4kJni.smokeAsyncEchoStart(value) }) { it.longValue }

internal actual suspend fun nativeSmokeAsyncError(): Nothing {
    jniOp({ Iroh4kJni.smokeAsyncErrorStart() }) { }
    error("smokeAsyncError must always fail")
}

internal actual suspend fun nativeSmokeAsyncSleep(millis: Long): Long =
    jniOp({ Iroh4kJni.smokeAsyncSleepStart(millis) }) { it.longValue }
