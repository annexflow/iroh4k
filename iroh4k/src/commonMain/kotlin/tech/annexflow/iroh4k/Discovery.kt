package tech.annexflow.iroh4k

import tech.annexflow.iroh4k.internal.BinaryWriter

/**
 * One address lookup service: how this endpoint publishes where it can be reached, and how it
 * resolves an [EndpointId] into somewhere to send a packet.
 *
 * A list of these is what `AddressLookupServices` is in iroh, which is why [EndpointConfig.discovery]
 * is a list rather than a set of fields. Each variant is inert data, read once when the endpoint
 * binds.
 *
 * mDNS is deliberately *not* a variant here — see [EndpointConfig.mdns]. It points at no server and
 * no preset installs it, so it composes with a preset instead of replacing it, which is the opposite
 * of what everything in this list does.
 */
sealed interface Discovery {

    /**
     * Publishes this endpoint's addresses to a pkarr relay, as a signed packet under its
     * [EndpointId].
     *
     * ## What gets published, and the way it can silently be nothing
     *
     * [published] defaults to [PublishedAddrs.RelayOnly], which is upstream's default and is there
     * to avoid handing IP addresses to a *public* pkarr server. That reasoning inverts as soon as
     * the server is your own, and the combination worth knowing about is this one: with
     * [RelayMode.Disabled] there are no relay addresses to publish, so a `RelayOnly` publisher
     * signs and uploads a packet containing **no addresses at all**. The endpoint binds, the
     * publisher reports nothing wrong, and no peer can ever resolve it. Pass
     * [PublishedAddrs.IpOnly] or [PublishedAddrs.Unfiltered] when you run the relay yourself.
     *
     * @property relayUrl the pkarr relay to publish to, or `null` for n0's own — which is what
     *   [n0] produces. There is no default: publishing this endpoint's addresses to somebody
     *   else's server should not be what happens when an argument is forgotten.
     * @property published which of this endpoint's addresses may go into the packet.
     */
    class PkarrPublisher(
        val relayUrl: String?,
        val published: PublishedAddrs = PublishedAddrs.RelayOnly,
    ) : Discovery {

        override fun equals(other: Any?): Boolean =
            this === other || (other is PkarrPublisher &&
                    other.relayUrl == relayUrl && other.published == published)

        override fun hashCode(): Int = 31 * relayUrl.hashCode() + published.hashCode()

        override fun toString(): String =
            "Discovery.PkarrPublisher(relayUrl=$relayUrl, published=$published)"

        companion object {
            /**
             * Publishes to the pkarr relay n0 runs.
             *
             * Carries "not stated" rather than n0's URL, so upstream keeps choosing — including
             * between its production and staging servers. A copy of the URL on this side would keep
             * compiling while pointing at a retired server.
             */
            fun n0(published: PublishedAddrs = PublishedAddrs.RelayOnly): PkarrPublisher =
                PkarrPublisher(null, published)
        }
    }

    /**
     * Resolves other endpoints' addresses from a pkarr relay over HTTP.
     *
     * The resolving half of [PkarrPublisher], and independent of it: an endpoint may resolve from a
     * relay it never publishes to, and the reverse.
     *
     * @property relayUrl the pkarr relay to query, or `null` for n0's own — see [n0].
     */
    class PkarrResolver(val relayUrl: String?) : Discovery {

        override fun equals(other: Any?): Boolean =
            this === other || (other is PkarrResolver && other.relayUrl == relayUrl)

        override fun hashCode(): Int = relayUrl.hashCode()

        override fun toString(): String = "Discovery.PkarrResolver(relayUrl=$relayUrl)"

        companion object {
            /** Resolves from the pkarr relay n0 runs. Carries "not stated" — see [PkarrPublisher.n0]. */
            fun n0(): PkarrResolver = PkarrResolver(null)
        }
    }

    /**
     * Resolves other endpoints' addresses from DNS `TXT` records under an origin domain.
     *
     * The plain-DNS path, which works wherever a resolver does and needs no HTTP client. What it
     * cannot do is publish: a DNS zone is written by whatever fills it, which for n0's domain is the
     * pkarr relay in front of it.
     *
     * @property originDomain the domain endpoint records live under, or `null` for n0's own — see
     *   [n0].
     */
    class Dns(val originDomain: String?) : Discovery {

        override fun equals(other: Any?): Boolean =
            this === other || (other is Dns && other.originDomain == originDomain)

        override fun hashCode(): Int = originDomain.hashCode()

        override fun toString(): String = "Discovery.Dns(originDomain=$originDomain)"

        companion object {
            /** Resolves under the domain n0 runs. Carries "not stated" — see [PkarrPublisher.n0]. */
            fun n0(): Dns = Dns(null)
        }
    }
}

/**
 * Which of this endpoint's addresses a [Discovery.PkarrPublisher] may put in a signed packet.
 *
 * The **ordinals** are the wire protocol shared with `endpoint.rs`, exactly as [EndpointPreset]'s
 * are: do not reorder, append only. They map onto iroh's `AddrFilter` constructors, which are named
 * choices and therefore data, even though the filter itself is a function.
 */
enum class PublishedAddrs {
    /** Relay URLs only. Upstream's default: it keeps IP addresses off a public pkarr server. */
    RelayOnly,

    /** IP sockets only. */
    IpOnly,

    /** Everything this endpoint knows about itself. */
    Unfiltered,
}

// ── Wire format ───────────────────────────────────────────────────────────────────────────────
//
// Mirrored by `read_discovery` in `endpoint.rs`; the two must be changed together, because the bind
// payload is positional and nothing on either side would notice a drift.
//
// This is its own tag family. It is NOT `Addr.kt`'s `ADDR_TAG_*`, which numbers a different set of
// payload shapes from the same starting point — reusing one numbering across shapes is how the two
// ends quietly stop agreeing.

private const val DISCOVERY_TAG_PKARR_PUBLISHER = 0
private const val DISCOVERY_TAG_PKARR_RESOLVER = 1
private const val DISCOVERY_TAG_DNS = 2

/**
 * Writes one discovery service inline: its tag, then that variant's fields.
 *
 * An absent URL or domain is the `i32 -1` of [BinaryWriter.optString], which is what tells Rust to
 * ask upstream for its own default rather than parsing anything.
 */
internal fun BinaryWriter.writeDiscovery(service: Discovery) {
    when (service) {
        is Discovery.PkarrPublisher -> {
            u8(DISCOVERY_TAG_PKARR_PUBLISHER)
            optString(service.relayUrl)
            u8(service.published.ordinal)
        }

        is Discovery.PkarrResolver -> {
            u8(DISCOVERY_TAG_PKARR_RESOLVER)
            optString(service.relayUrl)
        }

        is Discovery.Dns -> {
            u8(DISCOVERY_TAG_DNS)
            optString(service.originDomain)
        }
    }
}
