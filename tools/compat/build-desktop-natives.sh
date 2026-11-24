#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# The desktop app's own native libraries, built inside Ubuntu 20.04 so that they run on Ubuntu 20.04 and newer
# (glibc 2.31, libstdc++ of GCC 9): built on a newer system they ask for that system's glibc and do not load on older
# ones. No sudo or Docker: tools/compat/ubuntu-root.sh.
#
#   tools/compat/build-desktop-natives.sh [all|x11|fastdds|filament]
#
# - x11:      desktopApp/src/jvmMain/resources/natives/linux-x64/libvizmini2-x11.so (tools/x11-threads)
# - fastdds:  Fast DDS, Fast CDR and the JNI library of the jros2 fork, in tools/jros2-16k/desktop/jros2-desktop.jar
#             (JROS2_FORK: a local clone or URL of the fork, by default ~/vizmini2/jros2 or VizMini2/jros2 on GitHub)
# - filament: libfilament-jni.so and libfilamat-jni.so, replaced inside tools/filament-desktop/filament-desktop.jar
#             (the Filament sources and the portable clang come from tools/filament-desktop/build-filament.sh)
# At the end it checks that no library asks for more than GLIBC_2.31 / GLIBCXX_3.4.28.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
C="${VIZMINI2_COMPAT:-$HOME/.cache/vizmini2-compat}"
FW="${FILAMENT_WORK:-$HOME/.cache/vizmini2-filament}"
ROOT="$HERE/ubuntu-root.sh"
WHAT="${1:-all}"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
CMAKE_VERSION=3.31.6
mkdir -p "$C/tools" "$C/work"

"$ROOT" 20.04 create
"$ROOT" 20.04 apt update
"$ROOT" 20.04 apt install -y build-essential git curl ca-certificates python3 libx11-dev libgl-dev libxext-dev zip unzip xz-utils

if [ ! -x "$C/tools/cmake/bin/cmake" ]; then
  echo "== cmake $CMAKE_VERSION"
  mkdir -p "$C/tools/cmake"
  curl -fsSL "https://github.com/Kitware/CMake/releases/download/v$CMAKE_VERSION/cmake-$CMAKE_VERSION-linux-x86_64.tar.gz" | tar -xz --strip-components=1 -C "$C/tools/cmake"
fi
# The JNI headers only (the host JDK does not run on 20.04).
rm -rf "$C/jdk-headers"; mkdir -p "$C/jdk-headers"; cp -r "$JAVA_HOME/include" "$C/jdk-headers/"
IN_PATH="$C/tools/cmake/bin:\$PATH"

if [ "$WHAT" = all ] || [ "$WHAT" = x11 ]; then
  echo "== x11"
  "$ROOT" 20.04 run "cd '$REPO/tools/x11-threads' && CC=gcc sh build.sh"
fi

if [ "$WHAT" = all ] || [ "$WHAT" = fastdds ]; then
  echo "== Fast DDS (log: $C/work/fastdds.log)"
  FORK="${JROS2_FORK:-}"
  if [ -z "$FORK" ]; then [ -d "$HOME/vizmini2/jros2/.git" ] && FORK="$HOME/vizmini2/jros2" || FORK=https://github.com/VizMini2/jros2.git; fi
  W="$C/work/jros2"
  rm -rf "$W"; git clone -q --depth 1 ${FORK:+"$( [ -d "$FORK" ] && echo "file://$FORK" || echo "$FORK")"} "$W"
  # GCC 9's libstdc++ linked into each library, with its symbols hidden: Fast DDS's inline template code must meet
  # the libstdc++ it was compiled with. Mixed with a newer system one it corrupted the heap while loading (its
  # static std::regex), depending on what the process had loaded before (Skia first: always).
  STATIC="-static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL"
  sed -i "s|^JNI_CXXFLAGS=\"-std=c++17 -O3 -Wall -fPIC -pthread -shared\"|JNI_CXXFLAGS=\"-std=c++17 -O3 -Wall -fPIC -pthread -shared $STATIC\"|" "$W/scripts/cppbuild.bash"
  grep -q -- "-static-libstdc++" "$W/scripts/cppbuild.bash" || { echo "could not patch the JNI flags of cppbuild.bash"; exit 1; }
  "$ROOT" 20.04 run "export PATH=$IN_PATH JAVA_HOME='$C/jdk-headers' LDFLAGS='$STATIC'; cd '$W' && bash scripts/build-linux-x86_64.bash" > "$C/work/fastdds.log" 2>&1 \
    || { tail -30 "$C/work/fastdds.log"; exit 1; }
  "$REPO/tools/jros2-16k/build-desktop.sh" "$W"
fi

if [ "$WHAT" = all ] || [ "$WHAT" = filament ]; then
  echo "== Filament (log: $C/work/filament.log)"
  [ -x "$FW/tools/bin/clang" ] && [ -d "$FW/filament" ] || { echo "first run tools/filament-desktop/build-filament.sh (sources and clang)"; exit 1; }
  F="$C/filament"; mkdir -p "$F"
  ln -sfn "$FW/tools" "$F/tools"; ln -sfn "$FW/filament" "$F/filament"
  "$ROOT" 20.04 run "export PATH=$IN_PATH FILAMENT_WORK='$F' JAVA_HOME='$C/jdk-headers'; bash '$REPO/tools/filament-desktop/build-filament.sh' && bash '$REPO/tools/filament-desktop/build-jni.sh'" > "$C/work/filament.log" 2>&1 \
    || { tail -30 "$C/work/filament.log"; exit 1; }
  ( cd "$F/jni/classes" && zip -q "$REPO/tools/filament-desktop/filament-desktop.jar" natives/linux-x86_64/libfilament-jni.so natives/linux-x86_64/libfilamat-jni.so )
fi

echo "== check"
"$HERE/check-glibc.sh"
