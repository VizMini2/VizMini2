#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# Downloads the binaries of tools/prebuilt/prebuilt.txt that are missing or out of date, as settings.gradle.kts does
# on every Gradle build: for the scripts that read them without Gradle (tools/compat/check-glibc.sh, the natives'
# build scripts). A binary rebuilt here (different from the one last downloaded) is kept, with a warning.
#
# Usage: tools/prebuilt/fetch.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STATE="$ROOT/tools/prebuilt/.state"

sha() { sha256sum "$1" | cut -d' ' -f1; }

while read -r path want url; do
  case "$path" in ''|'#'*) continue ;; esac
  f="$ROOT/$path"
  s="$STATE/${path//\//__}.sha256"
  if [ -f "$f" ]; then
    have="$(sha "$f")"
    if [ "$have" = "$want" ]; then mkdir -p "$STATE"; echo "$want" > "$s"; continue; fi
    if [ "$have" != "$(cat "$s" 2> /dev/null || true)" ]; then
      echo "fetch.sh: $path is not the one in tools/prebuilt/prebuilt.txt (rebuilt here?): kept." \
        "To publish it: tools/prebuilt/publish.sh $path" >&2
      continue
    fi
  fi
  echo "Downloading $path"
  mkdir -p "$(dirname "$f")" "$STATE"
  curl -fsSL --retry 3 -o "$f.part" "$url"
  have="$(sha "$f.part")"
  if [ "$have" != "$want" ]; then
    rm -f "$f.part"
    echo "fetch.sh: $url has sha256 $have, not $want" >&2
    exit 1
  fi
  mv "$f.part" "$f"
  echo "$want" > "$s"
done < "$ROOT/tools/prebuilt/prebuilt.txt"
