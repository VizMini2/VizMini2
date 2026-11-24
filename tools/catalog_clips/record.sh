#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# Records the catalog videos on the phone (CatalogClips test) in English and Spanish and downloads them to
# build/catalog_clips/<lang>/. Usage: tools/catalog_clips/record.sh [clip1,clip2...]
# Requirements: phone connected over adb and signed in; APKs installed with
#   ./gradlew installDirectDebug installDirectDebugAndroidTest   (or adb install -r -t ...)
set -euo pipefail
cd "$(dirname "$0")/../.."
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
PKG=com.juancams.vizmini2
# Code package (the one of the test classes), different from the applicationId.
CODE_PKG=com.juancams.rviz2mini
OUT=build/catalog_clips
ONLY=${1:-}
# The test sets the app language itself; cmd locale (Android 13+) is just in case.
before=$("$ADB" shell cmd locale get-app-locales $PKG 2>/dev/null | sed -n 's/.*are \[\(.*\)\].*/\1/p' || true)
# MIUI/HyperOS: without this permission the activity does not open in the background and the test waits
# forever; MIUI removes it again now and then, so it is renewed every few seconds (on other phones it simply fails).
allow_bg() { "$ADB" shell appops set $PKG 10021 allow > /dev/null 2>&1 || true; }
( while sleep 5; do allow_bg; done ) &
KEEPER=$!
trap 'kill $KEEPER 2> /dev/null' EXIT
for lang in en es; do
  "$ADB" shell am force-stop $PKG
  allow_bg
  "$ADB" shell cmd locale set-app-locales $PKG --locales $lang >/dev/null 2>&1 || true
  sleep 2
  args=(-e clips true -e lang $lang)
  [ -n "$ONLY" ] && args+=(-e only "$ONLY")
  # For the behavior tree (Groot2 on the PC).
  [ -n "${PC_IP:-}" ] && args+=(-e pcIp "$PC_IP")
  "$ADB" shell am instrument -w -r "${args[@]}" -e class $CODE_PKG.CatalogClips $PKG.test/$CODE_PKG.TestRunner | grep -E "OK \(|FAILURES|Error" || true
  mkdir -p "$OUT/$lang"
  "$ADB" pull /sdcard/clips/$lang/. "$OUT/$lang" >/dev/null
done
"$ADB" shell am force-stop $PKG
# Quoted in a single command: adb drops an empty argument ("" = system language).
"$ADB" shell "cmd locale set-app-locales $PKG --locales \"$before\"" >/dev/null 2>&1 || true
echo "Grabaciones en $OUT. Siguiente paso: tools/catalog_clips/encode.sh"
