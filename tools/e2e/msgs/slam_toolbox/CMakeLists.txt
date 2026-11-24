# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# A stand-in for slam_toolbox's services (only the three the Maps tab calls) for the end-to-end tests
# (tools/e2e/build_msgs.sh), on distros that do not ship slam_toolbox yet.
cmake_minimum_required(VERSION 3.8)
project(slam_toolbox)
find_package(ament_cmake REQUIRED)
find_package(rosidl_default_generators REQUIRED)
find_package(std_msgs REQUIRED)
rosidl_generate_interfaces(${PROJECT_NAME} "srv/SaveMap.srv" "srv/SerializePoseGraph.srv" "srv/Pause.srv" DEPENDENCIES std_msgs)
ament_export_dependencies(rosidl_default_runtime)
ament_package()
