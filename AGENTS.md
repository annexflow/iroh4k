# AGENTS.md

Instructions for an AI coding agent working on iroh4k. [`README.md`](README.md) says what the
library is and how to consume it — this file is only the rules that are not visible from the code
you happen to be editing, and each one is here because breaking it has already cost something.

Every rule below has its reason attached. A rule without a reason gets "simplified" away; that has
happened in this repository, which is why several of the single-definition rules exist at all.

## Layout

One Rust crate (`iroh4k/src/rust`), built twice: a `staticlib` linked into Kotlin/Native through
cinterop, and a `cdylib` reached from the JVM and Android through hand-written JNI. Both facades
call the same core.

Infrastructure is `codec.rs`, `core.rs`, `handle.rs`, `ops.rs`, plus the two facade entry points
`ffi.rs` and `jni.rs`. Everything else is a **domain**: `keys`, `relay`, `addr`, `endpoint`,
`connection`, `stream`, `watch`, `services`. A domain owns its Rust module *and* both facades'
exports for it — the `#[unsafe(no_mangle)] extern "C"` functions and the
`#[cfg(not(target_os = "ios"))] mod jni_facade` with the `Java_*` symbols live in the same file as
the shared logic, so a domain stays independently editable.

A new domain is therefore five files, not one:

```
iroh4k/src/rust/src/<domain>.rs                 shared logic + both facades' exports
iroh4k/src/rust/src/lib.rs                      add `mod <domain>;`
iroh4k/src/commonMain/.../<Domain>.kt           public API + `internal expect fun native<Domain>*`
iroh4k/src/nativeMain/.../<Domain>.native.kt    actuals over cinterop
iroh4k/src/jniMain/.../<Domain>.jni.kt          actuals over the JNI object
iroh4k/src/commonTest/.../Common<Domain>Tests.kt  + native/jvm/androidHostTest delegators
```

`jniMain` has no `androidMain` counterpart to write: the JNI actuals are shared by the JVM and
Android targets. `androidMain` holds only what is Android and not JNI — the native-library loader,
the `Iroh4kAndroid`/`Iroh4kInitializer` pair that installs the Android context (paired with
`src/rust/src/android.rs`, the one Android-only Rust module), and `Iroh4kAndroid.multicastLock`,
which is Android platform API with no Rust behind it at all: it takes the `WifiManager.MulticastLock`
without which `EndpointConfig.mdns` receives nothing on this platform. That last one is why
`androidHostTest` is no longer purely delegators — see **Tests**.

`expect` names are prefixed with the domain (`nativeAddrTicketFromAddr`, `nativeEndpointBind`) so
they stay distinct inside the one shared package.

## Rust: nothing may panic on caller input

`Cargo.toml` sets `panic = "abort"` in `[profile.release]`, and both facades are built with
`--release`. A panic on anything Kotlin passed in kills the host process — there is no unwinding,
no Kotlin exception, no stack trace worth having. Unwinding across `extern "C"` is UB anyway, so
abort is the deliberate choice, not an oversight.

- Never `unwrap`/`expect`/index/slice on data that crossed the boundary. Return an error code.
- `codec.rs`'s `Reader` returns `Result` from **every** method for exactly this reason: a length
  prefix arriving from Kotlin can say anything. `count()` additionally rejects a count larger than
  the bytes remaining, so a hostile `i32::MAX` cannot become a reservation. `finish()` rejects
  trailing bytes so a wrong-layout payload fails loudly instead of being half-read.
- `keys.rs` routes every fixed-size conversion through `fixed::<N>()`, which reports a length
  mismatch rather than `try_into().unwrap()`.
- `addr.rs` validates the endpoint id length, the transport count against the bytes remaining, and
  every address string through iroh's own parser. Failures are `ERROR_ADDR` / `ERROR_TICKET`.
- `core::c_str` yields `""` for a null or non-UTF-8 pointer, so a bad string argument becomes a
  parse error rather than UB.
- JNI argument helpers do the same: an unreadable `byte[]` becomes empty, which the payload reader
  then rejects.

The `expect` calls that do exist are on invariants that cannot involve caller data — a poisoned
mutex, a static string, `take(4).try_into()`, creating the tokio runtime, allocating a JNI array.
Do not add to that list.

## Error codes are decoded by ordinal

`core.rs`'s `ERROR_*` constants map onto Kotlin's `IrohError.Code` **by ordinal**
(`Code.of(code) = entries.getOrElse(code) { Unknown }`). `OK` is `-1`; `ERROR_UNKNOWN` is `0`.

The enum and the constant list are **append-only**. Reordering or removing one silently remaps
every error in the binding — nothing fails to compile, and the tests that assert a specific code
are the only thing that would notice. Add at the end, on both sides, in the same change.

## Handles

Ten iroh objects have identity and lifetime and cross as an opaque `*mut c_void`. Everything else
(keys, addresses, tickets, relay maps) is a value type that lives entirely in Kotlin — do not give
a value type a handle; it buys nothing and costs a free routine and a leak to worry about.

- Every handle is a `handle::Tagged<T>`: a `TypeId` at offset 0 (`#[repr(C)]`, tag first) followed
  by the value. `borrow`/`clone_arc`/`free` all check the tag first, so a wrong-type handle is an
  **error**, not UB. This project has hit that twice — a `pthread_mutex_lock` and a `strlen` on an
  address that was really ASCII text. Never project a handle over a type without going through
  `handle::`.
- Consumed-once iroh types get `handle::Consumed` (synchronous: `Incoming`) or `connection.rs`'s
  `Once` (async, over a `tokio::sync::Mutex`: `Accepting`, `Connecting` — their `alpn` is `async`,
  and a `std::sync::MutexGuard` held across an `await` is not `Send`). A second use returns
  `ERROR_CLOSED`. Never `expect("already consumed")`: see the panic rule.
- On the Kotlin side, `internal/NativeHandle.kt` is the **only** handle guard. One `AtomicLong`
  holds a closed bit in bit 63 and the in-flight caller count below it, so exactly one of `close()`
  and the final `release()` frees. A second copy of this would drift, and the failure mode is a
  use-after-free on a pointer rather than an exception.

## Async operations and cancellation

Read `ops.rs`'s module header before touching anything here — it states the three interlocking
guarantees (deliver exactly once, a late cancel is harmless, both facades drive one registry).

- **An async operation that creates a handle must wrap its result with
  `ops::OpResult::with_handle(ptr, iroh4k_<type>_free)`.** If a cancel wins the deliver-once race
  the result is discarded, and without the drop function that strands a live QUIC connection or a
  bound socket with nobody holding it. See `OpResult::discard` and `impl Drop for OpResult`.
- The Kotlin caller must pass the matching releaser: `iroh(::iroh4k_connection_free) { … }` on
  native, `jniOp({ … }, ConnectionJni::freeConnection) { … }` on JNI. These cover the *sibling*
  case — the operation succeeded but the continuation was already cancelled, so nobody will ever
  receive the handle. Both sides are needed; neither alone is sufficient.
- **The alternative, where it applies, is to not create the handle inside the operation at all.**
  `endpoint.rs`'s `EndpointSlot` and `services.rs`'s `ClientSlot` are created *before* the async
  build and only filled in by it, so Kotlin owns the slot from the first instant and `close()` is
  the single release point. This removes the race instead of narrowing it, and it is required
  where `with_handle` is not enough — on the cinterop path a continuation that is already cancelled
  when the callback fires discards the result through `iroh4k_free_result`, which knows nothing
  about handles.
- **`jniOp` must not be rewritten with `withContext`.** It deadlocks: `withContext` waits for its
  block to finish, the block is parked in an uninterruptible native `opAwait`, and the cancel that
  would release it never runs. The `async(Dispatchers.IO)` + `await()` shape is what lets
  cancellation be observed while the thread is still blocked. The comment in `JniResult.kt` spells
  out why all three cleanup paths (the `invokeOnCompletion` handler, `ops::cancel_op`'s registry
  removal, and `OpResult`'s `Drop`) are needed rather than one.

### Borrowed futures and borrowed streams

iroh's `Endpoint::accept`, `Connection::closed`, `read_datagram`, `send_datagram_wait` and the four
stream openers all **borrow** their receiver, so the future is not `'static` and cannot be stored
in a handle or spawned on its own.

The one idiom: take an owned clone (`endpoint::endpoint_clone`, `connection::connection_clone` —
both are `Arc` inside, so it is a refcount bump) and create the borrow *entirely inside* the
spawned block. Nothing borrowed is ever stored. The only handles holding a future are the two iroh
hands over by value, `Accepting` and `Connecting`.

A borrowed **stream** cannot use that idiom — a stream has to survive many `next()` calls, so there
is no block for the borrow to live in. `watch.rs` answers all four watchers with the
**forwarding-task** pattern: one tokio task owns the clone, creates the stream inside itself,
encodes each item and pushes bytes into a channel; the handle owns only the receiving end, and
dropping it aborts the task. Latest-value watchers forward through `tokio::sync::watch` (coalescing
is the desired behaviour); `path_events` forwards through a bounded `mpsc` and lets iroh's own
`PathEvent::Lagged` report loss rather than dropping events silently.

## Single definitions — do not reintroduce copies

A consolidation pass removed four copies each of several of these. Each pair below is one wire
format or one policy with two ends; a private copy per domain is how the two ends quietly stop
agreeing, and the resulting bug **compiles cleanly**.

| The one definition | Its counterpart |
| --- | --- |
| `codec.rs` `Writer` / `Reader` | `internal/BinaryWriter.kt` / `internal/BinaryReader.kt` |
| `addr.rs` `write_transport_addr` / `write_endpoint_addr` / `read_endpoint_addr` / `decode_endpoint_addr` and the `TAG_*` constants | `Addr.kt` `writeTransportAddr` / `writeEndpointAddr` / `readEndpointAddr` and its mirrored `TAG_*` |
| `jni.rs` `finish` (the result envelope writer) | `JniResult.kt` (the only envelope decoder) and `jniOp` |
| `internal/NativeHandle.kt` | — the only handle guard |
| `connection.rs` `with` / `share` / `released` / `varint` / `in_runtime` / `Tracked` / `connection_clone_any` | reused by `stream.rs` and `watch.rs`, not re-implemented |

`connection_clone` — the strict, `Connection`-only clone — is not on that row and stays that way. It
is not folded into `connection_clone_any`: `watch.rs`'s path watchers and `connection.rs`'s own
`connection_alpn` / `connection_remote_id` / `connection_side` / `connection_paths` /
`connection_rtt` are `HandshakeCompleted`-only upstream, so they must reject a 0-RTT handle rather
than be handed one they cannot serve. `connection_clone_any` exists beside it for the surface that
*is* common to all three handshake states — see `AnyConnection` in `connection.rs`.

Two specific traps:

- **`writeEndpointAddr` vs `encodeEndpointAddr`.** `writeEndpointAddr` writes the address
  **inline** — id, count, transports — as one field of a larger payload; that is what
  `read_endpoint_addr` consumes and what `Services.kt` uses. `encodeEndpointAddr` is the standalone
  whole-buffer form, matched by `decode_endpoint_addr`, which additionally calls `Reader::finish()`
  to reject trailing bytes. Using the wrong one on either side desynchronises the two ends and
  compiles cleanly. (It is *not* length-prefixed — the bytes are identical; the difference is
  whether the reader owns the whole buffer.)
- **`TAG_RELAY/IP/CUSTOM/UNKNOWN` are one tag family, several payload shapes.** `addr.rs` owns the
  constants; `connection.rs` and `watch.rs` import them and deliberately reuse the same numbering
  for `IncomingAddr`, `LocalTransportAddr` and `PathEvent`, whose payloads differ (an optional
  string here, a presence byte there). Read the codec block in the header of the module you are
  editing rather than assuming `addr.rs`'s shape.

`TransportAddr` is `#[non_exhaustive]` upstream. An unknown variant is encoded as `TAG_UNKNOWN`
with its `Display` text and **refused** with `ERROR_ADDR` if Kotlin sends it back. Do not add a
`_ => {}` arm that drops it — that is the iroh-ffi bug this binding exists partly to avoid.

## Zero reverse callbacks

Rust never calls into Kotlin except through the one-shot completion callback on the FFI path.
Watchers are pull-based (`next()`), and `Router` is fifty lines of pure Kotlin over
`Endpoint.acceptNext()` with no `router.rs` and no `expect fun` behind it.

That is why there is no `JNI_OnLoad` and no `AttachCurrentThread` anywhere — `grep -rn JNI_OnLoad`
finds only the comments explaining its absence. Keep it that way: a reverse callback is the riskiest
thing in a hand-written binding, and nothing here needs one.

**The one JavaVM in the codebase is `android.rs`, and it is not a reverse callback.** Android's
`ndk_context` must be handed the process's `JavaVM` and application `Context` before iroh touches
the platform, because `hickory-resolver` reads the DNS servers off `LinkProperties` and `netdev`
enumerates interfaces through it; its accessor is an `expect`, so without the install the first
`Endpoint::bind` aborts the process with `android context was not initialized`. The stored pointers
are used by *dependencies* to call **Android framework** APIs — never to call into Kotlin. Two rules
follow: the install runs exactly once (`ndk_context::initialize_android_context` asserts it was not
set before, so a second call aborts just like a missing one), and nothing in this crate may grow a
callback into application code on the back of that VM being available.

## Tests

- **Bodies live once.** `commonTest`'s `Common*Tests` classes hold the test bodies;
  `nativeTest`, `jvmTest` and `androidHostTest` are thin classes that construct the runner and
  delegate one `@Test` per method. Every facade is held to identical behaviour, so a body added to
  a runner must be added to all **three** delegators — 227 shared bodies run in each. The Android ones
  additionally carry `@RunWith(RobolectricTestRunner::class)` and `@Config(sdk = [34])`. Note what
  that does *not* buy: the shared bodies touch no Android API, so a delegator that omits the runner
  still passes — measured, not assumed. It is there so the class is an Android unit test rather
  than a JVM test that happens to compile against `android.jar`, which is what a body reaching for
  the framework (`Endpoint.networkChange()` from a `ConnectivityManager` callback is the obvious
  one) would need. Keep it on new delegators; nothing will fail loudly if you don't.
  (`BinaryReaderTests` is the one exception on the shared side: pure-Kotlin decoder tests with no
  facade, so they carry `@Test` directly in `commonTest` and are picked up by every compilation.
  The 227 is 221 delegated bodies plus its 6.)
- **The counts are not symmetric, and the asymmetry is deliberate.** 227 shared bodies per facade,
  so 454 across the two tested facades (`jvmTest`, `macosArm64Test`), and `androidHostTest` runs
  those 227 **plus 6 Android-only tests** — `AndroidMulticastLockTests`, the one class in that
  source set that is not a delegator, because `Iroh4kAndroid.multicastLock` is `androidMain` code
  over `WifiManager` and there is no other facade to hold to the same behaviour. 233 on Android,
  687 host tests in all. Something that exists only on Android belongs in a class of its own there,
  with a KDoc saying why it is not a delegator; do not invent a `Common*Tests` body that only one
  facade can run, and do not "restore symmetry" by deleting the class.
- **`androidDeviceTest` is deliberately not a fourth delegator.** It runs on a device or emulator
  (`./gradlew :iroh4k:connectedAndroidDeviceTest -Ptargets=jvm,android`) and covers only what a
  host cannot: `System.loadLibrary` on the packaged `.so`, ART instead of HotSpot, the manifest's
  permissions — including one the manifest deliberately does **not** declare, since
  `multicastLockRefusesWithoutTheDeclaredPermission` asserts the `SecurityException` an app gets for
  reaching for `Iroh4kAndroid.multicastLock` without `CHANGE_WIFI_MULTICAST_STATE`, which
  Robolectric's shadow cannot produce because it enforces no permission at all — and
  `androidx.startup` having installed the Android context. It cannot run the
  shared bodies at all — Kotlin turns a suspend lambda inside ``fun `a name with spaces`()`` into a
  class whose name contains spaces, which DEX rejects below version 040, i.e. below `minSdk 35`.
  That is why its methods are named without backticks and why the compilation is left out of the
  `test` source-set tree. Everything these tests found was invisible to all 687 host tests: a
  process-aborting missing init, and a missing `INTERNET` permission.
- **Robolectric gives each test class its own sandbox classloader**, so under `androidHostTest`
  every class loads its *own copy* of the host `libiroh4k.so` — the loader in `androidMain`
  copies to a unique temp file precisely so the second class does not hit "native library already
  loaded in another classloader". The consequence for assertions: `LiveCounters`' "process-global"
  `AtomicI64`s are global only within one test class there. The existing tests take their
  baselines per class, so they are unaffected — but a test that assumes a counter carries across
  classes would be silently wrong on Android and right everywhere else.
- **Hermetic by construction.** `EndpointPreset.Minimal` + `RelayMode.Disabled` +
  `bindAddrs = [127.0.0.1:0]`. `Minimal` installs a TLS provider and nothing else, so there is no
  DNS or pkarr lookup; `N0` and even `N0DisableRelay` do reach the network. Two such endpoints can
  still reach each other over loopback, which is the only offline way to have something to accept.
  A consequence to keep in mind: `Endpoint.online()` can never complete under this configuration —
  that is the property the cancellation tests rely on.

  A `Discovery.PkarrPublisher` begins publishing shortly after bind, so no shared body may configure
  one against a reachable URL; the existing bodies use `https://127.0.0.1:1/pkarr`, which is refused
  locally and leaves the host.
- **`runTest`'s virtual clock skips `withTimeout`.** A body that waits on real network events must
  go through `Loopback.bounded { }`, which is `runTest { withContext(Dispatchers.Default) {
  withTimeout(60.seconds) { supervisorScope { … } } } }`. `supervisorScope` matters too: several
  tests start a peer that is *expected* to fail. Use latches / `CompletableDeferred`, not sleeps.
- **Leak counters:** read the header of `commonTest/LiveCounters.kt` before writing an assertion
  against one. They are process-global `AtomicI64`s in Rust. The pattern is `LiveCounters.settle()`
  once, then take baselines, then finish with `counter.awaitAtMost(baseline)`. The header documents
  three distinct flake mechanisms and explicitly forbids `awaitUntil { c == baseline }` followed by
  an `assertThat` — do not "simplify" back to that.
- Test-only hooks are `internal` (`Iroh4k.smokeEcho`, `liveOpCount`, `Streams`, `Watchers`,
  `Endpoint.liveHandleCount`, …). There was a near-miss shipping them as public API; check the
  visibility of anything you add for tests.

## Build and verification

Gates, the standard four:

```bash
cargo fmt --manifest-path iroh4k/src/rust/Cargo.toml -- --check
cargo clippy --manifest-path iroh4k/src/rust/Cargo.toml --release -- -D warnings
./gradlew :iroh4k:macosArm64Test
./gradlew :iroh4k:jvmTest
```

A fifth for anything in `commonMain`, `commonTest` or `jniMain`, which Android shares verbatim:

```bash
./gradlew :iroh4k:testAndroidHostTest -Ptargets=jvm,android
```

It needs an Android SDK, so it is not in the standard four — but it is the only gate that runs the
shared bodies through the Android loader, and CI runs it on every change.

Anything touching `src/androidMain`, `src/rust/src/android.rs` or the Android manifest also wants a
device or emulator, because none of what that code does is observable on a host:

```bash
./gradlew :iroh4k:connectedAndroidDeviceTest -Ptargets=jvm,android
```

**Run the two test tasks as separate Gradle invocations.** Sharing one has been observed to let a
Rust rebuild swap the JNI shared library out from under a running test JVM, producing
`NoClassDefFoundError` and a dead worker instead of an honest failure. `.github/workflows/ci.yml`
encodes this as a shell loop rather than one command line — keep it that way.

Other build facts worth knowing:

- `-Ptargets` selects targets, read by `Utils.targetsOf`. Absent, it is the JVM plus the build
  host's own Kotlin/Native target, so a local build needs no cross toolchain. `-Ptargets=all` is
  all ten.
- **`android` and `androidNative*` are different targets and imply nothing about each other.**
  `android` is the AAR: the Android Gradle plugin, applied by `iroh4k/build.gradle.kts` *only* when
  the token is present — an unconditional `plugins { }` entry would make every build of the module,
  `-Ptargets=linuxX64` included, need an Android SDK. `androidNativeArm64`/`androidNativeX64` are
  Kotlin/Native targets on the cinterop path. Keep the two decoupled in both directions; the AAR is
  in `allTargets` so that a publish, which runs with `all`, cannot drop the platform silently.
- **The AAR's `.so` comes from `cargo ndk`, not from the cinterop cargo call**, and the two need
  different toolchain setup. `cargo ndk` injects its own linker and `CC`, so it needs only the NDK
  (found by `rustJni` from `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT`/`ndk.dir`/`sdk.dir`). The plain
  `cargo build --target *-linux-android` behind cinterop needs the `[target.*]` **and** `[env]`
  entries from `scripts/config.toml`: `cc-rs` looks for a compiler named exactly `<triple>-clang`,
  which no NDK ships, and the failure is inside a build script rather than at link time.
- `:iroh4k:assemble -Ptargets=<target>` **does** build the native target: the task graph pulls in
  `cargo-<triple>`, `cinteropFfi<Target>` and `compileKotlin<Target>`. Verify with `--dry-run` if
  in doubt. `compileKotlin<Target>` on its own is a narrower gate when you only want the compile.
- `core.rs`'s `IROH_VERSION` is kept in sync with `Cargo.toml` **by hand** — there is no build-time
  way to read a dependency's version. Bumping iroh means editing both. It also means re-reading the
  guard thresholds in upstream's `endpoint/quic.rs`: `TransportConfig.kt`'s KDoc hardcodes four of
  them — values below 9 ignored for `maxConcurrentMultipathPaths`, `defaultPathMaxIdleTimeout`
  clamped to 15 seconds, `defaultPathKeepAliveInterval` above 5 seconds ignored, and values below 8
  ignored for `maxRemoteNatTraversalAddresses` — read out of private code with no test pinning them,
  so a version bump can silently falsify all four without anything here noticing.
- A new `extern "C"` export reaches Kotlin through cbindgen (`build.rs` regenerates
  `target/iroh4k.h`); cbindgen only emits a type that appears in an exported signature, which is
  what the `auto_generated_for_struct_*` no-ops in `ffi.rs` are for.
- If a change pulls in a new system library, the cinterop `.def` files' `linkerOpts` are per
  target and must be updated. The recipe is in `iroh4k.def`:
  `RUSTFLAGS="--print=native-static-libs" cargo build --release --target <triple>`.
- Never enable iroh's `tls-aws-lc-rs` feature: it needs cmake and a C toolchain and cross-compiles
  poorly to iOS, Android and mingw. `tls-ring` is the default and stays.

## Releasing

A `v*` tag publishes every target to Maven Central. The version comes from the tag and nowhere
else, and `scripts/check-release-version.sh` refuses a tag that disagrees with `[package].version`
in `iroh4k/src/rust/Cargo.toml` — `Iroh4k.version` is built from `CARGO_PKG_VERSION`, so the two
have to be bumped together, along with the assertions in `CommonSmokeTests` and `DeviceSmokeTests`
that hardcode it.

### What has to be set up outside this repository

Four repository secrets, read by the publish plugin under its own names:
`MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a Central Portal **user token** pair from
<https://central.sonatype.com/account>, not the account password), and `SIGNING_IN_MEMORY_KEY` with
`SIGNING_IN_MEMORY_KEY_PASSWORD`. The signing key is the ASCII-armoured private key, and it must be
**sign-capable as its primary key** — `gpg --full-generate-key` with `(4) RSA (sign only)`. The
workflow does not pass `signingInMemoryKeyId`, so Gradle takes the first key in the ring; a key
generated the usual way has a certify-only primary and signs with a subkey, which silently produces
nothing. Publish the public half to a keyserver or Central rejects the deployment.

Two things on the Portal side, and they are **separate settings**:

  - the namespace has to be verified, and
  - **SNAPSHOT publishing has to be enabled for it.**

The second is the one that costs an afternoon, because its symptom accuses the wrong component:
every PUT to `central.sonatype.com/repository/maven-snapshots` returns `403`, while the Portal
deployments API answers `200` for the same token. That reads like a broken or mis-copied credential,
and it is not — the token is fine and the account simply may not write snapshots. The tell is `403`
rather than `401`: the user is known, the privilege is missing. Note also that the release path does
not touch the snapshot repository at all — it stages locally and uploads a bundle to the Portal API
— so a tag can succeed while the rehearsal below cannot run.

Rehearse with the snapshot path first. It is the same workflow, the same jobs and the same Gradle
invocation, missing only the irreversible step:

    gh workflow run Release

Then push the tag:

    git tag v0.2.0 && git push origin v0.2.0

The release is not built where you might expect. Rust is cross-compiled on three runners because no
host has every toolchain, and everything else happens once on macOS with those libraries restored —
`-Prust.prebuilt=true` turns cargo off for that run. macOS is not a preference: it is the only host
on which no Kotlin target is disabled, and a disabled target is *dropped from the root module*
rather than failing the build. `scripts/check-staged-release.sh` asserts the root module references
all ten targets before anything is uploaded, because nothing downstream would.
