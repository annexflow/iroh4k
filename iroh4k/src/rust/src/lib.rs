//! iroh4k — Kotlin Multiplatform bindings for [iroh](https://iroh.computer).
//!
//! A single Rust core ([`core`]) backed by the `iroh` crate is exposed two ways:
//!
//! - [`ffi`]: `#[no_mangle] extern "C"` functions consumed by Kotlin/Native targets via
//!   cinterop (the static library). Asynchronous: a completion callback resumes the
//!   suspended Kotlin coroutine.
//! - [`jni`]: JNI exports consumed by the JVM and Android (the shared library). Blocking:
//!   each call drives the tokio runtime to completion and returns the marshalled result,
//!   while Kotlin keeps the call off the main thread via `Dispatchers.IO`.
//!
//! Structured values never get their own `#[repr(C)]` struct — they are encoded by
//! [`codec`] into a length-prefixed big-endian buffer and decoded by shared Kotlin
//! `commonMain` code, so both facades share one decoder.
//!
//! Rust never calls back into Kotlin except through the one-shot completion callback on
//! the FFI path. Watchers are pull-based and the protocol router lives in Kotlin, so no
//! `JNI_OnLoad` / `AttachCurrentThread` machinery is needed.
//!
//! The `jni` module is unavailable on iOS, which uses the FFI/cinterop path exclusively.

// Infrastructure shared by every domain.
mod codec;
mod core;
mod ffi;
mod handle;
mod ops;

#[cfg(not(target_os = "ios"))]
mod jni;

// Domain modules. Each owns its shared logic *and* both facades' exports for it, so the
// modules stay independently editable — the same reason iroh-ffi splits into key.rs, net.rs,
// relay.rs and ticket.rs rather than one flat FFI surface.
mod addr;
mod connection;
mod endpoint;
mod keys;
mod relay;
mod services;
mod stream;
mod watch;
