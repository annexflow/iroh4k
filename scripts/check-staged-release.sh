#!/usr/bin/env bash
# Verifies a staged release before anything is uploaded, and reports what it weighs.
#
# Two assertions, in order of how badly they fail if skipped:
#
#  1. The root .module must reference all ten targets. Kotlin drops a target the build host cannot
#     build (kotlin.native.ignoreDisabledTargets=true), so a publish from a host that cannot build
#     Apple targets produces a root module that resolves fine and offers no iOS. Nothing else
#     catches this: the build is green, the upload succeeds, and consumers find out.
#  2. Eleven module directories must be present — the ten targets plus the root.
#
# The size line exists because Maven Central tracks release size as a three-month average per
# organisation and begins rate limiting on 2026-10-01. A release is around 90 MB, which puts a
# monthly cadence at roughly the 90th percentile of all publishers, so the number is worth seeing
# on every run rather than discovering later.
set -euo pipefail

staging="${1:?usage: check-staged-release.sh <staging-repo-dir>}/tech/annexflow/iroh4k"

[ -d "$staging" ] || { echo "No staged release at $staging" >&2; exit 1; }

targets="jvm android iosarm64 iossimulatorarm64 macosarm64 linuxx64 linuxarm64 mingwx64 androidnativearm64 androidnativex64"

root="$(find "$staging/iroh4k" -name 'iroh4k-*.module' | head -1)"
[ -n "$root" ] || { echo "No root .module under $staging/iroh4k" >&2; exit 1; }

status=0
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

[ "$status" -eq 0 ] || exit "$status"

echo "Staged release: $(du -sh "$staging" | cut -f1), $(find "$staging" -type f | wc -l | tr -d ' ') files, $modules modules."
