#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
#
# The instrumented tests that need no PC servers (they use the app's demo robot), on the connected device or emulator:
# installs the debug APKs, runs each class and collects its output and screenshots in build/e2e-android/. Exit code 1
# if any class fails. Used by the CI (on an emulator); on a real phone, back its data up first (the tests restore the
# user's tabs, but better safe than sorry).
#
# The CI's emulator has no GPU and shares a small machine, so a class that fails is run once more from a clean start
# (app stopped, home screen); it only counts as failed if it fails twice. The first output stays as <Class>.1.txt.
# The classes that fail go to failed.txt (added to, across runs). If the device is lost (the emulator crashed), the
# classes not run yet go to not_run.txt: the CI runs just those again on a new emulator (ONLY="A B" runs only those).
set -uo pipefail
cd "$(dirname "$0")/../.."
ADB="${ADB:-adb}"
APP=com.juancams.vizmini2
RUNNER=$APP.test/com.juancams.rviz2mini.TestRunner
OUT=build/e2e-android; mkdir -p "$OUT"
CLASSES=(
  DashboardDeviceTest DragDropDeviceTest ImagePanelsDeviceTest RvizDisplaysLocalTest RvizToolsDeviceTest
  LandscapeDeviceTest TeleopDeviceTest BagPlaybackDeviceTest GraphTabsDeviceTest OnboardingDeviceTest
  DemoModeDeviceTest ZstdDeviceTest Jros2StartupDeviceTest
)
# shellcheck disable=SC2206
if [ -n "${ONLY:-}" ]; then CLASSES=($ONLY); fi
rm -f "$OUT/not_run.txt"
# A full run starts a new list of failures; a run of the classes not run yet (ONLY) adds to it.
[ -n "${ONLY:-}" ] || rm -f "$OUT/failed.txt"
# The APK for the device's ABI (x86_64 on the emulator, arm64-v8a on phones) or, if there is none, the universal one.
abi=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')
apk=app/build/outputs/apk/direct/debug/app-direct-$abi-debug.apk
[ -f "$apk" ] || apk=app/build/outputs/apk/direct/debug/app-direct-universal-debug.apk
"$ADB" install -r -d "$apk" > /dev/null
"$ADB" install -r -d app/build/outputs/apk/androidTest/direct/debug/app-direct-debug-androidTest.apk > /dev/null
# No "app isn't responding" dialogs on top of the app being tested.
"$ADB" shell settings put global hide_error_dialogs 1 > /dev/null 2>&1 || true

run() {
  "$ADB" shell am force-stop "$APP"
  "$ADB" shell input keyevent KEYCODE_HOME
  sleep 2
  "$ADB" shell am instrument -w -e class "com.juancams.rviz2mini.$1" "$RUNNER" > "$OUT/$1.txt" 2>&1
  grep -q "^OK (" "$OUT/$1.txt"
}

# If the device is gone (the emulator crashed or was killed), the rest would only "fail" without running: stop there.
alive() {
  [ "$("$ADB" get-state 2> /dev/null)" = device ] && return 0
  echo "The device is gone (adb: $("$ADB" get-state 2>&1 | tr -d '\r')) before $1; not run: ${*}"
  echo "Failed before the device was lost: ${failed[*]:-none}"
  echo "$*" > "$OUT/not_run.txt"
  exit 1
}

failed=()
retried=()
for i in "${!CLASSES[@]}"; do
  c=${CLASSES[$i]}
  alive "${CLASSES[@]:$i}"
  if run "$c"; then echo "$c: OK"; continue; fi
  alive "${CLASSES[@]:$i}"
  mv "$OUT/$c.txt" "$OUT/$c.1.txt"
  if run "$c"; then echo "$c: OK at the second try"; retried+=("$c")
  else echo "$c: FAILED"; failed+=("$c"); echo "$c" >> "$OUT/failed.txt"; fi
done
"$ADB" pull "/sdcard/Android/data/$APP/files" "$OUT/files" > /dev/null 2>&1 || true
if [ ${#retried[@]} -gt 0 ]; then echo "Passed at the second try: ${retried[*]}"; fi
if [ ${#failed[@]} -gt 0 ]; then echo "Failed: ${failed[*]}"; exit 1; fi
echo "All device tests passed."
