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

// `arc_into_handle` and `Consumed::is_present` are part of the complete, reviewed set of
// ownership rules but have no caller today. Kept so the rules stay in one place rather than being
// rediscovered piecemeal by the next handle type.
#![allow(dead_code)]

use std::{any::TypeId, ffi::c_void, ops::Deref, sync::Arc};

/// A handle payload, tagged with the type it actually holds.
///
/// Handles cross the FFI boundary as untyped `*mut c_void`, so nothing in the type system stops a
/// `Connection` handle reaching a function that expects an `Incoming`. Without a tag that mistake
/// is undefined behaviour: the wrong type is projected over the memory and the first field read —
/// typically a mutex or a string pointer — is garbage. This project has seen exactly that twice,
/// as a `pthread_mutex_lock` and a `strlen` on an address that was really ASCII text.
///
/// The tag makes that case an error instead. `#[repr(C)]` with the tag first guarantees it sits at
/// offset 0 for every `T`, so reading it through the wrong `Tagged<U>` is still a well-defined
/// read of the same bytes — which is what lets the check run at all.
///
/// This does not rescue a pointer that was never a handle: nothing can. It closes the reachable
/// gap, which is a valid handle used as the wrong type.
#[repr(C)]
pub struct Tagged<T> {
    tag: TypeId,
    value: T,
}

impl<T> Deref for Tagged<T> {
    type Target = T;

    fn deref(&self) -> &T {
        &self.value
    }
}

/// Converts an owned value into a handle for Kotlin.
pub fn into_handle<T: 'static>(value: T) -> *mut c_void {
    Arc::into_raw(Arc::new(Tagged {
        tag: TypeId::of::<T>(),
        value,
    })) as *mut c_void
}

/// Converts an existing tagged `Arc` into a handle, sharing ownership rather than cloning.
pub fn arc_into_handle<T: 'static>(value: Arc<Tagged<T>>) -> *mut c_void {
    Arc::into_raw(value) as *mut c_void
}

/// Whether `handle` really points at a `T`.
///
/// # Safety
/// `handle` must be null or a handle produced by [`into_handle`] for *some* type.
unsafe fn is<T: 'static>(handle: *mut c_void) -> bool {
    unsafe { !handle.is_null() && (*(handle as *const Tagged<T>)).tag == TypeId::of::<T>() }
}

/// Borrows the value behind a handle without affecting its refcount.
///
/// `None` for a null handle or one holding a different type — callers report that as
/// `ERROR_CLOSED` rather than projecting the wrong type over the memory.
///
/// # Safety
/// `handle` must be null or a live handle produced by [`into_handle`]/[`arc_into_handle`] for
/// *some* type.
pub unsafe fn borrow<'a, T: 'static>(handle: *mut c_void) -> Option<&'a T> {
    unsafe {
        if !is::<T>(handle) {
            return None;
        }
        Some(&(*(handle as *const Tagged<T>)).value)
    }
}

/// Clones the `Arc` behind a handle, for moving ownership into a spawned task.
///
/// The handle itself remains valid — the strong count is incremented, not transferred.
///
/// The result derefs to `T`, so callers use it as before. `None` on a type mismatch.
///
/// # Safety
/// As [`borrow`].
pub unsafe fn clone_arc<T: 'static>(handle: *mut c_void) -> Option<Arc<Tagged<T>>> {
    unsafe {
        if !is::<T>(handle) {
            return None;
        }
        Arc::increment_strong_count(handle as *const Tagged<T>);
        Some(Arc::from_raw(handle as *const Tagged<T>))
    }
}

/// Releases a handle, dropping the value if this was the last reference.
///
/// Tolerates null so Kotlin's `close()` can be idempotent.
///
/// A handle whose type does not match is left alone rather than freed through the wrong type,
/// which would run the wrong destructor.
///
/// # Safety
/// `handle` must be null, or a handle produced by [`into_handle`]/[`arc_into_handle`] for *some*
/// type that has not already been freed.
pub unsafe fn free<T: 'static>(handle: *mut c_void) {
    unsafe {
        if !is::<T>(handle) {
            return;
        }
        drop(Arc::from_raw(handle as *const Tagged<T>));
    }
}

/// A handle to a value that is **consumed** by the operation that uses it.
///
/// Several iroh types take `self` rather than `&self`: `Incoming::{accept, refuse, retry,
/// ignore}` each consume the incoming connection, and `Accepting`/`Connecting` are futures, so
/// awaiting them consumes them too. A plain shared handle cannot express that — `borrow` only
/// yields `&T`, and there is no way to move the value out of an `Arc` that Kotlin still points at.
///
/// This wraps the value so exactly one caller can [`take`](Consumed::take) it. The second caller
/// gets `None` and the domain turns that into `ERROR_CLOSED`. Returning an error rather than
/// unwrapping matters more than usual here: the crate is built with `panic = "abort"`, so
/// `expect("already consumed")` on a double `accept()` would kill the host process instead of
/// raising a Kotlin exception.
pub struct Consumed<T> {
    value: std::sync::Mutex<Option<T>>,
}

impl<T> Consumed<T> {
    pub fn new(value: T) -> Self {
        Self {
            value: std::sync::Mutex::new(Some(value)),
        }
    }

    /// Takes the value, leaving the handle valid but empty. `None` on the second call.
    ///
    /// A poisoned lock also yields `None`: the value's state is unknown after a panic elsewhere,
    /// so refusing to hand it out is the safe answer.
    pub fn take(&self) -> Option<T> {
        self.value.lock().ok()?.take()
    }

    /// Whether the value is still present. Advisory only — it can change before a [`take`]
    /// lands, so `take` is what decides. Useful for the `&self` accessors iroh exposes
    /// alongside the consuming ones (`Incoming::remote_addr`, for instance).
    pub fn is_present(&self) -> bool {
        self.value.lock().map(|v| v.is_some()).unwrap_or(false)
    }

    /// Runs `f` against the value without consuming it, for iroh's `&self` accessors.
    pub fn with<R>(&self, f: impl FnOnce(&T) -> R) -> Option<R> {
        let guard = self.value.lock().ok()?;
        guard.as_ref().map(f)
    }

    /// Runs `f` against the value with mutable access, for iroh's `&mut self` accessors
    /// (`Connecting::alpn`, for instance).
    pub fn with_mut<R>(&self, f: impl FnOnce(&mut T) -> R) -> Option<R> {
        let mut guard = self.value.lock().ok()?;
        guard.as_mut().map(f)
    }
}
