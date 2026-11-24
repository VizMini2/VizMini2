#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
#
# Runs one instrumented test class (or method) by hand on the connected phone, with what tools/pc_tests.sh does
# around each one: MIUI/HyperOS's "open in the background" permission (renewed every few seconds, MIUI resets it),
# the screen kept on, and the user's tabs compared before and after (put back if a test changed them).
#
#   tools/e2e/device_run.sh FeaturesDeviceTest [-e key value ...]
#   tools/e2e/device_run.sh 'CatalogClips#clips' -e clips true -e only controllers,maps,alerts -e lang en
set -uo pipefail
cd "$(dirname "$0")/../.."
ADB="${ADB:-adb}"
APP=com.juancams.vizmini2
RUNNER=$APP.test/com.juancams.rviz2mini.TestRunner
CLASS="${1:?test class}"; shift
OUT=build/e2e-android; mkdir -p "$OUT"
name="${CLASS//#/_}"
"$ADB" exec-out run-as "$APP" cat shared_prefs/rviz2mini_tabs.xml > "$OUT/$name.tabs_before.xml" 2> /dev/null
"$ADB" shell appops set "$APP" 10021 allow > /dev/null 2>&1 || true
( while sleep 5; do "$ADB" shell appops set "$APP" 10021 allow > /dev/null 2>&1; done ) &
KEEPER=$!
STAY_ON="$("$ADB" shell settings get global stay_on_while_plugged_in 2> /dev/null | tr -d '\r')"
cleanup() {
  kill "$KEEPER" 2> /dev/null
  [ -n "$STAY_ON" ] && "$ADB" shell settings put global stay_on_while_plugged_in "$STAY_ON" > /dev/null 2>&1
  "$ADB" exec-out run-as "$APP" cat shared_prefs/rviz2mini_tabs.xml > "$OUT/$name.tabs_after.xml" 2> /dev/null
  if [ -s "$OUT/$name.tabs_before.xml" ] && ! cmp -s "$OUT/$name.tabs_before.xml" "$OUT/$name.tabs_after.xml"; then
    echo "WARNING: $CLASS changed the user's tabs: put back (before/after in $OUT/$name.tabs_*.xml)" >&2
    "$ADB" shell am force-stop "$APP"
    "$ADB" push "$OUT/$name.tabs_before.xml" /data/local/tmp/vm_tabs.xml > /dev/null 2>&1 \
      && "$ADB" shell "run-as $APP sh -c 'cat /data/local/tmp/vm_tabs.xml > shared_prefs/rviz2mini_tabs.xml'" \
      && "$ADB" shell rm -f /data/local/tmp/vm_tabs.xml
  fi
}
trap cleanup EXIT
# Right after installing, MIUI resets the permission again a few seconds later: given once more before starting.
sleep 6; "$ADB" shell appops set "$APP" 10021 allow > /dev/null 2>&1 || true
"$ADB" shell svc power stayon usb > /dev/null 2>&1
"$ADB" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
"$ADB" shell wm dismiss-keyguard > /dev/null 2>&1
"$ADB" shell am instrument -w -e class "com.juancams.rviz2mini.$CLASS" "$@" "$RUNNER" | tee "$OUT/$name.txt"
grep -q "^OK (" "$OUT/$name.txt"
