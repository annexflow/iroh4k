package tech.annexflow.iroh4k

import kotlin.test.Test

/** Exercises the endpoint surface over the JNI facade: the same Rust core as a shared library. */
class JvmEndpointTests {
    private val runner = CommonEndpointTests()

    @Test
    fun `an endpoint binds with the identity it was given`() =
        runner.`an endpoint binds with the identity it was given`()

    @Test
    fun `an endpoint without a secret key gets a fresh identity`() =
        runner.`an endpoint without a secret key gets a fresh identity`()

    @Test
    fun `two endpoints bind to distinct real ports`() =
        runner.`two endpoints bind to distinct real ports`()

    @Test
    fun `binding on a port already in use raises a Bind error`() =
        runner.`binding on a port already in use raises a Bind error`()

    @Test
    fun `the Empty preset cannot bind`() =
        runner.`the Empty preset cannot bind`()

    @Test
    fun `an external address given at bind time is accepted`() =
        runner.`an external address given at bind time is accepted`()

    @Test
    fun `alpns are configurable at bind time and replaceable afterwards`() =
        runner.`alpns are configurable at bind time and replaceable afterwards`()

    @Test
    fun `an endpoint config copies its alpns`() =
        runner.`an endpoint config copies its alpns`()

    @Test
    fun `stats reports iroh's own metrics`() =
        runner.`stats reports iroh's own metrics`()

    @Test
    fun `relay configurations can be inserted and removed`() =
        runner.`relay configurations can be inserted and removed`()

    @Test
    fun `a relay configuration with an impossible quic port is rejected`() =
        runner.`a relay configuration with an impossible quic port is rejected`()

    @Test
    fun `external addresses can be added and removed at runtime`() =
        runner.`external addresses can be added and removed at runtime`()

    @Test
    fun `notifying a network change is harmless`() =
        runner.`notifying a network change is harmless`()

    @Test
    fun `an added endpoint address lets an id-only dial resolve`() =
        runner.`an added endpoint address lets an id-only dial resolve`()

    @Test
    fun `adding an endpoint address accumulates rather than replacing`() =
        runner.`adding an endpoint address accumulates rather than replacing`()

    @Test
    fun `a removed endpoint address stops resolving an id-only dial`() =
        runner.`a removed endpoint address stops resolving an id-only dial`()

    @Test
    fun `removing an endpoint address reports whether there was one`() =
        runner.`removing an endpoint address reports whether there was one`()

    @Test
    fun `an endpoint address this build cannot re-encode is refused`() =
        runner.`an endpoint address this build cannot re-encode is refused`()

    @Test
    fun `an unknown remote has no address`() =
        runner.`an unknown remote has no address`()

    @Test
    fun `shutdown closes the endpoint and is safe to repeat`() =
        runner.`shutdown closes the endpoint and is safe to repeat`()

    @Test
    fun `a shut down endpoint reports iroh's own no-op answers`() =
        runner.`a shut down endpoint reports iroh's own no-op answers`()

    @Test
    fun `using a released endpoint raises Closed rather than crashing`() =
        runner.`using a released endpoint raises Closed rather than crashing`()

    @Test
    fun `an endpoint works as an AutoCloseable resource`() =
        runner.`an endpoint works as an AutoCloseable resource`()

    @Test
    fun `cancelling online returns promptly and drains the op registry`() =
        runner.`cancelling online returns promptly and drains the op registry`()

    @Test
    fun `online can be bounded by a timeout`() =
        runner.`online can be bounded by a timeout`()

    @Test
    fun `repeated bind and shutdown cycles leak neither handles nor operations`() =
        runner.`repeated bind and shutdown cycles leak neither handles nor operations`()

    @Test
    fun `closing an endpoint while a call is in flight is safe`() =
        runner.`closing an endpoint while a call is in flight is safe`()

    @Test
    fun `relay config is a value and hides its token`() =
        runner.`relay config is a value and hides its token`()

    @Test
    fun `mdns config is a value and is absent unless asked for`() =
        runner.`mdns config is a value and is absent unless asked for`()

    @Test
    fun `discovery services are values`() =
        runner.`discovery services are values`()

    @Test
    fun `an endpoint binds with discovery services configured`() =
        runner.`an endpoint binds with discovery services configured`()

    @Test
    fun `a malformed discovery URL is refused`() =
        runner.`a malformed discovery URL is refused`()

    @Test
    fun `the address book survives clearing the preset's lookup`() =
        runner.`the address book survives clearing the preset's lookup`()

    @Test
    fun `PublishedAddrs ordinals match the Rust wire contract`() =
        runner.`PublishedAddrs ordinals match the Rust wire contract`()

    @Test
    fun `transport config is a value and says nothing by default`() =
        runner.`transport config is a value and says nothing by default`()

    @Test
    fun `CongestionController ordinals match the Rust wire contract`() =
        runner.`CongestionController ordinals match the Rust wire contract`()

    @Test
    fun `an endpoint binds with a transport configuration`() =
        runner.`an endpoint binds with a transport configuration`()

    @Test
    fun `a transport value upstream ignores is passed through rather than refused`() =
        runner.`a transport value upstream ignores is passed through rather than refused`()

    @Test
    fun `the largest transport duration a caller can express is accepted and a negative one is refused`() =
        runner.`the largest transport duration a caller can express is accepted and a negative one is refused`()
}
