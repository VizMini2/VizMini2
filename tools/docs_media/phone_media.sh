#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
#
# Screenshots of the phone app for the manual (androidTest DocsShots, demo robot), in English and Spanish:
# docs/shots/<lang>/shot_phone_<name>.webp. The debug app and its test APK must be installed (tools/pc_tests.sh
# builds and installs them). The user's tabs are compared and put back by tools/e2e/device_run.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
ADB="${ADB:-adb}"; export ADB
WORK=build/docs_media/phone; mkdir -p "$WORK"
for l in en es; do
  "$ADB" shell rm -f "/sdcard/clips/$l/shot_phone_*.png"
  tools/e2e/device_run.sh 'DocsShots#shots' -e shots true -e lang "$l" > "$WORK/run-$l.txt" 2>&1 || { cat "$WORK/run-$l.txt"; exit 1; }
  mkdir -p "$WORK/$l" "docs/shots/$l"
  "$ADB" pull "/sdcard/clips/$l/." "$WORK/$l/" > /dev/null
  for f in "$WORK/$l"/shot_phone_*.png; do
    # Half size (the phone's 1080 px wide screenshots are bigger than the manual needs).
    ffmpeg -v error -y -i "$f" -vf "scale=540:-1" -c:v libwebp -quality 80 "docs/shots/$l/$(basename "${f%.png}").webp"
    echo "  $(basename "${f%.png}") ($l)"
  done
done
