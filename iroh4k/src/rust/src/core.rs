//! Shared iroh engine.
//!
//! Holds the tokio runtime, the C-ABI result type, error mapping, and the operations that
//! both [`crate::ffi`] (cinterop) and [`crate::jni`] (JVM/Android) call into. This is the
//! single source of truth — the two facades only marshal arguments and results.

// The error taxonomy is declared as a complete set so the ordinal contract with Kotlin's
// `IrohError.Code` is append-only, and a few argument helpers have no caller in the current
// domains. Both are kept deliberately rather than trimmed to whatever happens to be used today.
#![allow(dead_code)]

use std::{
    ffi::{CStr, CString, c_char, c_int, c_void},
    ptr::null_mut,
    slice,
    sync::OnceLock,
};
use tokio::runtime::Runtime;

// ============================================================================
// Error codes — the wire protocol shared with Kotlin's `IrohError.Code` enum,
// which decodes them by ORDINAL.
//
// IMPORTANT: Do not change the order of the errors. Append only.
// ============================================================================

pub const OK: c_int = -1;
pub const ERROR_UNKNOWN: c_int = 0;
pub const ERROR_CANCELLED: c_int = 1;
pub const ERROR_INVALID_ARGUMENT: c_int = 2;
pub const ERROR_BIND: c_int = 3;
pub const ERROR_CONNECT: c_int = 4;
pub const ERROR_ACCEPT: c_int = 5;
pub const ERROR_READ: c_int = 6;
pub const ERROR_WRITE: c_int = 7;
pub const ERROR_CLOSED: c_int = 8;
pub const ERROR_KEY: c_int = 9;
pub const ERROR_ADDR: c_int = 10;
pub const ERROR_TICKET: c_int = 11;
pub const ERROR_RELAY: c_int = 12;
pub const ERROR_SERVICES: c_int = 13;
pub const ERROR_DISCOVERY: c_int = 14;
/// A stream that carried 0-RTT data, after the peer refused it. The data must be resent.
pub const ERROR_ZERO_RTT_REJECTED: c_int = 15;

// ============================================================================
// C-ABI types
// ============================================================================

/// Opaque pointer wrapper. Carries the Kotlin `StableRef` to a suspended `Continuation`
/// across the FFI boundary; `Send`/`Sync` so it can be moved into a spawned future.
#[repr(C)]
pub struct Iroh4kPtr {
    pub ptr: *mut c_void,
}
unsafe impl Send for Iroh4kPtr {}
unsafe impl Sync for Iroh4kPtr {}

/// Releases the object behind [`Iroh4kResult::handle`].
///
/// Supplied per handle type, because only the producing domain knows what `T` is.
pub type HandleDrop = unsafe extern "C" fn(*mut c_void);

/// The single result type for every operation.
///
/// `error` is [`OK`] (-1) on success, otherwise one of the `ERROR_*` codes. Object returns
/// travel in `handle`, scalars in `i64_val`/`f64_val`, and everything structured in
/// `bytes` as a [`crate::codec`] payload.
#[repr(C)]
pub struct Iroh4kResult {
    pub error: c_int,
    pub error_message: *mut c_char,
    pub handle: *mut c_void,
    pub i64_val: i64,
    pub f64_val: f64,
    pub bytes: *mut u8,
    pub bytes_len: c_int,
}

impl Default for Iroh4kResult {
    fn default() -> Self {
        Self {
            error: OK,
            error_message: null_mut(),
            handle: null_mut(),
            i64_val: 0,
            f64_val: 0.0,
            bytes: null_mut(),
            bytes_len: 0,
        }
    }
}

impl Iroh4kResult {
    /// Moves the result onto the heap and leaks it. Kotlin owns it from here and must
    /// hand it back to [`free_result`].
    pub fn leak(self) -> *mut Iroh4kResult {
        Box::leak(Box::new(self))
    }
}

// ----------------------------------------------------------------------------
// Result constructors
// ----------------------------------------------------------------------------

pub fn ok_result() -> *mut Iroh4kResult {
    Iroh4kResult::default().leak()
}

pub fn i64_result(value: i64) -> *mut Iroh4kResult {
    Iroh4kResult {
        i64_val: value,
        ..Default::default()
    }
    .leak()
}

pub fn bytes_result(bytes: Vec<u8>) -> *mut Iroh4kResult {
    let len = bytes.len() as c_int;
    // `into_boxed_slice` trims excess capacity so `free_result` can reconstruct the Vec
    // with capacity == len.
    let ptr = Box::into_raw(bytes.into_boxed_slice()) as *mut u8;
    Iroh4kResult {
        bytes: ptr,
        bytes_len: len,
        ..Default::default()
    }
    .leak()
}

/// A result carrying a live object handle.
///
/// An async operation that *produces* an object must additionally tell [`crate::ops`] how to
/// release it — see `OpResult::with_handle` — so a cancel winning the deliver-once race cannot
/// strand a bound socket or a live QUIC connection.
pub fn handle_result(handle: *mut c_void) -> *mut Iroh4kResult {
    Iroh4kResult {
        handle,
        ..Default::default()
    }
    .leak()
}

pub fn error_result(code: c_int, message: impl AsRef<str>) -> *mut Iroh4kResult {
    let message = CString::new(message.as_ref()).unwrap_or_else(|_| {
        CString::new("error message contained an interior nul byte").expect("static string")
    });
    Iroh4kResult {
        error: code,
        error_message: message.into_raw(),
        ..Default::default()
    }
    .leak()
}

/// Flattens a result into the envelope the JNI facade returns as a Java `byte[]`.
///
/// The native facade reads the [`Iroh4kResult`] fields directly through cinterop; JNI has no
/// struct access, so the same fields are serialized here. Only the envelope differs between
/// the two paths — the `bytes` payload inside it is the shared [`crate::codec`] format, so
/// Kotlin's payload decoder is common to both.
///
/// Layout (big-endian): `i32 error`, optional error message, `i64 handle`, `i64 i64_val`,
/// `f64 f64_val`, optional `bytes`.
pub fn serialize_result(ptr: *mut Iroh4kResult) -> Vec<u8> {
    let mut w = crate::codec::Writer::new();
    if ptr.is_null() {
        w.i32(ERROR_UNKNOWN)
            .str("null result pointer")
            .i64(0)
            .i64(0)
            .f64(0.0)
            .i32(-1);
        return w.finish();
    }
    let r = unsafe { &*ptr };
    w.i32(r.error);
    if r.error_message.is_null() {
        w.i32(-1);
    } else {
        w.bytes(unsafe { CStr::from_ptr(r.error_message) }.to_bytes());
    }
    w.i64(r.handle as usize as i64);
    w.i64(r.i64_val);
    w.f64(r.f64_val);
    if r.bytes.is_null() {
        w.i32(-1);
    } else {
        w.bytes(unsafe { slice::from_raw_parts(r.bytes, r.bytes_len as usize) });
    }
    w.finish()
}

/// Frees a result previously handed to Kotlin. Every allocation reachable from the struct
/// is released here; Kotlin never frees Rust memory itself.
///
/// Deliberately does **not** touch `handle`: by the time Kotlin calls this it has already taken
/// ownership of the object. Releasing a handle that was never delivered is `crate::ops`' job,
/// since only it knows a result was discarded.
pub fn free_result(ptr: *mut Iroh4kResult) {
    if ptr.is_null() {
        return;
    }
    let result: Iroh4kResult = unsafe { *Box::from_raw(ptr) };
    if !result.error_message.is_null() {
        drop(unsafe { CString::from_raw(result.error_message) });
    }
    if !result.bytes.is_null() {
        let len = result.bytes_len as usize;
        drop(unsafe { Vec::from_raw_parts(result.bytes, len, len) });
    }
}

// ============================================================================
// Argument helpers
// ============================================================================

/// Borrows a C string as `&str`. Returns `""` for null.
///
/// # Safety
/// `ptr` must be null or point to a valid nul-terminated UTF-8 string.
pub unsafe fn c_str(ptr: *const c_char) -> &'static str {
    unsafe {
        if ptr.is_null() {
            return "";
        }
        CStr::from_ptr(ptr).to_str().unwrap_or("")
    }
}

/// Copies a caller-owned byte buffer into an owned `Vec`.
///
/// The copy is required: the buffer typically lives in a Kotlin `memScoped` block that is
/// freed as soon as the FFI call returns, while the future runs later on a tokio thread.
///
/// # Safety
/// `ptr` must be null or point to at least `len` readable bytes.
pub unsafe fn owned_bytes(ptr: *const u8, len: c_int) -> Vec<u8> {
    unsafe {
        if ptr.is_null() || len <= 0 {
            return Vec::new();
        }
        slice::from_raw_parts(ptr, len as usize).to_vec()
    }
}

// ============================================================================
// Runtime
// ============================================================================

static RUNTIME: OnceLock<Runtime> = OnceLock::new();

/// The shared multi-threaded tokio runtime, created on first use.
pub fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| Runtime::new().expect("failed to start the tokio runtime"))
}

// ============================================================================
// Operations
// ============================================================================

/// The version of the `iroh` crate this build links against. Kept in sync with
/// `Cargo.toml` by hand — there is no build-time way to read a dependency's version.
pub const IROH_VERSION: &str = "1.0.3";

/// `"<iroh4k version>+iroh<iroh version>"`, e.g. `"0.1.0+iroh1.0.3"`.
pub fn version() -> Vec<u8> {
    format!("{}+iroh{}", env!("CARGO_PKG_VERSION"), IROH_VERSION).into_bytes()
}

/// Counts sleeps that ran to completion — see [`smoke_sleep`].
static SLEEP_COMPLETIONS: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);

/// Sleeps for `millis`, then reports how long was requested.
///
/// Stands in for iroh's genuinely long-running operations (`accept`, stream reads) so
/// cancellation can be tested before those exist. Temporary: removed in M4/M5.
pub async fn smoke_sleep(millis: i64) -> *mut Iroh4kResult {
    tokio::time::sleep(std::time::Duration::from_millis(millis.max(0) as u64)).await;
    // Incremented only past the await, so an aborted task never reaches it. This is what lets a
    // test distinguish a genuinely aborted task from one still running whose result was merely
    // discarded by the deliver-once guard.
    SLEEP_COMPLETIONS.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    i64_result(millis)
}

/// How many [`smoke_sleep`] calls ran past their sleep.
pub fn smoke_sleep_completions() -> i64 {
    SLEEP_COMPLETIONS.load(std::sync::atomic::Ordering::Relaxed)
}

/// A record exercising every [`crate::codec`] primitive, so the Rust encoder and the Kotlin
/// decoder are verified against each other rather than each against its own assumptions.
///
/// Field order and values are asserted in `CommonSmokeTests`. Temporary: superseded by real
/// records (`ConnectionStats`, `PathSnapshot`, …) are what callers actually see.
pub fn smoke_record() -> Vec<u8> {
    let mut w = crate::codec::Writer::new();
    w.bool(true)
        .u8(200)
        .i32(-123_456)
        .i64(i64::MIN)
        .f64(0.5)
        .str("iroh4k")
        .opt_str(Some("present"))
        .opt_str(None)
        .seq(&[10i32, 20, 30], |w, v| {
            w.i32(*v);
        });
    w.finish()
}
