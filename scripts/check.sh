#!/bin/bash
# Local CI. Prints failures only: unit tests, a plugin build, the package size gate, and a scan of
# what would be published. Run it before merging to main and before a release.
set -u
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
fail=0
note() { echo "FAIL: $*"; fail=1; }
log=$(mktemp)
gradle -q build >"$log" 2>&1 || { note "gradle build (tests)"; tail -20 "$log"; }
sh plugin/build.sh >"$log" 2>&1 || { note "plugin build"; tail -20 "$log"; }
for z in build/plugin-stage/dist/*.zip; do
  [ -e "$z" ] || continue
  [ "$(stat -f%z "$z" 2>/dev/null || stat -c%s "$z")" -le 3500000 ] || note "$z exceeds 3.5 MB (the limit is 4 MB)"
done
snap=$(mktemp -d)
if [ -x private/scripts/public-snapshot.sh ]; then
  private/scripts/public-snapshot.sh "$snap" >"$log" 2>&1 || { note "public snapshot scan"; grep -v '^published:' "$log" | head -30; }
fi
rm -rf "$snap" "$log"
exit $fail
