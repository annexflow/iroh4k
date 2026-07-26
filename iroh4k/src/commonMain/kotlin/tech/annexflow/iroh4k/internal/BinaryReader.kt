package tech.annexflow.iroh4k.internal

/**
 * Big-endian decoder mirroring the Rust `codec::Writer`.
 *
 * Both facades share this: the FFI path decodes the `bytes` payload of an `Iroh4kResult`, the
 * JNI path decodes the whole result envelope plus the same payload. The layout is defined in
 * `codec.rs` and the two must be changed together.
 */
internal class BinaryReader(private val buf: ByteArray) {
    private var pos = 0

    val remaining: Int get() = buf.size - pos

    private fun require(n: Int) {
        if (remaining < n) {
            error("Malformed codec payload: needed $n more bytes at offset $pos, have $remaining")
        }
    }

    fun u8(): Int {
        require(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun bool(): Boolean = u8() != 0

    fun i32(): Int {
        require(4)
        var v = 0
        repeat(4) { v = (v shl 8) or (buf[pos++].toInt() and 0xFF) }
        return v
    }

    fun i64(): Long {
        require(8)
        var v = 0L
        repeat(8) { v = (v shl 8) or (buf[pos++].toLong() and 0xFF) }
        return v
    }

    fun f64(): Double = Double.fromBits(i64())

    /** `i32 len` followed by `len` bytes. */
    fun bytes(): ByteArray {
        val len = i32()
        require(len)
        return buf.copyOfRange(pos, pos + len).also { pos += len }
    }

    fun string(): String = bytes().decodeToString()

    /** `i32 -1` for absent, otherwise a length-prefixed value. */
    fun optBytes(): ByteArray? {
        val len = i32()
        if (len < 0) return null
        require(len)
        return buf.copyOfRange(pos, pos + len).also { pos += len }
    }

    fun optString(): String? = optBytes()?.decodeToString()

    /** `i32 count`, then [f] per element. */
    fun <T> seq(f: (BinaryReader) -> T): List<T> {
        val count = i32()
        return List(count) { f(this) }
    }
}
