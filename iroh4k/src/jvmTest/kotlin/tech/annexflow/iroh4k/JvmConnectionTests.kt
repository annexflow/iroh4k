package tech.annexflow.iroh4k

import kotlin.test.Test

/** Exercises the connection surface over the JNI facade: the same Rust core as a shared library. */
class JvmConnectionTests {
    private val runner = CommonConnectionTests()

    @Test
    fun `a loopback handshake reaches a connection on both sides`() =
        runner.`a loopback handshake reaches a connection on both sides`()

    @Test
    fun `the negotiated alpn is readable before the handshake completes`() =
        runner.`the negotiated alpn is readable before the handshake completes`()

    @Test
    fun `a mismatched alpn fails the handshake rather than hanging`() =
        runner.`a mismatched alpn fails the handshake rather than hanging`()

    @Test
    fun `refusing and ignoring an incoming connection leave the endpoint accepting`() =
        runner.`refusing and ignoring an incoming connection leave the endpoint accepting`()

    @Test
    fun `retry makes the peer validate its address`() =
        runner.`retry makes the peer validate its address`()

    @Test
    fun `an incoming connection describes itself before it is consumed`() =
        runner.`an incoming connection describes itself before it is consumed`()

    @Test
    fun `an incoming connection can only be consumed once`() =
        runner.`an incoming connection can only be consumed once`()

    @Test
    fun `acceptNext answers null once the endpoint is shut down`() =
        runner.`acceptNext answers null once the endpoint is shut down`()

    @Test
    fun `accepting or dialling on a released endpoint raises Closed`() =
        runner.`accepting or dialling on a released endpoint raises Closed`()

    @Test
    fun `dialling something impossible is refused rather than left hanging`() =
        runner.`dialling something impossible is refused rather than left hanging`()

    @Test
    fun `cancelling acceptNext returns promptly and drains the op registry`() =
        runner.`cancelling acceptNext returns promptly and drains the op registry`()

    @Test
    fun `a connection can be closed with an error code and a reason`() =
        runner.`a connection can be closed with an error code and a reason`()

    @Test
    fun `an impossible close error code is rejected`() =
        runner.`an impossible close error code is rejected`()

    @Test
    fun `connect reaches a connection in a single step`() =
        runner.`connect reaches a connection in a single step`()

    @Test
    fun `connect refuses the impossible rather than leaving it hanging`() =
        runner.`connect refuses the impossible rather than leaving it hanging`()

    @Test
    fun `a connection is a QuicConnection and streams open through the supertype`() =
        runner.`a connection is a QuicConnection and streams open through the supertype`()

    @Test
    fun `side tells the two ends of one connection apart`() =
        runner.`side tells the two ends of one connection apart`()

    @Test
    fun `stats count a transfer and rtt is plausible`() =
        runner.`stats count a transfer and rtt is plausible`()

    @Test
    fun `paths describe the loopback path`() =
        runner.`paths describe the loopback path`()

    @Test
    fun `datagrams round trip both ways`() =
        runner.`datagrams round trip both ways`()

    @Test
    fun `a datagram over the maximum size is refused cleanly`() =
        runner.`a datagram over the maximum size is refused cleanly`()

    @Test
    fun `the connection limits take a varint and reject anything else`() =
        runner.`the connection limits take a varint and reject anything else`()

    @Test
    fun `closed resolves when the peer closes`() =
        runner.`closed resolves when the peer closes`()

    @Test
    fun `cancelling closed returns promptly and drains the op registry`() =
        runner.`cancelling closed returns promptly and drains the op registry`()

    @Test
    fun `a released incoming connection raises Closed from every member`() =
        runner.`a released incoming connection raises Closed from every member`()

    @Test
    fun `a released connection attempt raises Closed from every member`() =
        runner.`a released connection attempt raises Closed from every member`()

    @Test
    fun `a released accepting handshake raises Closed from every member`() =
        runner.`a released accepting handshake raises Closed from every member`()

    @Test
    fun `a released connection raises Closed from every member`() =
        runner.`a released connection raises Closed from every member`()

    @Test
    fun `repeated accept cycles leak neither handles nor operations`() =
        runner.`repeated accept cycles leak neither handles nor operations`()

    @Test
    fun `incoming and local addresses are values`() =
        runner.`incoming and local addresses are values`()

    @Test
    fun `a connection made with datagrams refused reports no datagram size`() =
        runner.`a connection made with datagrams refused reports no datagram size`()

    @Test
    fun `an incoming connection can be accepted with its own transport configuration`() =
        runner.`an incoming connection can be accepted with its own transport configuration`()

    @Test
    fun `acceptWith with no overlapping ALPN fails loudly rather than silently`() =
        runner.`acceptWith with no overlapping ALPN fails loudly rather than silently`()

    @Test
    fun `acceptWith refuses an empty ALPN list without touching the incoming connection`() =
        runner.`acceptWith refuses an empty ALPN list without touching the incoming connection`()

    @Test
    fun `the first dial has no ticket and leaves the attempt usable`() =
        runner.`the first dial has no ticket and leaves the attempt usable`()

    @Test
    fun `a second dial from the same endpoint sends data before the handshake`() =
        runner.`a second dial from the same endpoint sends data before the handshake`()

    @Test
    fun `the accepting side reads a stream before its handshake completes`() =
        runner.`the accepting side reads a stream before its handshake completes`()
}
