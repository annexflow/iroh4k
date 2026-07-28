//! QUIC transport configuration: the wire format for `TransportConfig`/`MtuDiscovery`/
//! `AckFrequency`, and the decoder that turns it into an `iroh::endpoint::QuicTransportConfig`.
//!
//! Owned by the transport domain, but it has no handle and no exports of its own: a transport
//! configuration only ever rides inside a larger payload — the bind configuration today
//! (`endpoint.rs`), a per-connection options payload in a later task (`connection.rs`) — so this
//! module owns the codec and the decode logic and nothing else. Every caller decodes with
//! [`read_transport_config`] or [`read_optional`] and applies the result itself.
//!
//! ## Codec layout
//!
//! Mirrored by `writeTransportConfig`/`writeOptionalTransportConfig`/`writeMtuDiscovery`/
//! `writeAckFrequency` in `TransportConfig.kt`; the two must be changed together.
//!
//! ```text
//! Optional TransportConfig  (Kotlin -> Rust)
//!   u8  0 absent — iroh's own defaults — or 1 present, then TransportConfig below
//!
//! TransportConfig  (Kotlin -> Rust) — a counted, tagged, sparse sequence
//!   i32  count      then count x one entry:
//!     u8 tag  then the field's value. Tags 0..=28, in the order of Task 1's field table:
//!       0  maxConcurrentBidiStreams          i64  a QUIC varint
//!       1  maxConcurrentUniStreams           i64  a QUIC varint
//!       2  streamReceiveWindow               i64  a QUIC varint
//!       3  receiveWindow                     i64  a QUIC varint
//!       4  sendWindow                        i64  a raw u64
//!       5  sendFairness                      u8   bool
//!       6  maxIdleTimeout                    i64  MILLISECONDS, not nanoseconds — see below
//!       7  keepAliveInterval                 i64  nanoseconds
//!       8  initialRtt                        i64  nanoseconds
//!       9  packetThreshold                   i32  narrowed to u32
//!       10 timeThreshold                     f64  narrowed to f32
//!       11 persistentCongestionThreshold     i32  narrowed to u32
//!       12 ackFrequency                      AckFrequency, below
//!       13 congestionController              u8   ordinal: 0 Cubic, 1 NewReno
//!       14 initialMtu                        i32  narrowed to u16
//!       15 minMtu                            i32  narrowed to u16
//!       16 mtuDiscovery                      MtuDiscovery, below
//!       17 padToMtu                          u8   bool
//!       18 datagramReceiveBufferSize         i32  narrowed to usize
//!       19 datagramSendBufferSize            i32  narrowed to usize
//!       20 cryptoBufferSize                  i32  narrowed to usize
//!       21 allowSpin                         u8   bool
//!       22 enableSegmentationOffload         u8   bool
//!       23 sendObservedAddressReports        u8   bool
//!       24 receiveObservedAddressReports     u8   bool
//!       25 maxConcurrentMultipathPaths       i32  narrowed to u32
//!       26 defaultPathMaxIdleTimeout         i64  nanoseconds
//!       27 defaultPathKeepAliveInterval      i64  nanoseconds
//!       28 maxRemoteNatTraversalAddresses    i32  narrowed to u8
//!
//! MtuDiscovery  (Kotlin -> Rust) — its own small tag family, the same sparse shape
//!   i32  count      then count x one entry:
//!     u8 0  interval             i64 nanoseconds
//!     u8 1  upperBound           i32 narrowed to u16
//!     u8 2  blackHoleCooldown    i64 nanoseconds
//!     u8 3  minimumChange        i32 narrowed to u16
//!
//! AckFrequency  (Kotlin -> Rust) — its own small tag family, the same sparse shape
//!   i32  count      then count x one entry:
//!     u8 0  ackElicitingThreshold  i64  a QUIC varint
//!     u8 1  maxAckDelay            i64  nanoseconds
//!     u8 2  reorderingThreshold    i64  a QUIC varint
//! ```
//!
//! Three tag families live here (`TRANSPORT_TAG_*`, `MTU_TAG_*`, `ACK_TAG_*`), and none of them
//! is `addr.rs`'s `TAG_*` or `endpoint.rs`'s `DISCOVERY_TAG_*` — those number different payload
//! shapes from the same starting point, and reusing one numbering across shapes is exactly how
//! two ends quietly stop agreeing. An unknown tag anywhere in here is an error, never a skipped
//! entry: this payload only ever travels from Kotlin to Rust inside one build, so a tag this
//! build does not recognise is version skew *inside* the build, not a newer peer to tolerate.
//!
//! ## Why `maxIdleTimeout` is milliseconds and everything else is nanoseconds
//!
//! Every other duration here becomes a plain `std::time::Duration` upstream, so nanosecond
//! precision costs nothing and matches every other `Duration`-typed field in the binding.
//! `maxIdleTimeout` is different: it becomes `Option<IdleTimeout>`, and `IdleTimeout` wraps a
//! 62-bit QUIC varint counted in **milliseconds** — the one place in this configuration a value
//! can be too large for the wire and must be refused rather than silently accepted. Nanoseconds
//! would defeat that: dividing an 8-byte nanosecond count back down to milliseconds can never
//! produce a value anywhere near the varint's 62-bit range (even `i64::MAX` nanoseconds is only
//! on the order of `10^13` milliseconds, against a varint that holds up to roughly `4.6 * 10^18`
//! milliseconds), so encoding this field as nanoseconds would make the overflow this module has
//! to guard against unreachable from Kotlin. Milliseconds is also upstream's own unit, so nothing
//! is lost by converting through it instead.
//!
//! Every failure here is malformed caller input, reported as `ERROR_INVALID_ARGUMENT` by every
//! caller of this module — there is no second code, because there is nothing here upstream itself
//! refuses beyond the two constructors that return a `Result`: `IdleTimeout`'s varint bound and a
//! QUIC varint out of range. This crate builds with `panic = "abort"`, so nothing below may ever
//! panic on what Kotlin passes in — every read goes through `codec::Reader`, which is fallible
//! throughout for exactly that reason.

use std::{sync::Arc, time::Duration};

use iroh::endpoint::{
    AckFrequencyConfig, ControllerFactory, IdleTimeout, MtuDiscoveryConfig, QuicTransportConfig,
    VarInt,
};
use noq_proto::congestion::{CubicConfig, NewRenoConfig};

use crate::codec::Reader;

// ============================================================================
// Wire protocol constants — shared with `TransportConfig.kt`. Append only.
// ============================================================================

/// Discriminators for an optional record — the same convention `endpoint.rs`'s `ABSENT`/
/// `PRESENT` carry, private to this module because there is no shared definition of the pair.
const ABSENT: u8 = 0;
const PRESENT: u8 = 1;

/// `TransportConfig` (Kotlin) entry tags. Neither `addr.rs`'s nor `endpoint.rs`'s
/// family — see the module header.
const TRANSPORT_TAG_MAX_CONCURRENT_BIDI_STREAMS: u8 = 0;
const TRANSPORT_TAG_MAX_CONCURRENT_UNI_STREAMS: u8 = 1;
const TRANSPORT_TAG_STREAM_RECEIVE_WINDOW: u8 = 2;
const TRANSPORT_TAG_RECEIVE_WINDOW: u8 = 3;
const TRANSPORT_TAG_SEND_WINDOW: u8 = 4;
const TRANSPORT_TAG_SEND_FAIRNESS: u8 = 5;
const TRANSPORT_TAG_MAX_IDLE_TIMEOUT: u8 = 6;
const TRANSPORT_TAG_KEEP_ALIVE_INTERVAL: u8 = 7;
const TRANSPORT_TAG_INITIAL_RTT: u8 = 8;
const TRANSPORT_TAG_PACKET_THRESHOLD: u8 = 9;
const TRANSPORT_TAG_TIME_THRESHOLD: u8 = 10;
const TRANSPORT_TAG_PERSISTENT_CONGESTION_THRESHOLD: u8 = 11;
const TRANSPORT_TAG_ACK_FREQUENCY: u8 = 12;
const TRANSPORT_TAG_CONGESTION_CONTROLLER: u8 = 13;
const TRANSPORT_TAG_INITIAL_MTU: u8 = 14;
const TRANSPORT_TAG_MIN_MTU: u8 = 15;
const TRANSPORT_TAG_MTU_DISCOVERY: u8 = 16;
const TRANSPORT_TAG_PAD_TO_MTU: u8 = 17;
const TRANSPORT_TAG_DATAGRAM_RECEIVE_BUFFER_SIZE: u8 = 18;
const TRANSPORT_TAG_DATAGRAM_SEND_BUFFER_SIZE: u8 = 19;
const TRANSPORT_TAG_CRYPTO_BUFFER_SIZE: u8 = 20;
const TRANSPORT_TAG_ALLOW_SPIN: u8 = 21;
const TRANSPORT_TAG_ENABLE_SEGMENTATION_OFFLOAD: u8 = 22;
const TRANSPORT_TAG_SEND_OBSERVED_ADDRESS_REPORTS: u8 = 23;
const TRANSPORT_TAG_RECEIVE_OBSERVED_ADDRESS_REPORTS: u8 = 24;
const TRANSPORT_TAG_MAX_CONCURRENT_MULTIPATH_PATHS: u8 = 25;
const TRANSPORT_TAG_DEFAULT_PATH_MAX_IDLE_TIMEOUT: u8 = 26;
const TRANSPORT_TAG_DEFAULT_PATH_KEEP_ALIVE_INTERVAL: u8 = 27;
const TRANSPORT_TAG_MAX_REMOTE_NAT_TRAVERSAL_ADDRESSES: u8 = 28;

/// `CongestionController` ordinals, matching Kotlin's enum.
const CONGESTION_CONTROLLER_CUBIC: u8 = 0;
const CONGESTION_CONTROLLER_NEW_RENO: u8 = 1;

/// [`MtuDiscoveryConfig`] entry tags. Its own small family, distinct from `TRANSPORT_TAG_*`.
const MTU_TAG_INTERVAL: u8 = 0;
const MTU_TAG_UPPER_BOUND: u8 = 1;
const MTU_TAG_BLACK_HOLE_COOLDOWN: u8 = 2;
const MTU_TAG_MINIMUM_CHANGE: u8 = 3;

/// [`AckFrequencyConfig`] entry tags. Its own small family, distinct from `TRANSPORT_TAG_*`.
const ACK_TAG_ACK_ELICITING_THRESHOLD: u8 = 0;
const ACK_TAG_MAX_ACK_DELAY: u8 = 1;
const ACK_TAG_REORDERING_THRESHOLD: u8 = 2;

// ============================================================================
// Scalar readers
// ============================================================================

/// Reads a QUIC varint field: an `i64` that must be nonnegative and fit under `VarInt::MAX`
/// (2^62 - 1). `what` names the field in the error, for a caller with several of these.
///
/// Kotlin's `Long` is signed and 64 bits; a QUIC varint is unsigned and 62 bits, so both a
/// negative value and one in the top two bits are the caller's mistake to hear about rather than
/// iroh4k's to silently truncate into a different number than was asked for. This mirrors the
/// policy `connection.rs`'s `varint` enforces for stream and connection limits, but is not the
/// same function: that one already carries its result as a boxed `*mut Iroh4kResult` for its own
/// callers (`stream.rs`, `watch.rs`), while every reader in this module stays message-only until
/// the single `ERROR_INVALID_ARGUMENT` is attached at the boundary that calls into this module —
/// unifying the two would cost an unsafe round trip through an already-leaked error result for no
/// benefit, since both ultimately call the same `noq_proto::VarInt::from_u64`.
fn read_varint(r: &mut Reader, what: &str) -> Result<VarInt, String> {
    let value = r.i64()?;
    let unsigned =
        u64::try_from(value).map_err(|_| format!("{what} must not be negative, got {value}"))?;
    VarInt::from_u64(unsigned).map_err(|error| format!("{what} must fit in a QUIC varint: {error}"))
}

/// Reads a nonnegative `i64` count of nanoseconds as a [`Duration`].
fn read_duration(r: &mut Reader, what: &str) -> Result<Duration, String> {
    let nanos = r.i64()?;
    if nanos < 0 {
        return Err(format!(
            "{what} must not be negative, got {nanos} nanoseconds"
        ));
    }
    Ok(Duration::from_nanos(nanos as u64))
}

/// Reads `TransportConfig.maxIdleTimeout`'s (Kotlin) `i64` **milliseconds** — see the
/// module header for why this one field is not nanoseconds like every other duration here.
fn read_idle_timeout(r: &mut Reader) -> Result<IdleTimeout, String> {
    let millis = r.i64()?;
    let unsigned = u64::try_from(millis)
        .map_err(|_| format!("maxIdleTimeout must not be negative, got {millis} milliseconds"))?;
    VarInt::from_u64(unsigned)
        .map(IdleTimeout::from)
        .map_err(|error| format!("maxIdleTimeout of {millis}ms does not fit on the wire: {error}"))
}

/// Reads an `i32` and narrows it to `u32`, rejecting a negative value.
fn read_u32(r: &mut Reader, what: &str) -> Result<u32, String> {
    let value = r.i32()?;
    u32::try_from(value).map_err(|_| format!("{what} must not be negative, got {value}"))
}

/// Reads an `i32` and narrows it to `u16`, rejecting anything outside `0..=65535`.
fn read_u16(r: &mut Reader, what: &str) -> Result<u16, String> {
    let value = r.i32()?;
    u16::try_from(value).map_err(|_| format!("{what} must fit in 0..=65535, got {value}"))
}

/// Reads an `i32` and narrows it to `u8`, rejecting anything outside `0..=255`.
fn read_u8_scalar(r: &mut Reader, what: &str) -> Result<u8, String> {
    let value = r.i32()?;
    u8::try_from(value).map_err(|_| format!("{what} must fit in 0..=255, got {value}"))
}

/// Reads an `i32` and narrows it to `usize`, rejecting a negative value.
fn read_usize(r: &mut Reader, what: &str) -> Result<usize, String> {
    Ok(read_u32(r, what)? as usize)
}

/// Reads a nonnegative `i64` as `u64` — `TransportConfig.sendWindow` (Kotlin), the one
/// field upstream takes as a raw `u64` rather than a `VarInt`.
fn read_u64(r: &mut Reader, what: &str) -> Result<u64, String> {
    let value = r.i64()?;
    u64::try_from(value).map_err(|_| format!("{what} must not be negative, got {value}"))
}

/// Reads `TransportConfig.congestionController`'s (Kotlin) `u8` ordinal into the
/// concrete factory it names. `noq-proto` is a direct dependency of this crate for exactly these
/// two types: `iroh` re-exports the `ControllerFactory` trait it takes but not the concrete
/// `CubicConfig`/`NewRenoConfig` upstream builds its own default from.
fn read_congestion_controller(
    r: &mut Reader,
) -> Result<Arc<dyn ControllerFactory + Send + Sync>, String> {
    match r.u8()? {
        CONGESTION_CONTROLLER_CUBIC => Ok(Arc::new(CubicConfig::default())),
        CONGESTION_CONTROLLER_NEW_RENO => Ok(Arc::new(NewRenoConfig::default())),
        other => Err(format!(
            "malformed transport configuration: unknown congestion controller ordinal {other}"
        )),
    }
}

// ============================================================================
// Nested records
// ============================================================================

/// Reads `TransportConfig.mtuDiscovery`'s (Kotlin) nested counted sequence.
fn read_mtu_discovery(r: &mut Reader) -> Result<MtuDiscoveryConfig, String> {
    let count = r.count()?;
    let mut config = MtuDiscoveryConfig::default();
    for _ in 0..count {
        match r.u8()? {
            MTU_TAG_INTERVAL => {
                config.interval(read_duration(r, "mtuDiscovery.interval")?);
            }
            MTU_TAG_UPPER_BOUND => {
                config.upper_bound(read_u16(r, "mtuDiscovery.upperBound")?);
            }
            MTU_TAG_BLACK_HOLE_COOLDOWN => {
                config.black_hole_cooldown(read_duration(r, "mtuDiscovery.blackHoleCooldown")?);
            }
            MTU_TAG_MINIMUM_CHANGE => {
                config.minimum_change(read_u16(r, "mtuDiscovery.minimumChange")?);
            }
            other => {
                return Err(format!(
                    "malformed transport configuration: unknown MTU discovery tag {other}"
                ));
            }
        }
    }
    Ok(config)
}

/// Reads `TransportConfig.ackFrequency`'s (Kotlin) nested counted sequence.
fn read_ack_frequency(r: &mut Reader) -> Result<AckFrequencyConfig, String> {
    let count = r.count()?;
    let mut config = AckFrequencyConfig::default();
    for _ in 0..count {
        match r.u8()? {
            ACK_TAG_ACK_ELICITING_THRESHOLD => {
                config
                    .ack_eliciting_threshold(read_varint(r, "ackFrequency.ackElicitingThreshold")?);
            }
            ACK_TAG_MAX_ACK_DELAY => {
                config.max_ack_delay(Some(read_duration(r, "ackFrequency.maxAckDelay")?));
            }
            ACK_TAG_REORDERING_THRESHOLD => {
                config.reordering_threshold(read_varint(r, "ackFrequency.reorderingThreshold")?);
            }
            other => {
                return Err(format!(
                    "malformed transport configuration: unknown ACK frequency tag {other}"
                ));
            }
        }
    }
    Ok(config)
}

// ============================================================================
// The transport configuration itself
// ============================================================================

/// Reads a `TransportConfig` (Kotlin), the payload `writeTransportConfig` produces.
///
/// An unknown tag is an error, never a skipped entry — see the module header.
pub(crate) fn read_transport_config(r: &mut Reader) -> Result<QuicTransportConfig, String> {
    let count = r.count()?;
    let mut builder = QuicTransportConfig::builder();
    for _ in 0..count {
        builder = match r.u8()? {
            TRANSPORT_TAG_MAX_CONCURRENT_BIDI_STREAMS => {
                builder.max_concurrent_bidi_streams(read_varint(r, "maxConcurrentBidiStreams")?)
            }
            TRANSPORT_TAG_MAX_CONCURRENT_UNI_STREAMS => {
                builder.max_concurrent_uni_streams(read_varint(r, "maxConcurrentUniStreams")?)
            }
            TRANSPORT_TAG_STREAM_RECEIVE_WINDOW => {
                builder.stream_receive_window(read_varint(r, "streamReceiveWindow")?)
            }
            TRANSPORT_TAG_RECEIVE_WINDOW => {
                builder.receive_window(read_varint(r, "receiveWindow")?)
            }
            TRANSPORT_TAG_SEND_WINDOW => builder.send_window(read_u64(r, "sendWindow")?),
            TRANSPORT_TAG_SEND_FAIRNESS => builder.send_fairness(r.bool()?),
            TRANSPORT_TAG_MAX_IDLE_TIMEOUT => builder.max_idle_timeout(Some(read_idle_timeout(r)?)),
            TRANSPORT_TAG_KEEP_ALIVE_INTERVAL => {
                builder.keep_alive_interval(read_duration(r, "keepAliveInterval")?)
            }
            TRANSPORT_TAG_INITIAL_RTT => builder.initial_rtt(read_duration(r, "initialRtt")?),
            TRANSPORT_TAG_PACKET_THRESHOLD => {
                builder.packet_threshold(read_u32(r, "packetThreshold")?)
            }
            TRANSPORT_TAG_TIME_THRESHOLD => builder.time_threshold(r.f64()? as f32),
            TRANSPORT_TAG_PERSISTENT_CONGESTION_THRESHOLD => builder
                .persistent_congestion_threshold(read_u32(r, "persistentCongestionThreshold")?),
            TRANSPORT_TAG_ACK_FREQUENCY => {
                builder.ack_frequency_config(Some(read_ack_frequency(r)?))
            }
            TRANSPORT_TAG_CONGESTION_CONTROLLER => {
                builder.congestion_controller_factory(read_congestion_controller(r)?)
            }
            TRANSPORT_TAG_INITIAL_MTU => builder.initial_mtu(read_u16(r, "initialMtu")?),
            TRANSPORT_TAG_MIN_MTU => builder.min_mtu(read_u16(r, "minMtu")?),
            TRANSPORT_TAG_MTU_DISCOVERY => {
                builder.mtu_discovery_config(Some(read_mtu_discovery(r)?))
            }
            TRANSPORT_TAG_PAD_TO_MTU => builder.pad_to_mtu(r.bool()?),
            TRANSPORT_TAG_DATAGRAM_RECEIVE_BUFFER_SIZE => builder
                .datagram_receive_buffer_size(Some(read_usize(r, "datagramReceiveBufferSize")?)),
            TRANSPORT_TAG_DATAGRAM_SEND_BUFFER_SIZE => {
                builder.datagram_send_buffer_size(read_usize(r, "datagramSendBufferSize")?)
            }
            TRANSPORT_TAG_CRYPTO_BUFFER_SIZE => {
                builder.crypto_buffer_size(read_usize(r, "cryptoBufferSize")?)
            }
            TRANSPORT_TAG_ALLOW_SPIN => builder.allow_spin(r.bool()?),
            TRANSPORT_TAG_ENABLE_SEGMENTATION_OFFLOAD => {
                builder.enable_segmentation_offload(r.bool()?)
            }
            TRANSPORT_TAG_SEND_OBSERVED_ADDRESS_REPORTS => {
                builder.send_observed_address_reports(r.bool()?)
            }
            TRANSPORT_TAG_RECEIVE_OBSERVED_ADDRESS_REPORTS => {
                builder.receive_observed_address_reports(r.bool()?)
            }
            TRANSPORT_TAG_MAX_CONCURRENT_MULTIPATH_PATHS => {
                builder.max_concurrent_multipath_paths(read_u32(r, "maxConcurrentMultipathPaths")?)
            }
            TRANSPORT_TAG_DEFAULT_PATH_MAX_IDLE_TIMEOUT => builder
                .default_path_max_idle_timeout(read_duration(r, "defaultPathMaxIdleTimeout")?),
            TRANSPORT_TAG_DEFAULT_PATH_KEEP_ALIVE_INTERVAL => builder
                .default_path_keep_alive_interval(read_duration(
                    r,
                    "defaultPathKeepAliveInterval",
                )?),
            TRANSPORT_TAG_MAX_REMOTE_NAT_TRAVERSAL_ADDRESSES => builder
                .max_remote_nat_traversal_addresses(read_u8_scalar(
                    r,
                    "maxRemoteNatTraversalAddresses",
                )?),
            other => {
                return Err(format!(
                    "malformed transport configuration: unknown tag {other}"
                ));
            }
        };
    }
    Ok(builder.build())
}

/// Reads the `u8 present` + `TransportConfig` (Kotlin) an endpoint-wide default or a
/// per-connection override crosses as. `writeOptionalTransportConfig`'s counterpart.
pub(crate) fn read_optional(r: &mut Reader) -> Result<Option<QuicTransportConfig>, String> {
    match r.u8()? {
        ABSENT => Ok(None),
        PRESENT => read_transport_config(r).map(Some),
        other => Err(format!(
            "malformed transport configuration: unknown optional-record tag {other}"
        )),
    }
}
