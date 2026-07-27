//! JNI bridge consumed by the JVM and Android (the shared library).
//!
//! Synchronous getters return their result immediately. Asynchronous operations use the same
//! [`crate::ops`] registry as the FFI path, split into **start / await / cancel**:
//!
//! ```text
//! val id = opStart(...)        // returns at once, task runs on the tokio runtime
//! val bytes = opAwait(id)      // blocks this Dispatchers.IO thread until done
//! opCancel(id)                 // from invokeOnCancellation; unblocks the awaiting thread
//! ```
//!
//! The split is what makes cancellation possible here. A single blocking call would pin a
//! `Dispatchers.IO` thread with nothing able to interrupt it — for an operation like `accept`,
//! forever. Splitting it means a cancel aborts the task and the awaiting thread returns with
//! `ERROR_CANCELLED`.
//!
//! No `JNI_OnLoad` or class caching is needed because the Rust side never calls back into Java.
//! The one place a `JavaVM` is retained at all is [`crate::android`], which hands it to
//! `ndk_context` for iroh's platform lookups — still not a callback into application code.
//!
//! Symbol names match the Kotlin object `tech.annexflow.iroh4k.Iroh4kJni`. Results are returned
//! as a single byte buffer produced by [`crate::core::serialize_result`] and decoded by Kotlin.

#![allow(non_snake_case)]

use crate::core::*;
use crate::ops::{self, OpResult};
use jni::objects::JClass;
use jni::sys::{jbyteArray, jlong};
use jni::{Env, EnvUnowned, Outcome};

/// Upgrades the native method's unowned attachment into an [`Env`] for the duration of `f`.
///
/// jni 0.22 split the FFI-safe argument type ([`EnvUnowned`]) from the type that actually calls
/// JNI ([`Env`]), so every JNI call now happens inside a closure. `with_env_no_catch` is the right
/// half of that pair here: the crate is built with `panic = "abort"`, so the `catch_unwind` in
/// `with_env` could never observe a panic anyway, and no JNI stack frame is pushed either way —
/// local references made inside `f` stay alive until the JVM pops the native method's own frame,
/// which is what lets [`finish`] hand its `byte[]` back to Kotlin.
pub(crate) fn with_env<'local, T>(
    env: &mut EnvUnowned<'local>,
    f: impl FnOnce(&mut Env<'local>) -> jni::errors::Result<T>,
) -> jni::errors::Result<T> {
    match env.with_env_no_catch(f).into_outcome() {
        Outcome::Ok(value) => Ok(value),
        Outcome::Err(error) => Err(error),
        // Unreachable: `with_env_no_catch` does not catch unwinds, and `panic = "abort"` means
        // there is nothing to catch. Resuming is still the only correct answer to a payload.
        Outcome::Panic(payload) => std::panic::resume_unwind(payload),
    }
}

/// Serializes the leaked result into a Java `byte[]`, then frees the native result.
///
/// Shared with every domain module's JNI facade: one place decides the envelope layout, so the
/// Kotlin side only ever has one format to decode.
pub(crate) fn finish(env: &mut EnvUnowned, result: *mut Iroh4kResult) -> jbyteArray {
    let bytes = serialize_result(result);
    free_result(result);
    with_env(env, |env| env.byte_array_from_slice(&bytes))
        .expect("failed to allocate result byte array")
        .into_raw()
}

// ============================================================================
// Operation control
// ============================================================================

/// Blocks until the operation completes or is cancelled.
#[unsafe(no_mangle)]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_opAwait(
    mut env: EnvUnowned,
    _class: JClass,
    op: jlong,
) -> jbyteArray {
    let result = ops::await_op(op);
    finish(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_opCancel(
    _env: EnvUnowned,
    _class: JClass,
    op: jlong,
) {
    ops::cancel_op(op);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_liveOpCount(
    _env: EnvUnowned,
    _class: JClass,
) -> jlong {
    ops::live_op_count()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_smokeSleepCompletions(
    _env: EnvUnowned,
    _class: JClass,
) -> jlong {
    smoke_sleep_completions()
}

// ============================================================================
// Synchronous
// ============================================================================

#[unsafe(no_mangle)]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_version(
    mut env: EnvUnowned,
    _class: JClass,
) -> jbyteArray {
    finish(&mut env, bytes_result(version()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_smokeRecord(
    mut env: EnvUnowned,
    _class: JClass,
) -> jbyteArray {
    finish(&mut env, bytes_result(smoke_record()))
}

// ============================================================================
// Asynchronous — each returns an operation id for opAwait/opCancel
// ============================================================================

#[unsafe(no_mangle)]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_smokeAsyncEchoStart(
    _env: EnvUnowned,
    _class: JClass,
    value: jlong,
) -> jlong {
    ops::spawn_channel(async move {
        tokio::task::yield_now().await;
        OpResult::new(i64_result(value))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_smokeAsyncErrorStart(
    _env: EnvUnowned,
    _class: JClass,
) -> jlong {
    ops::spawn_channel(async move {
        OpResult::new(error_result(ERROR_INVALID_ARGUMENT, "smoke test failure"))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_smokeAsyncSleepStart(
    _env: EnvUnowned,
    _class: JClass,
    millis: jlong,
) -> jlong {
    ops::spawn_channel(async move { OpResult::new(smoke_sleep(millis).await) })
}
