#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# Crops and compresses the record.sh recordings into app/src/main/res/raw (English) and raw-es (Spanish), or into
# docs/clips/<language> those marked "docs" in crops.txt,
# with the framing from crops.txt. Usage: tools/catalog_clips/encode.sh [clip...]   (ffmpeg on the PATH or $FFMPEG)
set -euo pipefail
cd "$(dirname "$0")/../.."
FF="${FFMPEG:-ffmpeg}"
IN=build/catalog_clips
while read -r name y dur dest; do
  case "$name" in ''|'#'*) continue;; esac
  if [ $# -gt 0 ] && [[ " $* " != *" $name "* ]]; then continue; fi
  for lang in en es; do
    out=app/src/main/res/raw$([ $lang = en ] || echo "-$lang")
    [ "${dest:-}" = docs ] && out=docs/clips/$lang
    mkdir -p "$out"
    src="$IN/$lang/clip_$name.mp4"
    [ -f "$src" ] || { echo "falta $src"; continue; }
    "$FF" -v error -y -ss 0.6 -t "$dur" -i "$src" \
      -vf "crop=610:762:0:$y,scale=480:600:flags=lanczos,fps=20,format=yuv420p" \
      -an -c:v libx264 -profile:v main -preset slow -crf 30 -movflags +faststart "$out/clip_$name.mp4"
    echo "$out/clip_$name.mp4 $(wc -c < "$out/clip_$name.mp4") bytes"
  done
done < tools/catalog_clips/crops.txt
