#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
#
# The ROS 2 side of the end-to-end tests, inside a ros:<distro>-ros-base container (used by the CI, one container per
# distro): installs what the test servers need and starts the same servers as tools/e2e/run.sh: the fake robot, the
# fake Nav2, MoveIt with the Panda, the Panda under ros2_control in /rc, H.264/H.265 video, the fake map services and
# a NavMap (tools/navmap_demo.py).
# Writes "servers ready" to build/e2e/servers.log. ROS_DOMAIN_ID comes from the environment.
set -eo pipefail
# shellcheck disable=SC1090
source "/opt/ros/$ROS_DISTRO/setup.bash"
mkdir -p build/e2e
LOG=build/e2e/servers.log
: > "$LOG"
apt-get update -qq
apt-get install -y -qq "ros-$ROS_DISTRO-nav2-msgs" "ros-$ROS_DISTRO-control-msgs" "ros-$ROS_DISTRO-tf2-ros" \
  "ros-$ROS_DISTRO-moveit" "ros-$ROS_DISTRO-moveit-resources-panda-moveit-config" "ros-$ROS_DISTRO-robot-state-publisher" \
  "ros-$ROS_DISTRO-ros2-control" "ros-$ROS_DISTRO-ros2-controllers" "ros-$ROS_DISTRO-xacro" \
  ffmpeg python3-colcon-common-extensions > /dev/null
# Released on some distros only: where they are missing, tools/e2e/build_msgs.sh builds stand-ins.
for p in foxglove-msgs ffmpeg-image-transport-msgs; do apt-get install -y -qq "ros-$ROS_DISTRO-$p" > /dev/null 2>&1 || true; done
MSGS="$(tools/e2e/build_msgs.sh)"
echo "distro $ROS_DISTRO, domain ${ROS_DOMAIN_ID:-0}" >> "$LOG"
python3 -u tools/fake_robot.py > build/e2e/fake_robot.log 2>&1 &
python3 -u tools/nav2_waypoints_server.py > build/e2e/nav2.log 2>&1 &
ros2 launch tools/moveit_panda_headless.launch.py > build/e2e/moveit.log 2>&1 &
ros2 launch tools/ros2_control_panda.launch.py namespace:=rc > build/e2e/ros2_control.log 2>&1 &
(source "$MSGS"; exec python3 -u tools/video_publisher.py) > build/e2e/video.log 2>&1 &
(source "$MSGS"; exec python3 -u tools/fake_map_services.py) > build/e2e/maps.log 2>&1 &
(source "$MSGS"; exec python3 -u tools/navmap_demo.py) > build/e2e/navmap.log 2>&1 &
for _ in $(seq 1 90); do grep -q "You can start planning now" build/e2e/moveit.log && break; sleep 2; done
echo "servers ready" >> "$LOG"
wait
