package tech.annexflow.iroh4k

import kotlin.test.Test

/** Exercises the FFI/cinterop facade: the statically linked Rust library. */
class NativeSmokeTests {
    private val runner = CommonSmokeTests()

    @Test
    fun `version reports both iroh4k and iroh versions`() =
        runner.`version reports both iroh4k and iroh versions`()

    @Test
    fun `smokeEcho round-trips a value through the tokio runtime`() =
        runner.`smokeEcho round-trips a value through the tokio runtime`()

    @Test
    fun `many concurrent echoes each resume their own continuation`() =
        runner.`many concurrent echoes each resume their own continuation`()

    @Test
    fun `smokeError surfaces the Rust error code and message`() =
        runner.`smokeError surfaces the Rust error code and message`()

    @Test
    fun `smokeRecord round-trips every codec primitive`() =
        runner.`smokeRecord round-trips every codec primitive`()

    @Test
    fun `cancelling a pending operation returns promptly and drains the registry`() =
        runner.`cancelling a pending operation returns promptly and drains the registry`()

    @Test
    fun `cancelling a pending operation aborts the Rust task`() =
        runner.`cancelling a pending operation aborts the Rust task`()

    @Test
    fun `completed operations do not accumulate in the registry`() =
        runner.`completed operations do not accumulate in the registry`()

    @Test
    fun `a short sleep still completes normally`() =
        runner.`a short sleep still completes normally`()
}
