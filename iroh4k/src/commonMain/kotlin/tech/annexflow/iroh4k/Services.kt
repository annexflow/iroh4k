package tech.annexflow.iroh4k

import tech.annexflow.iroh4k.internal.BinaryWriter
import tech.annexflow.iroh4k.internal.NativeHandle

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import tech.annexflow.iroh4k.internal.BinaryReader

/**
 * The [ServicesClient]: naming an endpoint, checking liveness, pushing metrics and reporting network
 * diagnostics to [services.iroh.computer](https://services.iroh.computer).
 *
 * Everything here talks to n0's hosted service over an [Endpoint], so this is the one domain in
 * iroh4k that **cannot work offline**. Read that as a constraint on what the client is for, not as a
 * warning: the client is a monitoring sidecar for an endpoint that is already doing real work, and
 * an endpoint keeps working whether or not its services client can reach the service.
 *
 * ## Getting a credential
 *
 * A client needs to prove who it is, and the service needs to know where to send it. Both come from
 * a [ServicesCredential]:
 *
 * - [ServicesCredential.ApiSecret] and [ServicesCredential.ApiSecretFromEnv] carry an encoded
 *   `services…` secret from a services.iroh.computer project. The secret **includes the service
 *   endpoint to dial**, so nothing else is needed.
 * - [ServicesCredential.SshKey] and [ServicesCredential.SshKeyFile] authenticate as a project owner
 *   with a registered SSH key, and grant the full capability set. They carry no remote, so
 *   [ServicesConfig.remote] must be supplied alongside them or [ServicesClient.create] fails.
 *
 * Exactly one credential is supplied, because [ServicesCredential] is a sealed hierarchy and
 * [ServicesConfig.credential] is a single non-null field. That is a deliberate improvement on
 * iroh-ffi, whose `ServicesOptions` has three independent nullable credential fields and counts them
 * at runtime — so "no credential" and "two credentials" are errors it can only report after the
 * fact, and errors a caller of iroh4k cannot write.
 *
 * ## Creating a client does not touch the network
 *
 * [ServicesClient.create] validates the credential, wraps the endpoint in a lazily-dialled
 * connection and starts the client's background actor. It performs **no I/O** and returns in
 * microseconds, so a successful `create` says nothing about whether the service is reachable — that
 * is what [ping] is for. A client created with a well-formed but wrong secret, or with no network at
 * all, is a perfectly good object whose every operation then fails with [IrohError.Code.Services].
 *
 * ## Lifecycle
 *
 * A client holds a native handle and a background task that pushes metrics on an interval, so it
 * must be released with [close] or a `use { }` block. Closing it stops the pushes; letting it leak
 * keeps them going for the life of the process.
 *
 * Note that a client holds its **own** reference to the endpoint's sockets, exactly as upstream's
 * `Client` does. Closing the [Endpoint] while a client is alive therefore does not tear the endpoint
 * down — close the client too.
 */

// ── Credentials ───────────────────────────────────────────────────────────────────────────────

/**
 * How a [ServicesClient] proves who it is. Exactly one of these, by construction.
 *
 * The **ordinals** of the subclasses are the wire protocol shared with `services.rs`'s
 * `read_client_config`, as [EndpointPreset]'s are with `builder_for`: do not renumber, append only.
 */
sealed interface ServicesCredential {

    /**
     * An encoded API secret, the `services…` string a services.iroh.computer project issues.
     *
     * Sets both the capability and the service endpoint to dial, so a client needs nothing else.
     *
     * This is a credential. It is never rendered by [toString] and never quoted in an error message,
     * for the reason [SecretKey.toString] renders nothing: a `toString` in a log line is how secrets
     * escape.
     */
    class ApiSecret(val encoded: String) : ServicesCredential {
        override fun toString(): String = "ApiSecret(..)"
    }

    /**
     * Read the API secret from the `IROH_SERVICES_API_SECRET` environment variable.
     *
     * The variable is read **in Rust at create time**, not here, so this works identically on every
     * target — including Kotlin/Native, which has no portable `System.getenv`. An unset or
     * unparseable variable fails with [IrohError.Code.Services].
     */
    data object ApiSecretFromEnv : ServicesCredential

    /**
     * An unencrypted PEM-encoded OpenSSH ed25519 private key registered with the project.
     *
     * Grants the **full** capability set rather than the client subset an API secret grants, so this
     * is the credential for node operators and project owners. It carries no service endpoint:
     * supply [ServicesConfig.remote] as well, or creating the client fails with "Missing remote
     * endpoint to dial".
     */
    class SshKey(val pem: String) : ServicesCredential {
        override fun toString(): String = "SshKey(..)"
    }

    /**
     * A path to a file holding such a key — typically `~/.ssh/id_ed25519`.
     *
     * The file is read by Rust, which is why this exists at all: `commonMain` has no filesystem API,
     * and iroh-ffi omits upstream's `ssh_key_from_file` entirely. As with [SshKey], supply
     * [ServicesConfig.remote] too.
     */
    class SshKeyFile(val path: String) : ServicesCredential
}

/**
 * How often a client pushes metrics without being asked.
 *
 * Three cases rather than iroh-ffi's `metricsIntervalMs: Long?` with `0` meaning "off": leaving the
 * upstream default alone and asking for a specific interval are different requests, and only one of
 * them survives a change to that default. The same distinction [EndpointConfig.relayMode] draws
 * between `null` and [RelayMode.Disabled].
 */
sealed interface MetricsPush {

    /** Whatever iroh-services defaults to, which is every 60 seconds at the time of writing. */
    data object Default : MetricsPush

    /**
     * Push on a fixed interval.
     *
     * Must be at least 1 millisecond: upstream hands the value straight to a tokio interval timer,
     * which rejects zero, so Rust reports anything shorter as [IrohError.Code.Services] rather than
     * letting it reach the timer.
     */
    class Every(val interval: Duration) : MetricsPush {
        override fun toString(): String = "Every($interval)"
    }

    /**
     * Never push automatically. [ServicesClient.pushMetrics] still works, which is the point: a
     * caller that wants to control exactly when its endpoint is observed uses this plus explicit
     * pushes.
     */
    data object Disabled : MetricsPush
}

/**
 * Everything [ServicesClient.create] can be told, as one immutable value.
 *
 * `ClientBuilder::build()` consumes its builder upstream, so — exactly as with [EndpointConfig] and
 * for the same reasons — the configuration is assembled here as a value, crosses the boundary once,
 * and the builder is created and consumed inside a single Rust operation. See the header of
 * `services.rs`.
 *
 * @property credential how the client authenticates. The only required field.
 * @property name a human-readable label for this endpoint, registered with the service so its
 *   metrics are identifiable — a machine name, a user id. Must be **2 to 128 UTF-8 bytes**; a name
 *   outside that range is rejected by [create] with [IrohError.Code.Services]. Uniqueness is *not*
 *   enforced service-side, so two endpoints may share a name. Note that upstream sends the name from
 *   the client's background actor and only *logs* a failure, so a name set here is best-effort;
 *   [ServicesClient.setName] is the version that reports whether it worked.
 * @property remote the service endpoint to dial. `null` uses the one inside an API secret, which is
 *   the usual case. Required with an SSH-key credential, and may be set alongside an API secret to
 *   point it at a different deployment. iroh-ffi exposes no equivalent, which is why an SSH-key
 *   client is impossible to create through it.
 * @property metricsPush how often metrics are pushed unprompted.
 */
class ServicesConfig(
    val credential: ServicesCredential,
    val name: String? = null,
    val remote: EndpointAddr? = null,
    val metricsPush: MetricsPush = MetricsPush.Default,
)

/**
 * A capability a services client can grant to another endpoint with
 * [ServicesClient.grantCapability].
 *
 * Each entry carries the canonical string iroh-services itself uses, and that string — not an
 * ordinal — is what crosses the boundary, so the capability vocabulary has exactly one definition
 * (upstream's `Cap`) and a capability added in a later iroh-services needs no new wire tag here. The
 * set of names this build understands is asserted against Rust's own list in `CommonServicesTests`.
 */
enum class ServicesCapability(internal val canonical: String) {

    /** Everything. Only granted from a credential registered with the service — an SSH key. */
    All("all"),

    /** The capability set an API secret grants: relay use, metrics push, diagnostics. */
    Client("client"),

    /** Use the project's relays. */
    RelayUse("relay:use"),

    /** Push metrics for any endpoint. */
    MetricsPutAny("metrics:put-any"),

    /** Upload a network diagnostics report for any endpoint. */
    NetDiagnosticsPutAny("net-diagnostics:put-any"),

    /** Read a network diagnostics report for any endpoint. */
    NetDiagnosticsGetAny("net-diagnostics:get-any"),
    ;

    override fun toString(): String = canonical
}

// ── Values the client reports ─────────────────────────────────────────────────────────────────

/**
 * The service's answer to a [ServicesClient.ping].
 *
 * Carries the 16-byte request id the client generated and the service echoed back. iroh-ffi discards
 * it (`map(|_| ())`); it is handed over here because it is the only content this protocol message
 * has and dropping data at the binding layer is how a binding becomes less useful than the library
 * it wraps.
 *
 * Be clear about what it is good for: the request id is generated *inside* Rust, so a caller never
 * sees the outgoing value and cannot use this to match a response to a request. What it does give is
 * evidence that the bytes came back from a service that echoed them, and something to log.
 */
class Pong internal constructor(private val bytes: ByteArray) {

    /** The 16 raw request-id bytes, as a fresh copy. */
    fun requestId(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Pong && other.bytes.contentEquals(bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    /** The request id in lowercase hex, as [Signature] renders itself. */
    override fun toString(): String = bytes.joinToString("") {
        val v = it.toInt() and 0xFF
        "${HEX[v shr 4]}${HEX[v and 0xF]}"
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}

/** Which protocol measured a relay's latency in a [NetReport]. */
enum class LatencyProbe {
    /** An empty HTTPS `GET` against the relay. */
    Https,

    /** QUIC address discovery over IPv4. */
    QuicIpv4,

    /** QUIC address discovery over IPv6. */
    QuicIpv6,

    /**
     * A probe kind this build of iroh4k does not know.
     *
     * iroh's `Probe` is `#[non_exhaustive]`, so a newer iroh can measure latency a way this build
     * has no name for. The measurement is still reported rather than dropped — see
     * [TransportAddr.Unknown] for the same choice in the addressing domain.
     */
    Unknown,
}

/**
 * One relay's measured latency.
 *
 * @property probe which protocol produced the measurement. The same relay can appear more than once
 *   in [NetReport.relayLatencies], with one entry per probe that reached it.
 */
data class RelayLatency(val probe: LatencyProbe, val url: RelayUrl, val latency: Duration)

/**
 * Port-mapping protocol availability on the local network.
 *
 * All three are probed together; `false` means the router did not answer that protocol, not that the
 * probe failed. A probe that could not run at all is reported as a `null`
 * [DiagnosticsReport.portMapProbe] instead.
 */
data class PortMapProbe(val upnp: Boolean, val pcp: Boolean, val natPmp: Boolean)

/**
 * iroh's own view of the network this endpoint is on.
 *
 * The full contents of `iroh::unstable_net_report::NetReport`. iroh-ffi reduces this to a single
 * `hasNetReport: Boolean` and tells the caller to read the dashboard for the rest; iroh4k reports it,
 * because it is the part of a diagnostics report that actually explains a connectivity problem.
 *
 * **The shape is unstable.** Upstream gates this type behind an `unstable-net-report` feature and
 * documents it as exempt from semantic versioning, and it is `#[non_exhaustive]`, so a future iroh
 * may add fields that do not appear here. Every field that exists today is present.
 *
 * @property udpIpv4 an IPv4 QUIC address-discovery round trip completed — plain UDP works outbound.
 * @property udpIpv6 the same over IPv6.
 * @property mappingVariesByDestIpv4 whether the public address this endpoint appears to have differs
 *   depending on which server it probes — a hard NAT, and a reason hole punching may fail. `null`
 *   when too few probes completed to tell, which is genuinely different from `false`.
 * @property mappingVariesByDestIpv6 the same over IPv6.
 * @property captivePortal whether something is intercepting HTTP, e.g. a hotel network's sign-in
 *   page. `null` when the check did not run — it is off in the minimal net-report configuration.
 * @property preferredRelay the relay with the lowest measured latency, which is the one the endpoint
 *   will make its home relay.
 * @property globalIpv4 the public IPv4 address and port the probes saw for this endpoint.
 * @property globalIpv6 the same over IPv6.
 * @property relayLatencies every relay latency measured, in iroh's own order: HTTPS probes first,
 *   then IPv4 QUIC, then IPv6 QUIC, each group sorted by relay URL.
 */
class NetReport internal constructor(
    val udpIpv4: Boolean,
    val udpIpv6: Boolean,
    val mappingVariesByDestIpv4: Boolean?,
    val mappingVariesByDestIpv6: Boolean?,
    val captivePortal: Boolean?,
    val preferredRelay: RelayUrl?,
    val globalIpv4: SocketAddr?,
    val globalIpv6: SocketAddr?,
    val relayLatencies: List<RelayLatency>,
) {
    /** Whether UDP works at all, over either family — upstream's `has_udp`. */
    val hasUdp: Boolean get() = udpIpv4 || udpIpv6

    /**
     * Whether the public address varies by destination over either family, or `null` if neither
     * family could tell. Upstream's `mapping_varies_by_dest`.
     */
    val mappingVariesByDest: Boolean?
        get() = when {
            mappingVariesByDestIpv4 == null && mappingVariesByDestIpv6 == null -> null
            else -> mappingVariesByDestIpv4 == true || mappingVariesByDestIpv6 == true
        }

    override fun toString(): String =
        "NetReport(udpIpv4=$udpIpv4, udpIpv6=$udpIpv6, preferredRelay=$preferredRelay, " +
            "relayLatencies=${relayLatencies.size})"
}

/**
 * A full network diagnostics report, as produced by [ServicesClient.netDiagnostics].
 *
 * Named as upstream names it. iroh-ffi calls its version a `DiagnosticsSummary`, honestly, because it
 * is one; this is the report.
 *
 * @property endpointId the endpoint the report is about.
 * @property directAddrs the addresses the endpoint believes it is directly reachable on.
 * @property netReport iroh's own network view, or `null` if no report was available even partially.
 * @property portMapProbe UPnP/PCP/NAT-PMP availability, or `null` if the probe failed or timed out.
 * @property irohVersion the iroh version iroh-services was built against.
 * @property irohServicesVersion the iroh-services version that produced the report.
 */
class DiagnosticsReport internal constructor(
    val endpointId: EndpointId,
    val directAddrs: List<SocketAddr>,
    val netReport: NetReport?,
    val portMapProbe: PortMapProbe?,
    val irohVersion: String,
    val irohServicesVersion: String,
) {
    override fun toString(): String =
        "DiagnosticsReport($endpointId, directAddrs=${directAddrs.size}, " +
            "netReport=${netReport != null}, portMapProbe=$portMapProbe, " +
            "iroh=$irohVersion, irohServices=$irohServicesVersion)"
}

// ── The client ────────────────────────────────────────────────────────────────────────────────

/**
 * A live iroh-services client for one [Endpoint].
 *
 * Create one with [ServicesClient.create]. It holds native resources and a background task, so it
 * must be released — with [close], or by a `use { }` block.
 *
 * **Thread-safe.** Every method may be called concurrently from any thread or coroutine, including
 * concurrently with [close]; a released handle is never dereferenced, so racing a call against
 * [close] produces [IrohError] with [IrohError.Code.Closed] rather than a crash. The guard is the
 * same design [Endpoint] uses, for the same reason.
 *
 * **Cancellable.** Every `suspend` member aborts its Rust task when the calling coroutine is
 * cancelled. [netDiagnostics] is why that matters here: it can take more than 20 seconds even when
 * everything succeeds, so cancellation is the only way to bound it.
 *
 * **Every operation needs the service.** [name] is the sole exception — it reads what this client
 * last set, from its own actor. Everything else fails with [IrohError.Code.Services] when the service
 * cannot be reached or refuses the client's capability.
 */
class ServicesClient private constructor(private val guard: NativeHandle) : AutoCloseable {

    // ── Naming ───────────────────────────────────────────────────────────────────────────────

    /**
     * The name this client last registered for its endpoint, or `null` if it never has.
     *
     * A **local** read: it asks the client's own actor, not the service, so it answers without a
     * network round trip and reflects only what this client set — through [ServicesConfig.name] or a
     * successful [setName]. It is not a way to discover a name some other client registered.
     */
    suspend fun name(): String? = suspending { handle ->
        BinaryReader(nativeServicesName(handle)).optString()
    }

    /**
     * Registers [name] for this endpoint service-side, so its metrics are identifiable.
     *
     * Must be **2 to 128 UTF-8 bytes** — note bytes, not characters, so a name of two emoji is eight
     * bytes and a name of one is too short. Uniqueness is not enforced service-side.
     *
     * The reporting counterpart of [ServicesConfig.name], which upstream sends from the background
     * actor and only logs a failure for. Use this when it matters whether the name arrived.
     *
     * @throws IrohError with [IrohError.Code.Services] if the name is out of range, or if the service
     *   is unreachable or refuses it.
     */
    suspend fun setName(name: String) = suspending {
        // The name crosses as UTF-8 bytes rather than as a platform string, so the byte length Rust
        // validates is exactly the one encoded here.
        nativeServicesSetName(it, name.encodeToByteArray())
    }

    // ── Liveness ─────────────────────────────────────────────────────────────────────────────

    /**
     * Pings the service, returning the request id it echoed.
     *
     * The way to find out whether a client created by [create] can actually reach the service:
     * `create` performs no I/O, so this is the first call that proves anything.
     *
     * @throws IrohError with [IrohError.Code.Services] if the service cannot be reached, or refuses
     *   the client's capability.
     */
    suspend fun ping(): Pong = suspending { Pong(nativeServicesPing(it)) }

    // ── Metrics ──────────────────────────────────────────────────────────────────────────────

    /**
     * Pushes a metrics snapshot to the service now.
     *
     * Unnecessary with a non-zero [MetricsPush] interval, which pushes on its own; this is for
     * flushing before a shutdown, or for a client configured with [MetricsPush.Disabled] that pushes
     * only when it chooses to.
     *
     * The metrics pushed are the endpoint's own — the same set [Endpoint.stats] reports. Registering
     * an application's metrics group alongside them is upstream's `register_metrics_group`, which
     * takes a Rust trait object and so has no Kotlin equivalent.
     *
     * @throws IrohError with [IrohError.Code.Services] if the push fails.
     */
    suspend fun pushMetrics() = suspending { nativeServicesPushMetrics(it) }

    // ── Capabilities ─────────────────────────────────────────────────────────────────────────

    /**
     * Grants [capabilities] to the endpoint [grantee], as a token the service stores for it.
     *
     * This client signs the token with its endpoint's secret key; [grantee] presents it when it dials
     * the service, and is then authorised for exactly these capabilities. Tokens expire after 30
     * days, which is upstream's fixed default.
     *
     * Only a client whose own credential permits it can grant — an API secret's [ServicesCapability.Client]
     * set cannot grant [ServicesCapability.All]. iroh-ffi does not expose this operation at all.
     *
     * @throws IrohError with [IrohError.Code.Services] if [capabilities] is empty, or if the service
     *   is unreachable or refuses the grant.
     */
    suspend fun grantCapability(grantee: EndpointId, capabilities: Set<ServicesCapability>) =
        suspending { handle ->
            val w = BinaryWriter()
            w.bytes(grantee.toBytes())
            // Sorted so the payload is deterministic for a given set, whatever iteration order the
            // caller's `Set` implementation happens to have.
            val names = capabilities.map { it.canonical }.sorted()
            w.i32(names.size)
            for (name in names) w.string(name)
            nativeServicesGrantCapability(handle, w.finish())
        }

    // ── Diagnostics ──────────────────────────────────────────────────────────────────────────

    /**
     * Runs a full network diagnostics pass on this client's endpoint.
     *
     * **Slow, and it touches the network regardless of [send].** Upstream waits up to 10 seconds for
     * a home relay, up to another 10 for a net report, and then probes the local network for UPnP,
     * PCP and NAT-PMP with a 5-second budget — so more than 20 seconds is the *normal* duration, not
     * a failure mode. Wrap it in `withTimeout { }`, or run it somewhere a long suspension is fine.
     *
     * @param send also upload the report to the service, where it is stored for the project. `false`
     *   keeps it entirely local: the probing still happens, but nothing is sent to n0. Defaults to
     *   `false` so a caller has to ask before their network topology leaves the machine — iroh-ffi's
     *   equivalent has no default.
     * @throws IrohError with [IrohError.Code.Services] if the report cannot be produced, or if
     *   uploading it fails.
     */
    suspend fun netDiagnostics(send: Boolean = false): DiagnosticsReport = suspending { handle ->
        decodeDiagnosticsReport(BinaryReader(nativeServicesNetDiagnostics(handle, send)))
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────

    /**
     * Whether [close] has released this client's handle, after which every other member raises
     * [IrohError] with [IrohError.Code.Closed].
     *
     * The one member that answers instead of raising. A snapshot: another thread may release the
     * client between this read and the next call, which is why the guard checks again on every call
     * rather than trusting a prior look at this flag.
     */
    val isReleased: Boolean get() = guard.isReleased

    /**
     * Releases this client's native handle, aborting its metrics actor.
     *
     * Idempotent, and safe to call while other threads are using the client: the handle is freed only
     * once every in-flight call has returned, and any call arriving after this point raises
     * [IrohError] with [IrohError.Code.Closed] instead of touching freed memory.
     *
     * This is what stops the automatic metric pushes. A `suspend` call still in flight keeps the
     * handle alive until it finishes, so a client closed during a [netDiagnostics] is released when
     * that coroutine is cancelled — cancelling the scope, rather than only closing the client, is
     * what makes the release prompt.
     */
    override fun close() {
        guard.close()
    }

    override fun toString(): String =
        if (isReleased) "ServicesClient(released)" else "ServicesClient()"

    // ── The guard ────────────────────────────────────────────────────────────────────────────

    /**
     * Runs a suspending native call against a handle that cannot be freed underneath it.
     *
     * Holding the guard across the suspension is what makes cancelling a long operation safe: the
     * handle stays valid until the Rust task has actually been aborted and the call has returned.
     */
    private suspend fun <T> suspending(block: suspend (Long) -> T): T =
        guard.useSuspending(block)

    companion object {
        /**
         * Creates a services client for [endpoint], configured by [config].
         *
         * Performs **no network I/O** — see the file header. It validates the credential, reads an
         * SSH key file if one was named, and starts the client's background actor; the first packet
         * leaves on the first operation or the first metrics tick. A client therefore comes back even
         * when the service is unreachable, and [ping] is what tells you whether it is.
         *
         * Cancellable: cancelling the calling coroutine releases anything the create had already
         * built, so a cancelled create leaks neither a handle nor a running actor.
         *
         * @throws IrohError with [IrohError.Code.Services] for a credential that cannot be used (a
         *   malformed API secret, an unset environment variable, an unreadable key file), a name
         *   outside 2..128 bytes, a [MetricsPush.Every] interval under 1ms, or a configuration with
         *   no service endpoint to dial. A bad piece of [ServicesConfig.remote] reports the code that
         *   fits it instead: [IrohError.Code.Key] for its endpoint id, [IrohError.Code.Addr] for a
         *   socket address, [IrohError.Code.Relay] for a relay URL.
         * @throws IrohError with [IrohError.Code.Closed] if [endpoint] has already been closed.
         */
        suspend fun create(endpoint: Endpoint, config: ServicesConfig): ServicesClient {
            // The handle is created *before* the build, so Kotlin owns it no matter how the build
            // ends — the same argument `Endpoint.bind` makes. See `ClientSlot` in `services.rs`: had
            // the handle come back from the build, a cancellation landing in the instant it succeeded
            // would strand a tokio actor pushing metrics every minute with nothing left to abort it.
            val client = ServicesClient(NativeHandle(nativeServicesNew(), "services client", ::nativeServicesFree))
            try {
                // The endpoint's own guard is held for the whole build, so the endpoint cannot be
                // released between the handle being read and Rust cloning the `iroh::Endpoint` out
                // of it.
                endpoint.withHandle { endpointHandle ->
                    client.suspending {
                        nativeServicesBuild(it, endpointHandle, encodeConfig(config))
                    }
                }
            } catch (failure: Throwable) {
                // Covers cancellation too, which is the case that matters: the Rust task may have
                // finished building and filled the slot in, and this is what drops it.
                client.close()
                throw failure
            }
            return client
        }

        /**
         * The capability strings the linked Rust build understands.
         *
         * Exposed for tests: [ServicesCapability]'s canonical names must be exactly this set, or a
         * grant would be rejected by iroh-services with an "unknown capability" that no Kotlin-side
         * test would have caught. One of the few properties of this domain that can be checked
         * without a live service.
         */
        internal val capabilityNames: List<String>
            get() = BinaryReader(nativeServicesCapabilityNames()).seq { it.string() }

        /**
         * Services-client handles still alive in Rust.
         *
         * Exposed for tests: it must return to its baseline after clients are closed, which is how a
         * handle leak — and with it a leaked metrics actor — would be caught. The op-registry
         * counterpart is [Iroh4k.liveOpCount].
         */
        internal val liveHandleCount: Long get() = nativeServicesLiveHandleCount()
    }
}


// ── The services codec ────────────────────────────────────────────────────────────────────────
//
// The layout is defined in `services.rs`, which documents it in full; this is its Kotlin mirror and
// the two must be changed together. The transport-address tags are the same numbers `Addr.kt` and
// `Endpoint.kt` use, deliberately: an `EndpointAddr` has one payload shape across the binding.

private const val ADDR_TAG_RELAY = 0
private const val ADDR_TAG_IP = 1
private const val ADDR_TAG_CUSTOM = 2
private const val ADDR_TAG_UNKNOWN = 3

/** Credential tags, matching `ServicesCredential`'s order in `services.rs`. */
private const val CRED_API_SECRET = 0
private const val CRED_API_SECRET_FROM_ENV = 1
private const val CRED_SSH_KEY = 2
private const val CRED_SSH_KEY_FILE = 3

/** Metrics-push tags. */
private const val METRICS_DEFAULT = 0
private const val METRICS_EVERY = 1
private const val METRICS_DISABLED = 2

/** Discriminators for an optional record. */
private const val ABSENT = 0
private const val PRESENT = 1

/** Discriminators for an `Option<bool>`. */
private const val TRI_UNKNOWN = 0
private const val TRI_FALSE = 1
private const val TRI_TRUE = 2

/** Latency probe tags. */
private const val PROBE_HTTPS = 0
private const val PROBE_QUIC_IPV4 = 1
private const val PROBE_QUIC_IPV6 = 2


/** Encodes a [ServicesConfig] as the payload `services.rs`'s `read_client_config` expects. */
private fun encodeConfig(config: ServicesConfig): ByteArray {
    val w = BinaryWriter()

    when (val credential = config.credential) {
        is ServicesCredential.ApiSecret -> {
            w.u8(CRED_API_SECRET)
            w.string(credential.encoded)
        }

        ServicesCredential.ApiSecretFromEnv -> w.u8(CRED_API_SECRET_FROM_ENV)

        is ServicesCredential.SshKey -> {
            w.u8(CRED_SSH_KEY)
            w.string(credential.pem)
        }

        is ServicesCredential.SshKeyFile -> {
            w.u8(CRED_SSH_KEY_FILE)
            w.string(credential.path)
        }
    }

    w.optString(config.name)

    val remote = config.remote
    if (remote == null) {
        w.u8(ABSENT)
    } else {
        w.u8(PRESENT)
        // Inline, matching what `services.rs` reads — see `BinaryWriter.writeEndpointAddr`.
        w.writeEndpointAddr(remote)
    }

    when (val push = config.metricsPush) {
        MetricsPush.Default -> w.u8(METRICS_DEFAULT)
        is MetricsPush.Every -> {
            w.u8(METRICS_EVERY)
            // Rust rejects anything below 1ms rather than truncating it, so a sub-millisecond
            // `Duration` is reported instead of silently becoming the zero that panics upstream's
            // interval timer.
            w.i64(push.interval.inWholeMilliseconds)
        }

        MetricsPush.Disabled -> w.u8(METRICS_DISABLED)
    }

    return w.finish()
}

/** Decodes a [DiagnosticsReport] written by `services.rs`'s `write_diagnostics_report`. */
private fun decodeDiagnosticsReport(reader: BinaryReader): DiagnosticsReport {
    // iroh validated the id and canonicalised every address before writing them, so none of these
    // need a second round trip through Rust — see `EndpointId.validated`.
    val endpointId = EndpointId.validated(reader.bytes())
    val directAddrs = reader.seq { SocketAddr.trusted(it.string()) }
    val irohVersion = reader.string()
    val irohServicesVersion = reader.string()
    val netReport = if (reader.u8() == ABSENT) null else decodeNetReport(reader)
    val portMapProbe = if (reader.u8() == ABSENT) {
        null
    } else {
        PortMapProbe(upnp = reader.bool(), pcp = reader.bool(), natPmp = reader.bool())
    }
    return DiagnosticsReport(
        endpointId = endpointId,
        directAddrs = directAddrs,
        netReport = netReport,
        portMapProbe = portMapProbe,
        irohVersion = irohVersion,
        irohServicesVersion = irohServicesVersion,
    )
}

private fun decodeNetReport(reader: BinaryReader): NetReport = NetReport(
    udpIpv4 = reader.bool(),
    udpIpv6 = reader.bool(),
    mappingVariesByDestIpv4 = reader.tri(),
    mappingVariesByDestIpv6 = reader.tri(),
    captivePortal = reader.tri(),
    preferredRelay = reader.optString()?.let { RelayUrl.trusted(it) },
    globalIpv4 = reader.optString()?.let { SocketAddr.trusted(it) },
    globalIpv6 = reader.optString()?.let { SocketAddr.trusted(it) },
    relayLatencies = reader.seq { it.readRelayLatency() },
)

/** Reads an `Option<bool>`: `null` is a real answer here and is not collapsed onto `false`. */
private fun BinaryReader.tri(): Boolean? = when (val tag = u8()) {
    TRI_UNKNOWN -> null
    TRI_FALSE -> false
    TRI_TRUE -> true
    else -> error("Malformed diagnostics payload: unknown tri-state tag $tag")
}

private fun BinaryReader.readRelayLatency(): RelayLatency {
    val probe = when (u8()) {
        PROBE_HTTPS -> LatencyProbe.Https
        PROBE_QUIC_IPV4 -> LatencyProbe.QuicIpv4
        PROBE_QUIC_IPV6 -> LatencyProbe.QuicIpv6
        // Rust's own `PROBE_UNKNOWN`. Anything else lands here too, so a newer Rust core paired with
        // an older Kotlin one degrades to `Unknown` rather than throwing away a measurement it can
        // still report the latency of.
        else -> LatencyProbe.Unknown
    }
    // Left-to-right argument evaluation is what keeps these reads in the encoded order.
    return RelayLatency(probe, RelayUrl.trusted(string()), i64().microseconds)
}

// ── The services domain's FFI surface, implemented per facade ───────────────────────────────────
//
// Names are prefixed `nativeServices` so each domain's expect declarations stay distinct in this
// shared package. Handles travel as `Long`: the JVM has no pointer type, and the native facade
// converts back with `toCPointer`.

internal expect fun nativeServicesNew(): Long

internal expect fun nativeServicesFree(handle: Long)

internal expect fun nativeServicesLiveHandleCount(): Long

internal expect fun nativeServicesCapabilityNames(): ByteArray

internal expect suspend fun nativeServicesBuild(
    handle: Long,
    endpoint: Long,
    payload: ByteArray,
)

internal expect suspend fun nativeServicesName(handle: Long): ByteArray

internal expect suspend fun nativeServicesSetName(handle: Long, name: ByteArray)

internal expect suspend fun nativeServicesPing(handle: Long): ByteArray

internal expect suspend fun nativeServicesPushMetrics(handle: Long)

internal expect suspend fun nativeServicesGrantCapability(handle: Long, payload: ByteArray)

internal expect suspend fun nativeServicesNetDiagnostics(handle: Long, send: Boolean): ByteArray
