# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# A stand-in for ffmpeg_image_transport_msgs for the end-to-end tests (tools/e2e/build_msgs.sh), on distros that do not ship it yet.
cmake_minimum_required(VERSION 3.8)
project(ffmpeg_image_transport_msgs)
find_package(ament_cmake REQUIRED)
find_package(rosidl_default_generators REQUIRED)
find_package(std_msgs REQUIRED)
rosidl_generate_interfaces(${PROJECT_NAME} "msg/FFMPEGPacket.msg" DEPENDENCIES std_msgs)
ament_export_dependencies(rosidl_default_runtime)
ament_package()
