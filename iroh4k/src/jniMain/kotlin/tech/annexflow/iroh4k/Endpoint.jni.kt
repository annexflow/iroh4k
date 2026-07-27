package tech.annexflow.iroh4k

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Raw JNI entry points for the endpoint domain.
 *
 * A separate object from [Iroh4kJni] so this domain owns its own symbols, beside the Rust exports in
 * `endpoint.rs` that implement them. The symbol names are part of the ABI: they must match the
 * `Java_tech_annexflow_iroh4k_EndpointJni_*` exports there.
 *
 * Unlike the value domains this one is a mix. The getters are synchronous — reading an id or a
 * metric snapshot is a memory read, and no `Dispatchers.IO` hop is warranted for it. Everything
 * that touches the network is split into **start / await / cancel** as [Iroh4kJni] describes,
 * because a single blocking call would pin a `Dispatchers.IO` thread with nothing able to interrupt
 * it, and [onlineStart] in particular may never finish on its own.
 *
 * Handles cross as `Long`, which is the only pointer-sized integer the JVM has.
 */
internal object EndpointJni {
    init {
        // Each JNI object must ensure the shared library is loaded before its first `external`
        // call; the loader itself is idempotent, so objects do not depend on each other's order.
        ensureNativeLoaded()
    }

    // ── Handle lifecycle ─────────────────────────────────────────────────────────────────────

    external fun newHandle(): Long

    external fun freeHandle(handle: Long)

    external fun liveHandleCount(): Long

    // ── Synchronous ──────────────────────────────────────────────────────────────────────────

    external fun id(handle: Long): ByteArray

    external fun addr(handle: Long): ByteArray

    external fun secretKey(handle: Long): ByteArray

    external fun setAlpns(handle: Long, payload: ByteArray): ByteArray

    external fun boundSockets(handle: Long): ByteArray

    external fun isClosed(handle: Long): ByteArray

    external fun stats(handle: Long): ByteArray

    // ── Asynchronous — each returns an operation id for Iroh4kJni.opAwait/opCancel ─────────────

    external fun bindStart(handle: Long, payload: ByteArray): Long

    external fun shutdownStart(handle: Long): Long

    external fun onlineStart(handle: Long): Long

    external fun remoteAddrStart(handle: Long, id: ByteArray): Long

    external fun addExternalAddrStart(handle: Long, addr: String): Long

    external fun removeExternalAddrStart(handle: Long, addr: String): Long

    external fun insertRelayStart(handle: Long, payload: ByteArray): Long

    external fun removeRelayStart(handle: Long, url: String): Long

    external fun networkChangeStart(handle: Long): Long
}

// ── Handle lifecycle ──────────────────────────────────────────────────────────────────────────

/**
 * Allocates an unbound endpoint slot in Rust.
 *
 * `into_handle` cannot fail, so a `0` here would mean the loaded library is not the one this was
 * compiled against; it is reported rather than passed on as a handle every later call would reject.
 */
internal actual fun nativeEndpointNew(): Long =
    EndpointJni.newHandle().also {
        if (it == 0L) {
            IrohError(IrohError.Code.Bind, "the native library returned no endpoint handle").raise()
        }
    }

internal actual fun nativeEndpointFree(handle: Long) = EndpointJni.freeHandle(handle)

internal actual fun nativeEndpointLiveHandleCount(): Long = EndpointJni.liveHandleCount()

// ── Synchronous ───────────────────────────────────────────────────────────────────────────────

internal actual fun nativeEndpointId(handle: Long): ByteArray =
    EndpointJni.id(handle).jniBytesOrThrow()

internal actual fun nativeEndpointAddr(handle: Long): ByteArray =
    EndpointJni.addr(handle).jniBytesOrThrow()

internal actual fun nativeEndpointSecretKey(handle: Long): ByteArray =
    EndpointJni.secretKey(handle).jniBytesOrThrow()

internal actual fun nativeEndpointSetAlpns(handle: Long, payload: ByteArray) =
    EndpointJni.setAlpns(handle, payload).jniOrThrow()

internal actual fun nativeEndpointBoundSockets(handle: Long): ByteArray =
    EndpointJni.boundSockets(handle).jniBytesOrThrow()

internal actual fun nativeEndpointIsClosed(handle: Long): Boolean =
    EndpointJni.isClosed(handle).jniLongOrThrow() != 0L

internal actual fun nativeEndpointStats(handle: Long): ByteArray =
    EndpointJni.stats(handle).jniBytesOrThrow()

// ── Asynchronous ──────────────────────────────────────────────────────────────────────────────

internal actual suspend fun nativeEndpointBind(handle: Long, payload: ByteArray) =
    jniOp({ EndpointJni.bindStart(handle, payload) }) { }

internal actual suspend fun nativeEndpointShutdown(handle: Long) =
    jniOp({ EndpointJni.shutdownStart(handle) }) { }

internal actual suspend fun nativeEndpointOnline(handle: Long) =
    jniOp({ EndpointJni.onlineStart(handle) }) { }

internal actual suspend fun nativeEndpointRemoteAddr(handle: Long, id: ByteArray): ByteArray =
    jniOp({ EndpointJni.remoteAddrStart(handle, id) }) { it.payload() }

internal actual suspend fun nativeEndpointAddExternalAddr(handle: Long, addr: String) =
    jniOp({ EndpointJni.addExternalAddrStart(handle, addr) }) { }

internal actual suspend fun nativeEndpointRemoveExternalAddr(handle: Long, addr: String): Boolean =
    jniOp({ EndpointJni.removeExternalAddrStart(handle, addr) }) { it.longValue != 0L }

internal actual suspend fun nativeEndpointInsertRelay(handle: Long, payload: ByteArray): ByteArray =
    jniOp({ EndpointJni.insertRelayStart(handle, payload) }) { it.payload() }

internal actual suspend fun nativeEndpointRemoveRelay(handle: Long, url: String): ByteArray =
    jniOp({ EndpointJni.removeRelayStart(handle, url) }) { it.payload() }

internal actual suspend fun nativeEndpointNetworkChange(handle: Long) =
    jniOp({ EndpointJni.networkChangeStart(handle) }) { }
