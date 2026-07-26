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
//!
//! Symbol names match the Kotlin object `tech.annexflow.iroh4k.Iroh4kJni`. Results are returned
//! as a single byte buffer produced by [`crate::core::serialize_result`] and decoded by Kotlin.

#![allow(non_snake_case)]

use crate::core::*;
use crate::ops::{self, OpResult};
use jni::objects::JClass;
use jni::sys::{jbyteArray, jlong};
use jni::JNIEnv;

/// Serializes the leaked result into a Java `byte[]`, then frees the native result.
///
/// Shared with every domain module's JNI facade: one place decides the envelope layout, so the
/// Kotlin side only ever has one format to decode.
pub(crate) fn finish(env: &mut JNIEnv, result: *mut Iroh4kResult) -> jbyteArray {
    let bytes = serialize_result(result);
    free_result(result);
    env.byte_array_from_slice(&bytes)
        .expect("failed to allocate result byte array")
        .into_raw()
}

// ============================================================================
// Operation control
// ============================================================================

/// Blocks until the operation completes or is cancelled.
#[no_mangle]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_opAwait(
    mut env: JNIEnv,
    _class: JClass,
    op: jlong,
) -> jbyteArray {
    let result = ops::await_op(op);
    finish(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_opCancel(
    _env: JNIEnv,
    _class: JClass,
    op: jlong,
) {
    ops::cancel_op(op);
}

#[no_mangle]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_liveOpCount(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    ops::live_op_count()
}

#[no_mangle]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_smokeSleepCompletions(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    smoke_sleep_completions()
}

// ============================================================================
// Synchronous
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_version(
    mut env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    finish(&mut env, bytes_result(version()))
}

#[no_mangle]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_smokeRecord(
    mut env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    finish(&mut env, bytes_result(smoke_record()))
}

// ============================================================================
// Asynchronous — each returns an operation id for opAwait/opCancel
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_smokeAsyncEchoStart(
    _env: JNIEnv,
    _class: JClass,
    value: jlong,
) -> jlong {
    ops::spawn_channel(async move {
        tokio::task::yield_now().await;
        OpResult::new(i64_result(value))
    })
}

#[no_mangle]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_smokeAsyncErrorStart(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    ops::spawn_channel(async move {
        OpResult::new(error_result(ERROR_INVALID_ARGUMENT, "smoke test failure"))
    })
}

#[no_mangle]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kJni_smokeAsyncSleepStart(
    _env: JNIEnv,
    _class: JClass,
    millis: jlong,
) -> jlong {
    ops::spawn_channel(async move { OpResult::new(smoke_sleep(millis).await) })
}
