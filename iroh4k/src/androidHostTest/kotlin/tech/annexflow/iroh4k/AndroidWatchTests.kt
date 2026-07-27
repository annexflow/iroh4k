package tech.annexflow.iroh4k

import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises watchers from the Android target: the same shared `CommonWatchTests` bodies as
 * `JvmWatchTests`, run under Robolectric on the host JVM against `androidMain`'s loader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidWatchTests {
    private val runner = CommonWatchTests()

    @Test
    fun `watchAddr emits the endpoint's own address`() =
        runner.`watchAddr emits the endpoint's own address`()

    @Test
    fun `watchAddr reports an address added while it is being collected`() =
        runner.`watchAddr reports an address added while it is being collected`()

    @Test
    fun `watchHomeRelay reports no relays for an endpoint that has none`() =
        runner.`watchHomeRelay reports no relays for an endpoint that has none`()

    @Test
    fun `watchNetworkChange emits when the endpoint's reachability moves`() =
        runner.`watchNetworkChange emits when the endpoint's reachability moves`()

    @Test
    fun `watchPaths emits the live paths of a loopback connection`() =
        runner.`watchPaths emits the live paths of a loopback connection`()

    @Test
    fun `watchPathEvents reports the closing path and then ends`() =
        runner.`watchPathEvents reports the closing path and then ends`()

    @Test
    fun `two collectors on one endpoint both see its address`() =
        runner.`two collectors on one endpoint both see its address`()

    @Test
    fun `a flow that is never collected creates no watcher`() =
        runner.`a flow that is never collected creates no watcher`()

    @Test
    fun `cancelling a collector returns promptly and drains the op registry`() =
        runner.`cancelling a collector returns promptly and drains the op registry`()

    @Test
    fun `take ends a collection and releases its watcher`() =
        runner.`take ends a collection and releases its watcher`()

    @Test
    fun `shutting the endpoint down ends its watchers without throwing`() =
        runner.`shutting the endpoint down ends its watchers without throwing`()

    @Test
    fun `closing the connection ends watchPaths without throwing`() =
        runner.`closing the connection ends watchPaths without throwing`()

    @Test
    fun `watching a released endpoint raises Closed`() =
        runner.`watching a released endpoint raises Closed`()

    @Test
    fun `watching a released connection raises Closed`() =
        runner.`watching a released connection raises Closed`()

    @Test
    fun `repeated collect and cancel cycles leak neither watchers nor operations`() =
        runner.`repeated collect and cancel cycles leak neither watchers nor operations`()

    @Test
    fun `repeated endpoint watcher cycles leak neither watchers nor operations`() =
        runner.`repeated endpoint watcher cycles leak neither watchers nor operations`()
}
