@file:OptIn(ExperimentalForeignApi::class)

package tech.annexflow.iroh4k

import iroh4k.ffi.iroh4k_relay_default_urls
import iroh4k.ffi.iroh4k_relay_staging_urls
import iroh4k.ffi.iroh4k_relay_url_parse
import iroh4k.ffi.iroh4k_set_log_level
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * The relay surface over the C ABI.
 *
 * cinterop maps the `const char *` parameter to a Kotlin `String`, copying it into a temporary
 * nul-terminated UTF-8 buffer that lives for the duration of the call. That is exactly as long as
 * Rust reads it: `iroh4k_relay_url_parse` borrows the argument only to parse it and returns the
 * canonical form as a freshly owned allocation.
 */
internal actual fun nativeRelayUrlParse(url: String): String =
    iroh4k_relay_url_parse(url).bytesOrThrow().decodeToString()

internal actual fun nativeRelayDefaultUrls(): ByteArray =
    iroh4k_relay_default_urls().bytesOrThrow()

internal actual fun nativeRelayStagingUrls(): ByteArray =
    iroh4k_relay_staging_urls().bytesOrThrow()

internal actual fun nativeSetLogLevel(level: Int) {
    iroh4k_set_log_level(level).orThrow()
}
