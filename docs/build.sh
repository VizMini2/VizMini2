#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# Builds the documentation in English (root) and Spanish (es/). Usage: docs/build.sh [destination]
# Defaults to docs/_build/site. For the website: docs/build.sh ~/VizMini2.github.io
# Requirements: pip install sphinx sphinx-rtd-theme myst-parser
set -euo pipefail
cd "$(dirname "$0")"
OUT=$(mkdir -p "${1:-_build/site}" && cd "${1:-_build/site}" && pwd)
APP=../app/src/main
for lang in en es; do
  gen=_generated/$lang
  rm -rf "$gen"; mkdir -p "$gen/clips" "$gen/shots"
  raw=$APP/res/raw; [ $lang = es ] && raw=$APP/res/raw-es
  cp "$raw"/clip_*.mp4 "$gen/clips/"
  # Documentation-only videos (they don't go in the APK).
  cp clips/$lang/clip_*.mp4 "$gen/clips/" 2>/dev/null || true
  cp $APP/res/drawable-nodpi/shot_*.webp "$gen/shots/"
  # Documentation-only screenshots (the PC app's and the phone's, per language: tools/docs_media).
  cp shots/$lang/shot_*.webp "$gen/shots/" 2>/dev/null || true
  cp $APP/assets/CHANGELOG$([ $lang = es ] && echo .es || true).md "$gen/CHANGELOG.md"
  dest=$OUT; [ $lang = es ] && dest=$OUT/es
  # -d: the Sphinx cache (.doctrees) outside the website
  VIZ_LANG=$lang "${SPHINX_BUILD:-sphinx-build}" -q -b html -c . -d "_build/doctrees/$lang" "$lang" "$dest"
done
rm -rf "$OUT/_sources" "$OUT/es/_sources" "$OUT/.buildinfo" "$OUT/es/.buildinfo"
# Without .nojekyll, GitHub Pages ignores _static/ and the website has no styles
touch "$OUT/.nojekyll"
echo "Documentación en $OUT (abre index.html, o es/index.html)"
