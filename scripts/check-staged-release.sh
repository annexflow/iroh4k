#!/usr/bin/env bash
# Verifies a staged release before anything is uploaded, and reports what it weighs.
#
# Four assertions, in order of how badly they fail if skipped:
#
#  1. The root .module must reference all ten targets. Kotlin drops a target the build host cannot
#     build (kotlin.native.ignoreDisabledTargets=true), so a publish from a host that cannot build
#     Apple targets produces a root module that resolves fine and offers no iOS. Nothing else
#     catches this: the build is green, the upload succeeds, and consumers find out.
#  2. Eleven module directories must be present — the ten targets plus the root.
#  3. Every publishable artifact must carry a detached signature. Maven Central refuses an unsigned
#     deployment, but Gradle does not: with no signing key the sign tasks are SKIPPED and the
#     staging build succeeds with zero .asc files. A missing or misnamed SIGNING_IN_MEMORY_KEY
#     secret would otherwise produce a green staging step and die at the Portal, after the upload.
#  4. The staged version must be the version being released, when the caller says what that is.
#     `-Piroh4kVersion` reaching the module is assumed everywhere and checked nowhere: if it ever
#     stopped arriving the version would stay at build.gradle.kts's -SNAPSHOT default,
#     publishAndReleaseToMavenCentral would route it to the snapshot repository, perform no Portal
#     release, and exit 0 — a tag reporting a successful release of nothing.
#
# The size line exists because Maven Central tracks release size as a three-month average per
# organisation and begins rate limiting on 2026-10-01. A release is around 90 MB, which puts a
# monthly cadence at roughly the 90th percentile of all publishers, so the number is worth seeing
# on every run rather than discovering later.
set -euo pipefail

staging="${1:?usage: check-staged-release.sh <staging-repo-dir> [expected-version]}/tech/annexflow/iroh4k"

# Optional, and empty on the workflow_dispatch path, which stages whatever the build's default
# version is and is not releasing anything. Absent means both version assertions are skipped.
version="${2:-}"

[ -d "$staging" ] || { echo "No staged release at $staging" >&2; exit 1; }

targets="jvm android iosarm64 iossimulatorarm64 macosarm64 linuxx64 linuxarm64 mingwx64 androidnativearm64 androidnativex64"

status=0

if [ -n "$version" ]; then
    # A release must never stage a snapshot: the publish plugin silently reroutes a -SNAPSHOT
    # version to the snapshot repository and performs no Portal release at all, so this is the
    # difference between publishing and appearing to publish.
    case "$version" in
        *-SNAPSHOT)
            echo "Refusing to stage a release of snapshot version '$version'." >&2
            echo "publishAndReleaseToMavenCentral would route this to the snapshot repository," >&2
            echo "release nothing at the Portal, and exit 0." >&2
            status=1
            ;;
    esac

    # Gradle lays the staged files out as <group>/<artifact>/<version>/, so the directory existing
    # is proof that the version the caller asked for is the version that was actually written.
    if [ ! -d "$staging/iroh4k/$version" ]; then
        echo "Nothing staged for version '$version' at $staging/iroh4k/$version." >&2
        echo "Staged versions:" >&2
        find "$staging/iroh4k" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; >&2
        status=1
    fi
fi

# Exactly one, not the first of however many. A -SNAPSHOT publish writes uniquely timestamped
# filenames, so a staging directory reused across runs accumulates several root modules and
# `head -1` picks between them arbitrarily — the target assertion below then passes or fails on
# which one `find` happened to walk into first. Refusing to guess is more honest than guessing.
root_modules="$(find "$staging/iroh4k" -name 'iroh4k-*.module' 2>/dev/null | sort || true)"
if [ -n "$root_modules" ]; then
    root_count="$(printf '%s\n' "$root_modules" | wc -l | tr -d ' ')"
else
    root_count=0
fi
if [ "$root_count" -ne 1 ]; then
    echo "Expected exactly one root .module under $staging/iroh4k, found $root_count:" >&2
    [ "$root_count" -eq 0 ] || printf '%s\n' "$root_modules" >&2
    echo "Delete the staging directory and stage again." >&2
    exit 1
fi
root="$root_modules"

for target in $targets; do
    if ! grep -q "\"iroh4k-$target\"" "$root"; then
        echo "Root module does not reference iroh4k-$target: $root" >&2
        status=1
    fi
done

modules="$(find "$staging" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
if [ "$modules" -ne 11 ]; then
    echo "Expected 11 module directories (ten targets plus the root), found $modules:" >&2
    find "$staging" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; >&2
    status=1
fi

# Everything Maven Central treats as a publishable artifact needs a sibling detached signature.
# Every unsigned file is reported rather than just the first, because the cause is almost always
# "no signing key at all" and seeing one name invites fixing one file.
unsigned=0
while IFS= read -r artifact; do
    if [ ! -f "$artifact.asc" ]; then
        echo "Unsigned: $artifact" >&2
        unsigned=$((unsigned + 1))
    fi
done < <(
    find "$staging" -type f \
        \( -name '*.jar' -o -name '*.klib' -o -name '*.aar' -o -name '*.pom' -o -name '*.module' \) |
        sort
)
if [ "$unsigned" -ne 0 ]; then
    echo "$unsigned staged artifacts have no detached .asc signature." >&2
    echo "The signing tasks skip silently when no key is configured, so this is what an unset or" >&2
    echo "misnamed ORG_GRADLE_PROJECT_signingInMemoryKey looks like from here." >&2
    status=1
fi

[ "$status" -eq 0 ] || exit "$status"

# Counted without sha256/sha512, because this staging repository is plain `maven-publish` output
# and the deployment is not. The publish plugin's `Checksum.DEFAULT` is `[MD5, SHA1]`, so those two
# extensions are all that reaches Maven Central — 48 of the 120 files a three-module staging
# produces here never leave the runner. Since the only reason to print this line is Central's
# release-size budget, counting files it will never see would defeat it.
shipped="$(find "$staging" -type f ! -name '*.sha256' ! -name '*.sha512' | wc -l | tr -d ' ')"
bytes="$(find "$staging" -type f ! -name '*.sha256' ! -name '*.sha512' -exec du -k {} + | awk '{s += $1} END {printf "%.1f", s / 1024}')"

echo "Staged release: ${bytes}M, $shipped files, $modules modules."
