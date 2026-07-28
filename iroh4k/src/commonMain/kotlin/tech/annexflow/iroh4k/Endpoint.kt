package tech.annexflow.iroh4k

import tech.annexflow.iroh4k.internal.BinaryWriter
import tech.annexflow.iroh4k.internal.NativeHandle

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import tech.annexflow.iroh4k.internal.BinaryReader

/**
 * The [Endpoint]: the object that actually goes on the network, plus the values that configure it
 * ([EndpointPreset], [EndpointConfig], [RelayConfig]) and the one it reports ([CounterStats]).
 *
 * This is the first type in iroh4k that is **not** a value. An endpoint owns a UDP socket, a QUIC
 * state machine and a tree of tokio tasks, all of which live in Rust; Kotlin holds an opaque handle
 * to them and has to say when it is done. See `endpoint.rs` for the Rust half.
 *
 * ## Lifecycle: two different endings
 *
 * iroh has an async `close()`, and Kotlin's [AutoCloseable] has a synchronous one. They mean
 * different things, so they are different methods here — the same clash iroh-ffi hit and resolved
 * by renaming its async one for the Kotlin binding:
 *
 * - [Endpoint.shutdown] is iroh's `close()`: it tells peers the endpoint is going away and waits
 *   for that to be acknowledged. The endpoint stays *queryable* afterwards — [isClosed] is `true`,
 *   [id] and [secretKey] still answer — exactly as iroh's own does.
 * - [Endpoint.close] releases the handle. After it, the endpoint is gone and every method raises
 *   [IrohError] with [IrohError.Code.Closed].
 *
 * A graceful exit is `shutdown()` then `close()`; `use { }` alone is an abrupt one, which is
 * legitimate (iroh drops the sockets) but tells no peer why their connection stopped.
 */

// ── Configuration ─────────────────────────────────────────────────────────────────────────────

/**
 * A bundle of endpoint defaults, mirroring `iroh::endpoint::presets`.
 *
 * The **ordinals** are the wire protocol shared with `endpoint.rs`'s `builder_for`, exactly as
 * [LogLevel]'s are with `level_filter`: do not reorder, append only. A preset is a Rust *type*
 * whose whole content is code, so it cannot cross the boundary as data — only the choice of which
 * one to use can.
 */
enum class EndpointPreset {
    /**
     * Sets nothing at all.
     *
     * **Cannot bind.** iroh requires a TLS crypto provider and this preset does not install one, so
     * [Endpoint.bind] with it always fails with [IrohError.Code.Bind] — that is upstream's own
     * documented behaviour, not a limitation here. It exists as the honest baseline: use it only
     * once iroh4k exposes every mandatory option, and use [Minimal] until then.
     */
    Empty,

    /**
     * The smallest preset that binds: a crypto provider and nothing else.
     *
     * No relays and no address lookup, so an endpoint built with it talks to nothing it was not
     * explicitly told about — which is what makes it the right choice for tests and for offline or
     * LAN-only use.
     */
    Minimal,

    /**
     * The n0 defaults: the production relay fleet plus DNS/pkarr address lookup.
     *
     * The preset to use in production, and the one that reaches the network as soon as the endpoint
     * binds.
     */
    N0,

    /**
     * The n0 defaults with relays switched off.
     *
     * Direct connections only, but address lookup is still configured — so this still publishes to
     * and queries n0's DNS servers. For an endpoint that must touch nothing, use [Minimal] with
     * [RelayMode.Disabled].
     */
    N0DisableRelay,
}

/**
 * Configuration for a single relay server, mirroring `iroh::RelayConfig`.
 *
 * A value type, like [RelayMap] and for the same reason: relay configuration is inert data.
 *
 * Modelled on the three fields iroh-ffi exposes, which are the three that have a public
 * constructor upstream — `iroh_relay::RelayConfig` is otherwise `#[non_exhaustive]`.
 *
 * @property url the relay to configure.
 * @property quicPort the port of the relay's QUIC endpoint, enabling QUIC address discovery
 *   against it. `null` disables that; a value outside `0..65535` is rejected by Rust with
 *   [IrohError.Code.Relay].
 * @property authToken sent to the relay as an `Authorization: Bearer` header on the upgrade
 *   request. `null` for an open relay.
 */
class RelayConfig(
    val url: RelayUrl,
    val quicPort: Int? = null,
    val authToken: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is RelayConfig &&
                other.url == url && other.quicPort == quicPort && other.authToken == authToken)

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (quicPort ?: -1)
        result = 31 * result + authToken.hashCode()
        return result
    }

    /** Never renders [authToken], which is a credential — see [SecretKey.toString]. */
    override fun toString(): String =
        "RelayConfig($url, quicPort=$quicPort, authToken=${if (authToken == null) "null" else ".."})"
}

/**
 * Configuration for mDNS peer discovery on the local link.
 *
 * A value type, like [RelayConfig]: it is inert data read once, when the endpoint binds.
 *
 * mDNS is not part of iroh itself — it is the separate `iroh-mdns-address-lookup` crate, which no
 * preset installs — so setting this is the only way to switch it on, and leaving
 * [EndpointConfig.mdns] `null` is the only way to be sure an endpoint sends no multicast at all.
 * With it configured, the endpoint both announces itself on the local link and resolves the peers
 * announcing themselves there, which is what lets [connect] reach a LAN peer given nothing but its
 * [EndpointId].
 *
 * ## Caveats worth reading before relying on it
 *
 * These are silent failures — the endpoint binds, discovery simply never finds anything — and
 * upstream documents none of them:
 *
 * - **Android** drops multicast and broadcast packets at the Wi-Fi driver unless the app holds a
 *   `WifiManager.MulticastLock` and declares `CHANGE_WIFI_MULTICAST_STATE` in its manifest. iroh4k
 *   deliberately does **not** put that permission in the AAR's manifest: it is a battery cost and a
 *   visible permission, so it is the app's call, not a library's. An app that wants mDNS on Android
 *   has to add both itself.
 * - **iOS and macOS apps** need Apple's `com.apple.developer.networking.multicast` entitlement to
 *   send or receive multicast, and that entitlement is granted by request rather than by checking a
 *   box. Without it the traffic is dropped by the OS.
 * - **A host with no usable multicast at all** — neither IPv4 nor IPv6 — makes the discovery
 *   service fail to start, and that failure comes out of [Endpoint.bind] as [IrohError] with
 *   [IrohError.Code.Bind]. Asking for mDNS can therefore turn a bind that would have succeeded into
 *   one that fails.
 *
 * @property advertise whether this endpoint announces its own presence. `true`, the default,
 *   advertises and resolves; `false` makes it a listen-only resolver, which finds peers on the link
 *   without telling them it is there.
 * @property serviceName the mDNS service name to advertise and query under, which is what separates
 *   one swarm from another on a shared link. `null` leaves upstream's own default, currently
 *   `"irohv1"` — kept as "not stated" rather than spelled out here so the default stays upstream's
 *   to change. Two endpoints only find each other if their service names match.
 */
class MdnsConfig(
    val advertise: Boolean = true,
    val serviceName: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is MdnsConfig &&
                other.advertise == advertise && other.serviceName == serviceName)

    override fun hashCode(): Int = 31 * advertise.hashCode() + serviceName.hashCode()

    override fun toString(): String = "MdnsConfig(advertise=$advertise, serviceName=$serviceName)"
}

/**
 * Everything [Endpoint.bind] can be told, as one immutable value.
 *
 * iroh's `Builder` is consumed by `bind()`, so a Kotlin builder object backed by a Rust handle
 * could not survive the call that used it. Assembling the configuration on this side instead means
 * there is no consumed-handle lifetime to model, the whole thing crosses the boundary once rather
 * than once per setter, and a configuration is an ordinary value that can be held, compared and
 * reused. See the header of `endpoint.rs` for the full argument.
 *
 * Every field except [preset] is optional, and each one that is set is applied **after** the
 * preset, so an explicit choice always wins over the preset's.
 *
 * @property preset the baseline. Defaults to [EndpointPreset.N0], as iroh-ffi's does.
 * @property secretKey the endpoint's identity. `null` asks iroh to generate one, which means a new
 *   [EndpointId] on every bind — pass a key to keep an identity across restarts.
 * @property relayMode which relays to use. `null` leaves whatever [preset] configured; pass
 *   [RelayMode.Disabled] for an endpoint that never contacts a relay.
 * @property bindAddrs the local addresses to bind sockets on. Empty means iroh's default, one
 *   socket on `0.0.0.0` and one on `[::]`. A non-empty list **replaces** those rather than adding
 *   to them, so `bindAddrs = [127.0.0.1:0]` really is loopback only. (iroh's `bind_addr` and
 *   iroh-ffi's wrapper for it *add*, leaving the endpoint still listening on every interface;
 *   iroh4k clears the defaults first, as iroh's own documentation example does.) Port `0` asks the
 *   OS for a free port.
 * @property externalAddrs addresses this endpoint is directly reachable on that it cannot discover
 *   itself — a static NAT mapping or a load balancer.
 * @property mdns whether to discover peers over mDNS on the local link. `null`, the default, means
 *   no mDNS at all: nothing is advertised, nothing is listened for, and no multicast leaves the
 *   host. That is not merely a default — it is the whole reason an endpoint can be offline, so an
 *   [EndpointPreset.Minimal] endpoint with [RelayMode.Disabled] and a loopback [bindAddrs] still
 *   touches nothing. Pass an [MdnsConfig] to switch it on, and read that type's caveats first: it
 *   needs a manifest permission on Android and an entitlement on Apple platforms, and it can make
 *   [Endpoint.bind] fail on a host with no usable multicast.
 * @property discovery the address lookup services this endpoint uses. Empty, the default, means
 *   "I did not say", leaving whatever [preset] configured — n0's pkarr and DNS under
 *   [EndpointPreset.N0], nothing at all under [EndpointPreset.Minimal]. A **non-empty** list
 *   replaces the preset's services rather than adding to them, so it is how an app points discovery
 *   at its own infrastructure. Note the asymmetry that follows: "use the preset's relays but no
 *   discovery at all" is not expressible here, because an empty list already means something else —
 *   spell that [EndpointPreset.Minimal] with `relayMode = RelayMode.Default`. [mdns] is separate and
 *   always composes, because it points at no server and no preset installs it.
 * @property transportConfig the QUIC transport parameters this endpoint applies to every connection
 *   by default. `null`, the default, leaves iroh's own — including the six it overrides for hole
 *   punching, see [TransportConfig]'s class documentation. A config set on a single connection
 *   **replaces** this one outright rather than merging with it, exactly as [TransportConfig] itself
 *   documents.
 */
class EndpointConfig(
    val preset: EndpointPreset = EndpointPreset.N0,
    val secretKey: SecretKey? = null,
    alpns: List<ByteArray> = emptyList(),
    val relayMode: RelayMode? = null,
    val bindAddrs: List<SocketAddr> = emptyList(),
    val externalAddrs: List<SocketAddr> = emptyList(),
    val mdns: MdnsConfig? = null,
    val discovery: List<Discovery> = emptyList(),
    val transportConfig: TransportConfig? = null,
) {
    // Copied in and copied out, for the reason `CustomAddr` copies: a `ByteArray` is mutable, and a
    // configuration that changed under the caller after being built would be a confusing bug.
    private val alpnList: List<ByteArray> = alpns.map { it.copyOf() }

    /**
     * The ALPN protocol identifiers to advertise, as fresh copies.
     *
     * Every entry is raw bytes rather than a `String`, because an ALPN is an opaque octet string in
     * TLS. Use `"my-alpn".encodeToByteArray()` for the usual textual ones.
     */
    val alpns: List<ByteArray> get() = alpnList.map { it.copyOf() }
}

/**
 * One metric of a bound endpoint.
 *
 * @property value the counter or gauge reading. A histogram reports its observation count.
 * @property description iroh's own help text for the metric.
 */
data class CounterStats(val value: Long, val description: String)

// ── The endpoint ──────────────────────────────────────────────────────────────────────────────

/**
 * A bound iroh endpoint: a socket, an identity, and the machinery for reaching other endpoints.
 *
 * Create one with [Endpoint.bind]. It holds native resources, so it must be released — with
 * [close], or by a `use { }` block, ideally after [shutdown].
 *
 * **Thread-safe.** Every method may be called concurrently from any thread or coroutine, including
 * concurrently with [close]. That is not incidental: an endpoint is exactly the kind of object one
 * coroutine reads while another shuts down. A released handle is never dereferenced — see the note
 * on the guard below — so racing a call against [close] produces [IrohError] with
 * [IrohError.Code.Closed], never a crash.
 *
 * **Cancellable.** Every `suspend` member aborts its Rust task when the calling coroutine is
 * cancelled, rather than leaving it running. [online] is the reason this matters: with no reachable
 * relay it never completes, so the caller's timeout or scope is what ends it.
 */
class Endpoint private constructor(private val guard: NativeHandle) : AutoCloseable {

    // ── Identity and addressing ──────────────────────────────────────────────────────────────

    /**
     * This endpoint's [EndpointId] — the public key other endpoints need in order to reach it.
     *
     * Derived from [secretKey], so it is stable for the endpoint's whole life and across restarts
     * that reuse the same key. Not cached on this side: reading it after [close] must report
     * [IrohError.Code.Closed] rather than answering from a stale copy.
     */
    val id: EndpointId
        get() = sync { EndpointId.validated(nativeEndpointId(it)) }

    /**
     * The secret key backing this endpoint's identity.
     *
     * This is key material. It is exposed because there is no other way to persist a generated
     * identity: an endpoint bound without a [EndpointConfig.secretKey] gets a fresh one, and this
     * is how it can be saved and passed back on the next bind.
     */
    fun secretKey(): SecretKey = sync { SecretKey.fromBytes(nativeEndpointSecretKey(it)) }

    /**
     * Every address at which this endpoint currently believes it can be reached.
     *
     * A snapshot, not a watcher: it changes as iroh discovers addresses and picks a home relay.
     * Right after [bind] it holds the local sockets and little else — [online] is what waits until
     * there is enough here to be dialled from the internet.
     */
    fun addr(): EndpointAddr = sync { decodeEndpointAddr(BinaryReader(nativeEndpointAddr(it))) }

    /**
     * The local socket addresses the underlying sockets are bound to.
     *
     * Non-empty for a successfully bound endpoint, and this is where a port-`0` bind reveals the
     * port the OS actually chose.
     */
    fun boundSockets(): List<SocketAddr> = sync { handle ->
        BinaryReader(nativeEndpointBoundSockets(handle)).seq { SocketAddr.trusted(it.string()) }
    }

    /**
     * What this endpoint knows about the remote [id], or `null` if it knows nothing.
     *
     * iroh keeps a record of recently used remotes for a while and then drops it, so this answers
     * `null` both for a remote never seen and for one seen long enough ago. It is also `null` once
     * this endpoint has been shut down, which is iroh's own behaviour rather than an error.
     *
     * Mirrors iroh-ffi's `remoteAddr`: `RemoteInfo`'s content is an id and a set of addresses, so
     * it is reported as the [EndpointAddr] it amounts to instead of a second address-shaped type.
     */
    suspend fun remoteAddr(id: EndpointId): EndpointAddr? = suspending { handle ->
        val reader = BinaryReader(nativeEndpointRemoteAddr(handle, id.toBytes()))
        if (reader.u8() == ABSENT) null else decodeEndpointAddr(reader)
    }

    // ── Configuration at runtime ─────────────────────────────────────────────────────────────

    /**
     * Replaces the set of ALPN protocols this endpoint accepts.
     *
     * Affects new incoming connections only, and **overrides** the previous list rather than adding
     * to it. Ignored by iroh on an endpoint that has been [shutdown] — it logs a warning rather
     * than failing, and so does this.
     */
    fun setAlpns(alpns: List<ByteArray>) = sync { handle ->
        val w = BinaryWriter()
        w.i32(alpns.size)
        for (alpn in alpns) w.bytes(alpn)
        nativeEndpointSetAlpns(handle, w.finish())
    }

    /**
     * Records where a *remote* endpoint can be reached, in this endpoint's own address book.
     *
     * This does **not** dial anything; it only files [addr] away so that a later
     * `connect(EndpointAddr.of(addr.id), alpn)` — an id with no transports in it — has somewhere to
     * resolve that id from. Without an entry here, and without an address lookup service configured
     * on [EndpointConfig], such a connect has no way to find the remote and fails.
     *
     * Calling this twice for the same endpoint **accumulates** rather than replaces, which is
     * upstream's own behaviour and worth knowing before relying on it: the new IP addresses are
     * added to the ones already recorded, and only the relay URL is overwritten. So this on its own
     * is not a way to retract an address a peer has moved off — the stale one stays in the book
     * alongside the new one, and iroh keeps trying it. [removeEndpointAddr] is how an entry goes
     * away.
     *
     * Synchronous, unlike its neighbours here, because it genuinely is: the address is inserted into
     * a map and nothing goes on the network.
     *
     * @throws IrohError with [IrohError.Code.Addr] if [addr] cannot be encoded, which means it
     *   holds a [TransportAddr.Unknown] this build cannot re-encode.
     * @throws IrohError with [IrohError.Code.Closed] if this endpoint has been released, or was
     *   never successfully bound.
     */
    fun addEndpointAddr(addr: EndpointAddr) = sync {
        nativeEndpointAddEndpointAddr(it, encodeEndpointAddr(addr))
    }

    /**
     * Removes what [addEndpointAddr] recorded for [id]. `true` if there was an entry to remove.
     *
     * The whole entry goes, not part of it: the address book holds one record per peer, keyed by
     * id, so every transport address and the relay URL in it disappear together. Since
     * [addEndpointAddr] accumulates, this is the only way to retract an address — remove the entry
     * and record the address the peer moved to.
     *
     * What was removed is not handed back. An entry is an `EndpointInfo`, which carries more than an
     * [EndpointAddr] — when it was last updated, and the peer's own user data — and iroh4k models
     * neither, so the most that could be returned is a lossy half of it. Whether there was an entry
     * at all is the part a caller can act on.
     *
     * Removing an entry does not close connections already established with that peer, and does not
     * un-learn the path a live connection is using; it only takes away what a *later*
     * `connect(EndpointAddr.of(id), alpn)` would have resolved the id from.
     *
     * Synchronous, like [addEndpointAddr] and for the same reason.
     *
     * @throws IrohError with [IrohError.Code.Closed] if this endpoint has been released, or was
     *   never successfully bound.
     */
    fun removeEndpointAddr(id: EndpointId): Boolean = sync {
        nativeEndpointRemoveEndpointAddr(it, id.toBytes())
    }

    /**
     * Advertises [addr] as an address this endpoint is directly reachable on.
     *
     * For addresses iroh cannot discover for itself — a static port-forward, a load balancer. The
     * runtime counterpart of [EndpointConfig.externalAddrs]. Ignored after [shutdown].
     */
    suspend fun addExternalAddr(addr: SocketAddr) = suspending {
        nativeEndpointAddExternalAddr(it, addr.toString())
    }

    /**
     * Removes an address added by [addExternalAddr]. `true` if one was there to remove.
     *
     * Also `false` after [shutdown], which iroh does not distinguish from "was not present".
     */
    suspend fun removeExternalAddr(addr: SocketAddr): Boolean = suspending {
        nativeEndpointRemoveExternalAddr(it, addr.toString())
    }

    /**
     * Adds [config] to this endpoint's relay map, returning the configuration it replaced.
     *
     * `null` means there was no entry for that relay — **and also** that this endpoint has been
     * [shutdown], which iroh reports the same way rather than as an error. Check [isClosed] if the
     * difference matters.
     */
    suspend fun insertRelay(config: RelayConfig): RelayConfig? = suspending { handle ->
        val w = BinaryWriter()
        writeRelayConfig(w, config)
        decodeOptionalRelayConfig(nativeEndpointInsertRelay(handle, w.finish()))
    }

    /**
     * Removes [url] from this endpoint's relay map, returning the configuration that was there.
     *
     * `null` as in [insertRelay]: either no such relay, or the endpoint is shut down.
     */
    suspend fun removeRelay(url: RelayUrl): RelayConfig? = suspending {
        decodeOptionalRelayConfig(nativeEndpointRemoveRelay(it, url.toString()))
    }

    /**
     * Tells iroh the network may have changed, prompting it to re-examine its connectivity.
     *
     * iroh detects most changes itself, but Android does not expose interface changes to native
     * code — only to Java — so an Android app should call this from its own `ConnectivityManager`
     * callback. Harmless at any other time, and ignored after [shutdown].
     */
    suspend fun networkChange() = suspending { nativeEndpointNetworkChange(it) }

    // ── Connectivity ─────────────────────────────────────────────────────────────────────────

    /**
     * Suspends until this endpoint is connected to a home relay.
     *
     * After [bind] the endpoint can already be dialled directly, but it has almost certainly not
     * finished picking a relay; waiting here is what makes [addr] complete enough to be dialled
     * from the internet, and makes hole punching work.
     *
     * **This may never return.** With [RelayMode.Disabled] — or any configuration whose relays are
     * unreachable — there is no home relay to wait for, and iroh waits on a watcher that will never
     * update. That is upstream behaviour, and it is why cancellation is plumbed all the way into
     * Rust: wrap this in `withTimeout { }` unless the surrounding scope is already bounded.
     */
    suspend fun online() = suspending { nativeEndpointOnline(it) }

    // ── Introspection ────────────────────────────────────────────────────────────────────────

    /**
     * Every metric iroh keeps for this endpoint, keyed `"<group>:<metric>"`.
     *
     * A snapshot taken now. Counters are [Long] rather than iroh-ffi's saturating `UInt`, which
     * pins any value past ~4.29 billion — reachable for a byte counter — at its maximum and so
     * reads as a plateau instead of an overflow.
     */
    fun stats(): Map<String, CounterStats> = sync { handle ->
        val reader = BinaryReader(nativeEndpointStats(handle))
        val count = reader.i32()
        val stats = LinkedHashMap<String, CounterStats>(count)
        repeat(count) {
            val name = reader.string()
            stats[name] = CounterStats(reader.i64(), reader.string())
        }
        stats
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────

    /**
     * Whether iroh's own endpoint has been closed — that is, whether [shutdown] has run.
     *
     * Not the same question as [isReleased]. This one crosses to Rust, so it raises [IrohError]
     * with [IrohError.Code.Closed] once the handle has been released by [close].
     */
    val isClosed: Boolean get() = sync { nativeEndpointIsClosed(it) }

    /**
     * Whether [close] has released this endpoint's handle, after which every other member raises
     * [IrohError] with [IrohError.Code.Closed].
     *
     * The one member that answers instead of raising, so `if (!endpoint.isReleased)` is possible
     * without a try/catch. Note it is a snapshot: another thread may release the endpoint between
     * this read and the next call, which is precisely why the guard checks again on every call
     * rather than trusting a prior look at this flag.
     */
    val isReleased: Boolean get() = guard.isReleased

    /**
     * Closes iroh's endpoint gracefully: tells peers it is going away and waits for them to
     * acknowledge it.
     *
     * This is iroh's async `close()`, renamed because [AutoCloseable.close] is synchronous and
     * cannot await — the same collision iroh-ffi resolved the same way. Afterwards [isClosed] is
     * `true` and the endpoint is still queryable, exactly as iroh's own is; [close] is what
     * releases it.
     *
     * Safe to call more than once — the second call finds an already-closed endpoint and returns.
     */
    suspend fun shutdown() = suspending { nativeEndpointShutdown(it) }

    /**
     * Releases this endpoint's native handle, dropping its sockets and tasks.
     *
     * Idempotent, and safe to call while other threads are using the endpoint: the handle is freed
     * only once every in-flight call has returned, and any call arriving after this point raises
     * [IrohError] with [IrohError.Code.Closed] instead of touching freed memory.
     *
     * This does **not** notify peers — for that, [shutdown] first. A `use { }` block that only
     * closes is an abrupt disconnect, which is legal but leaves remote endpoints waiting for a
     * timeout to work out what happened.
     *
     * Note that a `suspend` call still in flight keeps the handle alive until it finishes, so an
     * endpoint closed while [online] is suspended is released when that coroutine is cancelled.
     * Cancelling the scope, rather than only closing the endpoint, is what makes the release prompt.
     */
    override fun close() {
        guard.close()
    }

    /**
     * `Endpoint(<id>)`, or `Endpoint(released)` once the handle is gone.
     *
     * Asked of the endpoint rather than of [isReleased], because a release landing between the two
     * would otherwise print a `null` id — and a `toString` is the last place a caller wants a race.
     */
    override fun toString(): String = runCatching { "Endpoint($id)" }.getOrElse { "Endpoint(released)" }

    // ── The guard ────────────────────────────────────────────────────────────────────────────

    /** Runs a synchronous native call against a handle that cannot be freed underneath it. */
    private fun <T> sync(block: (Long) -> T): T {
        val handle = guard.acquire()
        try {
            return block(handle)
        } finally {
            guard.release()
        }
    }

    /**
     * As [sync], for a suspending call.
     *
     * Holding the guard across the suspension is what makes cancelling a long operation safe: the
     * handle stays valid until the Rust task has actually been aborted and the call has returned.
     */
    private suspend fun <T> suspending(block: suspend (Long) -> T): T =
        guard.useSuspending(block)

    /**
     * Runs [block] against this endpoint's native handle, holding the guard across it.
     *
     * The hook the connection domain needs. `acceptNext` and the outbound starter belong to the
     * accept chain, so they live in `Connection.kt` as extensions on this type rather than as members
     * here — and all either of them needs from an endpoint is its handle. Exposing that through the
     * guard, rather than exposing the handle itself, keeps the guard the single arbiter of when the
     * handle may be touched: a release racing an `acceptNext` still reports
     * [IrohError.Code.Closed] instead of reaching a freed pointer.
     */
    internal suspend fun <T> withHandle(block: suspend (Long) -> T): T = suspending(block)

    companion object {
        /**
         * Binds an endpoint according to [config].
         *
         * Cancellable: cancelling the calling coroutine aborts the bind in Rust and releases
         * anything it had already created, so a cancelled bind leaks neither a socket nor a handle.
         *
         * @throws IrohError with [IrohError.Code.Bind] if iroh refuses to bind — a port already in
         *   use, no crypto provider ([EndpointPreset.Empty] never has one), or a malformed
         *   configuration. A bad piece of [config] reports the code that fits it instead:
         *   [IrohError.Code.Key] for the secret key, [IrohError.Code.Addr] for an address and
         *   [IrohError.Code.Relay] for a relay URL.
         */
        suspend fun bind(config: EndpointConfig = EndpointConfig()): Endpoint {
            // The handle is created *before* the bind, so Kotlin owns it no matter how the bind
            // ends. See `EndpointSlot` in `endpoint.rs`: had the handle come back from the bind
            // instead, a cancellation landing in the instant it succeeded would strand a bound
            // socket in Rust with nothing left to close it.
            val endpoint = Endpoint(NativeHandle(nativeEndpointNew(), "endpoint", ::nativeEndpointFree))
            try {
                endpoint.suspending { nativeEndpointBind(it, encodeBindConfig(config)) }
            } catch (failure: Throwable) {
                // Covers cancellation too, which is the case that matters: the Rust task may have
                // finished binding and filled the slot in, and this is what drops it.
                endpoint.close()
                throw failure
            }
            return endpoint
        }

        /**
         * Endpoint handles still alive in Rust.
         *
         * Exposed for tests: it must return to its baseline after endpoints are closed, which is
         * how a handle leak would be caught. The op-registry counterpart is [Iroh4k.liveOpCount].
         */
        internal val liveHandleCount: Long get() = nativeEndpointLiveHandleCount()
    }
}


// ── The endpoint codec ────────────────────────────────────────────────────────────────────────
//
// The layout is defined in `endpoint.rs`, which documents it in full; this is its Kotlin mirror and
// the two must be changed together. The transport-address tags are the same numbers `Addr.kt` uses,
// deliberately: an `EndpointAddr` has one payload shape across the binding, whether it came from a
// ticket or from `Endpoint.addr()`.
//
// The bind configuration is *positional* — no field ids, no version — so a new field can only ever
// be appended, and only in the same change as the Rust reader. The tail of it currently reads:
//
//   i32     external count   then count × str (canonical socket address)
//   u8      mDNS             0 absent — no mDNS at all — or 1 present, then:
//                            u8   advertise     0 resolve only, 1 also advertise this endpoint
//                            str? service name  i32 -1 for upstream's own default
//   i32     discovery count  then count × one service, `Discovery.kt`'s `writeDiscovery`:
//                            u8 0 PkarrPublisher + str? relay URL + u8 published addrs;
//                            u8 1 PkarrResolver  + str? relay URL;
//                            u8 2 Dns            + str? origin domain
//   u8      transport config 0 absent — iroh's own defaults — or 1 present, then
//                            `TransportConfig.kt`'s `writeTransportConfig`: a counted sequence of
//                            tagged entries, its own tag family (`TRANSPORT_TAG_*`), documented in
//                            full there and mirrored by `transport.rs`.
//
// `addEndpointAddr` sends an `EndpointAddr` as the *whole* payload, so it is written with
// `encodeEndpointAddr` and read by `addr::decode_endpoint_addr`, which rejects trailing bytes —
// not the inline `writeEndpointAddr`/`read_endpoint_addr` pair, which is for an address embedded in
// a larger message and would accept a wrong-layout payload here without complaint.

private const val ADDR_TAG_RELAY = 0
private const val ADDR_TAG_IP = 1
private const val ADDR_TAG_CUSTOM = 2
private const val ADDR_TAG_UNKNOWN = 3

/** Discriminators for an optional record. */
private const val ABSENT = 0
private const val PRESENT = 1

/** Ordinals of the relay-mode tag inside a bind configuration. */
private const val RELAY_MODE_UNSET = 0
private const val RELAY_MODE_DISABLED = 1
private const val RELAY_MODE_DEFAULT = 2
private const val RELAY_MODE_STAGING = 3
private const val RELAY_MODE_CUSTOM = 4


/** Encodes an [EndpointConfig] as the payload `endpoint.rs`'s `read_bind_config` expects. */
private fun encodeBindConfig(config: EndpointConfig): ByteArray {
    val w = BinaryWriter()
    w.i32(config.preset.ordinal)

    val secret = config.secretKey
    if (secret == null) w.i32(-1) else w.bytes(secret.toBytes())

    val alpns = config.alpns
    w.i32(alpns.size)
    for (alpn in alpns) w.bytes(alpn)

    when (val mode = config.relayMode) {
        // Absent rather than `Disabled`: leaving the preset's choice alone and switching relays off
        // are different requests, and only one of them is "I did not say".
        null -> w.u8(RELAY_MODE_UNSET)
        RelayMode.Disabled -> w.u8(RELAY_MODE_DISABLED)
        RelayMode.Default -> w.u8(RELAY_MODE_DEFAULT)
        RelayMode.Staging -> w.u8(RELAY_MODE_STAGING)
        is RelayMode.Custom -> {
            w.u8(RELAY_MODE_CUSTOM)
            val urls = mode.map.urls
            w.i32(urls.size)
            for (url in urls) w.string(url.toString())
        }
    }

    w.i32(config.bindAddrs.size)
    for (addr in config.bindAddrs) w.string(addr.toString())

    w.i32(config.externalAddrs.size)
    for (addr in config.externalAddrs) w.string(addr.toString())

    // An optional *record* rather than a tag with an "unset" value, as `relayMode` has: there is no
    // preset choice to leave alone here, because no iroh preset configures mDNS at all. Absent
    // means the discovery service is never created, which is what keeps an endpoint off the link.
    val mdns = config.mdns
    if (mdns == null) {
        w.u8(ABSENT)
    } else {
        w.u8(PRESENT)
        w.bool(mdns.advertise)
        w.optString(mdns.serviceName)
    }

    // Appended after the mDNS record, because the payload is positional and a field can only ever
    // be added at the end. A counted sequence rather than an optional record: an empty list and an
    // absent one mean the same thing here — leave the preset's services alone — so there is nothing
    // for a presence byte to distinguish.
    w.i32(config.discovery.size)
    for (service in config.discovery) w.writeDiscovery(service)

    // Appended after the discovery sequence, for the same reason: the payload is positional and a
    // field can only ever be added at the end. An optional *record*, like `mdns` above and unlike
    // `relayMode`'s "leave the preset's choice" tag — there is no preset transport configuration to
    // leave alone, so absent always means iroh's own defaults.
    w.writeOptionalTransportConfig(config.transportConfig)

    return w.finish()
}

private fun writeRelayConfig(w: BinaryWriter, config: RelayConfig) {
    w.string(config.url.toString())
    // `-1` for absent, since a port is a fixed-width scalar; Rust rejects anything else outside
    // 0..65535, so an out-of-range Int is reported rather than silently truncated.
    w.i32(config.quicPort ?: -1)
    w.optString(config.authToken)
}

/** Decodes the `u8 present` + [RelayConfig] payload the relay-map mutations answer with. */
private fun decodeOptionalRelayConfig(payload: ByteArray): RelayConfig? {
    val reader = BinaryReader(payload)
    if (reader.u8() == ABSENT) return null
    // iroh produced this URL, so it is already canonical — see `RelayUrl.trusted`.
    val url = RelayUrl.trusted(reader.string())
    val port = reader.i32()
    return RelayConfig(url, quicPort = if (port < 0) null else port, authToken = reader.optString())
}

/**
 * Decodes an [EndpointAddr] written by `endpoint.rs`'s `write_endpoint_addr`.
 *
 * The same layout `Addr.kt` decodes for a ticket; both should call one shared reader once the
 * addressing codec is `internal` rather than file-private.
 */
private fun decodeEndpointAddr(reader: BinaryReader): EndpointAddr {
    // iroh validated the id before writing it, so it needs no second round trip.
    val id = EndpointId.validated(reader.bytes())
    return EndpointAddr.trusted(id, reader.seq { decodeTransportAddr(it) })
}

private fun decodeTransportAddr(reader: BinaryReader): TransportAddr = when (val tag = reader.u8()) {
    ADDR_TAG_RELAY -> TransportAddr.Relay(RelayUrl.trusted(reader.string()))
    ADDR_TAG_IP -> TransportAddr.Ip(SocketAddr.trusted(reader.string()))
    // Left-to-right argument evaluation is what keeps these two reads in the encoded order.
    ADDR_TAG_CUSTOM -> TransportAddr.Custom(CustomAddr(reader.i64(), reader.bytes()))
    ADDR_TAG_UNKNOWN -> TransportAddr.Unknown(reader.string())
    else -> error("Malformed address payload: unknown transport address tag $tag")
}

// ── The endpoint domain's FFI surface, implemented per facade ──────────────────────────────────
//
// Names are prefixed `nativeEndpoint` so each domain's expect declarations stay distinct in this
// shared package. Handles travel as `Long`: the JVM has no pointer type, and the native facade
// converts back with `toCPointer`.

internal expect fun nativeEndpointNew(): Long

internal expect fun nativeEndpointFree(handle: Long)

internal expect fun nativeEndpointLiveHandleCount(): Long

internal expect fun nativeEndpointId(handle: Long): ByteArray

internal expect fun nativeEndpointAddr(handle: Long): ByteArray

internal expect fun nativeEndpointSecretKey(handle: Long): ByteArray

internal expect fun nativeEndpointSetAlpns(handle: Long, payload: ByteArray)

internal expect fun nativeEndpointAddEndpointAddr(handle: Long, payload: ByteArray)

internal expect fun nativeEndpointRemoveEndpointAddr(handle: Long, id: ByteArray): Boolean

internal expect fun nativeEndpointBoundSockets(handle: Long): ByteArray

internal expect fun nativeEndpointIsClosed(handle: Long): Boolean

internal expect fun nativeEndpointStats(handle: Long): ByteArray

internal expect suspend fun nativeEndpointBind(handle: Long, payload: ByteArray)

internal expect suspend fun nativeEndpointShutdown(handle: Long)

internal expect suspend fun nativeEndpointOnline(handle: Long)

internal expect suspend fun nativeEndpointRemoteAddr(handle: Long, id: ByteArray): ByteArray

internal expect suspend fun nativeEndpointAddExternalAddr(handle: Long, addr: String)

internal expect suspend fun nativeEndpointRemoveExternalAddr(handle: Long, addr: String): Boolean

internal expect suspend fun nativeEndpointInsertRelay(handle: Long, payload: ByteArray): ByteArray

internal expect suspend fun nativeEndpointRemoveRelay(handle: Long, url: String): ByteArray

internal expect suspend fun nativeEndpointNetworkChange(handle: Long)
