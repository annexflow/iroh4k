package tech.annexflow.iroh4k

import kotlin.test.Test

/**
 * Exercises the services surface over the FFI/cinterop facade: the statically linked Rust library.
 */
class NativeServicesTests {
    private val runner = CommonServicesTests()

    @Test
    fun `the capability vocabulary matches the linked rust build`() =
        runner.`the capability vocabulary matches the linked rust build`()

    @Test
    fun `a client is created without touching the network`() =
        runner.`a client is created without touching the network`()

    @Test
    fun `creating a client is fast because it performs no io`() =
        runner.`creating a client is fast because it performs no io`()

    @Test
    fun `a client can be created on top of an api secret with an explicit remote`() =
        runner.`a client can be created on top of an api secret with an explicit remote`()

    @Test
    fun `a remote address this build cannot represent is refused as an Addr error`() =
        runner.`a remote address this build cannot represent is refused as an Addr error`()

    @Test
    fun `a malformed credential is refused and leaks nothing`() =
        runner.`a malformed credential is refused and leaks nothing`()

    @Test
    fun `reading the credential from the environment is bounded either way`() =
        runner.`reading the credential from the environment is bounded either way`()

    @Test
    fun `creating a client on a released endpoint reports Closed`() =
        runner.`creating a client on a released endpoint reports Closed`()

    @Test
    fun `a name outside two to 128 bytes is refused`() =
        runner.`a name outside two to 128 bytes is refused`()

    @Test
    fun `name reports what the client was configured with`() =
        runner.`name reports what the client was configured with`()

    @Test
    fun `setName rejects an out of range name before it reaches the service`() =
        runner.`setName rejects an out of range name before it reaches the service`()

    @Test
    fun `a metrics push interval below one millisecond is refused`() =
        runner.`a metrics push interval below one millisecond is refused`()

    @Test
    fun `every service operation fails promptly when the service is unreachable`() =
        runner.`every service operation fails promptly when the service is unreachable`()

    @Test
    fun `granting no capability at all is refused`() =
        runner.`granting no capability at all is refused`()

    @Test
    fun `a client outlives the endpoint handle it was created from`() =
        runner.`a client outlives the endpoint handle it was created from`()

    @Test
    fun `using a released client raises Closed rather than crashing`() =
        runner.`using a released client raises Closed rather than crashing`()

    @Test
    fun `a client works as an AutoCloseable resource`() =
        runner.`a client works as an AutoCloseable resource`()

    @Test
    fun `cancelling net diagnostics returns promptly and drains the op registry`() =
        runner.`cancelling net diagnostics returns promptly and drains the op registry`()

    @Test
    fun `closing a client while a call is in flight is safe`() =
        runner.`closing a client while a call is in flight is safe`()

    @Test
    fun `repeated create and close cycles leak neither handles nor operations`() =
        runner.`repeated create and close cycles leak neither handles nor operations`()

    @Test
    fun `a credential never renders its secret`() =
        runner.`a credential never renders its secret`()

    @Test
    fun `metrics push distinguishes the default from an explicit interval`() =
        runner.`metrics push distinguishes the default from an explicit interval`()

    @Test
    fun `a pong is a value and renders as hex`() =
        runner.`a pong is a value and renders as hex`()

    @Test
    fun `a net report derives what upstream derives`() =
        runner.`a net report derives what upstream derives`()

    @Test
    fun `diagnostics values are comparable`() =
        runner.`diagnostics values are comparable`()
}
