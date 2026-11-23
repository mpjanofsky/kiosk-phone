#!/bin/bash
# Release dry run, start to finish, stopping before anything is published. Usage:
#   scripts/release-prep.sh X.Y.Z          prepare (below)
#   scripts/release-prep.sh --verify X.Y.Z after the maintainer's release: poll until the assets are attached
# Prepare: working tree clean (untracked tooling is stashed and restored; tracked changes stop it), release
# notes checked (private/RELEASE_NOTES_X.Y.Z.md, WRITING 5), scripts/release.sh (check, build, local tag),
# a fresh public clone via `gh repo clone`, private/scripts/publish.sh into it, then the diff and SHA-256.
# It never pushes and never runs `gh release create`; it prints both commands for the maintainer's go.
# kit.json keys: "publicRepo": "owner/name" (required), "publicClone": scratch dir (default
# ${TMPDIR:-/tmp}/<id>-public), "assets": expected release assets (default the zip and kiosk-satellite-plugin.json).
set -eu
cd "$(dirname "$0")/.."
cfg() { python3 -c 'import json,os,sys; d=json.load(open("kit.json")) if os.path.exists("kit.json") else {}; v=d.get(sys.argv[1], sys.argv[2]); print(" ".join(v) if isinstance(v, list) else v)' "$1" "$2"; }
verify=0; [ "${1:-}" = "--verify" ] && { verify=1; shift; }
v=${1:-}
[[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: scripts/release-prep.sh [--verify] MAJOR.MINOR.PATCH" >&2; exit 2; }
read -r id name < <(python3 -c 'import json; m = json.load(open("kiosk-satellite-plugin.json")); print(m["id"], m["name"])')
repo=$(cfg publicRepo "")
[ -n "$repo" ] || { echo 'kit.json needs "publicRepo": "owner/name" (and optionally "publicClone")' >&2; exit 1; }
dist=$(cfg dist dist); zip="$dist/$id-$v.zip"
clone=$(cfg publicClone "${TMPDIR:-/tmp}/$id-public")

if [ $verify = 1 ]; then
  want=$(cfg assets "$id-$v.zip kiosk-satellite-plugin.json")
  for i in $(seq 1 30); do
    have=$(gh release view "v$v" --repo "$repo" --json assets -q '.assets[].name' 2>/dev/null || true)
    missing=""; for a in $want; do echo "$have" | grep -qx "$a" || missing="$missing $a"; done
    [ -z "$missing" ] && { echo "release v$v has: $want"; exit 0; }
    echo "waiting for:$missing"; sleep 20
  done
  echo "assets still missing after 10 minutes:$missing (check the release workflow run)" >&2; exit 1
fi

# 1. Tree: tracked changes stop the release; untracked tooling is stashed for the run and put back after.
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "tracked files have changes; commit them or stash them yourself:" >&2; git status --short --untracked-files=no >&2; exit 1; }
if [ -n "$(git status --porcelain)" ]; then
  echo "untracked files, stashed for this run and restored at the end:"; git status --short
  git stash push -u -q -m "release-prep $v"
  trap 'git stash pop -q' EXIT
fi

# 2. Release notes: title line comes from the manifest; the file must follow WRITING 5.
notes="private/RELEASE_NOTES_$v.md"
[ -f "$notes" ] || { echo "missing $notes (draft it per WRITING.md 5, the maintainer reviews it)" >&2; exit 1; }
bad=""
if ! head -1 "$notes" | grep -q '^First release\.'; then
  grep -q "^## What's new" "$notes" || bad="$bad\n  no '## What's new' heading"
  grep -q '^## Notes' "$notes" || bad="$bad\n  no '## Notes' heading"
  grep -q '^🧪' "$notes" || bad="$bad\n  no 🧪 line under Notes (tested on, 'Please open an issue if something looks wrong.')"
fi
grep -q '^- ' "$notes" && bad="$bad\n  items start with '- ' (the emoji is the marker)"
[ -z "$bad" ] || { printf "release notes problems in %s:$bad\n" "$notes" >&2; exit 1; }
title="$name $v"

# 3. Check, build, local tag (scripts/release.sh); skipped when the tag exists, then only the check runs.
if git rev-parse -q --verify "refs/tags/v$v" >/dev/null; then
  echo "tag v$v exists; re-running the check only"; PUBLISH_CHECK=1 scripts/check.sh
else
  scripts/release.sh "$v"
fi
[ -f "$zip" ] || { echo "no $zip" >&2; exit 1; }

# 4. Fresh public clone in a scratch dir, then the snapshot sync (publish.sh scans, commits, tags there).
[ ! -e "$clone" ] || { echo "$clone exists; delete it or set another publicClone in kit.json" >&2; exit 1; }
gh repo clone "$repo" "$clone" -- -q
private/scripts/publish.sh "$clone" "$title"

# 5. What the maintainer reviews. Nothing below pushes.
echo; echo "=== diff of the public clone against its last commit ==="
git -C "$clone" show --stat --format='%h %s' HEAD
echo; echo "sha256 $(shasum -a 256 "$zip" | cut -d' ' -f1)  $zip"
cat <<MSG

STOPPED. Nothing was pushed or published. On the maintainer's go:
  git -C "$clone" push origin main --tags
  gh release create v$v --repo $repo --title "$title" --notes-file "$PWD/$notes"
  scripts/release-prep.sh --verify $v
MSG
