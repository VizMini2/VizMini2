#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
#
# The messages of the test servers that some distros lack (Lyrical does not have them yet): foxglove_msgs and
# ffmpeg_image_transport_msgs (tools/video_publisher.py), slam_toolbox's services (tools/fake_map_services.py) and
# EasyNavigation's navmap_ros_interfaces (tools/navmap_demo.py).
# Builds the stand-ins of tools/e2e/msgs that are missing into build/e2e/msgs_ws. Prints the setup file to source ("" if nothing is needed).
#
#   source "$(tools/e2e/build_msgs.sh)"     (with a sourced ROS 2)
set -euo pipefail
cd "$(dirname "$0")/../.."
missing=()
for p in foxglove_msgs.msg ffmpeg_image_transport_msgs.msg slam_toolbox.srv navmap_ros_interfaces.msg; do
  python3 -c "import $p" 2> /dev/null || missing+=("${p%.*}")
done
if [ ${#missing[@]} -eq 0 ]; then echo /dev/null; exit 0; fi
WS=build/e2e/msgs_ws
if [ ! -f "$WS/install/setup.bash" ] || [ -n "$(find tools/e2e/msgs -newer "$WS/install/setup.bash" -print -quit)" ]; then
  mkdir -p "$WS"
  ( cd "$WS" && colcon --log-base log build --base-paths ../../../tools/e2e/msgs --packages-select "${missing[@]}" > build.log 2>&1 ) \
    || { tail -20 "$WS/build.log" >&2; exit 1; }
fi
echo "$PWD/$WS/install/setup.bash"
