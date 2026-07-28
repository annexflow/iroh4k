# iroh4k

Kotlin Multiplatform bindings for [iroh](https://github.com/n0-computer/iroh): peer-to-peer QUIC
connections dialled by public key.

```kotlin
implementation("tech.annexflow.iroh4k:iroh4k:0.1.0")
```

You dial a peer by its 32-byte public key instead of an address, and iroh works out how to get
there — directly if it can, hole punched if it must, through a relay if it cannot. What you get back
is a QUIC connection: authenticated, encrypted, as many streams as you want, and able to survive the
network changing underneath it.

iroh4k puts that behind `suspend` functions, `Flow`s and `AutoCloseable` handles, on JVM, Android,
iOS, macOS, Linux and Windows.

## Quickstart

Two endpoints in one process: one serves an echo protocol, the other dials it and gets its bytes
back. It is lifted from [`examples/echo`](examples/echo), which runs on both the JVM and
Kotlin/Native — try it with `./gradlew :examples:echo:jvmRun`.

Everything is suspending, so it lives in a coroutine; the example's `main` is a `runBlocking { }`.

```kotlin
val alpn = "iroh4k/echo/0".encodeToByteArray()

// `Minimal` installs a TLS provider and nothing else: no relays, no DNS or pkarr lookup. With
// `RelayMode.Disabled` and an explicit loopback bind, this endpoint talks to nothing it was not
// told about — which is what makes an example (or a test) hermetic.
fun config() = EndpointConfig(
    preset = EndpointPreset.Minimal,
    relayMode = RelayMode.Disabled,
    bindAddrs = listOf(SocketAddr.parse("127.0.0.1:0")),
)

Endpoint.bind(config()).use { server ->
    Endpoint.bind(config()).use { client ->
        // `spawn()` sets the endpoint's ALPN list to exactly the registered protocols.
        val router = Router.builder(server)
            .accept(alpn) { connection ->
                connection.acceptBi().use { stream ->
                    val request = stream.recv.readToEnd(64 * 1024)
                    stream.send.writeAll(request)
                    stream.send.finish()
                    // `writeAll` returns when QUIC took the bytes, not when the peer has them, and
                    // the router closes this connection the moment the handler returns.
                    stream.send.stopped()
                }
            }
            .spawn()

        // With discovery off, the dialler has to be told where to go. Over a real network with
        // `EndpointPreset.N0` an `EndpointId` is enough.
        client.connect(server.addr(), alpn).use { connection ->
            connection.openBi().use { stream ->
                stream.send.writeAll("hello".encodeToByteArray())
                stream.send.finish()
                println(stream.recv.readToEnd(64 * 1024).decodeToString())  // hello
            }
            connection.close(0, "done".encodeToByteArray())
        }

        router.shutdown()   // before the endpoint: the router does not own it
        client.shutdown()   // iroh's async close — tells peers why, and waits
        server.shutdown()
    }
}
```

Two things there are the API's opinions rather than style. **Every handle-owning type is
`AutoCloseable` and must be released**, because it owns Rust memory no GC can see — `use { }` is not
optional. And **the two kinds of ending are separate**: `shutdown()` is iroh's graceful close, which
tells the peer why and waits, while `close()` releases the handle. Doing only the second is legal and
leaves the peer to work it out from a timeout.

## What you get

| | |
| --- | --- |
| **Endpoints** | `bind`, `shutdown`, identity, bound sockets, external addresses, live metrics |
| **Connections** | The full accept path (`Incoming` → `Accepting` → `Connection`), stats, paths, RTT, MTU |
| **Streams** | Bidirectional and unidirectional, priorities, resets, `stopped()`, `readToEnd` |
| **Datagrams** | `sendDatagram`, `sendDatagramWait`, `readDatagram` |
| **Router** | ALPN-routed protocol handlers, in fifty lines of Kotlin over `acceptNext()` |
| **Watchers** | `watchAddr`, `watchHomeRelay`, `watchPaths`, `watchPathEvents` — real cold `Flow`s |
| **Addressing** | `EndpointAddr` as a faithful set of transports, tickets, z32, sign and verify |
| **Relays** | Presets, custom relay maps, runtime `insertRelay`/`removeRelay` |
| **Discovery** | n0's DNS and pkarr, your own pkarr relay or DNS domain, mDNS on the local link |

## Finding a peer

Four ways, and you can combine them.

**Hand it the address.** `connect(addr, alpn)` with an `EndpointAddr` you got out of band — a QR
code, your own backend, an `EndpointTicket`. Works offline and needs no infrastructure.

**Remember it.** `endpoint.addEndpointAddr(addr)` files an address away, so a later
`connect(EndpointAddr.of(id), alpn)` — an id with no transports in it — can resolve it.
`removeEndpointAddr(id)` takes it back out.

**Use n0's infrastructure.** `EndpointPreset.N0` publishes to and queries n0's DNS and pkarr servers,
so an `EndpointId` alone is enough over the internet.

**Or your own.** `EndpointConfig.discovery` points pkarr publishing, pkarr resolution and DNS lookup
at your servers instead. A non-empty list replaces the preset's services rather than joining them.
`EndpointConfig.mdns` adds discovery over the local link, which is the offline-LAN answer — see
[Android](#android) for the one permission it needs there.

## How this relates to iroh-ffi

[iroh-ffi](https://github.com/n0-computer/iroh-ffi) is upstream's own binding, written and maintained
by the people who write iroh, and it tracks their releases. **If your app is JVM-only, start there** —
it is the shorter path and it comes from the source.

iroh4k exists for the case iroh-ffi does not cover: **Kotlin Multiplatform**. iroh-ffi is generated by
UniFFI and reaches the JVM through JNA, so the Kotlin it produces is JVM bytecode calling a
JNA-loaded shared library. That is a sound design for what it targets, but Kotlin/Native has no JVM
and no JNA, so there is no iOS, macOS, Linux or Windows target to be had from it without a different
strategy.

iroh4k is that different strategy: one Rust crate built twice — a `staticlib` linked into
Kotlin/Native through cinterop, and a `cdylib` reached from the JVM and Android through hand-written
JNI. Both facades call the same core through the same codec, and the same 216 shared test bodies run
against each.

Writing the binding by hand rather than generating it also changed three things. They are trade-offs,
not scores — each costs something too:

- **`suspend` and `Flow` are real.** Async Rust operations are registered in an op table and resumed
  through a continuation, so cancelling the Kotlin coroutine aborts the tokio task rather than
  leaving it running, and nothing blocks a thread waiting on a network round trip. iroh's watchers
  come across as cold flows, with the latest-value ones coalescing and the event one reporting a
  `Lagged` element rather than dropping transitions silently. The cost is that the op table, the
  cancellation path and both facades' halves of it are ours to keep correct.

- **Addresses keep their shape.** iroh's `EndpointAddr` holds a *set* of `TransportAddr`s — relay
  URLs, IP sockets, and `Custom` addresses for transports iroh does not implement itself. A generated
  binding has to pick a flat shape for that, and iroh-ffi picks
  `{ id, relay_url: String?, addresses: List<String> }`, which is simpler to use and cannot carry a
  `Custom` address or a second relay URL. iroh4k carries the set instead, plus an `Unknown` variant so
  a newer iroh's address kind surfaces rather than disappearing — at the price of a more elaborate
  type to hold.

- **Rust never calls into Kotlin.** UniFFI models `ProtocolHandler` as a foreign trait, which is the
  natural thing to do with a generator and gives you a handler interface to implement; on the JVM it
  means a `JNI_OnLoad`, a cached `JavaVM` and an `AttachCurrentThread` on every tokio worker that runs
  one. iroh4k's `Router` is Kotlin over `Endpoint.acceptNext()` instead, so there is no reverse
  callback anywhere — fewer moving parts across the boundary, and you write a lambda rather than
  implement a trait.

  One honest exception, Android only: `Iroh4kAndroid` hands the process's `JavaVM` and application
  `Context` to `ndk_context`, because the crates that read the system DNS servers and the interface
  list have no other way to reach them. Those pointers are used by dependencies to call *Android
  framework* APIs, never to call back into your code.

What iroh4k does not have is upstream's blessing or upstream's release cadence. It is hand-written and
maintained separately, which is why it is specific about what it has and has not verified.

## Platforms

| Target | Status |
| --- | --- |
| `macosArm64`, `jvm` | Tested on every change |
| `android` (AAR) | Tested on the host, plus instrumented tests on an emulator and a real device |
| `iosArm64`, `iosSimulatorArm64` | Compiles and links; cinterop verified in CI |
| `linuxX64` | Tested in CI |
| `linuxArm64`, `mingwX64` | Cross-compiled and assembled in CI; never executed |
| `androidNativeArm64`, `androidNativeX64` | Cross-compiled and assembled in CI; never executed |

Exact test counts per target are in [`STATUS.md`](STATUS.md).

## Android

Add the dependency and it works — the AAR takes care of two things a consuming app would otherwise
crash without.

**It initialises itself.** Crates underneath iroh reach Android's DNS servers and interface list
through `ndk_context`, which needs the process's `JavaVM` and `Context` before the first
`Endpoint.bind`. Without it the process dies with a native `SIGABRT` and `android context was not
initialized`, which no Kotlin code can catch. `Iroh4kInitializer` does it at process start through
`androidx.startup`. If you remove the startup provider, call `Iroh4kAndroid.install(context)`
yourself before creating any endpoint.

**It declares `INTERNET` and `ACCESS_NETWORK_STATE`.** Android gates socket creation on the first at
the kernel level, and a bind without it fails as `Failed to bind sockets` with nothing naming the
cause. Both are install-time permissions with no runtime prompt.

The AAR is 64-bit only — `arm64-v8a` and `x86_64` — because the facade passes every native handle as
a `jlong`. `minSdk` is 26.

### mDNS needs one permission you have to add

Android's Wi-Fi stack drops multicast frames not addressed to the device, so `EndpointConfig.mdns`
discovers nothing until your app holds a `WifiManager.MulticastLock` — and there is no error when it
does not, only silence. Declare `CHANGE_WIFI_MULTICAST_STATE` in your manifest, then:

```kotlin
Iroh4kAndroid.multicastLock(context).use {
    Endpoint.bind(EndpointConfig(mdns = MdnsConfig())).use { endpoint -> /* discover, connect */ }
}
```

iroh4k deliberately leaves that permission out of its own manifest. mDNS is off by default and the
library is fully functional without it, while a Wi-Fi permission is visible in the merged manifest
and in a store listing, and the lock costs battery while held — so it is your call, not a library's.
Without the permission the call throws `SecurityException` naming it, which is the one part of mDNS
on Android that reports itself. Hold the lock while discovery matters, not for the process lifetime.

### Two Android targets, and an app wants the first

`-Ptargets=…,android` builds the **AAR** — the JNI facade plus a `libiroh4k.so` per ABI from
`cargo ndk`. `androidNativeArm64`/`androidNativeX64` are **Kotlin/Native** targets on the cinterop
path, for Kotlin/Native binaries that happen to run on Android; they have no `JavaVM`, so anything
needing the platform's DNS or interface list is unavailable to them. Neither selection implies the
other.

`Endpoint.networkChange()` exists for Android specifically: the OS reports interface changes to Java
and not to native code, so call it from your own `ConnectivityManager` callback.

## Building from source

You need a Rust toolchain — the Gradle build shells out to plain `cargo build --target <triple>` and
installs nothing for you.

```bash
./gradlew build                            # the JVM plus the build host's native target
./gradlew build -Ptargets=macosArm64,jvm
./gradlew build -Ptargets=all              # all ten
```

With no `-Ptargets`, only the JVM and the host's own Kotlin/Native target are enabled, so a local
build needs no cross toolchain at all.

Cross-compiling is where prerequisites appear. [`scripts/build.sh`](scripts/build.sh) is the
full-matrix build from a macOS host and lists, at the top, the exact `rustup target add`,
`cargo install` and `brew install` lines it assumes. [`scripts/config.toml`](scripts/config.toml) is
the matching `~/.cargo/config.toml` fragment: a linker per target, plus the `[env]` block the Android
targets need because `cc-rs` looks for `<triple>-clang` while the NDK only ships
`<triple>21-clang`.

### Android builds

```bash
./gradlew :iroh4k:assemble -Ptargets=jvm,android                     # the AAR, both ABIs
./gradlew :iroh4k:testAndroidHostTest -Ptargets=jvm,android          # Robolectric, host library
./gradlew :iroh4k:connectedAndroidDeviceTest -Ptargets=jvm,android   # needs a device or emulator
```

Beyond the Rust toolchain that needs an Android SDK, an NDK, `cargo install cargo-ndk`, and
`rustup target add aarch64-linux-android x86_64-linux-android`. The SDK is found through
`ANDROID_HOME` or `sdk.dir`; the NDK through `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, `ndk.dir`, or
the newest `ndk/<version>` under the SDK — so an Android Studio install with nothing exported works
as is. Without `android` in `-Ptargets`, the Android Gradle plugin is never applied.

## Status

Version `0.1.0-SNAPSHOT`, targeting **iroh 1.0.3**. Dual licensed Apache-2.0 or MIT.

Four things are worth knowing before you rely on them, and
[`STATUS.md`](STATUS.md) has the evidence for each:

- **mDNS** works — measured by hand across two hosts, including a packet capture taken outside iroh —
  but no automated test covers it, because a test that joined a multicast group would stop the suite
  being hermetic.
- **Self-hosted pkarr and DNS** are wired and encoded, but no pkarr relay or DNS zone was ever stood
  up, so nothing proves a peer resolved through them.
- **The services domain** is tested only against its failure path; the successful round trips to
  services.iroh.computer are not covered.
- **No 0-RTT and no per-connection transport config** yet, and an IPv6 zone id does not survive an
  `EndpointTicket`.

## License

This project is licensed under either of

 * Apache License, Version 2.0, ([LICENSE-APACHE](LICENSE-APACHE) or
   http://www.apache.org/licenses/LICENSE-2.0)
 * MIT license ([LICENSE-MIT](LICENSE-MIT) or
   http://opensource.org/licenses/MIT)

at your option.

### Contribution

Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in this project by you, as defined in the Apache-2.0 license,
shall be dual licensed as above, without any additional terms or conditions.
