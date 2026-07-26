@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.iroh4k_live_op_count
import iroh4k.ffi.iroh4k_smoke_async_echo
import iroh4k.ffi.iroh4k_smoke_async_error
import iroh4k.ffi.iroh4k_smoke_async_sleep
import iroh4k.ffi.iroh4k_smoke_record
import iroh4k.ffi.iroh4k_smoke_sleep_completions
import iroh4k.ffi.iroh4k_version
import kotlinx.cinterop.ExperimentalForeignApi

internal actual fun nativeVersion(): String =
    iroh4k_version().bytesOrThrow().decodeToString()

internal actual fun nativeSmokeRecord(): ByteArray =
    iroh4k_smoke_record().bytesOrThrow()

internal actual fun nativeLiveOpCount(): Long = iroh4k_live_op_count()

internal actual fun nativeSmokeSleepCompletions(): Long = iroh4k_smoke_sleep_completions()

internal actual suspend fun nativeSmokeAsyncEcho(value: Long): Long =
    iroh { c -> iroh4k_smoke_async_echo(value, c, completion) }.longOrThrow()

internal actual suspend fun nativeSmokeAsyncError(): Nothing {
    iroh { c -> iroh4k_smoke_async_error(c, completion) }.orThrow()
    error("iroh4k_smoke_async_error must always fail")
}

internal actual suspend fun nativeSmokeAsyncSleep(millis: Long): Long =
    iroh { c -> iroh4k_smoke_async_sleep(millis, c, completion) }.longOrThrow()
