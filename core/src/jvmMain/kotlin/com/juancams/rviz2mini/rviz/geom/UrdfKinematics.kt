// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.geom

import com.juancams.rviz2mini.TfMath

/**
 * Forward kinematics of a URDF: where each link ends up (relative to the root link) for some joint positions.
 * Revolute/continuous rotate about their axis, prismatic translates, fixed does not move and mimic joints copy theirs.
 * For the ghost robot of a trajectory (MoveIt) without depending on the real robot's TF.
 */
class UrdfKinematics(val model: UrdfModel) {
    private val byChild = model.joints.associateBy { it.child }
    private val byName = model.joints.associateBy { it.name }

    /** The root link (the one that is not the child of any joint). */
    val root: String = model.links.firstOrNull { it !in byChild } ?: model.joints.firstOrNull()?.parent ?: ""

    /** Joints that move, in URDF order. */
    val movableJoints: List<UrdfJoint> = model.joints.filter { it.movable }

    fun joint(name: String) = byName[name]

    /** Position of a joint (mimic joints, derived from theirs). */
    fun position(j: UrdfJoint, positions: Map<String, Double>): Double =
        j.mimic?.let { m -> (positions[m] ?: 0.0) * j.mimicMultiplier + j.mimicOffset } ?: (positions[j.name] ?: 0.0)

    /** link → transform (4×4, column-major) from the root link. */
    fun linkPoses(positions: Map<String, Double>): Map<String, DoubleArray> {
        val out = HashMap<String, DoubleArray>()
        out[root] = TfMath.identity()
        fun pose(link: String, depth: Int): DoubleArray? {
            out[link]?.let { return it }
            if (depth > 512) return null
            val j = byChild[link] ?: return null
            val parent = pose(j.parent, depth + 1) ?: return null
            val local = TfMath.multiply(j.origin, motion(j, position(j, positions)), DoubleArray(16))
            return TfMath.multiply(parent, local, DoubleArray(16)).also { out[link] = it }
        }
        for (l in model.links) pose(l, 0)
        return out
    }

    private fun motion(j: UrdfJoint, q: Double): DoubleArray = when (j.type) {
        "revolute", "continuous" -> {
            val s = Math.sin(q / 2)
            TfMath.fromTranslationQuaternion(0.0, 0.0, 0.0, j.axis[0] * s, j.axis[1] * s, j.axis[2] * s, Math.cos(q / 2))
        }
        "prismatic" -> TfMath.fromTranslationQuaternion(j.axis[0] * q, j.axis[1] * q, j.axis[2] * q, 0.0, 0.0, 0.0, 1.0)
        else -> TfMath.identity()
    }
}
