#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# Publishes rebuilt binaries of tools/prebuilt/prebuilt.txt: uploads each one that changed to the "prebuilt" GitHub
# release (a pre-release, never the latest one, created the first time) under a name with its hash, and updates its
# line in prebuilt.txt. The old files stay in the release, so older commits still build. Then commit prebuilt.txt.
#
# Usage: tools/prebuilt/publish.sh [path ...]   (default: every path in prebuilt.txt)
# Needs gh, logged in with write access to the repository.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
LIST=tools/prebuilt/prebuilt.txt
STATE=tools/prebuilt/.state
REPO="${VIZMINI2_REPO:-VizMini2/VizMini2}"
TAG=prebuilt
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT

paths=("$@")
[ ${#paths[@]} -gt 0 ] || mapfile -t paths < <(awk '$1 !~ /^#/ && NF == 3 {print $1}' "$LIST")

if ! gh release view "$TAG" -R "$REPO" > /dev/null 2>&1; then
  echo "== Creating the \"$TAG\" release"
  gh release create "$TAG" -R "$REPO" --target main --prerelease --latest=false --title "Prebuilt binaries" \
    --notes "Binaries built by VizMini2's own scripts (Fast DDS and jros2 for Android and the desktop, Filament for the desktop), downloaded by the build: see tools/prebuilt/README.md. Not a VizMini2 release; never delete its files, older commits need them."
fi

changed=0
for path in "${paths[@]}"; do
  path="${path#./}"
  want="$(awk -v p="$path" '$1 == p {print $2}' "$LIST")"
  [ -n "$want" ] || { echo "$path is not in $LIST"; exit 1; }
  [ -f "$path" ] || { echo "$path does not exist"; exit 1; }
  have="$(sha256sum "$path" | cut -d' ' -f1)"
  if [ "$have" = "$want" ]; then
    # Already listed: make sure the release has it (the first publish, or a failed upload).
    url="$(awk -v p="$path" '$1 == p {print $3}' "$LIST")"
    asset="${url##*/}"
    if gh release view "$TAG" -R "$REPO" --json assets --jq '.assets[].name' | grep -qx "$asset"; then
      echo "== $path: unchanged"; continue
    fi
  else
    name="$(basename "$path")"
    asset="${name%.*}-${have:0:8}.${name##*.}"
    changed=1
  fi
  echo "== $path -> $asset"
  cp "$path" "$W/$asset"
  gh release upload "$TAG" -R "$REPO" "$W/$asset" --clobber
  url="https://github.com/$REPO/releases/download/$TAG/$asset"
  awk -v p="$path" -v s="$have" -v u="$url" '$1 == p && NF == 3 {print p, s, u; next} {print}' "$LIST" > "$W/list"
  cat "$W/list" > "$LIST"
  mkdir -p "$STATE"; echo "$have" > "$STATE/${path//\//__}.sha256"
done

[ "$changed" = 0 ] || echo "Done: commit $LIST."
