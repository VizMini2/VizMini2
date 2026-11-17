#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# Filament's Java bindings for Windows x86_64: the same JNI sources as build-jni.sh (Linux), compiled with clang-cl
# against Filament's official Windows release (static libraries with the /MD runtime, the same one as Fast DDS's MSVC
# build), so Filament itself is not built here.
# Run from Git Bash with the MSVC x64 environment (on GitHub: ilammy/msvc-dev-cmd) and LLVM's clang-cl in the PATH
# (GitHub's Windows runners have it), and JAVA_HOME pointing to a JDK (for the JNI headers). The linker finds the Windows
# SDK and the C++ runtime through that environment's LIB variable (so this script must not use that name).
# Output: $OUT (default build/filament-windows) with natives/windows-x86_64/filament-jni.dll and filamat-jni.dll, the
# layout of filament-desktop.jar (tools/desktop-natives/add-windows.sh puts them in).
set -euo pipefail
# Native Windows tools (clang-cl, lld-link) get Windows paths (cygpath -m) and their own /options untouched.
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
V=1.75.1
B="$(cygpath -m "${FILAMENT_WORK:-$HOME/.cache/vizmini2-filament-windows}")"
OUT="$(cygpath -m "${OUT:-$HERE/../../build/filament-windows}")"
JH="$(cygpath -m "${JAVA_HOME:?JAVA_HOME must point to a JDK}")"
[ -n "${LIB:-}" ] || { echo "LIB is not set: run from the MSVC x64 environment"; exit 1; }
mkdir -p "$B"; cd "$B"

if [ ! -d dist/lib/x86_64/md ]; then
  echo "== Filament $V for Windows (release)"
  curl -sSL -o filament-windows.tgz "https://github.com/google/filament/releases/download/v$V/filament-v$V-windows.tgz"
  mkdir -p dist
  tar -xzf filament-windows.tgz -C dist include lib/x86_64/md
  rm -f filament-windows.tgz
fi
if [ ! -d src/android/common ]; then
  echo "== Filament $V sources (only the JNI bindings and the headers they need)"
  rm -rf src
  git clone -q --depth 1 --filter=blob:none --sparse -b "v$V" https://github.com/google/filament.git src
  git -C src sparse-checkout set --no-cone /android/common/ /android/filament-android/src/main/cpp/ \
    /android/filamat-android/src/main/cpp/ /filament/backend/src/VirtualMachineEnv.cpp /filament/backend/include/ \
    /libs/utils/include/ /libs/math/include/
fi

F="$B/src/android"
FLIB="$B/dist/lib/x86_64/md"
O="$B/jni"
rm -rf "$O"; mkdir -p "$O/obj"
# WIN32: Filament's headers test it (not _WIN32) and its CMake defines it; without it some structures (JobSystem::Job)
# would not have the layout the prebuilt libraries were compiled with.
CXXFLAGS=(-std:c++20 -O2 -MD -EHsc -GR- -W0 -DWIN32 -D_CRT_SECURE_NO_WARNINGS -DFILAMENT_SUPPORTS_OPENGL=1
  -DWIN32_LEAN_AND_MEAN -DNOMINMAX
  -I"$B/dist/include" -I"$F" -I"$F/common" -I"$F/filament-android/src/main/cpp"
  -I"$B/src/libs/utils/include" -I"$B/src/filament/backend/include" -I"$B/src/libs/math/include"
  -I"$JH/include" -I"$JH/include/win32")
# All of Filament's static libraries: the linker only takes what is used. OpenGL through bluegl, which needs these.
SYSLIBS=(opengl32.lib gdi32.lib user32.lib shlwapi.lib advapi32.lib ws2_32.lib)

echo "== filament-jni"
objs=()
for f in "$F"/filament-android/src/main/cpp/*.cpp "$F"/common/*.cpp "$B/src/filament/backend/src/VirtualMachineEnv.cpp" \
    "$(cygpath -m "$HERE/NativeWindowDesktop.cpp")"; do
  case "$(basename "$f")" in FramePacerAndroidJni.cpp) continue;; esac
  o="$O/obj/fil_$(basename "$f" .cpp).obj"
  clang-cl "${CXXFLAGS[@]}" -c "$f" -Fo"$o"
  objs+=("$o")
done
lld-link /DLL /NOLOGO /OUT:"$O/filament-jni.dll" "${objs[@]}" "$FLIB"/*.lib "${SYSLIBS[@]}"

echo "== filamat-jni"
mobjs=()
for f in "$F"/filamat-android/src/main/cpp/*.cpp "$F"/common/NioUtils.cpp; do
  o="$O/obj/mat_$(basename "$f" .cpp).obj"
  clang-cl "${CXXFLAGS[@]}" -c "$f" -Fo"$o"
  mobjs+=("$o")
done
lld-link /DLL /NOLOGO /OUT:"$O/filamat-jni.dll" "${mobjs[@]}" "$FLIB"/*.lib "${SYSLIBS[@]}"

mkdir -p "$OUT/natives/windows-x86_64"
cp "$O/filament-jni.dll" "$O/filamat-jni.dll" "$OUT/natives/windows-x86_64/"
echo "== done"
ls -la "$OUT/natives/windows-x86_64"
