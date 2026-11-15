# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""The example Panda under a real ros2_control controller manager with mock hardware (to test the Controllers panel).

ros2_control_node with the Panda description of moveit_resources_panda_moveit_config (two hardware components:
PandaFakeSystem and PandaHandFakeSystem), robot_state_publisher, and the controllers of tools/ros2_control_panda.yaml:
joint_state_broadcaster, panda_arm_controller (joint trajectory) and panda_hand_controller (forward command) active,
panda_arm_position_controller loaded but inactive (it uses the same joints as panda_arm_controller).

    source /opt/ros/<distro>/setup.bash
    ros2 launch tools/ros2_control_panda.launch.py [namespace:=rc]

With a namespace everything goes under it (/rc/controller_manager, /rc/joint_states, /rc/robot_description), so it
can run beside MoveIt's Panda (tools/e2e/run.sh does that).

Needs ros-<distro>-ros2-control, ros-<distro>-ros2-controllers, ros-<distro>-xacro and
ros-<distro>-moveit-resources-panda-moveit-config.
"""
import os

import xacro
from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, OpaqueFunction
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node


def generate_launch_description():
    return LaunchDescription([
        DeclareLaunchArgument("namespace", default_value="", description="namespace of the robot and its controller manager"),
        OpaqueFunction(function=nodes),
    ])


def nodes(context):
    ns = LaunchConfiguration("namespace").perform(context).strip("/")
    manager = f"/{ns}/controller_manager" if ns else "/controller_manager"
    config = get_package_share_directory("moveit_resources_panda_moveit_config")
    urdf = xacro.process_file(os.path.join(config, "config", "panda.urdf.xacro"),
                              mappings={"ros2_control_hardware_type": "mock_components"}).toxml()
    controllers = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ros2_control_panda.yaml")
    description = {"robot_description": urdf}

    def spawner(name, *extra):
        return Node(package="controller_manager", executable="spawner", output="screen",
                    namespace=ns, arguments=[name, "--controller-manager", manager, "--param-file", controllers, *extra])

    return [
        # Humble's ros2_control_node takes the description as a parameter; newer ones read the topic (both given).
        # Each controller's parameters go with its spawner (--param-file), which every distro since Humble accepts.
        Node(package="controller_manager", executable="ros2_control_node", output="screen", namespace=ns,
             parameters=[description, controllers]),
        Node(package="robot_state_publisher", executable="robot_state_publisher", output="screen", namespace=ns, parameters=[description]),
        spawner("joint_state_broadcaster"),
        spawner("panda_arm_controller"),
        spawner("panda_hand_controller"),
        spawner("panda_arm_position_controller", "--inactive"),
    ]
