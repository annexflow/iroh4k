//! The iroh services client: node naming, liveness, metrics push and network diagnostics.
//!
//! Owned by the services domain. Contains the shared logic plus both facades' exports for it:
//! `#[no_mangle] extern "C"` for cinterop and `#[cfg(not(target_os = "ios"))] Java_*` for JNI.
//!
//! This domain wraps `iroh_services::Client`, which talks to n0's hosted service at
//! services.iroh.computer over an iroh endpoint. It is therefore the first domain in iroh4k whose
//! *successful* paths cannot be exercised without credentials and a network — see the note on
//! testability below, and `CommonServicesTests` for what is verified instead.
//!
//! ## The builder is consumed, so it is not a handle
//!
//! `ClientBuilder::build(self)` takes the builder by value, exactly as `Endpoint`'s
//! `Builder::bind(self)` does. `endpoint.rs` already solved that and this domain repeats the
//! solution rather than inventing a second one: the whole configuration is assembled as a **Kotlin
//! value**, encoded as one [`crate::codec`] payload, and the builder is created, configured and
//! consumed inside a single asynchronous operation. There is no consumed-handle state to model, and
//! no `Mutex<Option<Builder>>` for Kotlin to use after the fact.
//!
//! The credential is the interesting part of that configuration. iroh-ffi's `ServicesOptions` has
//! three independent `Option` fields (`api_secret`, `api_secret_from_env`, `ssh_key_pem`) and
//! counts them at runtime to reject zero or two, so "no credential" and "two credentials" are
//! errors a caller can only discover by trying. iroh4k models the credential as a **tagged union**
//! instead ([`CRED_API_SECRET`] and friends, `ServicesCredential` in Kotlin), so exactly one is
//! structurally guaranteed and those two runtime errors cannot be written.
//!
//! iroh4k also exposes two builder options iroh-ffi omits, both of which it needs:
//!
//! - `remote`, the service endpoint to dial. An API secret carries its own remote, but an SSH-key
//!   credential does not — so in iroh-ffi an `ssh_key_pem` client can only ever fail to build with
//!   `MissingRemote`, with no way to supply the missing piece.
//! - `ssh_key_from_file`, so a caller does not need a filesystem API that Kotlin Multiplatform's
//!   common source set does not have.
//!
//! `register_metrics_group` is deliberately **not** exposed: it takes an `Arc<dyn MetricsGroup>`,
//! which is a Rust trait object with no representation Kotlin could construct. The endpoint's own
//! metrics group is registered by `ClientBuilder::new`, which is what a Kotlin caller has to report
//! on anyway. `rcan` is likewise not exposed: an `Rcan<Caps>` can only be obtained from one of the
//! credential paths already modelled here.
//!
//! ## Diagnostics are modelled, not summarised
//!
//! iroh-ffi projects `DiagnosticsReport` onto a `DiagnosticsSummary` that drops the entire
//! `net_report` — the relay latencies, the UDP reachability, the discovered global addresses — and
//! keeps only `has_net_report: bool`, telling the caller to read the dashboard for the rest. That is
//! the same kind of lossy flattening its address model applies to `TransportAddr::Custom`, and this
//! binding does not repeat it: [`write_diagnostics_report`] encodes the report in full, including
//! every relay latency and both tri-state `Option<bool>` fields.
//!
//! `iroh::unstable_net_report::NetReport` is `#[non_exhaustive]` and explicitly outside iroh's
//! semver guarantees, which is a reason to *say so* in the Kotlin documentation rather than a reason
//! to discard the data. `Probe` is `#[non_exhaustive]` too, so an unrecognised probe kind is
//! surfaced as [`PROBE_UNKNOWN`] with its `Display` text rather than dropped — the same rule
//! `addr.rs` applies to an unknown transport address.
//!
//! ## Codec layout
//!
//! Defined here, mirrored by the encoder/decoder in `Services.kt`; the two must be changed together.
//!
//! ```text
//! ServicesConfig  (Kotlin → Rust)
//!   u8      credential        0 ApiSecret + str encoded secret
//!                             1 ApiSecretFromEnv
//!                             2 SshKey + str PEM
//!                             3 SshKeyFile + str path
//!   str?    name              i32 -1 for "do not name the endpoint"
//!   u8      remote            0 absent, 1 present + EndpointAddr
//!   u8      metrics push      0 upstream default, 1 Every + i64 millis, 2 Disabled
//!
//! EndpointAddr  (both ways)   — the layout `addr.rs` defines and documents
//!   bytes   id
//!   i32     count             then count × TransportAddr
//!                             u8 0 Relay + str; u8 1 Ip + str;
//!                             u8 2 Custom + i64 transport id + bytes data;
//!                             u8 3 Unknown + str Display text (Rust → Kotlin only)
//!
//! Capabilities  (Kotlin → Rust)
//!   bytes   grantee endpoint id
//!   i32     count             then count × str (a canonical `Cap` string, e.g. "metrics:put-any")
//!
//! Name  (Rust → Kotlin)
//!   str?    the name the client last saw, or absent
//!
//! DiagnosticsReport  (Rust → Kotlin)
//!   bytes   endpoint id
//!   i32     direct addr count then count × str (canonical socket address)
//!   str     iroh version
//!   str     iroh-services version
//!   u8      net report        0 absent, 1 present + NetReport
//!   u8      portmap probe     0 absent, 1 present + bool upnp, bool pcp, bool natPmp
//!
//! NetReport
//!   bool    UDP IPv4 round trip completed
//!   bool    UDP IPv6 round trip completed
//!   u8      mapping varies by dest, IPv4    0 unknown, 1 false, 2 true
//!   u8      mapping varies by dest, IPv6    as above
//!   u8      captive portal                  as above
//!   str?    preferred relay URL
//!   str?    discovered global IPv4 socket address
//!   str?    discovered global IPv6 socket address
//!   i32     latency count     then count × (u8 probe, str relay URL, i64 latency in microseconds)
//! ```
//!
//! Failures report the code that fits: [`ERROR_SERVICES`] for anything the services layer itself
//! refuses (a credential, a name, a build, an RPC), [`ERROR_KEY`] / [`ERROR_ADDR`] /
//! [`ERROR_RELAY`] for the pieces of a `remote` address, and [`ERROR_CLOSED`] for a handle whose
//! client is gone. This crate builds with `panic = "abort"`, so a panic on a short buffer or a
//! hostile length prefix would take the host process down — nothing below may ever panic on what
//! Kotlin passes in, which is why the payload reader is fallible everywhere.
//!
//! ## What cannot be verified offline
//!
//! `ClientBuilder::build()` performs **no I/O**: it validates the credential, wraps the endpoint in
//! an `IrohLazyRemoteConnection` and spawns the client actor. A build with a well-formed but fake
//! API secret therefore succeeds in microseconds with no network at all, and every operation that
//! follows fails just as promptly because the fake remote has no reachable address. That is the
//! failure path the test suite exercises. `name`, `set_name`, `ping`, `push_metrics`,
//! `grant_capability` and `net_diagnostics(send = true)` have no offline success path, and the tests
//! do not pretend otherwise.

use std::{
    ffi::{c_int, c_void},
    str::FromStr,
    sync::{
        atomic::{AtomicI64, Ordering},
        Arc, OnceLock,
    },
    time::Duration,
};

use iroh::{
    unstable_net_report::{NetReport, Probe},
    Endpoint, EndpointAddr, EndpointId, RelayUrl,
};
use iroh_services::{
    caps::Cap,
    net_diagnostics::{DiagnosticsReport, PortMapProbe},
    Client, ClientBuilder,
};

use crate::addr::read_endpoint_addr;
use crate::codec::{Reader, Writer};
use crate::core::{
    bytes_result, error_result, ok_result, owned_bytes, Iroh4kPtr, Iroh4kResult, ERROR_ADDR,
    ERROR_CLOSED, ERROR_KEY, ERROR_SERVICES,
};
use crate::handle::{self, Tagged};
use crate::ops::{self, OpResult};

/// The completion callback every async C export takes — see [`crate::ffi`].
type Completion = extern "C" fn(Iroh4kPtr, *mut Iroh4kResult);

// ============================================================================
// Wire protocol constants — shared with `Services.kt`. Append only.
// ============================================================================

/// Credential discriminators, matching Kotlin's `ServicesCredential` subclasses.
const CRED_API_SECRET: u8 = 0;
const CRED_API_SECRET_FROM_ENV: u8 = 1;
const CRED_SSH_KEY: u8 = 2;
const CRED_SSH_KEY_FILE: u8 = 3;

/// Metrics-push discriminators, matching Kotlin's `MetricsPush` subclasses.
///
/// [`METRICS_DEFAULT`] means "do not touch the builder", which leaves upstream's 60-second
/// interval. It is a distinct case from [`METRICS_EVERY`] with 60 000 ms for the same reason
/// `RELAY_MODE_UNSET` is distinct from an explicit mode in `endpoint.rs`: not saying and saying the
/// current default are different requests, and only one of them survives a change upstream.
const METRICS_DEFAULT: u8 = 0;
const METRICS_EVERY: u8 = 1;
const METRICS_DISABLED: u8 = 2;

/// Latency probe discriminators, matching Kotlin's `LatencyProbe` enum.
const PROBE_HTTPS: u8 = 0;
const PROBE_QUIC_IPV4: u8 = 1;
const PROBE_QUIC_IPV6: u8 = 2;
const PROBE_UNKNOWN: u8 = 3;

/// Discriminators for an optional record.
const ABSENT: u8 = 0;
const PRESENT: u8 = 1;

/// Discriminators for an `Option<bool>`, which several `NetReport` fields are: "the probe did not
/// run" and "the probe said no" are genuinely different answers and are not collapsed here.
const TRI_UNKNOWN: u8 = 0;
const TRI_FALSE: u8 = 1;
const TRI_TRUE: u8 = 2;

/// Raw byte length of an endpoint id, fixed by Ed25519.
const ENDPOINT_ID_LEN: usize = 32;

// ============================================================================
// Failures
// ============================================================================

/// A failure together with the error code that fits it.
///
/// As in `endpoint.rs`: a whole configuration crosses in one payload, so reporting everything as
/// `ERROR_SERVICES` would tell a caller with an unparseable relay URL in `remote` only that "the
/// services client failed". Carrying the code with the message keeps `IrohError.Code.Relay` for
/// that, `.Addr` for a socket address and `.Key` for an endpoint id.
struct Failure {
    code: c_int,
    message: String,
}

type Outcome<T> = Result<T, Failure>;

fn fail<T>(code: c_int, message: impl Into<String>) -> Outcome<T> {
    Err(Failure {
        code,
        message: message.into(),
    })
}

/// Adapts a reader/parser error, which carries only a message, to a code.
fn under<T>(code: c_int, outcome: Result<T, String>) -> Outcome<T> {
    outcome.map_err(|message| Failure { code, message })
}

/// The result for a handle that no longer refers to a live services client.
///
/// Kotlin's guard makes this unreachable through the public API; it exists so the boundary answers
/// with an error rather than dereferencing a stale pointer if it ever is.
fn closed() -> *mut Iroh4kResult {
    error_result(
        ERROR_CLOSED,
        "this services client has been released and cannot be used",
    )
}

/// The result for an endpoint handle whose endpoint is gone, or was never bound.
fn endpoint_closed() -> *mut Iroh4kResult {
    error_result(
        ERROR_CLOSED,
        "the endpoint this services client was given has been closed",
    )
}

// ============================================================================
// Payload reader
// ============================================================================

// ============================================================================
// EndpointAddr decoding
// ============================================================================

/// Parses an endpoint id argument.
fn parse_endpoint_id(bytes: &[u8]) -> Outcome<EndpointId> {
    let bytes: [u8; ENDPOINT_ID_LEN] = bytes.try_into().map_err(|_| Failure {
        code: ERROR_KEY,
        message: format!(
            "an endpoint id must be exactly {ENDPOINT_ID_LEN} bytes, got {}",
            bytes.len()
        ),
    })?;
    EndpointId::from_bytes(&bytes).map_err(|error| Failure {
        code: ERROR_KEY,
        message: format!("invalid endpoint id: {error}"),
    })
}

// ============================================================================
// Client configuration
// ============================================================================

/// How the client proves who it is. Exactly one of these, by construction — see the module header.
enum Credential {
    /// An encoded `services…` API secret string.
    ApiSecret(String),
    /// Read the API secret from the `IROH_SERVICES_API_SECRET` environment variable.
    ApiSecretFromEnv,
    /// An unencrypted PEM-encoded OpenSSH ed25519 private key.
    SshKey(String),
    /// A path to a file holding such a key.
    SshKeyFile(String),
}

/// How often metrics are pushed without an explicit `push_metrics` call.
enum MetricsPush {
    /// Leave whatever `ClientBuilder::new` configured — 60 seconds, upstream.
    Preset,
    Every(Duration),
    Disabled,
}

/// The whole of what Kotlin can configure on a services client, decoded from one payload.
struct ClientConfig {
    credential: Credential,
    name: Option<String>,
    remote: Option<EndpointAddr>,
    metrics: MetricsPush,
}

fn read_client_config(payload: &[u8]) -> Outcome<ClientConfig> {
    let mut r = Reader::new(payload);

    let credential = match under(ERROR_SERVICES, r.u8())? {
        CRED_API_SECRET => Credential::ApiSecret(under(ERROR_SERVICES, r.str())?.to_owned()),
        CRED_API_SECRET_FROM_ENV => Credential::ApiSecretFromEnv,
        CRED_SSH_KEY => Credential::SshKey(under(ERROR_SERVICES, r.str())?.to_owned()),
        CRED_SSH_KEY_FILE => Credential::SshKeyFile(under(ERROR_SERVICES, r.str())?.to_owned()),
        other => {
            return fail(
                ERROR_SERVICES,
                format!("malformed services payload: unknown credential tag {other}"),
            )
        }
    };

    let name = under(ERROR_SERVICES, r.opt_str())?.map(str::to_owned);

    let remote = match under(ERROR_ADDR, r.u8())? {
        ABSENT => None,
        PRESENT => Some(under(ERROR_ADDR, read_endpoint_addr(&mut r))?),
        other => {
            return fail(
                ERROR_ADDR,
                format!("malformed services payload: unknown optional-remote tag {other}"),
            )
        }
    };

    let metrics = match under(ERROR_SERVICES, r.u8())? {
        METRICS_DEFAULT => MetricsPush::Preset,
        METRICS_EVERY => {
            let millis = under(ERROR_SERVICES, r.i64())?;
            // Upstream hands this straight to `n0_future::time::interval`, which **panics** on a
            // zero period. With `panic = "abort"` that would take the host process down, so the
            // range check has to happen here rather than being left to the caller.
            if millis <= 0 {
                return fail(
                    ERROR_SERVICES,
                    format!("a metrics push interval must be at least 1ms, got {millis}ms"),
                );
            }
            MetricsPush::Every(Duration::from_millis(millis as u64))
        }
        METRICS_DISABLED => MetricsPush::Disabled,
        other => {
            return fail(
                ERROR_SERVICES,
                format!("malformed services payload: unknown metrics-push tag {other}"),
            )
        }
    };

    under(ERROR_SERVICES, r.finish())?;

    Ok(ClientConfig {
        credential,
        name,
        remote,
        metrics,
    })
}

/// Applies `config` to a fresh builder.
///
/// `async` only because of [`Credential::SshKeyFile`], which reads the key file. Every other arm is
/// pure computation: none of this touches the network, and neither does the `build()` that follows.
async fn configure(endpoint: &Endpoint, config: ClientConfig) -> Outcome<ClientBuilder> {
    let mut builder = ClientBuilder::new(endpoint);

    // Applied first, because an API secret also sets the remote — an explicit `remote` below is
    // then free to override it, which is what lets a caller point a secret at a staging deployment.
    builder = match config.credential {
        Credential::ApiSecret(secret) => builder.api_secret_from_str(&secret).map_err(|error| {
            // The message deliberately does not quote the secret: it is a credential, and an error
            // string ends up in logs. Kotlin's `ServicesCredential.ApiSecret` hides it too.
            Failure {
                code: ERROR_SERVICES,
                message: format!("the api secret could not be decoded: {error}"),
            }
        })?,
        Credential::ApiSecretFromEnv => builder.api_secret_from_env().map_err(|error| Failure {
            code: ERROR_SERVICES,
            message: format!("no usable api secret in the environment: {error}"),
        })?,
        Credential::SshKey(pem) => builder.ssh_key(&pem).map_err(|error| Failure {
            code: ERROR_SERVICES,
            message: format!("the ssh key could not be used: {error}"),
        })?,
        Credential::SshKeyFile(path) => {
            builder
                .ssh_key_from_file(&path)
                .await
                .map_err(|error| Failure {
                    code: ERROR_SERVICES,
                    message: format!("the ssh key file {path:?} could not be used: {error}"),
                })?
        }
    };

    if let Some(name) = config.name {
        builder = builder.name(name).map_err(|error| Failure {
            code: ERROR_SERVICES,
            message: format!("invalid endpoint name: {error}"),
        })?;
    }
    if let Some(remote) = config.remote {
        builder = builder.remote(remote);
    }
    builder = match config.metrics {
        MetricsPush::Preset => builder,
        MetricsPush::Every(interval) => builder.metrics_interval(interval),
        MetricsPush::Disabled => builder.disable_metrics_interval(),
    };

    Ok(builder)
}

// ============================================================================
// The client handle
// ============================================================================
/// The owned reference an async operation carries. `handle::Tagged` wraps the payload so a
/// handle used as the wrong type is rejected rather than projected over; it derefs to the payload,
/// so every use below is unaffected.
type ClientShared = Arc<Tagged<ClientSlot>>;

/// The object a services-client handle points at.
///
/// A *slot* rather than the `Client` itself, for the reason `EndpointSlot` is one in `endpoint.rs`:
/// ownership during the build. [`crate::ops`] delivers a result exactly once, and a `Client` owns an
/// `AbortOnDropHandle` for its actor task — the task that pushes metrics on an interval. Had `build`
/// created the handle on completion, a coroutine cancelled in the instant the build succeeded would
/// leave that actor running with nothing left to abort it, invisible from Kotlin and pushing metrics
/// every 60 seconds for the life of the process.
///
/// `OpResult::with_handle` exists for exactly that race and would cover the [`crate::ops`] half of
/// it, but not the other half: on the cinterop path a continuation that is *already* cancelled when
/// the callback fires discards the result through `iroh4k_free_result`, which releases the result's
/// buffers and knows nothing about handles. Creating the handle **before** the build removes the
/// race rather than narrowing it — Kotlin owns the slot from the start, the operation only fills it
/// in, and Kotlin's `close()`, which it runs on the cancellation path too, is the single release
/// point. The operation holds its own `Arc` clone, so a release racing the fill is a refcount
/// decrement rather than a use-after-free.
struct ClientSlot {
    /// Empty until `build` succeeds, and set at most once: a slot is built by exactly one `build`,
    /// and Kotlin discards it if that fails.
    client: OnceLock<Client>,
}

/// Client slots still alive.
///
/// A test hook in the spirit of `endpoint.rs`'s own counter and [`crate::ops::live_op_count`]. A
/// leaked client is invisible from Kotlin *and* keeps a tokio task pushing metrics, so a test that
/// creates and closes clients in a loop would pass either way without this.
static LIVE_HANDLES: AtomicI64 = AtomicI64::new(0);

impl ClientSlot {
    fn new() -> Self {
        LIVE_HANDLES.fetch_add(1, Ordering::Relaxed);
        Self {
            client: OnceLock::new(),
        }
    }
}

/// Counts the slot down when it is really gone.
///
/// On `Drop` rather than in the free export: freeing a handle drops one `Arc` strong count, and an
/// operation still in flight holds another. What a leak test needs to observe is when the `Client`
/// inside — and with it the actor task it aborts on drop — is actually released, which is here.
impl Drop for ClientSlot {
    fn drop(&mut self) {
        LIVE_HANDLES.fetch_sub(1, Ordering::Relaxed);
    }
}

/// Clones the `Arc` behind a client handle, so it can be moved into a spawned future.
///
/// `None` for a null handle, which the operations report as [`ERROR_CLOSED`].
///
/// # Safety
/// `handle` must be null, or a live handle produced by `iroh4k_services_new` and not yet freed.
/// Kotlin's guard guarantees both: the handle is created before any operation can reference it, and
/// it is released only once every in-flight operation has returned.
unsafe fn slot(handle: *mut c_void) -> Option<ClientShared> {
    if handle.is_null() {
        return None;
    }
    handle::clone_arc::<ClientSlot>(handle)
}

/// Resolves the client inside a slot from within an asynchronous operation.
fn built(slot: &Option<ClientShared>) -> Result<&Client, *mut Iroh4kResult> {
    slot.as_ref()
        .and_then(|slot| slot.client.get())
        .ok_or_else(closed)
}

// ============================================================================
// Shared logic
// ============================================================================

/// Builds the client described by `payload` into `slot`, on `endpoint`.
///
/// The whole configuration is decoded here rather than at the export, because the export returns an
/// operation id and has no result to fail with — every error has to travel through the completion
/// instead.
///
/// `endpoint` is an **owned** clone taken at the export, not a handle: `iroh::Endpoint` is an `Arc`
/// inside, so cloning it is a refcount bump, and it has to be owned because the raw endpoint handle
/// is not `Send` and this future runs on a tokio worker thread. The clone also means the client
/// keeps its endpoint alive independently of Kotlin's `Endpoint`, which is upstream's own behaviour:
/// `Client` holds an `Endpoint` field for diagnostics and connection restarts.
async fn build(
    slot: Option<ClientShared>,
    endpoint: Option<Endpoint>,
    payload: Vec<u8>,
) -> *mut Iroh4kResult {
    let Some(slot) = slot else { return closed() };
    let Some(endpoint) = endpoint else {
        return endpoint_closed();
    };

    let config = match read_client_config(&payload) {
        Ok(config) => config,
        Err(Failure { code, message }) => return error_result(code, message),
    };
    let builder = match configure(&endpoint, config).await {
        Ok(builder) => builder,
        Err(Failure { code, message }) => return error_result(code, message),
    };

    // No I/O happens here. `build` validates that a remote and a capability are present, wraps the
    // endpoint in a lazy connection and spawns the client actor; the first packet is sent by the
    // first operation, or by the metrics interval.
    let client = match builder.build().await {
        Ok(client) => client,
        Err(error) => {
            return error_result(
                ERROR_SERVICES,
                format!("could not create a services client: {error}"),
            )
        }
    };

    if slot.client.set(client).is_err() {
        // Only reachable by building one slot twice, which the Kotlin API cannot express: a slot is
        // created by `ServicesClient.create` and handed to exactly one build operation.
        return error_result(
            ERROR_SERVICES,
            "this services client has already been created",
        );
    }
    ok_result()
}

/// The name the client last saw for its endpoint, as an optional string payload.
///
/// Read from the client actor's own state rather than from the service, so it answers with what
/// *this* client set (or was built with) and `None` if it has never named the endpoint. It is the
/// one operation here that needs no network — upstream's `Client::name` is a channel round trip.
async fn name(client: &Client) -> *mut Iroh4kResult {
    match client.name().await {
        Ok(name) => {
            let mut w = Writer::new();
            w.opt_str(name.as_deref());
            bytes_result(w.finish())
        }
        Err(error) => error_result(
            ERROR_SERVICES,
            format!("could not read the endpoint name: {error}"),
        ),
    }
}

/// Names the endpoint service-side.
async fn set_name(client: &Client, name: String) -> *mut Iroh4kResult {
    match client.set_name(name).await {
        Ok(()) => ok_result(),
        Err(error) => error_result(
            ERROR_SERVICES,
            format!("could not set the endpoint name: {error}"),
        ),
    }
}

/// Pings the service, answering with the request id the service echoed back.
///
/// iroh-ffi discards the `Pong` with `map(|_| ())`. It is 16 bytes of the only payload this protocol
/// message has, so it is handed over rather than thrown away — see `Pong` in `Services.kt` for what
/// it is and is not good for.
async fn ping(client: &Client) -> *mut Iroh4kResult {
    match client.ping().await {
        Ok(pong) => bytes_result(pong.req_id.to_vec()),
        Err(error) => error_result(
            ERROR_SERVICES,
            format!("could not ping the services endpoint: {error}"),
        ),
    }
}

/// Pushes a metrics snapshot immediately.
async fn push_metrics(client: &Client) -> *mut Iroh4kResult {
    match client.push_metrics().await {
        Ok(()) => ok_result(),
        Err(error) => error_result(ERROR_SERVICES, format!("could not push metrics: {error}")),
    }
}

/// Grants `caps` to `grantee`, as a token the service stores on its behalf.
async fn grant_capability(
    client: &Client,
    grantee: EndpointId,
    caps: Vec<Cap>,
) -> *mut Iroh4kResult {
    match client.grant_capability(grantee, caps).await {
        Ok(()) => ok_result(),
        Err(error) => error_result(
            ERROR_SERVICES,
            format!("could not grant capabilities to {grantee}: {error}"),
        ),
    }
}

/// Runs network diagnostics, optionally uploading the report.
///
/// **Slow and not hermetic**, which is why nothing here shortens it: upstream waits up to 10s for a
/// home relay, up to another 10s for a net report, and then probes the LAN for UPnP, PCP and NAT-PMP
/// with a 5s budget. Cancellation is the only way to end it early, which is exactly what
/// [`crate::ops`] provides.
async fn net_diagnostics(client: &Client, send: bool) -> *mut Iroh4kResult {
    match client.net_diagnostics(send).await {
        Ok(report) => {
            let mut w = Writer::new();
            write_diagnostics_report(&mut w, &report);
            bytes_result(w.finish())
        }
        Err(error) => error_result(
            ERROR_SERVICES,
            format!("could not run network diagnostics: {error}"),
        ),
    }
}

/// Reads the grantee id and capability set for [`grant_capability`].
fn read_capabilities(payload: &[u8]) -> Outcome<(EndpointId, Vec<Cap>)> {
    let mut r = Reader::new(payload);
    let grantee = parse_endpoint_id(under(ERROR_KEY, r.bytes())?)?;

    let count = under(ERROR_SERVICES, r.count())?;
    let mut caps = Vec::with_capacity(count);
    for _ in 0..count {
        let text = under(ERROR_SERVICES, r.str())?;
        // Kotlin sends `Cap`'s own canonical strings, produced by upstream's `strum::Display` and
        // parsed by its `FromStr`, so the capability vocabulary has exactly one definition and a
        // capability iroh-services adds later needs no new wire tag.
        caps.push(Cap::from_str(text).map_err(|error| Failure {
            code: ERROR_SERVICES,
            message: format!("unknown capability {text:?}: {error}"),
        })?);
    }
    under(ERROR_SERVICES, r.finish())?;

    if caps.is_empty() {
        // `Caps::new([])` would build an empty capability set and sign a token that permits
        // nothing, which is never what a caller meant by "grant".
        return fail(
            ERROR_SERVICES,
            "granting a capability needs at least one capability",
        );
    }
    Ok((grantee, caps))
}

// ============================================================================
// DiagnosticsReport encoding
// ============================================================================

/// Writes a `DiagnosticsReport` in the layout documented at the top of this module.
fn write_diagnostics_report(w: &mut Writer, report: &DiagnosticsReport) {
    w.bytes(report.endpoint_id.as_bytes());
    w.seq(&report.direct_addrs, |w, addr| {
        w.str(&addr.to_string());
    });
    w.str(&report.iroh_version);
    w.str(&report.iroh_services_version);

    match report.net_report.as_ref() {
        None => {
            w.u8(ABSENT);
        }
        Some(net_report) => {
            w.u8(PRESENT);
            write_net_report(w, net_report);
        }
    }

    match report.portmap_probe.as_ref() {
        None => {
            w.u8(ABSENT);
        }
        Some(probe) => {
            w.u8(PRESENT);
            write_portmap_probe(w, probe);
        }
    }
}

fn write_portmap_probe(w: &mut Writer, probe: &PortMapProbe) {
    w.bool(probe.upnp).bool(probe.pcp).bool(probe.nat_pmp);
}

/// Writes an `iroh::unstable_net_report::NetReport` in full.
///
/// Every field, rather than iroh-ffi's single `has_net_report: bool` — see the module header. The
/// type is `#[non_exhaustive]`, so a future iroh release may add a field that is silently missing
/// here; that is a reason for the Kotlin documentation to say the shape is unstable, not a reason to
/// discard the eight fields that exist.
fn write_net_report(w: &mut Writer, report: &NetReport) {
    w.bool(report.udp_v4).bool(report.udp_v6);
    write_tri(w, report.mapping_varies_by_dest_ipv4);
    write_tri(w, report.mapping_varies_by_dest_ipv6);
    write_tri(w, report.captive_portal);

    w.opt_str(
        report
            .preferred_relay
            .as_ref()
            .map(RelayUrl::to_string)
            .as_deref(),
    );
    w.opt_str(report.global_v4.map(|addr| addr.to_string()).as_deref());
    w.opt_str(report.global_v6.map(|addr| addr.to_string()).as_deref());

    // `RelayLatencies` exposes only `iter()`, and its order is https-then-QAD-v4-then-QAD-v6 with
    // each group in `BTreeMap` order — so Kotlin receives a deterministic sequence without this
    // having to sort it.
    let latencies: Vec<(u8, String, i64)> = report
        .relay_latency
        .iter()
        .map(|(probe, url, latency)| {
            // Microseconds rather than milliseconds: a LAN relay answers in well under 1ms, and
            // rounding that to zero would make the fast case indistinguishable from no measurement.
            // `i64` microseconds covers ±292 000 years, so the saturating cast is unreachable.
            let micros = i64::try_from(latency.as_micros()).unwrap_or(i64::MAX);
            (probe_tag(probe), url.to_string(), micros)
        })
        .collect();
    w.seq(&latencies, |w, (tag, url, micros)| {
        w.u8(*tag).str(url).i64(*micros);
    });
}

/// `Option<bool>` as a three-way tag. "The probe did not run" and "the probe said no" are different
/// answers and are not collapsed onto `false`.
fn write_tri(w: &mut Writer, value: Option<bool>) {
    w.u8(match value {
        None => TRI_UNKNOWN,
        Some(false) => TRI_FALSE,
        Some(true) => TRI_TRUE,
    });
}

/// Maps a latency probe kind onto its wire tag.
///
/// `Probe` is `#[non_exhaustive]`, so a kind this build does not know lands in the catch-all and is
/// reported as [`PROBE_UNKNOWN`] rather than dropping the latency measurement with it — the same
/// rule `addr.rs` applies to an unknown transport address.
fn probe_tag(probe: Probe) -> u8 {
    match probe {
        Probe::Https => PROBE_HTTPS,
        Probe::QadIpv4 => PROBE_QUIC_IPV4,
        Probe::QadIpv6 => PROBE_QUIC_IPV6,
        _ => PROBE_UNKNOWN,
    }
}

/// The canonical capability strings this build understands, as a codec sequence.
///
/// Exposed so Kotlin's `ServicesCapability` enum can be checked against upstream's own vocabulary
/// rather than against a hand-copied list that would drift silently. Every string here round-trips
/// through `Cap`'s `FromStr`/`Display`, which is what [`read_capabilities`] relies on — and it is one
/// of the few things in this domain that *can* be verified without a live service.
fn capability_names() -> Vec<u8> {
    use iroh_services::caps::{MetricsCap, NetDiagnosticsCap, RelayCap};

    let caps = [
        Cap::All,
        Cap::Client,
        Cap::Relay(RelayCap::Use),
        Cap::Metrics(MetricsCap::PutAny),
        Cap::NetDiagnostics(NetDiagnosticsCap::PutAny),
        Cap::NetDiagnostics(NetDiagnosticsCap::GetAny),
    ];
    let mut w = Writer::new();
    w.seq(&caps, |w, cap| {
        w.str(&cap.to_string());
    });
    w.finish()
}

// ============================================================================
// C ABI — Kotlin/Native via cinterop
// ============================================================================

/// The canonical capability strings this build understands, as a codec sequence of strings.
///
/// The only synchronous export in this domain: every services *operation* talks to a remote, but the
/// capability vocabulary is a compile-time property of the linked build.
#[no_mangle]
pub extern "C" fn iroh4k_services_capability_names() -> *mut Iroh4kResult {
    bytes_result(capability_names())
}

/// Creates an unbuilt services-client handle, which `iroh4k_services_build` fills in.
///
/// Never fails, so it returns the handle rather than a result. Kotlin owns it from here and must
/// hand it back to [`iroh4k_services_free`] — including when the build fails or is cancelled, which
/// is the whole reason the handle is created first (see [`ClientSlot`]).
#[no_mangle]
pub extern "C" fn iroh4k_services_new() -> *mut c_void {
    handle::into_handle(ClientSlot::new())
}

/// Client slots still alive. Test hook for asserting handles do not leak.
#[no_mangle]
pub extern "C" fn iroh4k_services_live_handle_count() -> i64 {
    LIVE_HANDLES.load(Ordering::Relaxed)
}

/// Releases a services-client handle, aborting its metrics actor once the last reference goes.
///
/// Tolerates null so Kotlin's `close()` can be idempotent.
///
/// # Safety
/// `handle` must be null, or a handle from [`iroh4k_services_new`] that has not been freed.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_services_free(handle: *mut c_void) {
    handle::free::<ClientSlot>(handle);
}

/// Builds the client described by the configuration payload into `handle`. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract, and `endpoint` must be null or a live handle from
/// `iroh4k_endpoint_new` — the caller holds `Endpoint`'s own guard across this call, so the endpoint
/// cannot be freed before the clone is taken. `payload`/`payload_len` must be null/0 or describe at
/// least `payload_len` readable bytes; they are **copied** before the future is spawned, so the
/// caller may free the buffer as soon as this returns.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_services_build(
    handle: *mut c_void,
    endpoint: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    // Cloned here, on the calling thread, because a raw handle pointer is not `Send` and the future
    // below runs on a tokio worker.
    let endpoint = crate::endpoint::endpoint_clone(endpoint);
    let payload = owned_bytes(payload, payload_len);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(build(slot, endpoint, payload).await)
    })
}

/// The name the client last saw for its endpoint, as an optional string payload. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_services_name(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match built(&slot) {
            Ok(client) => name(client).await,
            Err(result) => result,
        })
    })
}

/// Names the endpoint service-side. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `name`/`name_len` are copied before the future is
/// spawned. The name is taken as a length-counted buffer rather than a C string because it is
/// arbitrary UTF-8 whose *byte* length is what upstream validates.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_services_set_name(
    handle: *mut c_void,
    name: *const u8,
    name_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    let bytes = owned_bytes(name, name_len);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match String::from_utf8(bytes) {
            Err(error) => error_result(
                ERROR_SERVICES,
                format!("an endpoint name must be valid UTF-8: {error}"),
            ),
            Ok(text) => match built(&slot) {
                Ok(client) => set_name(client, text).await,
                Err(result) => result,
            },
        })
    })
}

/// Pings the service, answering with the 16 echoed request-id bytes. Asynchronous.
///
/// # Safety
/// As [`iroh4k_services_name`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_services_ping(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match built(&slot) {
            Ok(client) => ping(client).await,
            Err(result) => result,
        })
    })
}

/// Pushes a metrics snapshot immediately. Asynchronous.
///
/// # Safety
/// As [`iroh4k_services_name`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_services_push_metrics(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match built(&slot) {
            Ok(client) => push_metrics(client).await,
            Err(result) => result,
        })
    })
}

/// Grants capabilities to another endpoint. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `payload`/`payload_len` are copied before the future
/// is spawned.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_services_grant_capability(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    let payload = owned_bytes(payload, payload_len);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match read_capabilities(&payload) {
            Err(Failure { code, message }) => error_result(code, message),
            Ok((grantee, caps)) => match built(&slot) {
                Ok(client) => grant_capability(client, grantee, caps).await,
                Err(result) => result,
            },
        })
    })
}

/// Runs network diagnostics, uploading the report when `send` is non-zero. Asynchronous.
///
/// # Safety
/// As [`iroh4k_services_name`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_services_net_diagnostics(
    handle: *mut c_void,
    send: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    let send = send != 0;
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match built(&slot) {
            Ok(client) => net_diagnostics(client, send).await,
            Err(result) => result,
        })
    })
}

// ============================================================================
// JNI — JVM and Android via the shared library
//
// Unavailable on iOS, which uses the FFI/cinterop path exclusively. Symbol names match the Kotlin
// object `tech.annexflow.iroh4k.ServicesJni`.
//
// Handles travel as `jlong`, the JVM's only pointer-sized integer. Each `*Start` export copies its
// arguments out of the JVM before spawning, then returns an operation id for
// `Iroh4kJni.opAwait`/`opCancel`.
// ============================================================================

#[cfg(not(target_os = "ios"))]
#[allow(non_snake_case)]
mod jni_facade {
    use super::*;
    use jni::objects::{JByteArray, JClass};
    use jni::sys::{jboolean, jbyteArray, jlong};
    use jni::JNIEnv;

    // One shared envelope writer — see `crate::jni::finish`.
    use crate::jni::finish;

    /// Reinterprets a `jlong` handle as the pointer it came from.
    ///
    /// # Safety
    /// `handle` must be `0`, or a value returned by `newHandle` for a handle that has not been
    /// freed. Kotlin's guard is what guarantees that — see [`slot`].
    unsafe fn as_handle(handle: jlong) -> *mut c_void {
        handle as usize as *mut c_void
    }

    /// Copies a Java `byte[]` into an owned `Vec`.
    ///
    /// An unreadable or absent array becomes empty, which the payload reader then rejects as
    /// malformed. That is deliberate: the FFI boundary must never unwind, so a missing argument is
    /// reported like any other malformed one.
    fn arg(env: &mut JNIEnv, array: &JByteArray) -> Vec<u8> {
        env.convert_byte_array(array).unwrap_or_default()
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_newHandle(
        _env: JNIEnv,
        _class: JClass,
    ) -> jlong {
        iroh4k_services_new() as usize as jlong
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_liveHandleCount(
        _env: JNIEnv,
        _class: JClass,
    ) -> jlong {
        LIVE_HANDLES.load(Ordering::Relaxed)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_freeHandle(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<ClientSlot>(as_handle(handle)) };
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_buildStart(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
        endpoint: jlong,
        payload: JByteArray,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let endpoint = unsafe { crate::endpoint::endpoint_clone(as_handle(endpoint)) };
        let payload = arg(&mut env, &payload);
        ops::spawn_channel(async move { OpResult::new(build(slot, endpoint, payload).await) })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_nameStart(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        ops::spawn_channel(async move {
            OpResult::new(match built(&slot) {
                Ok(client) => name(client).await,
                Err(result) => result,
            })
        })
    }

    /// The name arrives as a `byte[]` rather than a `String` so both facades hand Rust the same
    /// UTF-8 bytes, and so the byte length upstream validates is the one Kotlin encoded.
    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_setNameStart(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
        name_bytes: JByteArray,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let bytes = arg(&mut env, &name_bytes);
        ops::spawn_channel(async move {
            OpResult::new(match String::from_utf8(bytes) {
                Err(error) => error_result(
                    ERROR_SERVICES,
                    format!("an endpoint name must be valid UTF-8: {error}"),
                ),
                Ok(text) => match built(&slot) {
                    Ok(client) => set_name(client, text).await,
                    Err(result) => result,
                },
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_pingStart(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        ops::spawn_channel(async move {
            OpResult::new(match built(&slot) {
                Ok(client) => ping(client).await,
                Err(result) => result,
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_pushMetricsStart(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        ops::spawn_channel(async move {
            OpResult::new(match built(&slot) {
                Ok(client) => push_metrics(client).await,
                Err(result) => result,
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_grantCapabilityStart(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
        payload: JByteArray,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let payload = arg(&mut env, &payload);
        ops::spawn_channel(async move {
            OpResult::new(match read_capabilities(&payload) {
                Err(Failure { code, message }) => error_result(code, message),
                Ok((grantee, caps)) => match built(&slot) {
                    Ok(client) => grant_capability(client, grantee, caps).await,
                    Err(result) => result,
                },
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_netDiagnosticsStart(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        send: jboolean,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let send = send != 0;
        ops::spawn_channel(async move {
            OpResult::new(match built(&slot) {
                Ok(client) => net_diagnostics(client, send).await,
                Err(result) => result,
            })
        })
    }

    /// The only synchronous export in this domain: every services *operation* talks to a remote and
    /// so is asynchronous, but the capability vocabulary is a compile-time property of this build.
    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ServicesJni_capabilityNames(
        mut env: JNIEnv,
        _class: JClass,
    ) -> jbyteArray {
        finish(&mut env, bytes_result(capability_names()))
    }
}
