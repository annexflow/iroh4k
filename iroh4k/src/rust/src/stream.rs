//! QUIC streams: opening them, accepting them, and the two halves they come in.
//!
//! Owned by the stream domain, and structured exactly as [`crate::connection`] is: shared logic
//! first, then both facades' exports for it. It leans on that module rather than repeating it — the
//! handle-payload wrapper, the handle accessors, the varint parser, the "released" answer and the
//! runtime guard are all `pub(crate)` there and used here, because a second copy of any of them
//! would drift.
//!
//! ## One handle type for three Kotlin types
//!
//! iroh hands out `SendStream` and `RecvStream` separately, and a bidirectional open produces one of
//! each *at the same time*. An [`crate::core::Iroh4kResult`] carries one handle, and
//! `OpResult::with_handle` releases one handle — so an operation that produced two of them could
//! strand the second if a cancel won the deliver-once race.
//!
//! So there is one payload, [`StreamSlot`], holding whichever halves exist, and one handle type over
//! it. A unidirectional open fills the send half, a unidirectional accept the receive half, and a
//! bidirectional open or accept fills both. Kotlin then asks for a **second handle to the same
//! payload** through [`iroh4k_stream_share`] — a plain `Arc` strong-count bump — and gives one to its
//! `SendStream` and one to its `RecvStream`, so the two Kotlin objects have independent lifetimes
//! while there is only ever one handle for cancellation to have to clean up.
//!
//! The deviation that buys: dropping iroh's own `RecvStream` sends STOP_SENDING to the peer, and here
//! that happens when *both* halves of a bidirectional stream have been released rather than as soon
//! as the receiving one is. `RecvStream::stop` is the explicit way to say it earlier, and
//! `BiStream.close()` releases both at once, so the common paths are unaffected.
//!
//! ## `&mut self` I/O behind a shared handle
//!
//! Every I/O method on iroh's streams takes `&mut self` — `write`, `write_all`, `read`, `read_exact`,
//! `read_to_end`, `finish`, `reset`, `stop`, `received_reset` — while a handle is shared by
//! definition: Kotlin may call from any thread, and the same stream may be reachable from several
//! coroutines.
//!
//! Each half therefore lives in a **`tokio::sync::Mutex`**, and an operation holds the guard for its
//! whole duration, suspension points included. It has to be tokio's and not `std`'s for a reason that
//! is not a matter of taste: a `std::sync::MutexGuard` is not `Send`, so a future holding one cannot
//! be spawned onto the runtime at all — the code would not compile. The same choice, for the same
//! reason, as `Once` in `connection.rs`.
//!
//! What that means for a caller is that concurrent operations on *one half* serialise rather than
//! interleave, which is the only coherent answer for a byte stream anyway: two `write`s racing on the
//! same stream would produce interleaved garbage. The two halves of a bidirectional stream have
//! separate locks, so reading and writing at the same time is genuinely concurrent — which is what
//! makes an echo over a single stream possible.
//!
//! Cancelling an operation aborts the tokio task, which drops the guard, which frees the lock. So a
//! cancelled read does not wedge the stream. Whether the *stream* survives depends on iroh: `write`,
//! `read`, `read_chunk` and `received_reset` are documented cancel-safe, while `write_all`,
//! `read_exact` and `read_to_end` are not — a cancelled one of those may have consumed or transmitted
//! a prefix, and Kotlin's documentation says so.
//!
//! ## Error codes
//!
//! [`ERROR_WRITE`] and [`ERROR_READ`] for I/O that failed as I/O, and [`ERROR_CLOSED`] wherever iroh
//! says the thing being used is gone — a finished, reset or stopped stream, or a connection that
//! ended. Reading zero bytes at the end of a stream is **not** an error: it is `-1` in `i64_val` with
//! no payload, which Kotlin reads as `null`.
//!
//! [`ERROR_ZERO_RTT_REJECTED`] is the one code in this file with its own `#[cfg(test)]` module —
//! the first in this crate. A live rejection over a real connection cannot pin it deterministically
//! (see `CommonConnectionTests.kt`'s note on the write assertion it deliberately does not make: the
//! stream's 0-RTT-ness is decided by `noq` at the first poll of `open_bi`, and that races the
//! connection's own background driver independently of anything Kotlin can sequence), so the four
//! mappers are instead tested directly here, off a hand-built `ZeroRttRejected` value, with no
//! connection involved at all. That pins one half — the mapper produces the constant — and
//! `CommonSmokeTests` pins the other — the constant's ordinal decodes to this variant in Kotlin.
//!
//! ## Codec layout
//!
//! None. Every value here is either raw stream bytes or a scalar, so nothing needs the codec — the
//! optional scalars use the `-1`-for-absent convention `connection.rs` documents.

use std::{
    ffi::{c_int, c_void},
    sync::{
        Arc,
        atomic::{AtomicI64, Ordering},
    },
};

use iroh::endpoint::{
    ConnectionError, ReadError, ReadExactError, ReadToEndError, RecvStream, ResetError, SendStream,
    StoppedError, VarInt, WriteError,
};
use tokio::sync::Mutex;

use crate::connection::{
    AnyConnection, Completion, Tracked, connection_clone_any, in_runtime, released, share, varint,
    with,
};
use crate::core::{
    ERROR_CLOSED, ERROR_INVALID_ARGUMENT, ERROR_READ, ERROR_WRITE, ERROR_ZERO_RTT_REJECTED,
    Iroh4kResult, bytes_result, error_result, handle_result, i64_result, ok_result, owned_bytes,
};
use crate::handle::{self, Tagged};
use crate::ops::{self, OpResult};

// ============================================================================
// Handle payload
// ============================================================================

/// Stream handles still alive.
///
/// Separate from the connection domain's counter on purpose: a stream leaked by an open/close loop
/// has to be visible even while connections are being created and released around it.
static LIVE_HANDLES: AtomicI64 = AtomicI64::new(0);

/// The halves of one QUIC stream, however many of them there are.
///
/// `None` for a half this stream does not have — the receive half of a unidirectional stream this
/// side opened, or the send half of one it accepted. Reaching for a missing half is unreachable
/// through the Kotlin API, which only ever wraps the halves that exist, and is answered with
/// [`ERROR_CLOSED`] rather than unwrapped: this crate builds with `panic = "abort"`.
pub(crate) struct StreamSlot {
    send: Option<Mutex<SendStream>>,
    recv: Option<Mutex<RecvStream>>,
}

type StreamShared = Arc<Tagged<StreamHandle>>;
type StreamHandle = Tracked<StreamSlot>;

/// Wraps a set of halves as a handle payload, counting it into [`LIVE_HANDLES`].
fn slot(send: Option<SendStream>, recv: Option<RecvStream>) -> StreamHandle {
    Tracked::new(
        &LIVE_HANDLES,
        StreamSlot {
            send: send.map(Mutex::new),
            recv: recv.map(Mutex::new),
        },
    )
}

/// The result a handle for the wrong direction would produce. See [`StreamSlot`].
fn wrong_half(half: &str) -> *mut Iroh4kResult {
    error_result(
        ERROR_CLOSED,
        format!("this stream has no {half} half, so that operation has nothing to act on"),
    )
}

/// How the two halves are named in a message, kept in one place so they read the same everywhere.
const SEND: &str = "send";
const RECEIVE: &str = "receive";

// ============================================================================
// Failures
// ============================================================================

/// Why a write failed.
///
/// [`ERROR_CLOSED`] for a stream that has already been finished or reset, and for a connection that
/// ended — in both cases there is nothing left to write *to*, which is a different fact from a write
/// having gone wrong. [`ERROR_WRITE`] for the peer having stopped the stream, which carries its own
/// application error code and is reported in the message. [`ERROR_ZERO_RTT_REJECTED`] for a stream
/// opened before a 0-RTT handshake completed, once the peer has refused that early data — see
/// `connection.rs`'s `ZeroRttStatus` doc. That is a distinct fact from an ordinary write failure: the
/// peer is there and reachable, it just could not decrypt what was sent before it vouched for its own
/// identity, so the caller's answer is to resend on the now-established connection rather than give up.
fn write_failure(error: &WriteError) -> *mut Iroh4kResult {
    let code = match error {
        WriteError::ClosedStream | WriteError::ConnectionLost(_) => ERROR_CLOSED,
        WriteError::ZeroRttRejected => ERROR_ZERO_RTT_REJECTED,
        _ => ERROR_WRITE,
    };
    error_result(code, format!("could not write to the stream: {error}"))
}

/// Why a read failed. The mirror of [`write_failure`], with a reset by the peer as the read-side
/// counterpart of a stop and the same [`ERROR_ZERO_RTT_REJECTED`] carve-out for early data the peer
/// refused. Reached from `ReadExactError` and `ReadToEndError` too — both wrap a [`ReadError`] and
/// unwrap it back to this function rather than duplicating the match.
fn read_failure(error: &ReadError) -> *mut Iroh4kResult {
    let code = match error {
        ReadError::ClosedStream | ReadError::ConnectionLost(_) => ERROR_CLOSED,
        ReadError::ZeroRttRejected => ERROR_ZERO_RTT_REJECTED,
        _ => ERROR_READ,
    };
    error_result(code, format!("could not read from the stream: {error}"))
}

/// The result for an operation on a stream iroh reports as already closed.
///
/// `ClosedStream` reaches here from `finish`, `reset`, `stop`, `set_priority`, `priority` and
/// `bytes_read`. iroh documents it as harmless — it says only that the caller may have the wrong idea
/// about the stream's state — which is exactly what [`ERROR_CLOSED`] means here.
fn closed_stream(what: &str) -> *mut Iroh4kResult {
    error_result(
        ERROR_CLOSED,
        format!("this stream has already been finished, reset or stopped, so {what} has no effect"),
    )
}

/// Rejects a read size the boundary cannot carry.
///
/// The upper bound is not arbitrary: the payload length is an `i32` on the wire and a Kotlin
/// `ByteArray` is indexed by `Int`, so a larger read has nowhere to go. The lower bound rules out
/// zero, which would make a real read indistinguishable from the end of the stream.
fn read_size(value: i64, what: &str) -> Result<usize, *mut Iroh4kResult> {
    if value <= 0 || value > i64::from(i32::MAX) {
        return Err(error_result(
            ERROR_INVALID_ARGUMENT,
            format!("{what} must be 1..={}, got {value}", i32::MAX),
        ));
    }
    Ok(value as usize)
}

/// A `VarInt` from iroh reported as the `i64` the boundary carries.
fn code_of(value: VarInt) -> i64 {
    i64::try_from(u64::from(value)).unwrap_or(i64::MAX)
}

/// An optional application error code: the code, or `-1` for absent.
///
/// Used by `stopped` and `received_reset`, both of which answer "yes, with this code" or "no, and
/// that is not a failure". See the note on sentinels in `connection.rs`.
fn optional_code(value: Option<VarInt>) -> *mut Iroh4kResult {
    i64_result(value.map_or(-1, code_of))
}

// ============================================================================
// Shared logic — opening and accepting
//
// All four borrow the connection to build their future, so all four take an owned `AnyConnection`
// and create the borrow inside the spawned block — the idiom `connection.rs` documents. Resolved
// through `connection_clone_any` rather than the strict `connection_clone`: opening and accepting
// streams is on `impl<T: ConnectionState>` upstream, so a 0-RTT handle answers these too — the whole
// point of this domain running through the three-way probe at all.
// ============================================================================

/// Turns a stream-open outcome into a result carrying a handle.
///
/// The handle is created inside an asynchronous operation, so `ops` is told how to release it: a
/// cancel winning the deliver-once race discards the result, and without the drop function that would
/// strand an open QUIC stream — and, through it, keep the connection alive.
fn opened(outcome: Result<StreamHandle, ConnectionError>, what: &str) -> OpResult {
    match outcome {
        Ok(payload) => OpResult::with_handle(
            handle_result(handle::into_handle(payload)),
            iroh4k_stream_free,
        ),
        // A stream open or accept fails only when the connection is gone, which is what `ERROR_CLOSED`
        // says — and it is what makes a blocked `acceptBi` on a closed connection end rather than wait.
        Err(error) => OpResult::new(error_result(
            ERROR_CLOSED,
            format!("could not {what} a stream: {error}"),
        )),
    }
}

/// Opens a bidirectional stream, filling both halves of one slot.
///
/// Returns as soon as QUIC has an id for it — no round trip is involved, and the peer sees nothing
/// until data is written, which is why an unwritten stream never reaches its `accept_bi`.
async fn open_bi(connection: Option<AnyConnection>) -> OpResult {
    let Some(connection) = connection else {
        return OpResult::new(released());
    };
    opened(
        connection
            .open_bi()
            .await
            .map(|(send, recv)| slot(Some(send), Some(recv))),
        "open",
    )
}

/// Awaits the peer's next bidirectional stream. Waits indefinitely by design.
async fn accept_bi(connection: Option<AnyConnection>) -> OpResult {
    let Some(connection) = connection else {
        return OpResult::new(released());
    };
    opened(
        connection
            .accept_bi()
            .await
            .map(|(send, recv)| slot(Some(send), Some(recv))),
        "accept",
    )
}

/// Opens a unidirectional stream: a send half and no receive half.
async fn open_uni(connection: Option<AnyConnection>) -> OpResult {
    let Some(connection) = connection else {
        return OpResult::new(released());
    };
    opened(
        connection
            .open_uni()
            .await
            .map(|send| slot(Some(send), None)),
        "open",
    )
}

/// Awaits the peer's next unidirectional stream: a receive half and no send half.
async fn accept_uni(connection: Option<AnyConnection>) -> OpResult {
    let Some(connection) = connection else {
        return OpResult::new(released());
    };
    opened(
        connection
            .accept_uni()
            .await
            .map(|recv| slot(None, Some(recv))),
        "accept",
    )
}

// ============================================================================
// Shared logic — the send half
// ============================================================================

/// Writes as much of `payload` as the stream will take right now, reporting how much that was.
///
/// A short write is normal rather than a failure: congestion and flow control decide how much fits,
/// and the caller writes the rest. `write_all` is the loop over this.
async fn send_write(slot: Option<StreamShared>, payload: Vec<u8>) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let Some(send) = slot.send.as_ref() else {
        return OpResult::new(wrong_half(SEND));
    };
    // The guard is held across the await — see the module header on why it must be tokio's mutex.
    let mut send = send.lock().await;
    match send.write(&payload).await {
        Ok(written) => OpResult::new(i64_result(written as i64)),
        Err(error) => OpResult::new(write_failure(&error)),
    }
}

/// Writes all of `payload`, however many round trips that takes.
async fn send_write_all(slot: Option<StreamShared>, payload: Vec<u8>) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let Some(send) = slot.send.as_ref() else {
        return OpResult::new(wrong_half(SEND));
    };
    let mut send = send.lock().await;
    match send.write_all(&payload).await {
        Ok(()) => OpResult::new(ok_result()),
        Err(error) => OpResult::new(write_failure(&error)),
    }
}

/// Suspends until the peer stops the stream or has acknowledged everything written to it.
///
/// Answers the stop code, or `-1` when the peer simply received it all — which is why this is the way
/// to know a finished stream really arrived.
async fn send_stopped(slot: Option<StreamShared>) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let Some(send) = slot.send.as_ref() else {
        return OpResult::new(wrong_half(SEND));
    };
    // `stopped()` needs only `&self` and returns an owned future, so the lock is released before the
    // await rather than held across it. That matters: holding it would block a concurrent `write` on
    // the same half for as long as the peer stayed quiet, which is potentially forever.
    let stopped = send.lock().await.stopped();
    match stopped.await {
        Ok(code) => OpResult::new(optional_code(code)),
        Err(error) => OpResult::new(stopped_failure(&error)),
    }
}

/// Why waiting for the peer to stop the stream failed.
///
/// `StoppedError` has exactly these two variants upstream — no catch-all arm, so a third one added
/// later fails this match at compile time instead of silently falling into the wrong code.
/// [`ERROR_ZERO_RTT_REJECTED`] covers a stream opened before a 0-RTT handshake completed once the
/// peer refuses that early data — see [`write_failure`]'s note on what the caller should do about it.
fn stopped_failure(error: &StoppedError) -> *mut Iroh4kResult {
    let code = match error {
        StoppedError::ConnectionLost(_) => ERROR_CLOSED,
        StoppedError::ZeroRttRejected => ERROR_ZERO_RTT_REJECTED,
    };
    error_result(
        code,
        format!("could not wait for the peer to stop the stream: {error}"),
    )
}

/// Tells the peer nothing more will be written. Synchronous in iroh.
///
/// Does not wait for anything already buffered to arrive — `stopped` is what does that.
fn send_finish(slot: &StreamSlot) -> *mut Iroh4kResult {
    let Some(send) = slot.send.as_ref() else {
        return wrong_half(SEND);
    };
    // `try_lock` rather than `blocking_lock`: this is a synchronous FFI call on a Kotlin thread, and
    // `blocking_lock` panics if it is ever reached from inside the runtime — which, with
    // `panic = "abort"`, would take the host process down. A caller who really has a concurrent write
    // in flight gets told the stream is busy instead.
    let Ok(mut send) = send.try_lock() else {
        return busy(ERROR_WRITE, SEND);
    };
    match in_runtime(|| send.finish()) {
        Ok(()) => ok_result(),
        Err(_) => closed_stream("finishing"),
    }
}

/// Abandons the stream, discarding anything still buffered. Synchronous in iroh.
fn send_reset(slot: &StreamSlot, error_code: i64) -> *mut Iroh4kResult {
    let Some(send) = slot.send.as_ref() else {
        return wrong_half(SEND);
    };
    let code = match varint(error_code, "a stream reset code") {
        Ok(code) => code,
        Err(failure) => return failure,
    };
    let Ok(mut send) = send.try_lock() else {
        return busy(ERROR_WRITE, SEND);
    };
    match in_runtime(|| send.reset(code)) {
        Ok(()) => ok_result(),
        Err(_) => closed_stream("resetting"),
    }
}

/// Sets or reads the transmission priority of this stream relative to the connection's others.
///
/// Both take `&self` in iroh, so neither needs the lock exclusively — but the lock is still what
/// makes the `&`-borrow of the stream safe next to a `&mut` one in flight, so both take it.
fn send_set_priority(slot: &StreamSlot, priority: i32) -> *mut Iroh4kResult {
    let Some(send) = slot.send.as_ref() else {
        return wrong_half(SEND);
    };
    let Ok(send) = send.try_lock() else {
        return busy(ERROR_WRITE, SEND);
    };
    match send.set_priority(priority) {
        Ok(()) => ok_result(),
        Err(_) => closed_stream("setting the priority"),
    }
}

fn send_priority(slot: &StreamSlot) -> *mut Iroh4kResult {
    let Some(send) = slot.send.as_ref() else {
        return wrong_half(SEND);
    };
    let Ok(send) = send.try_lock() else {
        return busy(ERROR_WRITE, SEND);
    };
    match send.priority() {
        Ok(priority) => i64_result(i64::from(priority)),
        Err(_) => closed_stream("reading the priority"),
    }
}

fn send_id(slot: &StreamSlot) -> *mut Iroh4kResult {
    let Some(send) = slot.send.as_ref() else {
        return wrong_half(SEND);
    };
    let Ok(send) = send.try_lock() else {
        return busy(ERROR_WRITE, SEND);
    };
    i64_result(stream_id(u64::from(send.id())))
}

// ============================================================================
// Shared logic — the receive half
// ============================================================================

/// Reads whatever is available, up to `size_limit` bytes.
///
/// Short reads are normal and are the caller's to loop over — a QUIC stream is bytes, not messages,
/// and chunk boundaries have nothing to do with the peer's writes. The end of the stream is `-1` in
/// `i64_val` and no payload, never an error.
///
/// Implemented with iroh's `read_chunk` rather than `read`: `read` fills a caller-supplied buffer, so
/// serving it would mean allocating `size_limit` bytes before knowing whether one byte was waiting.
/// `read_chunk` allocates nothing and hands over the bytes it already had.
async fn recv_read(slot: Option<StreamShared>, size_limit: i64) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let Some(recv) = slot.recv.as_ref() else {
        return OpResult::new(wrong_half(RECEIVE));
    };
    let limit = match read_size(size_limit, "a read size limit") {
        Ok(limit) => limit,
        Err(failure) => return OpResult::new(failure),
    };
    let mut recv = recv.lock().await;
    match recv.read_chunk(limit).await {
        Ok(Some(chunk)) => OpResult::new(bytes_result(chunk.to_vec())),
        Ok(None) => OpResult::new(i64_result(END_OF_STREAM)),
        Err(error) => OpResult::new(read_failure(&error)),
    }
}

/// Reads exactly `size` bytes, or fails.
///
/// Fails rather than blocks when the peer finishes the stream early, which is the whole point of it:
/// a caller that framed its data can say how much it expects and hear about a truncated stream
/// instead of waiting for bytes that will never come.
async fn recv_read_exact(slot: Option<StreamShared>, size: i64) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let Some(recv) = slot.recv.as_ref() else {
        return OpResult::new(wrong_half(RECEIVE));
    };
    let size = match read_size(size, "a read size") {
        Ok(size) => size,
        Err(failure) => return OpResult::new(failure),
    };
    // Unlike every other read here, this one must allocate up front — the size is what was asked for.
    // `try_reserve_exact` turns a refused allocation into an error instead of an abort, which with
    // `panic = "abort"` is the difference between an exception and a dead host process.
    let mut buffer = Vec::new();
    if buffer.try_reserve_exact(size).is_err() {
        return OpResult::new(error_result(
            ERROR_INVALID_ARGUMENT,
            format!("could not reserve {size} bytes for a read"),
        ));
    }
    buffer.resize(size, 0);

    let mut recv = recv.lock().await;
    match recv.read_exact(&mut buffer).await {
        Ok(()) => OpResult::new(bytes_result(buffer)),
        Err(ReadExactError::ReadError(error)) => OpResult::new(read_failure(&error)),
        Err(error @ ReadExactError::FinishedEarly(_)) => OpResult::new(error_result(
            ERROR_READ,
            format!("could not read {size} bytes: {error}"),
        )),
    }
}

/// Reads the stream to its end, refusing to buffer more than `size_limit` bytes.
async fn recv_read_to_end(slot: Option<StreamShared>, size_limit: i64) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let Some(recv) = slot.recv.as_ref() else {
        return OpResult::new(wrong_half(RECEIVE));
    };
    let limit = match read_size(size_limit, "a read size limit") {
        Ok(limit) => limit,
        Err(failure) => return OpResult::new(failure),
    };
    let mut recv = recv.lock().await;
    match recv.read_to_end(limit).await {
        Ok(bytes) => OpResult::new(bytes_result(bytes)),
        Err(ReadToEndError::Read(error)) => OpResult::new(read_failure(&error)),
        Err(error @ ReadToEndError::TooLong) => OpResult::new(error_result(
            ERROR_READ,
            format!("the stream is longer than the {limit}-byte limit: {error}"),
        )),
    }
}

/// Suspends until the peer resets the stream, answering its code — or `-1` if it never will.
///
/// `-1` covers both endings that make a reset meaningless afterwards: this side stopped the stream, or
/// the peer finished it and everything arrived.
async fn recv_received_reset(slot: Option<StreamShared>) -> OpResult {
    let Some(slot) = slot else {
        return OpResult::new(released());
    };
    let Some(recv) = slot.recv.as_ref() else {
        return OpResult::new(wrong_half(RECEIVE));
    };
    let mut recv = recv.lock().await;
    match recv.received_reset().await {
        Ok(code) => OpResult::new(optional_code(code)),
        Err(error) => OpResult::new(reset_failure(&error)),
    }
}

/// Why waiting for the peer to reset the stream failed. The read-side mirror of
/// [`stopped_failure`], including the exhaustive-match note: `ResetError` has exactly these two
/// variants upstream, so there is no catch-all arm here either.
fn reset_failure(error: &ResetError) -> *mut Iroh4kResult {
    let code = match error {
        ResetError::ConnectionLost(_) => ERROR_CLOSED,
        ResetError::ZeroRttRejected => ERROR_ZERO_RTT_REJECTED,
    };
    error_result(
        code,
        format!("could not wait for the peer to reset the stream: {error}"),
    )
}

/// Stops accepting data, discarding what was unread and telling the peer to stop sending.
fn recv_stop(slot: &StreamSlot, error_code: i64) -> *mut Iroh4kResult {
    let Some(recv) = slot.recv.as_ref() else {
        return wrong_half(RECEIVE);
    };
    let code = match varint(error_code, "a stream stop code") {
        Ok(code) => code,
        Err(failure) => return failure,
    };
    let Ok(mut recv) = recv.try_lock() else {
        return busy(ERROR_READ, RECEIVE);
    };
    match in_runtime(|| recv.stop(code)) {
        Ok(()) => ok_result(),
        Err(_) => closed_stream("stopping"),
    }
}

/// How many bytes of this stream the application has consumed.
fn recv_bytes_read(slot: &StreamSlot) -> *mut Iroh4kResult {
    let Some(recv) = slot.recv.as_ref() else {
        return wrong_half(RECEIVE);
    };
    let Ok(recv) = recv.try_lock() else {
        return busy(ERROR_READ, RECEIVE);
    };
    match recv.bytes_read() {
        Ok(read) => i64_result(i64::try_from(read).unwrap_or(i64::MAX)),
        Err(_) => closed_stream("reading the byte count"),
    }
}

fn recv_id(slot: &StreamSlot) -> *mut Iroh4kResult {
    let Some(recv) = slot.recv.as_ref() else {
        return wrong_half(RECEIVE);
    };
    let Ok(recv) = recv.try_lock() else {
        return busy(ERROR_READ, RECEIVE);
    };
    i64_result(stream_id(u64::from(recv.id())))
}

/// Whether this stream was accepted while the handshake was still in progress.
///
/// `noq` decides this once, at the moment the stream is created (`open_bi`/`open_uni` on the
/// dialling side) or accepted (`accept_bi`/`accept_uni` on the accepting one), and `RecvStream`
/// just remembers the bit — see `is_0rtt` on `noq-1.1.0`'s `RecvStream`/`UnorderedRecvStream`. A
/// `true` here is the caller's signal that whatever this stream carries arrived before the
/// handshake vouched for the peer's identity, and is therefore replayable.
fn recv_is_0rtt(slot: &StreamSlot) -> *mut Iroh4kResult {
    let Some(recv) = slot.recv.as_ref() else {
        return wrong_half(RECEIVE);
    };
    let Ok(recv) = recv.try_lock() else {
        return busy(ERROR_READ, RECEIVE);
    };
    i64_result(i64::from(recv.is_0rtt()))
}

// ============================================================================
// Small shared pieces
// ============================================================================

/// What `i64_val` says when a read found the end of the stream. Never a real byte count.
const END_OF_STREAM: i64 = -1;

/// A QUIC stream id as the `i64` the boundary carries.
///
/// A stream id is a 62-bit varint, so it always fits; the saturation is there because the type says
/// `u64` and an unrepresentable id must not become a negative one.
fn stream_id(id: u64) -> i64 {
    i64::try_from(id).unwrap_or(i64::MAX)
}

/// The result for a synchronous operation that could not take its half's lock.
///
/// Only reachable while an asynchronous operation is in flight on the same half — a `finish` racing a
/// `writeAll`, say. Reported rather than waited for, because a synchronous FFI call must not block a
/// Kotlin thread on a QUIC round trip.
///
/// `code` is the direction's own — [`ERROR_WRITE`] for the send half, [`ERROR_READ`] for the receive
/// one. Neither is quite what happened, but there is no "busy" code in the shared taxonomy and the
/// alternative is to report the wrong direction, which tells a caller less.
fn busy(code: c_int, half: &str) -> *mut Iroh4kResult {
    error_result(
        code,
        format!(
            "another operation is in flight on this stream's {half} half, so this one could not be \
             applied; wait for it to finish and try again"
        ),
    )
}

// ============================================================================
// C ABI — Kotlin/Native via cinterop
// ============================================================================

/// Stream handles still alive. Test hook for asserting handles do not leak.
#[unsafe(no_mangle)]
pub extern "C" fn iroh4k_stream_live_handle_count() -> i64 {
    LIVE_HANDLES.load(Ordering::Relaxed)
}

/// Releases a stream handle.
///
/// Dropping the last one drops the halves it held, which is what tells the peer the stream is over —
/// a send half abandons anything unsent, a receive half stops the peer sending more. Also used as the
/// `HandleDrop` for this type, so a cancelled open releases the stream it had already produced.
///
/// Tolerates null so Kotlin's `close()` can be idempotent.
///
/// # Safety
/// `handle` must be null, or a stream handle from this module that has not been freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_stream_free(handle: *mut c_void) {
    unsafe {
        handle::free::<StreamHandle>(handle);
    }
}

/// A second handle to the same stream payload, for the two halves of a bidirectional stream.
///
/// An `Arc` strong-count bump and nothing else, which is why it cannot fail and returns the handle
/// directly rather than an [`Iroh4kResult`]. `0` for a null input.
///
/// # Safety
/// As [`iroh4k_stream_free`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_stream_share(handle: *mut c_void) -> *mut c_void {
    unsafe {
        match share::<StreamSlot>(handle) {
            Some(shared) => handle::arc_into_handle(shared),
            None => std::ptr::null_mut(),
        }
    }
}

// ----------------------------------------------------------------------------
// The send half — synchronous, because these are synchronous in iroh
// ----------------------------------------------------------------------------

/// Tells the peer nothing more will be written to this stream.
///
/// # Safety
/// `handle` must satisfy `connection::peek`'s contract for a stream handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_send_stream_finish(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<StreamSlot>(handle, |slot| send_finish(slot)) }
}

/// Abandons this stream with `error_code`.
///
/// # Safety
/// As [`iroh4k_send_stream_finish`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_send_stream_reset(
    handle: *mut c_void,
    error_code: i64,
) -> *mut Iroh4kResult {
    unsafe { with::<StreamSlot>(handle, |slot| send_reset(slot, error_code)) }
}

/// Sets this stream's transmission priority.
///
/// # Safety
/// As [`iroh4k_send_stream_finish`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_send_stream_set_priority(
    handle: *mut c_void,
    priority: i32,
) -> *mut Iroh4kResult {
    unsafe { with::<StreamSlot>(handle, |slot| send_set_priority(slot, priority)) }
}

/// This stream's transmission priority, in `i64_val`.
///
/// # Safety
/// As [`iroh4k_send_stream_finish`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_send_stream_priority(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<StreamSlot>(handle, |slot| send_priority(slot)) }
}

/// This stream's QUIC id, in `i64_val`.
///
/// # Safety
/// As [`iroh4k_send_stream_finish`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_send_stream_id(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<StreamSlot>(handle, |slot| send_id(slot)) }
}

// ----------------------------------------------------------------------------
// The receive half — synchronous
// ----------------------------------------------------------------------------

/// Stops accepting data on this stream, telling the peer `error_code`.
///
/// # Safety
/// As [`iroh4k_send_stream_finish`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_recv_stream_stop(
    handle: *mut c_void,
    error_code: i64,
) -> *mut Iroh4kResult {
    unsafe { with::<StreamSlot>(handle, |slot| recv_stop(slot, error_code)) }
}

/// How many bytes have been consumed from this stream, in `i64_val`.
///
/// # Safety
/// As [`iroh4k_send_stream_finish`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_recv_stream_bytes_read(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<StreamSlot>(handle, |slot| recv_bytes_read(slot)) }
}

/// Whether this stream was accepted while the handshake was still in progress, `0`/`1` in
/// `i64_val`. See [`recv_is_0rtt`].
///
/// # Safety
/// As [`iroh4k_send_stream_finish`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_recv_stream_is_0rtt(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<StreamSlot>(handle, |slot| recv_is_0rtt(slot)) }
}

/// This stream's QUIC id, in `i64_val`.
///
/// # Safety
/// As [`iroh4k_send_stream_finish`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_recv_stream_id(handle: *mut c_void) -> *mut Iroh4kResult {
    unsafe { with::<StreamSlot>(handle, |slot| recv_id(slot)) }
}

// ----------------------------------------------------------------------------
// Asynchronous
// ----------------------------------------------------------------------------

/// Opens a bidirectional stream; the result carries a stream handle. Asynchronous.
///
/// # Safety
/// `connection` must be null, or a live connection handle of any of the three kinds — completed or
/// either 0-RTT — from `connection.rs` that has not been freed, satisfying
/// [`crate::connection::connection_clone_any`]'s contract. Kotlin's guard on the corresponding handle
/// type guarantees the second part.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_open_bi(
    connection: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let connection = connection_clone_any(connection);
        ops::spawn_callback(callback, fun, open_bi(connection))
    }
}

/// Accepts the peer's next bidirectional stream. Asynchronous, and waits indefinitely.
///
/// # Safety
/// As [`iroh4k_connection_open_bi`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_accept_bi(
    connection: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let connection = connection_clone_any(connection);
        ops::spawn_callback(callback, fun, accept_bi(connection))
    }
}

/// Opens a unidirectional stream. Asynchronous.
///
/// # Safety
/// As [`iroh4k_connection_open_bi`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_open_uni(
    connection: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let connection = connection_clone_any(connection);
        ops::spawn_callback(callback, fun, open_uni(connection))
    }
}

/// Accepts the peer's next unidirectional stream. Asynchronous, and waits indefinitely.
///
/// # Safety
/// As [`iroh4k_connection_open_bi`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_connection_accept_uni(
    connection: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let connection = connection_clone_any(connection);
        ops::spawn_callback(callback, fun, accept_uni(connection))
    }
}

/// Writes as much of the payload as fits now; the count lands in `i64_val`. Asynchronous.
///
/// # Safety
/// `handle` must satisfy `connection::share`'s contract for a stream handle. `payload`/`payload_len`
/// must be null/0 or describe that many readable bytes; the buffer is **copied** before the future is
/// spawned, so the caller may free it as soon as this returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_send_stream_write(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<StreamSlot>(handle);
        let payload = owned_bytes(payload, payload_len);
        ops::spawn_callback(callback, fun, send_write(slot, payload))
    }
}

/// Writes the whole payload, however long that takes. Asynchronous.
///
/// # Safety
/// As [`iroh4k_send_stream_write`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_send_stream_write_all(
    handle: *mut c_void,
    payload: *const u8,
    payload_len: c_int,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<StreamSlot>(handle);
        let payload = owned_bytes(payload, payload_len);
        ops::spawn_callback(callback, fun, send_write_all(slot, payload))
    }
}

/// Suspends until the peer stops or fully receives the stream; the code lands in `i64_val`, `-1` for
/// a clean receipt. Asynchronous.
///
/// # Safety
/// `handle` must satisfy `connection::share`'s contract for a stream handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_send_stream_stopped(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<StreamSlot>(handle);
        ops::spawn_callback(callback, fun, send_stopped(slot))
    }
}

/// Reads up to `size_limit` bytes; `-1` in `i64_val` and no payload at the end of the stream.
/// Asynchronous.
///
/// # Safety
/// As [`iroh4k_send_stream_stopped`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_recv_stream_read(
    handle: *mut c_void,
    size_limit: i64,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<StreamSlot>(handle);
        ops::spawn_callback(callback, fun, recv_read(slot, size_limit))
    }
}

/// Reads exactly `size` bytes, or fails. Asynchronous.
///
/// # Safety
/// As [`iroh4k_send_stream_stopped`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_recv_stream_read_exact(
    handle: *mut c_void,
    size: i64,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<StreamSlot>(handle);
        ops::spawn_callback(callback, fun, recv_read_exact(slot, size))
    }
}

/// Reads to the end of the stream, up to `size_limit` bytes. Asynchronous.
///
/// # Safety
/// As [`iroh4k_send_stream_stopped`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_recv_stream_read_to_end(
    handle: *mut c_void,
    size_limit: i64,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<StreamSlot>(handle);
        ops::spawn_callback(callback, fun, recv_read_to_end(slot, size_limit))
    }
}

/// Suspends until the peer resets the stream; the code lands in `i64_val`, `-1` if it never will.
/// Asynchronous.
///
/// # Safety
/// As [`iroh4k_send_stream_stopped`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn iroh4k_recv_stream_received_reset(
    handle: *mut c_void,
    callback: *mut c_void,
    fun: Completion,
) -> i64 {
    unsafe {
        let slot = share::<StreamSlot>(handle);
        ops::spawn_callback(callback, fun, recv_received_reset(slot))
    }
}

// ============================================================================
// JNI — JVM and Android via the shared library
//
// Unavailable on iOS, which uses the FFI/cinterop path exclusively. Symbol names match the Kotlin
// object `tech.annexflow.iroh4k.StreamJni`. Handles travel as `jlong`, and each `*Start` export copies
// its arguments out of the JVM before spawning, then returns an operation id for
// `Iroh4kJni.opAwait`/`opCancel`.
// ============================================================================

#[cfg(not(target_os = "ios"))]
#[allow(non_snake_case)]
mod jni_facade {
    use super::*;
    use jni::EnvUnowned;
    use jni::objects::{JByteArray, JClass};
    use jni::sys::{jbyteArray, jint, jlong};

    use crate::jni::finish;

    /// Reinterprets a `jlong` handle as the pointer it came from.
    ///
    /// # Safety
    /// As `connection.rs`'s equivalent: `handle` must be `0`, or a live handle Kotlin's guard has not
    /// released.
    unsafe fn as_handle(handle: jlong) -> *mut c_void {
        handle as usize as *mut c_void
    }

    /// Copies a Java `byte[]` into an owned `Vec`. An unreadable array becomes empty, which is then
    /// reported like any other malformed argument — the FFI boundary must never unwind.
    fn arg(env: &mut EnvUnowned, array: &JByteArray) -> Vec<u8> {
        crate::jni::with_env(env, |env| env.convert_byte_array(array)).unwrap_or_default()
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_liveHandleCount(
        _env: EnvUnowned,
        _class: JClass,
    ) -> jlong {
        LIVE_HANDLES.load(Ordering::Relaxed)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_free(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { handle::free::<StreamHandle>(as_handle(handle)) };
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_share(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let shared = unsafe { share::<StreamSlot>(as_handle(handle)) };
        match shared {
            Some(shared) => handle::arc_into_handle(shared) as usize as jlong,
            None => 0,
        }
    }

    // ── The send half — synchronous ───────────────────────────────────────────────────────────

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_sendFinish(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe { with::<StreamSlot>(as_handle(handle), |slot| send_finish(slot)) };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_sendReset(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        error_code: jlong,
    ) -> jbyteArray {
        let result =
            unsafe { with::<StreamSlot>(as_handle(handle), |slot| send_reset(slot, error_code)) };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_sendSetPriority(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        priority: jint,
    ) -> jbyteArray {
        let result = unsafe {
            with::<StreamSlot>(as_handle(handle), |slot| send_set_priority(slot, priority))
        };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_sendPriority(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe { with::<StreamSlot>(as_handle(handle), |slot| send_priority(slot)) };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_sendId(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe { with::<StreamSlot>(as_handle(handle), |slot| send_id(slot)) };
        finish(&mut env, result)
    }

    // ── The receive half — synchronous ────────────────────────────────────────────────────────

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_recvStop(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        error_code: jlong,
    ) -> jbyteArray {
        let result =
            unsafe { with::<StreamSlot>(as_handle(handle), |slot| recv_stop(slot, error_code)) };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_recvBytesRead(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe { with::<StreamSlot>(as_handle(handle), |slot| recv_bytes_read(slot)) };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_recvIs0Rtt(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe { with::<StreamSlot>(as_handle(handle), |slot| recv_is_0rtt(slot)) };
        finish(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_recvId(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let result = unsafe { with::<StreamSlot>(as_handle(handle), |slot| recv_id(slot)) };
        finish(&mut env, result)
    }

    // ── Asynchronous ──────────────────────────────────────────────────────────────────────────

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_openBiStart(
        _env: EnvUnowned,
        _class: JClass,
        connection: jlong,
    ) -> jlong {
        let connection = unsafe { connection_clone_any(as_handle(connection)) };
        ops::spawn_channel(open_bi(connection))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_acceptBiStart(
        _env: EnvUnowned,
        _class: JClass,
        connection: jlong,
    ) -> jlong {
        let connection = unsafe { connection_clone_any(as_handle(connection)) };
        ops::spawn_channel(accept_bi(connection))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_openUniStart(
        _env: EnvUnowned,
        _class: JClass,
        connection: jlong,
    ) -> jlong {
        let connection = unsafe { connection_clone_any(as_handle(connection)) };
        ops::spawn_channel(open_uni(connection))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_acceptUniStart(
        _env: EnvUnowned,
        _class: JClass,
        connection: jlong,
    ) -> jlong {
        let connection = unsafe { connection_clone_any(as_handle(connection)) };
        ops::spawn_channel(accept_uni(connection))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_sendWriteStart(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        payload: JByteArray,
    ) -> jlong {
        let slot = unsafe { share::<StreamSlot>(as_handle(handle)) };
        let payload = arg(&mut env, &payload);
        ops::spawn_channel(send_write(slot, payload))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_sendWriteAllStart(
        mut env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        payload: JByteArray,
    ) -> jlong {
        let slot = unsafe { share::<StreamSlot>(as_handle(handle)) };
        let payload = arg(&mut env, &payload);
        ops::spawn_channel(send_write_all(slot, payload))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_sendStoppedStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { share::<StreamSlot>(as_handle(handle)) };
        ops::spawn_channel(send_stopped(slot))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_recvReadStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        size_limit: jlong,
    ) -> jlong {
        let slot = unsafe { share::<StreamSlot>(as_handle(handle)) };
        ops::spawn_channel(recv_read(slot, size_limit))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_recvReadExactStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        size: jlong,
    ) -> jlong {
        let slot = unsafe { share::<StreamSlot>(as_handle(handle)) };
        ops::spawn_channel(recv_read_exact(slot, size))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_recvReadToEndStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
        size_limit: jlong,
    ) -> jlong {
        let slot = unsafe { share::<StreamSlot>(as_handle(handle)) };
        ops::spawn_channel(recv_read_to_end(slot, size_limit))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_tech_annexflow_iroh4k_StreamJni_recvReceivedResetStart(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) -> jlong {
        let slot = unsafe { share::<StreamSlot>(as_handle(handle)) };
        ops::spawn_channel(recv_received_reset(slot))
    }
}

// ============================================================================
// Tests
//
// See the module header's note by `ERROR_ZERO_RTT_REJECTED` for why this exists: the four
// `ZeroRttRejected` arms cannot be pinned by a live connection deterministically, so they are
// pinned here instead, calling each mapper directly with a hand-built error value.
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    /// Reads a result's error code and frees it, so a test cannot leak the allocation
    /// `error_result` leaks by design — Kotlin ordinarily frees it through `free_result` once it
    /// has read the fields it needs, and a test is the one caller on the Rust side that must do
    /// the same rather than just dropping the pointer.
    fn error_code_of(result: *mut Iroh4kResult) -> c_int {
        let code = unsafe { (*result).error };
        crate::core::free_result(result);
        code
    }

    #[test]
    fn write_failure_maps_zero_rtt_rejected() {
        let result = write_failure(&WriteError::ZeroRttRejected);
        assert_eq!(error_code_of(result), ERROR_ZERO_RTT_REJECTED);
    }

    #[test]
    fn read_failure_maps_zero_rtt_rejected() {
        let result = read_failure(&ReadError::ZeroRttRejected);
        assert_eq!(error_code_of(result), ERROR_ZERO_RTT_REJECTED);
    }

    #[test]
    fn stopped_failure_maps_zero_rtt_rejected() {
        let result = stopped_failure(&StoppedError::ZeroRttRejected);
        assert_eq!(error_code_of(result), ERROR_ZERO_RTT_REJECTED);
    }

    #[test]
    fn reset_failure_maps_zero_rtt_rejected() {
        let result = reset_failure(&ResetError::ZeroRttRejected);
        assert_eq!(error_code_of(result), ERROR_ZERO_RTT_REJECTED);
    }
}
