# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# A stand-in for EasyNavigation's navmap_ros_interfaces (https://github.com/EasyNavigation/NavMap, Apache-2.0: the
# message definitions in msg/ are theirs, unchanged) for tools/navmap_demo.py (tools/e2e/build_msgs.sh), so the demo
# does not need the whole NavMap stack built, and for tools/navmap_file_pub.py (V1: to read the old .navmap files).
cmake_minimum_required(VERSION 3.8)
project(navmap_ros_interfaces)
find_package(ament_cmake REQUIRED)
find_package(rosidl_default_generators REQUIRED)
find_package(std_msgs REQUIRED)
rosidl_generate_interfaces(${PROJECT_NAME} "msg/NavMap.msg" "msg/NavMapSurface.msg" "msg/NavMapLayer.msg" "msg/NavMapLink.msg"
  "msg/NavMapV1.msg" "msg/NavMapLayerV1.msg"
  DEPENDENCIES std_msgs)
ament_export_dependencies(rosidl_default_runtime)
ament_package()
