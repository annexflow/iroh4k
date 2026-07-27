//! Endpoint addresses (`EndpointAddr`, `TransportAddr`) and connection tickets
//! (`EndpointTicket`).
//!
//! Owned by the addressing domain. Contains the shared logic plus both facades' exports for it:
//! `#[unsafe(no_mangle)] extern "C"` for cinterop, `#[cfg(not(target_os = "ios"))] Java_*` for JNI.
//!
//! Like the keys and relay domains, nothing here is an opaque handle: an address and a ticket are
//! inert data, so Kotlin owns the values and hands them back in on every call. What genuinely needs
//! iroh is only this:
//!
//! - **`SocketAddr` parsing.** `std::net::SocketAddr::from_str` decides what an IP-and-port pair
//!   is, and it is fussier and more capable than a hand-rolled Kotlin parser would be: it
//!   compresses IPv6 (`[2001:0db8::0001]:443` → `[2001:db8::1]:443`), keeps a numeric zone id
//!   (`[fe80::1%3]:80`), and rejects a hostname, a missing port, and a port above 65535. Kotlin
//!   must accept exactly what iroh accepts, so it asks.
//! - **Ticket coding.** `EndpointTicket`'s string form is a `postcard` body in base32 behind an
//!   `"endpoint"` prefix. Reimplementing that in Kotlin would be a second wire format that agreed
//!   with iroh only until it did not.
//!
//! ## Deviation from iroh-ffi: the address model is not flattened
//!
//! iroh-ffi's `EndpointAddr` is `{ id, relay_url: Option<String>, addresses: Vec<String> }`, and
//! its `From<iroh::EndpointAddr>` is **lossy** in two ways: `TransportAddr::Custom` falls into a
//! `_ => {}` arm and is silently discarded, and several relay URLs collapse onto the last one seen.
//! A ticket read and re-issued through that model quietly drops addresses — a data-loss bug rather
//! than a simplification, and exactly the kind that surfaces as "the connection only works over the
//! relay" months later.
//!
//! iroh4k therefore models `TransportAddr` faithfully, as a tagged union carrying `Relay`, `Ip` and
//! `Custom` intact. `TransportAddr` is `#[non_exhaustive]` upstream, so a future iroh variant would
//! not match any arm here; rather than dropping it (iroh-ffi's mistake) it is encoded as
//! [`TAG_UNKNOWN`] with its `Display` text, surfaces in Kotlin as `TransportAddr.Unknown`, and is
//! **refused** with [`ERROR_ADDR`] if Kotlin tries to send it back. Refusing to re-encode what this
//! build cannot understand is the only honest answer: the alternative is to silently issue a ticket
//! that is missing an address the caller believed was in it.
//!
//! ## Codec layout
//!
//! Compound values cross as a [`crate::codec`] payload. Defined once here, mirrored by the
//! decoder/encoder in `Addr.kt`; the two must be changed together.
//!
//! ```text
//! EndpointAddr:
//!   bytes  id            the 32 raw endpoint-id bytes
//!   i32    count         number of transport addresses (as `Writer::seq` writes it)
//!   count × TransportAddr:
//!     u8 0  Relay   + str  canonical relay URL          (iroh's `RelayUrl` Display)
//!     u8 1  Ip      + str  canonical socket address     (Rust's `SocketAddr` Display)
//!     u8 2  Custom  + i64  transport id (u64 bit pattern) + bytes opaque data
//!     u8 3  Unknown + str  Display text                 (Rust → Kotlin only)
//!
//! Ticket:
//!   str    the canonical ticket string
//!   ...    the EndpointAddr above, as embedded in that ticket
//! ```
//!
//! A ticket operation answers with **both** halves so Kotlin never has to re-derive one from the
//! other: the string is the one iroh produced, and the address is the one iroh actually parsed —
//! normalised and de-duplicated by its `BTreeSet` — rather than Kotlin's pre-normalisation guess.
//!
//! Every failure here is malformed caller input, reported as [`ERROR_ADDR`] for address problems
//! and [`ERROR_TICKET`] for ticket problems. This crate builds with `panic = "abort"`, so a panic
//! on a bad string, a short buffer or a hostile length prefix would take the host process down —
//! nothing below may ever panic on what Kotlin passes in, which is why the payload reader is
//! fallible everywhere rather than indexing and hoping.

use std::{
    collections::BTreeSet,
    ffi::{c_char, c_int},
    net::SocketAddr,
    slice,
    str::FromStr,
};

// `EndpointAddr`, `TransportAddr`, `EndpointId` and `RelayUrl` come from the `iroh` facade, as in
// the keys and relay domains. `CustomAddr` is not re-exported by `iroh`, so it is taken from
// `iroh-base` — the same crate, reached by its own name.
use iroh::{EndpointAddr, EndpointId, RelayUrl, TransportAddr};
use iroh_base::CustomAddr;
use iroh_tickets::endpoint::EndpointTicket;

use crate::codec::{Reader, Writer};
use crate::core::{ERROR_ADDR, ERROR_TICKET, Iroh4kResult, bytes_result, c_str, error_result};

/// Transport address discriminators. Part of the wire protocol shared with `Addr.kt`: do not
/// renumber, append only.
pub(crate) const TAG_RELAY: u8 = 0;
pub(crate) const TAG_IP: u8 = 1;
pub(crate) const TAG_CUSTOM: u8 = 2;
pub(crate) const TAG_UNKNOWN: u8 = 3;

/// Raw length of an endpoint id, fixed by Ed25519.
const ENDPOINT_ID_LEN: usize = 32;

// ============================================================================
// Payload reader
// ============================================================================

// ============================================================================
// EndpointAddr coding
// ============================================================================

/// Writes an `EndpointAddr` in the layout documented at the top of this module.
///
/// Iteration is over the `BTreeSet`, so the order Kotlin receives is iroh's own canonical order
/// (`Relay` before `Ip` before `Custom`, then by the inner value) rather than whatever order the
/// caller happened to insert in.
/// Writes one transport address: `u8 tag` then its payload.
///
/// The single definition of the transport-address wire shape. Four domains encode an
/// `EndpointAddr` — addressing, endpoint, connection and watch — and a private copy per domain is
/// how the two ends of one wire format quietly stop agreeing.
pub(crate) fn write_transport_addr(w: &mut Writer, transport: &TransportAddr) {
    match transport {
        TransportAddr::Relay(url) => {
            w.u8(TAG_RELAY).str(&url.to_string());
        }
        TransportAddr::Ip(socket) => {
            w.u8(TAG_IP).str(&socket.to_string());
        }
        TransportAddr::Custom(custom) => {
            // The transport id is a `u64`; it travels as the same 64 bits in an `i64` because
            // the codec has no unsigned form and Kotlin has no unsigned `Long`. Kotlin
            // documents the reinterpretation on `CustomAddr.id`.
            w.u8(TAG_CUSTOM)
                .i64(custom.id() as i64)
                .bytes(custom.data());
        }
        // `TransportAddr` is `#[non_exhaustive]`: a future iroh variant lands here. It is
        // surfaced with its `Display` text rather than dropped — see the module header for why
        // iroh-ffi's `_ => {}` is a bug and not a simplification.
        other => {
            w.u8(TAG_UNKNOWN).str(&other.to_string());
        }
    }
}

/// Writes an endpoint address: its id, then a counted sequence of transport addresses.
pub(crate) fn write_endpoint_addr(w: &mut Writer, addr: &EndpointAddr) {
    w.bytes(addr.id.as_bytes());
    w.i32(addr.addrs.len() as i32);
    for transport in &addr.addrs {
        write_transport_addr(w, transport);
    }
}

/// Reads an `EndpointAddr` written in that layout.
pub(crate) fn read_endpoint_addr(r: &mut Reader) -> Result<EndpointAddr, String> {
    let id_bytes: [u8; ENDPOINT_ID_LEN] = r.bytes()?.try_into().map_err(|_| {
        format!("an endpoint id in an address must be exactly {ENDPOINT_ID_LEN} bytes")
    })?;
    let id = EndpointId::from_bytes(&id_bytes)
        .map_err(|error| format!("invalid endpoint id in an address: {error}"))?;

    let count = r.i32()?;
    if count < 0 {
        return Err(format!(
            "an address cannot hold {count} transport addresses"
        ));
    }
    // Every encoded address is at least one tag byte, so a count larger than the bytes left is a
    // corrupt prefix. Checked up front so a hostile `i32::MAX` fails immediately rather than
    // looping two billion times towards the same error.
    if count as usize > r.remaining() {
        return Err(format!(
            "an address claims {count} transport addresses but only {} bytes remain",
            r.remaining()
        ));
    }

    let mut addrs = BTreeSet::new();
    for _ in 0..count {
        addrs.insert(read_transport_addr(r)?);
    }
    Ok(EndpointAddr::from_parts(id, addrs))
}

/// Decodes a complete `EndpointAddr` payload, rejecting trailing bytes.
///
/// The byte-level entry point, for the domains that receive an address as a whole buffer rather
/// than as part of a larger payload. Sharing it keeps the "reject trailing bytes" rule — a payload
/// Kotlin built with the wrong layout must fail loudly, not be half-read — in one place.
pub(crate) fn decode_endpoint_addr(bytes: &[u8]) -> Result<EndpointAddr, (c_int, String)> {
    let mut r = Reader::new(bytes);
    let addr = read_endpoint_addr(&mut r).map_err(|message| (ERROR_ADDR, message))?;
    r.finish().map_err(|message| (ERROR_ADDR, message))?;
    Ok(addr)
}

pub(crate) fn read_transport_addr(r: &mut Reader) -> Result<TransportAddr, String> {
    match r.u8()? {
        TAG_RELAY => {
            let text = r.str()?;
            RelayUrl::from_str(text)
                .map(TransportAddr::Relay)
                .map_err(|error| format!("could not parse {text:?} as a relay URL: {error}"))
        }
        TAG_IP => {
            let text = r.str()?;
            SocketAddr::from_str(text)
                .map(TransportAddr::Ip)
                .map_err(|error| format!("could not parse {text:?} as a socket address: {error}"))
        }
        TAG_CUSTOM => {
            let id = r.i64()?;
            let data = r.bytes()?;
            // Back to `u64`, undoing the reinterpretation in `write_endpoint_addr`. iroh neither
            // validates nor size-limits the opaque data, so neither do we.
            Ok(TransportAddr::Custom(CustomAddr::from_parts(
                id as u64, data,
            )))
        }
        TAG_UNKNOWN => {
            let text = r.str()?;
            Err(format!(
                "this build of iroh4k cannot re-encode the transport address {text:?}: it came \
                 from a newer iroh than the one linked here. Remove it from the address before \
                 building a ticket, or upgrade iroh4k."
            ))
        }
        other => Err(format!(
            "malformed address payload: unknown transport address tag {other}"
        )),
    }
}

/// Encodes the ticket half of the layout: the canonical string plus the address inside it.
fn ticket_payload(ticket: &EndpointTicket) -> Vec<u8> {
    let mut w = Writer::new();
    w.str(&ticket.to_string());
    write_endpoint_addr(&mut w, ticket.endpoint_addr());
    w.finish()
}

// ============================================================================
// Shared logic
// ============================================================================

/// Parses `input` as an IP-and-port pair, returning Rust's canonical spelling of it.
///
/// Delegates to `std::net::SocketAddr::from_str` and adds no rules of its own, for the reason
/// `relay_url_parse` delegates to `RelayUrl::from_str`: the point of the crossing is that Kotlin's
/// notion of a socket address *is* the one iroh will use.
///
/// Normalisation matters as much as validation, because these strings are what an `EndpointAddr`'s
/// set is keyed on: `[2001:0db8:0000::0001]:443` and `[2001:db8::1]:443` are one address and must
/// not appear as two.
pub fn socket_addr_parse(input: &str) -> Result<String, String> {
    SocketAddr::from_str(input)
        .map(|addr| addr.to_string())
        .map_err(|error| format!("could not parse {input:?} as a socket address: {error}"))
}

/// Builds a ticket for the `EndpointAddr` encoded in `payload`, answering with the ticket payload.
///
/// Every failure is a problem with the address that was handed in — a bad length prefix, an
/// endpoint id that is not a curve point, an address string iroh rejects, or a transport variant
/// this build cannot re-encode — hence [`ERROR_ADDR`] at the facades. Encoding the ticket itself
/// cannot fail.
pub fn ticket_from_addr(payload: &[u8]) -> Result<Vec<u8>, String> {
    let mut r = Reader::new(payload);
    let addr = read_endpoint_addr(&mut r)?;
    r.finish()?;
    Ok(ticket_payload(&EndpointTicket::new(addr)))
}

/// Parses a ticket string, answering with the ticket payload.
///
/// Every failure is a problem with the string — the wrong prefix, invalid base32, or a body that is
/// not a ticket — hence [`ERROR_TICKET`] at the facades. The string that comes back is iroh's
/// canonical spelling, so `fromString(toString())` is a fixed point on the Kotlin side.
pub fn ticket_from_string(text: &str) -> Result<Vec<u8>, String> {
    let ticket = EndpointTicket::from_str(text)
        .map_err(|error| format!("could not parse an endpoint ticket: {error}"))?;
    Ok(ticket_payload(&ticket))
}

// ============================================================================
// Result marshalling shared by both facades
// ============================================================================

/// Turns an addressing outcome into a result under an explicit error code. Unlike the keys domain
/// this domain has two codes — `ERROR_ADDR` and `ERROR_TICKET` — so the caller names the one that
/// fits, and Kotlin gets `IrohError.Code.Addr` or `.Ticket` accordingly.
fn bytes_or_error(code: c_int, outcome: Result<Vec<u8>, String>) -> *mut Iroh4kResult {
    match outcome {
        Ok(bytes) => bytes_result(bytes),
        Err(message) => error_result(code, message),
    }
}

// ============================================================================
// C ABI — Kotlin/Native via cinterop
// ============================================================================

/// Borrows a caller-owned buffer as a slice for the duration of one call.
///
/// Does not copy, for the same reason `keys::borrowed` does not: every export here is synchronous
/// and finishes before returning, so the Kotlin `ByteArray` pinned around the call outlives the
/// borrow, and nothing retains the slice past the call — the payload is decoded into owned iroh
/// values before anything is returned.
///
/// # Safety
/// `ptr` must be null, or point to at least `len` initialised bytes that stay valid and unmutated
/// for the lifetime `'a`. Callers must not let `'a` outlive the call they borrowed for; the one use
/// below passes the slice straight into an operation that returns before the export does.
unsafe fn borrowed<'a>(ptr: *const u8, len: c_int) -> &'a [u8] {
    unsafe {
        if ptr.is_null() || len <= 0 {
            return &[];
        }
        slice::from_raw_parts(ptr, len as usize)
    }
}

/// The canonical form of the socket address `text`, as UTF-8 bytes. `ERROR_ADDR` if it does not
/// parse.
///
/// # Safety
/// `text` must be null or point to a valid nul-terminated UTF-8 string. It is only read for the
/// duration of the call — the canonical form is a fresh allocation owned by the returned result —
/// so the caller may free its buffer as soon as this returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_addr_socket_parse(text: *const c_char) -> *mut Iroh4kResult {
    unsafe {
        // `c_str` yields "" for a null or non-UTF-8 pointer, which then fails to parse as an address —
        // an `ERROR_ADDR` rather than undefined behaviour or a panic.
        match socket_addr_parse(c_str(text)) {
            Ok(canonical) => bytes_result(canonical.into_bytes()),
            Err(message) => error_result(ERROR_ADDR, message),
        }
    }
}

/// A ticket for the `EndpointAddr` encoded in `payload`, as the ticket codec payload.
/// `ERROR_ADDR` if the payload or any address in it is malformed.
///
/// # Safety
/// `payload`/`payload_len` must satisfy [`borrowed`]'s contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_addr_ticket_from_addr(
    payload: *const u8,
    payload_len: c_int,
) -> *mut Iroh4kResult {
    unsafe { bytes_or_error(ERROR_ADDR, ticket_from_addr(borrowed(payload, payload_len))) }
}

/// The ticket written as `text`, as the ticket codec payload. `ERROR_TICKET` if it does not parse.
///
/// # Safety
/// `text` must be null or point to a valid nul-terminated UTF-8 string, read only for the duration
/// of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_addr_ticket_from_string(text: *const c_char) -> *mut Iroh4kResult {
    unsafe { bytes_or_error(ERROR_TICKET, ticket_from_string(c_str(text))) }
}

// ============================================================================
// JNI — JVM and Android via the shared library
//
// Unavailable on iOS, which uses the FFI/cinterop path exclusively. Symbol names match the
// Kotlin object `tech.annexflow.iroh4k.AddrJni`.
// ============================================================================

#[cfg(not(target_os = "ios"))]
#[allow(non_snake_case)]
mod jni_facade {
    use super::*;
    use jni::EnvUnowned;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::jbyteArray;

    // One shared envelope writer — see `crate::jni::finish`.
    use crate::jni::finish;

    /// Copies a Java `byte[]` into an owned `Vec`.
    ///
    /// An unreadable or absent array becomes empty, which the payload reader then rejects as a
    /// malformed address. That is deliberate: the FFI boundary must never unwind, so a missing
    /// argument is reported like any other malformed one.
    fn arg(env: &mut EnvUnowned, array: &JByteArray) -> Vec<u8> {
        crate::jni::with_env(env, |env| env.convert_byte_array(array)).unwrap_or_default()
    }

    /// Reads a Java `String` argument, or reports why it could not be read under `code`.
    ///
    /// `String::from(MUTF8Chars)` decodes Java's modified UTF-8 and is infallible (it falls back
    /// to lossy decoding), so the only failure left is not being handed a string at all.
    fn text(
        env: &mut EnvUnowned,
        value: &JString,
        code: c_int,
    ) -> Result<String, *mut Iroh4kResult> {
        match crate::jni::with_env(env, |env| value.try_to_string(env)) {
            Ok(text) => Ok(text),
            Err(error) => Err(error_result(
                code,
                format!("could not read the string argument: {error}"),
            )),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_AddrJni_socketParse(
        mut env: EnvUnowned,
        _class: JClass,
        addr: JString,
    ) -> jbyteArray {
        let result = match text(&mut env, &addr, ERROR_ADDR) {
            Ok(input) => match socket_addr_parse(&input) {
                Ok(canonical) => bytes_result(canonical.into_bytes()),
                Err(message) => error_result(ERROR_ADDR, message),
            },
            Err(result) => result,
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_AddrJni_ticketFromAddr(
        mut env: EnvUnowned,
        _class: JClass,
        payload: JByteArray,
    ) -> jbyteArray {
        let payload = arg(&mut env, &payload);
        let result = bytes_or_error(ERROR_ADDR, ticket_from_addr(&payload));
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_AddrJni_ticketFromString(
        mut env: EnvUnowned,
        _class: JClass,
        ticket: JString,
    ) -> jbyteArray {
        let result = match text(&mut env, &ticket, ERROR_TICKET) {
            Ok(input) => bytes_or_error(ERROR_TICKET, ticket_from_string(&input)),
            Err(result) => result,
        };
        finish(&mut env, result)
    }
}
