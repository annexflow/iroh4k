# Status, coverage and limitations

The long version of what iroh4k is and is not proven to do. [`README.md`](README.md) carries a
summary; this file carries the evidence.

Version `0.1.0`, targeting **iroh 1.0.3**. Published as `tech.annexflow.iroh4k:iroh4k` from a `v*`
tag by [`.github/workflows/release.yml`](.github/workflows/release.yml), dual licensed Apache-2.0 or
MIT — the same terms as iroh itself, so a consumer picks up nothing stricter than upstream. The
version string reports both halves: `Iroh4k.version` is `"0.1.0+iroh1.0.3"`.

## What runs where

Both facades — the cinterop one for Kotlin/Native and the hand-written JNI one for the JVM and
Android — are held to identical behaviour by the same shared test bodies.

| Target | What is actually run |
| --- | --- |
| `macosArm64`, `jvm` | 480 test bodies, 240 per facade, on every change |
| `android` (AAR) | The same 240 shared bodies under Robolectric, plus 6 Android-only tests for `Iroh4kAndroid.multicastLock`, which exists on no other target — 246 in all. Plus 6 instrumented tests, the only ones that exercise the packaged `.so` |
| `iosArm64`, `iosSimulatorArm64` | Compiles and links; cinterop verified in CI |
| `linuxX64` | Test suite configured in CI on `ubuntu-latest`; not verified locally |
| `linuxArm64`, `mingwX64` | Cross-compiled and assembled in CI; never executed |
| `androidNativeArm64`, `androidNativeX64` | Cross-compiled and assembled in CI; never executed |

The instrumented tests have run on an emulator in CI, on a physical Galaxy A22 (Android 13,
`arm64-v8a`), and on an API 37 emulator with 16 KB pages.

## What is honestly incomplete

### The services domain is only tested offline

`ServicesClient` talks to [services.iroh.computer](https://services.iroh.computer), so its networked
operations — naming an endpoint, liveness, metric push, network reports — are covered only against
the failure path they take when the service cannot be reached. Credential handling, configuration
and lifecycle are tested properly; the successful round trips are not tested at all.

### mDNS discovery is wired but never exercised by a test

`EndpointConfig.mdns` builds an `iroh-mdns-address-lookup` service into the endpoint, and it is the
one option in the binding that puts multicast on the local link. That is precisely why no test binds
with it set: the suite is hermetic by construction, and a body that joined a multicast group would
stop being so — and would fail outright on a runner with no usable IPv4 or IPv6 multicast, since
that failure comes out of `Endpoint.bind` rather than being a silent no-op. What the suite covers is
therefore the value type and the encoding, and nothing beyond them.

It has been run by hand instead, which is the only reason anything here claims it works. First on one
macOS host, two endpoints in one process, `Minimal` with relays off, a unique service name, dialling
`EndpointAddr.of(id)` with no transport addresses at all. The connection came up in 715 ms, and
iroh's own debug log shows the whole chain — the query on the wire, the response, the address
entering the client's book, then the handshake over the LAN interface rather than loopback. Most of
that 715 ms is `swarm-discovery`'s initial announcement jitter, so treat sub-second as the floor
rather than the expectation. `advertise = false` on both ends found nothing, as documented.

It has since been run across two *separate* hosts, which is the part a single machine cannot stand in
for: a macOS laptop on 5 GHz and a Galaxy A22 on 2.4 GHz, different BSSIDs bridged onto one `/24`, so
the multicast had to cross between two radios rather than loop back through one kernel. Both
directions resolved an id with no transport addresses in it — Mac dialling the phone in 127 ms and
435 ms on two runs, phone dialling the Mac in 551 ms, with round-trip times of 5 to 23 ms, which is
Wi-Fi and not loopback. The phone held `Iroh4kAndroid.multicastLock` throughout. A packet capture
taken outside iroh entirely — a plain socket joined to `224.0.0.251:5353` — saw the service name
cross the wire eleven times, and the discovered endpoint id in iroh's log matches the phone's, so
nothing here rests on the binding's own account of itself.

What that still does not establish is that the multicast lock is *necessary*, only that it was held:
proving necessity needs a run with the permission granted and the lock deliberately not taken, and
nobody has done one. Read `MdnsConfig`'s own documentation before relying on any of this — it also
needs an entitlement on Apple platforms, which nothing here has exercised.

### Self-hosted discovery has never met a real server

`EndpointConfig.discovery` builds pkarr publishing, pkarr resolution and DNS lookup against whatever
URL or domain it is given, and the suite covers the value types, the encoding, and that an endpoint
binds with them configured. What it does not cover is any of them working: no pkarr relay and no DNS
zone was stood up, so the path from a `Discovery.PkarrPublisher` to a peer that resolved through it
is unproven. The one thing measured is the ordering invariant — that clearing the preset's services
leaves the address book behind `addEndpointAddr` intact — because that one can be shown on loopback.

The suite also cannot show that the preset's services *stopped* being queried when a list is given:
observing that n0 is no longer consulted needs the network.

### An IPv6 zone id does not survive a ticket

`SocketAddr.parse("[fe80::1%3]:4433")` keeps its zone, because Rust's parser does, but iroh's
postcard encoding of a `SocketAddr` has nowhere to put one. So an address that goes through an
`EndpointTicket` comes back unzoned. This is upstream's encoding, pinned by a test rather than
hidden — do not rely on a zone surviving a round trip.

### 0-RTT: both sides work over loopback; two of its edges are not pinned by a live assertion

Both directions are bound and exercised. `Connecting.zeroRtt()` is the dialling side — it answers
`null` when the endpoint holds no session ticket for the peer, and the `Connecting` it was called on
is untouched in that case, so the caller falls back to an ordinary handshake with nothing lost.
`Accepting.zeroRtt()` is the accepting side, and unlike the dial it is infallible: iroh accepts 0-RTT
at the TLS layer on every endpoint, so an accepted handshake can always be converted. Both connect
onward through `awaitHandshake()`.

**Acceptance and rejection are both demonstrated, not just acceptance.** A loopback dial that primes
a session ticket and reconnects to the same server sees `ZeroRttStatus.Accepted`. Rejection needs a
server that has forgotten the resumption state without changing identity, and the suite produces that
by binding the same `SecretKey` twice — closing the endpoint that issued the ticket and standing up a
fresh one under the same key in its place — which is upstream's own recipe for the situation, not a
contrivance of this binding's. The client, still holding a ticket for that key, dials again, and
`ZeroRttStatus.Rejected` comes back for real, over the wire, against a server that was never told to
reject anything.

**What is not proven is narrower than "the rejection path," though it sounds that way at first.** The
per-stream error — `ERROR_ZERO_RTT_REJECTED`, `IrohError.Code.ZeroRttRejected`, ordinal 15 — is meant
to surface from a write on a stream that was opened before the rejection landed. Forcing that from
Kotlin turns out not to be reliable: `noq`, the QUIC implementation underneath iroh, captures whether
a stream is 0-RTT exactly once, at `open_bi()`'s first poll — and this binding dispatches `zeroRtt`,
`openBi` and `awaitHandshake` as three independently scheduled tokio tasks. If the connection's
background driver has already processed the peer's rejection by the time `open_bi`'s task is actually
polled, the stream it hands back is an ordinary post-handshake stream, and its write then correctly
succeeds — there is nothing issued afterwards, from Kotlin, that can close that window. This was
measured rather than assumed: a `writeAll` on the stream failed 0 times out of 9 runs on the JVM/JNI
facade, and 10 times out of 16 on cinterop. So the code is pinned a different way — a Rust unit test
over `stream.rs`'s four failure-mapper arms, together with a Kotlin assertion that ordinal 15 decodes
to `ZeroRttRejected` and a compile-time assertion in `core.rs` that the constant really is 15 — rather
than by a write that reliably fails. The rejection itself is still proven end to end, exactly as
described above; only the specific error code on a specific stream write is not.

**`is0Rtt() == true` is asserted nowhere.** `RecvStream.is0Rtt()` reports whether a received stream
carried early data, and `poll_accept` upstream samples the handshake state at the moment a stream is
*accepted*, not when it was *created*. A loopback server accepts fast enough that the handshake has
routinely already settled by then, so `is0Rtt()` legitimately answers `false` even for a stream the
client opened before the handshake completed. Only that negative is deterministic enough to assert;
the suite does, and stops there rather than asserting a `true` that would be racing the same clock the
comment above describes.

**`EndpointConfig.maxTlsTickets` is covered for its value type, its encoding, that an endpoint binds
with it set, and — this once said something false — for what `0` actually does.** Absent by
default, an explicit size round-trips, and a negative size is refused with
`IrohError.Code.InvalidArgument`; none of that changed. What changed is `0`: an earlier draft of
this file called it "store none" and said it "effectively disables 0-RTT". That was never run. It
is measured now, by `maxTlsTickets 0 does not stop a single peer from resuming` in
`CommonConnectionTests`, and it is false for the case a hermetic suite can exercise — one endpoint
talking to one peer. `rustls`'s cache rounds `0` down to a per-server budget of `0`, and a quirk in
how its backing `VecDeque` grows means that budget is never enforced for the first (and, here, only)
server name inserted: the entry survives, and 0-RTT works exactly as it would with no limit set. The
KDoc on `maxTlsTickets` carries the full explanation and the citations into `rustls`; the short
version is that `0` is not a reliable way to turn 0-RTT off, and — measured separately, not
asserted by the suite — a value from `1` to `8` is actually worse, evicting the single peer's ticket
the instant it is written every time. Eviction behaviour beyond this one corner — what happens to a
cached ticket once a cache sized for more than one peer is actually full — is still not observable
from this suite and is still not asserted; confirming it needs enough distinct peers to fill the
cache and a way to tell a stale ticket from an evicted one, neither of which a hermetic loopback
suite can provide on its own.

### `Endpoint.watchNetworkChange()` is address-derived, not iroh's own net-report watcher

It is built from address changes rather than from upstream's net-report watcher, which is unstable
upstream — read its documentation before relying on it.

### Per-connection transport configuration: every tag round-trips, rarely shown to reach the wire

`EndpointConfig.transportConfig`, `Endpoint.startConnect`'s `transportConfig` parameter and
`Incoming.acceptWith`'s `transportConfig` parameter cover all twenty-nine of iroh's QUIC transport
knobs as one sparse tagged payload shared by `TransportConfig.kt` and `transport.rs`. The suite covers
the value types — every field absent by default, equality and `hashCode` sensitive to what was
actually set, the ordinals for `CongestionController` pinned against the Rust wire contract — the
encoding for every kind the codec carries (a duration, a boolean, a float, an ordinal, both nested
records), and that an endpoint binds, a connection is made with `startConnect`'s `transportConfig`,
and one is accepted with `acceptWith`'s, each with a configuration set. One body,
`every transport configuration tag round-trips through the codec` in `CommonEndpointTests`, sets all
twenty-nine top-level fields and every field of both nested records at once and binds successfully —
the only way that bind can succeed is if all thirty-six tag numbers involved agree between
`TransportConfig.kt` and `transport.rs` and every reader consumes exactly what its writer produced, so
that body is what actually establishes that every tag round-trips, not only the handful the other
bodies happen to set.

What round-tripping through the codec does not show, for twenty-eight of the twenty-nine knobs, is
that the value changed anything beyond landing in iroh's own config object. Confirming more than that
generally needs traffic analysis this suite does not do. The one exception is
`datagramReceiveBufferSize`: setting it to zero makes the *other end's* `maxDatagramSize()` report `0`
instead of iroh's default — transport parameters travel inside the QUIC handshake, so the peer
observing a different value is evidence the setting reached the wire, not just the local config
object. That is demonstrated in both directions — the dialling side setting it in `startConnect`, the
accepting side setting it in `acceptWith` — because it is the one setting a hermetic loopback test can
observe without capturing packets.

### No logger of its own

`setLogLevel()` configures Rust's `tracing` subscriber. On the Kotlin side, `RouterBuilder.onFailure`
is where the router's survivable failures go, and its default is silence.
