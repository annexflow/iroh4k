package tech.annexflow.iroh4k

/**
 * The three cryptographic identity types: [SecretKey], [EndpointId] and [Signature].
 *
 * All three are **value types**. iroh-ffi models them as opaque objects with an FFI lifetime;
 * here they are ordinary Kotlin classes holding raw bytes, because a key owns no resources and has
 * no identity beyond its contents. There is nothing to close and nothing to leak.
 *
 * Only what genuinely needs iroh crosses the boundary — generation, derivation, signing,
 * verification, z-base-32 coding and validation. Everything else is Kotlin. See `keys.rs` for the
 * Rust half of the split.
 */

/** Lowercase hex digits, for the pure-formatting conversions below. */
private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Lowercase hex, the form iroh's `Display` uses for a signature.
 *
 * Kept in Kotlin deliberately: hex involves no crypto and no iroh type, so an FFI round-trip
 * would buy nothing. Contrast [EndpointId.fmtShort], which crosses because iroh is the one that
 * decides what an endpoint id's short form is.
 */
private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

/**
 * The secret half of an endpoint identity: 32 raw Ed25519 bytes.
 *
 * Construct one with [generate] or [fromBytes]; the constructor is private so an instance can
 * only ever hold bytes iroh has accepted.
 */
class SecretKey private constructor(private val bytes: ByteArray) {

    /**
     * The 32 raw bytes.
     *
     * A fresh copy every time: `ByteArray` is mutable, and handing out the backing array would
     * let a caller silently change a key that other code already holds.
     */
    fun toBytes(): ByteArray = bytes.copyOf()

    /**
     * The [EndpointId] this secret key identifies.
     *
     * Deterministic — the same secret always derives the same id — which is why an endpoint keeps
     * its identity across restarts as long as it keeps its secret key.
     */
    fun public(): EndpointId = EndpointId.validated(nativeKeysSecretPublic(bytes))

    /** Signs [message], producing a 64-byte Ed25519 [Signature]. An empty message is allowed. */
    fun sign(message: ByteArray): Signature =
        Signature.validated(nativeKeysSecretSign(bytes, message))

    // Content equality, hand-written. `ByteArray.equals` is referential, so a `data class` holding
    // one would report two keys with identical bytes as different — the generated `equals` and
    // `hashCode` would be quietly wrong for exactly the comparison anybody would want to make.
    //
    // Note this is not a constant-time comparison. Comparing two secret keys is a bookkeeping
    // question ("is this the key I already have?"), never a security check, so there is no secret
    // for the timing to leak to an attacker who is already holding both keys.
    override fun equals(other: Any?): Boolean =
        this === other || (other is SecretKey && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    /**
     * Never renders the key material, mirroring iroh's own `Debug`. A secret key that prints
     * itself ends up in a log file.
     */
    override fun toString(): String = "SecretKey(..)"

    companion object {
        /** Generates a new random secret key. */
        fun generate(): SecretKey = SecretKey(nativeKeysSecretGenerate())

        /**
         * Wraps 32 raw bytes.
         *
         * @throws IrohError with [IrohError.Code.Key] if [bytes] is not exactly 32 bytes long.
         */
        fun fromBytes(bytes: ByteArray): SecretKey = SecretKey(nativeKeysSecretFromBytes(bytes))
    }
}

/**
 * An endpoint's identifier: the 32-byte Ed25519 public key that globally names an endpoint and
 * that all traffic to it is encrypted for.
 *
 * Not every 32-byte string is one — the bytes have to decompress to a point on the curve — so
 * [fromBytes] and [fromString] validate through iroh and an instance is always well-formed.
 */
class EndpointId private constructor(private val bytes: ByteArray) {

    /**
     * The textual form, computed once.
     *
     * Cached because [toString] is expected to be cheap — it is called from string templates and
     * log statements — and the encoding cannot change for a given id. The bytes were validated at
     * construction, so the crossing here cannot fail.
     */
    private val text: String by lazy { nativeKeysEndpointIdToString(bytes).decodeToString() }

    /** The 32 raw bytes, as a fresh copy — see [SecretKey.toBytes]. */
    fun toBytes(): ByteArray = bytes.copyOf()

    /**
     * iroh's short form: the hex of the first five bytes, enough to recognise an endpoint in a log
     * line. Delegates to iroh so the two never disagree on what "short" means.
     */
    fun fmtShort(): String = nativeKeysEndpointIdFmtShort(bytes).decodeToString()

    /**
     * Whether [signature] is a valid signature over [message] by this endpoint.
     *
     * A signature that does not verify returns `false` rather than throwing: that is the expected
     * answer to a legitimate question, and an exception would push callers towards catching it and
     * getting the control flow wrong. [IrohError] is reserved for input that cannot be parsed at
     * all, which cannot happen here because both arguments are already validated types.
     */
    fun verify(message: ByteArray, signature: Signature): Boolean =
        nativeKeysEndpointIdVerify(bytes, message, signature.raw())

    // Content equality, for the reason given on [SecretKey.equals].
    override fun equals(other: Any?): Boolean =
        this === other || (other is EndpointId && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    /**
     * iroh's textual form: **lowercase hex**, which [fromString] parses back.
     *
     * This is iroh's own `Display`, so an id printed here is byte-for-byte the id printed by iroh,
     * iroh-ffi, and iroh's logs. That matters for more than tidiness — endpoint ids are copied
     * between tools and embedded in tickets, so a different spelling here would be a silent
     * interop break. For the DNS form used by pkarr, ask for it explicitly with [toZ32].
     */
    override fun toString(): String = text

    /**
     * The [z-base-32](https://pkarr.org) form, the encoding iroh uses for endpoint ids inside DNS
     * names. Parsed back by [fromZ32].
     */
    fun toZ32(): String = nativeKeysEndpointIdToZ32(bytes).decodeToString()

    companion object {
        /**
         * Wraps 32 raw bytes.
         *
         * @throws IrohError with [IrohError.Code.Key] if [bytes] is not exactly 32 bytes long, or
         *   if it is not a valid Ed25519 public key.
         */
        fun fromBytes(bytes: ByteArray): EndpointId =
            EndpointId(nativeKeysEndpointIdFromBytes(bytes))

        /**
         * Parses the textual form produced by [toString].
         *
         * Accepts **hex or base32**, matching iroh's own `FromStr`, so an id taken from iroh's
         * output — or from another iroh binding — parses here without the caller having to know
         * which encoding produced it.
         *
         * @throws IrohError with [IrohError.Code.Key] if [text] is not a valid endpoint id.
         */
        fun fromString(text: String): EndpointId =
            EndpointId(nativeKeysEndpointIdFromString(text))

        /**
         * Parses the [z-base-32](https://pkarr.org) form produced by [toZ32].
         *
         * @throws IrohError with [IrohError.Code.Key] if [text] is not valid z-base-32 for a valid
         *   endpoint id.
         */
        fun fromZ32(text: String): EndpointId =
            EndpointId(nativeKeysEndpointIdFromZ32(text))

        /**
         * Wraps bytes iroh itself just produced, skipping the validating round trip.
         *
         * Used for the outputs of operations that already return a canonical endpoint id — there
         * is nothing left to check, and re-checking would double the boundary crossings.
         */
        internal fun validated(bytes: ByteArray): EndpointId = EndpointId(bytes)
    }
}

/** An Ed25519 signature over a message: 64 raw bytes. */
class Signature private constructor(private val bytes: ByteArray) {

    /** The 64 raw bytes, as a fresh copy — see [SecretKey.toBytes]. */
    fun toBytes(): ByteArray = bytes.copyOf()

    /**
     * The backing array itself, for callers inside this module that only read it.
     *
     * Exists so [EndpointId.verify] does not copy 64 bytes on every call. Internal precisely
     * because the no-mutation rule cannot be enforced, only observed.
     */
    internal fun raw(): ByteArray = bytes

    // Content equality, for the reason given on [SecretKey.equals].
    override fun equals(other: Any?): Boolean =
        this === other || (other is Signature && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    /** Lowercase hex, matching iroh's `Display`. */
    override fun toString(): String = bytes.toHex()

    companion object {
        /**
         * Wraps 64 raw bytes.
         *
         * @throws IrohError with [IrohError.Code.Key] if [bytes] is not exactly 64 bytes long.
         *   Whether the signature is *correct* is a separate question, answered by
         *   [EndpointId.verify].
         */
        fun fromBytes(bytes: ByteArray): Signature =
            Signature(nativeKeysSignatureFromBytes(bytes))

        /** Wraps bytes iroh itself just produced — see [EndpointId.validated]. */
        internal fun validated(bytes: ByteArray): Signature = Signature(bytes)
    }
}

// ── The keys domain's FFI surface, implemented per facade ─────────────────────────────────────
//
// Names are prefixed `nativeKeys` so each domain's expect declarations stay distinct in this
// shared package.

internal expect fun nativeKeysSecretGenerate(): ByteArray

internal expect fun nativeKeysSecretFromBytes(secret: ByteArray): ByteArray

internal expect fun nativeKeysSecretPublic(secret: ByteArray): ByteArray

internal expect fun nativeKeysSecretSign(secret: ByteArray, message: ByteArray): ByteArray

internal expect fun nativeKeysEndpointIdFromBytes(id: ByteArray): ByteArray

internal expect fun nativeKeysEndpointIdFromString(text: String): ByteArray

internal expect fun nativeKeysEndpointIdToString(id: ByteArray): ByteArray

internal expect fun nativeKeysEndpointIdToZ32(id: ByteArray): ByteArray

internal expect fun nativeKeysEndpointIdFromZ32(text: String): ByteArray

internal expect fun nativeKeysEndpointIdFmtShort(id: ByteArray): ByteArray

internal expect fun nativeKeysEndpointIdVerify(
    id: ByteArray,
    message: ByteArray,
    signature: ByteArray,
): Boolean

internal expect fun nativeKeysSignatureFromBytes(signature: ByteArray): ByteArray
