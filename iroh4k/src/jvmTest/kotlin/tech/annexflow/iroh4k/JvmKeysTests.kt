package tech.annexflow.iroh4k

import kotlin.test.Test

/** Exercises the keys domain over the JNI facade: the same Rust core loaded as a shared library. */
class JvmKeysTests {
    private val runner = CommonKeysTests()

    @Test
    fun `generate produces distinct secret keys`() =
        runner.`generate produces distinct secret keys`()

    @Test
    fun `secret key round-trips through its bytes`() =
        runner.`secret key round-trips through its bytes`()

    @Test
    fun `endpoint id and signature round-trip through their bytes`() =
        runner.`endpoint id and signature round-trip through their bytes`()

    @Test
    fun `toBytes hands out a defensive copy`() =
        runner.`toBytes hands out a defensive copy`()

    @Test
    fun `public key derivation matches the RFC 8032 test vector`() =
        runner.`public key derivation matches the RFC 8032 test vector`()

    @Test
    fun `public key derivation is deterministic`() =
        runner.`public key derivation is deterministic`()

    @Test
    fun `signing an empty message matches the RFC 8032 test vector`() =
        runner.`signing an empty message matches the RFC 8032 test vector`()

    @Test
    fun `sign and verify round-trip`() =
        runner.`sign and verify round-trip`()

    @Test
    fun `verify fails for a tampered message`() =
        runner.`verify fails for a tampered message`()

    @Test
    fun `verify fails for the wrong key`() =
        runner.`verify fails for the wrong key`()

    @Test
    fun `verify fails for a tampered signature`() =
        runner.`verify fails for a tampered signature`()

    @Test
    fun `endpoint id round-trips through its textual form`() =
        runner.`endpoint id round-trips through its textual form`()

    @Test
    fun `endpoint id round-trips through z-base-32`() =
        runner.`endpoint id round-trips through z-base-32`()

    @Test
    fun `endpoint id matches the upstream iroh-ffi vector`() =
        runner.`endpoint id matches the upstream iroh-ffi vector`()

    @Test
    fun `endpoint id renders as hex and not as z-base-32`() =
        runner.`endpoint id renders as hex and not as z-base-32`()

    @Test
    fun `fromString accepts both hex and base32`() =
        runner.`fromString accepts both hex and base32`()

    @Test
    fun `malformed endpoint id text raises a key error`() =
        runner.`malformed endpoint id text raises a key error`()


    @Test
    fun `signature renders as lowercase hex`() =
        runner.`signature renders as lowercase hex`()

    @Test
    fun `secret key never renders its bytes`() =
        runner.`secret key never renders its bytes`()

    @Test
    fun `wrong-length secret key bytes raise a key error`() =
        runner.`wrong-length secret key bytes raise a key error`()

    @Test
    fun `wrong-length endpoint id bytes raise a key error`() =
        runner.`wrong-length endpoint id bytes raise a key error`()

    @Test
    fun `wrong-length signature bytes raise a key error`() =
        runner.`wrong-length signature bytes raise a key error`()

    @Test
    fun `endpoint id bytes that are not a curve point raise a key error`() =
        runner.`endpoint id bytes that are not a curve point raise a key error`()

    @Test
    fun `malformed z-base-32 raises a key error`() =
        runner.`malformed z-base-32 raises a key error`()

    @Test
    fun `value types compare and hash by content`() =
        runner.`value types compare and hash by content`()
}
