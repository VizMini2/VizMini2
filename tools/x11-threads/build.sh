#!/bin/sh
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# Builds libvizmini2-x11.so (see x11threads.c) into the desktop app resources.
set -e
cd "$(dirname "$0")"
OUT=../../desktopApp/src/jvmMain/resources/natives/linux-x64/libvizmini2-x11.so
CC=${CC:-$(command -v cc || command -v gcc || echo "$HOME/.cache/vizmini2-filament/tools/bin/clang")}
"$CC" -O2 -fPIC -shared -o "$OUT" x11threads.c -ldl
echo "$OUT"
