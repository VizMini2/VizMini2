#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# Builds the ROS 2 package's Debian package (ros-<distro>-vizmini2) the way ROS's buildfarm does, and tries it: inside a
# clean ros:<distro>-ros-base container, only the dependencies package.xml declares (rosdep), the debian/ folder that
# bloom generates from it, and the build without a usable home folder; then the package is installed and the app
# started (--check). The .deb and the build log go to the output folder.
#
# Usage (from the repository): docker run --rm -v "$PWD":/src:ro -v /tmp/out:/out ros:jazzy-ros-base \
#                                bash /src/tools/ros/build-deb.sh
# The distro is the container's (ROS_DISTRO).
set -euo pipefail
OUT="${OUT:-/out}"
: "${ROS_DISTRO:?run it in a ros:<distro> container}"
export DEBIAN_FRONTEND=noninteractive
mkdir -p "$OUT"
# apt gives up on a mirror that stops answering instead of waiting for ever.
echo 'Acquire::http::Timeout "60"; Acquire::https::Timeout "60"; Acquire::Retries "3";' > /etc/apt/apt.conf.d/80vizmini2-timeouts

# Up to three tries: a mirror being updated can list a package it does not have yet for a while.
for i in 1 2 3; do
  apt-get update -qq && apt-get install -y -qq python3-bloom fakeroot debhelper dpkg-dev > /dev/null && break
  [ "$i" -lt 3 ] || exit 1
  sleep 30
done

# A clean copy of the sources (the tracked files of a checkout, without builds or caches).
SRC=/tmp/vizmini2_src
rm -rf "$SRC"
mkdir -p "$SRC"
(cd /src && tar --exclude=./build --exclude='./*/build' --exclude=./.gradle --exclude=./.kotlin --exclude='./*/bin' \
  --exclude=./debian --exclude='./tools/jros2-16k/work' -cf - .) | tar -xf - -C "$SRC"
cd "$SRC"

echo "== rosdep install (only what package.xml declares)"
rosdep update -q > /dev/null 2>&1 || rosdep update
rosdep install --from-paths . --ignore-src -y --rosdistro "$ROS_DISTRO"
java -version 2>&1 | head -1

echo "== bloom-generate rosdebian ($ROS_DISTRO)"
bloom-generate rosdebian --ros-distro "$ROS_DISTRO"

echo "== debian/rules binary"
if ! HOME=/nonexistent fakeroot debian/rules binary > "$OUT/build.log" 2>&1; then
  tail -60 "$OUT/build.log"
  exit 1
fi
DEB="$(ls ../ros-"$ROS_DISTRO"-vizmini2_*.deb)"
cp "$DEB" "$OUT/"
dpkg-deb -I "$DEB" | grep -E ' (Version|Installed-Size|Depends):'
ls -la "$DEB"

echo "== install and start it"
apt-get install -y -qq "$DEB" > /dev/null
"/opt/ros/$ROS_DISTRO/lib/vizmini2/vizmini2" --check > "$OUT/check.log" 2>&1 || true
grep -m1 'Status: Connected' "$OUT/check.log"
echo "OK: $(basename "$DEB")"
