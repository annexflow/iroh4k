//! The `Endpoint`: binding, configuration, outbound connections and lifecycle.
//!
//! Owned by the endpoint domain. Contains the shared logic plus both facades' exports for it:
//! `#[no_mangle] extern "C"` for cinterop and `#[cfg(not(target_os = "ios"))] Java_*` for JNI.
//!
//! This is the first domain with a genuine *handle*: an `Endpoint` owns a socket, a tokio task
//! tree and a QUIC state machine, so it lives in Rust behind [`crate::handle`] and Kotlin holds
//! an opaque pointer with an explicit lifetime. It is also the first to use [`crate::ops`] for
//! real — `bind`, `connect`, `close` and `online` are all cancellable async operations.
//!
//! ## Presets cross as an ordinal, not as an object
//!
//! `Endpoint::builder(preset: impl Preset)` is generic over a *type*, and the four presets
//! (`Empty`, `Minimal`, `N0`, `N0DisableRelay`) are unit structs whose whole content is the code
//! in their `apply`. There is nothing to marshal. iroh-ffi solves this by exporting a callback
//! trait so a foreign language can implement `Preset` itself, which costs a live `EndpointBuilder`
//! object with its own FFI lifetime plus three `apply_*` re-entrancy points.
//!
//! iroh4k does the simple thing instead: Kotlin sends the preset's **ordinal** and
//! [`builder_for`] dispatches to the concrete type. That covers every preset iroh ships, and the
//! things a custom preset would actually set — secret key, ALPNs, relay mode, bind and external
//! addresses — are already first-class fields of the bind configuration, applied *after* the
//! preset so they win. Nothing that can be expressed through iroh-ffi's callback is lost.
//!
//! Note that `Empty` sets no crypto provider, so binding with it always fails with
//! `BindError::InvalidCryptoProvider`. It is offered anyway because it is what the upstream
//! preset is: the baseline for a configuration that sets everything itself. `Minimal` is the
//! smallest preset that binds.
//!
//! ## The builder is consumed, so it is not a handle
//!
//! `Builder::bind(self)` takes the builder by value, so a Kotlin `EndpointBuilder` backed by a
//! Rust handle could not outlive the `bind()` that consumed it — iroh-ffi models exactly that and
//! has to keep the builder in a `Mutex<Option<Builder>>` and `expect("EndpointBuilder consumed")`
//! when it is used after the fact.
//!
//! iroh4k assembles the whole configuration as a **Kotlin value**, encodes it as one
//! [`crate::codec`] payload, and builds *and* binds inside a single asynchronous operation. The
//! builder therefore never exists outside one Rust function, there is no consumed-handle state to
//! model, and configuring an endpoint costs one boundary crossing rather than one per setter.
//!
//! ## Codec layout
//!
//! Defined here, mirrored by the encoder/decoder in `Endpoint.kt`; the two must be changed
//! together.
//!
//! ```text
//! BindConfig  (Kotlin → Rust)
//!   i32     preset            0 Empty, 1 Minimal, 2 N0, 3 N0DisableRelay
//!   bytes?  secret key        32 bytes, or i32 -1 for "generate one"
//!   i32     alpn count        then count × bytes
//!   u8      relay mode        0 leave the preset's, 1 Disabled, 2 Default, 3 Staging,
//!                             4 Custom + i32 count + count × str (relay URL)
//!   i32     bind addr count   then count × str (canonical socket address)
//!   i32     external count    then count × str (canonical socket address)
//!
//! EndpointAddr  (Rust → Kotlin)   — the same layout `addr.rs` documents and writes
//!   bytes   id
//!   i32     count             then count × TransportAddr
//!                             u8 0 Relay   + str; u8 1 Ip + str;
//!                             u8 2 Custom  + i64 transport id + bytes data;
//!                             u8 3 Unknown + str Display text
//!
//! RelayConfig  (both ways)
//!   str     canonical relay URL
//!   i32     QUIC port         -1 for "no QUIC address discovery"
//!   str?    auth token
//!
//! Optional record (remote address, replaced relay config)
//!   u8      1 present then the record, or 0 for absent
//!
//! Stats
//!   i32     count             then count × (str "group:metric", i64 value, str description)
//!
//! Bound sockets / ALPNs
//!   i32     count             then count × str / count × bytes
//! ```
//!
//! Every failure is either malformed caller input or a bind that iroh refused, reported under the
//! code that fits: [`ERROR_BIND`] for the bind itself, [`ERROR_KEY`], [`ERROR_ADDR`] and
//! [`ERROR_RELAY`] for the pieces of a configuration, and [`ERROR_CLOSED`] for a handle whose
//! endpoint is gone. This crate builds with `panic = "abort"`, so a panic on a short buffer or a
//! hostile length prefix would take the host process down — nothing below may ever panic on what
//! Kotlin passes in, which is why the payload reader is fallible everywhere.

use std::{
    ffi::{c_char, c_int, c_void},
    net::SocketAddr,
    str::FromStr,
    sync::{
        atomic::{AtomicI64, Ordering},
        Arc, OnceLock,
    },
};

use iroh::{
    endpoint::{presets, Builder},
    Endpoint, EndpointAddr, EndpointId, RelayConfig, RelayMode, RelayUrl, SecretKey,
};
use iroh_relay::RelayQuicConfig;

use crate::addr::write_endpoint_addr;
use crate::codec::{Reader, Writer};
use crate::core::{
    bytes_result, c_str, error_result, i64_result, ok_result, owned_bytes, Iroh4kPtr, Iroh4kResult,
    ERROR_ADDR, ERROR_BIND, ERROR_CLOSED, ERROR_KEY, ERROR_RELAY,
};
use crate::handle::{self, Tagged};
use crate::ops::{self, OpResult};

/// The completion callback every async C export takes — see [`crate::ffi`].
type Completion = extern "C" fn(Iroh4kPtr, *mut Iroh4kResult);

// ============================================================================
// Wire protocol constants — shared with `Endpoint.kt`. Append only.
// ============================================================================

/// Preset ordinals, matching Kotlin's `EndpointPreset` enum.
const PRESET_EMPTY: i32 = 0;
const PRESET_MINIMAL: i32 = 1;
const PRESET_N0: i32 = 2;
const PRESET_N0_DISABLE_RELAY: i32 = 3;

/// Relay-mode tags inside a bind configuration. [`RELAY_MODE_UNSET`] means the caller did not
/// choose one, leaving whatever the preset configured.
const RELAY_MODE_UNSET: u8 = 0;
const RELAY_MODE_DISABLED: u8 = 1;
const RELAY_MODE_DEFAULT: u8 = 2;
const RELAY_MODE_STAGING: u8 = 3;
const RELAY_MODE_CUSTOM: u8 = 4;

/// Discriminators for an optional record.
const ABSENT: u8 = 0;
const PRESENT: u8 = 1;

/// Raw byte lengths, fixed by Ed25519.
const SECRET_KEY_LEN: usize = 32;
const ENDPOINT_ID_LEN: usize = 32;

// ============================================================================
// Failures
// ============================================================================

/// A failure together with the error code that fits it.
///
/// Binding takes a whole configuration in one crossing, so a single `ERROR_BIND` for everything
/// would tell a caller with a 31-byte secret key only that "bind failed". Carrying the code with
/// the message means Kotlin still gets `IrohError.Code.Key` for that, `.Addr` for an unparseable
/// bind address and `.Relay` for a bad relay URL.
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

/// The result for an endpoint whose handle no longer refers to a bound endpoint.
///
/// Kotlin's closed-flag guard makes this unreachable through the public API; it exists so the
/// boundary answers with an error rather than dereferencing a stale pointer if it ever is.
fn closed() -> *mut Iroh4kResult {
    error_result(
        ERROR_CLOSED,
        "this endpoint has been released and cannot be used",
    )
}

// ============================================================================
// Payload reader
// ============================================================================

// ============================================================================
// EndpointAddr encoding
// ============================================================================

// ============================================================================
// RelayConfig coding
// ============================================================================

/// Writes a `RelayConfig`, modelled as iroh-ffi models it: URL, optional QUIC port, optional auth
/// token. The remaining fields of `iroh_relay::RelayConfig` are `#[non_exhaustive]` internals with
/// no constructor a binding can reach.
fn write_relay_config(w: &mut Writer, config: &RelayConfig) {
    w.str(&config.url.to_string());
    // `-1` rather than an optional string, since a port is a fixed-width scalar. `u16` widens
    // into `i32` losslessly, so the sentinel can never collide with a real port.
    w.i32(config.quic.as_ref().map_or(-1, |quic| i32::from(quic.port)));
    w.opt_str(config.auth_token.as_deref());
}

fn read_relay_config(r: &mut Reader) -> Outcome<RelayConfig> {
    let text = under(ERROR_RELAY, r.str())?;
    let url = RelayUrl::from_str(text).map_err(|error| Failure {
        code: ERROR_RELAY,
        message: format!("could not parse {text:?} as a relay URL: {error}"),
    })?;
    let port = under(ERROR_RELAY, r.i32())?;
    let quic = match port {
        -1 => None,
        port if (0..=i32::from(u16::MAX)).contains(&port) => {
            Some(RelayQuicConfig::new(port as u16))
        }
        other => {
            return fail(
                ERROR_RELAY,
                format!("a relay QUIC port must be 0..=65535 or absent, got {other}"),
            )
        }
    };
    let token = under(ERROR_RELAY, r.opt_str())?;
    let mut config = RelayConfig::new(url, quic);
    if let Some(token) = token {
        config = config.with_auth_token(token);
    }
    Ok(config)
}

/// Encodes an optional relay configuration — the value `insert_relay` and `remove_relay` answer
/// with. `None` also means "the endpoint is closed", which is iroh's own behaviour and not an
/// error it reports.
fn relay_config_payload(config: Option<Arc<RelayConfig>>) -> Vec<u8> {
    let mut w = Writer::new();
    match config {
        None => {
            w.u8(ABSENT);
        }
        Some(config) => {
            w.u8(PRESENT);
            write_relay_config(&mut w, &config);
        }
    }
    w.finish()
}

// ============================================================================
// Bind configuration
// ============================================================================

/// The whole of what Kotlin can configure on an endpoint, decoded from one payload.
struct BindConfig {
    preset: i32,
    secret_key: Option<SecretKey>,
    alpns: Vec<Vec<u8>>,
    relay_mode: Option<RelayMode>,
    bind_addrs: Vec<SocketAddr>,
    external_addrs: Vec<SocketAddr>,
}

fn read_bind_config(payload: &[u8]) -> Outcome<BindConfig> {
    let mut r = Reader::new(payload);

    let preset = under(ERROR_BIND, r.i32())?;

    let secret_key = match under(ERROR_KEY, r.opt_bytes())? {
        None => None,
        Some(bytes) => {
            let bytes: [u8; SECRET_KEY_LEN] = bytes.try_into().map_err(|_| Failure {
                code: ERROR_KEY,
                message: format!(
                    "a secret key must be exactly {SECRET_KEY_LEN} bytes, got {}",
                    bytes.len()
                ),
            })?;
            Some(SecretKey::from_bytes(&bytes))
        }
    };

    let alpn_count = under(ERROR_BIND, r.count())?;
    let mut alpns = Vec::with_capacity(alpn_count);
    for _ in 0..alpn_count {
        alpns.push(under(ERROR_BIND, r.bytes())?.to_vec());
    }

    let relay_mode = read_relay_mode(&mut r)?;
    let bind_addrs = read_socket_addrs(&mut r)?;
    let external_addrs = read_socket_addrs(&mut r)?;

    under(ERROR_BIND, r.finish())?;

    Ok(BindConfig {
        preset,
        secret_key,
        alpns,
        relay_mode,
        bind_addrs,
        external_addrs,
    })
}

fn read_relay_mode(r: &mut Reader) -> Outcome<Option<RelayMode>> {
    Ok(match under(ERROR_RELAY, r.u8())? {
        RELAY_MODE_UNSET => None,
        RELAY_MODE_DISABLED => Some(RelayMode::Disabled),
        RELAY_MODE_DEFAULT => Some(RelayMode::Default),
        RELAY_MODE_STAGING => Some(RelayMode::Staging),
        RELAY_MODE_CUSTOM => {
            let count = under(ERROR_RELAY, r.count())?;
            let mut urls = Vec::with_capacity(count);
            for _ in 0..count {
                let text = under(ERROR_RELAY, r.str())?;
                urls.push(RelayUrl::from_str(text).map_err(|error| Failure {
                    code: ERROR_RELAY,
                    message: format!("could not parse {text:?} as a relay URL: {error}"),
                })?);
            }
            Some(RelayMode::custom(urls))
        }
        other => return fail(ERROR_RELAY, format!("unknown relay mode tag {other}")),
    })
}

fn read_socket_addrs(r: &mut Reader) -> Outcome<Vec<SocketAddr>> {
    let count = under(ERROR_ADDR, r.count())?;
    let mut addrs = Vec::with_capacity(count);
    for _ in 0..count {
        let text = under(ERROR_ADDR, r.str())?;
        addrs.push(SocketAddr::from_str(text).map_err(|error| Failure {
            code: ERROR_ADDR,
            message: format!("could not parse {text:?} as a socket address: {error}"),
        })?);
    }
    Ok(addrs)
}

/// Dispatches a preset ordinal to the concrete `Preset` type — see the module header for why the
/// preset cannot cross as data.
fn builder_for(preset: i32) -> Outcome<Builder> {
    Ok(match preset {
        PRESET_EMPTY => Endpoint::builder(presets::Empty),
        PRESET_MINIMAL => Endpoint::builder(presets::Minimal),
        PRESET_N0 => Endpoint::builder(presets::N0),
        PRESET_N0_DISABLE_RELAY => Endpoint::builder(presets::N0DisableRelay),
        other => {
            return fail(
                ERROR_BIND,
                format!("unknown endpoint preset ordinal {other}"),
            )
        }
    })
}

/// Applies `config` on top of its preset, in the order that makes the explicit fields win.
fn configure(config: BindConfig) -> Outcome<Builder> {
    let mut builder = builder_for(config.preset)?;

    if let Some(key) = config.secret_key {
        builder = builder.secret_key(key);
    }
    if !config.alpns.is_empty() {
        builder = builder.alpns(config.alpns);
    }
    if let Some(mode) = config.relay_mode {
        builder = builder.relay_mode(mode);
    }
    if !config.bind_addrs.is_empty() {
        // `bind_addr` *adds* a socket, and every builder starts pre-configured with `0.0.0.0` and
        // `[::]`. A caller who names the addresses to bind on means those instead of, not as well
        // as, "every interface" — so the defaults are cleared first, exactly as iroh's own
        // `bind_addr` example does. iroh-ffi omits this, and its `bind_addr("127.0.0.1:0")`
        // therefore still leaves the endpoint listening on all interfaces.
        builder = builder.clear_ip_transports();
        for addr in config.bind_addrs {
            builder = builder.bind_addr(addr).map_err(|error| Failure {
                code: ERROR_ADDR,
                message: format!("could not bind on {addr}: {error}"),
            })?;
        }
    }
    for addr in config.external_addrs {
        builder = builder.external_addr(addr);
    }
    Ok(builder)
}

// ============================================================================
// The endpoint handle
// ============================================================================
/// The owned reference an async operation carries. `handle::Tagged` wraps the payload so a
/// handle used as the wrong type is rejected rather than projected over; it derefs to the payload,
/// so every use below is unaffected.
type EndpointShared = Arc<Tagged<EndpointSlot>>;

/// The object an endpoint handle points at.
///
/// A *slot* rather than the `Endpoint` itself, and the reason is ownership during `bind`.
/// [`crate::ops`] delivers a result exactly once; a cancel that wins that race makes the result be
/// discarded by [`crate::core::free_result`], which releases the message and byte buffers but
/// knows nothing about handles. Had `bind` created the handle on completion, a coroutine cancelled
/// in the instant the bind succeeded would leak a bound socket and its whole task tree —
/// unreachable from Kotlin and never closed.
///
/// Creating the handle *before* the bind removes the race rather than narrowing it: Kotlin owns
/// the slot from the start, the operation only fills it in, and Kotlin's `close()` — which it runs
/// on the cancellation path too — is the single release point. The operation holds its own `Arc`
/// clone, so a release racing the fill is a refcount decrement, not a use-after-free.
struct EndpointSlot {
    /// Empty until `bind` succeeds, and set at most once: a slot is bound by exactly one `bind`,
    /// and Kotlin discards it if that fails.
    endpoint: OnceLock<Endpoint>,
}

/// Endpoint slots still alive.
///
/// A test hook in the spirit of [`crate::ops::live_op_count`]. This is the first domain that *can*
/// leak a handle, and a leaked one is invisible from Kotlin — the test that binds and closes in a
/// loop would pass either way — so the count is kept here where it can be asserted.
static LIVE_HANDLES: AtomicI64 = AtomicI64::new(0);

impl EndpointSlot {
    fn new() -> Self {
        LIVE_HANDLES.fetch_add(1, Ordering::Relaxed);
        Self {
            endpoint: OnceLock::new(),
        }
    }
}

/// Counts the slot down when it is really gone.
///
/// Deliberately on `Drop` rather than in the free export: freeing a handle drops one `Arc` strong
/// count, and an operation still in flight holds another. What a leak test needs to observe is when
/// the `Endpoint` inside is actually released, which is exactly here.
impl Drop for EndpointSlot {
    fn drop(&mut self) {
        LIVE_HANDLES.fetch_sub(1, Ordering::Relaxed);
    }
}

/// Clones the `Arc` behind an endpoint handle, so it can be moved into a spawned future.
///
/// `None` for a null handle, which the operations report as [`ERROR_CLOSED`].
///
/// # Safety
/// `handle` must be null, or a live handle produced by `iroh4k_endpoint_new` and not yet freed.
/// Kotlin's guard guarantees both: the handle is created before any operation can reference it,
/// and it is released only once every in-flight operation has returned.
unsafe fn slot(handle: *mut c_void) -> Option<Arc<Tagged<EndpointSlot>>> {
    handle::clone_arc::<EndpointSlot>(handle)
}

/// Borrows the bound endpoint behind a handle for the duration of one synchronous call.
///
/// # Safety
/// As [`slot`]. The returned reference must not outlive the call it was taken for; every use below
/// passes it straight into an operation that returns before the export does.
unsafe fn borrow_endpoint<'a>(handle: *mut c_void) -> Result<&'a Endpoint, *mut Iroh4kResult> {
    if handle.is_null() {
        return Err(closed());
    }
    handle::borrow::<EndpointSlot>(handle)
        .and_then(|slot| slot.endpoint.get())
        .ok_or_else(closed)
}

/// Runs `f` against the endpoint behind `handle`, or answers [`ERROR_CLOSED`].
///
/// # Safety
/// As [`borrow_endpoint`].
unsafe fn with_endpoint(
    handle: *mut c_void,
    f: impl FnOnce(&Endpoint) -> *mut Iroh4kResult,
) -> *mut Iroh4kResult {
    match borrow_endpoint(handle) {
        Ok(endpoint) => f(endpoint),
        Err(result) => result,
    }
}

/// Clones the `iroh::Endpoint` behind an endpoint handle, for another domain's operations.
///
/// The hook `connection.rs` needs. An `iroh::Endpoint` is an `Arc` inside, so this is a refcount bump
/// that yields an **owned**, `'static` endpoint — which is exactly what the accept chain requires:
/// `Endpoint::accept(&self)` returns a borrowed future, so the borrow has to be taken inside the
/// spawned task from an endpoint that task owns. Handing out the clone rather than the slot also
/// keeps `EndpointSlot` private to this module.
///
/// `None` for a null handle, or one whose bind never completed; the caller reports that as
/// [`ERROR_CLOSED`].
///
/// # Safety
/// As [`slot`].
pub(crate) unsafe fn endpoint_clone(handle: *mut c_void) -> Option<Endpoint> {
    if handle.is_null() {
        return None;
    }
    handle::borrow::<EndpointSlot>(handle)
        .and_then(|slot| slot.endpoint.get())
        .cloned()
}

/// Resolves the endpoint inside a slot from within an asynchronous operation.
fn bound(slot: &Option<EndpointShared>) -> Result<&Endpoint, *mut Iroh4kResult> {
    slot.as_ref()
        .and_then(|slot| slot.endpoint.get())
        .ok_or_else(closed)
}

// ============================================================================
// Shared logic
// ============================================================================

/// Binds the endpoint described by `payload` into `slot`.
///
/// The whole configuration is decoded here rather than at the export, because the export returns
/// an operation id and has no result to fail with — every error has to travel through the
/// completion instead.
async fn bind(slot: Option<EndpointShared>, payload: Vec<u8>) -> *mut Iroh4kResult {
    let Some(slot) = slot else { return closed() };

    let builder = match read_bind_config(&payload).and_then(configure) {
        Ok(builder) => builder,
        Err(Failure { code, message }) => return error_result(code, message),
    };

    let endpoint = match builder.bind().await {
        Ok(endpoint) => endpoint,
        Err(error) => {
            return error_result(ERROR_BIND, format!("could not bind an endpoint: {error}"))
        }
    };

    if slot.endpoint.set(endpoint).is_err() {
        // Only reachable by binding one slot twice, which the Kotlin API cannot express: a slot is
        // created by `Endpoint.bind` and handed to exactly one bind operation.
        return error_result(ERROR_BIND, "this endpoint has already been bound");
    }
    ok_result()
}

/// The 32 raw bytes of the endpoint's id.
fn id_bytes(endpoint: &Endpoint) -> Vec<u8> {
    endpoint.id().as_bytes().to_vec()
}

/// The 32 raw bytes of the endpoint's secret key.
///
/// iroh-ffi returns the key as an object; here it is the raw bytes, because `SecretKey` is a Kotlin
/// value type in this binding. Note this hands out key material: it is the same key the caller
/// supplied (or asked iroh to generate), and there is no other way to persist a generated identity.
fn secret_key_bytes(endpoint: &Endpoint) -> Vec<u8> {
    endpoint.secret_key().to_bytes().to_vec()
}

/// The endpoint's current `EndpointAddr`.
fn addr_payload(endpoint: &Endpoint) -> Vec<u8> {
    let mut w = Writer::new();
    write_endpoint_addr(&mut w, &endpoint.addr());
    w.finish()
}

/// The local socket addresses the endpoint is bound to, as canonical strings.
fn bound_sockets_payload(endpoint: &Endpoint) -> Vec<u8> {
    let sockets = endpoint.bound_sockets();
    let mut w = Writer::new();
    w.seq(&sockets, |w, addr| {
        w.str(&addr.to_string());
    });
    w.finish()
}

/// Replaces the endpoint's ALPN list from a codec sequence of byte strings.
///
/// iroh ignores this on a closed endpoint (it logs a warning), so neither this nor Kotlin turns it
/// into an error.
fn set_alpns(endpoint: &Endpoint, payload: &[u8]) -> Outcome<()> {
    let mut r = Reader::new(payload);
    let count = under(ERROR_BIND, r.count())?;
    let mut alpns = Vec::with_capacity(count);
    for _ in 0..count {
        alpns.push(under(ERROR_BIND, r.bytes())?.to_vec());
    }
    under(ERROR_BIND, r.finish())?;
    endpoint.set_alpns(alpns);
    Ok(())
}

/// A snapshot of every endpoint metric, keyed `"<group>:<metric>"` as iroh-ffi keys it.
///
/// Values are `i64` rather than iroh-ffi's saturating `u32`. A `u32` silently pins any counter
/// past 4.29 billion — reachable for a byte counter on a long-lived endpoint — at `u32::MAX`,
/// which reads as a plateau rather than as an overflow. `i64` is lossless for every counter iroh
/// can actually reach, and it is the width Kotlin's `Long` has anyway.
///
/// A histogram contributes its observation *count*, and an unknown future metric kind contributes
/// zero: `MetricValue` is `#[non_exhaustive]`, and a metric this build cannot interpret is better
/// listed with a zero than dropped from the map.
fn stats_payload(endpoint: &Endpoint) -> Vec<u8> {
    use iroh_metrics::{MetricValue, MetricsGroupSet};

    let mut items: Vec<(String, i64, &'static str)> = Vec::new();
    for (group, item) in endpoint.metrics().iter() {
        let value = match item.value() {
            MetricValue::Counter(value) => i64::try_from(value).unwrap_or(i64::MAX),
            MetricValue::Gauge(value) => value,
            MetricValue::Histogram { count, .. } => i64::try_from(count).unwrap_or(i64::MAX),
            _ => 0,
        };
        items.push((format!("{group}:{}", item.name()), value, item.help()));
    }

    let mut w = Writer::new();
    w.seq(&items, |w, (name, value, description)| {
        w.str(name).i64(*value).str(description);
    });
    w.finish()
}

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

/// What the endpoint knows about the remote `id`, as an optional `EndpointAddr`.
///
/// Mirrors iroh-ffi's `remote_addr`: `RemoteInfo`'s only content is the id and its addresses, so
/// projecting it onto an `EndpointAddr` loses nothing a caller of this operation wants and saves a
/// second address-shaped type. Absent when the endpoint has no record of the remote — or when it
/// is closed, which iroh reports the same way.
async fn remote_addr(endpoint: &Endpoint, id: EndpointId) -> Vec<u8> {
    let mut w = Writer::new();
    match endpoint.remote_info(id).await {
        None => {
            w.u8(ABSENT);
        }
        Some(info) => {
            w.u8(PRESENT);
            let id = info.id();
            let addr = EndpointAddr::from_parts(id, info.into_addrs().map(|info| info.into_addr()));
            write_endpoint_addr(&mut w, &addr);
        }
    }
    w.finish()
}

/// Parses a socket address argument.
fn parse_socket_addr(text: &str) -> Outcome<SocketAddr> {
    SocketAddr::from_str(text).map_err(|error| Failure {
        code: ERROR_ADDR,
        message: format!("could not parse {text:?} as a socket address: {error}"),
    })
}

/// Parses a relay URL argument.
fn parse_relay_url(text: &str) -> Outcome<RelayUrl> {
    RelayUrl::from_str(text).map_err(|error| Failure {
        code: ERROR_RELAY,
        message: format!("could not parse {text:?} as a relay URL: {error}"),
    })
}

// ============================================================================
// C ABI — Kotlin/Native via cinterop
// ============================================================================

/// Borrows a caller-owned buffer as a slice for the duration of one call.
///
/// Only for the **synchronous** exports, as in `keys.rs` and `addr.rs`: they finish before
/// returning, so the pinned Kotlin `ByteArray` outlives the borrow. The asynchronous exports use
/// [`crate::core::owned_bytes`] instead, because their future runs on a tokio thread long after
/// the Kotlin buffer is gone.
///
/// # Safety
/// `ptr` must be null, or point to at least `len` initialised bytes that stay valid and unmutated
/// for the lifetime `'a`, and `'a` must not outlive the call.
unsafe fn borrowed<'a>(ptr: *const u8, len: c_int) -> &'a [u8] {
    if ptr.is_null() || len <= 0 {
        return &[];
    }
    std::slice::from_raw_parts(ptr, len as usize)
}

/// Creates an unbound endpoint handle, which `iroh4k_endpoint_bind` fills in.
///
/// Never fails, so it returns the handle rather than a result. Kotlin owns it from here and must
/// hand it back to [`iroh4k_endpoint_free`] — including when the bind fails or is cancelled, which
/// is the whole reason the handle is created first (see [`EndpointSlot`]).
#[no_mangle]
pub extern "C" fn iroh4k_endpoint_new() -> *mut c_void {
    handle::into_handle(EndpointSlot::new())
}

/// Endpoint slots still alive. Test hook for asserting handles do not leak.
#[no_mangle]
pub extern "C" fn iroh4k_endpoint_live_handle_count() -> i64 {
    LIVE_HANDLES.load(Ordering::Relaxed)
}

/// Releases an endpoint handle, closing its sockets once the last reference goes.
///
/// Tolerates null so Kotlin's `close()` can be idempotent.
///
/// # Safety
/// `handle` must be null, or a handle from [`iroh4k_endpoint_new`] that has not been freed.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_free(handle: *mut c_void) {
    handle::free::<EndpointSlot>(handle);
}

/// Binds the endpoint described by the configuration payload into `handle`. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `payload`/`payload_len` must be null/0 or describe
/// at least `payload_len` readable bytes; they are **copied** before the future is spawned, so the
/// caller may free the buffer as soon as this returns.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_bind(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    let payload = owned_bytes(payload, payload_len);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(bind(slot, payload).await)
    })
}

/// The 32 raw bytes of the endpoint's id.
///
/// # Safety
/// `handle` must satisfy [`borrow_endpoint`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_id(handle: *mut c_void) -> *mut Iroh4kResult {
    with_endpoint(handle, |endpoint| bytes_result(id_bytes(endpoint)))
}

/// The endpoint's current `EndpointAddr`, as a codec payload.
///
/// # Safety
/// As [`iroh4k_endpoint_id`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_addr(handle: *mut c_void) -> *mut Iroh4kResult {
    with_endpoint(handle, |endpoint| bytes_result(addr_payload(endpoint)))
}

/// The 32 raw bytes of the endpoint's secret key.
///
/// # Safety
/// As [`iroh4k_endpoint_id`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_secret_key(handle: *mut c_void) -> *mut Iroh4kResult {
    with_endpoint(handle, |endpoint| bytes_result(secret_key_bytes(endpoint)))
}

/// Replaces the endpoint's ALPN list from a codec sequence of byte strings.
///
/// # Safety
/// `handle` must satisfy [`borrow_endpoint`]'s contract, and `payload`/`payload_len` must satisfy
/// [`borrowed`]'s.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_set_alpns(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
) -> *mut Iroh4kResult {
    let payload = borrowed(payload, payload_len);
    with_endpoint(handle, |endpoint| match set_alpns(endpoint, payload) {
        Ok(()) => ok_result(),
        Err(Failure { code, message }) => error_result(code, message),
    })
}

/// The local socket addresses the endpoint is bound to, as a codec sequence of strings.
///
/// # Safety
/// As [`iroh4k_endpoint_id`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_bound_sockets(handle: *mut c_void) -> *mut Iroh4kResult {
    with_endpoint(handle, |endpoint| {
        bytes_result(bound_sockets_payload(endpoint))
    })
}

/// `1` in `i64_val` if the endpoint has been closed, `0` if it is still alive.
///
/// # Safety
/// As [`iroh4k_endpoint_id`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_is_closed(handle: *mut c_void) -> *mut Iroh4kResult {
    with_endpoint(handle, |endpoint| {
        i64_result(i64::from(endpoint.is_closed()))
    })
}

/// Every endpoint metric, as a codec sequence of `(name, value, description)`.
///
/// # Safety
/// As [`iroh4k_endpoint_id`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_stats(handle: *mut c_void) -> *mut Iroh4kResult {
    with_endpoint(handle, |endpoint| bytes_result(stats_payload(endpoint)))
}

/// Closes the endpoint gracefully, notifying peers. Asynchronous.
///
/// Does **not** release the handle: iroh's `Endpoint` stays queryable after `close()`, so
/// `is_closed`, `id` and `secret_key` keep working. Kotlin splits the two — `shutdown()` for this,
/// `close()` for [`iroh4k_endpoint_free`].
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_shutdown(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match bound(&slot) {
            Ok(endpoint) => {
                endpoint.close().await;
                ok_result()
            }
            Err(result) => result,
        })
    })
}

/// Resolves once the endpoint has a connected home relay. Asynchronous.
///
/// Blocks **indefinitely** when no relay can be reached — with `RelayMode::Disabled` there is
/// never a home relay to connect to, so iroh's own implementation waits on a watcher that will
/// never update. That is exactly why every operation here is cancellable: the caller's coroutine
/// scope, timeout or cancellation is what ends it.
///
/// # Safety
/// As [`iroh4k_endpoint_shutdown`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_online(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match bound(&slot) {
            Ok(endpoint) => {
                endpoint.online().await;
                ok_result()
            }
            Err(result) => result,
        })
    })
}

/// What the endpoint knows about a remote, as an optional `EndpointAddr` payload. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `id`/`id_len` are copied before the future is
/// spawned, so the caller may free the buffer as soon as this returns.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_remote_addr(
    handle: *mut c_void,
    id: *const u8,
    id_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    let id = owned_bytes(id, id_len);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match parse_endpoint_id(&id) {
            Err(Failure { code, message }) => error_result(code, message),
            Ok(id) => match bound(&slot) {
                Ok(endpoint) => bytes_result(remote_addr(endpoint, id).await),
                Err(result) => result,
            },
        })
    })
}

/// Advertises `addr` as an address this endpoint is directly reachable on. Asynchronous.
///
/// Ignored by iroh on a closed endpoint, which is not reported as an error.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `addr` must be null or point to a valid
/// nul-terminated UTF-8 string; it is copied before the future is spawned.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_add_external_addr(
    handle: *mut c_void,
    addr: *const c_char,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    let addr = c_str(addr).to_owned();
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match parse_socket_addr(&addr) {
            Err(Failure { code, message }) => error_result(code, message),
            Ok(addr) => match bound(&slot) {
                Ok(endpoint) => {
                    endpoint.add_external_addr(addr).await;
                    ok_result()
                }
                Err(result) => result,
            },
        })
    })
}

/// Removes a previously advertised external address; `1` in `i64_val` if one was present.
/// Asynchronous.
///
/// # Safety
/// As [`iroh4k_endpoint_add_external_addr`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_remove_external_addr(
    handle: *mut c_void,
    addr: *const c_char,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    let addr = c_str(addr).to_owned();
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match parse_socket_addr(&addr) {
            Err(Failure { code, message }) => error_result(code, message),
            Ok(addr) => match bound(&slot) {
                Ok(endpoint) => i64_result(i64::from(endpoint.remove_external_addr(&addr).await)),
                Err(result) => result,
            },
        })
    })
}

/// Inserts a relay configuration, answering with the configuration it replaced (absent if there
/// was none, or if the endpoint is closed). Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `payload`/`payload_len` are copied before the future
/// is spawned.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_insert_relay(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    let payload = owned_bytes(payload, payload_len);
    ops::spawn_callback(callback, fun, async move {
        let config = {
            let mut r = Reader::new(&payload);
            read_relay_config(&mut r)
                .and_then(|config| under(ERROR_RELAY, r.finish()).map(|()| config))
        };
        OpResult::new(match config {
            Err(Failure { code, message }) => error_result(code, message),
            Ok(config) => match bound(&slot) {
                Ok(endpoint) => {
                    let url = config.url.clone();
                    let replaced = endpoint.insert_relay(url, Arc::new(config)).await;
                    bytes_result(relay_config_payload(replaced))
                }
                Err(result) => result,
            },
        })
    })
}

/// Removes a relay configuration, answering with the one that was removed (absent if there was
/// none, or if the endpoint is closed). Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `url` must be null or point to a valid
/// nul-terminated UTF-8 string; it is copied before the future is spawned.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_remove_relay(
    handle: *mut c_void,
    url: *const c_char,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    let url = c_str(url).to_owned();
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match parse_relay_url(&url) {
            Err(Failure { code, message }) => error_result(code, message),
            Ok(url) => match bound(&slot) {
                Ok(endpoint) => {
                    bytes_result(relay_config_payload(endpoint.remove_relay(&url).await))
                }
                Err(result) => result,
            },
        })
    })
}

/// Tells iroh the network may have changed. Asynchronous.
///
/// # Safety
/// As [`iroh4k_endpoint_shutdown`].
#[no_mangle]
pub unsafe extern "C" fn iroh4k_endpoint_network_change(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    let slot = slot(handle);
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(match bound(&slot) {
            Ok(endpoint) => {
                endpoint.network_change().await;
                ok_result()
            }
            Err(result) => result,
        })
    })
}

// ============================================================================
// JNI — JVM and Android via the shared library
//
// Unavailable on iOS, which uses the FFI/cinterop path exclusively. Symbol names match the
// Kotlin object `tech.annexflow.iroh4k.EndpointJni`.
//
// Handles travel as `jlong`, the JVM's only pointer-sized integer. Each `*Start` export copies
// its arguments out of the JVM before spawning, then returns an operation id for
// `Iroh4kJni.opAwait`/`opCancel`.
// ============================================================================

#[cfg(not(target_os = "ios"))]
#[allow(non_snake_case)]
mod jni_facade {
    use super::*;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jbyteArray, jlong};
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

    /// Reads a Java `String` argument. An unreadable one becomes `""`, which then fails to parse —
    /// again, reported rather than panicked on.
    fn text(env: &mut JNIEnv, value: &JString) -> String {
        env.get_string(value).map(String::from).unwrap_or_default()
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_newHandle(
        _env: JNIEnv,
        _class: JClass,
    ) -> jlong {
        iroh4k_endpoint_new() as usize as jlong
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_liveHandleCount(
        _env: JNIEnv,
        _class: JClass,
    ) -> jlong {
        LIVE_HANDLES.load(Ordering::Relaxed)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_freeHandle(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<EndpointSlot>(as_handle(handle)) };
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_bindStart(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
        payload: JByteArray,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let payload = arg(&mut env, &payload);
        ops::spawn_channel(async move { OpResult::new(bind(slot, payload).await) })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_id(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_endpoint(as_handle(handle), |endpoint| {
                bytes_result(id_bytes(endpoint))
            })
        };
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_addr(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_endpoint(as_handle(handle), |endpoint| {
                bytes_result(addr_payload(endpoint))
            })
        };
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_secretKey(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_endpoint(as_handle(handle), |endpoint| {
                bytes_result(secret_key_bytes(endpoint))
            })
        };
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_setAlpns(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
        payload: JByteArray,
    ) -> jbyteArray {
        let payload = arg(&mut env, &payload);
        let result = unsafe {
            with_endpoint(as_handle(handle), |endpoint| {
                match set_alpns(endpoint, &payload) {
                    Ok(()) => ok_result(),
                    Err(Failure { code, message }) => error_result(code, message),
                }
            })
        };
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_boundSockets(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_endpoint(as_handle(handle), |endpoint| {
                bytes_result(bound_sockets_payload(endpoint))
            })
        };
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_isClosed(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_endpoint(as_handle(handle), |endpoint| {
                i64_result(i64::from(endpoint.is_closed()))
            })
        };
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_stats(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_endpoint(as_handle(handle), |endpoint| {
                bytes_result(stats_payload(endpoint))
            })
        };
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_shutdownStart(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        ops::spawn_channel(async move {
            OpResult::new(match bound(&slot) {
                Ok(endpoint) => {
                    endpoint.close().await;
                    ok_result()
                }
                Err(result) => result,
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_onlineStart(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        ops::spawn_channel(async move {
            OpResult::new(match bound(&slot) {
                Ok(endpoint) => {
                    endpoint.online().await;
                    ok_result()
                }
                Err(result) => result,
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_remoteAddrStart(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
        id: JByteArray,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let id = arg(&mut env, &id);
        ops::spawn_channel(async move {
            OpResult::new(match parse_endpoint_id(&id) {
                Err(Failure { code, message }) => error_result(code, message),
                Ok(id) => match bound(&slot) {
                    Ok(endpoint) => bytes_result(remote_addr(endpoint, id).await),
                    Err(result) => result,
                },
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_addExternalAddrStart(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
        addr: JString,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let addr = text(&mut env, &addr);
        ops::spawn_channel(async move {
            OpResult::new(match parse_socket_addr(&addr) {
                Err(Failure { code, message }) => error_result(code, message),
                Ok(addr) => match bound(&slot) {
                    Ok(endpoint) => {
                        endpoint.add_external_addr(addr).await;
                        ok_result()
                    }
                    Err(result) => result,
                },
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_removeExternalAddrStart(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
        addr: JString,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let addr = text(&mut env, &addr);
        ops::spawn_channel(async move {
            OpResult::new(match parse_socket_addr(&addr) {
                Err(Failure { code, message }) => error_result(code, message),
                Ok(addr) => match bound(&slot) {
                    Ok(endpoint) => {
                        i64_result(i64::from(endpoint.remove_external_addr(&addr).await))
                    }
                    Err(result) => result,
                },
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_insertRelayStart(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
        payload: JByteArray,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let payload = arg(&mut env, &payload);
        ops::spawn_channel(async move {
            let config = {
                let mut r = Reader::new(&payload);
                read_relay_config(&mut r)
                    .and_then(|config| under(ERROR_RELAY, r.finish()).map(|()| config))
            };
            OpResult::new(match config {
                Err(Failure { code, message }) => error_result(code, message),
                Ok(config) => match bound(&slot) {
                    Ok(endpoint) => {
                        let url = config.url.clone();
                        let replaced = endpoint.insert_relay(url, Arc::new(config)).await;
                        bytes_result(relay_config_payload(replaced))
                    }
                    Err(result) => result,
                },
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_removeRelayStart(
        mut env: JNIEnv,
        _class: JClass,
        handle: jlong,
        url: JString,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let url = text(&mut env, &url);
        ops::spawn_channel(async move {
            OpResult::new(match parse_relay_url(&url) {
                Err(Failure { code, message }) => error_result(code, message),
                Ok(url) => match bound(&slot) {
                    Ok(endpoint) => {
                        bytes_result(relay_config_payload(endpoint.remove_relay(&url).await))
                    }
                    Err(result) => result,
                },
            })
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_networkChangeStart(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        ops::spawn_channel(async move {
            OpResult::new(match bound(&slot) {
                Ok(endpoint) => {
                    endpoint.network_change().await;
                    ok_result()
                }
                Err(result) => result,
            })
        })
    }
}
