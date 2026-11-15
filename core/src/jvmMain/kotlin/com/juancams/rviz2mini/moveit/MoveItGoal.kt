// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.moveit

/**
 * The MoveIt panel's goal (joint → value), so the 3D Trajectory display shows it as a blue ghost
 * robot before planning. Within the app, without going through ROS: the last panel that changes the goal wins.
 */
object MoveItGoal {
    @Volatile var joints: Map<String, Double>? = null
        private set
    @Volatile var version = 0
        private set

    /** The MoveIt panel currently in use (to move the gripper by dragging it in the 3D view). */
    @Volatile var client: MoveItClient? = null

    /** Where the robot is (so as not to show the goal once it has arrived). */
    @Volatile var current: Map<String, Double> = emptyMap()

    fun set(j: Map<String, Double>?) { joints = j; version++ }

    /** The goal, if the robot is not there yet (some joint more than `tol` away). */
    fun pending(tol: Double = 0.01): Map<String, Double>? {
        val g = joints ?: return null
        val c = current
        if (c.isEmpty()) return g
        return g.takeIf { it.any { (j, v) -> c[j]?.let { Math.abs(it - v) > tol } ?: false } }
    }
}
