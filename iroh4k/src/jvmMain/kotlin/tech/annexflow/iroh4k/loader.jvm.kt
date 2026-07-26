package tech.annexflow.iroh4k

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val CRATE = "iroh4k"

/** Set by the `rustJni` Gradle plugin for `jvmTest`; absent in a published jar. */
private const val NATIVE_PATH_PROPERTY = "$CRATE.native.path"

private val nativeLoaded = AtomicBoolean(false)

internal actual fun ensureNativeLoaded() {
    if (!nativeLoaded.compareAndSet(false, true)) return
    if (!tryLoadFromProperty()) loadFromClasspath()
}

/**
 * The resource/file name the `rustJni` plugin produces for this host. arm64 builds carry an
 * `_aarch64` suffix so one jar can hold every desktop host's library without collision.
 */
private fun hostNativeLibName(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
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

/** Loads the host library from the directory the Gradle plugin points at, under test. */
private fun tryLoadFromProperty(): Boolean {
    val path = System.getProperty(NATIVE_PATH_PROPERTY) ?: return false
    val src = File(path, hostNativeLibName())
    require(src.exists()) { "iroh4k native library not found at ${src.absolutePath}" }
    System.load(copyToTempFile(src.readBytes(), src.name).absolutePath)
    return true
}

/** Extracts the library from the jar and loads it. */
private fun loadFromClasspath() {
    val name = hostNativeLibName()
    val bytes = Iroh4kJni::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
        ?: error(
            "iroh4k native library '$name' not found on the classpath. This host " +
                    "(${System.getProperty("os.name")}/${System.getProperty("os.arch")}) may not " +
                    "be bundled in this artifact."
        )
    System.load(copyToTempFile(bytes, name).absolutePath)
}

/**
 * Copies to a unique temp file before loading. `System.load`ing the *same* file from two
 * classloaders throws "Native Library already loaded in another classloader", which sandboxed
 * test runners hit; distinct copies avoid it.
 */
private fun copyToTempFile(bytes: ByteArray, name: String): File {
    val ext = name.substringAfterLast('.', "so")
    return File.createTempFile(CRATE, ".$ext")
        .also { it.deleteOnExit(); it.writeBytes(bytes) }
}
