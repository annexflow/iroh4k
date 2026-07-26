//! Cancellable asynchronous operations.
//!
//! iroh has operations that block indefinitely — `accept`, stream reads, `online`, `closed` —
//! so coroutine cancellation has to reach into Rust and abort the task. That needs three
//! guarantees, and the design here exists to provide them:
//!
//! 1. **Deliver exactly once.** A task completing and a concurrent cancel race. If both
//!    delivered, Kotlin would resume one continuation twice (a crash on native, an
//!    `IllegalStateException` on the JVM). An [`AtomicBool`] decides the winner; the loser's
//!    result is freed.
//! 2. **A late cancel must be harmless.** Operations are addressed by an integer **id** rather
//!    than a raw pointer, so cancelling an already-finished operation is a map lookup that
//!    misses — not a use-after-free.
//! 3. **Symmetry across facades.** The JNI path cannot simply block a `Dispatchers.IO` thread
//!    inside `block_on`: nothing could interrupt it and an `accept` would pin the thread
//!    forever. So JNI drives the same registry through start/await/cancel — an abort makes the
//!    blocked thread's channel yield a `CANCELLED` result and return.
//!
//! Cleanup needs no cooperation from Kotlin. A callback-sink operation removes itself from the
//! registry as it delivers, and a channel-sink operation is removed by [`await_op`], so there
//! is no `release` call for a caller to forget.

use crate::core::{
    error_result, free_result, Iroh4kPtr, Iroh4kResult, ERROR_CANCELLED, ERROR_UNKNOWN,
};
use std::{
    collections::HashMap,
    ffi::c_void,
    future::Future,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{sync_channel, Receiver, SyncSender},
        Arc, Mutex, OnceLock,
    },
};

/// A leaked [`Iroh4kResult`] pointer, wrapped so it can be moved between threads.
///
/// Raw pointers are not `Send`, but the pointee is heap-owned and reaches exactly one consumer,
/// so handing it across threads is sound.
pub struct OpResult(*mut Iroh4kResult);

unsafe impl Send for OpResult {}

impl OpResult {
    pub fn new(ptr: *mut Iroh4kResult) -> Self {
        Self(ptr)
    }
}

/// Where a completed result goes.
enum Sink {
    /// Kotlin/Native: resume the pinned `Continuation` directly from the tokio thread.
    ///
    /// `callback` is the Kotlin `StableRef` address, held as `usize` because a raw pointer
    /// would make the state non-`Send`.
    Callback {
        callback: usize,
        fun: extern "C" fn(Iroh4kPtr, *mut Iroh4kResult),
    },
    /// JVM/Android: hand the result to the blocked JNI thread. Bounded at 1 — there is only
    /// ever one result — and `std::sync::mpsc` is used rather than a tokio channel so the
    /// waiting JVM thread never needs to enter the runtime.
    Channel {
        tx: SyncSender<OpResult>,
        rx: Mutex<Option<Receiver<OpResult>>>,
    },
}

struct OpState {
    id: u64,
    delivered: AtomicBool,
    sink: Sink,
    /// A tokio `JoinHandle`; dropping it merely detaches, so only an explicit `abort()` in
    /// [`cancel_op`] cancels the task. That is deliberate — an abort-on-drop handle here would
    /// abort the very task that is running [`OpState::deliver`].
    abort: Mutex<Option<tokio::task::JoinHandle<()>>>,
}

impl OpState {
    /// Delivers `result` unless this operation already completed or was cancelled.
    fn deliver(&self, result: *mut Iroh4kResult) {
        if self
            .delivered
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            // Lost the race against a cancel (or a duplicate completion). Freeing here is what
            // keeps the losing path from leaking.
            free_result(result);
            return;
        }
        match &self.sink {
            Sink::Callback { callback, fun } => {
                // Drop the registry entry first: once `fun` returns, Kotlin has resumed and may
                // already have issued the next operation. Safe to do while the task is running
                // because the `JoinHandle` detaches on drop rather than aborting.
                remove(self.id);
                fun(
                    Iroh4kPtr {
                        ptr: *callback as *mut c_void,
                    },
                    result,
                );
            }
            Sink::Channel { tx, .. } => {
                if tx.send(OpResult(result)).is_err() {
                    // Receiver gone: the operation was abandoned without being awaited.
                    free_result(result);
                }
            }
        }
    }
}

type Registry = Mutex<HashMap<u64, Arc<OpState>>>;

static OPS: OnceLock<Registry> = OnceLock::new();
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn registry() -> &'static Registry {
    OPS.get_or_init(Registry::default)
}

/// Looks up an operation, cloning the `Arc` so the registry lock is released before the state is
/// used. Returns `None` for an id that already completed.
fn lookup(id: i64) -> Option<Arc<OpState>> {
    if id <= 0 {
        return None;
    }
    registry().lock().ok()?.get(&(id as u64)).cloned()
}

fn remove(id: u64) {
    if let Ok(mut ops) = registry().lock() {
        ops.remove(&id);
    }
}

/// Registers `state` and spawns `fut`, returning the operation id.
fn start(state: Arc<OpState>, fut: impl Future<Output = OpResult> + Send + 'static) -> i64 {
    let id = state.id;
    registry()
        .lock()
        .expect("ops registry poisoned")
        .insert(id, Arc::clone(&state));

    let for_task = Arc::clone(&state);
    let handle = crate::core::runtime().spawn(async move {
        let result = fut.await;
        for_task.deliver(result.0);
    });
    // Storing the handle after spawning is fine: a cancel arriving in between finds `None` and
    // still wins the `delivered` race, so the task's later result is discarded.
    *state.abort.lock().expect("op abort lock poisoned") = Some(handle);

    id as i64
}

fn next_id() -> u64 {
    NEXT_ID.fetch_add(1, Ordering::Relaxed)
}

/// Spawns `fut` and resumes the Kotlin continuation at `callback` on completion.
///
/// Returns the operation id, which Kotlin passes to [`cancel_op`] on cancellation. No release
/// call is needed — delivery removes the operation itself.
pub fn spawn_callback(
    callback: *mut c_void,
    fun: extern "C" fn(Iroh4kPtr, *mut Iroh4kResult),
    fut: impl Future<Output = OpResult> + Send + 'static,
) -> i64 {
    start(
        Arc::new(OpState {
            id: next_id(),
            delivered: AtomicBool::new(false),
            sink: Sink::Callback {
                callback: callback as usize,
                fun,
            },
            abort: Mutex::new(None),
        }),
        fut,
    )
}

/// Spawns `fut` for the JNI path. The caller blocks in [`await_op`] with the returned id.
pub fn spawn_channel(fut: impl Future<Output = OpResult> + Send + 'static) -> i64 {
    let (tx, rx) = sync_channel(1);
    start(
        Arc::new(OpState {
            id: next_id(),
            delivered: AtomicBool::new(false),
            sink: Sink::Channel {
                tx,
                rx: Mutex::new(Some(rx)),
            },
            abort: Mutex::new(None),
        }),
        fut,
    )
}

/// Blocks until the operation completes or is cancelled, then removes it.
///
/// Safe to call from a JVM thread: it waits on a plain `std::sync::mpsc` receiver and never
/// enters the tokio runtime.
pub fn await_op(id: i64) -> *mut Iroh4kResult {
    let Some(state) = lookup(id) else {
        return error_result(ERROR_UNKNOWN, "unknown operation id");
    };
    let receiver = match &state.sink {
        Sink::Channel { rx, .. } => rx.lock().expect("op rx lock poisoned").take(),
        Sink::Callback { .. } => None,
    };
    let result = match receiver {
        Some(rx) => match rx.recv() {
            Ok(OpResult(ptr)) => ptr,
            Err(_) => error_result(ERROR_CANCELLED, "operation was dropped before completing"),
        },
        // Only reachable by misuse: awaiting a callback-sink op, or awaiting twice.
        None => error_result(
            ERROR_UNKNOWN,
            "operation is not awaitable or was already awaited",
        ),
    };
    remove(id as u64);
    result
}

/// Aborts the operation and delivers [`ERROR_CANCELLED`], unless it already completed.
///
/// A no-op for an unknown id, so racing against completion is safe.
pub fn cancel_op(id: i64) {
    let Some(state) = lookup(id) else { return };
    if let Some(handle) = state.abort.lock().expect("op abort lock poisoned").take() {
        handle.abort();
    }
    state.deliver(error_result(ERROR_CANCELLED, "operation was cancelled"));
}

/// Number of live operations. Exposed so tests can assert the registry drains rather than
/// growing one entry per call.
pub fn live_op_count() -> i64 {
    registry().lock().map(|m| m.len() as i64).unwrap_or(-1)
}
