package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import tech.annexflow.iroh4k.internal.BinaryReader

/**
 * Pure-Kotlin codec tests. The encoder lives in Rust (`codec.rs`), so these pin the decoder
 * against hand-written big-endian bytes; the cross-language agreement is covered by the smoke
 * tests that decode real Rust output.
 */
class BinaryReaderTests {

    @Test
    fun `reads big-endian scalars`() {
        val r = BinaryReader(
            byteArrayOf(
                0x7F, // u8 = 127
                0x00, 0x00, 0x01, 0x00, // i32 = 256
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2A, // i64 = 42
            )
        )
        assertThat(r.u8()).isEqualTo(127)
        assertThat(r.i32()).isEqualTo(256)
        assertThat(r.i64()).isEqualTo(42L)
        assertThat(r.remaining).isEqualTo(0)
    }

    @Test
    fun `reads negative i32 and i64`() {
        val r = BinaryReader(
            byteArrayOf(
                -1, -1, -1, -1, // i32 = -1
                -1, -1, -1, -1, -1, -1, -1, -1, // i64 = -1
            )
        )
        assertThat(r.i32()).isEqualTo(-1)
        assertThat(r.i64()).isEqualTo(-1L)
    }

    @Test
    fun `reads f64 via its bit pattern`() {
        val bits = 1.5.toRawBits()
        val bytes = ByteArray(8) { i -> ((bits shr (56 - i * 8)) and 0xFF).toByte() }
        assertThat(BinaryReader(bytes).f64()).isEqualTo(1.5)
    }

    @Test
    fun `reads length-prefixed strings`() {
        val payload = "iroh".encodeToByteArray()
        val r = BinaryReader(byteArrayOf(0, 0, 0, payload.size.toByte()) + payload)
        assertThat(r.string()).isEqualTo("iroh")
    }

    @Test
    fun `decodes a negative length as absent`() {
        val r = BinaryReader(byteArrayOf(-1, -1, -1, -1))
        assertThat(r.optString()).isNull()
    }

    @Test
    fun `reads a counted sequence`() {
        val r = BinaryReader(
            byteArrayOf(
                0, 0, 0, 2, // count = 2
                0, 0, 0, 7, // 7
                0, 0, 0, 9, // 9
            )
        )
        assertThat(r.seq { it.i32() }).isEqualTo(listOf(7, 9))
    }
}
