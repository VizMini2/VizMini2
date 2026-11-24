#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# Builds bt_groot_server against the BehaviorTree.CPP of the sourced ROS 2 distro (jazzy if there is
# none). With RPATH and not RUNPATH: that way it wins over an LD_LIBRARY_PATH from another distro (e.g. rolling).
set -euo pipefail
cd "$(dirname "$0")"
ROS=/opt/ros/${ROS_DISTRO:-jazzy}
g++ -std=c++17 -O1 bt_groot_server.cpp -o bt_groot_server \
  -I"$ROS/include" -L"$ROS/lib" -Wl,--disable-new-dtags,-rpath,"$ROS/lib" -lbehaviortree_cpp -lpthread
