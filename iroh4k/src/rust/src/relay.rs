//! Relay configuration (`RelayUrl`, `RelayMap`, `RelayMode`) and log-level control.
//!
//! Owned by the relay domain. Contains the shared logic plus both facades' exports for it:
//! `#[no_mangle] extern "C"` for cinterop and `#[cfg(not(target_os = "ios"))] Java_*` for JNI.
//!
//! Unlike iroh-ffi — which makes `RelayMap` and `RelayMode` opaque `Arc`-wrapped objects with
//! their own FFI lifetime — iroh4k keeps both as **pure Kotlin value types**. Relay
//! configuration is inert data: it has no live resources, so handing Kotlin a handle to it
//! would buy nothing and cost a free routine, a closed-flag guard, and a leak to worry about.
//!
//! What is left on this side is only what genuinely needs iroh:
//!
//! - **URL validation and normalisation.** Kotlin must agree with iroh byte-for-byte on what a
//!   relay URL is, and on its canonical spelling — a Kotlin-side reimplementation of WHATWG URL
//!   parsing would drift from the `url` crate iroh actually keys its relay maps on.
//! - **The built-in relay lists.** Read out of `iroh::defaults` so an upstream change to the n0
//!   production or staging fleet propagates on a version bump, rather than silently disagreeing
//!   with a hardcoded copy in Kotlin.
//!
//! Log-level control lives here too because it is the other piece of process-wide configuration
//! Kotlin needs before it builds an endpoint.

use std::{
    ffi::{c_char, c_int},
    str::FromStr,
    sync::OnceLock,
};

use iroh::RelayUrl;
use tracing_subscriber::{filter::LevelFilter, reload, Registry};

use crate::codec::Writer;
use crate::core::{
    bytes_result, c_str, error_result, ok_result, Iroh4kResult, ERROR_INVALID_ARGUMENT, ERROR_RELAY,
};

// ============================================================================
// Relay URLs
// ============================================================================

/// Parses `input` as a relay URL, returning its canonical string form.
///
/// Delegates to `iroh::RelayUrl`'s own `FromStr` and deliberately adds no rules of its own: the
/// entire point of routing this through Rust is that Kotlin's notion of a valid relay URL *is*
/// iroh's, including the parts a hand-rolled check would miss.
///
/// Normalisation matters as much as validation. `https://example.com` comes back as
/// `https://example.com/`, which is the form iroh compares against, so a Kotlin `RelayMap` keyed
/// on canonical strings treats the two spellings as one relay instead of two.
///
/// The error is a message rather than a code: every failure here is malformed user input, which
/// the facades below turn into `ERROR_RELAY`.
pub fn relay_url_parse(input: &str) -> Result<String, String> {
    RelayUrl::from_str(input)
        .map(|url| url.to_string())
        .map_err(|error| format!("could not parse {input:?} as a relay URL: {error}"))
}

/// Encodes relay URLs as a [`Writer::seq`] of strings, the shape Kotlin's `BinaryReader` decodes.
fn urls_payload(urls: Vec<RelayUrl>) -> Vec<u8> {
    let mut w = Writer::new();
    w.seq(&urls, |w, url| {
        w.str(&url.to_string());
    });
    w.finish()
}

/// The n0 production relay URLs, taken from `iroh::defaults::prod`.
pub fn relay_default_urls() -> Vec<u8> {
    urls_payload(iroh::defaults::prod::default_relay_map().urls())
}

/// The n0 staging relay URLs, taken from `iroh::defaults::staging`.
pub fn relay_staging_urls() -> Vec<u8> {
    urls_payload(iroh::defaults::staging::default_relay_map().urls())
}

// ============================================================================
// Logging
// ============================================================================

/// Handle onto the installed subscriber's level filter — see [`set_log_level`].
///
/// `Some` once we installed the subscriber ourselves; `None` if the global dispatcher already
/// belonged to somebody else. Either way the `OnceLock` is what guarantees at most one install.
static LOG_FILTER: OnceLock<Option<reload::Handle<LevelFilter, Registry>>> = OnceLock::new();

/// Maps a Kotlin `LogLevel` ordinal onto a `tracing` filter. `None` for an unknown ordinal.
fn level_filter(level: c_int) -> Option<LevelFilter> {
    // The ordinals are the wire protocol shared with Kotlin's `LogLevel` enum, exactly as the
    // `ERROR_*` codes are shared with `IrohError.Code`. Append only.
    Some(match level {
        0 => LevelFilter::TRACE,
        1 => LevelFilter::DEBUG,
        2 => LevelFilter::INFO,
        3 => LevelFilter::WARN,
        4 => LevelFilter::ERROR,
        5 => LevelFilter::OFF,
        _ => return None,
    })
}

/// Sets the `tracing` log level, safely on every call including the first.
///
/// Two things have to hold at once, and iroh-ffi's `set_log_level` (a bare
/// `tracing_subscriber::registry()....init()`) holds neither:
///
/// - **It must not blow up on a second call.** `tracing`'s global dispatcher can be set only
///   once; `init()` *panics* on the second attempt, and this crate builds with `panic = "abort"`,
///   so that panic would take the host process — or the test runner — down with it. The install
///   happens inside `OnceLock::get_or_init`, which makes it race-free between threads, and uses
///   `try_init` so an already-installed foreign subscriber is a `None` handle rather than a
///   panic.
/// - **It must actually change the level.** Plain idempotence would be a silent lie: a caller
///   that raises the level after startup expects more logs. The level therefore sits behind a
///   `reload::Layer`, so later calls retune the live subscriber in place.
pub fn set_log_level(level: c_int) -> Result<(), String> {
    let Some(filter) = level_filter(level) else {
        return Err(format!("unknown log level ordinal {level}"));
    };
    let handle = LOG_FILTER.get_or_init(|| install_subscriber(filter));
    if let Some(handle) = handle {
        // A no-op on the call that just installed `filter`; the real work on every later call.
        handle
            .reload(filter)
            .map_err(|error| format!("could not apply the log level: {error}"))?;
    }
    Ok(())
}

/// Installs the `tracing` subscriber, returning the handle that can retune its level later.
///
/// `None` means the global dispatcher already belonged to someone else — a host application, or a
/// test harness with its own subscriber. That is not our error to report: iroh's events still go
/// wherever that subscriber sends them, and refusing to panic over it is the whole point.
fn install_subscriber(initial: LevelFilter) -> Option<reload::Handle<LevelFilter, Registry>> {
    use tracing_subscriber::{fmt, prelude::*};

    let (filter, handle) = reload::Layer::new(initial);
    let mut layer = fmt::Layer::default();
    // ANSI escapes are noise in logcat, in Xcode's console and in CI logs alike.
    layer.set_ansi(false);
    tracing_subscriber::registry()
        .with(filter)
        .with(layer)
        .try_init()
        .ok()
        .map(|()| handle)
}

// ============================================================================
// C ABI — Kotlin/Native via cinterop
// ============================================================================

/// The canonical form of the relay URL `url`, as UTF-8 bytes. `ERROR_RELAY` if it does not parse.
///
/// # Safety
/// `url` must be null or point to a valid nul-terminated UTF-8 string. It is only read for the
/// duration of the call — the canonical form is a fresh allocation owned by the returned result —
/// so the caller may free its buffer as soon as this returns.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_relay_url_parse(url: *const c_char) -> *mut Iroh4kResult {
    match relay_url_parse(c_str(url)) {
        Ok(canonical) => bytes_result(canonical.into_bytes()),
        Err(message) => error_result(ERROR_RELAY, message),
    }
}

/// The n0 production relay URLs as a codec sequence of strings.
#[no_mangle]
pub extern "C" fn iroh4k_relay_default_urls() -> *mut Iroh4kResult {
    bytes_result(relay_default_urls())
}

/// The n0 staging relay URLs as a codec sequence of strings.
#[no_mangle]
pub extern "C" fn iroh4k_relay_staging_urls() -> *mut Iroh4kResult {
    bytes_result(relay_staging_urls())
}

/// Sets the log level from a Kotlin `LogLevel` ordinal. Safe to call repeatedly.
#[no_mangle]
pub extern "C" fn iroh4k_set_log_level(level: c_int) -> *mut Iroh4kResult {
    match set_log_level(level) {
        Ok(()) => ok_result(),
        Err(message) => error_result(ERROR_INVALID_ARGUMENT, message),
    }
}

// ============================================================================
// JNI — JVM and Android via the shared library
//
// Unavailable on iOS, which uses the FFI/cinterop path exclusively. Symbol names match the
// Kotlin object `tech.annexflow.iroh4k.RelayJni`.
// ============================================================================

#[cfg(not(target_os = "ios"))]
#[allow(non_snake_case)]
mod jni_facade {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::{jbyteArray, jint};
    use jni::JNIEnv;

    // One shared envelope writer — see `crate::jni::finish`.
    use crate::jni::finish;

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_RelayJni_relayUrlParse(
        mut env: JNIEnv,
        _class: JClass,
        url: JString,
    ) -> jbyteArray {
        // `String::from(JavaStr)` decodes Java's modified UTF-8 and is infallible (it falls back
        // to lossy decoding), so the only failure left is not being handed a string at all —
        // which is reported rather than panicked on, since the FFI boundary must never unwind.
        let result = match env.get_string(&url) {
            Ok(java_str) => {
                let input = String::from(java_str);
                match relay_url_parse(&input) {
                    Ok(canonical) => bytes_result(canonical.into_bytes()),
                    Err(message) => error_result(ERROR_RELAY, message),
                }
            }
            Err(error) => error_result(
                ERROR_RELAY,
                format!("could not read the relay URL argument: {error}"),
            ),
        };
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_RelayJni_relayDefaultUrls(
        mut env: JNIEnv,
        _class: JClass,
    ) -> jbyteArray {
        finish(&mut env, bytes_result(relay_default_urls()))
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_RelayJni_relayStagingUrls(
        mut env: JNIEnv,
        _class: JClass,
    ) -> jbyteArray {
        finish(&mut env, bytes_result(relay_staging_urls()))
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_RelayJni_setLogLevel(
        mut env: JNIEnv,
        _class: JClass,
        level: jint,
    ) -> jbyteArray {
        let result = match set_log_level(level) {
            Ok(()) => ok_result(),
            Err(message) => error_result(ERROR_INVALID_ARGUMENT, message),
        };
        finish(&mut env, result)
    }
}
