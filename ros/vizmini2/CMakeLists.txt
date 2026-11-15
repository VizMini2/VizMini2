# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only

cmake_minimum_required(VERSION 3.16)
project(vizmini2 NONE)

find_package(ament_cmake REQUIRED)

# The whole repository (this package lives in ros/vizmini2).
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(APP_IMAGE "${REPO_ROOT}/desktopApp/build/compose/binaries/main/app/vizmini2")

# The desktop app with Gradle: an image with its own Java (jlink), not depending on the system Java.
# Uses the Fast DDS native libraries it bundles (those from jros2), so it works on any distro.
add_custom_target(vizmini2_desktop ALL
  COMMAND "${REPO_ROOT}/gradlew" --no-daemon -q -p "${REPO_ROOT}" :desktopApp:createDistributable
  WORKING_DIRECTORY "${REPO_ROOT}"
  COMMENT "Building the VizMini2 desktop app with Gradle"
  VERBATIM)

install(DIRECTORY "${APP_IMAGE}/" DESTINATION share/${PROJECT_NAME}/app USE_SOURCE_PERMISSIONS)
# `ros2 run vizmini2 vizmini2` looks for the executable in lib/vizmini2/.
install(PROGRAMS scripts/vizmini2 DESTINATION lib/${PROJECT_NAME})

ament_package()
