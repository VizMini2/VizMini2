# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
#
# The ROS 2 package (package.xml): the desktop app built with Gradle, its jars installed in share/vizmini2/java and run
# with the system's Java by lib/vizmini2/vizmini2 (`ros2 run vizmini2 vizmini2`). The whole repository is the package.
cmake_minimum_required(VERSION 3.16)
project(vizmini2 NONE)

find_package(ament_cmake REQUIRED)

# Its native libraries (Fast DDS, Filament) are built for x86-64 only: elsewhere, say so instead of building a package
# that cannot start.
if(NOT CMAKE_HOST_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64|amd64)$")
  message(FATAL_ERROR "VizMini2 needs an x86-64 PC: its native libraries (Fast DDS, Filament) are only built for "
    "x86-64, and this is ${CMAKE_HOST_SYSTEM_PROCESSOR}.")
endif()

# Gradle with the system's JDK (-Pvizmini2.jdk=current: no JDK of another version downloaded) and its files under the
# build folder (a package build may have no usable home folder).
set(GRADLE_HOME "${CMAKE_CURRENT_BINARY_DIR}/gradle-home")
set(APP_JARS "${CMAKE_CURRENT_SOURCE_DIR}/desktopApp/build/ros/java")
add_custom_target(vizmini2_desktop ALL
  COMMAND "${CMAKE_COMMAND}" -E env "GRADLE_USER_HOME=${GRADLE_HOME}"
    "${CMAKE_CURRENT_SOURCE_DIR}/gradlew" --no-daemon --console=plain -Pvizmini2.jdk=current
    "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8" :desktopApp:rosJars
  WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
  COMMENT "Building the VizMini2 desktop app with Gradle"
  VERBATIM)

install(DIRECTORY "${APP_JARS}/" DESTINATION share/${PROJECT_NAME}/java)
# `ros2 run vizmini2 vizmini2` looks for the executable in lib/vizmini2/.
install(PROGRAMS scripts/vizmini2 DESTINATION lib/${PROJECT_NAME})

ament_package()
