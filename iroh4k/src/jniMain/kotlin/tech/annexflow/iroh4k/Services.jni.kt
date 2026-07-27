package tech.annexflow.iroh4k

/**
 * Raw JNI entry points for the services domain.
 *
 * A separate object from [Iroh4kJni] so this domain owns its own symbols, beside the Rust exports in
 * `services.rs` that implement them. The symbol names are part of the ABI: they must match the
 * `Java_tech_annexflow_iroh4k_ServicesJni_*` exports there.
 *
 * All but two of these are **start / await / cancel** as [Iroh4kJni] describes: every services
 * operation talks to a remote, so a single blocking call would pin a `Dispatchers.IO` thread with
 * nothing able to interrupt it — and [netDiagnosticsStart] can legitimately take more than twenty
 * seconds. The exceptions are the handle lifecycle and [capabilityNames], which reads a compile-time
 * property of the linked library.
 *
 * Handles cross as `Long`, which is the only pointer-sized integer the JVM has. The endpoint handle
 * crosses the same way: `buildStart` takes it as a second `Long` and Rust clones the `iroh::Endpoint`
 * out of it before spawning.
 */
internal object ServicesJni {
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

    external fun capabilityNames(): ByteArray

    // ── Asynchronous — each returns an operation id for Iroh4kJni.opAwait/opCancel ─────────────

    external fun buildStart(handle: Long, endpoint: Long, payload: ByteArray): Long

    external fun nameStart(handle: Long): Long

    external fun setNameStart(handle: Long, name: ByteArray): Long

    external fun pingStart(handle: Long): Long

    external fun pushMetricsStart(handle: Long): Long

    external fun grantCapabilityStart(handle: Long, payload: ByteArray): Long

    external fun netDiagnosticsStart(handle: Long, send: Boolean): Long
}

// ── Handle lifecycle ──────────────────────────────────────────────────────────────────────────

/**
 * Allocates an unbuilt services-client slot in Rust.
 *
 * `into_handle` cannot fail, so a `0` here would mean the loaded library is not the one this was
 * compiled against; it is reported rather than passed on as a handle every later call would reject.
 */
internal actual fun nativeServicesNew(): Long =
    ServicesJni.newHandle().also {
        if (it == 0L) {
            IrohError(
                IrohError.Code.Services,
                "the native library returned no services client handle",
            ).raise()
        }
    }

internal actual fun nativeServicesFree(handle: Long) = ServicesJni.freeHandle(handle)

internal actual fun nativeServicesLiveHandleCount(): Long = ServicesJni.liveHandleCount()

// ── Synchronous ───────────────────────────────────────────────────────────────────────────────

internal actual fun nativeServicesCapabilityNames(): ByteArray =
    ServicesJni.capabilityNames().jniBytesOrThrow()

// ── Asynchronous ──────────────────────────────────────────────────────────────────────────────

internal actual suspend fun nativeServicesBuild(handle: Long, endpoint: Long, payload: ByteArray) =
    jniOp({ ServicesJni.buildStart(handle, endpoint, payload) }) { }

internal actual suspend fun nativeServicesName(handle: Long): ByteArray =
    jniOp({ ServicesJni.nameStart(handle) }) { it.payload() }

internal actual suspend fun nativeServicesSetName(handle: Long, name: ByteArray) =
    jniOp({ ServicesJni.setNameStart(handle, name) }) { }

internal actual suspend fun nativeServicesPing(handle: Long): ByteArray =
    jniOp({ ServicesJni.pingStart(handle) }) { it.payload() }

internal actual suspend fun nativeServicesPushMetrics(handle: Long) =
    jniOp({ ServicesJni.pushMetricsStart(handle) }) { }

internal actual suspend fun nativeServicesGrantCapability(handle: Long, payload: ByteArray) =
    jniOp({ ServicesJni.grantCapabilityStart(handle, payload) }) { }

internal actual suspend fun nativeServicesNetDiagnostics(handle: Long, send: Boolean): ByteArray =
    jniOp({ ServicesJni.netDiagnosticsStart(handle, send) }) { it.payload() }
