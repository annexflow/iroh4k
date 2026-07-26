@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.Iroh4kPtr
import iroh4k.ffi.Iroh4kResult
import iroh4k.ffi.iroh4k_free_result
import iroh4k.ffi.iroh4k_op_cancel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents

private fun Iroh4kResult.isError(): Boolean = error != IrohError.OK

private fun Iroh4kResult.toError(): IrohError =
    IrohError(IrohError.Code.of(error), error_message?.toKString())

private fun Iroh4kResult.throwIfError() {
    if (isError()) toError().raise()
}

/**
 * Reads the result, then frees it — always, even if [block] throws. Kotlin owns every result
 * pointer handed to it and `iroh4k_free_result` is the only way to release it.
 */
private inline fun <T> CPointer<Iroh4kResult>?.use(block: (Iroh4kResult) -> T): T {
    try {
        return this?.pointed?.let(block)
            ?: throw IllegalStateException("Invalid Iroh4kResult pointer: cannot dereference null")
    } finally {
        iroh4k_free_result(this)
    }
}

internal fun CPointer<Iroh4kResult>?.orThrow(): Unit = use { it.throwIfError() }

internal fun CPointer<Iroh4kResult>?.longOrThrow(): Long = use {
    it.throwIfError()
    it.i64_val
}

internal fun CPointer<Iroh4kResult>?.bytesOrThrow(): ByteArray = use {
    it.throwIfError()
    val len = it.bytes_len
    if (len <= 0) ByteArray(0) else it.bytes?.readBytes(len) ?: ByteArray(0)
}

/**
 * Suspends until the Rust side invokes the completion callback, propagating cancellation.
 *
 * The `Continuation` is pinned with a [StableRef] so the Kotlin GC cannot move or collect it
 * while Rust holds the raw pointer, and [operation] passes that pointer to an FFI function which
 * resumes it later from a tokio worker thread. All state travels through the ref because
 * [staticCFunction] cannot capture.
 *
 * [operation] returns the Rust operation id, which is handed to `iroh4k_op_cancel` if the
 * coroutine is cancelled. That matters for iroh: `accept` and stream reads block indefinitely,
 * so without it a cancelled coroutine would leave the Rust task running forever. Rust guarantees
 * the callback fires exactly once — either with the real result or with `ERROR_CANCELLED` — so
 * cancelling concurrently with completion cannot resume twice.
 */
internal suspend inline fun iroh(
    crossinline operation: (continuationPtr: CPointer<out CPointed>) -> Long
): CPointer<Iroh4kResult>? = suspendCancellableCoroutine { continuation ->
    val stableRef = StableRef.create(continuation)
    val op = operation(stableRef.asCPointer())
    // Registered after starting; if the coroutine is already cancelled the handler runs
    // immediately, which is exactly the desired abort.
    continuation.invokeOnCancellation { iroh4k_op_cancel(op) }
}

/**
 * The completion callback handed to every async FFI function.
 *
 * Disposes the [StableRef] — without which every operation would leak a pinned continuation —
 * and resumes. The `onCancellation` arm frees the result when the continuation has already been
 * cancelled and therefore discards the value: Rust hands ownership of every result to Kotlin, so
 * a discarded one still has to be freed.
 */
internal val completion =
    staticCFunction<CValue<Iroh4kPtr>, CPointer<Iroh4kResult>?, Unit> { ptr, result ->
        val ref = ptr.useContents { this.ptr }!!
            .asStableRef<CancellableContinuation<CPointer<Iroh4kResult>?>>()
        val continuation = ref.get()
        ref.dispose()
        continuation.resume(result) { _, discarded, _ -> iroh4k_free_result(discarded) }
    }
