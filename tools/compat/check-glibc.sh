#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# Fails if one of the desktop app's own native libraries asks for a newer glibc or libstdc++ than Ubuntu 20.04 has
# (GLIBC_2.31, GLIBCXX_3.4.28). Used by the CI and by tools/compat/build-desktop-natives.sh.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
"$REPO/tools/prebuilt/fetch.sh"
MAX_GLIBC=2.31; MAX_CXX=3.4.28
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
for j in tools/jros2-16k/desktop/jros2-desktop.jar tools/filament-desktop/filament-desktop.jar; do
  mkdir -p "$T/$(basename "$j")"; ( cd "$T/$(basename "$j")" && unzip -qo "$REPO/$j" '*linux-x86_64/*.so*' )
done
cp "$REPO/desktopApp/src/jvmMain/resources/natives/linux-x64/libvizmini2-x11.so" "$T/"
bad=0
while read -r f; do
  g="$(objdump -T "$f" | { grep -oE 'GLIBC_[0-9.]+' || true; } | sed 's/GLIBC_//' | sort -Vu | tail -1)"
  x="$(objdump -T "$f" | { grep -oE 'GLIBCXX_[0-9.]+' || true; } | sed 's/GLIBCXX_//' | sort -Vu | tail -1)"
  ok=ok
  [ -n "$g" ] && [ "$(printf '%s\n%s\n' "$g" "$MAX_GLIBC" | sort -V | tail -1)" != "$MAX_GLIBC" ] && ok=NEWER
  [ -n "$x" ] && [ "$(printf '%s\n%s\n' "$x" "$MAX_CXX" | sort -V | tail -1)" != "$MAX_CXX" ] && ok=NEWER
  printf '%-6s GLIBC %-5s GLIBCXX %-7s %s\n' "$ok" "${g:--}" "${x:--}" "${f#$T/}"
  [ "$ok" = ok ] || bad=1
done < <(find "$T" -name '*.so*' -type f | sort)
[ "$bad" = 0 ] && echo "all run on Ubuntu 20.04 or newer" || { echo "some need a newer system than Ubuntu 20.04 (tools/compat/build-desktop-natives.sh)"; exit 1; }
