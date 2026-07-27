//! Android process initialisation.
//!
//! Several crates underneath iroh reach the Android platform through
//! [`ndk_context`](https://crates.io/crates/ndk-context): `hickory-resolver` reads the system DNS
//! servers off `LinkProperties`, and `netdev` (through `netwatch`) enumerates interfaces. Both ask
//! `ndk_context` for the process's `JavaVM` and `Context`, and `ndk_context::android_context()` is
//! an `expect`:
//!
//! ```text
//! Fatal signal 6 (SIGABRT) in tid (tokio-rt-worker)
//! Abort message: 'android context was not initialized'
//! ```
//!
//! With `panic = "abort"` that is the whole process, not an exception Kotlin could catch, and it
//! happens inside `Endpoint::bind` — before any connection is attempted. So on Android this has to
//! run once, at startup, before anything else in this library is touched. `androidx.startup` does
//! that automatically for consumers; see `Iroh4kInitializer` in `androidMain`.
//!
//! ## Why this does not break the no-reverse-callbacks rule
//!
//! iroh4k's own code still never calls into Kotlin: there is no `JNI_OnLoad`, no cached class, no
//! `AttachCurrentThread` in this crate. What is stored here is the `JavaVM` pointer and a global
//! reference to the application `Context`, which *dependencies* use to call **Android framework**
//! APIs — never application code. That is a different thing from a reverse callback, and it is the
//! only way to reach `LinkProperties` at all.
//!
//! The approach mirrors upstream's own `iroh-ffi`, which installs the context the same way.

use jni::JNIEnv;
use jni::objects::{JClass, JObject};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean};
use std::sync::Once;
use std::sync::atomic::{AtomicBool, Ordering};

/// Guards the one-shot initialisation.
///
/// `ndk_context::initialize_android_context` asserts that nothing was installed before it, so a
/// second call would abort exactly like the missing context does. `Once` makes that impossible
/// even if two threads call in at the same time, which an app with several entry points can do.
static INIT: Once = Once::new();

/// Whether the context was installed successfully, so a caller can be told rather than discovering
/// it later as a crash on a tokio worker.
static INSTALLED: AtomicBool = AtomicBool::new(false);

/// Installs the process's `JavaVM` and application `Context` for the crates that need them.
///
/// Returns `false` if the JNI calls failed, in which case nothing was stored and Kotlin raises the
/// error where it can still be attributed. Calling it repeatedly is safe and does nothing after
/// the first success: the value returned is then simply that of the first call.
///
/// Never panics — the FFI boundary must not unwind, and a panic here would be indistinguishable
/// from the abort this function exists to prevent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_tech_annexflow_iroh4k_Iroh4kAndroidJni_installContext(
    env: JNIEnv,
    _class: JClass,
    context: JObject,
) -> jboolean {
    INIT.call_once(|| {
        let Ok(vm) = env.get_java_vm() else { return };
        let Ok(global) = env.new_global_ref(&context) else {
            return;
        };

        // SAFETY: both pointers come from live JNI handles. The `JavaVM` is process-wide, and the
        // global reference keeps the `Context` alive; `ndk_context` stores the raw pointers and
        // never takes ownership, so both must outlive every use — which is the process.
        unsafe {
            ndk_context::initialize_android_context(
                vm.get_java_vm_pointer() as *mut std::ffi::c_void,
                global.as_obj().as_raw() as *mut std::ffi::c_void,
            );
        }

        // Deliberate: dropping the `GlobalRef` would delete the JNI global reference while
        // `ndk_context` still hands out the raw pointer. It is one reference held for the lifetime
        // of the process, which is what every consumer of `ndk_context` does.
        std::mem::forget(global);

        INSTALLED.store(true, Ordering::Release);
    });

    if INSTALLED.load(Ordering::Acquire) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
