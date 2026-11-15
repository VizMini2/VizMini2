// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.display

import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.ros.RosStrings
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.rviz.geom.MeshBuilder
import com.juancams.rviz2mini.rviz.geom.UrdfKinematics
import com.juancams.rviz2mini.rviz.geom.UrdfParser
import java.util.Locale

/**
 * Trajectory (like MoveIt's display in RViz): the planned trajectory (`/display_planned_path`,
 * moveit_msgs/DisplayTrajectory) is traversed by a ghost robot, translucent and single-colored. The robot's shape comes from its
 * URDF (`/robot_description`) and each link's position from the kinematics ([UrdfKinematics]); the ghost robot sits
 * where the real robot's base is in TF. Looping or once (and it stays at the end).
 */
class TrajectoryDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {

    private class Traj(val joints: List<String>, val times: DoubleArray, val positions: List<DoubleArray>, val start: Map<String, Double>) {
        val duration: Double get() = times.lastOrNull() ?: 0.0
    }

    @Volatile private var kin: UrdfKinematics? = null
    @Volatile private var traj: Traj? = null
    @Volatile private var trajVersion = 0
    @Volatile private var modelVersion = 0
    private var builtModel = -1
    private var playStartMs = 0L
    private var playedVersion = -1
    private var meshInfo = ""
    private var dirty = false
    private val links = HashMap<String, GeometrySet>()
    /** The MoveIt panel's goal ([com.juancams.rviz2mini.moveit.MoveItGoal]), in blue. */
    private val goalLinks = HashMap<String, GeometrySet>()
    private var subTraj: DynRos.Subscription? = null
    private var subModel: DynRos.Subscription? = null

    override fun onActivate() {
        val reg = Interfaces.get()
        subModel = runCatching {
            DynRos.subscribe(DynRos.normalizeName(config.string("description").ifBlank { "/robot_description" }), reg.msg("std_msgs/msg/String"), BagRecorder.qos(true)) { v ->
                val xml = RosStrings.fix(v["data"] as? String ?: return@subscribe)
                runCatching { UrdfParser.parse(xml) }.getOrNull()?.let { kin = UrdfKinematics(it); modelVersion++ }
            }
        }.getOrNull()
        subTraj = runCatching {
            DynRos.subscribe(DynRos.normalizeName(config.topic), reg.msg("moveit_msgs/msg/DisplayTrajectory"), config.qosProfile()) { v ->
                parse(v)?.let { traj = it; trajVersion++ }
            }
        }.getOrElse { setStatus(StatusLevel.ERROR, it.message ?: "?"); null }
    }

    override fun onDeactivate() {
        subTraj?.close(); subTraj = null
        subModel?.close(); subModel = null
    }

    override fun onConfigChanged(old: DisplayConfig) {
        if (old.string("meshUrl") != config.string("meshUrl")) MeshCache.forgetFailures()
        if (old.string("color") != config.string("color") || old.string("alpha") != config.string("alpha") || old.string("meshUrl") != config.string("meshUrl")) dirty = true
        if (active && (old.topic != config.topic || old.string("description") != config.string("description"))) { onDeactivate(); onActivate() }
    }

    private fun parse(v: Map<String, Any?>): Traj? {
        val rt = (v["trajectory"] as? List<*>)?.lastOrNull() as? Map<*, *> ?: return null
        val jt = rt["joint_trajectory"] as? Map<*, *> ?: return null
        val joints = (jt["joint_names"] as? List<*>)?.map { it.toString() } ?: return null
        val pts = (jt["points"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: return null
        if (joints.isEmpty() || pts.isEmpty()) return null
        val times = DoubleArray(pts.size) { i ->
            val t = pts[i]["time_from_start"] as? Map<*, *>
            ((t?.get("sec") as? Number)?.toDouble() ?: 0.0) + ((t?.get("nanosec") as? Number)?.toDouble() ?: 0.0) / 1e9
        }
        val positions = pts.map { p -> (p["positions"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 }?.toDoubleArray() ?: DoubleArray(joints.size) }
        // Joints not in the trajectory (another group), as in the start state.
        val start = HashMap<String, Double>()
        ((v["trajectory_start"] as? Map<*, *>)?.get("joint_state") as? Map<*, *>)?.let { js ->
            val n = js["name"] as? List<*> ?: emptyList<Any>(); val p = js["position"] as? List<*> ?: emptyList<Any>()
            for (i in n.indices) (p.getOrNull(i) as? Number)?.toDouble()?.let { start[n[i].toString()] = it }
        }
        return Traj(joints, times, positions, start)
    }

    /** Positions at instant `t` (s), interpolating between points. */
    private fun at(tr: Traj, t: Double): Map<String, Double> {
        val out = HashMap(tr.start)
        var i = tr.times.indexOfFirst { it >= t }
        if (i < 0) i = tr.times.size - 1
        val p1 = tr.positions[i]
        if (i == 0 || tr.times[i] <= tr.times[i - 1]) { for (k in tr.joints.indices) out[tr.joints[k]] = p1.getOrElse(k) { 0.0 }; return out }
        val p0 = tr.positions[i - 1]
        val a = ((t - tr.times[i - 1]) / (tr.times[i] - tr.times[i - 1])).coerceIn(0.0, 1.0)
        for (k in tr.joints.indices) out[tr.joints[k]] = p0.getOrElse(k) { 0.0 } + (p1.getOrElse(k) { 0.0 } - p0.getOrElse(k) { 0.0 }) * a
        return out
    }

    private fun rebuild(k: UrdfKinematics) {
        val tint = Colors.withAlpha(config.color("color"), config.number("alpha").toFloat())
        val (builders, info) = buildUrdfLinkMeshes(k.model, config.string("meshUrl"), true, false, config.number("alpha").toFloat(), tint) { dirty = true }
        for (l in links.keys - builders.keys) links.remove(l)?.destroy()
        for ((l, b) in builders) links.getOrPut(l) { GeometrySet(host.engine, host.scene).also { it.setVisible(active) } }.update(b)
        meshInfo = info
        val (goal, _) = buildUrdfLinkMeshes(k.model, config.string("meshUrl"), true, false, 0.35f, Colors.withAlpha(GOAL_COLOR, 0.35f)) { dirty = true }
        for (l in goalLinks.keys - goal.keys) goalLinks.remove(l)?.destroy()
        for ((l, b) in goal) goalLinks.getOrPut(l) { GeometrySet(host.engine, host.scene).also { it.setVisible(false) } }.update(b)
    }

    /** The blue ghost robot at the goal, if the panel has one the robot has not reached yet. */
    private fun drawGoal(k: UrdfKinematics, base: DoubleArray) {
        val goal = if (config.bool("showGoal")) com.juancams.rviz2mini.moveit.MoveItGoal.pending() else null
        if (goal == null) { for (g in goalLinks.values) g.setVisible(false); return }
        val poses = k.linkPoses(goal)
        val tmp = DoubleArray(16); val f = FloatArray(16)
        for ((link, g) in goalLinks) {
            val p = poses[link] ?: continue
            TfMath.multiply(base, p, tmp)
            g.setVisible(true); g.setTransform(TfMath.toFloat(tmp, f))
        }
    }

    override fun render(ctx: FrameContext) {
        if (!active) return
        val k = kin
        val tr = traj
        if (k == null) { setStatus(StatusLevel.WARN, "Waiting for the robot description on ${config.string("description").ifBlank { "/robot_description" }}"); hide(); return }
        if (builtModel != modelVersion || dirty) { rebuild(k); builtModel = modelVersion; dirty = false }
        val root = ctx.transformD(k.root)?.copyOf()
        if (root != null) drawGoal(k, root) else for (g in goalLinks.values) g.setVisible(false)
        if (tr == null) { setStatus(StatusLevel.OK, "No planned path yet (plan with MoveIt)"); for (g in links.values) g.setVisible(false); return }
        if (playedVersion != trajVersion) { playedVersion = trajVersion; playStartMs = ctx.nowMs }
        val speed = config.number("speed").coerceAtLeast(0.05)
        val elapsed = (ctx.nowMs - playStartMs) / 1000.0 * speed
        // Looping, with a short pause at the end so you can see where it ends.
        val t = if (config.bool("loop") && tr.duration > 0) elapsed % (tr.duration + 0.6) else elapsed
        val poses = k.linkPoses(at(tr, t.coerceAtMost(tr.duration)))
        val base = ctx.transformD(k.root)?.copyOf()
        if (base == null) { setStatus(StatusLevel.WARN, "No TF for ${k.root}"); hide(); return }
        val tmp = DoubleArray(16); val f = FloatArray(16)
        for ((link, g) in links) {
            val p = poses[link] ?: continue
            TfMath.multiply(base, p, tmp)
            g.setVisible(true); g.setTransform(TfMath.toFloat(tmp, f))
        }
        val text = String.format(Locale.ROOT, "%d points · %.1f s", tr.positions.size, tr.duration) + if (meshInfo.isNotEmpty()) " · $meshInfo" else ""
        setStatus(if (meshInfo.contains("without mesh server") || meshInfo.contains("error")) StatusLevel.WARN else StatusLevel.OK, text)
    }

    private fun hide() { for (g in links.values) g.setVisible(false); for (g in goalLinks.values) g.setVisible(false) }

    override fun setVisible(v: Boolean) { if (!v) hide() }

    override fun destroy() {
        super.destroy()
        onDeactivate()
        for (g in links.values) g.destroy()
        for (g in goalLinks.values) g.destroy()
        links.clear(); goalLinks.clear()
    }

    companion object { val GOAL_COLOR = Colors.rgb(66, 165, 245) }
}
