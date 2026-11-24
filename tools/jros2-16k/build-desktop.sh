#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# jros2 for desktop: the same classes as the Android AAR (repo/us/ihmc/jros2-android) plus the
# Fast DDS native libraries built from the fork for the current system (Linux x86_64 for now),
# in fastddsjava/native/<platform>/, which is where fastddsjavaNativeLibrary looks for them.
#
# Usage: tools/jros2-16k/build-desktop.sh [path/to/fork]   (by default clones VizMini2/jros2, develop branch)
# Output: tools/jros2-16k/desktop/jros2-desktop.jar (used by desktopApp) and classes/jros2-classes.jar (core and ui).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# The current jar (its Windows natives are kept) and the AAR (its classes).
"$HERE/../prebuilt/fetch.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FORK="${1:-}"
if [ -z "$FORK" ]; then
  git clone -q --depth 1 -b develop https://github.com/VizMini2/jros2.git "$WORK/jros2"
  FORK="$WORK/jros2"
fi
PLATFORM=linux-x86_64
NATIVE="$FORK/src/main/resources/fastddsjava/native/$PLATFORM"
# (nm into a variable: with pipefail, `nm | grep -q` fails due to SIGPIPE even if it finds the symbol)
SYMS="$(nm -D "$NATIVE/libjnifastddsjava.so" 2>/dev/null || true)"
if ! grep -q fastddsjavaGraph_participantGetDiscoveredParticipantAddresses <<< "$SYMS"; then
  echo "Compilando los nativos de $PLATFORM desde $FORK (tarda un rato)..."
  ( cd "$FORK" && bash scripts/build-linux-x86_64.bash )
  SYMS="$(nm -D "$NATIVE/libjnifastddsjava.so")"
fi
# Every native method of fastddsjavaGraph must be in the JNI library.
for m in $(grep -o 'native [a-zA-Z\[\]]* [a-zA-Z]*' "$HERE/../../core/src/jvmMain/java/us/ihmc/fastddsjava/natives/fastddsjavaGraph.java" | awk '{print $3}'); do
  grep -q "fastddsjavaGraph_${m}\$" <<< "$SYMS" || { echo "Falta $m en libjnifastddsjava.so"; exit 1; }
done
mkdir -p "$WORK/jar/fastddsjava/native/$PLATFORM" "$HERE/desktop" "$HERE/classes"
AAR="$(ls "$HERE"/repo/us/ihmc/jros2-android/*/jros2-android-*.aar | tail -1)"
( cd "$WORK" && unzip -q -o "$AAR" classes.jar && cd jar && unzip -q -o ../classes.jar )
cp "$NATIVE"/lib* "$WORK/jar/fastddsjava/native/$PLATFORM/"
# The other systems' natives already in the jar (Windows: tools/desktop-natives/add-windows.sh) are kept.
if [ -f "$HERE/desktop/jros2-desktop.jar" ]; then
  ( cd "$WORK/jar" && unzip -q -n "$HERE/desktop/jros2-desktop.jar" 'fastddsjava/native/*' -x "fastddsjava/native/$PLATFORM/*" ) || true
fi
( cd "$WORK/jar" && rm -f "$HERE/desktop/jros2-desktop.jar" && zip -q -r "$HERE/desktop/jros2-desktop.jar" . )
cp "$WORK/classes.jar" "$HERE/classes/jros2-classes.jar"
echo "Listo: $HERE/desktop/jros2-desktop.jar"
