@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.iroh4k_services_build
import iroh4k.ffi.iroh4k_services_capability_names
import iroh4k.ffi.iroh4k_services_free
import iroh4k.ffi.iroh4k_services_grant_capability
import iroh4k.ffi.iroh4k_services_live_handle_count
import iroh4k.ffi.iroh4k_services_name
import iroh4k.ffi.iroh4k_services_net_diagnostics
import iroh4k.ffi.iroh4k_services_new
import iroh4k.ffi.iroh4k_services_ping
import iroh4k.ffi.iroh4k_services_push_metrics
import iroh4k.ffi.iroh4k_services_set_name
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned

/**
 * The services surface over the C ABI.
 *
 * Every operation in this domain is asynchronous apart from the handle lifecycle and the capability
 * vocabulary, so almost everything here goes through `iroh { }` — which pins the continuation, hands
 * Rust the operation id and registers `iroh4k_op_cancel` on cancellation. That last part is what
 * bounds [nativeServicesNetDiagnostics], whose Rust side can legitimately run for more than twenty
 * seconds.
 *
 * Payload buffers are *pinned* for the duration of the FFI call rather than copied here: Rust copies
 * every asynchronous argument out before its future is spawned (see `core::owned_bytes`), so the
 * pinned array only has to survive the call that starts the operation.
 */

/**
 * Reinterprets a handle from `commonMain` as the pointer Rust produced.
 *
 * `0` maps to `null`, which every export answers with `ERROR_CLOSED` rather than dereferencing —
 * unreachable through [ServicesClient], whose handle is non-null from construction, but the boundary
 * does not rely on that.
 */
private fun Long.asHandle(): COpaquePointer? = toCPointer<CPointed>()

/**
 * Pins the payload and lends Rust a pointer to it plus its length, for the duration of [block].
 *
 * An empty array is lent as `null`/0, since `Pinned.addressOf(0)` is out of bounds for one; Rust maps
 * that back to an empty slice, which its payload reader then reports as malformed rather than
 * crashing on.
 */
private inline fun <T> ByteArray.usePtr(block: (CPointer<UByteVar>?, Int) -> T): T =
    if (isEmpty()) {
        block(null, 0)
    } else {
        usePinned { block(it.addressOf(0).reinterpret<UByteVar>(), size) }
    }

// ── Handle lifecycle ──────────────────────────────────────────────────────────────────────────

/**
 * Allocates an unbuilt services-client slot in Rust.
 *
 * `into_handle` cannot fail, so a null here would mean the library is not the one this was compiled
 * against; it is reported rather than passed on as a handle that every later call would reject.
 */
internal actual fun nativeServicesNew(): Long =
    iroh4k_services_new()?.toLong()
        ?: IrohError(
            IrohError.Code.Services,
            "the native library returned no services client handle",
        ).raise()

internal actual fun nativeServicesFree(handle: Long) {
    iroh4k_services_free(handle.asHandle())
}

internal actual fun nativeServicesLiveHandleCount(): Long = iroh4k_services_live_handle_count()

// ── Synchronous ───────────────────────────────────────────────────────────────────────────────

internal actual fun nativeServicesCapabilityNames(): ByteArray =
    iroh4k_services_capability_names().bytesOrThrow()

// ── Asynchronous ──────────────────────────────────────────────────────────────────────────────

internal actual suspend fun nativeServicesBuild(handle: Long, endpoint: Long, payload: ByteArray) {
    iroh { c ->
        payload.usePtr { ptr, len ->
            iroh4k_services_build(handle.asHandle(), endpoint.asHandle(), ptr, len, c, completion)
        }
    }.orThrow()
}

internal actual suspend fun nativeServicesName(handle: Long): ByteArray =
    iroh { c -> iroh4k_services_name(handle.asHandle(), c, completion) }.bytesOrThrow()

internal actual suspend fun nativeServicesSetName(handle: Long, name: ByteArray) {
    iroh { c ->
        name.usePtr { ptr, len -> iroh4k_services_set_name(handle.asHandle(), ptr, len, c, completion) }
    }.orThrow()
}

internal actual suspend fun nativeServicesPing(handle: Long): ByteArray =
    iroh { c -> iroh4k_services_ping(handle.asHandle(), c, completion) }.bytesOrThrow()

internal actual suspend fun nativeServicesPushMetrics(handle: Long) {
    iroh { c -> iroh4k_services_push_metrics(handle.asHandle(), c, completion) }.orThrow()
}

internal actual suspend fun nativeServicesGrantCapability(handle: Long, payload: ByteArray) {
    iroh { c ->
        payload.usePtr { ptr, len ->
            iroh4k_services_grant_capability(handle.asHandle(), ptr, len, c, completion)
        }
    }.orThrow()
}

internal actual suspend fun nativeServicesNetDiagnostics(handle: Long, send: Boolean): ByteArray =
    iroh { c ->
        // `send` crosses as the C `int` the header declares; there is no `bool` in the ABI.
        iroh4k_services_net_diagnostics(handle.asHandle(), if (send) 1 else 0, c, completion)
    }.bytesOrThrow()
