#!/bin/bash
# Prepares a release from a clean tree and tags it locally. Usage: scripts/release.sh 1.0.0
# The tag (vX.Y.Z) must equal the checked-in manifest version: Kiosk Satellite reads the manifest
# from the repo, and the GitHub workflow refuses a tag that doesn't match it.
# Nothing is pushed or published; creating the GitHub release is a separate, deliberate step.
set -eu
cd "$(dirname "$0")/.."
v=${1:-}
[[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: scripts/release.sh MAJOR.MINOR.PATCH" >&2; exit 2; }
tag="v$v"
grep -q "\"version\": \"$v\"" kiosk-satellite-plugin.json || { echo "kiosk-satellite-plugin.json version is not $v; bump it and commit first" >&2; exit 1; }
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && { echo "tag $tag already exists" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree is not clean; commit or stash first" >&2; exit 1; }
PUBLISH_CHECK=1 scripts/check.sh || { echo "check.sh failed; not releasing" >&2; exit 1; }
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
sh plugin/build.sh --version "$v"
zip="build/plugin-stage/dist/kiosk-phone-$v.zip"
[ -f "$zip" ] || { echo "build did not produce $zip" >&2; exit 1; }
git tag -a "$tag" -m "Kiosk Phone $v"
echo "built $zip ($(stat -f%z "$zip" 2>/dev/null || stat -c%s "$zip") bytes), tagged $tag"
echo "sha256: $(shasum -a 256 "$zip" | cut -d' ' -f1)"
