@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.iroh4k_keys_endpoint_id_fmt_short
import iroh4k.ffi.iroh4k_keys_endpoint_id_from_bytes
import iroh4k.ffi.iroh4k_keys_endpoint_id_from_string
import iroh4k.ffi.iroh4k_keys_endpoint_id_from_z32
import iroh4k.ffi.iroh4k_keys_endpoint_id_to_string
import iroh4k.ffi.iroh4k_keys_endpoint_id_to_z32
import iroh4k.ffi.iroh4k_keys_endpoint_id_verify
import iroh4k.ffi.iroh4k_keys_secret_from_bytes
import iroh4k.ffi.iroh4k_keys_secret_generate
import iroh4k.ffi.iroh4k_keys_secret_public
import iroh4k.ffi.iroh4k_keys_secret_sign
import iroh4k.ffi.iroh4k_keys_signature_from_bytes
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

/**
 * Pins the array and lends Rust a pointer to it plus its length, for the duration of [block].
 *
 * Pinning rather than copying is safe because every keys export is synchronous: it has returned by
 * the time [block] does, and it copies anything it keeps into its result. That is the opposite of
 * the async operations, which is why `core::owned_bytes` copies and `keys::borrowed` does not.
 *
 * An empty array is lent as `null`/0 — `Pinned.addressOf(0)` is out of bounds for one — and Rust's
 * `borrowed` maps that back to an empty slice. Signing or verifying an empty message therefore
 * works rather than crashing, which is exactly the case a length-only guard would miss.
 */
private inline fun <T> ByteArray.usePtr(block: (CPointer<UByteVar>?, Int) -> T): T =
    if (isEmpty()) {
        block(null, 0)
    } else {
        usePinned { block(it.addressOf(0).reinterpret<UByteVar>(), size) }
    }

internal actual fun nativeKeysSecretGenerate(): ByteArray =
    iroh4k_keys_secret_generate().bytesOrThrow()

internal actual fun nativeKeysSecretFromBytes(secret: ByteArray): ByteArray =
    secret.usePtr { ptr, len -> iroh4k_keys_secret_from_bytes(ptr, len) }.bytesOrThrow()

internal actual fun nativeKeysSecretPublic(secret: ByteArray): ByteArray =
    secret.usePtr { ptr, len -> iroh4k_keys_secret_public(ptr, len) }.bytesOrThrow()

internal actual fun nativeKeysSecretSign(secret: ByteArray, message: ByteArray): ByteArray =
    secret.usePtr { keyPtr, keyLen ->
        message.usePtr { msgPtr, msgLen ->
            iroh4k_keys_secret_sign(keyPtr, keyLen, msgPtr, msgLen)
        }
    }.bytesOrThrow()

internal actual fun nativeKeysEndpointIdFromBytes(id: ByteArray): ByteArray =
    id.usePtr { ptr, len -> iroh4k_keys_endpoint_id_from_bytes(ptr, len) }.bytesOrThrow()

// cinterop maps the `const char *` parameter to `String?` and allocates the nul-terminated UTF-8
// copy in a scope it tears down once the call returns. That is all Rust needs: it parses the
// string and keeps nothing.
internal actual fun nativeKeysEndpointIdFromString(text: String): ByteArray =
    iroh4k_keys_endpoint_id_from_string(text).bytesOrThrow()

internal actual fun nativeKeysEndpointIdToString(id: ByteArray): ByteArray =
    id.usePtr { ptr, len -> iroh4k_keys_endpoint_id_to_string(ptr, len) }.bytesOrThrow()

internal actual fun nativeKeysEndpointIdToZ32(id: ByteArray): ByteArray =
    id.usePtr { ptr, len -> iroh4k_keys_endpoint_id_to_z32(ptr, len) }.bytesOrThrow()

internal actual fun nativeKeysEndpointIdFromZ32(text: String): ByteArray =
    iroh4k_keys_endpoint_id_from_z32(text).bytesOrThrow()

internal actual fun nativeKeysEndpointIdFmtShort(id: ByteArray): ByteArray =
    id.usePtr { ptr, len -> iroh4k_keys_endpoint_id_fmt_short(ptr, len) }.bytesOrThrow()

internal actual fun nativeKeysEndpointIdVerify(
    id: ByteArray,
    message: ByteArray,
    signature: ByteArray,
): Boolean = id.usePtr { idPtr, idLen ->
    message.usePtr { msgPtr, msgLen ->
        signature.usePtr { sigPtr, sigLen ->
            iroh4k_keys_endpoint_id_verify(idPtr, idLen, msgPtr, msgLen, sigPtr, sigLen)
        }
    }
}.longOrThrow() == 1L

internal actual fun nativeKeysSignatureFromBytes(signature: ByteArray): ByteArray =
    signature.usePtr { ptr, len -> iroh4k_keys_signature_from_bytes(ptr, len) }.bytesOrThrow()
