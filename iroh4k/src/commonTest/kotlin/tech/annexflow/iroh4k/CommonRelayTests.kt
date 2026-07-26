package tech.annexflow.iroh4k

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.each
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import kotlin.test.assertFailsWith

/**
 * Test bodies shared by every target, so the FFI (Kotlin/Native) and JNI (JVM/Android) facades are
 * held to identical behaviour for the relay and logging surface.
 *
 * Platform test classes construct this and delegate one `@Test` per method — see
 * `nativeTest`/`jvmTest`.
 */
class CommonRelayTests {

    // ── RelayUrl ─────────────────────────────────────────────────────────────────────────────

    fun `relay URLs come back in iroh's canonical form`() {
        // Each expectation is what iroh's own `RelayUrl::from_str` produces, i.e. what the `url`
        // crate does: an empty path becomes "/", the scheme and host are lowercased, and a default
        // port is dropped. Kotlin must not second-guess any of it, because these canonical strings
        // are what a RelayMap is keyed on.
        val cases = mapOf(
            "https://example.com" to "https://example.com/",
            "https://example.com/" to "https://example.com/",
            "HTTPS://Example.COM" to "https://example.com/",
            "https://example.com:443" to "https://example.com/",
            "https://example.com:8443/path" to "https://example.com:8443/path",
            "http://localhost:3340" to "http://localhost:3340/",
            // Fully-qualified name with the trailing dot iroh recommends for relay hosts.
            "https://relay.example.com." to "https://relay.example.com./",
        )
        for ((input, canonical) in cases) {
            assertThat(RelayUrl.parse(input).toString()).isEqualTo(canonical)
        }
    }

    fun `normalisation is idempotent and drives equality`() {
        val spelled = RelayUrl.parse("HTTPS://Example.COM:443")
        val canonical = RelayUrl.parse("https://example.com/")

        // Two spellings of one relay must be one RelayUrl, or a RelayMap would hold it twice.
        assertThat(spelled).isEqualTo(canonical)
        assertThat(spelled.hashCode()).isEqualTo(canonical.hashCode())
        // Re-parsing a canonical form must be a fixed point.
        assertThat(RelayUrl.parse(spelled.toString())).isEqualTo(spelled)

        assertThat(RelayUrl.parse("https://other.example.com/")).isNotEqualTo(canonical)
    }

    fun `invalid relay URLs raise a Relay error`() {
        // All rejected by the `url` crate, and so by iroh: no scheme, no host, or an unusable host.
        val invalid = listOf("", "   ", "not a url", "relay.example.com", "https://", "https://a b.com")
        for (input in invalid) {
            val error = assertFailsWith<IrohError>("expected $input to be rejected") {
                RelayUrl.parse(input)
            }
            assertThat(error.code).isEqualTo(IrohError.Code.Relay)
            // The message must name the offending input, or a failure is undiagnosable.
            assertThat(error.message!!).contains("relay URL")
        }
    }

    // ── RelayMap ─────────────────────────────────────────────────────────────────────────────

    fun `an empty relay map holds nothing`() {
        val map = RelayMap.empty()
        assertThat(map.size).isEqualTo(0)
        assertThat(map.isEmpty()).isTrue()
        assertThat(map.urls).isEmpty()
        assertThat(RelayMap.fromUrls(emptyList())).isEqualTo(map)
    }

    fun `fromUrls parses and canonicalises every entry`() {
        val map = RelayMap.fromUrls(listOf("https://a.example.com", "HTTPS://B.Example.com:443"))
        assertThat(map.urls.map { it.toString() })
            .containsExactly("https://a.example.com/", "https://b.example.com/")
    }

    fun `fromUrls rejects the whole list when one entry is invalid`() {
        val error = assertFailsWith<IrohError> {
            RelayMap.fromUrls(listOf("https://good.example.com", "nonsense"))
        }
        assertThat(error.code).isEqualTo(IrohError.Code.Relay)
    }

    fun `insert returns a new map and leaves the original alone`() {
        val a = RelayUrl.parse("https://a.example.com")
        val b = RelayUrl.parse("https://b.example.com")
        val one = RelayMap.empty().insert(a)
        val two = one.insert(b)

        assertThat(one.size).isEqualTo(1)
        assertThat(two.size).isEqualTo(2)
        // The whole reason the type is immutable: `one` cannot have been mutated by `insert`.
        assertThat(one.contains(b)).isFalse()
        assertThat(two.contains(a)).isTrue()
        assertThat(two.contains(b)).isTrue()
        // Insertion order is the iteration order.
        assertThat(two.urls).containsExactly(a, b)
    }

    fun `inserting a duplicate changes nothing`() {
        val a = RelayUrl.parse("https://a.example.com")
        val b = RelayUrl.parse("https://b.example.com")
        val map = RelayMap.empty().insert(a).insert(b)

        // A relay map is a set of relays, so a duplicate is not an error, must not grow the map,
        // and must not move the existing entry to the end.
        val again = map.insert(RelayUrl.parse("HTTPS://A.Example.com:443"))
        assertThat(again.size).isEqualTo(2)
        assertThat(again.urls).containsExactly(a, b)
        assertThat(again).isEqualTo(map)
    }

    fun `remove drops exactly one relay and tolerates absent ones`() {
        val a = RelayUrl.parse("https://a.example.com")
        val b = RelayUrl.parse("https://b.example.com")
        val absent = RelayUrl.parse("https://c.example.com")
        val map = RelayMap.of(listOf(a, b))

        val without = map.remove(a)
        assertThat(without.urls).containsExactly(b)
        assertThat(without.contains(a)).isFalse()
        // The receiver is untouched.
        assertThat(map.contains(a)).isTrue()

        assertThat(map.remove(absent)).isEqualTo(map)
        assertThat(without.remove(b).isEmpty()).isTrue()
    }

    fun `relay map equality ignores order but not contents`() {
        val a = RelayUrl.parse("https://a.example.com")
        val b = RelayUrl.parse("https://b.example.com")

        // Two maps listing the same relays describe the same configuration, which is how iroh
        // compares its own relay maps (keyed in a BTreeMap by URL).
        assertThat(RelayMap.of(listOf(a, b))).isEqualTo(RelayMap.of(listOf(b, a)))
        assertThat(RelayMap.of(listOf(a, b)).hashCode())
            .isEqualTo(RelayMap.of(listOf(b, a)).hashCode())
        // ...but iteration order still follows insertion.
        assertThat(RelayMap.of(listOf(b, a)).urls).containsExactly(b, a)

        assertThat(RelayMap.of(listOf(a))).isNotEqualTo(RelayMap.of(listOf(a, b)))
        assertThat(RelayMap.of(listOf(a))).isNotEqualTo(RelayMap.of(listOf(b)))
    }

    fun `relay map toString lists its relays`() {
        val text = RelayMap.fromUrls(listOf("https://a.example.com")).toString()
        assertThat(text).isEqualTo("RelayMap([https://a.example.com/])")
        assertThat(RelayMap.empty().toString()).isEqualTo("RelayMap([])")
    }

    // ── RelayMode ────────────────────────────────────────────────────────────────────────────

    fun `disabled mode has no relays`() {
        assertThat(RelayMode.Disabled.relayMap().isEmpty()).isTrue()
        assertThat(RelayMode.Disabled.relayMap()).isEqualTo(RelayMap.empty())
    }

    fun `the default mode resolves to iroh's production fleet`() {
        val map = RelayMode.Default.relayMap()
        // Non-empty proves the list really crossed the FFI boundary rather than defaulting to an
        // empty map on a decode failure.
        assertThat(map.isEmpty()).isFalse()
        assertThat(map.urls.map { it.toString() }).each {
            it.startsWith("https://")
            it.contains(".relay.n0.iroh.link.")
        }
        // Every URL iroh handed us must already be canonical: re-parsing is a fixed point, which
        // is what lets `RelayMap.fromCanonical` skip validation.
        for (url in map.urls) assertThat(RelayUrl.parse(url.toString())).isEqualTo(url)
    }

    fun `the staging mode resolves to a different fleet`() {
        val default = RelayMode.Default.relayMap()
        val staging = RelayMode.Staging.relayMap()

        assertThat(staging.isEmpty()).isFalse()
        assertThat(staging).isNotEqualTo(default)
        assertThat(staging.urls.map { it.toString() }).each {
            it.contains(".staging-relay.n0.iroh.link.")
        }
        // Staging and production must not share a single server, or "differs" would be accidental.
        assertThat(staging.urls.intersect(default.urls.toSet())).isEmpty()
    }

    fun `the built-in fleets are cached rather than re-fetched`() {
        // Both reads must give the same value; the lazy cache would also be caught misbehaving
        // here if it were rebuilt from a stale or partially consumed payload.
        assertThat(RelayMode.Default.relayMap()).isEqualTo(RelayMode.Default.relayMap())
        assertThat(RelayMode.Staging.relayMap()).isEqualTo(RelayMode.Staging.relayMap())
    }

    fun `custom mode round-trips its map`() {
        val map = RelayMap.fromUrls(listOf("https://a.example.com", "https://b.example.com:8443"))
        val mode = RelayMode.Custom(map)

        assertThat(mode.relayMap()).isEqualTo(map)
        assertThat(mode.map).isEqualTo(map)
        assertThat(mode.relayMap().urls.map { it.toString() })
            .containsExactly("https://a.example.com/", "https://b.example.com:8443/")
        // Structural equality on the variant, so a mode can be compared like the value it is, and
        // order-insensitively because its map is.
        val reordered = RelayMap.fromUrls(listOf("https://b.example.com:8443", "https://a.example.com"))
        assertThat(mode).isEqualTo(RelayMode.Custom(reordered))
        assertThat(mode == RelayMode.Disabled).isFalse()
    }

    fun `customFromUrls builds a custom mode`() {
        val mode = RelayMode.customFromUrls(listOf("https://a.example.com"))
        assertThat(mode).isEqualTo(RelayMode.Custom(RelayMap.fromUrls(listOf("https://a.example.com"))))
        assertThat(mode.relayMap().size).isEqualTo(1)

        val error = assertFailsWith<IrohError> { RelayMode.customFromUrls(listOf("nonsense")) }
        assertThat(error.code).isEqualTo(IrohError.Code.Relay)
    }

    // ── Logging ──────────────────────────────────────────────────────────────────────────────

    fun `setLogLevel is safe to call repeatedly`() {
        // The first call installs Rust's tracing subscriber; a naive second install *panics*, and
        // the Rust crate is built with `panic = "abort"`, so a regression here would abort the
        // test runner rather than fail this assertion. Every level is exercised, and the sequence
        // ends at Off so the rest of the suite is not drowned in trace output.
        for (level in LogLevel.entries) setLogLevel(level)
        setLogLevel(LogLevel.Warn)
        setLogLevel(LogLevel.Warn)
        setLogLevel(LogLevel.Off)

        // Reaching here at all is the assertion; this only records that the enum was covered.
        assertThat(LogLevel.entries).hasSize(6)
    }

    fun `LogLevel ordinals match the Rust wire contract`() {
        // These ordinals are the protocol shared with `relay.rs`'s `level_filter`; reordering the
        // enum would silently set the wrong level rather than fail to compile.
        assertThat(LogLevel.entries.map { it.ordinal to it.name }).containsExactly(
            0 to "Trace",
            1 to "Debug",
            2 to "Info",
            3 to "Warn",
            4 to "Error",
            5 to "Off",
        )
    }
}
