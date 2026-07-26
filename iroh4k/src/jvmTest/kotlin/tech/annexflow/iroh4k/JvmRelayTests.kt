package tech.annexflow.iroh4k

import kotlin.test.Test

/** Exercises the relay surface over the JNI facade: the same Rust core as a shared library. */
class JvmRelayTests {
    private val runner = CommonRelayTests()

    @Test
    fun `relay URLs come back in iroh's canonical form`() =
        runner.`relay URLs come back in iroh's canonical form`()

    @Test
    fun `normalisation is idempotent and drives equality`() =
        runner.`normalisation is idempotent and drives equality`()

    @Test
    fun `invalid relay URLs raise a Relay error`() =
        runner.`invalid relay URLs raise a Relay error`()

    @Test
    fun `an empty relay map holds nothing`() =
        runner.`an empty relay map holds nothing`()

    @Test
    fun `fromUrls parses and canonicalises every entry`() =
        runner.`fromUrls parses and canonicalises every entry`()

    @Test
    fun `fromUrls rejects the whole list when one entry is invalid`() =
        runner.`fromUrls rejects the whole list when one entry is invalid`()

    @Test
    fun `insert returns a new map and leaves the original alone`() =
        runner.`insert returns a new map and leaves the original alone`()

    @Test
    fun `inserting a duplicate changes nothing`() =
        runner.`inserting a duplicate changes nothing`()

    @Test
    fun `remove drops exactly one relay and tolerates absent ones`() =
        runner.`remove drops exactly one relay and tolerates absent ones`()

    @Test
    fun `relay map equality ignores order but not contents`() =
        runner.`relay map equality ignores order but not contents`()

    @Test
    fun `relay map toString lists its relays`() =
        runner.`relay map toString lists its relays`()

    @Test
    fun `disabled mode has no relays`() =
        runner.`disabled mode has no relays`()

    @Test
    fun `the default mode resolves to iroh's production fleet`() =
        runner.`the default mode resolves to iroh's production fleet`()

    @Test
    fun `the staging mode resolves to a different fleet`() =
        runner.`the staging mode resolves to a different fleet`()

    @Test
    fun `the built-in fleets are cached rather than re-fetched`() =
        runner.`the built-in fleets are cached rather than re-fetched`()

    @Test
    fun `custom mode round-trips its map`() =
        runner.`custom mode round-trips its map`()

    @Test
    fun `customFromUrls builds a custom mode`() =
        runner.`customFromUrls builds a custom mode`()

    @Test
    fun `setLogLevel is safe to call repeatedly`() =
        runner.`setLogLevel is safe to call repeatedly`()

    @Test
    fun `LogLevel ordinals match the Rust wire contract`() =
        runner.`LogLevel ordinals match the Rust wire contract`()
}
