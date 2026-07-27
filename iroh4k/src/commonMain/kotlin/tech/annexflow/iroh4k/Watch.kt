package tech.annexflow.iroh4k

import kotlin.time.Duration.Companion.microseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import tech.annexflow.iroh4k.internal.BinaryReader
import tech.annexflow.iroh4k.internal.NativeHandle

/**
 * Watchers: the parts of an [Endpoint] and a [Connection] that change over time, as cold [Flow]s.
 *
 * See `watch.rs` for the Rust half. Nothing here is a handle a caller has to close — the handle is
 * owned by the flow, created when collection starts and released when it stops.
 *
 * ```kotlin
 * // The addresses this endpoint believes it is reachable at, as they change.
 * endpoint.watchAddr()
 *     .onEach { addr -> println("reachable at ${addr.addrs}") }
 *     .launchIn(scope)
 * ```
 *
 * ## Pull-based, not callbacks
 *
 * iroh-ffi registers foreign callbacks that Rust invokes — `AddrChangeCallback`, `HomeRelayCallback`
 * and so on. iroh4k deliberately does not: a reverse call would need `JNI_OnLoad`, a cached `JavaVM`
 * and an `AttachCurrentThread` on every tokio worker thread, and this binding has none of that. Each
 * watcher is instead a native cursor with a suspending `next()`, and the loop below turns it into a
 * `Flow`. Both facades share that loop; only the one native call underneath it differs.
 *
 * ## Cold, and one watcher per collector
 *
 * Every flow here is **cold**: collecting it starts a fresh native watcher and a fresh forwarding
 * task in Rust, and ending the collection releases both. So two collectors on one endpoint are two
 * independent watchers that neither share nor steal each other's values, and a flow that is never
 * collected costs nothing. Use `shareIn`/`stateIn` if one native watcher should feed several
 * consumers.
 *
 * Cancelling a collector returns promptly: the in-flight `next()` is cancelled in Rust, the handle is
 * released, and the forwarding task is aborted.
 *
 * ## Latest value, not every value
 *
 * [watchAddr], [watchHomeRelay] and [watchPaths] observe **state**, and a slow collector sees the
 * newest state rather than a backlog — an address that changed three times while nobody was looking
 * emits once, with the current value. That is what iroh's own `n0-watcher` does, and `watch.rs`
 * preserves it rather than buffering.
 *
 * [watchPathEvents] observes **events**, where coalescing would lose meaning: swallowing a
 * `PathEvent.Opened` would leave a collector believing a path never existed. It is buffered and the
 * Rust side waits for room rather than discarding, so nothing is dropped by iroh4k. iroh may still
 * drop events on a genuinely slow collector, and says so with [PathEvent.Lagged].
 *
 * ## When a flow ends
 *
 * Normally, without an exception, when the thing being watched goes away:
 *
 * - [watchAddr], [watchHomeRelay] and [watchNetworkChange] end when the endpoint is shut down or its
 *   handle is released.
 * - [watchPathEvents] ends when the connection ends.
 * - [watchPaths] ends when the connection is **closed** — by [Connection.close] with a code, by the
 *   peer, or by a timeout. Merely releasing the Kotlin handle is not enough: `paths_stream` borrows
 *   the connection in iroh, so the Rust forwarding task has to hold a clone of it for as long as it
 *   runs, exactly as a suspended `acceptBi()` does. Cancelling the collector always ends it.
 *
 * Starting a flow on an already-released [Endpoint] or [Connection] raises [IrohError] with
 * [IrohError.Code.Closed] from the first `collect`, as every other member of those types does.
 */

// ── Values ────────────────────────────────────────────────────────────────────────────────────

/**
 * The connection status of one of the endpoint's home relays, mirroring `iroh::endpoint::RelayStatus`.
 *
 * @property url the relay this describes.
 * @property isConnected whether the endpoint currently has a working connection to it.
 * @property lastError the most recent failed connection attempt, or `null` while connected and while
 *   no attempt has failed yet. A diagnostic string from iroh, not a code to branch on.
 */
data class RelayStatus(
    val url: RelayUrl,
    val isConnected: Boolean,
    val lastError: String?,
) {
    override fun toString(): String =
        "RelayStatus($url, ${if (isConnected) "connected" else "disconnected${lastError?.let { ": $it" } ?: ""}"})"
}

/**
 * One thing that happened to a network path of a [Connection], mirroring `iroh::endpoint::PathEvent`.
 *
 * A connection usually starts on a relayed path and opens a direct one once hole punching succeeds,
 * at which point the direct path is [Selected] and the relayed one eventually [Closed]. Watching
 * these is how an application can tell the difference; [Connection.paths] is the same information as
 * a snapshot rather than as a stream.
 *
 * That enum is `#[non_exhaustive]`, so a newer iroh can add a variant this build has never heard of;
 * it decodes to [Unknown] rather than being dropped, exactly as [TransportAddr.Unknown] does.
 */
sealed interface PathEvent {

    /** A new network path opened. */
    data class Opened(
        /** iroh's identifier for the path within the connection, or `-1` if it could not be read. */
        val pathId: Long,
        /** The remote end. */
        val remoteAddr: TransportAddr,
        /** The local end, which the OS does not always report. */
        val localAddr: LocalTransportAddr,
    ) : PathEvent {
        override fun toString(): String = "PathOpened($pathId, $remoteAddr)"
    }

    /**
     * A network path closed, carrying the statistics it ended with.
     *
     * [path] is a full [PathSnapshot] so the final figures read exactly like a live one's;
     * [PathSnapshot.isSelected] is always `false`, because a closed path is by definition not the one
     * application data is going over.
     */
    data class Closed(val path: PathSnapshot) : PathEvent {
        override fun toString(): String = "PathClosed(${path.pathId}, ${path.remoteAddr})"
    }

    /** This path became the one application data is transmitted over. */
    data class Selected(
        /** iroh's identifier for the path within the connection, or `-1` if it could not be read. */
        val pathId: Long,
        /** The remote end. */
        val remoteAddr: TransportAddr,
        /** The local end, which the OS does not always report. */
        val localAddr: LocalTransportAddr,
    ) : PathEvent {
        override fun toString(): String = "PathSelected($pathId, $remoteAddr)"
    }

    /**
     * Events were dropped before they could be delivered, because the collector fell behind.
     *
     * iroh's own signal, forwarded rather than hidden: iroh4k never discards an event itself, but a
     * collector slower than the network gets this instead of the events it missed. The current set of
     * paths is still recoverable from [Connection.paths].
     *
     * @property missed how many events were lost since the last one delivered.
     */
    data class Lagged(val missed: Long) : PathEvent {
        override fun toString(): String = "PathEventsLagged($missed)"
    }

    /**
     * An event of a kind this build of iroh4k does not know, preserved for inspection.
     *
     * @property description the event as iroh's `Debug` rendered it — `PathEvent` has no `Display`.
     */
    data class Unknown(val description: String) : PathEvent {
        override fun toString(): String = "PathEventUnknown($description)"
    }
}

// ── The endpoint's watchers ───────────────────────────────────────────────────────────────────
//
// Extensions rather than members of `Endpoint`/`Connection`, as the stream domain's entry points are:
// everything they produce belongs to this domain, and all they need is the handle, which
// `withHandle` lends under the owner's own guard.

/**
 * The endpoint's own [EndpointAddr] — its id plus every address it believes it is reachable at — as
 * it changes.
 *
 * Emits the current value immediately, then a new one whenever iroh's view of its own reachability
 * moves: a direct address appearing or going away, a home relay being picked or lost. The [id][
 * EndpointAddr.id] never changes; only the addresses do.
 *
 * This is the value to hand a peer (or to put in an [EndpointTicket]) so it can dial back. Note that
 * with [RelayMode.Disabled], or before [Endpoint.online] has completed, it may hold only addresses
 * that are reachable on the local network.
 *
 * Latest-value: a collector that falls behind sees the current address, not every address in between.
 *
 * @throws IrohError with [IrohError.Code.Closed] if the endpoint has been released.
 */
fun Endpoint.watchAddr(): Flow<EndpointAddr> = watchFlow(
    what = ADDR_WATCHER,
    open = { withHandle { nativeEndpointWatchAddr(it) } },
) { BinaryReader(it).readEndpointAddr() }

/**
 * The connection status of the endpoint's home relays, as it changes.
 *
 * One [RelayStatus] per home relay whose URL is known. The list is **empty** before the endpoint has
 * picked a home relay, and stays empty for as long as none is picked — so an endpoint built with
 * [RelayMode.Disabled], or one whose relays are unreachable, may emit nothing at all rather than
 * emitting an empty list. Bound a wait on it with `withTimeout { }`, exactly as [Endpoint.online]
 * has to be bounded, for the same reason.
 *
 * Latest-value, as [watchAddr] is.
 *
 * @throws IrohError with [IrohError.Code.Closed] if the endpoint has been released.
 */
fun Endpoint.watchHomeRelay(): Flow<List<RelayStatus>> = watchFlow(
    what = RELAY_WATCHER,
    open = { withHandle { nativeEndpointWatchHomeRelay(it) } },
) { payload -> BinaryReader(payload).seq { it.readRelayStatus() } }

/**
 * Emits once each time the endpoint's view of its own reachability changes — the observable
 * consequence of the local network having moved.
 *
 * **Read this before using it.** iroh's `Endpoint::network_change()` — which [Endpoint.networkChange]
 * exposes — is a notification travelling *into* iroh, not an event source: it tells iroh to
 * re-examine its connectivity and returns as soon as that request is queued. Looping it would be a
 * busy loop pumping refresh requests at the endpoint, not an observation of anything, so this is not
 * built on it and adds no native surface of its own.
 *
 * What is genuinely observable is [watchAddr]: a network change is exactly what makes an endpoint's
 * direct addresses or home relay move. So this is that flow with its initial value dropped and each
 * later value reduced to [Unit]. It therefore reports *a change in reachability*, which is a superset
 * of "the network changed" — adding an external address with `Endpoint.addExternalAddr` also moves it
 * — and it will not fire for a network change that left the endpoint's addresses identical.
 *
 * iroh exposes a true network-change stream only through its unstable net-report watcher, which is
 * out of scope for this binding.
 *
 * @throws IrohError with [IrohError.Code.Closed] if the endpoint has been released.
 */
fun Endpoint.watchNetworkChange(): Flow<Unit> = watchAddr().drop(1).map { }

// ── The connection's watchers ─────────────────────────────────────────────────────────────────

/**
 * The connection's open network paths, re-snapshotted whenever they change.
 *
 * Emits the current paths immediately, then a fresh list whenever a path opens or closes or the
 * selected one changes. The same shape [Connection.paths] returns, so the two are interchangeable —
 * one polls, this one waits.
 *
 * Latest-value: a collector that falls behind sees the current set of paths, not every intermediate
 * set. Use [watchPathEvents] when the individual transitions matter.
 *
 * **This flow keeps the connection alive** for as long as it is collected, because iroh's
 * `paths_stream` borrows the connection and the native forwarding task therefore has to hold a clone
 * of it — the same property a suspended [Connection.acceptBi] has. So it ends when the connection is
 * *closed* (by [Connection.close] with a code, by the peer, or by a timeout) rather than when the
 * Kotlin handle is merely released. Cancelling the collector always ends it immediately.
 *
 * @throws IrohError with [IrohError.Code.Closed] if the connection has been released.
 */
fun Connection.watchPaths(): Flow<List<PathSnapshot>> = watchFlow(
    what = PATHS_WATCHER,
    open = { withHandle { nativeConnectionWatchPaths(it) } },
) { payload -> BinaryReader(payload).seq { it.readPathSnapshot() } }

/**
 * The individual lifecycle events of the connection's network paths.
 *
 * Every transition, in order: a path opening, a path being selected for transmission, a path closing
 * with its final statistics. Unlike [watchPaths] nothing is coalesced — see the note on events at the
 * top of this file — and a collector that cannot keep up is told so with [PathEvent.Lagged] rather
 * than silently missing transitions.
 *
 * Ends when the connection ends, including when its handle is released: unlike [watchPaths], this
 * stream does not hold the connection open.
 *
 * @throws IrohError with [IrohError.Code.Closed] if the connection has been released.
 */
fun Connection.watchPathEvents(): Flow<PathEvent> = watchFlow(
    what = EVENTS_WATCHER,
    open = { withHandle { nativeConnectionWatchPathEvents(it) } },
) { BinaryReader(it).readPathEvent() }

// ── The one flow every watcher is ─────────────────────────────────────────────────────────────

/**
 * Turns a native cursor into a cold [Flow].
 *
 * The single place the pull loop lives, shared by both facades and by all four watchers: [open]
 * creates the native handle, `next` is suspended on until it answers `null` for the end of the
 * stream, and the handle is released however the collection ends — normally, by an exception, or by
 * cancellation.
 *
 * The handle is guarded by [NativeHandle] like every other native object here, so a `close()` racing
 * an in-flight `next()` is safe: the guard frees only once the call inside it has returned. It is
 * created *inside* the `flow { }` block rather than outside, which is what makes the flow cold and
 * each collector's watcher its own.
 */
private fun <T> watchFlow(
    what: String,
    open: suspend () -> Long,
    decode: (ByteArray) -> T,
): Flow<T> = flow {
    val guard = NativeHandle(open(), what, ::nativeWatchFree)
    try {
        while (true) {
            // `null` is the end of the stream, not a failure — the watched object went away.
            val payload = guard.useSuspending { nativeWatchNext(it) } ?: break
            emit(decode(payload))
        }
    } finally {
        guard.close()
    }
}

/** What each watcher calls itself in an [IrohError.Code.Closed] message. */
private const val ADDR_WATCHER = "address watcher"
private const val RELAY_WATCHER = "home relay watcher"
private const val PATHS_WATCHER = "path watcher"
private const val EVENTS_WATCHER = "path event watcher"

// ── Test hooks ────────────────────────────────────────────────────────────────────────────────

/**
 * Watcher handles still alive in Rust.
 *
 * Exposed for tests, as [Streams.liveHandleCount] is, and counted separately from every other
 * domain's: a watcher leaked by a collect/cancel loop has to be visible even while endpoints and
 * connections come and go around it. It must return to its baseline once every collection has ended.
 */
internal object Watchers {
    val liveHandleCount: Long get() = nativeWatchLiveHandleCount()
}

// ── The watch codec ───────────────────────────────────────────────────────────────────────────
//
// The layout is defined in `watch.rs`, which documents it in full; this is its Kotlin mirror and the
// two must be changed together.
//
// The address and path readers below are near-duplicates of the private ones in `Addr.kt` and
// `Connection.kt`. That is the Kotlin side of the same trade the Rust writers make, and the note
// there applies word for word: the copies should be one `internal` set, and the shape is repeated
// rather than *changed* precisely so there is still one layout per type. A second layout for
// `TransportAddr` or `PathSnapshot` would be the real mistake.

private const val ADDR_TAG_RELAY = 0
private const val ADDR_TAG_IP = 1
private const val ADDR_TAG_CUSTOM = 2
private const val ADDR_TAG_UNKNOWN = 3

private const val EVENT_TAG_OPENED = 0
private const val EVENT_TAG_CLOSED = 1
private const val EVENT_TAG_SELECTED = 2
private const val EVENT_TAG_LAGGED = 3
private const val EVENT_TAG_UNKNOWN = 4

/** Discriminators for an optional record, as `watch.rs` writes them. */
private const val RECORD_ABSENT = 0

private fun BinaryReader.readLocalTransportAddr(): LocalTransportAddr = when (val tag = u8()) {
    ADDR_TAG_RELAY -> LocalTransportAddr.Relay(RelayUrl.trusted(string()))
    ADDR_TAG_IP -> LocalTransportAddr.Ip(optString())
    ADDR_TAG_CUSTOM -> LocalTransportAddr.Custom(
        if (u8() == RECORD_ABSENT) null else CustomAddr(i64(), bytes()),
    )

    ADDR_TAG_UNKNOWN -> LocalTransportAddr.Unknown(string())
    else -> error("Malformed watcher payload: unknown local transport address tag $tag")
}

private fun BinaryReader.readUdpStats(): UdpStats = UdpStats(datagrams = i64(), bytes = i64())

private fun BinaryReader.readRelayStatus(): RelayStatus = RelayStatus(
    url = RelayUrl.trusted(string()),
    isConnected = bool(),
    lastError = optString(),
)

private fun BinaryReader.readPathSnapshot(): PathSnapshot = PathSnapshot(
    pathId = i64(),
    remoteAddr = readTransportAddr(),
    localAddr = readLocalTransportAddr(),
    isSelected = bool(),
    rtt = i64().microseconds,
    congestionWindow = i64(),
    currentMtu = i32(),
    lostPackets = i64(),
    lostBytes = i64(),
    congestionEvents = i64(),
    udpTx = readUdpStats(),
    udpRx = readUdpStats(),
)

private fun BinaryReader.readPathEvent(): PathEvent = when (val tag = u8()) {
    EVENT_TAG_OPENED -> PathEvent.Opened(i64(), readTransportAddr(), readLocalTransportAddr())
    EVENT_TAG_CLOSED -> PathEvent.Closed(readPathSnapshot())
    EVENT_TAG_SELECTED -> PathEvent.Selected(i64(), readTransportAddr(), readLocalTransportAddr())
    EVENT_TAG_LAGGED -> PathEvent.Lagged(i64())
    EVENT_TAG_UNKNOWN -> PathEvent.Unknown(string())
    else -> error("Malformed watcher payload: unknown path event tag $tag")
}

// ── The watch domain's FFI surface, implemented per facade ────────────────────────────────────
//
// Names are prefixed `nativeWatch` so this domain's expect declarations stay distinct in the shared
// package. The two that take an *endpoint* or a *connection* handle keep that owner's prefix, since
// that is whose handle it is; the operations themselves live in `watch.rs`.

internal expect fun nativeWatchLiveHandleCount(): Long

internal expect fun nativeWatchFree(handle: Long)

/** Returns the handle of the watcher this started. */
internal expect fun nativeEndpointWatchAddr(handle: Long): Long

internal expect fun nativeEndpointWatchHomeRelay(handle: Long): Long

internal expect fun nativeConnectionWatchPaths(handle: Long): Long

internal expect fun nativeConnectionWatchPathEvents(handle: Long): Long

/** `null` once the watcher has ended, which Rust reports as `-1` in `i64_val` and no payload. */
internal expect suspend fun nativeWatchNext(handle: Long): ByteArray?
