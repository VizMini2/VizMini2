#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# Starts a release in your name: checks that the versions and both changelogs agree, creates the draft GitHub release
# (title and notes from the changelog) as you, then tags HEAD and pushes the tag. .github/workflows/release.yml builds
# and checks every file and uploads them to that draft, which keeps you as its author (a draft the workflow creates
# itself is github-actions', and its publication does not show in your followers' feed). Publish it on GitHub once the
# workflow has finished.
#
# Usage: tools/release/start.sh 1.2.3
# Needs gh, logged in with write access to the repository, and HEAD = origin/main with nothing uncommitted.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
fail() { echo "start.sh: $*" >&2; exit 1; }

[ $# -eq 1 ] || fail "usage: tools/release/start.sh 1.2.3"
V="${1#v}"
TAG="v$V"

[ -z "$(git status --porcelain --untracked-files=no)" ] || fail "there are uncommitted changes"
git fetch -q origin main --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || fail "HEAD is not origin/main: pull or push first"
! git rev-parse -q --verify "refs/tags/$TAG" > /dev/null || fail "the tag $TAG already exists"
! gh release view "$TAG" > /dev/null 2>&1 || fail "there is already a release $TAG"
python3 tools/release/version.py check "$TAG" > /dev/null

NOTES="$(mktemp)"
trap 'rm -f "$NOTES"' EXIT
python3 tools/release/version.py notes "$V" > "$NOTES"
SHA="$(git rev-parse HEAD)"

echo "== Draft release $TAG (as $(gh api user --jq .login))"
gh release create "$TAG" --draft --title "VizMini2 $V" --notes-file "$NOTES" --target "$SHA"
echo "== Tag $TAG on $(git log -1 --format='%h %s')"
git tag -a "$TAG" -m "VizMini2 $V"
git push origin "$TAG"
echo "Done: the release workflow uploads the files to the draft; publish it on GitHub when it has finished."
