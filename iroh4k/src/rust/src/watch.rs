//! Watchers and event streams as pull-based cursors.
//!
//! Owned by the watch domain. Contains the shared logic plus both facades' exports for it:
//! `#[unsafe(no_mangle)] extern "C"` for cinterop, `#[cfg(not(target_os = "ios"))] Java_*` for JNI.
//!
//! Unlike iroh-ffi, which registers foreign callbacks that Rust invokes, every stream here is a
//! handle with a `next()` operation — async one-shot on the FFI path, blocking on JNI — so both
//! facades share one Kotlin `flow { }` and Rust never calls into Kotlin.
//!
//! ## The forwarding task
//!
//! Every watcher iroh exposes is awkward to store in a handle, and each is awkward in its own way:
//!
//! - `Connection::paths_stream(&self) -> PathListStream<'_>` **borrows the connection**. That is
//!   worse than the borrowed *futures* `connection.rs` deals with: a future can be awaited entirely
//!   inside one spawned block, but a stream has to survive across many `next()` calls, so there is
//!   no block for the borrow to live in.
//! - `Watcher::stream(mut self)` **consumes** the watcher, and `Watcher::updated(&mut self)` hands
//!   back a borrowed future needing `&mut` — neither of which a shared handle can offer.
//! - `Connection::path_events()` is the one that is genuinely `'static`.
//!
//! So none of them is stored. Instead each watcher is a **forwarding task**: one tokio task owns
//! whatever clone it needs (`endpoint::endpoint_clone`, `connection::connection_clone`), creates the
//! stream *inside* itself, encodes each item with [`crate::codec`], and pushes the bytes into a
//! channel. The handle owns only the receiving end, which is `'static` by construction. Dropping the
//! handle drops the receiver and aborts the task — see [`WatchSlot`]'s [`Drop`].
//!
//! One mechanism for all four rather than four special cases, and the payload is always `Vec<u8>`,
//! so there is exactly one [`next`] operation and one Kotlin flow over it.
//!
//! ## Ending the stream
//!
//! `next()` answers [`ENDED`] in `i64_val` with no payload once the underlying stream has finished,
//! which Kotlin turns into the end of the `Flow`. That happens when the forwarding task ends: the
//! source ended, or the task was aborted.
//!
//! Two things have to end it, and each needs its own arrangement:
//!
//! - **The object was closed.** `Endpoint::watch_addr` and `Endpoint::home_relay_status` are
//!   documented as staying connected across a `close()` and disconnecting only when the *last clone*
//!   of the endpoint is dropped — an `n0_watcher::Direct` holds a `Weak`, not a strong reference. So
//!   the task drops its endpoint clone as soon as the watcher exists, and pairs the loop with
//!   `Endpoint::closed()` (which iroh documents as holding no clone either). Both endings are then
//!   covered: `Endpoint.shutdown()` resolves `closed()`, and `Endpoint.close()` releasing the last
//!   handle disconnects the watcher.
//! - **The collector went away.** Kotlin's flow closes the watcher handle when collection ends,
//!   which aborts the task.
//!
//! [`watch_paths`] is the exception: `paths_stream` borrows, so its task must hold a `Connection`
//! clone for as long as it runs — exactly as a blocked `accept_bi` in [`crate::stream`] does. Its
//! stream ends when the connection is *closed* (by either side) rather than when Kotlin merely
//! releases its handle, and `Connection.watchPaths` documents that.
//!
//! ## Channel capacity, chosen per stream
//!
//! An `n0-watcher` is **latest-value**: it stores one value, and a consumer that falls behind sees
//! the newest state rather than a backlog. Coalescing is behaviour to preserve, not a compromise — a
//! slow consumer of `watch_addr` wants the current address, not every address it used to have. So
//! [`watch_addr`], [`watch_home_relay`] and [`watch_paths`] forward through a
//! [`tokio::sync::watch`] channel, which coalesces by construction: capacity one, a newer value
//! replaces an older, no unbounded growth and no writer left waiting.
//!
//! [`watch_path_events`] is different in kind. A `PathEvent` is an *event*, not a state — dropping
//! an `Opened` would leave a consumer believing a path never existed — so it forwards through a
//! bounded [`tokio::sync::mpsc`] channel of [`EVENT_CAPACITY`] and the task **waits** for room
//! rather than discarding anything. Backpressure then reaches iroh's own per-connection broadcast,
//! whose designed answer to a slow subscriber is to yield `PathEvent::Lagged { missed }` — forwarded
//! like any other event. So loss is still possible (the network cannot be asked to pause), but it is
//! reported rather than silent, and iroh decides when it happens rather than a policy invented here.
//!
//! ## Codec layout
//!
//! Defined here, mirrored by the decoder in `Watch.kt`; the two must be changed together. The
//! transport-address tags are deliberately **the same numbering as `addr.rs`, `endpoint.rs` and
//! `connection.rs`**, so the whole binding keeps one tag family for "relay / ip / custom / something
//! newer", and `PathSnapshot` is byte-for-byte the layout `connection.rs` writes for `paths()`.
//!
//! ```text
//! watch_addr           → EndpointAddr, as `endpoint.rs` writes it
//!
//! watch_home_relay     → i32 count, then count ×
//!                          str  relay URL
//!                          u8   1 if connected
//!                          str? most recent connection error, absent while connected
//!
//! watch_paths          → i32 count, then count × PathSnapshot
//!
//! watch_path_events    → u8 0 Opened   + i64 path id + TransportAddr + LocalTransportAddr
//!                        u8 1 Closed   + PathSnapshot (final statistics)
//!                        u8 2 Selected + i64 path id + TransportAddr + LocalTransportAddr
//!                        u8 3 Lagged   + i64 events missed
//!                        u8 4 Unknown  + str Debug text
//! ```

use std::{
    ffi::c_void,
    future::Future,
    sync::{
        Arc,
        atomic::{AtomicI64, Ordering},
    },
};

use iroh::{
    Endpoint, TransportAddr, Watcher,
    endpoint::{Connection, LocalTransportAddr, Path, PathEvent, PathStats, RelayStatus, UdpStats},
};
use n0_future::StreamExt;
use tokio::sync::{Mutex, mpsc, watch};

use crate::addr::{
    TAG_CUSTOM, TAG_IP, TAG_RELAY, TAG_UNKNOWN, write_endpoint_addr, write_transport_addr,
};
use crate::codec::Writer;
use crate::connection::{Completion, Tracked, connection_clone, released, share};
use crate::core::{Iroh4kResult, bytes_result, handle_result, i64_result};
use crate::endpoint::endpoint_clone;
use crate::handle::{self, Tagged};
use crate::ops::{self, OpResult};

// ============================================================================
// Wire protocol constants — shared with `Watch.kt`. Append only.
// ============================================================================

/// Discriminators for an optional record, as `connection.rs` writes them.
const ABSENT: u8 = 0;
const PRESENT: u8 = 1;

/// `PathEvent` discriminators.
const EVENT_OPENED: u8 = 0;
const EVENT_CLOSED: u8 = 1;
const EVENT_SELECTED: u8 = 2;
const EVENT_LAGGED: u8 = 3;
const EVENT_UNKNOWN: u8 = 4;

/// How many path events one watcher buffers before the forwarding task waits for room.
///
/// Matched to iroh's own per-connection broadcast capacity of 8, doubled so that an ordinary burst —
/// a path opening, being selected, and the previous one closing — never reaches back into the
/// broadcast at all. Beyond it the task waits rather than dropping, and any loss becomes iroh's own
/// `PathEvent::Lagged`. See the note on capacity at the top of this module.
const EVENT_CAPACITY: usize = 16;

/// The sentinel `next()` uses to say the stream has ended, mirroring `stream.rs`'s end of stream.
///
/// A real value can be an *empty* payload — `watch_paths` on a connection with no open paths writes
/// a count of zero — so absence cannot be signalled by the payload alone.
const ENDED: i64 = -1;

// ============================================================================
// Handle payload
// ============================================================================

/// Watcher handles still alive.
///
/// Its own counter rather than the endpoint's or the connection's, for the reason `stream.rs` gives:
/// a watcher leaked by a collect/cancel loop has to be visible even while endpoints and connections
/// are being created and released around it.
static LIVE_HANDLES: AtomicI64 = AtomicI64::new(0);

/// The receiving end of one forwarding task, in whichever flavour that stream needs.
///
/// Both are behind a **`tokio::sync::Mutex`** rather than a `std` one for the reason `stream.rs`
/// documents: [`next`] holds the guard across a suspension point, and a `std::sync::MutexGuard` is
/// not `Send`, so a future holding one could not be spawned onto the runtime at all.
enum Cursor {
    /// Latest-value, coalescing. `None` in the slot is the initial state, never a delivered value.
    Latest(Mutex<watch::Receiver<Option<Vec<u8>>>>),
    /// Every value, in order, with backpressure. See the capacity note above.
    Every(Mutex<mpsc::Receiver<Vec<u8>>>),
}

/// What a watcher handle points at: one cursor, and the task feeding it.
struct WatchSlot {
    cursor: Cursor,
    /// The forwarding task, aborted when this payload drops.
    ///
    /// The opposite choice from [`crate::ops`], deliberately: there the `JoinHandle` must *detach*
    /// on drop, because the task it tracks is the one delivering the result. Here the task exists
    /// only to feed this handle, so a released handle has to take it with it — otherwise a cancelled
    /// collector would leave a task forwarding into a channel nobody reads, holding an endpoint or a
    /// connection clone alive for as long as the process runs.
    task: tokio::task::JoinHandle<()>,
}

impl Drop for WatchSlot {
    fn drop(&mut self) {
        self.task.abort();
    }
}

/// The owned reference an async operation carries; `Tagged` rejects a wrong-type handle and
/// derefs to the payload.
type WatchShared = Arc<Tagged<Tracked<WatchSlot>>>;

type WatchHandle = Tracked<WatchSlot>;

/// Wraps a cursor and its task as a handle payload, counting it into [`LIVE_HANDLES`].
fn slot(cursor: Cursor, task: tokio::task::JoinHandle<()>) -> *mut Iroh4kResult {
    handle_result(handle::into_handle(Tracked::new(
        &LIVE_HANDLES,
        WatchSlot { cursor, task },
    )))
}

// ============================================================================
// Shared logic — building a watcher
// ============================================================================

/// Spawns a latest-value forwarding task and hands back the handle for its receiver.
///
/// `make` is given the sending half and returns the task body. A factory rather than a plain future
/// so the sender can be moved into the body without the caller naming the channel type — and, more
/// to the point, so the body is built *after* the channel exists, which is what lets a body drop its
/// endpoint or connection clone the moment its stream is constructed.
fn latest_watcher<F, Fut>(make: F) -> *mut Iroh4kResult
where
    F: FnOnce(watch::Sender<Option<Vec<u8>>>) -> Fut,
    Fut: Future<Output = ()> + Send + 'static,
{
    let (tx, rx) = watch::channel(None);
    let task = crate::core::runtime().spawn(make(tx));
    slot(Cursor::Latest(Mutex::new(rx)), task)
}

/// Spawns an every-value forwarding task and hands back the handle for its receiver.
///
/// The sibling of [`latest_watcher`] for a stream whose items are events rather than states.
fn event_watcher<F, Fut>(make: F) -> *mut Iroh4kResult
where
    F: FnOnce(mpsc::Sender<Vec<u8>>) -> Fut,
    Fut: Future<Output = ()> + Send + 'static,
{
    let (tx, rx) = mpsc::channel(EVENT_CAPACITY);
    let task = crate::core::runtime().spawn(make(tx));
    slot(Cursor::Every(Mutex::new(rx)), task)
}

// ============================================================================
// Shared logic — the four watchers
// ============================================================================

/// Watches the endpoint's own `EndpointAddr`: its id plus every address it believes it is
/// reachable at.
///
/// The watcher is built from a clone that is then dropped, which is what makes the stream end when
/// Kotlin releases the endpoint — see the note on ending a stream at the top of this module. Pairing
/// the loop with `closed()` covers the other ending, a graceful `shutdown()`.
fn watch_addr(endpoint: Endpoint) -> *mut Iroh4kResult {
    latest_watcher(move |tx| async move {
        let closed = endpoint.closed();
        let mut stream = endpoint.watch_addr().stream();
        drop(endpoint);
        closed
            .run_until(async move {
                while let Some(addr) = stream.next().await {
                    let mut w = Writer::new();
                    write_endpoint_addr(&mut w, &addr);
                    // `Err` means the handle was released between two updates: nothing left to feed.
                    if tx.send(Some(w.finish())).is_err() {
                        break;
                    }
                }
            })
            .await;
    })
}

/// Watches the connection status of the endpoint's home relays.
///
/// The value stays empty until the endpoint has picked a home relay, and forever when no relays are
/// configured — which is why `Endpoint.watchHomeRelay` documents that a relay-less endpoint may
/// never emit anything at all.
fn watch_home_relay(endpoint: Endpoint) -> *mut Iroh4kResult {
    latest_watcher(move |tx| async move {
        let closed = endpoint.closed();
        let mut stream = endpoint.home_relay_status().stream();
        drop(endpoint);
        closed
            .run_until(async move {
                while let Some(statuses) = stream.next().await {
                    if tx.send(Some(relay_statuses(&statuses))).is_err() {
                        break;
                    }
                }
            })
            .await;
    })
}

/// Watches the connection's open network paths, one snapshot per change.
///
/// The only watcher whose task must keep its clone: `paths_stream` borrows the connection, so the
/// borrow lives inside this task's own state and the connection has to outlive it. That is the
/// arrangement a blocked `accept_bi` already has, and it means the stream ends when the connection
/// is *closed* rather than when Kotlin releases its handle.
fn watch_paths(connection: Connection) -> *mut Iroh4kResult {
    latest_watcher(move |tx| async move {
        let mut stream = connection.paths_stream();
        while let Some(paths) = stream.next().await {
            let mut w = Writer::new();
            w.i32(paths.len() as i32);
            for path in paths.iter() {
                write_path(&mut w, &path);
            }
            if tx.send(Some(w.finish())).is_err() {
                break;
            }
        }
    })
}

/// Watches individual path lifecycle events on the connection.
///
/// The one iroh stream that is already `'static`, so the clone is dropped as soon as the stream
/// exists and this task does not keep the connection open. The stream ends by itself when the
/// connection's path actor goes away, which is what makes a released connection end the flow.
fn watch_path_events(connection: Connection) -> *mut Iroh4kResult {
    event_watcher(move |tx| async move {
        let mut stream = connection.path_events();
        drop(connection);
        while let Some(event) = stream.next().await {
            // Waits for room rather than dropping: these are events, not states. See the capacity
            // note at the top of this module.
            if tx.send(path_event(&event)).await.is_err() {
                break;
            }
        }
    })
}

// ============================================================================
// Shared logic — reading the cursor
// ============================================================================

/// Answers the next item of a watcher, suspending until there is one.
///
/// Cancel-safe on both arms: `watch::Receiver::changed` and `mpsc::Receiver::recv` both leave the
/// cursor untouched when their future is dropped, so a cancelled `next()` loses nothing — which
/// matters, because Kotlin cancels one every time a collector is cancelled between items.
///
/// The end of the stream is reported as [`ENDED`] with no payload rather than as an error: a watcher
/// that finished did not fail, and Kotlin turns it into the end of the `Flow`.
async fn next(slot: Option<WatchShared>) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let payload = match &slot.cursor {
        Cursor::Latest(cursor) => {
            let mut cursor = cursor.lock().await;
            // `changed()` reports a value that arrived before the sender was dropped and only then
            // reports the disconnection, so a watcher's last update is never lost.
            match cursor.changed().await {
                Ok(()) => cursor.borrow_and_update().clone(),
                Err(_) => None,
            }
        }
        Cursor::Every(cursor) => cursor.lock().await.recv().await,
    };
    OpResult::new(match payload {
        Some(bytes) => bytes_result(bytes),
        None => i64_result(ENDED),
    })
}

// ============================================================================
// Encoding
//
// These are near-duplicates of writers that are private to `endpoint.rs` and `connection.rs`, and
// the note there applies here word for word: the copies should be one `pub(crate)` set, and the
// shape is repeated rather than *changed* precisely so Kotlin keeps one decoder per type. A second
// layout for `TransportAddr` or `PathSnapshot` would be the real mistake.
// ============================================================================

/// Writes the local end of a path, whose IP and custom variants are optional because iroh's are.
fn write_local_addr(w: &mut Writer, addr: &LocalTransportAddr) {
    match addr {
        LocalTransportAddr::Relay(url) => {
            w.u8(TAG_RELAY).str(&url.to_string());
        }
        LocalTransportAddr::Ip(ip) => {
            w.u8(TAG_IP).opt_str(ip.map(|ip| ip.to_string()).as_deref());
        }
        LocalTransportAddr::Custom(custom) => {
            w.u8(TAG_CUSTOM);
            match custom {
                None => {
                    w.u8(ABSENT);
                }
                Some(custom) => {
                    w.u8(PRESENT).i64(custom.id() as i64).bytes(custom.data());
                }
            }
        }
        // `#[non_exhaustive]`, and with no `Display` — so `Debug`, as `connection.rs` does.
        other => {
            w.u8(TAG_UNKNOWN).str(&format!("{other:?}"));
        }
    }
}

fn write_udp_stats(w: &mut Writer, stats: &UdpStats) {
    // `UdpStats::ios` is deprecated upstream and documented as always zero, so it is not carried.
    w.i64(stats.datagrams as i64).i64(stats.bytes as i64);
}

/// The number behind a `PathId`.
///
/// noq keeps `PathId::as_u32` crate-private, so `Display` — documented as, and implemented as, the
/// plain integer — is the only public route to it. `connection.rs` recovers it the same way and for
/// the same reason: without a comparable id, two snapshots of one connection could not be lined up.
fn path_id(id: impl std::fmt::Display) -> i64 {
    id.to_string().parse::<i64>().unwrap_or(-1)
}

/// Writes one `PathSnapshot`, in exactly the layout `connection.rs` writes for `paths()`.
fn write_path_snapshot(
    w: &mut Writer,
    id: i64,
    remote: &TransportAddr,
    local: &LocalTransportAddr,
    is_selected: bool,
    stats: &PathStats,
) {
    w.i64(id);
    write_transport_addr(w, remote);
    write_local_addr(w, local);
    w.bool(is_selected);
    w.i64(i64::try_from(stats.rtt.as_micros()).unwrap_or(i64::MAX))
        .i64(stats.cwnd as i64)
        .i32(i32::from(stats.current_mtu))
        .i64(stats.lost_packets as i64)
        .i64(stats.lost_bytes as i64)
        .i64(stats.congestion_events as i64);
    write_udp_stats(w, &stats.udp_tx);
    write_udp_stats(w, &stats.udp_rx);
}

/// Writes one open path out of a `PathList` snapshot.
fn write_path(w: &mut Writer, path: &Path<'_>) {
    write_path_snapshot(
        w,
        path_id(path.id()),
        path.remote_addr(),
        path.local_addr(),
        path.is_selected(),
        &path.stats(),
    );
}

/// The home relays' connection status, as a codec payload.
///
/// `last_error` is iroh's most recent failed connection attempt, absent while the relay is
/// connected, so it rides as an optional string rather than as an error code: it is a diagnostic
/// about a relay, not a failure of this call.
fn relay_statuses(statuses: &[RelayStatus]) -> Vec<u8> {
    let mut w = Writer::new();
    w.i32(statuses.len() as i32);
    for status in statuses {
        w.str(&status.url().to_string())
            .bool(status.is_connected())
            .opt_str(
                status
                    .last_error()
                    .map(|error| error.to_string())
                    .as_deref(),
            );
    }
    w.finish()
}

/// One `PathEvent`, as a codec payload.
///
/// `Closed` carries a full `PathSnapshot` with `is_selected` false. That is not an invented value: a
/// path that has closed is by definition not the one application data is going over, and reusing the
/// snapshot shape means Kotlin decodes a closed path's final statistics with the same reader it uses
/// for an open one.
fn path_event(event: &PathEvent) -> Vec<u8> {
    let mut w = Writer::new();
    match event {
        PathEvent::Opened {
            id,
            remote_addr,
            local_addr,
            ..
        } => {
            w.u8(EVENT_OPENED).i64(path_id(id));
            write_transport_addr(&mut w, remote_addr);
            write_local_addr(&mut w, local_addr);
        }
        PathEvent::Closed {
            id,
            remote_addr,
            local_addr,
            last_stats,
            ..
        } => {
            w.u8(EVENT_CLOSED);
            write_path_snapshot(
                &mut w,
                path_id(id),
                remote_addr,
                local_addr,
                false,
                last_stats,
            );
        }
        PathEvent::Selected {
            id,
            remote_addr,
            local_addr,
            ..
        } => {
            w.u8(EVENT_SELECTED).i64(path_id(id));
            write_transport_addr(&mut w, remote_addr);
            write_local_addr(&mut w, local_addr);
        }
        PathEvent::Lagged { missed, .. } => {
            w.u8(EVENT_LAGGED)
                .i64(i64::try_from(*missed).unwrap_or(i64::MAX));
        }
        // `PathEvent` is `#[non_exhaustive]` and has no `Display`: a future iroh variant is surfaced
        // with its `Debug` text rather than dropped, as every other `#[non_exhaustive]` decode here.
        other => {
            w.u8(EVENT_UNKNOWN).str(&format!("{other:?}"));
        }
    }
    w.finish()
}

// ============================================================================
// C ABI — Kotlin/Native via cinterop
//
// The four creators are **synchronous**: building a watcher never waits on anything, and the task it
// spawns is what does the waiting. Only `next` is asynchronous, and only it can be cancelled.
// ============================================================================

/// Watcher handles still alive. Test hook for asserting handles do not leak.
#[unsafe(no_mangle)]
pub extern "C" fn iroh4k_watch_live_handle_count() -> i64 {
    LIVE_HANDLES.load(Ordering::Relaxed)
}

/// Releases a watcher handle, ending its forwarding task.
///
/// Tolerates null so Kotlin's `close()` can be idempotent.
///
/// # Safety
/// `handle` must be null, or a watcher handle from this module that has not been freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_watch_free(handle: *mut c_void) {
    unsafe {
        handle::free::<WatchHandle>(handle);
    }
}

/// Starts watching an endpoint's `EndpointAddr`. Returns a watcher handle.
///
/// # Safety
/// `handle` must satisfy `endpoint::endpoint_clone`'s contract for an endpoint handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_watch_addr(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        match endpoint_clone(handle) {
            Some(endpoint) => watch_addr(endpoint),
            None => released(),
        }
    }
}

/// Starts watching an endpoint's home relay statuses. Returns a watcher handle.
///
/// # Safety
/// As [`iroh4k_endpoint_watch_addr`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_watch_home_relay(
    handle: *mut c_void,
) -> *mut Iroh4kResult {
    unsafe {
        match endpoint_clone(handle) {
            Some(endpoint) => watch_home_relay(endpoint),
            None => released(),
        }
    }
}

/// Starts watching a connection's open paths. Returns a watcher handle.
///
/// # Safety
/// `handle` must satisfy `connection::connection_clone`'s contract for a connection handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_watch_paths(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        match connection_clone(handle) {
            Some(connection) => watch_paths(connection),
            None => released(),
        }
    }
}

/// Starts watching a connection's path events. Returns a watcher handle.
///
/// # Safety
/// As [`iroh4k_connection_watch_paths`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_watch_path_events(
    handle: *mut c_void,
) -> *mut Iroh4kResult {
    unsafe {
        match connection_clone(handle) {
            Some(connection) => watch_path_events(connection),
            None => released(),
        }
    }
}

/// The watcher's next item; [`ENDED`] in `i64_val` with no payload once it has finished.
/// Asynchronous.
///
/// # Safety
/// `handle` must satisfy `connection::share`'s contract for a watcher handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_watch_next(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<WatchSlot>(handle);
        ops::spawn_callback(callback, fun, next(slot))
    }
}

// ============================================================================
// JNI — JVM and Android via the shared library
//
// Unavailable on iOS, which uses the FFI/cinterop path exclusively. Symbol names match the Kotlin
// object `tech.annexflow.iroh4k.WatchJni`. Handles travel as `jlong`.
// ============================================================================

#[cfg(not(target_os = "ios"))]
#[allow(non_snake_case)]
mod jni_facade {
    use super::*;
    use jni::JNIEnv;
    use jni::objects::JClass;
    use jni::sys::{jbyteArray, jlong};

    use crate::jni::finish;

    /// Reinterprets a `jlong` handle as the pointer it came from.
    ///
    /// # Safety
    /// As `stream.rs`'s equivalent: `handle` must be `0`, or a live handle Kotlin's guard has not
    /// released.
    unsafe fn as_handle(handle: jlong) -> *mut c_void {
        handle as usize as *mut c_void
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_WatchJni_liveHandleCount(
        _env: JNIEnv,
        _class: JClass,
    ) -> jlong {
        LIVE_HANDLES.load(Ordering::Relaxed)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_WatchJni_free(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<WatchHandle>(as_handle(handle)) };
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_WatchJni_endpointWatchAddr(
        mut env: JNIEnv,
        _class: JClass,
        endpoint: jlong,
    ) -> jbyteArray {
        let result = match unsafe { endpoint_clone(as_handle(endpoint)) } {
            Some(endpoint) => watch_addr(endpoint),
            None => released(),
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_WatchJni_endpointWatchHomeRelay(
        mut env: JNIEnv,
        _class: JClass,
        endpoint: jlong,
    ) -> jbyteArray {
        let result = match unsafe { endpoint_clone(as_handle(endpoint)) } {
            Some(endpoint) => watch_home_relay(endpoint),
            None => released(),
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_WatchJni_connectionWatchPaths(
        mut env: JNIEnv,
        _class: JClass,
        connection: jlong,
    ) -> jbyteArray {
        let result = match unsafe { connection_clone(as_handle(connection)) } {
            Some(connection) => watch_paths(connection),
            None => released(),
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_WatchJni_connectionWatchPathEvents(
        mut env: JNIEnv,
        _class: JClass,
        connection: jlong,
    ) -> jbyteArray {
        let result = match unsafe { connection_clone(as_handle(connection)) } {
            Some(connection) => watch_path_events(connection),
            None => released(),
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_WatchJni_nextStart(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { share::<WatchSlot>(as_handle(handle)) };
        ops::spawn_channel(next(slot))
    }
}
