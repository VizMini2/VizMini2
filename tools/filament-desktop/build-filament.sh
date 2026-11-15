#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# Filament 1.75.1 for desktop (Linux x86_64) with the Java bindings, for VizMini2's 3D view on the PC.
# No sudo: portable clang (only what is needed from the official LLVM tarball) and ninja in $FILAMENT_WORK (~/.cache/vizmini2-filament).
# -pthread: on glibc older than 2.34 (the Ubuntu 20.04 build, tools/compat) pthread is still a separate library.
# That clang needs libtinfo.so.5, which newer distros no longer ship: a minimal substitute is used (tinfo5.c).
# Afterwards: build-jni.sh (the Java bindings).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
B="${FILAMENT_WORK:-$HOME/.cache/vizmini2-filament}"
mkdir -p "$B"; cd "$B"
if [ ! -x tools/bin/clang ]; then
  echo "== LLVM"
  curl -sSL -o llvm.tar.xz https://github.com/llvm/llvm-project/releases/download/llvmorg-18.1.8/clang+llvm-18.1.8-x86_64-linux-gnu-ubuntu-18.04.tar.xz
  mkdir -p tools
  tar -xJf llvm.tar.xz --strip-components=1 -C tools --wildcards '*/bin/clang*' '*/bin/lld*' '*/bin/ld.lld' '*/bin/llvm-ar' '*/bin/llvm-ranlib' '*/bin/llvm-objcopy' '*/bin/llvm-strip' '*/lib/clang/*' '*/include/c++/*' '*/include/x86_64-unknown-linux-gnu/*' '*/lib/x86_64-unknown-linux-gnu/*'
  rm -f llvm.tar.xz
fi
if [ ! -x tools/bin/ninja ]; then
  echo "== ninja"
  curl -sSL -o ninja.zip https://github.com/ninja-build/ninja/releases/download/v1.12.1/ninja-linux.zip
  python3 -c "import zipfile; zipfile.ZipFile('ninja.zip').extractall('tools/bin')"; chmod +x tools/bin/ninja; rm ninja.zip
fi
if [ ! -f tools/lib/libtinfo.so.5 ]; then
  mkdir -p tools/lib
  gcc -shared -fPIC -o tools/lib/libtinfo.so.5 "$HERE/tinfo5.c" -Wl,--version-script="$HERE/tinfo5.map" -Wl,-soname,libtinfo.so.5
fi
export PATH="$B/tools/bin:$PATH"
export LD_LIBRARY_PATH="$B/tools/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
if [ ! -d filament ]; then
  echo "== Filament"
  git clone -q --depth 1 -b v1.75.1 https://github.com/google/filament.git
fi
mkdir -p out
cd out
echo "== cmake"
CC=clang CXX=clang++ cmake -G Ninja ../filament -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$B/install" \
  -DFILAMENT_ENABLE_JAVA=ON -DFILAMENT_SKIP_SAMPLES=ON -DFILAMENT_SKIP_SDL2=ON -DFILAMENT_BUILD_FILAMAT=ON \
  -DCMAKE_CXX_FLAGS="-stdlib=libc++" -DCMAKE_EXE_LINKER_FLAGS="-stdlib=libc++ -pthread -L$B/tools/lib/x86_64-unknown-linux-gnu -Wl,-rpath,$B/tools/lib/x86_64-unknown-linux-gnu" \
  -DCMAKE_SHARED_LINKER_FLAGS="-stdlib=libc++ -pthread -L$B/tools/lib/x86_64-unknown-linux-gnu -Wl,-rpath,$B/tools/lib/x86_64-unknown-linux-gnu"
echo "== build"
ninja -j12 install
echo "== listo"
ls -la "$B/install/lib" | head
