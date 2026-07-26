package tech.annexflow.iroh4k


/**
 * Raw JNI entry points for the keys domain.
 *
 * Every one of them is synchronous — there is no crypto here that blocks, so none of the
 * start/await/cancel machinery `Iroh4kJni` needs applies. The symbol names are part of the ABI:
 * they must match the `Java_tech_annexflow_iroh4k_KeysJni_*` exports in the Rust `keys.rs`.
 */
internal object KeysJni {
    init {
        // Each JNI object loads the library itself: whichever one a caller touches first has to
        // work, and there is no ordering between the domains.
        ensureNativeLoaded()
    }

    external fun secretGenerate(): ByteArray

    external fun secretFromBytes(secret: ByteArray): ByteArray

    external fun secretPublic(secret: ByteArray): ByteArray

    external fun secretSign(secret: ByteArray, message: ByteArray): ByteArray

    external fun endpointIdFromBytes(id: ByteArray): ByteArray

    external fun endpointIdFromString(text: String): ByteArray

    external fun endpointIdToString(id: ByteArray): ByteArray

    external fun endpointIdToZ32(id: ByteArray): ByteArray

    external fun endpointIdFromZ32(text: String): ByteArray

    external fun endpointIdFmtShort(id: ByteArray): ByteArray

    external fun endpointIdVerify(
        id: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): ByteArray

    external fun signatureFromBytes(signature: ByteArray): ByteArray
}


internal actual fun nativeKeysSecretGenerate(): ByteArray =
    KeysJni.secretGenerate().jniBytesOrThrow()

internal actual fun nativeKeysSecretFromBytes(secret: ByteArray): ByteArray =
    KeysJni.secretFromBytes(secret).jniBytesOrThrow()

internal actual fun nativeKeysSecretPublic(secret: ByteArray): ByteArray =
    KeysJni.secretPublic(secret).jniBytesOrThrow()

internal actual fun nativeKeysSecretSign(secret: ByteArray, message: ByteArray): ByteArray =
    KeysJni.secretSign(secret, message).jniBytesOrThrow()

internal actual fun nativeKeysEndpointIdFromBytes(id: ByteArray): ByteArray =
    KeysJni.endpointIdFromBytes(id).jniBytesOrThrow()

internal actual fun nativeKeysEndpointIdFromString(text: String): ByteArray =
    KeysJni.endpointIdFromString(text).jniBytesOrThrow()

internal actual fun nativeKeysEndpointIdToString(id: ByteArray): ByteArray =
    KeysJni.endpointIdToString(id).jniBytesOrThrow()

internal actual fun nativeKeysEndpointIdToZ32(id: ByteArray): ByteArray =
    KeysJni.endpointIdToZ32(id).jniBytesOrThrow()

internal actual fun nativeKeysEndpointIdFromZ32(text: String): ByteArray =
    KeysJni.endpointIdFromZ32(text).jniBytesOrThrow()

internal actual fun nativeKeysEndpointIdFmtShort(id: ByteArray): ByteArray =
    KeysJni.endpointIdFmtShort(id).jniBytesOrThrow()

internal actual fun nativeKeysEndpointIdVerify(
    id: ByteArray,
    message: ByteArray,
    signature: ByteArray,
): Boolean = KeysJni.endpointIdVerify(id, message, signature).jniLongOrThrow() == 1L

internal actual fun nativeKeysSignatureFromBytes(signature: ByteArray): ByteArray =
    KeysJni.signatureFromBytes(signature).jniBytesOrThrow()
