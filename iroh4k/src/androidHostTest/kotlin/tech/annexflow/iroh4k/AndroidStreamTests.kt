package tech.annexflow.iroh4k

import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises QUIC streams from the Android target: the same shared `CommonStreamTests` bodies
 * as `JvmStreamTests`, run under Robolectric on the host JVM against `androidMain`'s loader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidStreamTests {
    private val runner = CommonStreamTests()

    @Test
    fun `a payload echoed over a bidirectional stream comes back byte for byte`() =
        runner.`a payload echoed over a bidirectional stream comes back byte for byte`()

    @Test
    fun `a payload larger than one frame survives the round trip`() =
        runner.`a payload larger than one frame survives the round trip`()

    @Test
    fun `write reports how much it took and writeAll takes all of it`() =
        runner.`write reports how much it took and writeAll takes all of it`()

    @Test
    fun `a unidirectional stream carries data one way`() =
        runner.`a unidirectional stream carries data one way`()

    @Test
    fun `finish ends the stream and refuses a later write or finish`() =
        runner.`finish ends the stream and refuses a later write or finish`()

    @Test
    fun `readToEnd answers everything the peer finished`() =
        runner.`readToEnd answers everything the peer finished`()

    @Test
    fun `readExact answers exactly and fails rather than hanging when the stream ends early`() =
        runner.`readExact answers exactly and fails rather than hanging when the stream ends early`()

    @Test
    fun `resetting the send half surfaces on the receive half`() =
        runner.`resetting the send half surfaces on the receive half`()

    @Test
    fun `stopping the receive half surfaces to the sender`() =
        runner.`stopping the receive half surfaces to the sender`()

    @Test
    fun `a stream describes itself`() =
        runner.`a stream describes itself`()

    @Test
    fun `impossible read sizes and stream codes are rejected`() =
        runner.`impossible read sizes and stream codes are rejected`()

    @Test
    fun `cancelling a blocked read returns promptly and drains the op registry`() =
        runner.`cancelling a blocked read returns promptly and drains the op registry`()

    @Test
    fun `cancelling a blocked acceptBi returns promptly and drains the op registry`() =
        runner.`cancelling a blocked acceptBi returns promptly and drains the op registry`()

    @Test
    fun `streams on a closed connection are refused rather than left hanging`() =
        runner.`streams on a closed connection are refused rather than left hanging`()

    @Test
    fun `a released send stream raises Closed from every member`() =
        runner.`a released send stream raises Closed from every member`()

    @Test
    fun `a released receive stream raises Closed from every member`() =
        runner.`a released receive stream raises Closed from every member`()

    @Test
    fun `releasing one half of a bidirectional stream leaves the other usable`() =
        runner.`releasing one half of a bidirectional stream leaves the other usable`()

    @Test
    fun `repeated stream cycles leak neither handles nor operations`() =
        runner.`repeated stream cycles leak neither handles nor operations`()
}
