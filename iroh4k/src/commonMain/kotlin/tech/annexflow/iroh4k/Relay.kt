package tech.annexflow.iroh4k

import tech.annexflow.iroh4k.internal.BinaryReader

/**
 * A validated relay URL.
 *
 * Instances only exist for strings iroh itself accepted, and always hold iroh's **canonical**
 * spelling of the URL. That distinction matters: `https://example.com` and `https://example.com/`
 * are the same relay to iroh, so canonicalising on the way in is what lets [RelayMap] compare and
 * de-duplicate relays the way the endpoint eventually will.
 *
 * Parsing is deliberately delegated to Rust rather than reimplemented here — see
 * [RelayUrl.parse].
 */
class RelayUrl private constructor(private val canonical: String) {

    /** The canonical URL, exactly as iroh spells it. */
    override fun toString(): String = canonical

    override fun equals(other: Any?): Boolean = other is RelayUrl && other.canonical == canonical

    override fun hashCode(): Int = canonical.hashCode()

    companion object {
        /**
         * Parses [url] as a relay URL, returning it in canonical form.
         *
         * The work happens in Rust, in `iroh::RelayUrl`'s own `FromStr`, so what iroh4k accepts is
         * by construction what iroh accepts — a Kotlin-side URL parser would be a second opinion
         * that could only drift from the one that matters.
         *
         * @throws IrohError with [IrohError.Code.Relay] if [url] is not a valid relay URL.
         */
        fun parse(url: String): RelayUrl = RelayUrl(nativeRelayUrlParse(url))

        /**
         * Wraps a string that is *already* canonical because iroh produced it.
         *
         * Only for the built-in relay lists, which come straight out of `iroh::defaults`. Re-parsing
         * those would be a pointless round trip across the FFI boundary per URL.
         */
        internal fun trusted(canonical: String): RelayUrl = RelayUrl(canonical)
    }
}

/**
 * An ordered collection of relay servers an endpoint should consider.
 *
 * **Immutable.** [insert] and [remove] return a new map and leave the receiver untouched, unlike
 * iroh's own `RelayMap` (an `Arc<RwLock<..>>` mutated in place) and iroh-ffi's object wrapper
 * around it. Two reasons:
 *
 * - A relay map is configuration handed to an endpoint at construction time. Shared mutable
 *   configuration is a race waiting to happen — one caller inserting a relay while another reads
 *   the map to build an endpoint — and making it a value removes the possibility rather than
 *   documenting it away.
 * - Because it holds no native state, it can be an ordinary Kotlin value with structural equality
 *   and a useful [toString]. Nothing here needs a handle, a `close()`, or a lifetime.
 *
 * Iteration order (and so [urls]) is insertion order, which keeps output deterministic. Equality
 * ignores order: two maps listing the same relays describe the same configuration, which is also
 * how iroh compares its own relay maps (keyed in a `BTreeMap` by URL).
 */
class RelayMap private constructor(private val relays: Set<RelayUrl>) {

    /** The relays in this map, in insertion order. */
    val urls: List<RelayUrl> get() = relays.toList()

    /** How many relays this map holds. */
    val size: Int get() = relays.size

    /** True when this map holds no relays. */
    fun isEmpty(): Boolean = relays.isEmpty()

    /** True when [url] is one of this map's relays. */
    operator fun contains(url: RelayUrl): Boolean = url in relays

    /**
     * This map plus [url].
     *
     * Inserting a relay already present returns the receiver: a relay map is a set of relays, so a
     * duplicate is not an error and must not grow the map or reorder it.
     */
    fun insert(url: RelayUrl): RelayMap =
        if (url in relays) this else RelayMap(LinkedHashSet(relays).apply { add(url) })

    /** This map without [url], or the receiver unchanged if it was not present. */
    fun remove(url: RelayUrl): RelayMap =
        if (url in relays) RelayMap(LinkedHashSet(relays).apply { remove(url) }) else this

    override fun equals(other: Any?): Boolean = other is RelayMap && other.relays == relays

    override fun hashCode(): Int = relays.hashCode()

    override fun toString(): String = relays.joinToString(prefix = "RelayMap([", postfix = "])")

    companion object {
        private val EMPTY = RelayMap(emptySet())

        /** A map with no relays. */
        fun empty(): RelayMap = EMPTY

        /**
         * A map holding the relays named by [urls], each parsed with [RelayUrl.parse].
         *
         * @throws IrohError with [IrohError.Code.Relay] if any entry is not a valid relay URL.
         */
        fun fromUrls(urls: List<String>): RelayMap = of(urls.map { RelayUrl.parse(it) })

        /** A map holding [urls], de-duplicated, in the order given. */
        fun of(urls: List<RelayUrl>): RelayMap =
            if (urls.isEmpty()) EMPTY else RelayMap(LinkedHashSet(urls))

        /** Builds a map from canonical strings iroh produced — see [RelayUrl.trusted]. */
        internal fun fromCanonical(urls: List<String>): RelayMap =
            of(urls.map { RelayUrl.trusted(it) })
    }
}

/**
 * Which relay servers an endpoint uses.
 *
 * Mirrors `iroh::RelayMode`, as a plain sealed hierarchy rather than an opaque native object: the
 * variants carry no state beyond a [RelayMap], which is itself a Kotlin value.
 */
sealed interface RelayMode {

    /**
     * The relays this mode resolves to.
     *
     * Empty for [Disabled]; the built-in n0 lists for [Default] and [Staging]; the configured map
     * for [Custom].
     */
    fun relayMap(): RelayMap

    /** No relays at all — neither dialing nor listening via a relay is possible. */
    data object Disabled : RelayMode {
        override fun relayMap(): RelayMap = RelayMap.empty()
    }

    /** The n0 production relay fleet. */
    data object Default : RelayMode {
        override fun relayMap(): RelayMap = defaultRelayMap
    }

    /**
     * The n0 staging relay fleet. Used by iroh's own tests and may deploy incompatible changes,
     * so it is not a drop-in substitute for [Default].
     */
    data object Staging : RelayMode {
        override fun relayMap(): RelayMap = stagingRelayMap
    }

    /** An explicitly configured set of relays. */
    data class Custom(val map: RelayMap) : RelayMode {
        override fun relayMap(): RelayMap = map
    }

    companion object {
        /**
         * A [Custom] mode over the relays named by [urls].
         *
         * @throws IrohError with [IrohError.Code.Relay] if any entry is not a valid relay URL.
         */
        fun customFromUrls(urls: List<String>): RelayMode = Custom(RelayMap.fromUrls(urls))
    }
}

/**
 * The built-in relay lists, fetched from `iroh::defaults` on first use.
 *
 * Read across the FFI boundary rather than hardcoded here on purpose: the n0 fleet is iroh's to
 * define, and a Kotlin copy would keep compiling while quietly pointing at retired servers. They
 * are constant for a given build, so one lazy fetch each is enough.
 */
private val defaultRelayMap: RelayMap by lazy {
    RelayMap.fromCanonical(decodeUrls(nativeRelayDefaultUrls()))
}

private val stagingRelayMap: RelayMap by lazy {
    RelayMap.fromCanonical(decodeUrls(nativeRelayStagingUrls()))
}

/** Decodes a codec sequence of strings, the shape `relay.rs`'s `urls_payload` writes. */
private fun decodeUrls(payload: ByteArray): List<String> =
    BinaryReader(payload).seq { it.string() }

/**
 * How much iroh logs.
 *
 * The **ordinals** are the wire protocol shared with `relay.rs`'s `level_filter`, exactly as
 * [IrohError.Code]'s are with the Rust `ERROR_*` constants: do not reorder, append only.
 */
enum class LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
    Off,
}

/**
 * Sets the level at which iroh's internals log, process-wide.
 *
 * Safe to call more than once. The first call installs Rust's `tracing` subscriber and later calls
 * retune the live one, so the level really does change rather than the second call being ignored —
 * see `relay.rs`'s `set_log_level` for why that needed care (a naive second install panics, and
 * the Rust crate aborts on panic).
 *
 * Where the output goes is the platform's business: stdout on the JVM and desktop targets, logcat
 * on Android, the Xcode console on iOS. If the host process already installed its own `tracing`
 * subscriber, that one keeps ownership and this call has no effect.
 */
fun setLogLevel(level: LogLevel) {
    nativeSetLogLevel(level.ordinal)
}

internal expect fun nativeRelayUrlParse(url: String): String

internal expect fun nativeRelayDefaultUrls(): ByteArray

internal expect fun nativeRelayStagingUrls(): ByteArray

internal expect fun nativeSetLogLevel(level: Int)
