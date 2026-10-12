#!/bin/sh
# Builds the KS plugin ZIP. Stages core + plugin sources and runs tools/build.py (vendored from the
# Kiosk Satellite hello-world plugin template: javac --release 8, d8, manifest validation).
# Usage: plugin/build.sh [--version X.Y.Z] [--android-platform 35]
# Needs a JDK 17 and ANDROID_HOME with build-tools and a platform. Output: build/plugin-stage/dist/
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export ANDROID_HOME
stage="$root/build/plugin-stage"
rm -rf "$stage"; mkdir -p "$stage/src"
cp -R "$root/core/src/main/java/." "$stage/src/"
cp -R "$root/plugin/src/." "$stage/src/"
cp "$root/kiosk-satellite-plugin.json" "$root/LICENSE" "$stage/"
python3 "$root/tools/build.py" "$stage" "$@"
echo "ZIP: $stage/dist"
