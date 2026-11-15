#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# An Ubuntu root file system to build and try the desktop app on older releases, without sudo or Docker: the official
# ubuntu-base tarball, entered with an unprivileged user namespace (we are "root" inside, ourselves outside) and
# chroot. Inside, apt runs under fakeroot (only one user id is mapped, so chown to other users is faked).
#
#   tools/compat/ubuntu-root.sh 20.04 create                     # download and prepare (~/.cache/vizmini2-compat/20.04)
#   tools/compat/ubuntu-root.sh 20.04 apt install -y cmake       # apt inside
#   tools/compat/ubuntu-root.sh 20.04 run 'gcc --version'        # a shell command inside (as root)
#
# $HOME, /tmp, /dev, /sys, the X11 socket and /run/user are shared with the host; /proc is the namespace's own.
set -euo pipefail
REL="${1:?release, e.g. 20.04}"; CMD="${2:?create | apt | run}"; shift 2
BASE="${VIZMINI2_COMPAT:-$HOME/.cache/vizmini2-compat}"
R="$BASE/$REL"
declare -A POINT=([20.04]=20.04.5 [22.04]=22.04.5 [24.04]=24.04.3 [26.04]=26.04)

enter() {
  # $1: the command for bash -c inside the root.
  unshare --user --map-root-user --mount --pid --fork --kill-child bash -c '
    R="$1"; shift
    for d in dev sys tmp home run/user; do mkdir -p "$R/$d"; mount --rbind "/$d" "$R/$d" 2>/dev/null || true; done
    mkdir -p "$R/proc"; mount -t proc proc "$R/proc"
    cp -f /etc/resolv.conf "$R/etc/resolv.conf" 2>/dev/null || true
    exec chroot "$R" /usr/bin/env -i HOME=/root TERM=xterm DISPLAY="${DISPLAY:-}" XAUTHORITY="${XAUTHORITY:-}" \
      XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-}" LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -c "$1"
  ' _ "$R" "$1"
}

# Maintainer scripts that chown to a system user or group (fontconfig: root:staff) fail with only one id mapped:
# chown and chgrp that ignore that, ahead of the real ones in PATH.
wrappers() {
  mkdir -p "$R/usr/local/sbin"
  for c in chown chgrp; do printf '#!/bin/sh\n/bin/%s "$@" 2> /dev/null\nexit 0\n' "$c" > "$R/usr/local/sbin/$c"; chmod +x "$R/usr/local/sbin/$c"; done
}

case "$CMD" in
  create)
    [ -x "$R/bin/bash" ] && { echo "already there: $R"; exit 0; }
    mkdir -p "$R"
    url="https://cdimage.ubuntu.com/ubuntu-base/releases/$REL/release/ubuntu-base-${POINT[$REL]}-base-amd64.tar.gz"
    echo "== $url"
    curl -fsSL "$url" | tar -xz -C "$R" --exclude='dev/*' 2>/dev/null || true
    [ -x "$R/bin/bash" ] || { echo "download failed"; exit 1; }
    # apt as the only user there is (no _apt sandbox user), no recommends, and the chown wrappers in its PATH.
    printf 'APT::Sandbox::User "root";\nAPT::Install-Recommends "false";\nDpkg::Use-Pty "false";\nDPkg::Path "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";\n' > "$R/etc/apt/apt.conf.d/99vizmini2"
    rm -f "$R/etc/resolv.conf"; touch "$R/etc/resolv.conf"
    wrappers
    # fakeroot from the release itself (dpkg would fail on chown to system users otherwise).
    enter 'apt-get update -qq && cd /tmp && apt-get download -qq fakeroot libfakeroot && dpkg -i --force-all /tmp/fakeroot_*.deb /tmp/libfakeroot_*.deb >/dev/null 2>&1; rm -f /tmp/fakeroot_*.deb /tmp/libfakeroot_*.deb; fakeroot true && echo fakeroot-ok'
    ;;
  apt) [ -x "$R/usr/local/sbin/chown" ] || wrappers; enter "fakeroot dpkg --configure -a > /dev/null 2>&1; fakeroot apt-get -qq $(printf '%q ' "$@")" ;;
  run) enter "$*" ;;
  *) echo "unknown command: $CMD"; exit 2 ;;
esac
