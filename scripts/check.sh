#!/bin/bash
# Local CI. Prints failures only: tests, the package build, the size gate, the KS rules check, the public-snapshot
# scan, and the kit report (a warning, not a failure). Run it before merging to main and before a release.
set -u
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
fail=0
# Layout: the template's src/ + tests/ by default; a plugin with another layout (a Gradle core/ + plugin/, say)
# declares its commands in kit.json: {"test": ..., "build": ..., "dist": ..., "sources": [...]}.
cfg() { python3 -c 'import json,os,sys; d=json.load(open("kit.json")) if os.path.exists("kit.json") else {}; print(d.get(sys.argv[1], sys.argv[2]))' "$1" "$2"; }
test_cmd=$(cfg test "python3 scripts/test.py"); build_cmd=$(cfg build "python3 tools/build.py"); dist=$(cfg dist dist)
# Cloud-synced folders (iCloud Drive, Dropbox) leave conflict copies such as "Foo 2.java" that break the build.
dupes=$(find . -name '* [0-9].*' -not -path './.git/*' -not -path "./$dist/*" 2>/dev/null | head -5)
[ -z "$dupes" ] || { echo "FAIL: sync conflict copies (compare with the original, then delete):"; echo "$dupes"; fail=1; }
out=$(bash -c "$test_cmd" 2>&1) || { echo "FAIL: tests"; echo "$out" | grep -vE '^(Note:|\.+$|-+$|Ran |OK$|$)' | head -30; fail=1; }
out=$(bash -c "$build_cmd" 2>&1) || { echo "FAIL: build"; echo "$out" | tail -20; fail=1; }
out=$(python3 scripts/rules-check.py 2>&1) || fail=1; [ -z "$out" ] || echo "$out"
for z in "$dist"/*.zip; do
  [ -e "$z" ] || continue
  [ "$(stat -f%z "$z" 2>/dev/null || stat -c%s "$z")" -le 3500000 ] || { echo "FAIL: $z is over 3.5 MB (KS limit 4 MB)"; fail=1; }
done
# The working repo is never pushed (its history holds private/); publishing goes through private/scripts/publish.sh.
[ -d private ] && [ -n "$(git remote 2>/dev/null)" ] && { echo "FAIL: the working repo has a git remote; publish with private/scripts/publish.sh instead"; fail=1; }
# The scanners live in private/, which the public repo doesn't have, so the release workflow skips them.
if [ -x private/scripts/public-snapshot.sh ]; then
  snap=$(mktemp -d)
  out=$(private/scripts/public-snapshot.sh "$snap/out" 2>&1) || { echo "FAIL: public snapshot"; echo "$out" | head -30; fail=1; }
  rm -rf "$snap"
fi
if [ -f private/scripts/kit-sync.py ]; then
  out=$(python3 private/scripts/kit-sync.py 2>&1) || echo "$out"
fi
exit $fail
