#!/usr/bin/env sh
#
# Builds iroh4k for every supported target from a macOS host.
#
# The Gradle build shells out to plain `cargo build --target <triple>`, so everything a
# cross-compile needs must be on PATH or configured in ~/.cargo/config.toml before this runs.
# Copy scripts/config.toml there first — it sets the per-target linkers, including the NDK r21
# ones Kotlin/Native requires.
#
# Prerequisites, none of which this script installs (they change your machine, so they are yours
# to run knowingly):
#
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim \
#                     aarch64-linux-android x86_64-linux-android \
#                     aarch64-unknown-linux-gnu x86_64-unknown-linux-gnu \
#                     x86_64-pc-windows-gnu
#   cargo install cbindgen cargo-ndk
#   brew install --cask android-ndk
#   brew install MaterializeInc/crosstools/aarch64-unknown-linux-gnu
#   brew install MaterializeInc/crosstools/x86_64-unknown-linux-gnu
#   brew install mingw-w64
#
set -e

# The Android NDK. Kotlin/Native pins NDK 21 — see the note in scripts/config.toml.
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-ndk}"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"

# Linux cross-compilers (MaterializeInc/crosstools).
export CC_aarch64_unknown_linux_gnu=aarch64-unknown-linux-gnu-gcc
export CXX_aarch64_unknown_linux_gnu=aarch64-unknown-linux-gnu-g++
export CC_x86_64_unknown_linux_gnu=x86_64-unknown-linux-gnu-gcc
export CXX_x86_64_unknown_linux_gnu=x86_64-unknown-linux-gnu-g++

# Windows (mingw-w64).
export CC_x86_64_w64_mingw32=x86_64-w64-mingw32-gcc
export CXX_x86_64_w64_mingw32=x86_64-w64-mingw32-g++

./gradlew build -Ptargets=all "$@"
