//! C ABI consumed by Kotlin/Native targets via cinterop (the static library).
//!
//! Synchronous getters return `*mut Iroh4kResult` directly. Asynchronous operations register
//! themselves with [`crate::ops`], spawn onto the shared tokio runtime, and **return an
//! operation id**; on completion `fun(callback, result)` resumes the suspended Kotlin coroutine,
//! where `callback` is the Kotlin `StableRef` to the `Continuation`. Kotlin passes the id to
//! [`iroh4k_op_cancel`] when the coroutine is cancelled.
//!
//! Result pointers are leaked here and freed by Kotlin via [`iroh4k_free_result`]. Mirrors the
//! cbindgen-generated header consumed by the `iroh4k.ffi` cinterop package.

use crate::core::*;
use crate::ops::{self, OpResult};
use std::ffi::c_void;

/// The completion callback type every async export takes.
type Completion = extern "C" fn(Iroh4kPtr, *mut Iroh4kResult);

// These no-op exports force cbindgen to emit the `#[repr(C)]` types (and everything they
// reference) into the generated C header — cbindgen only emits types that appear in an
// exported function signature.
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_iroh4k_ptr(_: Iroh4kPtr) {}
#[no_mangle]
pub extern "C" fn auto_generated_for_struct_iroh4k_result(_: Iroh4kResult) {}

#[no_mangle]
pub extern "C" fn iroh4k_free_result(ptr: *mut Iroh4kResult) {
    free_result(ptr);
}

// ============================================================================
// Operation control
// ============================================================================

/// Aborts a pending operation, resuming its continuation with `ERROR_CANCELLED`.
///
/// Safe to call at any time: an id that already completed is simply unknown, and the
/// deliver-once guard means a cancel racing a completion cannot resume twice.
#[no_mangle]
pub extern "C" fn iroh4k_op_cancel(op: i64) {
    ops::cancel_op(op);
}

/// Number of operations still registered. Test hook for asserting the registry drains.
#[no_mangle]
pub extern "C" fn iroh4k_live_op_count() -> i64 {
    ops::live_op_count()
}

/// Sleeps that ran to completion. Test hook proving a cancelled task was really aborted.
#[no_mangle]
pub extern "C" fn iroh4k_smoke_sleep_completions() -> i64 {
    smoke_sleep_completions()
}

// ============================================================================
// Synchronous
// ============================================================================

/// `"<iroh4k version>+iroh<iroh version>"` as UTF-8 bytes.
#[no_mangle]
pub extern "C" fn iroh4k_version() -> *mut Iroh4kResult {
    bytes_result(version())
}

/// A codec payload exercising every primitive, for cross-language round-trip testing.
#[no_mangle]
pub extern "C" fn iroh4k_smoke_record() -> *mut Iroh4kResult {
    bytes_result(smoke_record())
}

// ============================================================================
// Asynchronous
// ============================================================================

/// Smoke test for the async bridge: resumes the continuation with `value` after a tokio
/// round-trip. Verifies `StableRef` + `staticCFunction` resumption on a tokio worker thread.
#[no_mangle]
pub extern "C" fn iroh4k_smoke_async_echo(
    value: i64,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    ops::spawn_callback(callback, fun, async move {
        tokio::task::yield_now().await;
        OpResult::new(i64_result(value))
    })
}

/// Smoke test for the error path: resumes with `ERROR_INVALID_ARGUMENT` and a message, covering
/// `throwIfError()` and the ordinal decoding of `IrohError.Code`.
#[no_mangle]
pub extern "C" fn iroh4k_smoke_async_error(callback: *mut c_void, fun: Completion) -> i64 {
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(error_result(ERROR_INVALID_ARGUMENT, "smoke test failure"))
    })
}

/// Smoke test for cancellation: a long operation that must abort promptly when the coroutine is
/// cancelled. Stands in for `accept`/stream reads until M4/M5.
#[no_mangle]
pub extern "C" fn iroh4k_smoke_async_sleep(
    millis: i64,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    ops::spawn_callback(callback, fun, async move {
        OpResult::new(smoke_sleep(millis).await)
    })
}
