@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.Iroh4kPtr
import iroh4k.ffi.Iroh4kResult
import iroh4k.ffi.iroh4k_free_result
import iroh4k.ffi.iroh4k_op_cancel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.cinterop.COpaquePointer
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
 *
 * The null check is on the receiver alone, never on what [block] returns. [bytesOrNull] legitimately
 * answers `null` from a perfectly valid, non-null result — that is the whole point of it — so folding
 * the two into one `this?.pointed?.let(block) ?: throw …` chain would raise "cannot dereference null"
 * on a live result purely because its own answer happened to be `null`, which is exactly the "absent"
 * case [bytesOrNull] exists to report.
 */
private inline fun <T> CPointer<Iroh4kResult>?.use(block: (Iroh4kResult) -> T): T {
    val pointer = this
        ?: throw IllegalStateException("Invalid Iroh4kResult pointer: cannot dereference null")
    try {
        return block(pointer.pointed)
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
 * The `bytes` payload, or `null` when Rust sent none.
 *
 * [bytesOrThrow] collapses that distinction to an empty array, which is right for every payload that
 * was never optional in the first place. The connection domain's 0-RTT `alpn`/`remoteId` need the
 * distinction kept: cinterop reads the `Iroh4kResult` struct's `bytes` field directly rather than
 * through the length-prefixed wire format `core::serialize_result` writes for JNI, and `bytes_result`
 * always leaks a non-null pointer — even for an empty payload, `Box::into_raw` on an empty boxed slice
 * is still non-null — so a null pointer here can only be the absent case, never a genuinely empty one.
 *
 * `bytes_len` still gets [bytesOrThrow]'s own floor of zero rather than being trusted as-is once the
 * pointer is non-null. Nothing on the Rust side sends a negative length today — `core.rs:117` derives
 * it from `bytes.len()` — but this file's whole job is not to trust the boundary, and a raw
 * `readBytes(negative)` would throw `NegativeArraySizeException` instead of the `IrohError` every
 * other malformed result raises here.
 */
internal fun CPointer<Iroh4kResult>?.bytesOrNull(): ByteArray? = use { result ->
    result.throwIfError()
    val len = result.bytes_len
    result.bytes?.let { ptr -> if (len <= 0) ByteArray(0) else ptr.readBytes(len) }
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
    noinline freeHandle: ((COpaquePointer) -> Unit)? = null,
    crossinline operation: (continuationPtr: CPointer<out CPointed>) -> Long
): CPointer<Iroh4kResult>? = suspendCancellableCoroutine { continuation ->
    val stableRef = StableRef.create(PendingOp(continuation, freeHandle))
    val op = operation(stableRef.asCPointer())
    // Registered after starting; if the coroutine is already cancelled the handler runs
    // immediately, which is exactly the desired abort.
    continuation.invokeOnCancellation { iroh4k_op_cancel(op) }
}

/**
 * What an in-flight operation has to remember, pinned for Rust to hand back.
 *
 * [staticCFunction] cannot capture, so everything the completion callback needs travels through
 * the [StableRef] instead — including how to release an object the result carried.
 */
internal class PendingOp(
    val continuation: CancellableContinuation<CPointer<Iroh4kResult>?>,
    /**
     * Releases the result's `handle` if the continuation discards it. `null` for operations that
     * do not produce an object.
     */
    val freeHandle: ((COpaquePointer) -> Unit)?,
)

/**
 * The completion callback handed to every async FFI function.
 *
 * Disposes the [StableRef] — without which every operation would leak a pinned continuation —
 * and resumes.
 *
 * The `onCancellation` arm handles the case where the operation *succeeded* but the continuation
 * was cancelled before it could be resumed, so the value is dropped on the floor. Rust hands
 * ownership of the whole result to Kotlin, which means freeing the envelope is not enough: if the
 * result carried a live object — a connection, an incoming, a stream — that object has to be
 * released too, or every cancelled-just-too-late operation strands one. `Iroh4kResult` carries no
 * destructor (Kotlin never needs one on the happy path), so the producing call site supplies it
 * via [PendingOp.freeHandle].
 *
 * The sibling case — a cancel *beating* the completion — is handled in Rust by
 * `ops::OpResult::discard`.
 */
internal val completion =
    staticCFunction<CValue<Iroh4kPtr>, CPointer<Iroh4kResult>?, Unit> { ptr, result ->
        val ref = ptr.useContents { this.ptr }!!.asStableRef<PendingOp>()
        val pending = ref.get()
        ref.dispose()
        pending.continuation.resume(result) { _, discarded, _ ->
            // Object first, then the envelope that pointed at it.
            discarded?.pointed?.handle?.let { handle -> pending.freeHandle?.invoke(handle) }
            iroh4k_free_result(discarded)
        }
    }
