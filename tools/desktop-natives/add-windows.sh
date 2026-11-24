#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# Puts the Windows x86_64 natives built by the CI (.github/workflows/windows-natives.yml) into the desktop jars, next to
# the Linux ones:
# - tools/filament-desktop/filament-desktop.jar: natives/windows-x86_64/{filament,filamat}-jni.dll, and NativeLoader
#   (recompiled from tools/filament-desktop/NativeLoader.java, which knows each system's folder and file names);
# - tools/jros2-16k/desktop/jros2-desktop.jar: fastddsjava/native/windows-x86_64/ (Fast DDS, Fast CDR, jros2's JNI).
# The Linux scripts (build-jni.sh, build-desktop.sh) keep these when they rebuild the jars.
#
# Usage: tools/desktop-natives/add-windows.sh [run id]   (default: the last successful run on the current branch)
# Needs gh (logged in) and a JDK (javac, jar).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# The current jars (the Windows natives go into them).
tools/prebuilt/fetch.sh
WORKFLOW="Windows natives"
RUN="${1:-$(gh run list --workflow "$WORKFLOW" --branch "$(git branch --show-current)" --status success --limit 1 \
  --json databaseId --jq '.[0].databaseId // empty')}"
[ -n "$RUN" ] || { echo "No successful \"$WORKFLOW\" run on this branch: pass a run id"; exit 1; }
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT

echo "== Run $RUN"
gh run download "$RUN" -n filament-windows-x86_64 -D "$W/filament"
gh run download "$RUN" -n fastdds-windows-x86_64 -D "$W/fastdds"
for f in filament/natives/windows-x86_64/filament-jni.dll filament/natives/windows-x86_64/filamat-jni.dll \
  fastdds/fastddsjava/native/windows-x86_64/jnifastddsjava.dll fastdds/fastddsjava/native/windows-x86_64/fastdds-3.6.dll \
  fastdds/fastddsjava/native/windows-x86_64/fastcdr-2.3.dll; do
  [ -s "$W/$f" ] || { echo "Missing in the artifacts: $f"; exit 1; }
done

echo "== filament-desktop.jar"
"${JAVA_BIN}javac" --release 17 -nowarn -d "$W/loader" tools/filament-desktop/NativeLoader.java
( cd "$W/filament" && "${JAVA_BIN}jar" uf "$ROOT/tools/filament-desktop/filament-desktop.jar" natives/windows-x86_64 )
( cd "$W/loader" && "${JAVA_BIN}jar" uf "$ROOT/tools/filament-desktop/filament-desktop.jar" com )

echo "== jros2-desktop.jar"
( cd "$W/fastdds" && "${JAVA_BIN}jar" uf "$ROOT/tools/jros2-16k/desktop/jros2-desktop.jar" fastddsjava/native/windows-x86_64 )

for j in tools/filament-desktop/filament-desktop.jar tools/jros2-16k/desktop/jros2-desktop.jar; do
  echo "-- $j"; unzip -l "$j" | grep -E '/(windows|linux)-x86_64/.' | awk '{print $1, $4}'
done
