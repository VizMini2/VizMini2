// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.moveit

import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry

/**
 * The moveit_msgs versions with a different wire format (each distro's compared with Lyrical's, which
 * are the ones bundled in the app):
 * - Jazzy (2.6.0): MotionPlanRequest without `smoothness_level` (it is in the middle of the MoveGroup goal).
 * - Humble (2.2.3): additionally, MoveItErrorCodes without `message` or `source` (it is in every response), ExecuteTrajectory without
 *   `controller_names` and GetCartesianPath without the two Cartesian velocity fields.
 * Kilted (2.7.1) is the same as Lyrical.
 */
enum class MoveItMsgs(val labelKey: String) {
    AUTO("moveit_msgs_auto"), V27("moveit_msgs_v27"), V26("moveit_msgs_v26"), V22("moveit_msgs_v22");

    companion object {
        private val cache = java.util.WeakHashMap<InterfaceRegistry, MutableMap<MoveItMsgs, InterfaceRegistry>>()

        private const val PLAN_REQUEST_26 = """WorkspaceParameters workspace_parameters
RobotState start_state
Constraints[] goal_constraints
Constraints path_constraints
TrajectoryConstraints trajectory_constraints
GenericTrajectory[] reference_trajectories
string pipeline_id
string planner_id
string group_name
int32 num_planning_attempts
float64 allowed_planning_time
float64 max_velocity_scaling_factor
float64 max_acceleration_scaling_factor
string cartesian_speed_limited_link
float64 max_cartesian_speed"""

        /** The registry with that version's definitions (V27/AUTO: the app's one). */
        @Synchronized fun registry(base: InterfaceRegistry, v: MoveItMsgs): InterfaceRegistry {
            if (v == AUTO || v == V27) return base
            return cache.getOrPut(base) { HashMap() }.getOrPut(v) {
                base.copy().apply {
                    define("moveit_msgs/msg/MotionPlanRequest", PLAN_REQUEST_26)
                    if (v == V22) {
                        define("moveit_msgs/msg/MoveItErrorCodes", "int32 val")
                        define("moveit_msgs/action/ExecuteTrajectory", "RobotTrajectory trajectory\n---\nMoveItErrorCodes error_code\n---\nstring state")
                        define("moveit_msgs/srv/GetCartesianPath", """std_msgs/Header header
RobotState start_state
string group_name
string link_name
geometry_msgs/Pose[] waypoints
float64 max_step
float64 jump_threshold
float64 prismatic_jump_threshold
float64 revolute_jump_threshold
bool avoid_collisions
Constraints path_constraints
float64 max_velocity_scaling_factor
float64 max_acceleration_scaling_factor
---
RobotState start_state
RobotTrajectory solution
float64 fraction
MoveItErrorCodes error_code""")
                    }
                }
            }
        }
    }
}
