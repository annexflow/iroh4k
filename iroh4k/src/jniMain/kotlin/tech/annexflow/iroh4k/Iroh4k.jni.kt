package tech.annexflow.iroh4k

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope




internal actual fun nativeVersion(): String =
    Iroh4kJni.version().jniBytesOrThrow().decodeToString()

internal actual fun nativeSmokeRecord(): ByteArray =
    Iroh4kJni.smokeRecord().jniBytesOrThrow()

internal actual fun nativeLiveOpCount(): Long = Iroh4kJni.liveOpCount()

internal actual fun nativeSmokeSleepCompletions(): Long = Iroh4kJni.smokeSleepCompletions()

internal actual suspend fun nativeSmokeAsyncEcho(value: Long): Long =
    jniOp({ Iroh4kJni.smokeAsyncEchoStart(value) }) { it.longValue }

internal actual suspend fun nativeSmokeAsyncError(): Nothing {
    jniOp({ Iroh4kJni.smokeAsyncErrorStart() }) { }
    error("smokeAsyncError must always fail")
}

internal actual suspend fun nativeSmokeAsyncSleep(millis: Long): Long =
    jniOp({ Iroh4kJni.smokeAsyncSleepStart(millis) }) { it.longValue }
