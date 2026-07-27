package tech.annexflow.iroh4k.internal

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import tech.annexflow.iroh4k.IrohError

/**
 * Guards an opaque Rust handle so it is freed exactly once and never used after that.
 *
 * Every handle-owning Kotlin type — endpoint, incoming, accepting, connecting, connection, services
 * client, and the streams to come — shares this one implementation. That is deliberate: the
 * invariant below is subtle enough that a second copy would eventually drift from it, and the
 * failure mode is a use-after-free on a pointer rather than an exception.
 *
 * ### The invariant
 *
 * A single [AtomicLong] holds the closed flag in bit 63 and the number of in-flight callers in bits
 * 0..62:
 *
 * - [acquire] increments the count, but only while the closed bit is clear, so a caller can never
 *   enter after a close has been observed.
 * - [close] sets the bit, and frees only if the count is already zero.
 * - [release] decrements, and frees if it was the last caller *and* the bit is set.
 *
 * So exactly one of `close` and the final `release` sees the state reach "closed with no callers",
 * and that one frees. Both orderings are covered, and no lock is held across a suspension point —
 * which matters because [suspending] lends the handle to an operation that may park for a long time
 * (an `accept`, an `online`, a stream read).
 *
 * @param handle the raw handle, as produced by Rust.
 * @param what names the owner in error messages, e.g. `"endpoint"`.
 * @param free releases the handle. Called at most once.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class NativeHandle(
    private val handle: Long,
    private val what: String,
    private val free: (Long) -> Unit,
) {
    private val state = AtomicLong(0L)

    /** True once [close] has run, whether or not the handle has been freed yet. */
    val isReleased: Boolean get() = state.load() and CLOSED != 0L

    /**
     * Registers a use of the handle and returns it.
     *
     * @throws IrohError with [IrohError.Code.Closed] if the owner has been released.
     */
    fun acquire(): Long {
        while (true) {
            val current = state.load()
            if (current and CLOSED != 0L) {
                IrohError(
                    IrohError.Code.Closed,
                    "this $what has been closed and cannot be used",
                ).raise()
            }
            if (state.compareAndSet(current, current + 1)) return handle
        }
    }

    /** Ends a use registered by [acquire], freeing the handle if a [close] is waiting on it. */
    fun release() {
        while (true) {
            val current = state.load()
            val next = current - 1
            if (state.compareAndSet(current, next)) {
                if (next == CLOSED) free(handle)
                return
            }
        }
    }

    /** Marks the handle closed, freeing it as soon as no call is inside it. Idempotent. */
    fun close() {
        while (true) {
            val current = state.load()
            if (current and CLOSED != 0L) return
            if (state.compareAndSet(current, current or CLOSED)) {
                if (current == 0L) free(handle)
                return
            }
        }
    }

    /** Runs a synchronous native call against a handle that cannot be freed underneath it. */
    fun <T> use(block: (Long) -> T): T {
        val raw = acquire()
        try {
            return block(raw)
        } finally {
            release()
        }
    }

    /**
     * Lends the handle to a suspending operation.
     *
     * The guard, not the caller, decides when the handle may be touched — so a `close()` racing a
     * long-running operation still reports [IrohError.Code.Closed] instead of reaching a freed
     * pointer, and the handle survives until the operation actually finishes.
     */
    suspend fun <T> useSuspending(block: suspend (Long) -> T): T {
        val raw = acquire()
        try {
            return block(raw)
        } finally {
            release()
        }
    }

    private companion object {
        /** The closed bit. Chosen as the sign bit so the user count stays a plain increment. */
        const val CLOSED: Long = 1L shl 63
    }
}
