# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# A stand-in for foxglove_msgs for the end-to-end tests (tools/e2e/build_msgs.sh), on distros that do not ship it yet.
cmake_minimum_required(VERSION 3.8)
project(foxglove_msgs)
find_package(ament_cmake REQUIRED)
find_package(rosidl_default_generators REQUIRED)
find_package(builtin_interfaces REQUIRED)
rosidl_generate_interfaces(${PROJECT_NAME} "msg/CompressedVideo.msg" DEPENDENCIES builtin_interfaces)
ament_export_dependencies(rosidl_default_runtime)
ament_package()
