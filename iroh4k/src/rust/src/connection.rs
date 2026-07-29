//! The accept side: `Incoming`, `Accepting`, `Connecting`, and the `Connection` handle.
//!
//! Owned by the connection domain. Contains the shared logic plus both facades' exports for it:
//! `#[unsafe(no_mangle)] extern "C"` for cinterop, `#[cfg(not(target_os = "ios"))] Java_*` for JNI.
//!
//! This domain introduces the *consumed-once* handle. Unlike `Endpoint`, which is shared and
//! long-lived, `Incoming` and the two connecting futures are each used exactly once — see
//! [`crate::handle::Consumed`].
//!
//! ## Borrowed futures, and the one idiom that answers all of them
//!
//! `Endpoint::accept(&self) -> Accept<'_>` borrows the endpoint, so the future cannot be stored in a
//! handle and cannot be moved into a `'static` tokio task on its own. What *can* be done is take an
//! owned `iroh::Endpoint` — which is an `Arc` inside, so cloning it is a refcount bump — and await
//! `ep.accept()` entirely inside the spawned async block: the borrow never escapes the block, the
//! clone keeps the endpoint alive for as long as the task runs, and the block itself is `'static`.
//! That is what [`accept_next`] does, and it is why the endpoint arrives here as an owned
//! `Endpoint` rather than as a borrow of the endpoint domain's handle.
//!
//! Its output is `Option<Incoming>`, and `None` is iroh's own answer for a closed endpoint rather
//! than a failure — so it travels as a **null handle** with no error set, and Kotlin reads it as
//! `null`.
//!
//! Every long operation on `Connection` has exactly the same shape: `closed(&self)`,
//! `read_datagram(&self) -> ReadDatagram<'_>`, `send_datagram_wait(&self) -> SendDatagram<'_>` and
//! the four stream openers in [`crate::stream`] all borrow the connection. So they all take an owned
//! `Connection` — which is `Clone` and an `Arc` inside, exactly like `Endpoint` — resolved by
//! [`connection_clone`], and create the borrow inside the spawned block. Nothing borrowed is ever
//! stored, which is why no handle in this crate holds a future except the two that iroh itself hands
//! over by value (`Accepting`, `Connecting`).
//!
//! ## Consumed once, and why that must never panic
//!
//! Every interesting method on `Incoming` takes `self`: `accept`, `refuse`, `retry` and `ignore`
//! each consume it, while `local_addr`, `remote_addr` and `remote_addr_validated` only borrow it.
//! `Accepting` and `Connecting` are futures, so awaiting them consumes them too. A plain shared
//! handle cannot express that, which is what [`crate::handle::Consumed`] exists for: exactly one
//! caller gets the value, and the second gets `None`.
//!
//! A second consuming call therefore reports [`ERROR_CLOSED`], and so does an inspector called after
//! the value is gone. Returning an error rather than unwrapping is not politeness — this crate
//! builds with `panic = "abort"`, so `expect("already accepted")` on a double `accept()` would kill
//! the host process instead of raising a Kotlin exception.
//!
//! [`Consumed`](crate::handle::Consumed) serves `Incoming`, whose every method is synchronous. It
//! cannot serve `Accepting`/`Connecting`: their `alpn(&mut self)` is `async`, so the value has to be
//! *lent* across a suspension point and handed back afterwards, and a `std::sync::MutexGuard` held
//! across an `await` is neither `Send` nor sound practice. Those two use [`Once`] instead, which is
//! the same shape over a `tokio::sync::Mutex`. See the note there.
//!
//! ## Cancellation
//!
//! Every asynchronous operation here can *create* an object, so each one wraps its result with
//! `OpResult::with_handle`: if a cancel wins [`crate::ops`]' deliver-once race the result is
//! discarded, and without the drop function that would strand a live QUIC connection with nobody
//! left holding it.
//!
//! A cancel that lands *after* a consuming operation took its value is a different matter: the value
//! is gone and cannot be put back, so the handle stays permanently consumed and a retry reports
//! [`ERROR_CLOSED`]. That is the honest answer — the `Accepting` really was consumed — and it is why
//! Kotlin documents `connect()` as a one-shot.
//!
//! ## Codec layout
//!
//! Defined here, mirrored by the decoder in `Connection.kt`; the two must be changed together. The
//! transport-address tags are deliberately **the same numbering as `addr.rs` and `endpoint.rs`**, so
//! the whole binding has one tag family for "relay / ip / custom / something newer".
//!
//! ```text
//! IncomingAddr  (Rust → Kotlin)
//!   u8  0 Relay   + str relay URL + bytes remote endpoint id
//!   u8  1 Ip      + str canonical socket address
//!   u8  2 Custom  + i64 transport id + bytes opaque data
//!   u8  3 Unknown + str Debug text
//!
//! LocalTransportAddr  (Rust → Kotlin)
//!   u8  0 Relay   + str relay URL
//!   u8  1 Ip      + str? local IP, absent when the OS did not surface one
//!   u8  2 Custom  + u8 present (+ i64 transport id + bytes opaque data)
//!   u8  3 Unknown + str Debug text
//!
//! TransportAddr  (Rust → Kotlin)  — one path's remote end, the layout `endpoint.rs` also writes
//!   u8  0 Relay   + str relay URL
//!   u8  1 Ip      + str canonical socket address
//!   u8  2 Custom  + i64 transport id + bytes opaque data
//!   u8  3 Unknown + str Display text
//!
//! Close reason  (Rust → Kotlin)
//!   u8  0 absent (the connection is still open), or 1 + str iroh's Display text
//!
//! UdpStats  (Rust → Kotlin)
//!   i64 datagrams
//!   i64 bytes
//!
//! ConnectionStats  (Rust → Kotlin)
//!   UdpStats udp_tx
//!   UdpStats udp_rx
//!   i64      lost_packets
//!   i64      lost_bytes
//!
//! PathSnapshot  (Rust → Kotlin)
//!   i64                path id
//!   TransportAddr      remote end
//!   LocalTransportAddr local end
//!   u8                 1 if this path is the selected one
//!   i64                round-trip time in microseconds
//!   i64                congestion window in bytes
//!   i32                current MTU in bytes
//!   i64                lost packets
//!   i64                lost bytes
//!   i64                congestion events
//!   UdpStats           udp_tx
//!   UdpStats           udp_rx
//!
//! paths()  (Rust → Kotlin)
//!   i32   count             then count × PathSnapshot
//!
//! EndpointAddr  (Kotlin → Rust)   — the layout `addr.rs` documents and reads
//!   bytes id
//!   i32   count             then count × TransportAddr, tags as above
//!
//! start_connect options  (Kotlin → Rust)   — `Endpoint.startConnect`'s `opts` argument
//!   u8    transport config  0 absent — the endpoint's own default — or 1 present, then a
//!                           `TransportConfig`: the counted, tagged, sparse sequence `transport.rs`
//!                           documents in full. Read by `transport::read_optional`, and the whole
//!                           of `opts` — `Reader::finish()` rejects anything left over.
//!
//! acceptWith options  (Kotlin → Rust)   — `Incoming.acceptWith`'s `opts` argument
//!   i32   ALPN count        then count × bytes (one ALPN each)
//!   u8    transport config  as `start_connect options` above, and likewise the rest of the
//!                           buffer — see `decode_accept_with_opts`.
//! ```
//!
//! ## Scalars with an absent value
//!
//! `rtt`, `max_datagram_size`, `SendStream::stopped` and `RecvStream::received_reset` are all
//! `Option` of something non-negative, and all four ride in `i64_val` with **`-1` for absent** rather
//! than in a one-byte-plus-scalar codec payload. That is the convention `endpoint.rs` already uses
//! for an optional relay QUIC port, and it is safe for exactly the reason it is safe there: the real
//! values cannot be negative, so the sentinel cannot collide with one.

use std::{
    ffi::{c_int, c_void},
    ops::Deref,
    ptr::null_mut,
    sync::{
        Arc,
        atomic::{AtomicI64, Ordering},
    },
};

use iroh::{
    Endpoint, EndpointId,
    endpoint::{
        Accepting, ConnectOptions, Connecting, ConnectingError, Connection, ConnectionError,
        ConnectionStats, Incoming, IncomingAddr, IncomingZeroRttConnection, LocalTransportAddr,
        OutgoingZeroRttConnection, Path, QuicTransportConfig, RecvStream, SendDatagramError,
        SendStream, UdpStats, VarInt,
    },
};

use crate::addr::{
    TAG_CUSTOM, TAG_IP, TAG_RELAY, TAG_UNKNOWN, decode_endpoint_addr, write_transport_addr,
};
use crate::codec::{Reader, Writer};
use crate::core::{
    ERROR_ACCEPT, ERROR_CLOSED, ERROR_CONNECT, ERROR_INVALID_ARGUMENT, ERROR_WRITE, Iroh4kPtr,
    Iroh4kResult, bytes_result, error_result, handle_result, i64_result, ok_result, owned_bytes,
};
use crate::handle::{self, Consumed, Tagged};
use crate::ops::{self, OpResult};
use crate::transport;

/// The completion callback every async C export takes — see [`crate::ffi`].
pub(crate) type Completion = extern "C" fn(Iroh4kPtr, *mut Iroh4kResult);

// ============================================================================
// Wire protocol constants — shared with `Connection.kt`. Append only.
// ============================================================================

/// Discriminators for an optional record.
const ABSENT: u8 = 0;
const PRESENT: u8 = 1;

// ============================================================================
// Failures
// ============================================================================

/// The result for a handle whose object is gone.
///
/// Kotlin's closed-flag guard makes this unreachable through the public API; it exists so the
/// boundary answers with an error rather than dereferencing a stale pointer if it ever is.
///
/// Shared with [`crate::stream`], which needs exactly the same answer for a released stream handle.
pub(crate) fn released() -> *mut Iroh4kResult {
    error_result(
        ERROR_CLOSED,
        "this handle has been released and cannot be used",
    )
}

/// Parses a QUIC application error code, flow-control limit or stream count out of caller input.
///
/// A QUIC varint is 62 bits and unsigned; Kotlin's `Long` is 64 and signed, so both ends of the
/// range hold values that are the caller's mistake to hear about rather than iroh4k's to truncate
/// into a different number than was asked for. `what` names the parameter in the message.
///
/// Shared with [`crate::stream`]: `reset`, `stop` and the three connection limits all take one.
pub(crate) fn varint(value: i64, what: &str) -> Result<VarInt, *mut Iroh4kResult> {
    let Ok(unsigned) = u64::try_from(value) else {
        return Err(error_result(
            ERROR_INVALID_ARGUMENT,
            format!("{what} must not be negative, got {value}"),
        ));
    };
    VarInt::from_u64(unsigned).map_err(|error| {
        error_result(
            ERROR_INVALID_ARGUMENT,
            format!("{what} must fit in a QUIC varint: {error}"),
        )
    })
}

/// The result for a consumed-once handle used a second time.
///
/// Reported as [`ERROR_CLOSED`] rather than as its own code: from the caller's side the object it
/// held really is gone, and the message says which one and why.
fn consumed(what: &str) -> *mut Iroh4kResult {
    error_result(
        ERROR_CLOSED,
        format!("this {what} has already been used and cannot be used again"),
    )
}

// ============================================================================
// Handle payloads
// ============================================================================

/// Connection-domain handles still alive.
///
/// The counterpart of `endpoint.rs`'s counter, and it exists for the same reason: a leaked handle is
/// invisible from Kotlin, so a test that accepts and closes in a loop would pass either way.
static LIVE_HANDLES: AtomicI64 = AtomicI64::new(0);

/// A handle payload that counts itself for as long as it is alive.
///
/// The count is decremented in [`Drop`] rather than in the free export deliberately: freeing a
/// handle drops one `Arc` strong count, and an operation still in flight holds another. What a leak
/// test needs to observe is when the object *inside* is actually released, which is exactly here.
///
/// The counter is a parameter rather than a global so [`crate::stream`] can share this one
/// implementation while keeping its own tally: `Connection.liveHandleCount` and
/// `Streams.liveHandleCount` have to move independently, or a stream leak would hide behind a
/// connection that happened to be released in the same breath.
pub(crate) struct Tracked<T> {
    value: T,
    live: &'static AtomicI64,
}

impl<T> Tracked<T> {
    pub(crate) fn new(live: &'static AtomicI64, value: T) -> Self {
        live.fetch_add(1, Ordering::Relaxed);
        Self { value, live }
    }
}

impl<T> Drop for Tracked<T> {
    fn drop(&mut self) {
        self.live.fetch_sub(1, Ordering::Relaxed);
    }
}

impl<T> Deref for Tracked<T> {
    type Target = T;

    fn deref(&self) -> &T {
        &self.value
    }
}

/// A consumed-once slot for a value that is only ever reached from **asynchronous** operations.
///
/// [`crate::handle::Consumed`] is the synchronous form and covers `Incoming`, every method of which
/// returns without awaiting. `Accepting` and `Connecting` need one more thing: their
/// `alpn(&mut self)` is `async`, so the value must be borrowed mutably *across* a suspension point
/// and returned to the slot afterwards. A `std::sync::MutexGuard` cannot do that — it is not `Send`,
/// so the future would not be spawnable, and holding a blocking lock across an await invites
/// deadlock — hence a `tokio::sync::Mutex` here.
///
/// Note this is the same *shape* as `Consumed`, not a second policy: `take` still hands the value to
/// exactly one caller and every later caller gets `None`. Worth hoisting into `handle.rs` as an
/// async sibling of `Consumed` once a second domain needs it.
struct Once<T> {
    slot: tokio::sync::Mutex<Option<T>>,
}

impl<T> Once<T> {
    fn new(value: T) -> Self {
        Self {
            slot: tokio::sync::Mutex::new(Some(value)),
        }
    }

    /// Takes the value, leaving the slot valid but empty. `None` on every later call.
    async fn take(&self) -> Option<T> {
        self.slot.lock().await.take()
    }

    /// Locks the slot so the value can be borrowed mutably across an await, for iroh's
    /// `alpn(&mut self)`. The guard is held for as long as that call runs, which serialises
    /// concurrent inspections of the same handle rather than letting them interleave.
    async fn lock(&self) -> tokio::sync::MutexGuard<'_, Option<T>> {
        self.slot.lock().await
    }
}

/// What a `Connecting` handle points at.
///
/// The `Connecting` itself lives in a [`Once`] because awaiting it consumes it, but `remote_id` is
/// cached beside it: iroh's `Connecting::remote_id(&self)` returns a stored field that never
/// changes, so caching it lets the synchronous export answer without taking a lock it would have to
/// be inside the runtime to take.
struct ConnectingSlot {
    remote_id: EndpointId,
    inner: Once<Connecting>,
}

// The payload each handle holds, and — as `*Shared` — the owned reference an async operation
// carries. `handle::Tagged` wraps every payload so a handle used as the wrong type is rejected
// rather than projected over; it derefs to the payload, so the bodies below are unaffected.
type IncomingHandle = Tracked<Consumed<Incoming>>;
type AcceptingHandle = Tracked<Once<Accepting>>;
type ConnectingHandle = Tracked<ConnectingSlot>;
type ConnectionHandle = Tracked<Connection>;
/// The client side of a connection still exchanging 0-RTT data.
///
/// Upstream's `Connection<OutgoingZeroRtt>`, produced by `Connecting::into_0rtt` (Task 3). Nothing in
/// this task creates one — see [`AnyConnection`] for why the type exists here regardless.
type OutgoingZeroRttHandle = Tracked<OutgoingZeroRttConnection>;
/// The server side of a connection still exchanging 0-RTT data.
///
/// Upstream's `Connection<IncomingZeroRtt>`, produced by `Incoming::into_0rtt` (Task 4). Nothing in
/// this task creates one — see [`AnyConnection`] for why the type exists here regardless.
type IncomingZeroRttHandle = Tracked<IncomingZeroRttConnection>;

type AcceptingShared = Arc<Tagged<AcceptingHandle>>;
type ConnectingShared = Arc<Tagged<ConnectingHandle>>;

/// Wraps a value as a connection-domain handle payload, counting it into [`LIVE_HANDLES`].
fn tracked<T>(value: T) -> Tracked<T> {
    Tracked::new(&LIVE_HANDLES, value)
}

// ----------------------------------------------------------------------------
// Handle access
// ----------------------------------------------------------------------------

/// Borrows a handle payload for the duration of one synchronous call.
///
/// # Safety
/// `handle` must be null, or a live handle produced by this module or [`crate::stream`] for `T` and
/// not yet freed. Kotlin's guard guarantees the second part: it releases a handle only once every
/// call inside it has returned. A live handle tagged for a *different* type than `T` is also a
/// supported input, not a violation of this contract: `handle::borrow` gates on `is::<T>(handle)`
/// before casting (`handle.rs:81-88`) and answers `None` rather than reinterpreting the payload.
/// [`connection_clone_any`] relies on exactly this, probing a handle against three different tagged
/// types in turn.
pub(crate) unsafe fn peek<'a, T: 'static>(handle: *mut c_void) -> Option<&'a Tracked<T>> {
    unsafe {
        if handle.is_null() {
            return None;
        }
        handle::borrow::<Tracked<T>>(handle)
    }
}

/// Clones the `Arc` behind a handle so it can be moved into a spawned future.
///
/// # Safety
/// As [`peek`].
pub(crate) unsafe fn share<T: 'static>(handle: *mut c_void) -> Option<Arc<Tagged<Tracked<T>>>> {
    unsafe { handle::clone_arc::<Tracked<T>>(handle) }
}

/// Runs `f` against the payload behind `handle`, or answers [`ERROR_CLOSED`].
///
/// # Safety
/// As [`peek`].
pub(crate) unsafe fn with<T: 'static>(
    handle: *mut c_void,
    f: impl FnOnce(&Tracked<T>) -> *mut Iroh4kResult,
) -> *mut Iroh4kResult {
    unsafe {
        match peek::<T>(handle) {
            Some(payload) => f(payload),
            None => released(),
        }
    }
}

/// Clones the `Connection` behind a connection handle, for moving into a spawned future.
///
/// The counterpart of `endpoint::endpoint_clone`, and it exists for the same reason: every long
/// operation on a connection is a *borrowed* future, so the only way to await one from a `'static`
/// task is to own a clone and create the borrow inside the task. `Connection` is an `Arc` inside, so
/// this is a refcount bump rather than a copy of anything.
///
/// The remaining caller is [`crate::watch`]'s path watchers (`iroh4k_connection_watch_paths` and
/// `iroh4k_connection_watch_path_events`, plus their JNI twins): they stay on this strict clone
/// rather than [`connection_clone_any`] because `paths_stream()` and `path_events()` only exist on
/// `Connection<HandshakeCompleted>` upstream (`iroh-1.0.3/src/endpoint/connection.rs:1157` and
/// `:1176`) — the `PathStateReceiver` they read lives in `HandshakeCompletedData`, and a 0-RTT
/// connection has no such data yet. The five synchronous exports below are strict for the same
/// reason: `connection_alpn`, `connection_remote_id`, `connection_side`, `connection_paths` and
/// `connection_rtt`.
///
/// # Safety
/// As [`peek`], for a `Connection` handle.
pub(crate) unsafe fn connection_clone(handle: *mut c_void) -> Option<Connection> {
    unsafe { peek::<Connection>(handle).map(|payload| (**payload).clone()) }
}

/// A clone of whatever connection a handle holds, whatever handshake state it is in.
///
/// The three states are distinct Rust types upstream — `Connection<HandshakeCompleted>`,
/// `Connection<OutgoingZeroRtt>` and `Connection<IncomingZeroRtt>` — with a private `data` field and
/// no conversion between them (`iroh-1.0.3/src/endpoint/connection.rs:737`). Everything on
/// `impl<T: ConnectionState>` is common to all three, so the exports for that surface probe the
/// three tags in turn and forward through this enum rather than existing three times.
///
/// The strict [`connection_clone`] stays: `watch.rs`'s path watchers, `connection_side`,
/// `connection_alpn`, `connection_remote_id`, `connection_paths` and `connection_rtt` are
/// `HandshakeCompleted`-only — `alpn`, `remote_id`, `paths` and `side` live on a *separate* `impl`
/// block upstream that is not generic over `ConnectionState`, and `connection_rtt` reads `paths()`
/// to find the selected path's latency — so they must reject a 0-RTT handle rather than be handed
/// one they cannot serve.
pub(crate) enum AnyConnection {
    Completed(Connection),
    Outgoing(OutgoingZeroRttConnection),
    Incoming(IncomingZeroRttConnection),
}

/// Runs `$body` against the connection inside `$self`, whatever its state.
///
/// A macro rather than a method taking a closure: the three arms hold three different concrete
/// `Connection<State>` types, so a closure parameter would need `dyn` or a fourth generic — this
/// keeps every call monomorphic and lets `$body` be `async` where the caller needs it to be.
macro_rules! any_connection {
    ($self:expr, $c:ident => $body:expr) => {
        match $self {
            AnyConnection::Completed($c) => $body,
            AnyConnection::Outgoing($c) => $body,
            AnyConnection::Incoming($c) => $body,
        }
    };
}

impl AnyConnection {
    /// Opens a bidirectional stream. See `impl<T: ConnectionState> Connection<T>::open_bi`.
    ///
    /// `async fn` rather than the brief's `fn` returning `impl Future` explicitly: the two desugar to
    /// the same borrow of `&self` and the same opaque future, and clippy's `manual_async_fn` — part
    /// of this crate's `-D warnings` gate — asks for the shorter form.
    pub(crate) async fn open_bi(&self) -> Result<(SendStream, RecvStream), ConnectionError> {
        any_connection!(self, c => c.open_bi().await)
    }

    /// Accepts the peer's next bidirectional stream. See `Connection::accept_bi`.
    pub(crate) async fn accept_bi(&self) -> Result<(SendStream, RecvStream), ConnectionError> {
        any_connection!(self, c => c.accept_bi().await)
    }

    /// Opens a unidirectional stream. See `Connection::open_uni`.
    pub(crate) async fn open_uni(&self) -> Result<SendStream, ConnectionError> {
        any_connection!(self, c => c.open_uni().await)
    }

    /// Accepts the peer's next unidirectional stream. See `Connection::accept_uni`.
    pub(crate) async fn accept_uni(&self) -> Result<RecvStream, ConnectionError> {
        any_connection!(self, c => c.accept_uni().await)
    }

    /// Suspends until a datagram arrives, and answers with its bytes.
    ///
    /// Answers `Vec<u8>` rather than iroh's own `bytes::Bytes`: `bytes` is not a direct dependency of
    /// this crate — see the note on [`connection_send_datagram`] — so naming its type here would
    /// need one. The copy this costs is the same one `connection_read_datagram` already paid before
    /// this change.
    pub(crate) async fn read_datagram(&self) -> Result<Vec<u8>, ConnectionError> {
        any_connection!(self, c => c.read_datagram().await).map(|data| data.to_vec())
    }

    /// Sends a datagram, waiting for buffer space if there is none. See `Connection::send_datagram_wait`.
    pub(crate) async fn send_datagram_wait(&self, data: Vec<u8>) -> Result<(), SendDatagramError> {
        any_connection!(self, c => c.send_datagram_wait(data.into()).await)
    }

    /// Suspends until the connection closes, then reports why. See `Connection::closed`.
    pub(crate) async fn closed(&self) -> ConnectionError {
        any_connection!(self, c => c.closed().await)
    }

    /// Connection-wide statistics. See `Connection::stats`.
    pub(crate) fn stats(&self) -> ConnectionStats {
        any_connection!(self, c => c.stats())
    }

    /// A connection identifier that survives address and connection-id changes. See
    /// `Connection::stable_id`.
    pub(crate) fn stable_id(&self) -> usize {
        any_connection!(self, c => c.stable_id())
    }

    /// Why the connection closed, or absent while it is still open. See `Connection::close_reason`.
    pub(crate) fn close_reason(&self) -> Option<ConnectionError> {
        any_connection!(self, c => c.close_reason())
    }

    /// Closes the connection immediately. See `Connection::close`.
    pub(crate) fn close(&self, code: VarInt, reason: &[u8]) {
        any_connection!(self, c => c.close(code, reason))
    }

    /// Sends an unreliable datagram, failing rather than waiting if there is no buffer space.
    ///
    /// Takes `Vec<u8>` rather than `bytes::Bytes` for the reason [`read_datagram`](Self::read_datagram)
    /// gives; the `.into()` conversion is resolved from `Connection::send_datagram`'s own signature.
    pub(crate) fn send_datagram(&self, data: Vec<u8>) -> Result<(), SendDatagramError> {
        any_connection!(self, c => c.send_datagram(data.into()))
    }

    /// The largest datagram this connection can currently carry. See `Connection::max_datagram_size`.
    pub(crate) fn max_datagram_size(&self) -> Option<usize> {
        any_connection!(self, c => c.max_datagram_size())
    }

    /// Bytes free in the outgoing datagram buffer. See `Connection::datagram_send_buffer_space`.
    pub(crate) fn datagram_send_buffer_space(&self) -> usize {
        any_connection!(self, c => c.datagram_send_buffer_space())
    }

    /// Sets how many bidirectional streams the peer may have open at once.
    pub(crate) fn set_max_concurrent_bi_streams(&self, n: VarInt) {
        any_connection!(self, c => c.set_max_concurrent_bi_streams(n))
    }

    /// Sets how many unidirectional streams the peer may have open at once.
    pub(crate) fn set_max_concurrent_uni_streams(&self, n: VarInt) {
        any_connection!(self, c => c.set_max_concurrent_uni_streams(n))
    }

    /// Sets the connection-level flow-control receive window, in bytes.
    pub(crate) fn set_receive_window(&self, n: VarInt) {
        any_connection!(self, c => c.set_receive_window(n))
    }
}

/// Clones whatever connection `handle` holds, probing the three tags in turn.
///
/// Each probe goes through [`peek`] for its own type, so the `TypeId` at offset 0 is compared
/// rather than assumed — never read the tag once and project over it. The order tried is the
/// common case first (a handshake-completed `Connection` is by far the most frequent handle this
/// crate resolves), but it is not a priority order in any other sense: the three tags are disjoint,
/// so at most one `peek` can ever succeed.
///
/// # Safety
/// As [`peek`], for a connection handle of any of the three kinds.
pub(crate) unsafe fn connection_clone_any(handle: *mut c_void) -> Option<AnyConnection> {
    unsafe {
        if let Some(p) = peek::<Connection>(handle) {
            return Some(AnyConnection::Completed((**p).clone()));
        }
        if let Some(p) = peek::<OutgoingZeroRttConnection>(handle) {
            return Some(AnyConnection::Outgoing((**p).clone()));
        }
        peek::<IncomingZeroRttConnection>(handle).map(|p| AnyConnection::Incoming((**p).clone()))
    }
}

/// Runs `f` against the connection behind `handle`, whatever handshake state it is in, or answers
/// [`ERROR_CLOSED`].
///
/// The [`with`] of the shared surface: it resolves through [`connection_clone_any`] rather than
/// [`peek`], because the shared exports must accept a completed connection as well as either 0-RTT
/// handle. The clone this takes is an `Arc` refcount bump — an atomic increment paired with the
/// eventual decrement on drop — rather than a copy of anything, so it is negligible next to the FFI
/// crossing itself, the same way this crate accounts for `Connection`'s clone cost elsewhere.
///
/// # Safety
/// As [`connection_clone_any`].
pub(crate) unsafe fn with_any(
    handle: *mut c_void,
    f: impl FnOnce(&AnyConnection) -> *mut Iroh4kResult,
) -> *mut Iroh4kResult {
    unsafe {
        match connection_clone_any(handle) {
            Some(connection) => f(&connection),
            None => released(),
        }
    }
}

/// Runs `f` inside the shared tokio runtime's context.
///
/// The consuming `Incoming` operations are synchronous in iroh, but they drive a QUIC state machine
/// and put packets on the wire. They are called from a Kotlin thread that knows nothing about the
/// runtime, and this crate aborts on panic — so they are entered on the runtime rather than resting
/// on every path inside them being runtime-agnostic. Entering is a thread-local set and restore,
/// which is nothing next to the FFI crossing itself.
///
/// The same reasoning covers the synchronous stream operations in [`crate::stream`] — `finish`,
/// `reset`, `stop` — and `send_datagram`, which is why this is shared rather than private.
pub(crate) fn in_runtime<R>(f: impl FnOnce() -> R) -> R {
    let _guard = crate::core::runtime().enter();
    f()
}

// ============================================================================
// Payload reader
// ============================================================================

// ============================================================================
// Addressing
// ============================================================================

/// Writes the address an incoming connection arrived *from*.
///
/// `IncomingAddr` is iroh's own type and not a `TransportAddr`: for a relayed connection it knows
/// the remote's endpoint id as well as the relay, which a `TransportAddr::Relay` cannot carry. It is
/// modelled faithfully here rather than flattened, for the reason `Addr.kt` gives — a binding that
/// collapses an address into a string is a binding that loses information without saying so.
fn write_incoming_addr(w: &mut Writer, addr: &IncomingAddr) {
    match addr {
        IncomingAddr::Ip(socket) => {
            w.u8(TAG_IP).str(&socket.to_string());
        }
        IncomingAddr::Relay { url, endpoint_id } => {
            w.u8(TAG_RELAY)
                .str(&url.to_string())
                .bytes(endpoint_id.as_bytes());
        }
        IncomingAddr::Custom(custom) => {
            w.u8(TAG_CUSTOM)
                .i64(custom.id() as i64)
                .bytes(custom.data());
        }
        // `IncomingAddr` is `#[non_exhaustive]`: a future iroh variant lands here and is surfaced
        // with its `Debug` text rather than dropped. It has no `Display`, unlike `TransportAddr`.
        other => {
            w.u8(TAG_UNKNOWN).str(&format!("{other:?}"));
        }
    }
}

/// Writes the local address that *received* an incoming connection.
///
/// Note the shape differs from an ordinary transport address in two places, both of them iroh's:
/// the IP variant is optional because the OS does not always surface which local address a datagram
/// arrived on, and the custom variant is optional because a custom transport need not report one.
fn write_local_addr(w: &mut Writer, addr: &LocalTransportAddr) {
    match addr {
        LocalTransportAddr::Ip(ip) => {
            w.u8(TAG_IP).opt_str(ip.map(|ip| ip.to_string()).as_deref());
        }
        LocalTransportAddr::Relay(url) => {
            w.u8(TAG_RELAY).str(&url.to_string());
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
        // As in [`write_incoming_addr`]: `#[non_exhaustive]`, no `Display`.
        other => {
            w.u8(TAG_UNKNOWN).str(&format!("{other:?}"));
        }
    }
}

// ============================================================================
// Shared logic — Incoming
// ============================================================================

/// Accepts an incoming connection, producing an `Accepting` handle.
///
/// Synchronous in iroh, so this creates its handle outside [`crate::ops`] and needs no
/// `with_handle`: there is no deliver-once race to lose, and Kotlin owns the handle from the moment
/// the result is read.
///
/// A failure here is routinely not the application's fault — a QUIC endpoint listens on an ordinary
/// UDP socket and anything on the network can send it datagrams that merely look like QUIC — which
/// is why iroh documents these as errors to log rather than to treat as broken state, and why the
/// accepting endpoint stays perfectly usable afterwards.
fn incoming_accept(slot: &Consumed<Incoming>) -> *mut Iroh4kResult {
    let Some(incoming) = slot.take() else {
        return consumed("incoming connection");
    };
    match in_runtime(|| incoming.accept()) {
        Ok(accepting) => handle_result(handle::into_handle(tracked(Once::new(accepting)))),
        Err(error) => error_result(
            ERROR_ACCEPT,
            format!("could not accept an incoming connection: {error}"),
        ),
    }
}

/// Decodes an `acceptWith` options payload: the ALPN list plus an optional transport
/// configuration.
///
/// Mirrors `Incoming.acceptWith`'s writer in `Connection.kt`: `i32` count then that many
/// length-prefixed ALPNs, followed by the same optional-`TransportConfig` record
/// `Endpoint.startConnect`'s options payload ends with.
fn decode_accept_with_opts(
    opts: &[u8],
) -> Result<(Vec<Vec<u8>>, Option<QuicTransportConfig>), String> {
    let mut r = Reader::new(opts);
    let count = r.count()?;
    let mut alpns = Vec::with_capacity(count);
    for _ in 0..count {
        alpns.push(r.bytes()?.to_vec());
    }
    let transport = transport::read_optional(&mut r)?;
    r.finish()?;
    Ok((alpns, transport))
}

/// Accepts an incoming connection with a transport configuration for this connection alone,
/// producing an `Accepting` handle.
///
/// A sibling of [`incoming_accept`] rather than a parameter added to it: `accept_with` needs an
/// `Arc<ServerConfig>`, which upstream can only build through `endpoint`'s own
/// `create_server_config_builder`, so this operation additionally takes the endpoint the incoming
/// connection came from. `alpns` is not inferred from the incoming connection's own negotiated
/// protocol: `ServerConfigBuilder` has no way to add an ALPN after it is created, so the caller
/// states the list explicitly and Kotlin's `acceptWith` documents why a list that omits the
/// negotiated protocol accepts a connection that agrees on nothing.
///
/// The slot is taken *after* `alpns`/`transport` have already been decoded by the caller, so a
/// malformed payload never consumes the incoming connection — only a well-formed one reaches this
/// function at all. Taking it here, before the server configuration is built, is what makes a
/// second use of the same handle report [`ERROR_CLOSED`] rather than building a config for nothing,
/// exactly as [`incoming_accept`] does.
fn incoming_accept_with(
    slot: &Consumed<Incoming>,
    endpoint: &Endpoint,
    alpns: Vec<Vec<u8>>,
    transport: Option<QuicTransportConfig>,
) -> *mut Iroh4kResult {
    let Some(incoming) = slot.take() else {
        return consumed("incoming connection");
    };
    let mut builder = endpoint.create_server_config_builder(alpns);
    if let Some(transport) = transport {
        builder = builder.set_transport_config(transport);
    }
    let server_config = Arc::new(builder.build());
    match in_runtime(|| incoming.accept_with(server_config)) {
        Ok(accepting) => handle_result(handle::into_handle(tracked(Once::new(accepting)))),
        Err(error) => error_result(
            ERROR_ACCEPT,
            format!("could not accept an incoming connection: {error}"),
        ),
    }
}

/// Rejects an incoming connection, telling the peer so.
fn incoming_refuse(slot: &Consumed<Incoming>) -> *mut Iroh4kResult {
    match slot.take() {
        Some(incoming) => {
            in_runtime(|| incoming.refuse());
            ok_result()
        }
        None => consumed("incoming connection"),
    }
}

/// Asks the peer to retry with address validation.
///
/// iroh returns the `Incoming` inside its `RetryError` so a caller can carry on with it. That cannot
/// be surfaced here: Kotlin still points at the handle whose value was just taken, and putting the
/// value *back* would break the consumed-once contract every other operation relies on. So the error
/// is reported and the incoming connection is dropped, which refuses it — the same outcome as
/// letting a `RetryError` fall out of scope in Rust.
fn incoming_retry(slot: &Consumed<Incoming>) -> *mut Iroh4kResult {
    let Some(incoming) = slot.take() else {
        return consumed("incoming connection");
    };
    match in_runtime(|| incoming.retry()) {
        Ok(()) => ok_result(),
        Err(error) => error_result(
            ERROR_ACCEPT,
            format!("could not ask the peer to retry: {error}"),
        ),
    }
}

/// Drops an incoming connection without answering it at all.
fn incoming_ignore(slot: &Consumed<Incoming>) -> *mut Iroh4kResult {
    match slot.take() {
        Some(incoming) => {
            in_runtime(|| incoming.ignore());
            ok_result()
        }
        None => consumed("incoming connection"),
    }
}

/// The local address that received an incoming connection, as a codec payload.
fn incoming_local_addr(slot: &Consumed<Incoming>) -> *mut Iroh4kResult {
    match slot.with(|incoming| {
        let mut w = Writer::new();
        write_local_addr(&mut w, &incoming.local_addr());
        w.finish()
    }) {
        Some(payload) => bytes_result(payload),
        None => consumed("incoming connection"),
    }
}

/// The address an incoming connection arrived from, as a codec payload.
fn incoming_remote_addr(slot: &Consumed<Incoming>) -> *mut Iroh4kResult {
    match slot.with(|incoming| {
        let mut w = Writer::new();
        write_incoming_addr(&mut w, &incoming.remote_addr());
        w.finish()
    }) {
        Some(payload) => bytes_result(payload),
        None => consumed("incoming connection"),
    }
}

/// `1` in `i64_val` if the peer has proved it can receive traffic at the address it dialled from.
fn incoming_remote_addr_validated(slot: &Consumed<Incoming>) -> *mut Iroh4kResult {
    match slot.with(|incoming| incoming.remote_addr_validated()) {
        Some(validated) => i64_result(i64::from(validated)),
        None => consumed("incoming connection"),
    }
}

// ============================================================================
// Shared logic — the handshake
// ============================================================================

/// Turns a finished handshake into a `Connection` handle.
///
/// Both sides land here, so both report the same code: [`ERROR_CONNECT`] is the handshake's, whereas
/// [`ERROR_ACCEPT`] belongs to the step *before* the handshake — deciding what to do with an
/// `Incoming`.
fn finish_handshake(outcome: Result<Connection, ConnectingError>) -> OpResult {
    match outcome {
        Ok(connection) => {
            let handle = handle::into_handle(tracked(connection));
            // The handle is created inside an async operation, so `ops` has to be told how to
            // release it: a cancel winning the deliver-once race discards this result, and without
            // the drop function that would strand a live QUIC connection.
            OpResult::with_handle(handle_result(handle), iroh4k_connection_free)
        }
        Err(error) => OpResult::new(error_result(
            ERROR_CONNECT,
            format!("the connection handshake failed: {error}"),
        )),
    }
}

/// Awaits the next inbound connection attempt on `endpoint`.
///
/// See the module header on why the endpoint arrives owned rather than borrowed.
async fn accept_next(endpoint: Option<Endpoint>) -> OpResult {
    let Some(endpoint) = endpoint else {
        return OpResult::new(released());
    };
    match endpoint.accept().await {
        // iroh's own answer for an endpoint that has been closed, and not an error it reports. It
        // travels as a null handle with no error set; Kotlin reads that as `null`.
        None => OpResult::new(handle_result(null_mut())),
        Some(incoming) => {
            let handle = handle::into_handle(tracked(Consumed::new(incoming)));
            OpResult::with_handle(handle_result(handle), iroh4k_incoming_free)
        }
    }
}

/// Starts an outbound connection attempt, producing a `Connecting` handle.
///
/// The outbound side is M5's; this exists because the accept chain cannot be exercised — or used —
/// without something to accept, and one loopback dial is the smallest thing that provides it.
/// `opts` carries an optional transport configuration — the same `writeOptionalTransportConfig`
/// payload `endpoint.rs`'s bind configuration ends with — decoded below and, when present, handed
/// to `connect_with_opts` through `ConnectOptions::with_transport_config`, replacing the
/// endpoint's own default outright rather than merging with it. Still not wired up: 0-RTT and
/// additional ALPNs, the other options `connect_with_opts` takes.
async fn start_connect(
    endpoint: Option<Endpoint>,
    addr: Vec<u8>,
    alpn: Vec<u8>,
    opts: Vec<u8>,
) -> OpResult {
    let Some(endpoint) = endpoint else {
        return OpResult::new(released());
    };
    let addr = match decode_endpoint_addr(&addr) {
        Ok(addr) => addr,
        Err((code, message)) => return OpResult::new(error_result(code, message)),
    };
    // Decoded here rather than in either facade's export, because both call this one function and
    // a second copy of the decode is a second place for the two ends to drift apart.
    let transport_config = {
        let mut r = Reader::new(&opts);
        match transport::read_optional(&mut r).and_then(|config| r.finish().map(|()| config)) {
            Ok(config) => config,
            Err(message) => {
                return OpResult::new(error_result(
                    ERROR_INVALID_ARGUMENT,
                    format!("malformed connect options: {message}"),
                ));
            }
        }
    };

    // A transport configuration given here replaces the endpoint's own outright — that is upstream's
    // behaviour, not a choice made here, and `Endpoint.startConnect`'s documentation says so.
    let mut options = ConnectOptions::new();
    if let Some(transport) = transport_config {
        options = options.with_transport_config(transport);
    }

    match endpoint.connect_with_opts(addr, &alpn, options).await {
        Ok(connecting) => {
            let slot = ConnectingSlot {
                remote_id: connecting.remote_id(),
                inner: Once::new(connecting),
            };
            let handle = handle::into_handle(tracked(slot));
            OpResult::with_handle(handle_result(handle), iroh4k_connecting_free)
        }
        Err(error) => OpResult::new(error_result(
            ERROR_CONNECT,
            format!("could not start a connection: {error}"),
        )),
    }
}

/// Completes an accepted handshake.
async fn accepting_connect(slot: Option<AcceptingShared>) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let Some(accepting) = slot.take().await else {
        return OpResult::new(consumed("accepting connection"));
    };
    finish_handshake(accepting.await)
}

/// Completes an outbound handshake.
async fn connecting_connect(slot: Option<ConnectingShared>) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let Some(connecting) = slot.inner.take().await else {
        return OpResult::new(consumed("connection attempt"));
    };
    finish_handshake(connecting.await)
}

/// The ALPN negotiated for a handshake still in progress.
///
/// Reads it *without* consuming the future, which is the whole point: this is how a server with
/// several ALPNs decides what to do before completing the handshake.
async fn accepting_alpn(slot: Option<AcceptingShared>) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let mut guard = slot.lock().await;
    let Some(accepting) = guard.as_mut() else {
        return OpResult::new(consumed("accepting connection"));
    };
    OpResult::new(alpn_result(accepting.alpn().await))
}

/// As [`accepting_alpn`], for the outbound side.
async fn connecting_alpn(slot: Option<ConnectingShared>) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let mut guard = slot.inner.lock().await;
    let Some(connecting) = guard.as_mut() else {
        return OpResult::new(consumed("connection attempt"));
    };
    OpResult::new(alpn_result(connecting.alpn().await))
}

/// The negotiated ALPN as raw bytes, or the reason there is none.
///
/// Not a codec payload: an ALPN is an opaque octet string in TLS, so it crosses as itself.
fn alpn_result(outcome: Result<Vec<u8>, iroh::endpoint::AlpnError>) -> *mut Iroh4kResult {
    match outcome {
        Ok(alpn) => bytes_result(alpn),
        Err(error) => error_result(
            ERROR_CONNECT,
            format!("could not read the negotiated ALPN: {error}"),
        ),
    }
}

// ============================================================================
// Shared logic — Connection
// ============================================================================

/// The negotiated ALPN of an established connection.
fn connection_alpn(connection: &Connection) -> Vec<u8> {
    connection.alpn().to_vec()
}

/// The 32 raw bytes of the remote's endpoint id, taken from its TLS certificate.
fn connection_remote_id(connection: &Connection) -> Vec<u8> {
    connection.remote_id().as_bytes().to_vec()
}

/// A connection identifier that survives address and connection-id changes.
///
/// iroh types it as `usize`; it is reported as `i64`, which is lossless on every target this builds
/// for and is the width Kotlin's `Long` has anyway.
///
/// Takes an [`AnyConnection`] — `stable_id` is on `impl<T: ConnectionState>` upstream, so it answers
/// for a 0-RTT handle exactly as it does for a completed one.
fn connection_stable_id(connection: &AnyConnection) -> i64 {
    connection.stable_id() as i64
}

/// Why the connection closed, or absent while it is still open.
///
/// Reported as iroh's `Display` text rather than as a code: `ConnectionError` is `#[non_exhaustive]`
/// with a dozen variants whose ordinals would be a second wire protocol to keep in step, and the
/// question a caller asks of a closed connection is "why", not "which discriminant".
///
/// Takes an [`AnyConnection`] — see [`connection_stable_id`].
fn connection_close_reason(connection: &AnyConnection) -> Vec<u8> {
    let mut w = Writer::new();
    match connection.close_reason() {
        None => {
            w.u8(ABSENT);
        }
        Some(error) => {
            w.u8(PRESENT).str(&error.to_string());
        }
    }
    w.finish()
}

/// Closes the connection immediately, handing `error_code` and `reason` to the peer verbatim.
///
/// Takes an [`AnyConnection`] — see [`connection_stable_id`].
fn connection_close(
    connection: &AnyConnection,
    error_code: i64,
    reason: &[u8],
) -> *mut Iroh4kResult {
    // A QUIC application error code is a 62-bit varint, which is what [`varint`] enforces.
    match varint(error_code, "a connection error code") {
        Ok(code) => {
            connection.close(code, reason);
            ok_result()
        }
        Err(failure) => failure,
    }
}

/// `0` for the side that dialled, `1` for the side that accepted.
///
/// Asked through `is_client` rather than matched on `Side`: the enum is a foreign type with exactly
/// two variants today, and a predicate cannot stop compiling if a third ever appears.
fn connection_side(connection: &Connection) -> i64 {
    i64::from(!connection.side().is_client())
}

/// Converts a microsecond count from a `Duration` into the `i64` the boundary carries.
///
/// Saturating rather than wrapping: an RTT that overflowed 292 000 years is not a number worth
/// reporting accurately, and a wrap would report a *negative* one, which is this domain's sentinel
/// for "absent".
fn micros(value: u128) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

/// Widens a `usize` iroh reports into the `i64` the boundary carries.
fn width(value: usize) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

/// The round-trip time of the path traffic is currently taking, in microseconds, or `-1`.
///
/// iroh's own `rtt` takes a `PathId`, which iroh4k does not expose — a path id is only meaningful
/// against a snapshot from [`connection_paths`], and asking a caller to carry one around to read a
/// latency would be an odd API. So the *selected* path is used, which is the one application data is
/// actually going over, falling back to the first open path when no path is selected yet.
fn connection_rtt(connection: &Connection) -> i64 {
    let paths = connection.paths();
    let mut chosen = None;
    for path in paths.iter() {
        let rtt = micros(path.rtt().as_micros());
        if path.is_selected() {
            return rtt;
        }
        if chosen.is_none() {
            chosen = Some(rtt);
        }
    }
    chosen.unwrap_or(-1)
}

/// Connection-wide statistics, as a codec payload.
///
/// The per-frame-type counters iroh's `FrameStats` carries are deliberately left out. There are
/// forty of them per direction, the struct is `#[non_exhaustive]` so the list moves between iroh
/// releases, and they answer a question about the QUIC implementation rather than about the
/// application. What is here is what an application measures itself against: bytes and datagrams
/// each way, and what was lost.
///
/// Takes an [`AnyConnection`] — see [`connection_stable_id`].
fn connection_stats(connection: &AnyConnection) -> Vec<u8> {
    let stats: ConnectionStats = connection.stats();
    let mut w = Writer::new();
    write_udp_stats(&mut w, &stats.udp_tx);
    write_udp_stats(&mut w, &stats.udp_rx);
    w.i64(stats.lost_packets as i64)
        .i64(stats.lost_bytes as i64);
    w.finish()
}

fn write_udp_stats(w: &mut Writer, stats: &UdpStats) {
    // `UdpStats::ios` is deprecated upstream and documented as always zero, so it is not carried.
    w.i64(stats.datagrams as i64).i64(stats.bytes as i64);
}

/// A snapshot of the connection's open network paths, as a codec payload.
///
/// iroh's `paths()` hands back a *borrowed* `PathList`, and every `Path` in it borrows from both the
/// list and the connection. That is fine here and needs none of the owned-clone machinery the
/// asynchronous operations use: the whole snapshot is read and encoded inside this one synchronous
/// call, so nothing borrowed outlives it.
fn connection_paths(connection: &Connection) -> Vec<u8> {
    let paths = connection.paths();
    let mut w = Writer::new();
    w.i32(paths.len() as i32);
    for path in paths.iter() {
        write_path(&mut w, &path);
    }
    w.finish()
}

fn write_path(w: &mut Writer, path: &Path) {
    // noq keeps `PathId::as_u32` crate-private, so `Display` — documented as, and implemented as,
    // the plain integer — is the only public route to the number. Parsing it back is ugly but it is
    // exact; the alternative would be to hide the id, and then two snapshots of the same connection
    // could not be lined up against each other.
    w.i64(path.id().to_string().parse::<i64>().unwrap_or(-1));
    write_transport_addr(w, path.remote_addr());
    write_local_addr(w, path.local_addr());
    w.bool(path.is_selected());

    let stats = path.stats();
    w.i64(micros(stats.rtt.as_micros()))
        .i64(stats.cwnd as i64)
        .i32(i32::from(stats.current_mtu))
        .i64(stats.lost_packets as i64)
        .i64(stats.lost_bytes as i64)
        .i64(stats.congestion_events as i64);
    write_udp_stats(w, &stats.udp_tx);
    write_udp_stats(w, &stats.udp_rx);
}

/// The largest datagram this connection can currently carry, or `-1` when it can carry none.
///
/// Absent means datagrams are switched off locally or unsupported by the peer — and it can also
/// change during the connection as the path MTU estimate moves, which is why it is a call and not a
/// value read once.
///
/// Takes an [`AnyConnection`] — see [`connection_stable_id`].
fn connection_max_datagram_size(connection: &AnyConnection) -> i64 {
    connection.max_datagram_size().map_or(-1, width)
}

/// Sends an unreliable datagram, failing rather than waiting if there is no buffer space.
///
/// Takes an [`AnyConnection`] — see [`connection_stable_id`]. The `.into()` inside
/// [`AnyConnection::send_datagram`] is what resolves `payload.to_vec()` into iroh's own
/// `bytes::Bytes` without this crate needing to name that type: it is not a direct dependency.
fn connection_send_datagram(connection: &AnyConnection, payload: &[u8]) -> *mut Iroh4kResult {
    match in_runtime(|| connection.send_datagram(payload.to_vec())) {
        Ok(()) => ok_result(),
        Err(error) => datagram_failure(&error),
    }
}

/// Why a datagram could not be sent.
///
/// [`ERROR_WRITE`] for everything the datagram itself is responsible for — too large for the current
/// path, unsupported by the peer, disabled locally — and [`ERROR_CLOSED`] only for a connection that
/// is gone, because that is not a fact about the datagram.
fn datagram_failure(error: &SendDatagramError) -> *mut Iroh4kResult {
    let code = match error {
        SendDatagramError::ConnectionLost(_) => ERROR_CLOSED,
        _ => ERROR_WRITE,
    };
    error_result(code, format!("could not send a datagram: {error}"))
}

/// Applies one of iroh's three connection-level limits.
///
/// The three share a body because they share a shape: a QUIC varint out of caller input, applied to
/// a live connection, reporting nothing back. `what` names the limit in a rejection message.
///
/// Takes an [`AnyConnection`] — see [`connection_stable_id`].
fn connection_set_limit(
    connection: &AnyConnection,
    value: i64,
    what: &str,
    apply: impl FnOnce(&AnyConnection, VarInt),
) -> *mut Iroh4kResult {
    match varint(value, what) {
        Ok(limit) => {
            apply(connection, limit);
            ok_result()
        }
        Err(failure) => failure,
    }
}

// ----------------------------------------------------------------------------
// Shared logic — Connection, asynchronous
//
// Each of these borrows the connection to make its future, so each takes an owned `AnyConnection`
// and creates the borrow inside the spawned block. See the module header. Resolved through
// `connection_clone_any` rather than `connection_clone`: `closed`, `send_datagram_wait` and
// `read_datagram` are all on `impl<T: ConnectionState>` upstream, so a 0-RTT handle answers them
// too.
// ----------------------------------------------------------------------------

/// Suspends until the connection closes, then reports why.
///
/// Not an error: iroh's `closed()` yields a `ConnectionError` for *every* ending, including the
/// entirely routine ones — the peer closing deliberately, or this side having closed. So the reason
/// travels as a successful string result and Kotlin decides what to make of it.
async fn connection_closed(connection: Option<AnyConnection>) -> OpResult {
    let Some(connection) = connection else {
        return OpResult::new(released());
    };
    let reason = connection.closed().await;
    OpResult::new(bytes_result(reason.to_string().into_bytes()))
}

/// Sends a datagram, waiting for buffer space if there is none.
async fn connection_send_datagram_wait(
    connection: Option<AnyConnection>,
    payload: Vec<u8>,
) -> OpResult {
    let Some(connection) = connection else {
        return OpResult::new(released());
    };
    match connection.send_datagram_wait(payload).await {
        Ok(()) => OpResult::new(ok_result()),
        Err(error) => OpResult::new(datagram_failure(&error)),
    }
}

/// Suspends until a datagram arrives, and answers with its bytes.
async fn connection_read_datagram(connection: Option<AnyConnection>) -> OpResult {
    let Some(connection) = connection else {
        return OpResult::new(released());
    };
    match connection.read_datagram().await {
        Ok(datagram) => OpResult::new(bytes_result(datagram)),
        // The only way this fails is the connection ending, which is what `ERROR_CLOSED` says.
        Err(error) => OpResult::new(error_result(
            ERROR_CLOSED,
            format!("could not read a datagram: {error}"),
        )),
    }
}

/// Dials `addr` and completes the handshake in one operation.
///
/// The one-shot form of `start_connect` + `Connecting::connect`, and iroh's own `Endpoint::connect`
/// is exactly that: it calls `connect_with_opts` with default options and awaits the `Connecting`.
/// Both are kept because they answer different questions — this one for "give me a connection", the
/// two-step one for "let me look at the handshake before I commit to it" (`Connecting::alpn`, and
/// the 0-RTT and custom-options surface a later milestone will add there rather than here).
async fn connect(endpoint: Option<Endpoint>, addr: Vec<u8>, alpn: Vec<u8>) -> OpResult {
    let Some(endpoint) = endpoint else {
        return OpResult::new(released());
    };
    let addr = match decode_endpoint_addr(&addr) {
        Ok(addr) => addr,
        Err((code, message)) => return OpResult::new(error_result(code, message)),
    };
    match endpoint.connect(addr, &alpn).await {
        Ok(connection) => {
            // As in `finish_handshake`: the handle is created inside an async operation, so a cancel
            // winning the deliver-once race has to be able to release the connection it produced.
            let handle = handle::into_handle(tracked(connection));
            OpResult::with_handle(handle_result(handle), iroh4k_connection_free)
        }
        Err(error) => OpResult::new(error_result(
            ERROR_CONNECT,
            format!("could not connect: {error}"),
        )),
    }
}

// ============================================================================
// C ABI — Kotlin/Native via cinterop
// ============================================================================

/// Borrows a caller-owned buffer as a slice for the duration of one call.
///
/// Only for the **synchronous** exports, which finish before returning, so the pinned Kotlin
/// `ByteArray` outlives the borrow. The asynchronous ones use [`crate::core::owned_bytes`], because
/// their future runs on a tokio thread long after the Kotlin buffer is gone.
///
/// # Safety
/// `ptr` must be null, or point to at least `len` initialised bytes that stay valid and unmutated
/// for the lifetime `'a`, and `'a` must not outlive the call.
unsafe fn borrowed<'a>(ptr: *const u8, len: c_int) -> &'a [u8] {
    unsafe {
        if ptr.is_null() || len <= 0 {
            return &[];
        }
        std::slice::from_raw_parts(ptr, len as usize)
    }
}

/// Connection-domain handles still alive. Test hook for asserting handles do not leak.
#[unsafe(no_mangle)]
pub extern "C" fn iroh4k_connection_live_handle_count() -> i64 {
    LIVE_HANDLES.load(Ordering::Relaxed)
}

// ----------------------------------------------------------------------------
// Handle release
//
// Each of these is also used as the `HandleDrop` for its type, so a cancelled operation that had
// already produced the object releases it — see `OpResult::with_handle`.
// ----------------------------------------------------------------------------

/// Releases an `Incoming` handle. Refuses the connection if it was never consumed, which is what
/// dropping an `Incoming` means in iroh.
///
/// Tolerates null so Kotlin's `close()` can be idempotent.
///
/// # Safety
/// `handle` must be null, or an `Incoming` handle from this module that has not been freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_incoming_free(handle: *mut c_void) {
    unsafe {
        handle::free::<IncomingHandle>(handle);
    }
}

/// Releases an `Accepting` handle, abandoning the handshake if it was never awaited.
///
/// # Safety
/// As [`iroh4k_incoming_free`], for an `Accepting` handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_accepting_free(handle: *mut c_void) {
    unsafe {
        handle::free::<AcceptingHandle>(handle);
    }
}

/// Releases a `Connecting` handle, abandoning the attempt if it was never awaited.
///
/// # Safety
/// As [`iroh4k_incoming_free`], for a `Connecting` handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connecting_free(handle: *mut c_void) {
    unsafe {
        handle::free::<ConnectingHandle>(handle);
    }
}

/// Releases a `Connection` handle.
///
/// Dropping the last reference closes the connection with error code `0` and an empty reason, which
/// is iroh's own behaviour. Call `iroh4k_connection_close` first to say something more specific.
///
/// # Safety
/// As [`iroh4k_incoming_free`], for a `Connection` handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_free(handle: *mut c_void) {
    unsafe {
        handle::free::<ConnectionHandle>(handle);
    }
}

/// Releases an `OutgoingZeroRttConnection` handle.
///
/// Dropping the last reference closes the connection with error code `0` and an empty reason, which
/// is iroh's own behaviour — exactly as [`iroh4k_connection_free`] documents. The distinction worth
/// having in mind here: `Connection<OutgoingZeroRtt>::handshake_completed(&self)` borrows rather than
/// consumes (`iroh-1.0.3/src/endpoint/connection.rs:1259`), and the `Connection` it produces arrives
/// *inside* the returned `ZeroRttStatus::Accepted` or `ZeroRttStatus::Rejected` (`:693`-`:701`), not
/// in place of this handle. So a 0-RTT handle is never the last reference once that call has
/// succeeded — this handle and the `Connection` handle wrapped in its `ZeroRttStatus` are two
/// independent references, and both must be released before the connection actually closes.
///
/// # Safety
/// As [`iroh4k_incoming_free`], for an `OutgoingZeroRttConnection` handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_outgoing_zero_rtt_free(handle: *mut c_void) {
    unsafe {
        handle::free::<OutgoingZeroRttHandle>(handle);
    }
}

/// Releases an `IncomingZeroRttConnection` handle.
///
/// As [`iroh4k_outgoing_zero_rtt_free`], for the server side of a 0-RTT handshake.
///
/// # Safety
/// As [`iroh4k_incoming_free`], for an `IncomingZeroRttConnection` handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_incoming_zero_rtt_free(handle: *mut c_void) {
    unsafe {
        handle::free::<IncomingZeroRttHandle>(handle);
    }
}

// ----------------------------------------------------------------------------
// Incoming — synchronous, because every one of these is synchronous in iroh
// ----------------------------------------------------------------------------

/// Accepts an incoming connection; the result carries an `Accepting` handle.
///
/// # Safety
/// `handle` must satisfy [`peek`]'s contract for an `Incoming` handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_incoming_accept(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<Consumed<Incoming>>(handle, |slot| incoming_accept(slot)) }
}

/// Rejects an incoming connection.
///
/// # Safety
/// As [`iroh4k_incoming_accept`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_incoming_refuse(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<Consumed<Incoming>>(handle, |slot| incoming_refuse(slot)) }
}

/// Asks the peer to retry with address validation.
///
/// # Safety
/// As [`iroh4k_incoming_accept`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_incoming_retry(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<Consumed<Incoming>>(handle, |slot| incoming_retry(slot)) }
}

/// Drops an incoming connection without answering it.
///
/// # Safety
/// As [`iroh4k_incoming_accept`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_incoming_ignore(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<Consumed<Incoming>>(handle, |slot| incoming_ignore(slot)) }
}

/// The local address that received the connection, as a codec payload.
///
/// # Safety
/// As [`iroh4k_incoming_accept`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_incoming_local_addr(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<Consumed<Incoming>>(handle, |slot| incoming_local_addr(slot)) }
}

/// The address the connection arrived from, as a codec payload.
///
/// # Safety
/// As [`iroh4k_incoming_accept`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_incoming_remote_addr(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<Consumed<Incoming>>(handle, |slot| incoming_remote_addr(slot)) }
}

/// `1` in `i64_val` if the peer's address has been validated.
///
/// # Safety
/// As [`iroh4k_incoming_accept`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_incoming_remote_addr_validated(
    handle: *mut c_void,
) -> *mut Iroh4kResult {
    unsafe { with::<Consumed<Incoming>>(handle, |slot| incoming_remote_addr_validated(slot)) }
}

/// Accepts an incoming connection with its own ALPN list and transport configuration; the result
/// carries an `Accepting` handle.
///
/// Synchronous, like [`iroh4k_incoming_accept`]: `accept_with` is synchronous in iroh too, so
/// `opts` is borrowed rather than copied — it need only survive this one call.
///
/// # Safety
/// `handle` must satisfy [`peek`]'s contract for an `Incoming` handle. `endpoint` must satisfy
/// [`crate::endpoint::endpoint_clone`]'s contract for an endpoint handle. `opts`/`opts_len` must
/// satisfy [`borrowed`]'s contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_incoming_accept_with(
    handle: *mut c_void,
    endpoint: *mut c_void,
    opts: *const u8,
    opts_len: c_int,
) -> *mut Iroh4kResult {
    unsafe {
        let opts = borrowed(opts, opts_len);
        let (alpns, transport) = match decode_accept_with_opts(opts) {
            Ok(parsed) => parsed,
            Err(message) => {
                return error_result(
                    ERROR_INVALID_ARGUMENT,
                    format!("malformed accept options: {message}"),
                );
            }
        };
        let Some(endpoint) = crate::endpoint::endpoint_clone(endpoint) else {
            return released();
        };
        with::<Consumed<Incoming>>(handle, |slot| {
            incoming_accept_with(slot, &endpoint, alpns, transport)
        })
    }
}

// ----------------------------------------------------------------------------
// Connecting — the one synchronous accessor
// ----------------------------------------------------------------------------

/// The 32 raw bytes of the endpoint id this attempt is aimed at.
///
/// Answers even after the attempt has been consumed: it is the id that was dialled, cached when the
/// handle was created, and it stays true whatever became of the handshake.
///
/// # Safety
/// `handle` must satisfy [`peek`]'s contract for a `Connecting` handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connecting_remote_id(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        with::<ConnectingSlot>(handle, |slot| {
            bytes_result(slot.remote_id.as_bytes().to_vec())
        })
    }
}

// ----------------------------------------------------------------------------
// Connection — synchronous
// ----------------------------------------------------------------------------

/// The negotiated ALPN, as raw bytes.
///
/// # Safety
/// `handle` must satisfy [`peek`]'s contract for a `Connection` handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_alpn(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        with::<Connection>(handle, |connection| {
            bytes_result(connection_alpn(connection))
        })
    }
}

/// The 32 raw bytes of the remote's endpoint id.
///
/// # Safety
/// As [`iroh4k_connection_alpn`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_remote_id(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        with::<Connection>(handle, |connection| {
            bytes_result(connection_remote_id(connection))
        })
    }
}

/// A stable identifier for the connection, in `i64_val`.
///
/// # Safety
/// `handle` must satisfy [`connection_clone_any`]'s contract for a connection handle of any of the
/// three kinds — `stable_id` is on `impl<T: ConnectionState>` upstream.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_stable_id(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        with_any(handle, |connection| {
            i64_result(connection_stable_id(connection))
        })
    }
}

/// Why the connection closed, as an optional string payload.
///
/// # Safety
/// As [`iroh4k_connection_stable_id`] — `close_reason` is on `impl<T: ConnectionState>` too.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_close_reason(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        with_any(handle, |connection| {
            bytes_result(connection_close_reason(connection))
        })
    }
}

/// Closes the connection immediately with `error_code` and `reason`.
///
/// # Safety
/// `handle` must satisfy [`connection_clone_any`]'s contract for a connection handle of any of the
/// three kinds — `close` is on `impl<T: ConnectionState>` too. `reason`/`reason_len` must satisfy
/// [`borrowed`]'s contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_close(
    handle: *mut c_void,
    error_code: i64,
    reason: *const u8,
    reason_len: c_int,
) -> *mut Iroh4kResult {
    unsafe {
        let reason = borrowed(reason, reason_len);
        with_any(handle, |connection| {
            connection_close(connection, error_code, reason)
        })
    }
}

/// `0` if this side dialled, `1` if it accepted, in `i64_val`.
///
/// # Safety
/// As [`iroh4k_connection_alpn`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_side(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<Connection>(handle, |connection| i64_result(connection_side(connection))) }
}

/// The selected path's round-trip time in microseconds, or `-1`, in `i64_val`.
///
/// # Safety
/// As [`iroh4k_connection_alpn`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_rtt(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<Connection>(handle, |connection| i64_result(connection_rtt(connection))) }
}

/// Connection-wide statistics, as a codec payload.
///
/// # Safety
/// As [`iroh4k_connection_stable_id`] — `stats` is on `impl<T: ConnectionState>` too.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_stats(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        with_any(handle, |connection| {
            bytes_result(connection_stats(connection))
        })
    }
}

/// A snapshot of the open network paths, as a codec payload.
///
/// # Safety
/// As [`iroh4k_connection_alpn`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_paths(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe {
        with::<Connection>(handle, |connection| {
            bytes_result(connection_paths(connection))
        })
    }
}

/// The largest datagram the connection can currently carry, or `-1`, in `i64_val`.
///
/// # Safety
/// As [`iroh4k_connection_stable_id`] — `max_datagram_size` is on `impl<T: ConnectionState>` too.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_max_datagram_size(
    handle: *mut c_void,
) -> *mut Iroh4kResult {
    unsafe {
        with_any(handle, |connection| {
            i64_result(connection_max_datagram_size(connection))
        })
    }
}

/// Bytes free in the outgoing datagram buffer, in `i64_val`.
///
/// # Safety
/// As [`iroh4k_connection_stable_id`] — `datagram_send_buffer_space` is on
/// `impl<T: ConnectionState>` too.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_datagram_send_buffer_space(
    handle: *mut c_void,
) -> *mut Iroh4kResult {
    unsafe {
        with_any(handle, |connection| {
            i64_result(width(connection.datagram_send_buffer_space()))
        })
    }
}

/// Sends an unreliable datagram, failing rather than waiting for buffer space.
///
/// # Safety
/// `handle` must satisfy [`connection_clone_any`]'s contract for a connection handle of any of the
/// three kinds — `send_datagram` is on `impl<T: ConnectionState>` too. `payload`/`payload_len` must
/// satisfy [`borrowed`]'s contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_send_datagram(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
) -> *mut Iroh4kResult {
    unsafe {
        let payload = borrowed(payload, payload_len);
        with_any(handle, |connection| {
            connection_send_datagram(connection, payload)
        })
    }
}

/// Sets how many bidirectional streams the peer may have open at once.
///
/// # Safety
/// As [`iroh4k_connection_stable_id`] — `set_max_concurrent_bi_streams` is on
/// `impl<T: ConnectionState>` too.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_set_max_concurrent_bi_streams(
    handle: *mut c_void,
    count: i64,
) -> *mut Iroh4kResult {
    unsafe {
        with_any(handle, |connection| {
            connection_set_limit(
                connection,
                count,
                "a maximum concurrent bidirectional stream count",
                |connection, count| connection.set_max_concurrent_bi_streams(count),
            )
        })
    }
}

/// Sets how many unidirectional streams the peer may have open at once.
///
/// # Safety
/// As [`iroh4k_connection_stable_id`] — `set_max_concurrent_uni_streams` is on
/// `impl<T: ConnectionState>` too.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_set_max_concurrent_uni_streams(
    handle: *mut c_void,
    count: i64,
) -> *mut Iroh4kResult {
    unsafe {
        with_any(handle, |connection| {
            connection_set_limit(
                connection,
                count,
                "a maximum concurrent unidirectional stream count",
                |connection, count| connection.set_max_concurrent_uni_streams(count),
            )
        })
    }
}

/// Sets the connection-level flow-control receive window, in bytes.
///
/// # Safety
/// As [`iroh4k_connection_stable_id`] — `set_receive_window` is on `impl<T: ConnectionState>` too.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_set_receive_window(
    handle: *mut c_void,
    bytes: i64,
) -> *mut Iroh4kResult {
    unsafe {
        with_any(handle, |connection| {
            connection_set_limit(
                connection,
                bytes,
                "a receive window",
                |connection, bytes| connection.set_receive_window(bytes),
            )
        })
    }
}

// ----------------------------------------------------------------------------
// Asynchronous
// ----------------------------------------------------------------------------

/// Awaits the next inbound connection attempt. Asynchronous.
///
/// The result's `handle` is an `Incoming`, or null once the endpoint has been closed.
///
/// Takes the **endpoint** domain's handle, not one of this module's: the accept chain belongs to
/// this domain, so the operation lives here and the endpoint is resolved through
/// `endpoint::endpoint_clone`.
///
/// # Safety
/// `endpoint` must be null, or a live endpoint handle from `iroh4k_endpoint_new` that has not been
/// freed. Kotlin's guard on `Endpoint` guarantees the second part.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_accept_next(
    endpoint: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let endpoint = crate::endpoint::endpoint_clone(endpoint);
        ops::spawn_callback(callback, fun, accept_next(endpoint))
    }
}

/// Starts an outbound connection attempt; the result carries a `Connecting` handle. Asynchronous.
///
/// # Safety
/// `endpoint` must satisfy [`iroh4k_endpoint_accept_next`]'s contract. `addr`/`addr_len` and
/// `alpn`/`alpn_len` must be null/0 or describe that many readable bytes; both are **copied** before
/// the future is spawned, so the caller may free them as soon as this returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_start_connect(
    endpoint: *mut c_void,
    addr: *const u8,
    addr_len: c_int,
    alpn: *const u8,
    alpn_len: c_int,
    opts: *const u8,
    opts_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let endpoint = crate::endpoint::endpoint_clone(endpoint);
        let addr = owned_bytes(addr, addr_len);
        let alpn = owned_bytes(alpn, alpn_len);
        // Copied, not borrowed: this is an asynchronous operation, so the pinned Kotlin array is
        // gone long before the spawned future reads the payload.
        let opts = owned_bytes(opts, opts_len);
        ops::spawn_callback(callback, fun, start_connect(endpoint, addr, alpn, opts))
    }
}

/// Completes an accepted handshake; the result carries a `Connection` handle. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`share`]'s contract for an `Accepting` handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_accepting_connect(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<Once<Accepting>>(handle);
        ops::spawn_callback(callback, fun, accepting_connect(slot))
    }
}

/// The ALPN negotiated for an accepted handshake, as raw bytes. Asynchronous.
///
/// # Safety
/// As [`iroh4k_accepting_connect`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_accepting_alpn(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<Once<Accepting>>(handle);
        ops::spawn_callback(callback, fun, accepting_alpn(slot))
    }
}

/// Completes an outbound handshake; the result carries a `Connection` handle. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`share`]'s contract for a `Connecting` handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connecting_connect(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<ConnectingSlot>(handle);
        ops::spawn_callback(callback, fun, connecting_connect(slot))
    }
}

/// The ALPN negotiated for an outbound handshake, as raw bytes. Asynchronous.
///
/// # Safety
/// As [`iroh4k_connecting_connect`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connecting_alpn(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<ConnectingSlot>(handle);
        ops::spawn_callback(callback, fun, connecting_alpn(slot))
    }
}

/// Dials `addr` and completes the handshake; the result carries a `Connection` handle. Asynchronous.
///
/// # Safety
/// As [`iroh4k_endpoint_start_connect`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_endpoint_connect(
    endpoint: *mut c_void,
    addr: *const u8,
    addr_len: c_int,
    alpn: *const u8,
    alpn_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let endpoint = crate::endpoint::endpoint_clone(endpoint);
        let addr = owned_bytes(addr, addr_len);
        let alpn = owned_bytes(alpn, alpn_len);
        ops::spawn_callback(callback, fun, connect(endpoint, addr, alpn))
    }
}

/// Suspends until the connection closes, then answers with iroh's reason text. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`connection_clone_any`]'s contract for a connection handle of any of the
/// three kinds — `closed` is on `impl<T: ConnectionState>` upstream.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_closed(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let connection = connection_clone_any(handle);
        ops::spawn_callback(callback, fun, connection_closed(connection))
    }
}

/// Sends a datagram, waiting for buffer space if there is none. Asynchronous.
///
/// # Safety
/// `handle` must satisfy [`connection_clone_any`]'s contract for a connection handle of any of the
/// three kinds — `send_datagram_wait` is on `impl<T: ConnectionState>` too. `payload`/`payload_len`
/// must be null/0 or describe that many readable bytes; the buffer is **copied** before the future is
/// spawned, so the caller may free it as soon as this returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_send_datagram_wait(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let connection = connection_clone_any(handle);
        let payload = owned_bytes(payload, payload_len);
        ops::spawn_callback(
            callback,
            fun,
            connection_send_datagram_wait(connection, payload),
        )
    }
}

/// Suspends until a datagram arrives, and answers with its bytes. Asynchronous.
///
/// # Safety
/// As [`iroh4k_connection_closed`] — `read_datagram` is on `impl<T: ConnectionState>` too.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_read_datagram(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let connection = connection_clone_any(handle);
        ops::spawn_callback(callback, fun, connection_read_datagram(connection))
    }
}

// ============================================================================
// JNI — JVM and Android via the shared library
//
// Unavailable on iOS, which uses the FFI/cinterop path exclusively. Symbol names match the Kotlin
// object `tech.annexflow.iroh4k.ConnectionJni`.
//
// Handles travel as `jlong`, the JVM's only pointer-sized integer. Each `*Start` export copies its
// arguments out of the JVM before spawning, then returns an operation id for
// `Iroh4kJni.opAwait`/`opCancel`.
// ============================================================================

#[cfg(not(target_os = "ios"))]
#[allow(non_snake_case)]
mod jni_facade {
    use super::*;
    use jni::EnvUnowned;
    use jni::objects::{JByteArray, JClass};
    use jni::sys::{jbyteArray, jlong};

    // One shared envelope writer — see `crate::jni::finish`.
    use crate::jni::finish;

    /// Reinterprets a `jlong` handle as the pointer it came from.
    ///
    /// # Safety
    /// `handle` must be `0`, or a value returned by a `newHandle`-style export for a handle that has
    /// not been freed. Kotlin's guard is what guarantees that — see [`peek`].
    unsafe fn as_handle(handle: jlong) -> *mut c_void {
        handle as usize as *mut c_void
    }

    /// Copies a Java `byte[]` into an owned `Vec`.
    ///
    /// An unreadable or absent array becomes empty, which the payload reader then rejects as
    /// malformed. That is deliberate: the FFI boundary must never unwind, so a missing argument is
    /// reported like any other malformed one.
    fn arg(env: &mut EnvUnowned, array: &JByteArray) -> Vec<u8> {
        crate::jni::with_env(env, |env| env.convert_byte_array(array)).unwrap_or_default()
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_liveHandleCount(
        _env: EnvUnowned,
        _class: JClass,
    ) -> jlong {
        LIVE_HANDLES.load(Ordering::Relaxed)
    }

    // ── Handle release ───────────────────────────────────────────────────────────────────────

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_freeIncoming(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<IncomingHandle>(as_handle(handle)) };
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_freeAccepting(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<AcceptingHandle>(as_handle(handle)) };
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_freeConnecting(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<ConnectingHandle>(as_handle(handle)) };
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_freeConnection(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<ConnectionHandle>(as_handle(handle)) };
    }

    /// As [`super::iroh4k_outgoing_zero_rtt_free`].
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_freeOutgoingZeroRtt(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<OutgoingZeroRttHandle>(as_handle(handle)) };
    }

    /// As [`super::iroh4k_incoming_zero_rtt_free`].
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_freeIncomingZeroRtt(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<IncomingZeroRttHandle>(as_handle(handle)) };
    }

    // ── Incoming ─────────────────────────────────────────────────────────────────────────────

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_incomingAccept(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result =
            unsafe { with::<Consumed<Incoming>>(as_handle(handle), |slot| incoming_accept(slot)) };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_incomingRefuse(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result =
            unsafe { with::<Consumed<Incoming>>(as_handle(handle), |slot| incoming_refuse(slot)) };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_incomingRetry(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result =
            unsafe { with::<Consumed<Incoming>>(as_handle(handle), |slot| incoming_retry(slot)) };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_incomingIgnore(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result =
            unsafe { with::<Consumed<Incoming>>(as_handle(handle), |slot| incoming_ignore(slot)) };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_incomingLocalAddr(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with::<Consumed<Incoming>>(as_handle(handle), |slot| incoming_local_addr(slot))
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_incomingRemoteAddr(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with::<Consumed<Incoming>>(as_handle(handle), |slot| incoming_remote_addr(slot))
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_incomingRemoteAddrValidated(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with::<Consumed<Incoming>>(as_handle(handle), |slot| {
                incoming_remote_addr_validated(slot)
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_incomingAcceptWith(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        endpoint: jlong,
        opts: JByteArray,
    ) -> jbyteArray {
        let opts = arg(&mut env, &opts);
        let result = match decode_accept_with_opts(&opts) {
            Ok((alpns, transport)) => {
                match unsafe { crate::endpoint::endpoint_clone(as_handle(endpoint)) } {
                    Some(endpoint) => unsafe {
                        with::<Consumed<Incoming>>(as_handle(handle), |slot| {
                            incoming_accept_with(slot, &endpoint, alpns, transport)
                        })
                    },
                    None => released(),
                }
            }
            Err(message) => error_result(
                ERROR_INVALID_ARGUMENT,
                format!("malformed accept options: {message}"),
            ),
        };
        finish(&mut env, result)
    }

    // ── Connecting and Connection — synchronous ──────────────────────────────────────────────

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectingRemoteId(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with::<ConnectingSlot>(as_handle(handle), |slot| {
                bytes_result(slot.remote_id.as_bytes().to_vec())
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionAlpn(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with::<Connection>(as_handle(handle), |connection| {
                bytes_result(connection_alpn(connection))
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionRemoteId(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with::<Connection>(as_handle(handle), |connection| {
                bytes_result(connection_remote_id(connection))
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionStableId(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_any(as_handle(handle), |connection| {
                i64_result(connection_stable_id(connection))
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionCloseReason(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_any(as_handle(handle), |connection| {
                bytes_result(connection_close_reason(connection))
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionClose(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        error_code: jlong,
        reason: JByteArray,
    ) -> jbyteArray {
        let reason = arg(&mut env, &reason);
        let result = unsafe {
            with_any(as_handle(handle), |connection| {
                connection_close(connection, error_code, &reason)
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionSide(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with::<Connection>(as_handle(handle), |connection| {
                i64_result(connection_side(connection))
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionRtt(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with::<Connection>(as_handle(handle), |connection| {
                i64_result(connection_rtt(connection))
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionStats(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_any(as_handle(handle), |connection| {
                bytes_result(connection_stats(connection))
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionPaths(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with::<Connection>(as_handle(handle), |connection| {
                bytes_result(connection_paths(connection))
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionMaxDatagramSize(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_any(as_handle(handle), |connection| {
                i64_result(connection_max_datagram_size(connection))
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionDatagramSendBufferSpace(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_any(as_handle(handle), |connection| {
                i64_result(width(connection.datagram_send_buffer_space()))
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionSendDatagram(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        payload: JByteArray,
    ) -> jbyteArray {
        let payload = arg(&mut env, &payload);
        let result = unsafe {
            with_any(as_handle(handle), |connection| {
                connection_send_datagram(connection, &payload)
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionSetMaxConcurrentBiStreams(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        count: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_any(as_handle(handle), |connection| {
                connection_set_limit(
                    connection,
                    count,
                    "a maximum concurrent bidirectional stream count",
                    |connection, count| connection.set_max_concurrent_bi_streams(count),
                )
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionSetMaxConcurrentUniStreams(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        count: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_any(as_handle(handle), |connection| {
                connection_set_limit(
                    connection,
                    count,
                    "a maximum concurrent unidirectional stream count",
                    |connection, count| connection.set_max_concurrent_uni_streams(count),
                )
            })
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionSetReceiveWindow(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        bytes: jlong,
    ) -> jbyteArray {
        let result = unsafe {
            with_any(as_handle(handle), |connection| {
                connection_set_limit(
                    connection,
                    bytes,
                    "a receive window",
                    |connection, bytes| connection.set_receive_window(bytes),
                )
            })
        };
        finish(&mut env, result)
    }

    // ── Asynchronous ─────────────────────────────────────────────────────────────────────────

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_acceptNextStart(
        _env: EnvUnowned,
        _class: JClass,
        endpoint: jlong,
    ) -> jlong {
        let endpoint = unsafe { crate::endpoint::endpoint_clone(as_handle(endpoint)) };
        ops::spawn_channel(accept_next(endpoint))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_startConnectStart(
        mut env: EnvUnowned,
        _class: JClass,
        endpoint: jlong,
        addr: JByteArray,
        alpn: JByteArray,
        opts: JByteArray,
    ) -> jlong {
        let endpoint = unsafe { crate::endpoint::endpoint_clone(as_handle(endpoint)) };
        let addr = arg(&mut env, &addr);
        let alpn = arg(&mut env, &alpn);
        let opts = arg(&mut env, &opts);
        ops::spawn_channel(start_connect(endpoint, addr, alpn, opts))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_acceptingConnectStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { share::<Once<Accepting>>(as_handle(handle)) };
        ops::spawn_channel(accepting_connect(slot))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_acceptingAlpnStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { share::<Once<Accepting>>(as_handle(handle)) };
        ops::spawn_channel(accepting_alpn(slot))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectingConnectStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { share::<ConnectingSlot>(as_handle(handle)) };
        ops::spawn_channel(connecting_connect(slot))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectingAlpnStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { share::<ConnectingSlot>(as_handle(handle)) };
        ops::spawn_channel(connecting_alpn(slot))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectStart(
        mut env: EnvUnowned,
        _class: JClass,
        endpoint: jlong,
        addr: JByteArray,
        alpn: JByteArray,
    ) -> jlong {
        let endpoint = unsafe { crate::endpoint::endpoint_clone(as_handle(endpoint)) };
        let addr = arg(&mut env, &addr);
        let alpn = arg(&mut env, &alpn);
        ops::spawn_channel(connect(endpoint, addr, alpn))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionClosedStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let connection = unsafe { connection_clone_any(as_handle(handle)) };
        ops::spawn_channel(connection_closed(connection))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionSendDatagramWaitStart(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        payload: JByteArray,
    ) -> jlong {
        let connection = unsafe { connection_clone_any(as_handle(handle)) };
        let payload = arg(&mut env, &payload);
        ops::spawn_channel(connection_send_datagram_wait(connection, payload))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_ConnectionJni_connectionReadDatagramStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let connection = unsafe { connection_clone_any(as_handle(handle)) };
        ops::spawn_channel(connection_read_datagram(connection))
    }
}
