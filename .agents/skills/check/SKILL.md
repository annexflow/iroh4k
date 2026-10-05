---
name: check
description: Use when verifying a change in the iroh4k repository before reporting completion, committing, or opening a pull request.
---

# Verifying a Change

Read [AGENTS.md](../../../AGENTS.md#build-and-verification) and keep these commands aligned with [CI](../../../.github/workflows/ci.yml). Run commands from the repository root.

## Code and build changes

Run the standard five gates on this project's macOS Apple silicon host:

```bash
cargo fmt --manifest-path iroh4k/src/rust/Cargo.toml -- --check
cargo clippy --manifest-path iroh4k/src/rust/Cargo.toml --release -- -D warnings
cargo test --manifest-path iroh4k/src/rust/Cargo.toml
./gradlew :iroh4k:macosArm64Test
./gradlew :iroh4k:jvmTest
```

Rust tests use the development profile: release builds set `panic = "abort"`. Release clippy alone does not typecheck the Rust unit tests. No nightly toolchain or root Cargo workspace is required.

Run Gradle test tasks sequentially, each in its own invocation. A simultaneous Rust rebuild can replace the JNI library underneath a test JVM. On another host, select its runnable native target and `-Ptargets` subset using the CI matrix; report which native facade was actually exercised.

## Android and cross-target changes

For changes in `commonMain`, `commonTest`, or `jniMain`, also run:

```bash
./gradlew :iroh4k:testAndroidHostTest -Ptargets=jvm,android
```

Changes in `androidMain`, `src/rust/src/android.rs`, or an Android manifest additionally need an AAR build and a device or emulator. Run these as separate invocations:

```bash
./gradlew :iroh4k:assemble -Ptargets=jvm,android
./gradlew :iroh4k:connectedAndroidDeviceTest -Ptargets=jvm,android
```

Host tests cannot establish packaged-library loading, ART behavior, manifest permissions, or Android context initialization. If the SDK or device is unavailable, state which gate remains unverified.

For target wiring, static libraries, or cinterop linker options, build each affected target with `./gradlew :iroh4k:assemble -Ptargets=<target>`. `android` is the AAR; `androidNativeArm64` and `androidNativeX64` use cinterop and do not imply the AAR. Consult AGENTS.md before changing their toolchains.

## Documentation and skills

For documentation-only changes, verify referenced paths, commands, API signatures, and examples against current source. For skill-only changes, validate frontmatter and local links, remove stale project assumptions, and exercise representative requests. A full native rebuild is not needed when executable code and build configuration are unchanged.

Report checks actually completed, failures, and unavailable prerequisites. A dry run establishes task wiring, not a passing build or test suite.
