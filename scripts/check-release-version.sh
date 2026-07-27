#!/usr/bin/env bash
# Asserts that a release version agrees with the Rust crate's own version.
#
# `Iroh4k.version` is built from CARGO_PKG_VERSION in iroh4k/src/rust/src/core.rs, not from
# Gradle, so a tag that disagrees with Cargo.toml would publish an artifact that misreports
# itself — `0.1.0+iroh1.0.3` out of a v0.2.0 release. Nothing in the build catches that, because
# the two version numbers are genuinely independent.
set -euo pipefail

version="${1:?usage: check-release-version.sh <version>}"
manifest="$(cd "$(dirname "$0")/.." && pwd)/iroh4k/src/rust/Cargo.toml"

# The [package] version only: the manifest has several other `version =` keys under
# [dependencies], and the first one to match wins without the section guard.
crate="$(
    awk '
        /^\[package\]/ { p = 1; next }
        /^\[/          { p = 0 }
        p && /^version[[:space:]]*=/ {
            gsub(/["'"'"' ]/, ""); sub(/^version=/, ""); print; exit
        }
    ' "$manifest"
)"

if [ "$version" != "$crate" ]; then
    echo "Release version '$version' does not match $manifest ('$crate')." >&2
    echo "Bump [package].version in the crate, and the version assertions in" >&2
    echo "iroh4k/src/commonTest/.../CommonSmokeTests.kt and" >&2
    echo "iroh4k/src/androidDeviceTest/.../DeviceSmokeTests.kt, which hardcode it." >&2
    exit 1
fi

echo "Version $version matches the crate manifest."
