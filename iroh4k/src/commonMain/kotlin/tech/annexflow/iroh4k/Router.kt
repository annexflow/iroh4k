package tech.annexflow.iroh4k

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * The [Router]: an accept loop that dispatches each inbound connection to the [ProtocolHandler]
 * registered for its ALPN.
 *
 * See `iroh::protocol` for the original. The vocabulary is deliberately the same —
 * `Router.builder(endpoint).accept(alpn, handler).spawn()`, [Router.endpoint], [Router.isShutdown],
 * [Router.shutdown] — so that anyone who knows iroh recognises this immediately.
 *
 * ## Why there is no Rust behind this file
 *
 * This is the one domain of iroh4k with **no FFI surface at all**: no `router.rs`, no `expect fun`,
 * nothing added to the C ABI or to the JNI symbol table. A router is an accept loop with a lookup
 * table, and [Endpoint.acceptNext] already hands Kotlin every inbound connection, so the loop can
 * simply live here.
 *
 * That is a decision, not an accident. iroh-ffi models `ProtocolHandler` as a UniFFI *foreign trait*
 * (`#[uniffi::export(with_foreign)]`), which means Rust calls back **into** the host language: on the
 * JNI side that requires a `JNI_OnLoad`, a cached `JavaVM` and an `AttachCurrentThread` on every
 * tokio worker thread that happens to run a handler, plus the matching lifetime dance on the
 * Kotlin/Native side. Hand-written, reverse callbacks are the riskiest thing in a binding like this
 * one — and for a router they buy nothing, because the loop they exist to run is fifty lines of
 * Kotlin. Keeping it here means one implementation, identical semantics on every target, and no
 * `JNI_OnLoad` anywhere in the project.
 *
 * ## Structured concurrency
 *
 * The router owns a [CoroutineScope] rather than borrowing the caller's, because [spawn] is not a
 * `suspend` function — it mirrors iroh's `RouterBuilder::spawn`, which is an ordinary call that
 * returns a running router. Everything the router ever does runs under one [SupervisorJob]: the
 * accept loop, and one child coroutine per accepted connection. So [shutdown] is a single
 * `cancelAndJoin` on that job, which cancels the loop *and* every handler still in flight and waits
 * for all of them — there is no coroutine left when it returns.
 *
 * ## What the router does not do
 *
 * It does not close [endpoint]. iroh's own router does (its `shutdown` ends with
 * `endpoint.close().await`), and it can afford to: a Rust `Endpoint` is an `Arc` handle that the
 * builder clones, so closing it there is closing a shared object every other holder still sees. Here
 * an [Endpoint] is a Kotlin object wrapping one native handle with one owner, and the router is
 * handed that object rather than a clone of it — so a router that closed the endpoint would release
 * something it does not own and break the caller's `use { }` block from underneath. The endpoint is
 * therefore left exactly as it was found: still bound, still queryable, and still the caller's to
 * [Endpoint.shutdown] and [Endpoint.close]. A graceful exit is `router.shutdown()` then
 * `endpoint.shutdown()`, in that order.
 */

// ── The handler ───────────────────────────────────────────────────────────────────────────────

/**
 * Handles inbound connections for one ALPN protocol, registered with [RouterBuilder.accept].
 *
 * A `fun interface`, so the common case is a lambda:
 *
 * ```kotlin
 * val router = Router.builder(endpoint)
 *     .accept("my-alpn".encodeToByteArray()) { connection ->
 *         println("serving ${connection.remoteId()}")
 *     }
 *     .spawn()
 * ```
 *
 * ## The router owns the connection
 *
 * [accept] is *lent* its [Connection] for exactly as long as it runs. The router releases the
 * handle the moment [accept] returns — normally, by throwing, or by being cancelled — which is
 * iroh's own contract ("once `accept()` returns, the connection is dropped"). So:
 *
 * - Do all the work inside [accept]. It runs on its own coroutine and may be long-lived; the accept
 *   loop is never waiting for it.
 * - Do **not** retain the [Connection] past the end of [accept], and do not close it yourself
 *   unless you mean iroh's [Connection.close(errorCode, reason)][Connection.close] — that one tells
 *   the peer why, and is worth calling explicitly for a protocol error, because a plain release
 *   closes with error code `0` and says nothing.
 *
 * A handler that throws does not disturb the router: the failure is reported to
 * [RouterBuilder.onFailure], that one connection is closed, and the loop carries on accepting.
 */
fun interface ProtocolHandler {

    /**
     * Serves [connection] until this protocol is done with it.
     *
     * Runs on a coroutine of the router's own scope, so [shutdown] cancels it. Cancellation must be
     * allowed to propagate — swallowing [CancellationException] here is what would make a shutdown
     * hang.
     */
    suspend fun accept(connection: Connection)
}

// ── The router ────────────────────────────────────────────────────────────────────────────────

/**
 * A running accept loop, dispatching by ALPN. Build one with [Router.builder].
 *
 * **Thread-safe**, as everything else in iroh4k is: [shutdown], [close] and [isShutdown] may be
 * called from any thread or coroutine, concurrently, and repeatedly.
 *
 * Holds coroutines rather than a native handle, which is why [close] is cheap and non-suspending —
 * but also why it is the abrupt ending: it cancels without waiting. [shutdown] is the graceful one.
 * The router does not close its [endpoint]; see the file header for why.
 */
class Router internal constructor(

    /**
     * The endpoint this router accepts on — the one that was handed to [Router.builder].
     *
     * The same object, not a copy: closing it closes what the router is accepting from, which ends
     * the accept loop (see [shutdown]).
     */
    val endpoint: Endpoint,

    /**
     * The routing table, keyed by ALPN.
     *
     * A `List<Byte>` rather than a `ByteArray` because arrays hash and compare by *identity* in
     * Kotlin, so a `Map<ByteArray, _>` would never find anything the FFI just handed back. The list
     * is also an immutable copy of the caller's bytes, which is what keeps the table stable.
     */
    private val protocols: Map<List<Byte>, ProtocolHandler>,

    /** Where failures go — see [RouterBuilder.onFailure]. */
    private val onFailure: (alpn: ByteArray?, failure: Throwable) -> Unit,
) : AutoCloseable {

    /**
     * The job every one of this router's coroutines runs under: the accept loop, and one child per
     * accepted connection.
     *
     * A *supervisor*, because a connection is not the router: a handler that fails must not cancel
     * the loop or its sibling connections. Failures are caught per connection anyway (see [serve]),
     * so this is the second line of defence rather than the first.
     *
     * `internal` so tests can assert the thing that is otherwise invisible — that after [shutdown]
     * this job is complete and has no children left.
     */
    internal val supervisor: Job = SupervisorJob()

    private val scope = CoroutineScope(
        supervisor +
                Dispatchers.Default +
                CoroutineName("iroh4k-router") +
                // Nothing should reach this: [serve] catches everything, and a supervisor would not
                // cancel its siblings even if something did. It is here so that a failure which
                // somehow escapes is *reported* rather than handed to the platform's default handler,
                // which on Kotlin/Native means the process.
                CoroutineExceptionHandler { _, failure -> report(null, failure) }
    )

    /**
     * Whether this router has been shut down, by [shutdown], by [close], or by its accept loop
     * ending on its own because [endpoint] was shut down.
     *
     * Becomes `true` as soon as the shutdown is *requested*, exactly as iroh's `is_shutdown` reads a
     * cancellation token rather than waiting for the tasks — so it can be `true` for the moment it
     * takes in-flight handlers to unwind. [shutdown] is what waits for them.
     */
    val isShutdown: Boolean get() = supervisor.isCancelled

    /**
     * Stops accepting, cancels every handler still running, and waits for all of them.
     *
     * When this returns, the router holds no live coroutine: the accept loop has been cancelled — in
     * Kotlin *and* in Rust, since cancelling a suspended [Endpoint.acceptNext] aborts its tokio task
     * — and every in-flight [ProtocolHandler.accept] has unwound, closing its connection on the way
     * out.
     *
     * Idempotent, and safe to call concurrently with itself: the second caller finds a job that is
     * already cancelled and simply waits for the same completion. Unlike iroh's, this does **not**
     * close [endpoint] — see the file header.
     */
    suspend fun shutdown() {
        supervisor.cancelAndJoin()
    }

    /**
     * Cancels the accept loop and every handler **without waiting** for them.
     *
     * The [AutoCloseable] ending, for `use { }` and for a caller with nothing to await on — iroh's
     * router aborts its tasks when dropped in exactly the same way. Idempotent.
     *
     * Prefer [shutdown]: handlers cancelled by this call are still unwinding when it returns, so a
     * connection they were serving may not have been released yet.
     */
    override fun close() {
        supervisor.cancel()
    }

    override fun toString(): String {
        val state = if (isShutdown) "shutdown" else "accepting"
        return "Router($state, ${protocols.size} protocols)"
    }

    // ── The accept loop ──────────────────────────────────────────────────────────────────────

    /** Starts the loop. Called exactly once, by [RouterBuilder.spawn], on a fully built router. */
    internal fun start() {
        scope.launch { acceptLoop() }
    }

    /**
     * Takes inbound connections one at a time and hands each to its own coroutine.
     *
     * The loop itself never touches a handler, and never awaits one: a protocol that blocks for an
     * hour delays nothing but itself.
     */
    private suspend fun acceptLoop() {
        try {
            while (true) {
                val incoming = try {
                    // `null` is iroh's answer for a shut-down endpoint, not a failure, so this ends
                    // the loop cleanly rather than spinning on an endpoint that will never accept
                    // again.
                    endpoint.acceptNext() ?: break
                } catch (failure: IrohError) {
                    // `Closed`: the endpoint's handle was released under us, which is the only
                    // failure `acceptNext` reports. There is nothing left to accept from, so this
                    // ends the loop too — but it is reported, because a released endpoint is a
                    // lifecycle bug in the caller rather than a graceful ending.
                    report(null, failure)
                    break
                }

                // One coroutine per connection, so a handler that takes an hour delays nothing. The
                // child is always attached to `supervisor`, and therefore always waited for by
                // `shutdown`: this loop is itself a live child at this instant, so the job cannot
                // already have finished cancelling — the one state that would refuse a new child.
                val serving = scope.launch { serve(incoming) }

                // The handle is released whatever becomes of that coroutine, including the case a
                // `use { }` inside it cannot cover: a scope cancelled between the accept above and
                // the dispatch below means the body *never runs at all*, and `incoming` would then
                // be stranded in Rust for the life of the process. Releasing from the completion
                // handler covers every ending; `close()` is idempotent and the guard frees exactly
                // once, so the `use { }` in `serve` remains the prompt path and this the backstop.
                //
                // Note this is why the coroutine is not started ATOMIC, which would also guarantee
                // the body runs: a body that begins *already cancelled* would go straight into the
                // accept chain's suspending FFI calls, and starting a native operation from a
                // cancelled coroutine is exactly the shape that leaves an operation registered in
                // Rust with nobody left to await it. Not starting is cleaner than starting doomed.
                serving.invokeOnCompletion { incoming.close() }
            }
        } finally {
            // iroh's `_cancel_guard`: however the loop ended, the router is shut down. That makes
            // [isShutdown] honest about an endpoint that was closed under the router, and cancels
            // any handler still running — the same order iroh's own shutdown uses.
            supervisor.cancel()
        }
    }

    /**
     * Answers one inbound connection: complete the handshake, look up the ALPN, run the handler.
     *
     * Every native handle in the chain is released on the way out, by `use { }` rather than by hand,
     * because this coroutine can be cancelled at any of its suspension points — and the nesting is
     * what makes that safe: a cancellation landing between [Incoming.accept] and [Accepting.connect]
     * would otherwise leave an `Accepting`, whose value has already been taken from the `Incoming`,
     * with nothing left to release it. The one ending `use { }` cannot cover — this body never
     * running at all — is covered by the completion handler in [acceptLoop].
     */
    private suspend fun serve(incoming: Incoming) {
        var alpn: ByteArray? = null
        try {
            // The handshake happens under the two handles that produce it, and both are released
            // before the handler is called: an `Incoming` and an `Accepting` are spent the moment a
            // `Connection` exists, and a connection that lives for hours should not pin two more
            // native handles for all of it.
            val routed = incoming.use { pending ->
                pending.accept().use { accepting ->
                    // Read before completing the handshake, which is what `Accepting.alpn` is for:
                    // the routing decision is made on the negotiated protocol, not on a connection
                    // that has to exist first.
                    val negotiated = accepting.alpn()
                    alpn = negotiated
                    val handler = protocols[negotiated.toList()]
                    if (handler == null) {
                        // Reported first, so the sink hears about the routing failure even if the
                        // refusal below fails on a peer that has already gone away.
                        report(
                            negotiated,
                            IrohError(
                                IrohError.Code.Accept,
                                "no protocol handler is registered for the negotiated ALPN",
                            ),
                        )
                        refuse(accepting)
                        null
                    } else {
                        handler to accepting.connect()
                    }
                }
            }

            // Nothing suspends between the connection appearing above and the `use` below — the
            // `close`es that unwind the handshake do not — so there is no window in which a
            // cancellation could leave this connection with nothing to release it. The router owns
            // it for exactly the duration of the handler; see [ProtocolHandler].
            if (routed != null) {
                val (handler, connection) = routed
                connection.use { handler.accept(it) }
            }
        } catch (cancellation: CancellationException) {
            // A shutdown, not a failure. Rethrown so this coroutine really completes as cancelled
            // and `shutdown()` is not left waiting on it.
            throw cancellation
        } catch (failure: Throwable) {
            // Everything else stops here, at the connection it belongs to: a failed handshake, an
            // ALPN nobody claimed, a handler that threw. The loop is a sibling coroutine under a
            // supervisor, so it never sees this and keeps accepting.
            report(alpn, failure)
        }
    }

    /**
     * Turns away a handshake whose ALPN nothing is registered for, telling the peer why.
     *
     * This is where iroh4k improves on upstream rather than copying it. iroh's router simply *drops*
     * the `Accepting` — but by the time either router has read the negotiated ALPN, the dialler's own
     * `connect` may already have completed, and a dropped handshake then reaches it as a connection
     * that closes with error code `0` and no explanation. So the refusal is made explicit here: the
     * handshake is completed and the connection immediately closed with
     * [UNSUPPORTED_ALPN_ERROR_CODE] and a reason the peer can read, which is the difference between
     * "the connection died" and "that protocol is not served here".
     *
     * Reached only when the endpoint advertises more ALPNs than the router routes:
     * [RouterBuilder.spawn] sets the endpoint's list to exactly the registered ones, so iroh itself
     * turns an unknown protocol away — with a real QUIC `CONNECTION_REFUSED`, before it ever becomes
     * an [Incoming] — unless [Endpoint.setAlpns] widened the list afterwards. This is the backstop for
     * that case, and the one thing it must not be is silent.
     */
    private suspend fun refuse(accepting: Accepting) {
        accepting.connect().use { connection ->
            connection.close(UNSUPPORTED_ALPN_ERROR_CODE, UNSUPPORTED_ALPN_REASON)
        }
    }

    /** Hands [failure] to the sink, which is not allowed to take the router down with it. */
    private fun report(alpn: ByteArray?, failure: Throwable) {
        try {
            onFailure(alpn, failure)
        } catch (sinkFailure: Throwable) {
            // Swallowed deliberately: this is the failure reporter, so there is nowhere left to
            // report to, and rethrowing would turn a broken log line into a lost connection.
        }
    }

    companion object {
        /** Starts building a router that accepts on [endpoint] — iroh's `Router::builder`. */
        fun builder(endpoint: Endpoint): RouterBuilder = RouterBuilder(endpoint)

        /**
         * The QUIC application error code a router closes a connection with when no
         * [ProtocolHandler] is registered for the ALPN it negotiated.
         *
         * iroh4k's own number: QUIC application error codes are defined by the application protocol
         * and there is no registry to consult, so this is simply the code a peer can match on to
         * tell "unroutable protocol" apart from an ordinary close, which uses `0`.
         */
        const val UNSUPPORTED_ALPN_ERROR_CODE: Long = 1

        /** The reason sent alongside [UNSUPPORTED_ALPN_ERROR_CODE], as raw bytes on the wire. */
        private val UNSUPPORTED_ALPN_REASON = "unsupported ALPN".encodeToByteArray()
    }
}

// ── The builder ───────────────────────────────────────────────────────────────────────────────

/**
 * Collects the protocols a [Router] will serve, then starts it. Obtained from [Router.builder].
 *
 * Mutable and chainable, as iroh's `RouterBuilder` is:
 *
 * ```kotlin
 * val router = Router.builder(endpoint)
 *     .accept(echoAlpn) { connection -> echo(connection) }
 *     .accept(chatAlpn, chat)
 *     .onFailure { alpn, failure -> logger.warn("$alpn failed", failure) }
 *     .spawn()
 * ```
 *
 * Not thread-safe, and does not need to be: a builder is assembled in one place and consumed by
 * [spawn]. The [Router] it produces is thread-safe.
 */
class RouterBuilder internal constructor(

    /** The endpoint the router will accept on — iroh's `RouterBuilder::endpoint`. */
    val endpoint: Endpoint,
) {

    private val protocols = LinkedHashMap<List<Byte>, ProtocolHandler>()

    private var onFailure: (alpn: ByteArray?, failure: Throwable) -> Unit = { _, _ -> }

    /**
     * Registers [handler] for connections whose negotiated protocol is [alpn].
     *
     * Registering the same ALPN twice replaces the first handler, as inserting into iroh's protocol
     * map does. Registration order is kept, which is the order the protocols are advertised in by
     * [spawn].
     */
    fun accept(alpn: ByteArray, handler: ProtocolHandler): RouterBuilder {
        // `toList` copies, which matters for the same reason `EndpointConfig` copies its ALPNs: a
        // `ByteArray` is mutable, and a routing table that changed under a running router would
        // dispatch to the wrong handler — or to none.
        protocols[alpn.toList()] = handler
        return this
    }

    /**
     * Sets where the router reports failures it survives.
     *
     * Called with the negotiated ALPN — `null` when the failure happened before one was known — and
     * the failure itself, for each of: a handshake that failed, an ALPN with no handler, a
     * [ProtocolHandler.accept] that threw, and an [Endpoint.acceptNext] that found a released
     * endpoint. None of these stop the router, which is exactly why they need somewhere to go.
     *
     * This stands in for the `tracing::warn!` iroh uses. iroh4k has no logger of its own — see
     * [setLogLevel], which configures Rust's — so the sink is the caller's to provide, and the
     * default is silence, just as `warn!` is silent until a subscriber is installed.
     *
     * Called from the coroutine that hit the failure, so keep it quick and non-blocking. It is
     * allowed to throw: the router ignores that, having nowhere better to put it.
     */
    fun onFailure(sink: (alpn: ByteArray?, failure: Throwable) -> Unit): RouterBuilder {
        onFailure = sink
        return this
    }

    /**
     * Starts the accept loop and hands back the running [Router].
     *
     * Two things happen here, in this order. First [endpoint] is told to advertise exactly the
     * registered ALPNs — iroh's `spawn` does the same, and it is what makes registering a handler
     * sufficient. Note the consequence: this **replaces** whatever [EndpointConfig.alpns] or
     * [Endpoint.setAlpns] had put there, so a router with no protocols leaves an endpoint that
     * accepts nothing. Then the loop starts, and from that moment inbound connections are being
     * answered.
     *
     * The router must be ended — with [Router.shutdown], or [Router.close] via `use { }`. Nothing
     * else will: its coroutines are rooted in a scope of its own, so an abandoned router keeps
     * accepting for as long as its endpoint is open.
     */
    fun spawn(): Router {
        endpoint.setAlpns(protocols.keys.map { it.toByteArray() })
        // `toMap` snapshots, so the router's table is unaffected by anything done to this builder
        // afterwards.
        val router = Router(endpoint, protocols.toMap(), onFailure)
        router.start()
        return router
    }
}
