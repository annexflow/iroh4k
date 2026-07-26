//! Opaque handles for live iroh objects.
//!
//! The ten objects with identity and lifetime — `Endpoint`, `Connection`, `BiStream`,
//! `SendStream`, `RecvStream`, `Incoming`, `Accepting`, `Connecting`, the services client and
//! watchers — are owned by Rust and referenced from Kotlin as an opaque `*mut c_void`.
//!
//! Each handle is one `Arc` strong count converted with [`Arc::into_raw`]. Kotlin releases it
//! through the per-type `iroh4k_*_free` export, which reconstitutes the `Arc` and drops it.
//! Value types (keys, addresses, tickets, relay maps) deliberately do **not** use handles —
//! they cross the boundary as bytes and live entirely in Kotlin.
//!
//! Every function here is `unsafe` in the ordinary FFI sense: a handle must come from
//! [`into_handle`] for the same `T` and must not be used after being freed. Kotlin enforces
//! that with `AutoCloseable` wrappers plus a closed-flag guard, so misuse cannot originate
//! from the public API.

// The helpers are defined as a complete set now so the ownership rules live in one reviewed
// place; the per-type `iroh4k_*_free` exports that call them arrive with their objects in M3-M7.
#![allow(dead_code)]

use std::{ffi::c_void, sync::Arc};

/// Converts an owned value into a handle for Kotlin.
pub fn into_handle<T>(value: T) -> *mut c_void {
    Arc::into_raw(Arc::new(value)) as *mut c_void
}

/// Converts an existing `Arc` into a handle, sharing ownership rather than cloning the value.
pub fn arc_into_handle<T>(value: Arc<T>) -> *mut c_void {
    Arc::into_raw(value) as *mut c_void
}

/// Borrows the value behind a handle without affecting its refcount.
///
/// # Safety
/// `handle` must be a non-null handle produced by [`into_handle`]/[`arc_into_handle`] for `T`
/// and must still be live.
pub unsafe fn borrow<'a, T>(handle: *mut c_void) -> &'a T {
    debug_assert!(!handle.is_null(), "null iroh4k handle");
    &*(handle as *const T)
}

/// Clones the `Arc` behind a handle, for moving ownership into a spawned task.
///
/// The handle itself remains valid — the strong count is incremented, not transferred.
///
/// # Safety
/// As [`borrow`].
pub unsafe fn clone_arc<T>(handle: *mut c_void) -> Arc<T> {
    Arc::increment_strong_count(handle as *const T);
    Arc::from_raw(handle as *const T)
}

/// Releases a handle, dropping the value if this was the last reference.
///
/// Tolerates null so Kotlin's `close()` can be idempotent.
///
/// # Safety
/// `handle` must be null, or a handle produced by [`into_handle`]/[`arc_into_handle`] for `T`
/// that has not already been freed.
pub unsafe fn free<T>(handle: *mut c_void) {
    if handle.is_null() {
        return;
    }
    drop(Arc::from_raw(handle as *const T));
}
