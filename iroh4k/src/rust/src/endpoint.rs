//! The `Endpoint`: binding, configuration, outbound connections and lifecycle.
//!
//! Owned by the endpoint domain. Contains the shared logic plus both facades' exports for it:
//! `#[unsafe(no_mangle)] extern "C"` for cinterop, `#[cfg(not(target_os = "ios"))] Java_*` for JNI.
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
//!   u8      mDNS              0 absent — no mDNS at all — or 1 present, then:
//!                             u8   advertise     0 resolve only, 1 also advertise this endpoint
//!                             str? service name  i32 -1 for upstream's own default
//!   i32     discovery count   then count × one service:
//!                             u8 0 PkarrPublisher + str? relay URL + u8 published addrs
//!                                  (0 relay only, 1 ip only, 2 unfiltered)
//!                             u8 1 PkarrResolver  + str? relay URL
//!                             u8 2 Dns            + str? origin domain
//!                             `str?` absent means upstream's own n0 default. Its own tag family,
//!                             deliberately not `addr.rs`'s.
//!
//! EndpointAddr  (both ways)       — the same layout `addr.rs` documents and writes
//!   bytes   id
//!   i32     count             then count × TransportAddr
//!                             u8 0 Relay   + str; u8 1 Ip + str;
//!                             u8 2 Custom  + i64 transport id + bytes data;
//!                             u8 3 Unknown + str Display text
//!                             Rust → Kotlin for `addr` and `remote_addr`, Kotlin → Rust for
//!                             `add_endpoint_addr`. The inbound direction is the *standalone*
//!                             form — the address is the whole payload — so it is read with
//!                             `addr::decode_endpoint_addr`, which rejects trailing bytes.
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
        Arc, OnceLock,
        atomic::{AtomicI64, Ordering},
    },
};

use iroh::{
    Endpoint, EndpointAddr, EndpointId, RelayConfig, RelayMode, RelayUrl, SecretKey,
    address_lookup::{AddrFilter, DnsAddressLookup, MemoryLookup, PkarrPublisher, PkarrResolver},
    endpoint::{Builder, presets},
};
use iroh_mdns_address_lookup::MdnsAddressLookup;
use iroh_relay::RelayQuicConfig;
use url::Url;

use crate::addr::{decode_endpoint_addr, write_endpoint_addr};
use crate::codec::{Reader, Writer};
use crate::core::{
    ERROR_ADDR, ERROR_BIND, ERROR_CLOSED, ERROR_DISCOVERY, ERROR_KEY, ERROR_RELAY, Iroh4kPtr,
    Iroh4kResult, bytes_result, c_str, error_result, i64_result, ok_result, owned_bytes,
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

/// Discovery service tags inside a bind configuration. Its own family: these are **not**
/// `addr.rs`'s `TAG_RELAY`/`TAG_IP`/`TAG_CUSTOM`/`TAG_UNKNOWN`, which number a different set of
/// payload shapes from the same starting point.
const DISCOVERY_TAG_PKARR_PUBLISHER: u8 = 0;
const DISCOVERY_TAG_PKARR_RESOLVER: u8 = 1;
const DISCOVERY_TAG_DNS: u8 = 2;

/// `PublishedAddrs` ordinals, matching Kotlin's enum.
const PUBLISHED_RELAY_ONLY: u8 = 0;
const PUBLISHED_IP_ONLY: u8 = 1;
const PUBLISHED_UNFILTERED: u8 = 2;

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
            );
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
    mdns: Option<MdnsSettings>,
    discovery: Vec<DiscoverySettings>,
}

/// What Kotlin can say about mDNS, mirroring `Endpoint.kt`'s `MdnsConfig`.
///
/// Absent means "no mDNS", which is the only way to keep an endpoint off the local link entirely,
/// so it is deliberately not the same shape as [`RELAY_MODE_UNSET`]: there is nothing a preset
/// could have configured for this — mDNS is a separate crate that no iroh preset installs.
struct MdnsSettings {
    advertise: bool,
    /// `None` leaves upstream's own default service name, currently `"irohv1"`. Kept as "not
    /// stated" rather than resolved here so the default stays upstream's to change.
    service_name: Option<String>,
}

/// One address lookup service Kotlin asked for, mirroring `Discovery.kt`'s sealed interface.
///
/// `None` for a URL or domain means "not stated", which is answered with upstream's own `n0_dns()`
/// rather than with a URL spelled out on this side — so upstream keeps choosing between its
/// production and staging infrastructure.
enum DiscoverySettings {
    PkarrPublisher {
        relay_url: Option<String>,
        published: AddrFilter,
    },
    PkarrResolver {
        relay_url: Option<String>,
    },
    Dns {
        origin_domain: Option<String>,
    },
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
    let mdns = read_mdns(&mut r)?;
    let discovery = read_discovery(&mut r)?;

    under(ERROR_BIND, r.finish())?;

    Ok(BindConfig {
        preset,
        secret_key,
        alpns,
        relay_mode,
        bind_addrs,
        external_addrs,
        mdns,
        discovery,
    })
}

/// Reads the optional mDNS record, second-to-last on the wire — [`read_discovery`] reads the
/// field that actually closes a bind configuration.
///
/// The fields are read into named bindings rather than straight into the struct literal, so the
/// order they are consumed in is the order they appear on the wire no matter how the struct is
/// declared.
fn read_mdns(r: &mut Reader) -> Outcome<Option<MdnsSettings>> {
    Ok(match under(ERROR_BIND, r.u8())? {
        ABSENT => None,
        PRESENT => {
            let advertise = under(ERROR_BIND, r.bool())?;
            let service_name = under(ERROR_BIND, r.opt_str())?.map(str::to_owned);
            Some(MdnsSettings {
                advertise,
                service_name,
            })
        }
        other => {
            return fail(
                ERROR_BIND,
                format!("malformed bind configuration: unknown optional-mDNS tag {other}"),
            );
        }
    })
}

/// Reads the counted sequence of discovery services that closes a bind configuration.
///
/// An unknown tag is an error rather than a skipped record. This payload only ever travels from
/// Kotlin to Rust, and both ends ship in one artifact, so a tag this build does not know is version
/// skew *inside* the build — a bug to report, not a newer peer to tolerate.
fn read_discovery(r: &mut Reader) -> Outcome<Vec<DiscoverySettings>> {
    let count = under(ERROR_DISCOVERY, r.count())?;
    let mut services = Vec::with_capacity(count);
    for _ in 0..count {
        services.push(match under(ERROR_DISCOVERY, r.u8())? {
            DISCOVERY_TAG_PKARR_PUBLISHER => {
                let relay_url = under(ERROR_DISCOVERY, r.opt_str())?.map(str::to_owned);
                let published = read_published_addrs(r)?;
                DiscoverySettings::PkarrPublisher {
                    relay_url,
                    published,
                }
            }
            DISCOVERY_TAG_PKARR_RESOLVER => DiscoverySettings::PkarrResolver {
                relay_url: under(ERROR_DISCOVERY, r.opt_str())?.map(str::to_owned),
            },
            DISCOVERY_TAG_DNS => DiscoverySettings::Dns {
                origin_domain: under(ERROR_DISCOVERY, r.opt_str())?.map(str::to_owned),
            },
            other => {
                return fail(
                    ERROR_DISCOVERY,
                    format!("malformed bind configuration: unknown discovery tag {other}"),
                );
            }
        });
    }
    Ok(services)
}

/// Maps a `PublishedAddrs` ordinal onto the `AddrFilter` it names.
fn read_published_addrs(r: &mut Reader) -> Outcome<AddrFilter> {
    Ok(match under(ERROR_DISCOVERY, r.u8())? {
        PUBLISHED_RELAY_ONLY => AddrFilter::relay_only(),
        PUBLISHED_IP_ONLY => AddrFilter::ip_only(),
        PUBLISHED_UNFILTERED => AddrFilter::unfiltered(),
        other => {
            return fail(
                ERROR_DISCOVERY,
                format!("malformed bind configuration: unknown published-addresses tag {other}"),
            );
        }
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
            );
        }
    })
}

/// Applies `config` on top of its preset, in the order that makes the explicit fields win.
///
/// `lookup` is the address book [`bind`] created and kept; see the comment on its registration
/// below for why it is threaded through here rather than fetched back off the bound endpoint.
fn configure(config: BindConfig, lookup: MemoryLookup) -> Outcome<Builder> {
    let mut builder = builder_for(config.preset)?;

    // Before the address book, always. A non-empty list means the caller named the services they
    // want, and `EndpointConfig` promises an explicit choice beats the preset's — the same contract
    // `bindAddrs` keeps when it clears iroh's default sockets rather than adding to them.
    //
    // Appending instead would be the quieter failure: `preset` defaults to `N0`, so
    // `EndpointConfig(discovery = [PkarrPublisher(mine)])` would go on publishing this endpoint's
    // addresses to n0 as well, which nobody would ever notice.
    //
    // This clears only what the preset registered. iroh4k's own `MemoryLookup` is registered below,
    // after the clear, so `addEndpointAddr` keeps working under every configuration — an ordering
    // invariant, not a property of the types, which is why a test pins it.
    if !config.discovery.is_empty() {
        builder = builder.clear_address_lookup();
    }

    // Registered unconditionally, for every preset, because `Endpoint.addEndpointAddr` has
    // nowhere else to put an address: a bound `Endpoint` exposes only the erased
    // `AddressLookupServices`, so the sole way to still hold a typed `MemoryLookup` afterwards is
    // to have created it before the bind. `Builder::address_lookup` *appends*, so whatever the
    // preset configured stays.
    //
    // This does not compromise `EndpointPreset.Minimal`'s "talks to nothing it was not explicitly
    // told about" property — the property the whole offline test suite rests on. A `MemoryLookup`
    // is a `BTreeMap` behind an `RwLock`: it resolves only from what Kotlin put in it, and it
    // publishes nowhere at all. An endpoint that never calls `addEndpointAddr` therefore carries
    // an empty map and sends not one extra packet.
    builder = builder.address_lookup(lookup);

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
    if let Some(mdns) = config.mdns {
        // The *builder* is registered, never a built `MdnsAddressLookup`. Building one calls
        // `tokio::runtime::Handle::current`, which panics when there is no runtime on the calling
        // thread — and with `panic = "abort"` that ends the host process rather than the call.
        // Handing iroh the builder makes it call `build` itself, inside `bind`, where a runtime is
        // guaranteed. A build that fails there — no usable IPv4 *or* IPv6 multicast on this host —
        // surfaces out of `Builder::bind` as an ordinary [`ERROR_BIND`], so asking for mDNS can
        // turn a bind that would have succeeded into one that fails.
        let mut mdns_lookup = MdnsAddressLookup::builder().advertise(mdns.advertise);
        if let Some(name) = mdns.service_name {
            mdns_lookup = mdns_lookup.service_name(name);
        }
        builder = builder.address_lookup(mdns_lookup);
    }
    // Each service is registered in the order given. `address_lookup` itself only ever adds, but
    // the clear above — run before anything in this function touches the preset's address
    // lookup — is what turns a non-empty `config.discovery` into a replacement of the preset's
    // services rather than an addition on top of them. mDNS, registered separately above, always
    // composes with whatever ends up here: it points at no server, so it has nothing to disagree
    // with, and no preset installs it, so there is nothing for the clear to have removed.
    for service in config.discovery {
        builder = match service {
            DiscoverySettings::PkarrPublisher {
                relay_url,
                published,
            } => {
                let publisher = match relay_url {
                    Some(url) => PkarrPublisher::builder(parse_discovery_url(&url)?),
                    None => PkarrPublisher::n0_dns(),
                };
                builder.address_lookup(publisher.addr_filter(published))
            }
            DiscoverySettings::PkarrResolver { relay_url } => {
                let resolver = match relay_url {
                    Some(url) => PkarrResolver::builder(parse_discovery_url(&url)?),
                    None => PkarrResolver::n0_dns(),
                };
                builder.address_lookup(resolver)
            }
            DiscoverySettings::Dns { origin_domain } => {
                let dns = match origin_domain {
                    Some(domain) => DnsAddressLookup::builder(domain),
                    None => DnsAddressLookup::n0_dns(),
                };
                builder.address_lookup(dns)
            }
        };
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

    /// The in-memory address book this endpoint was built with, filled by the same `bind`.
    ///
    /// A second field rather than something read back off [`Self::endpoint`], because iroh offers
    /// no way to recover a typed `MemoryLookup` from a bound endpoint — `Endpoint::address_lookup`
    /// answers with the erased `AddressLookupServices`, which can only be added to. What is kept
    /// here is a *clone* of the one handed to the builder, and a `MemoryLookup` is an
    /// `Arc<RwLock<..>>` inside, so the two share state: what `add_endpoint_addr` inserts is what
    /// iroh resolves against.
    lookup: OnceLock<MemoryLookup>,
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
            lookup: OnceLock::new(),
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
    unsafe { handle::clone_arc::<EndpointSlot>(handle) }
}

/// Borrows the bound endpoint behind a handle for the duration of one synchronous call.
///
/// # Safety
/// As [`slot`]. The returned reference must not outlive the call it was taken for; every use below
/// passes it straight into an operation that returns before the export does.
unsafe fn borrow_endpoint<'a>(handle: *mut c_void) -> Result<&'a Endpoint, *mut Iroh4kResult> {
    unsafe {
        if handle.is_null() {
            return Err(closed());
        }
        handle::borrow::<EndpointSlot>(handle)
            .and_then(|slot| slot.endpoint.get())
            .ok_or_else(closed)
    }
}

/// Runs `f` against the endpoint behind `handle`, or answers [`ERROR_CLOSED`].
///
/// # Safety
/// As [`borrow_endpoint`].
unsafe fn with_endpoint(
    handle: *mut c_void,
    f: impl FnOnce(&Endpoint) -> *mut Iroh4kResult,
) -> *mut Iroh4kResult {
    unsafe {
        match borrow_endpoint(handle) {
            Ok(endpoint) => f(endpoint),
            Err(result) => result,
        }
    }
}

/// Runs `f` against the address book behind `handle`, or answers [`ERROR_CLOSED`].
///
/// [`ERROR_CLOSED`] covers both "released" and "never bound": the address book is filled by the
/// same `bind` that fills the endpoint, so a slot without one is a slot with no endpoint either,
/// and both are cases where there is nothing for an address to be added to.
///
/// # Safety
/// As [`borrow_endpoint`].
unsafe fn with_lookup(
    handle: *mut c_void,
    f: impl FnOnce(&MemoryLookup) -> *mut Iroh4kResult,
) -> *mut Iroh4kResult {
    unsafe {
        if handle.is_null() {
            return closed();
        }
        match handle::borrow::<EndpointSlot>(handle).and_then(|slot| slot.lookup.get()) {
            Some(lookup) => f(lookup),
            None => closed(),
        }
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
    unsafe {
        if handle.is_null() {
            return None;
        }
        handle::borrow::<EndpointSlot>(handle)
            .and_then(|slot| slot.endpoint.get())
            .cloned()
    }
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

    // Created here, before the builder, because it has to be in two places at once: registered on
    // the builder so iroh resolves against it, and kept in the slot so `add_endpoint_addr` can
    // still write to it afterwards. The clone shares the map — see [`EndpointSlot::lookup`].
    let lookup = MemoryLookup::new();

    let builder =
        match read_bind_config(&payload).and_then(|config| configure(config, lookup.clone())) {
            Ok(builder) => builder,
            Err(Failure { code, message }) => return error_result(code, message),
        };

    let endpoint = match builder.bind().await {
        Ok(endpoint) => endpoint,
        Err(error) => {
            return error_result(ERROR_BIND, format!("could not bind an endpoint: {error}"));
        }
    };

    // Filled before the endpoint, so a slot that reports itself bound always has its address book
    // too. A second `set` is ignored rather than reported: it can only mean the slot was bound
    // twice, which the `endpoint` set below already reports.
    let _ = slot.lookup.set(lookup);

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

/// Inserts a remote endpoint's address into this endpoint's own address book.
///
/// Synchronous, because `MemoryLookup::add_endpoint_info` is: it takes the write lock, inserts into
/// a `BTreeMap` and returns. There is no dial and no I/O — the address is only recorded, so that a
/// later `connect` given nothing but an `EndpointId` has somewhere to resolve it from.
///
/// The payload is the address and nothing else, so it is decoded with `decode_endpoint_addr`, the
/// standalone form, which rejects trailing bytes. The inline `read_endpoint_addr` is for an address
/// that is one field of a larger message and would silently accept a wrong-layout payload here.
fn add_endpoint_addr(lookup: &MemoryLookup, payload: &[u8]) -> Outcome<()> {
    let addr =
        decode_endpoint_addr(payload).map_err(|(code, message)| Failure { code, message })?;
    lookup.add_endpoint_info(addr);
    Ok(())
}

/// Removes a remote endpoint's entry from this endpoint's address book, reporting whether there
/// was one.
///
/// Synchronous for the same reason [`add_endpoint_addr`] is, and the necessary counterpart to it:
/// `add_endpoint_info` *accumulates*, so without this there is no way to retract an address a peer
/// has moved off.
///
/// The removed `EndpointInfo` is dropped rather than encoded back to Kotlin. It carries more than
/// an `EndpointAddr` — when it was last updated, and the peer's own user data — and iroh4k models
/// neither, so what could cross the boundary is a lossy half of the entry. Whether there was one to
/// remove is the whole of what a caller can act on.
///
/// The id arrives as its 32 raw bytes and goes through [`parse_endpoint_id`], so a buffer of the
/// wrong length is an [`ERROR_KEY`] rather than a panic — which matters on the JNI path, where an
/// unreadable `byte[]` arrives here as an empty slice.
fn remove_endpoint_addr(lookup: &MemoryLookup, id: &[u8]) -> Outcome<bool> {
    let id = parse_endpoint_id(id)?;
    Ok(lookup.remove_endpoint_info(id).is_some())
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

/// Parses a discovery service URL.
///
/// Its own error code rather than [`ERROR_RELAY`]: a pkarr relay is a plain URL upstream, not an
/// iroh `RelayUrl`, so reporting it as a relay failure would name the wrong thing to whoever reads
/// the message.
fn parse_discovery_url(text: &str) -> Outcome<Url> {
    Url::parse(text).map_err(|error| Failure {
        code: ERROR_DISCOVERY,
        message: format!("could not parse {text:?} as a discovery service URL: {error}"),
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
    unsafe {
        if ptr.is_null() || len <= 0 {
            return &[];
        }
        std::slice::from_raw_parts(ptr, len as usize)
    }
}

/// Creates an unbound endpoint handle, which `iroh4k_endpoint_bind` fills in.
///
/// Never fails, so it returns the handle rather than a result. Kotlin owns it from here and must
/// hand it back to [`iroh4k_endpoint_free`] — including when the bind fails or is cancelled, which
/// is the whole reason the handle is created first (see [`EndpointSlot`]).
#[unsafe(no_mangle)]
pub extern "C" fn iroh4k_endpoint_new() -> *mut c_void {
    handle::into_handle(EndpointSlot::new())
}

/// Endpoint slots still alive. Test hook for asserting handles do not leak.
#[unsafe(no_mangle)]
pub extern "C" fn iroh4k_endpoint_live_handle_count() -> i64 {
    LIVE_HANDLES.load(Ordering::Relaxed)
}

/// Releases an endpoint handle, closing its sockets once the last reference goes.
///
/// Tolerates null so Kotlin's `close()` can be idempotent.
///
/// # Safety
/// `handle` must be null, or a handle from [`iroh4k_endpoint_new`] that has not been freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_free(handle: *mut c_void) {
    unsafe {
        handle::free::<EndpointSlot>(handle);
    }
}

/// Binds the endpoint described by the configuration payload into `handle`. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `payload`/`payload_len` must be null/0 or describe
/// at least `payload_len` readable bytes; they are **copied** before the future is spawned, so the
/// caller may free the buffer as soon as this returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_bind(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = slot(handle);
        let payload = owned_bytes(payload, payload_len);
        ops::spawn_callback(callback, fun, async move {
            OpResult::new(bind(slot, payload).await)
        })
    }
}

/// The 32 raw bytes of the endpoint's id.
///
/// # Safety
/// `handle` must satisfy [`borrow_endpoint`]'s contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_id(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with_endpoint(handle, |endpoint| bytes_result(id_bytes(endpoint))) }
}

/// The endpoint's current `EndpointAddr`, as a codec payload.
///
/// # Safety
/// As [`iroh4k_endpoint_id`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_addr(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with_endpoint(handle, |endpoint| bytes_result(addr_payload(endpoint))) }
}

/// The 32 raw bytes of the endpoint's secret key.
///
/// # Safety
/// As [`iroh4k_endpoint_id`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_secret_key(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with_endpoint(handle, |endpoint| bytes_result(secret_key_bytes(endpoint))) }
}

/// Replaces the endpoint's ALPN list from a codec sequence of byte strings.
///
/// # Safety
/// `handle` must satisfy [`borrow_endpoint`]'s contract, and `payload`/`payload_len` must satisfy
/// [`borrowed`]'s.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_set_alpns(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
) -> *mut Iroh4kResult {
    unsafe {
        let payload = borrowed(payload, payload_len);
        with_endpoint(handle, |endpoint| match set_alpns(endpoint, payload) {
            Ok(()) => ok_result(),
            Err(Failure { code, message }) => error_result(code, message),
        })
    }
}

/// Records a remote endpoint's address in this endpoint's address book. Synchronous.
///
/// The payload is one standalone `EndpointAddr`. Synchronous because the insert is a map write
/// under a lock — nothing is dialled — which is also why the payload may be *borrowed* rather than
/// copied: this returns before the pinned Kotlin array can go away.
///
/// # Safety
/// `handle` must satisfy [`borrow_endpoint`]'s contract, and `payload`/`payload_len` must satisfy
/// [`borrowed`]'s.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_add_endpoint_addr(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
) -> *mut Iroh4kResult {
    unsafe {
        let payload = borrowed(payload, payload_len);
        with_lookup(handle, |lookup| match add_endpoint_addr(lookup, payload) {
            Ok(()) => ok_result(),
            Err(Failure { code, message }) => error_result(code, message),
        })
    }
}

/// Removes a remote endpoint's entry from this endpoint's address book; `1` in `i64_val` if there
/// was one. Synchronous.
///
/// The argument is an endpoint id as its 32 raw bytes rather than a codec payload, as in
/// [`iroh4k_endpoint_remote_addr`]: an id is fixed-width, so there is nothing to frame. Synchronous
/// and therefore *borrowing*, for the reason [`iroh4k_endpoint_add_endpoint_addr`] gives.
///
/// # Safety
/// `handle` must satisfy [`borrow_endpoint`]'s contract, and `id`/`id_len` must satisfy
/// [`borrowed`]'s.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_remove_endpoint_addr(
    handle: *mut c_void,
    id: *const u8,
    id_len: c_int,
) -> *mut Iroh4kResult {
    unsafe {
        let id = borrowed(id, id_len);
        with_lookup(handle, |lookup| match remove_endpoint_addr(lookup, id) {
            Ok(removed) => i64_result(i64::from(removed)),
            Err(Failure { code, message }) => error_result(code, message),
        })
    }
}

/// The local socket addresses the endpoint is bound to, as a codec sequence of strings.
///
/// # Safety
/// As [`iroh4k_endpoint_id`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_bound_sockets(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        with_endpoint(handle, |endpoint| {
            bytes_result(bound_sockets_payload(endpoint))
        })
    }
}

/// `1` in `i64_val` if the endpoint has been closed, `0` if it is still alive.
///
/// # Safety
/// As [`iroh4k_endpoint_id`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_is_closed(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        with_endpoint(handle, |endpoint| {
            i64_result(i64::from(endpoint.is_closed()))
        })
    }
}

/// Every endpoint metric, as a codec sequence of `(name, value, description)`.
///
/// # Safety
/// As [`iroh4k_endpoint_id`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_stats(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with_endpoint(handle, |endpoint| bytes_result(stats_payload(endpoint))) }
}

/// Closes the endpoint gracefully, notifying peers. Asynchronous.
///
/// Does **not** release the handle: iroh's `Endpoint` stays queryable after `close()`, so
/// `is_closed`, `id` and `secret_key` keep working. Kotlin splits the two — `shutdown()` for this,
/// `close()` for [`iroh4k_endpoint_free`].
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_shutdown(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
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
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_online(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
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
}

/// What the endpoint knows about a remote, as an optional `EndpointAddr` payload. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `id`/`id_len` are copied before the future is
/// spawned, so the caller may free the buffer as soon as this returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_remote_addr(
    handle: *mut c_void,
    id: *const u8,
    id_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
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
}

/// Advertises `addr` as an address this endpoint is directly reachable on. Asynchronous.
///
/// Ignored by iroh on a closed endpoint, which is not reported as an error.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `addr` must be null or point to a valid
/// nul-terminated UTF-8 string; it is copied before the future is spawned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_add_external_addr(
    handle: *mut c_void,
    addr: *const c_char,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
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
}

/// Removes a previously advertised external address; `1` in `i64_val` if one was present.
/// Asynchronous.
///
/// # Safety
/// As [`iroh4k_endpoint_add_external_addr`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_remove_external_addr(
    handle: *mut c_void,
    addr: *const c_char,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = slot(handle);
        let addr = c_str(addr).to_owned();
        ops::spawn_callback(callback, fun, async move {
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
}

/// Inserts a relay configuration, answering with the configuration it replaced (absent if there
/// was none, or if the endpoint is closed). Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `payload`/`payload_len` are copied before the future
/// is spawned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_insert_relay(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
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
}

/// Removes a relay configuration, answering with the one that was removed (absent if there was
/// none, or if the endpoint is closed). Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`slot`]'s contract. `url` must be null or point to a valid
/// nul-terminated UTF-8 string; it is copied before the future is spawned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_remove_relay(
    handle: *mut c_void,
    url: *const c_char,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
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
}

/// Tells iroh the network may have changed. Asynchronous.
///
/// # Safety
/// As [`iroh4k_endpoint_shutdown`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_network_change(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
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
    use jni::EnvUnowned;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jbyteArray, jlong};

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
    fn arg(env: &mut EnvUnowned, array: &JByteArray) -> Vec<u8> {
        crate::jni::with_env(env, |env| env.convert_byte_array(array)).unwrap_or_default()
    }

    /// Reads a Java `String` argument. An unreadable one becomes `""`, which then fails to parse —
    /// again, reported rather than panicked on.
    fn text(env: &mut EnvUnowned, value: &JString) -> String {
        crate::jni::with_env(env, |env| value.try_to_string(env)).unwrap_or_default()
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_newHandle(
        _env: EnvUnowned,
        _class: JClass,
    ) -> jlong {
        iroh4k_endpoint_new() as usize as jlong
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_liveHandleCount(
        _env: EnvUnowned,
        _class: JClass,
    ) -> jlong {
        LIVE_HANDLES.load(Ordering::Relaxed)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_freeHandle(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<EndpointSlot>(as_handle(handle)) };
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_bindStart(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        payload: JByteArray,
    ) -> jlong {
        let slot = unsafe { slot(as_handle(handle)) };
        let payload = arg(&mut env, &payload);
        ops::spawn_channel(async move { OpResult::new(bind(slot, payload).await) })
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_id(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_addr(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_secretKey(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_setAlpns(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_addEndpointAddr(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        payload: JByteArray,
    ) -> jbyteArray {
        let payload = arg(&mut env, &payload);
        let result = unsafe {
            with_lookup(as_handle(handle), |lookup| {
                match add_endpoint_addr(lookup, &payload) {
                    Ok(()) => ok_result(),
                    Err(Failure { code, message }) => error_result(code, message),
                }
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_removeEndpointAddr(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        id: JByteArray,
    ) -> jbyteArray {
        let id = arg(&mut env, &id);
        let result = unsafe {
            with_lookup(as_handle(handle), |lookup| {
                match remove_endpoint_addr(lookup, &id) {
                    Ok(removed) => i64_result(i64::from(removed)),
                    Err(Failure { code, message }) => error_result(code, message),
                }
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_boundSockets(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_isClosed(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_stats(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_shutdownStart(
        _env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_onlineStart(
        _env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_remoteAddrStart(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_addExternalAddrStart(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_removeExternalAddrStart(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_insertRelayStart(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_removeRelayStart(
        mut env: EnvUnowned,
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

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_EndpointJni_networkChangeStart(
        _env: EnvUnowned,
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
