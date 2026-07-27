package tech.annexflow.iroh4k

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val CRATE = "iroh4k"

/**
 * Set by the `rustJni` Gradle plugin for the host tests (its `Test` task filter matches any task
 * whose name contains `HostTest`, which is what AGP calls the Android host-test task). Absent on a
 * device, where nothing outside the build ever sets it.
 */
private const val NATIVE_PATH_PROPERTY = "$CRATE.native.path"

private val nativeLoaded = AtomicBoolean(false)

/**
 * Loads the `iroh4k` shared library on Android.
 *
 * On a device or emulator this is `System.loadLibrary`, and nothing else would work: the `.so` is
 * packaged into the AAR under `jni/<abi>/`, unpacked by the platform installer, and only the
 * runtime knows which ABI's copy this device got. That is the branch consumers take.
 *
 * It is not *only* `loadLibrary` because the Robolectric tests in `src/androidHostTest` run on the
 * host JVM, not on a device. There is no APK there and no `jni/<abi>/` to unpack, so
 * `System.loadLibrary("iroh4k")` finds nothing on `java.library.path` and fails; what does exist
 * is the host `dylib`/`so`/`dll` the `rustJni` plugin builds and points at through
 * [NATIVE_PATH_PROPERTY]. The property is therefore tried first and the device path is the
 * fallback: on a device the property is absent and the first branch is skipped in a single
 * `getProperty` call.
 *
 * This deliberately duplicates a little of `jvmMain`'s loader instead of sharing it. The two live
 * in sibling source sets — neither compilation sees the other — and they are not the same
 * function: the JVM's fallback is to extract the library from the jar, Android's is to ask the
 * platform for the one it already unpacked. Only the host-test branch overlaps, and hoisting that
 * into `jniMain` would mean the shared code carrying a loader for a case that exists solely to
 * make tests run.
 */
internal actual fun ensureNativeLoaded() {
    if (!nativeLoaded.compareAndSet(false, true)) return
    if (!tryLoadFromProperty()) System.loadLibrary(CRATE)
}

/**
 * Loads the host library from the directory the Gradle plugin points at, under host tests.
 * Returns false when the property is absent, which is the device case.
 */
private fun tryLoadFromProperty(): Boolean {
    val path = System.getProperty(NATIVE_PATH_PROPERTY) ?: return false
    val src = File(path, hostNativeLibName())
    require(src.exists()) { "iroh4k native library not found at ${src.absolutePath}" }
    System.load(copyToTempFile(src.readBytes(), src.name).absolutePath)
    return true
}

/**
 * The file name the `rustJni` plugin produces for the *host* — this is only ever reached under
 * host tests, so the JVM naming applies: arm64 builds carry an `_aarch64` suffix so one directory
 * can hold every desktop host's library without collision.
 */
private fun hostNativeLibName(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    val isWindows = os.contains("windows")
    val isMac = os.contains("mac") || os.contains("darwin")
    val prefix = if (isWindows) "" else "lib"
    val ext = when {
        isMac -> "dylib"
        isWindows -> "dll"
        else -> "so"
    }
    val suffix = if (arch == "aarch64" || arch == "arm64") "_aarch64" else ""
    return "$prefix$CRATE$suffix.$ext"
}

/**
 * Copies to a unique temp file before loading. `System.load`ing the *same* file from two
 * classloaders throws "Native Library already loaded in another classloader", and Robolectric
 * gives every test class its own sandbox classloader — so without this, the second test class in
 * a run would fail. Distinct copies avoid it.
 */
private fun copyToTempFile(bytes: ByteArray, name: String): File {
    val ext = name.substringAfterLast('.', "so")
    return File.createTempFile(CRATE, ".$ext")
        .also { it.deleteOnExit(); it.writeBytes(bytes) }
}
