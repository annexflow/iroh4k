package tech.annexflow.iroh4k

import kotlin.test.Test

/**
 * Exercises the router over the FFI/cinterop facade: the statically linked Rust library.
 *
 * The router itself is pure Kotlin, so there is no `expect`/`actual` pair here to pin down — but the
 * accept chain underneath it is, and a continuation or a cancellation that behaves differently on
 * this facade shows up as a router that hangs. See `CommonRouterTests`.
 */
class NativeRouterTests {
    private val runner = CommonRouterTests()

    @Test
    fun `a router dispatches a connection to its handler`() =
        runner.`a router dispatches a connection to its handler`()

    @Test
    fun `two protocols dispatch to their own handlers`() =
        runner.`two protocols dispatch to their own handlers`()

    @Test
    fun `registering an alpn twice replaces the first handler`() =
        runner.`registering an alpn twice replaces the first handler`()

    @Test
    fun `an unregistered alpn is refused and the router keeps accepting`() =
        runner.`an unregistered alpn is refused and the router keeps accepting`()

    @Test
    fun `a failed handshake does not stop the loop`() =
        runner.`a failed handshake does not stop the loop`()

    @Test
    fun `a handler that throws does not stop the router`() =
        runner.`a handler that throws does not stop the router`()

    @Test
    fun `a failing failure sink does not stop the router`() =
        runner.`a failing failure sink does not stop the router`()

    @Test
    fun `a suspended handler does not delay another connection`() =
        runner.`a suspended handler does not delay another connection`()

    @Test
    fun `shutdown cancels in-flight handlers and leaves no coroutine`() =
        runner.`shutdown cancels in-flight handlers and leaves no coroutine`()

    @Test
    fun `close ends the router without waiting`() =
        runner.`close ends the router without waiting`()

    @Test
    fun `a router with no protocols shuts down cleanly and leaves the endpoint alone`() =
        runner.`a router with no protocols shuts down cleanly and leaves the endpoint alone`()

    @Test
    fun `a shut-down endpoint ends the accept loop`() =
        runner.`a shut-down endpoint ends the accept loop`()

    @Test
    fun `the endpoint outlives its router and can carry another one`() =
        runner.`the endpoint outlives its router and can carry another one`()

    @Test
    fun `a routed connection leaks neither handles nor operations`() =
        runner.`a routed connection leaks neither handles nor operations`()

    @Test
    fun `shutdown releases every connection it was serving`() =
        runner.`shutdown releases every connection it was serving`()
}
