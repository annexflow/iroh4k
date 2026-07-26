package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import kotlin.test.assertFailsWith

/**
 * Test bodies shared by every target, so the FFI (Kotlin/Native) and JNI (JVM/Android) facades are
 * held to identical behaviour for the keys domain.
 *
 * Platform test classes construct this and delegate one `@Test` per method — see
 * `nativeTest`/`jvmTest`.
 */
class CommonKeysTests {

    // ── Generation and byte round-trips ──────────────────────────────────────────────────────

    fun `generate produces distinct secret keys`() {
        val first = SecretKey.generate()
        val second = SecretKey.generate()
        assertThat(first.toBytes()).hasSize(32)
        assertThat(second.toBytes()).hasSize(32)
        // Proves real randomness reached us rather than a zeroed or constant buffer.
        assertThat(first).isNotEqualTo(second)
        assertThat(first.public()).isNotEqualTo(second.public())
        assertThat(first.toBytes().all { it == 0.toByte() }).isFalse()
    }

    fun `secret key round-trips through its bytes`() {
        val original = SecretKey.generate()
        val restored = SecretKey.fromBytes(original.toBytes())
        assertThat(restored).isEqualTo(original)
        // The identity has to survive the round trip too, not just the bytes.
        assertThat(restored.public()).isEqualTo(original.public())
    }

    fun `endpoint id and signature round-trip through their bytes`() {
        val secret = SecretKey.generate()
        val id = secret.public()
        val signature = secret.sign(MESSAGE)

        assertThat(EndpointId.fromBytes(id.toBytes())).isEqualTo(id)
        assertThat(Signature.fromBytes(signature.toBytes())).isEqualTo(signature)
        assertThat(id.toBytes()).hasSize(32)
        assertThat(signature.toBytes()).hasSize(64)
    }

    fun `toBytes hands out a defensive copy`() {
        val secret = SecretKey.generate()
        val id = secret.public()
        val signature = secret.sign(MESSAGE)

        // `ByteArray` is mutable, so an accessor returning the backing array would let a caller
        // corrupt a key that other code is still holding. Scribbling on the result must not.
        secret.toBytes().fill(0)
        id.toBytes().fill(0)
        signature.toBytes().fill(0)

        assertThat(secret.toBytes().all { it == 0.toByte() }).isFalse()
        assertThat(secret.public()).isEqualTo(id)
        assertThat(id.verify(MESSAGE, signature)).isTrue()
    }

    // ── Derivation and signing against independent test vectors ─────────────────────────────

    fun `public key derivation matches the RFC 8032 test vector`() {
        // RFC 8032 section 7.1, TEST 1. An externally specified vector, so this checks the real
        // Ed25519 derivation rather than merely that we agree with ourselves.
        val secret = SecretKey.fromBytes(hex(RFC_SECRET_HEX))
        assertThat(secret.public().toBytes().toList()).isEqualTo(hex(RFC_PUBLIC_HEX).toList())
    }

    fun `public key derivation is deterministic`() {
        val bytes = hex(RFC_SECRET_HEX)
        // Two independent instances, so nothing can be shared or cached between them.
        assertThat(SecretKey.fromBytes(bytes).public())
            .isEqualTo(SecretKey.fromBytes(bytes).public())
        // And repeated derivation from one instance is stable.
        val secret = SecretKey.generate()
        assertThat(secret.public()).isEqualTo(secret.public())
    }

    fun `signing an empty message matches the RFC 8032 test vector`() {
        // RFC 8032 TEST 1 signs a zero-length message, which also exercises the empty-buffer path
        // across the boundary: an empty `ByteArray` is passed as a null pointer with length 0.
        val secret = SecretKey.fromBytes(hex(RFC_SECRET_HEX))
        val signature = secret.sign(ByteArray(0))
        assertThat(signature.toBytes().toList()).isEqualTo(hex(RFC_SIGNATURE_HEX).toList())
        assertThat(secret.public().verify(ByteArray(0), signature)).isTrue()
    }

    // ── Verification ────────────────────────────────────────────────────────────────────────

    fun `sign and verify round-trip`() {
        val secret = SecretKey.generate()
        val id = secret.public()
        assertThat(id.verify(MESSAGE, secret.sign(MESSAGE))).isTrue()
        // A long message, to be sure nothing truncates at a convenient boundary.
        val long = ByteArray(10_000) { (it % 251).toByte() }
        assertThat(id.verify(long, secret.sign(long))).isTrue()
    }

    fun `verify fails for a tampered message`() {
        val secret = SecretKey.generate()
        val signature = secret.sign(MESSAGE)
        val tampered = MESSAGE.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertThat(secret.public().verify(tampered, signature)).isFalse()
        // Truncation and extension must fail too, not only substitution.
        assertThat(secret.public().verify(MESSAGE.copyOf(MESSAGE.size - 1), signature)).isFalse()
        assertThat(secret.public().verify(MESSAGE + 0.toByte(), signature)).isFalse()
    }

    fun `verify fails for the wrong key`() {
        val signer = SecretKey.generate()
        val other = SecretKey.generate()
        val signature = signer.sign(MESSAGE)

        assertThat(signer.public().verify(MESSAGE, signature)).isTrue()
        assertThat(other.public().verify(MESSAGE, signature)).isFalse()
    }

    fun `verify fails for a tampered signature`() {
        val secret = SecretKey.generate()
        val bytes = secret.sign(MESSAGE).toBytes()
        bytes[0] = (bytes[0] + 1).toByte()

        // Still a structurally valid 64-byte signature, so this must be `false` rather than an
        // error — a bad signature is an answer, not a malformed input.
        assertThat(secret.public().verify(MESSAGE, Signature.fromBytes(bytes))).isFalse()
    }

    // ── String forms ────────────────────────────────────────────────────────────────────────

    fun `endpoint id round-trips through its textual form`() {
        repeat(5) {
            val id = SecretKey.generate().public()
            val text = id.toString()
            assertThat(EndpointId.fromString(text)).isEqualTo(id)
            assertThat(EndpointId.fromString(text).toString()).isEqualTo(text)
        }
    }

    fun `endpoint id round-trips through z-base-32`() {
        repeat(5) {
            val id = SecretKey.generate().public()
            assertThat(EndpointId.fromZ32(id.toZ32())).isEqualTo(id)
        }
    }

    fun `endpoint id matches the upstream iroh-ffi vector`() {
        // Pinned against iroh-ffi v1.0.0's own `test_endpoint_id`, which is the parity contract:
        // its `to_string()` is lowercase hex and its `fmt_short()` is the first ten hex digits.
        // If this ever fails, an endpoint id printed by iroh4k no longer matches the same id
        // printed by iroh, and tickets and addresses stop interoperating.
        val id = EndpointId.fromBytes(hex(IROH_FFI_VECTOR_HEX))
        assertThat(id.toString()).isEqualTo(IROH_FFI_VECTOR_HEX)
        assertThat(id.fmtShort()).isEqualTo(IROH_FFI_VECTOR_SHORT)
        assertThat(EndpointId.fromString(IROH_FFI_VECTOR_HEX)).isEqualTo(id)
    }

    fun `endpoint id renders as hex and not as z-base-32`() {
        val id = EndpointId.fromBytes(hex(RFC_PUBLIC_HEX))
        assertThat(id.toString()).isEqualTo(RFC_PUBLIC_HEX)
        assertThat(id.toZ32()).isEqualTo(RFC_PUBLIC_Z32)
        // The two encodings must not be confused for one another.
        assertThat(id.toString()).isNotEqualTo(id.toZ32())
        // iroh's short form is the hex of the first five bytes.
        assertThat(id.fmtShort()).isEqualTo(RFC_PUBLIC_HEX.substring(0, 10))
    }

    fun `fromString accepts both hex and base32`() {
        val id = EndpointId.fromBytes(hex(RFC_PUBLIC_HEX))
        // iroh's FromStr dispatches on length: 64 characters are decoded as *lowercase* hex,
        // anything else as standard base32 (case-insensitively). So an id copied out of any iroh
        // tool parses without the caller knowing which encoding produced it — but uppercase hex is
        // not one of the accepted forms, and a test asserting otherwise would be wrong about iroh.
        assertThat(EndpointId.fromString(RFC_PUBLIC_HEX)).isEqualTo(id)
        assertThat(EndpointId.fromString(RFC_PUBLIC_BASE32)).isEqualTo(id)
        assertThat(EndpointId.fromString(RFC_PUBLIC_BASE32.lowercase())).isEqualTo(id)
        assertFailsWith<IrohError> { EndpointId.fromString(RFC_PUBLIC_HEX.uppercase()) }
    }

    fun `signature renders as lowercase hex`() {
        val signature = Signature.fromBytes(hex(RFC_SIGNATURE_HEX))
        assertThat(signature.toString()).isEqualTo(RFC_SIGNATURE_HEX)
    }

    fun `secret key never renders its bytes`() {
        // A secret key that prints itself ends up in a log file.
        val secret = SecretKey.fromBytes(hex(RFC_SECRET_HEX))
        assertThat(secret.toString()).isEqualTo("SecretKey(..)")
        assertThat(secret.toString()).isNotEqualTo(RFC_SECRET_HEX)
    }

    // ── Malformed input ─────────────────────────────────────────────────────────────────────

    fun `wrong-length secret key bytes raise a key error`() {
        for (size in listOf(0, 1, 31, 33, 64)) {
            val error = assertFailsWith<IrohError> { SecretKey.fromBytes(ByteArray(size)) }
            assertThat(error.code).isEqualTo(IrohError.Code.Key)
            assertThat(error.message!!).contains("32 bytes")
        }
    }

    fun `wrong-length endpoint id bytes raise a key error`() {
        for (size in listOf(0, 31, 33)) {
            val error = assertFailsWith<IrohError> { EndpointId.fromBytes(ByteArray(size)) }
            assertThat(error.code).isEqualTo(IrohError.Code.Key)
            assertThat(error.message!!).contains("32 bytes")
        }
    }

    fun `wrong-length signature bytes raise a key error`() {
        for (size in listOf(0, 32, 63, 65)) {
            val error = assertFailsWith<IrohError> { Signature.fromBytes(ByteArray(size)) }
            assertThat(error.code).isEqualTo(IrohError.Code.Key)
            assertThat(error.message!!).contains("64 bytes")
        }
    }

    fun `endpoint id bytes that are not a curve point raise a key error`() {
        // 32 bytes of the right length that do not decompress to a point on the Edwards curve.
        // This is the case a Kotlin-side length check would wave through, which is why the
        // validation is delegated to iroh instead.
        val error = assertFailsWith<IrohError> { EndpointId.fromBytes(hex(NOT_A_POINT_HEX)) }
        assertThat(error.code).isEqualTo(IrohError.Code.Key)
    }

    fun `malformed endpoint id text raises a key error`() {
        val rejected = listOf(
            "",                              // nothing at all
            "not-a-key",                     // outside every accepted alphabet
            "yy",                            // far too short
            RFC_PUBLIC_HEX.dropLast(1),      // one digit short of a key
            RFC_PUBLIC_HEX + "0",            // one digit too long
            RFC_PUBLIC_HEX.dropLast(1) + "g", // right length, not hex
        )
        for (text in rejected) {
            val error = assertFailsWith<IrohError>("expected \"$text\" to be rejected") {
                EndpointId.fromString(text)
            }
            assertThat(error.code).isEqualTo(IrohError.Code.Key)
        }
    }

    fun `malformed z-base-32 raises a key error`() {
        val rejected = listOf(
            "",
            "not a key",
            RFC_PUBLIC_Z32.dropLast(1),
            RFC_PUBLIC_Z32 + "y",
        )
        for (text in rejected) {
            val error = assertFailsWith<IrohError>("expected \"$text\" to be rejected") {
                EndpointId.fromZ32(text)
            }
            assertThat(error.code).isEqualTo(IrohError.Code.Key)
        }
    }

    // ── Value semantics ─────────────────────────────────────────────────────────────────────

    fun `value types compare and hash by content`() {
        val secret = SecretKey.generate()
        val id = secret.public()
        val signature = secret.sign(MESSAGE)

        // Distinct instances built from equal bytes, so any array-identity comparison fails here.
        val secretCopy = SecretKey.fromBytes(secret.toBytes())
        val idCopy = EndpointId.fromBytes(id.toBytes())
        val signatureCopy = Signature.fromBytes(signature.toBytes())

        assertThat(secretCopy).isEqualTo(secret)
        assertThat(idCopy).isEqualTo(id)
        assertThat(signatureCopy).isEqualTo(signature)
        assertThat(secretCopy.hashCode()).isEqualTo(secret.hashCode())
        assertThat(idCopy.hashCode()).isEqualTo(id.hashCode())
        assertThat(signatureCopy.hashCode()).isEqualTo(signature.hashCode())

        // Which is what makes them usable as keys in a collection — the reason it matters.
        assertThat(setOf(id, idCopy)).hasSize(1)
        assertThat(mapOf(id to "one")[idCopy]).isEqualTo("one")

        val other = SecretKey.generate()
        assertThat(other).isNotEqualTo(secret)
        assertThat(other.public()).isNotEqualTo(id)
        assertThat(other.sign(MESSAGE)).isNotEqualTo(signature)
        // And the types never compare equal across their own boundaries, even though all three
        // are ultimately byte arrays.
        assertThat(id.equals(signature)).isFalse()
        assertThat(signature.equals(id)).isFalse()
    }

    private companion object {
        val MESSAGE = "hello iroh".encodeToByteArray()

        // RFC 8032 section 7.1, TEST 1 — an Ed25519 vector specified outside this project.
        const val RFC_SECRET_HEX =
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
        const val RFC_PUBLIC_HEX =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        const val RFC_SIGNATURE_HEX =
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e0652249015" +
                "55fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

        /**
         * The endpoint id vector from iroh-ffi v1.0.0's own `test_endpoint_id`, with the exact
         * strings it asserts. Copied so the parity contract is pinned by an external source rather
         * than by whatever iroh4k happens to produce.
         */
        const val IROH_FFI_VECTOR_HEX =
            "523c7996bad77424e96786cf7a7205115337a5b4565cd25506a0f297b191a5ea"
        const val IROH_FFI_VECTOR_SHORT = "523c7996ba"

        /** The z-base-32 form of [RFC_PUBLIC_HEX], the encoding pkarr and therefore iroh use. */
        const val RFC_PUBLIC_Z32 = "47pjoycnsrfmxikm95jh13y88e8qnhzu5kungjpxyepgt7a8krpy"

        /**
         * The standard (RFC 4648, unpadded) base32 form of [RFC_PUBLIC_HEX] — the second encoding
         * iroh's `FromStr` accepts. Distinct from [RFC_PUBLIC_Z32], which uses pkarr's alphabet.
         */
        const val RFC_PUBLIC_BASE32 =
            "25NJQAMCWEFLPVKL73J4SZAHHIHOC4XT3KTCGJNPAINGR5YHKENA"

        /** 32 bytes that are not a valid compressed Edwards y-coordinate. */
        const val NOT_A_POINT_HEX =
            "523c7996bad77424e96786cf7a7205115337a5b4565cd25506a0f297b191a501"

        fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
