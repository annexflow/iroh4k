package tech.annexflow.iroh4k.internal

/**
 * Big-endian encoder, the mirror of the Rust `codec::Writer` and the counterpart to
 * [BinaryReader].
 *
 * Four domains build payloads to send *into* Rust (addressing, endpoint, services, connection).
 * This is one implementation for all of them: the wire layout is a contract with `codec.rs`, and
 * per-domain copies of the encoder are how the two ends quietly stop agreeing.
 *
 * Grows geometrically rather than per-write, so encoding an address set or an ALPN list is a
 * handful of allocations rather than one per field.
 */
internal class BinaryWriter {
    private var buf = ByteArray(64)
    private var size = 0

    private fun reserve(extra: Int) {
        if (size + extra <= buf.size) return
        var capacity = buf.size
        while (capacity < size + extra) capacity *= 2
        buf = buf.copyOf(capacity)
    }

    fun u8(value: Int) {
        reserve(1)
        buf[size++] = value.toByte()
    }

    fun bool(value: Boolean) = u8(if (value) 1 else 0)

    fun i32(value: Int) {
        reserve(4)
        for (shift in 24 downTo 0 step 8) buf[size++] = (value shr shift).toByte()
    }

    fun i64(value: Long) {
        reserve(8)
        for (shift in 56 downTo 0 step 8) buf[size++] = (value shr shift).toByte()
    }

    fun f64(value: Double) = i64(value.toRawBits())

    /** `i32 len` followed by the bytes. */
    fun bytes(value: ByteArray) {
        i32(value.size)
        reserve(value.size)
        value.copyInto(buf, size)
        size += value.size
    }

    fun string(value: String) = bytes(value.encodeToByteArray())

    /** `i32 -1` for absent, otherwise a length-prefixed value — what `Reader::opt_str` expects. */
    fun optString(value: String?) {
        if (value == null) i32(-1) else string(value)
    }

    fun finish(): ByteArray = buf.copyOf(size)
}
