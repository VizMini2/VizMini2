#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# Runs the desktop app image (createDistributable) on another Ubuntu release, without sudo or Docker
# (tools/compat/ubuntu-root.sh): with only the system libraries a desktop has (X11, OpenGL, fonts), against the fake
# robot of this machine's ROS 2 on domain $DOMAIN. Two checks:
#   1. `vizmini2 --check`: Fast DDS loads and sees the robot's nodes.
#   2. `vizmini2 --shot`: the RViz tab (Filament and Compose) renders to $OUT/rviz-<release>.png.
#
#   tools/compat/try-desktop.sh 20.04 [app image]      (default: desktopApp/build/compose/binaries/main/app/vizmini2)
#   tools/compat/try-desktop.sh 20.04 path/to/vizmini2_1.0.0_amd64.deb   (installs it with apt and runs /opt/vizmini2)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
REL="${1:?release, e.g. 20.04}"
DEB=""
case "${2:-}" in
  *.deb) DEB="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; APP=/opt/vizmini2 ;;
  *) APP="$(cd "${2:-$REPO/desktopApp/build/compose/binaries/main/app/vizmini2}" && pwd)" ;;
esac
C="${VIZMINI2_COMPAT:-$HOME/.cache/vizmini2-compat}"
DOMAIN="${DOMAIN:-78}"
OUT="${OUT:-$C/out}"; mkdir -p "$OUT"
ROOT="$HERE/ubuntu-root.sh"
[ -n "$DEB" ] || [ -x "$APP/bin/vizmini2" ] || { echo "no app image in $APP (./gradlew :desktopApp:createDistributable)"; exit 1; }

"$ROOT" "$REL" create
"$ROOT" "$REL" apt update
"$ROOT" "$REL" apt install -y libx11-6 libxext6 libxrender1 libxtst6 libxi6 libfreetype6 fontconfig fonts-dejavu-core \
  libgl1 libgl1-mesa-dri libglx-mesa0 ca-certificates
# The .deb with its dependencies, as a user would install it (apt resolves the Depends of each release).
[ -z "$DEB" ] || "$ROOT" "$REL" apt install -y "$DEB"

if [ -z "${ROS_DISTRO:-}" ]; then
  # shellcheck disable=SC1090
  set +u; source "/opt/ros/$(ls /opt/ros | head -1)/setup.bash"; set -u
fi
setsid env ROS_DOMAIN_ID="$DOMAIN" python3 -u "$REPO/tools/fake_robot.py" > "$OUT/fake_robot-$REL.log" 2>&1 &
PGID="$(ps -o pgid= -p $! | tr -d ' ')"
trap 'kill -TERM -- "-$PGID" 2> /dev/null || true' EXIT
sleep 3

echo "== $REL: $("$ROOT" "$REL" run 'ldd --version | head -1')"
rc=0
if "$ROOT" "$REL" run "'$APP/bin/vizmini2' --check --domain $DOMAIN" > "$OUT/check-$REL.log" 2>&1; then
  echo "check: ok ($(grep -Eo '(Nodes|Nodos) \([0-9]*\)' "$OUT/check-$REL.log" || true))"
else echo "check: FAILED (log: $OUT/check-$REL.log)"; tail -20 "$OUT/check-$REL.log"; rc=1; fi
rm -f "$OUT/rviz-$REL.png"
"$ROOT" "$REL" run "'$APP/bin/vizmini2' --shot '$OUT/rviz-$REL.png' --tab RVIZ --domain $DOMAIN --millis 9000" > "$OUT/shot-$REL.log" 2>&1 || true
if [ -s "$OUT/rviz-$REL.png" ]; then echo "shot: $OUT/rviz-$REL.png"
else echo "shot: FAILED (log: $OUT/shot-$REL.log)"; tail -20 "$OUT/shot-$REL.log"; rc=1; fi
exit $rc
