#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end tests of the desktop app against real ROS 2 servers on this machine: starts the fake robot, the fake
# Nav2, MoveIt with the Panda, the Panda under ros2_control in /rc, H.264/H.265 video, the fake map services and a NavMap
# (in their own process group, on domain $VIZMINI2_E2E_DOMAIN, 77 by default), runs `./gradlew :desktopApp:jvmTest` with
# VIZMINI2_E2E=1 and stops them. Needs a sourced ROS 2 (or DISTRO=lyrical...).
#
#   tools/e2e/run.sh [gradle args...]          e.g. tools/e2e/run.sh --tests '*Nav2*'
#   NO_MOVEIT=1 tools/e2e/run.sh               without MoveIt (its tests are skipped)
#   NO_CONTROL=1 tools/e2e/run.sh              without ros2_control (the same)
#   NO_VIDEO=1 tools/e2e/run.sh                without the video publisher (the same)
#   NO_MAPS=1 tools/e2e/run.sh                 without the fake map services (the same)
#   NO_NAVMAP=1 tools/e2e/run.sh               without the NavMap demo (the same)
set -euo pipefail
cd "$(dirname "$0")/../.."
DOMAIN="${VIZMINI2_E2E_DOMAIN:-77}"
if [ -z "${ROS_DISTRO:-}" ]; then
  DISTRO="${DISTRO:-$(ls /opt/ros | head -1)}"
  # shellcheck disable=SC1090
  set +u; source "/opt/ros/$DISTRO/setup.bash"; set -u
fi
LOGS=build/e2e; mkdir -p "$LOGS"
servers="python3 -u tools/fake_robot.py > $LOGS/fake_robot.log 2>&1 &
python3 -u tools/nav2_waypoints_server.py > $LOGS/nav2.log 2>&1 &"
if [ -z "${NO_MOVEIT:-}" ] && [ -d "/opt/ros/$ROS_DISTRO/share/moveit_resources_panda_moveit_config" ]; then
  servers="$servers
ros2 launch tools/moveit_panda_headless.launch.py > $LOGS/moveit.log 2>&1 &"
fi
if [ -z "${NO_CONTROL:-}" ] && [ -x "/opt/ros/$ROS_DISTRO/lib/controller_manager/ros2_control_node" ] \
   && [ -d "/opt/ros/$ROS_DISTRO/share/moveit_resources_panda_moveit_config" ]; then
  servers="$servers
ros2 launch tools/ros2_control_panda.launch.py namespace:=rc > $LOGS/ros2_control.log 2>&1 &"
fi
# H.264/H.265 video (tools/video_publisher.py), with stand-ins for its messages where the distro lacks them.
if [ -z "${NO_VIDEO:-}" ] && command -v ffmpeg > /dev/null && MSGS="$(tools/e2e/build_msgs.sh)"; then
  servers="$servers
(source '$MSGS'; exec python3 -u tools/video_publisher.py) > $LOGS/video.log 2>&1 &"
fi
# Map services (map_saver, SLAM Toolbox, costmaps) for the Maps tab.
if [ -z "${NO_MAPS:-}" ] && MSGS="$(tools/e2e/build_msgs.sh)"; then
  servers="$servers
(source '$MSGS'; exec python3 -u tools/fake_map_services.py) > $LOGS/maps.log 2>&1 &"
fi
# A NavMap (EasyNavigation) with layer updates, for the NavMap display.
if [ -z "${NO_NAVMAP:-}" ] && MSGS="$(tools/e2e/build_msgs.sh)"; then
  servers="$servers
(source '$MSGS'; exec python3 -u tools/navmap_demo.py) > $LOGS/navmap.log 2>&1 &"
fi
setsid env ROS_DOMAIN_ID="$DOMAIN" bash -c "$servers wait" &
PID=$!
PGID="$(ps -o pgid= -p "$PID" | tr -d ' ')"
stop() { kill -TERM -- "-$PGID" 2> /dev/null || true; sleep 2; kill -KILL -- "-$PGID" 2> /dev/null || true; }
trap stop EXIT
sleep 15
VIZMINI2_E2E=1 VIZMINI2_E2E_DOMAIN="$DOMAIN" ./gradlew --no-daemon :desktopApp:jvmTest "$@"
