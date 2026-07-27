package tech.annexflow.iroh4k

import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the addressing surface from the Android target: the same shared
 * `CommonAddrTests` bodies as `JvmAddrTests`, run under Robolectric on the host JVM against
 * `androidMain`'s loader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAddrTests {
    private val runner = CommonAddrTests()

    @Test
    fun `socket addresses come back in Rust's canonical form`() =
        runner.`socket addresses come back in Rust's canonical form`()

    @Test
    fun `invalid socket addresses raise an Addr error`() =
        runner.`invalid socket addresses raise an Addr error`()

    @Test
    fun `socket address accessors split the canonical form`() =
        runner.`socket address accessors split the canonical form`()

    @Test
    fun `socket address equality follows canonicalisation`() =
        runner.`socket address equality follows canonicalisation`()

    @Test
    fun `custom addresses are content-valued and print like iroh`() =
        runner.`custom addresses are content-valued and print like iroh`()

    @Test
    fun `an endpoint address starts out empty`() =
        runner.`an endpoint address starts out empty`()

    @Test
    fun `withRelayUrl and withIpAddr leave the receiver unchanged`() =
        runner.`withRelayUrl and withIpAddr leave the receiver unchanged`()

    @Test
    fun `adding a duplicate address does not grow the set`() =
        runner.`adding a duplicate address does not grow the set`()

    @Test
    fun `endpoint address equality is content-based and order-insensitive`() =
        runner.`endpoint address equality is content-based and order-insensitive`()

    @Test
    fun `the convenience views project each transport kind`() =
        runner.`the convenience views project each transport kind`()

    @Test
    fun `without removes exactly one address`() =
        runner.`without removes exactly one address`()

    @Test
    fun `toString mirrors iroh's rendering`() =
        runner.`toString mirrors iroh's rendering`()

    @Test
    fun `a ticket preserves every transport address`() =
        runner.`a ticket preserves every transport address`()

    @Test
    fun `an IPv6 zone id does not survive a ticket`() =
        runner.`an IPv6 zone id does not survive a ticket`()

    @Test
    fun `ticket strings round-trip through fromString`() =
        runner.`ticket strings round-trip through fromString`()

    @Test
    fun `malformed ticket strings raise a Ticket error`() =
        runner.`malformed ticket strings raise a Ticket error`()

    @Test
    fun `an endpoint address with no transport addresses round-trips`() =
        runner.`an endpoint address with no transport addresses round-trips`()

    @Test
    fun `a custom address survives a ticket round trip with a large unsigned id`() =
        runner.`a custom address survives a ticket round trip with a large unsigned id`()

    @Test
    fun `an unknown transport address cannot be re-encoded`() =
        runner.`an unknown transport address cannot be re-encoded`()

    @Test
    fun `a ticket carries iroh's canonical address ordering`() =
        runner.`a ticket carries iroh's canonical address ordering`()
}
