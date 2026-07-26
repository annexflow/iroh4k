package tech.annexflow.iroh4k

import tech.annexflow.iroh4k.internal.BinaryReader

/**
 * A decoded JNI result envelope — the single decoder for the layout Rust's
 * `core::serialize_result` writes.
 *
 * The native facade reads the `Iroh4kResult` struct fields directly through cinterop; JNI has no
 * struct access, so Rust flattens the same fields into a byte buffer and they are parsed here.
 * The [bytes] payload inside is the shared `commonMain` codec format, decoded by [BinaryReader].
 *
 * Deliberately shared by every domain (`Iroh4k`, `Keys`, `Relay`, …) rather than re-implemented
 * per file. One Rust writer must have exactly one Kotlin reader: a second copy would keep working
 * right up until the envelope gained a field, and then disagree silently on every operation in
 * whichever domains had not been updated.
 */
internal class JniResult(buffer: ByteArray) {
    val error: Int
    val errorMessage: String?
    val handle: Long
    val longValue: Long
    val doubleValue: Double
    val bytes: ByteArray?

    init {
        val r = BinaryReader(buffer)
        error = r.i32()
        errorMessage = r.optString()
        handle = r.i64()
        longValue = r.i64()
        doubleValue = r.f64()
        bytes = r.optBytes()
    }

    /**
     * Raises the Rust-reported failure, if any.
     *
     * @param code the [IrohError.Code] to report when Rust did not supply a more specific one.
     *   Rust always sends a code, so this is only a floor for an unrecognised value.
     */
    fun throwIfError() {
        if (error != IrohError.OK) IrohError(IrohError.Code.of(error), errorMessage).raise()
    }
}

internal fun ByteArray.decodeResult(): JniResult = JniResult(this)

/** Decodes the envelope and raises on failure, discarding any payload. */
internal fun ByteArray.jniOrThrow() {
    decodeResult().throwIfError()
}

/** The codec payload, or an empty array when Rust sent none. */
internal fun ByteArray.jniBytesOrThrow(): ByteArray = decodeResult().let {
    it.throwIfError()
    it.bytes ?: ByteArray(0)
}

/** The `i64_val` field. */
internal fun ByteArray.jniLongOrThrow(): Long = decodeResult().let {
    it.throwIfError()
    it.longValue
}
