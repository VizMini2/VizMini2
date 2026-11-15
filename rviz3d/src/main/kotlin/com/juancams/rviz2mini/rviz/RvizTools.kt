// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.display.GeometrySet
import com.juancams.rviz2mini.rviz.display.PickedPoint
import com.juancams.rviz2mini.rviz.display.PointPicker
import com.juancams.rviz2mini.rviz.geom.Measure
import com.juancams.rviz2mini.rviz.geom.MeshBuilder
import com.juancams.rviz2mini.rviz.geom.yawPose
import java.util.Locale

/** RViz toolbar tools (like RViz's: 2D Goal Pose, 2D Pose Estimate, Publish Point, Measure, Select). */
enum class RvizTool(val labelKey: String, val color: Int) {
    NONE("", 0), GOAL("rviz_tool_goal", 0xFF4CAF50.toInt()), POSE_ESTIMATE("rviz_tool_pose", 0xFFE040FB.toInt()),
    PUBLISH_POINT("rviz_tool_point", 0xFFFFC107.toInt()), MEASURE("rviz_tool_measure", 0xFF29B6F6.toInt()),
    INTERACT("rviz_tool_interact", 0xFFFF7043.toInt()), SELECT("rviz_tool_select", 0xFF7C4DFF.toInt()), // violet: white was not visible over the map floor
    WAYPOINTS("rviz_tool_waypoints", 0xFFFF9800.toInt()),
    MOVEIT_GOAL("rviz_tool_moveit", 0xFF42A5F5.toInt())
}

/**
 * The tools over an [RvizScene], without the per-platform part: touches arrive as [down]/[move]/[up]/
 * [cancel] in view pixels; notices ("sent to /goal_pose"...) go out through `onMessage`. Main thread.
 * Interacting with interactive markers is not here (each platform handles it).
 */
class RvizTools(private val scene: RvizScene, private val pxPerDp: Float = 1f, private val onMessage: (String) -> Unit = {}) {
    var tool = RvizTool.NONE; private set
    private var toolStart: DoubleArray? = null
    private var toolYaw = 0.0
    private var preview: GeometrySet? = null
    private var measureA: DoubleArray? = null
    private var measureB: DoubleArray? = null
    var selected: PickedPoint? = null; private set
    var lastToolPose: DoubleArray? = null; private set
    var lastToolPoint: DoubleArray? = null; private set

    /** The Nav2 waypoint route (Waypoints tool); saved with the tab. `onNavChanged`: for the toolbar. */
    var onNavChanged: () -> Unit = {}
    val mission: com.juancams.rviz2mini.nav.Nav2Mission = com.juancams.rviz2mini.nav.Nav2Mission(com.juancams.rviz2mini.ros.dyn.Interfaces.get()) {
        scene.saveSetting(KEY_NAV2, mission.toJson()); drawWaypoints(); onNavChanged()
    }.also { m -> m.load(scene.setting(KEY_NAV2)); m.watchNavigateToPose() }
    private var waypointGeo: GeometrySet? = null
    private var waypointsDrawn = false

    /** The waypoint arrows (numbered in [drawLabels]) and the route line; the current one, larger. */
    fun drawWaypoints() {
        val ws = mission.waypoints
        if (ws.isEmpty()) { waypointGeo?.setVisible(false); return }
        val g = waypointGeo ?: GeometrySet(scene.engine, scene.scene).also { waypointGeo = it }
        val b = MeshBuilder()
        val c = RvizTool.WAYPOINTS.color
        val s = scene.viewCtl.toolScale()
        for ((i, w) in ws.withIndex()) {
            val big = i == mission.current
            b.withTransform(yawPose(w.x, w.y, 0.0)) { sphere(0.06 * s, 0.06 * s, 0.06 * s, c) }
            b.withTransform(yawPose(w.x, w.y, w.yaw)) { arrow(if (big) 1.2 * s else 0.8 * s, if (big) 0xFF4CAF50.toInt() else c) }
            if (i > 0) { val p = ws[i - 1]; b.thickLine(p.x, p.y, 0.02, w.x, w.y, 0.02, c and 0x00FFFFFF or (0xAA shl 24), 0.03 * s) }
        }
        g.update(b); g.setVisible(true); g.setTransform(identity)
    }

    private var goalPublisher: RosConnection.ManagedPublisher<geometry_msgs.PoseStamped>? = null
    private var posePublisher: RosConnection.ManagedPublisher<geometry_msgs.PoseWithCovarianceStamped>? = null
    private var pointPublisher: RosConnection.ManagedPublisher<geometry_msgs.PointStamped>? = null

    val goalTopic get() = scene.setting(KEY_GOAL_TOPIC) ?: "/goal_pose"
    val poseTopic get() = scene.setting(KEY_POSE_TOPIC) ?: "/initialpose"
    val pointTopic get() = scene.setting(KEY_POINT_TOPIC) ?: "/clicked_point"

    fun setTopics(goal: String?, pose: String?, point: String?) {
        goal?.let { scene.saveSetting(KEY_GOAL_TOPIC, it); goalPublisher?.close(); goalPublisher = null }
        pose?.let { scene.saveSetting(KEY_POSE_TOPIC, it); posePublisher?.close(); posePublisher = null }
        point?.let { scene.saveSetting(KEY_POINT_TOPIC, it); pointPublisher?.close(); pointPublisher = null }
    }

    /** Switches tool and returns its help text (null = none). */
    fun setTool(t: RvizTool): String? {
        tool = t
        toolStart = null; measureA = null; measureB = null; selected = null
        grab = null; dragFixed = null; handleVersion = -1
        preview?.setVisible(false)
        return when (t) {
            RvizTool.NONE -> null
            RvizTool.GOAL -> tr("rviz_tool_hint", goalTopic)
            RvizTool.POSE_ESTIMATE -> tr("rviz_tool_hint", poseTopic)
            RvizTool.PUBLISH_POINT -> tr("rviz_tool_point_hint", pointTopic)
            RvizTool.MEASURE -> tr("rviz_tool_measure_hint")
            RvizTool.INTERACT -> tr("rviz_tool_interact_hint")
            RvizTool.SELECT -> tr("rviz_tool_select_hint")
            RvizTool.WAYPOINTS -> tr("rviz_tool_waypoints_hint")
            RvizTool.MOVEIT_GOAL -> if (com.juancams.rviz2mini.moveit.MoveItGoal.client == null) tr("rviz_tool_moveit_none") else tr("rviz_tool_moveit_hint")
        }
    }

    /** Whether touches belong to the tool (otherwise they move the camera). */
    val consumesTouches: Boolean get() = tool != RvizTool.NONE && tool != RvizTool.INTERACT && (tool != RvizTool.MOVEIT_GOAL || grab != null)

    /** Does this touch belong to the tool? (MoveIt: only if it lands on an arrow or a ring; otherwise it moves the camera). */
    fun consumesTouchAt(x: Float, y: Float): Boolean = if (tool == RvizTool.MOVEIT_GOAL) handleAt(x, y) != null else consumesTouches

    // ---------------------------------------------------------------- MoveIt: dragging the gripper

    /** What was grabbed: axis 0-2 (x, y, z of the gripper) and whether it rotates (ring) or translates (arrow). */
    private class Grab(val axis: Int, val rotate: Boolean, val startFixed: DoubleArray, val sx: Float, val sy: Float)
    private var grab: Grab? = null
    /** The pose (fixed) while dragging, or null (the panel's goal pose). */
    private var dragFixed: DoubleArray? = null
    private var handleGeo: GeometrySet? = null
    private var handleVersion = -1

    private fun moveItRootFixed(): DoubleArray? {
        val c = com.juancams.rviz2mini.moveit.MoveItGoal.client ?: return null
        val root = c.rootLink() ?: return null
        val m = DoubleArray(16)
        return if (scene.tfTree.poseInFixed(root, m) || root == scene.fixedFrame) (if (root == scene.fixedFrame) com.juancams.rviz2mini.TfMath.identity() else m) else null
    }

    /** The goal tip in the fixed frame. */
    private fun goalTipFixed(): DoubleArray? {
        val c = com.juancams.rviz2mini.moveit.MoveItGoal.client ?: return null
        val tip = c.tipPose() ?: return null
        val root = moveItRootFixed() ?: return null
        return com.juancams.rviz2mini.TfMath.multiply(root, tip, DoubleArray(16))
    }

    private val UNIT = 0.1
    private fun handleLen() = 0.18
    private fun ringRadius() = 0.13

    private fun axisOf(m: DoubleArray, k: Int) = doubleArrayOf(m[k * 4], m[k * 4 + 1], m[k * 4 + 2])

    /** Points (fixed) of an arrow or a ring, to know whether the finger is over it. */
    private fun handlePoints(m: DoubleArray, k: Int, rotate: Boolean): List<DoubleArray> {
        val o = doubleArrayOf(m[12], m[13], m[14])
        if (!rotate) { val a = axisOf(m, k); return (3..10).map { i -> val t = handleLen() * i / 10; doubleArrayOf(o[0] + a[0] * t, o[1] + a[1] * t, o[2] + a[2] * t) } }
        val u = axisOf(m, (k + 1) % 3); val v = axisOf(m, (k + 2) % 3); val r = ringRadius()
        return (0 until 36).map { i -> val t = 2 * Math.PI * i / 36; doubleArrayOf(o[0] + r * (u[0] * Math.cos(t) + v[0] * Math.sin(t)), o[1] + r * (u[1] * Math.cos(t) + v[1] * Math.sin(t)), o[2] + r * (u[2] * Math.cos(t) + v[2] * Math.sin(t))) }
    }

    /** Where (pixels) a point of the `axis` arrow (at 80 %) or of the ring appears: for the tests. */
    fun handleScreenPoint(axis: Int, rotate: Boolean): FloatArray? {
        val m = goalTipFixed() ?: return null
        val pts = handlePoints(m, axis, rotate)
        return scene.projector()(if (rotate) pts[0] else pts[pts.size * 7 / 10])
    }

    /** Direction the `axis` arrow points on screen (unit vector), for the tests. */
    fun handleScreenDirection(axis: Int): FloatArray? {
        val m = goalTipFixed() ?: return null
        val o = doubleArrayOf(m[12], m[13], m[14]); val a = axisOf(m, axis)
        val p = scene.projector()
        val c = p(o) ?: return null; val e = p(doubleArrayOf(o[0] + a[0] * UNIT, o[1] + a[1] * UNIT, o[2] + a[2] * UNIT)) ?: return null
        val l = Math.hypot((e[0] - c[0]).toDouble(), (e[1] - c[1]).toDouble()).toFloat().takeIf { it > 0 } ?: return null
        return floatArrayOf((e[0] - c[0]) / l, (e[1] - c[1]) / l)
    }

    private fun handleAt(x: Float, y: Float): Pair<Int, Boolean>? {
        val m = goalTipFixed() ?: return null
        val project = scene.projector()
        var best: Pair<Int, Boolean>? = null; var bestD = 30f * pxPerDp
        for (rotate in listOf(false, true)) for (k in 0..2) for (p in handlePoints(m, k, rotate)) {
            val s = project(p) ?: continue
            val d = Math.hypot((s[0] - x).toDouble(), (s[1] - y).toDouble()).toFloat()
            if (d < bestD) { bestD = d; best = k to rotate }
        }
        return best
    }

    private fun moveItDown(x: Float, y: Float) {
        val h = handleAt(x, y) ?: return
        val m = goalTipFixed() ?: return
        grab = Grab(h.first, h.second, m, x, y); dragFixed = m
    }

    private fun moveItMove(x: Float, y: Float) {
        val g = grab ?: return
        val project = scene.projector()
        val m = g.startFixed
        val o = doubleArrayOf(m[12], m[13], m[14])
        val c = project(o) ?: return
        if (!g.rotate) {
            val a = axisOf(m, g.axis)
            // The axis direction on screen, using 10 cm (1 m could end up behind the camera).
            val e = project(doubleArrayOf(o[0] + a[0] * UNIT, o[1] + a[1] * UNIT, o[2] + a[2] * UNIT)) ?: return
            val dx = e[0] - c[0]; val dy = e[1] - c[1]
            val len2 = dx * dx + dy * dy
            if (len2 < 1f) return
            val t = ((x - g.sx) * dx + (y - g.sy) * dy) / len2 * UNIT // meters along the axis
            dragFixed = m.copyOf().also { it[12] += a[0] * t; it[13] += a[1] * t; it[14] += a[2] * t }
        } else {
            // Finger angle around the center, in the direction the ring appears to turn.
            val a0 = Math.atan2((g.sy - c[1]).toDouble(), (g.sx - c[0]).toDouble())
            val a1 = Math.atan2((y - c[1]).toDouble(), (x - c[0]).toDouble())
            var d = a1 - a0; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI
            val pts = handlePoints(m, g.axis, true)
            val p0 = project(pts[0]); val p1 = project(pts[1])
            val sign = if (p0 != null && p1 != null) Math.signum(((p0[0] - c[0]) * (p1[1] - c[1]) - (p0[1] - c[1]) * (p1[0] - c[0])).toDouble()).takeIf { it != 0.0 } ?: 1.0 else 1.0
            val angle = d * sign
            val rot = when (g.axis) {
                0 -> com.juancams.rviz2mini.rviz.geom.UrdfParser.poseFromXyzRpy(0.0, 0.0, 0.0, angle, 0.0, 0.0)
                1 -> com.juancams.rviz2mini.rviz.geom.UrdfParser.poseFromXyzRpy(0.0, 0.0, 0.0, 0.0, angle, 0.0)
                else -> com.juancams.rviz2mini.rviz.geom.UrdfParser.poseFromXyzRpy(0.0, 0.0, 0.0, 0.0, 0.0, angle)
            }
            dragFixed = com.juancams.rviz2mini.TfMath.multiply(m, rot, DoubleArray(16))
        }
        handleVersion = -1
    }

    private fun moveItUp() {
        val target = dragFixed
        grab = null
        val c = com.juancams.rviz2mini.moveit.MoveItGoal.client
        val root = moveItRootFixed()
        if (target == null || c == null || root == null) { dragFixed = null; return }
        val inv = com.juancams.rviz2mini.TfMath.invertRigid(root, DoubleArray(16))
        // While MoveIt searches for the IK it stays where it was released; then, where the goal says.
        c.moveTipTo(com.juancams.rviz2mini.TfMath.multiply(inv, target, DoubleArray(16))) { ok ->
            com.juancams.rviz2mini.platform.Platform.main.post { dragFixed = null; handleVersion = -1; if (!ok) onMessage(tr("moveit_no_ik")) }
        }
    }

    /** The arrows and rings at the goal tip (or where it is being dragged). Every frame, with the tool active. */
    private fun drawMoveItHandles() {
        if (tool != RvizTool.MOVEIT_GOAL) { handleGeo?.setVisible(false); return }
        val m = dragFixed ?: goalTipFixed()
        if (m == null) { handleGeo?.setVisible(false); return }
        val v = com.juancams.rviz2mini.moveit.MoveItGoal.version
        val g = handleGeo ?: GeometrySet(scene.engine, scene.scene).also { handleGeo = it }
        if (v != handleVersion || dragFixed != null) {
            handleVersion = v
            val b = MeshBuilder()
            val colors = intArrayOf(0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt())
            for (k in 0..2) {
                val sel = grab?.axis == k
                // Arrow along the gripper's k axis (arrow points along +X: rotated to y or z).
                val toAxis = when (k) { 0 -> com.juancams.rviz2mini.TfMath.identity()
                    1 -> com.juancams.rviz2mini.rviz.geom.UrdfParser.poseFromXyzRpy(0.0, 0.0, 0.0, 0.0, 0.0, Math.PI / 2)
                    else -> com.juancams.rviz2mini.rviz.geom.UrdfParser.poseFromXyzRpy(0.0, 0.0, 0.0, 0.0, -Math.PI / 2, 0.0) }
                b.withTransform(com.juancams.rviz2mini.TfMath.multiply(m, toAxis, DoubleArray(16))) { arrow(handleLen(), colors[k], shaftRadius = if (sel && grab?.rotate == false) 0.012 else 0.007) }
                val pts = handlePoints(m, k, true)
                val w = if (sel && grab?.rotate == true) 0.012 else 0.006
                for (i in pts.indices) { val a = pts[i]; val c = pts[(i + 1) % pts.size]; b.thickLine(a[0], a[1], a[2], c[0], c[1], c[2], colors[k], w) }
            }
            g.update(b)
        }
        g.setVisible(true); g.setTransform(identity)
    }

    private fun ground(x: Float, y: Float): DoubleArray? {
        val p = DoubleArray(3)
        return if (scene.viewCtl.groundPoint(x, y, scene.width.toFloat(), scene.height.toFloat(), p)) p else null
    }

    fun down(x: Float, y: Float) {
        if (tool == RvizTool.MOVEIT_GOAL) { moveItDown(x, y); return }
        val p = ground(x, y)
        when (tool) {
            RvizTool.PUBLISH_POINT -> if (p != null) { toolStart = p; showPoints(listOf(p), null) }
            RvizTool.MEASURE -> { if (p != null) { measureA = p; measureB = p.copyOf() }; showMeasure() }
            RvizTool.GOAL, RvizTool.POSE_ESTIMATE, RvizTool.WAYPOINTS -> if (p != null) { toolStart = p; toolYaw = 0.0; showArrow() }
            else -> {}
        }
    }

    fun move(x: Float, y: Float) {
        if (tool == RvizTool.MOVEIT_GOAL) { moveItMove(x, y); return }
        val p = ground(x, y)
        when (tool) {
            RvizTool.PUBLISH_POINT -> if (p != null) { toolStart = p; showPoints(listOf(p), null) }
            RvizTool.MEASURE -> { if (p != null && measureA != null) measureB = p; showMeasure() }
            RvizTool.GOAL, RvizTool.POSE_ESTIMATE, RvizTool.WAYPOINTS -> {
                val s = toolStart ?: return
                if (p != null && Math.hypot(p[0] - s[0], p[1] - s[1]) > 0.05) toolYaw = Math.atan2(p[1] - s[1], p[0] - s[0])
                showArrow()
            }
            else -> {}
        }
    }

    fun up(x: Float, y: Float) {
        if (tool == RvizTool.MOVEIT_GOAL) { moveItUp(); return }
        val p = ground(x, y)
        when (tool) {
            RvizTool.PUBLISH_POINT -> { val s = p ?: toolStart ?: return; publishPoint(s[0], s[1], s[2]); setTool(RvizTool.NONE) }
            RvizTool.SELECT -> selectPoint(x, y)
            RvizTool.GOAL, RvizTool.POSE_ESTIMATE -> { val s = toolStart ?: return; publishPose(tool, s[0], s[1], toolYaw); setTool(RvizTool.NONE) }
            // Waypoints: one more in the route; the tool stays active to keep adding.
            RvizTool.WAYPOINTS -> {
                val s = toolStart ?: return
                mission.add(com.juancams.rviz2mini.nav.Nav2Mission.Waypoint(s[0], s[1], toolYaw), scene.fixedFrame)
                toolStart = null; preview?.setVisible(false)
            }
            else -> {}
        }
    }

    fun cancel() {
        if (tool == RvizTool.MOVEIT_GOAL) { grab = null; dragFixed = null; handleVersion = -1; return }
        if (tool == RvizTool.MEASURE) { measureA = null; measureB = null; showMeasure() } else setTool(RvizTool.NONE)
    }

    /** Tool texts in the scene (the measurement, the selected point): every frame, before closing the texts. */
    fun drawLabels() {
        val labels = scene.labelSink
        if (!waypointsDrawn) { waypointsDrawn = true; drawWaypoints() } // the saved ones, on the first frame
        drawMoveItHandles()
        for ((i, w) in mission.waypoints.withIndex())
            labels.label("wp-$i", "${i + 1}", w.x, w.y, 0.35 * scene.viewCtl.toolScale(), RvizTool.WAYPOINTS.color, 15.0, screenDp = true)
        if (tool == RvizTool.SELECT) selected?.let { s ->
            // A single two-line label: two separate ones offset in z overlapped.
            labels.label("tool-select", selectText(s), s.fixed[0], s.fixed[1], s.fixed[2] + 0.15, RvizTool.SELECT.color, 14.0, screenDp = true)
        }
        if (tool != RvizTool.MEASURE) return
        val a = measureA ?: return
        val b = measureB ?: return
        labels.label("tool-measure", Measure.text(a, b), (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2 + 0.05, RvizTool.MEASURE.color, 16.0, screenDp = true)
    }

    fun measureText(): String? = measureA?.let { a -> measureB?.let { b -> Measure.text(a, b) } }

    private fun showMeasure() {
        val a = measureA; val b = measureB
        if (a != null && b != null) showPoints(listOf(a, b), RvizTool.MEASURE.color) else preview?.setVisible(false)
    }

    private fun previewSet() = preview ?: GeometrySet(scene.engine, scene.scene).also { preview = it }
    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** Spheres at the points and, with `lineColor`, the line between the first two. */
    private fun showPoints(points: List<DoubleArray>, lineColor: Int?) {
        val g = previewSet()
        val b = MeshBuilder()
        val r = 0.12 * scene.viewCtl.toolScale()
        for (pt in points) b.withTransform(yawPose(pt[0], pt[1], 0.0)) { sphere(r, r, r, tool.color) }
        if (lineColor != null && points.size >= 2) {
            val (a, c) = points
            b.thickLine(a[0], a[1], a[2] + 0.01, c[0], c[1], c[2] + 0.01, lineColor, 0.05 * scene.viewCtl.toolScale())
        }
        g.update(b); g.setVisible(true); g.setTransform(identity)
    }

    private fun showArrow() {
        val s = toolStart ?: return
        val g = previewSet()
        val b = MeshBuilder()
        b.withTransform(yawPose(s[0], s[1], toolYaw)) { arrow(1.0, tool.color) }
        g.update(b); g.setVisible(true); g.setTransform(identity)
    }

    /** Select: the cloud or laser point closest to the cursor. */
    fun selectPoint(x: Float, y: Float) {
        val project = scene.projector()
        selected = scene.displays.values.mapNotNull { (it as? PointPicker)?.pickPoint(x, y, 28 * pxPerDp, project) }.minByOrNull { it.distPx }
        val s = selected
        if (s == null) { preview?.setVisible(false); onMessage(tr("rviz_tool_select_none")); return }
        showPoints(listOf(s.fixed), null)
    }

    /** Text of the selected point: fixed frame, sensor frame and distance to the sensor. */
    fun selectText(s: PickedPoint): String {
        val f = Locale.ROOT
        val l = s.local
        val fixed = String.format(f, "%s (%.3f, %.3f, %.3f)", scene.fixedFrame, s.fixed[0], s.fixed[1], s.fixed[2])
        val extra = if (s.fields.isEmpty()) "" else "\n" + s.fields.joinToString(" · ") { (k, v) -> "$k $v" }
        if (s.frame.isEmpty()) return "${s.display}: $fixed$extra"
        return "${s.display}: $fixed\n" + String.format(f, "%s (%.3f, %.3f, %.3f) · %.3f m", s.frame, l[0], l[1], l[2], Math.sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2])) + extra
    }

    fun publishPoint(x: Double, y: Double, z: Double) {
        val frame = scene.fixedFrame.ifEmpty { "map" }
        val pub = pointPublisher ?: RosConnection.createPublisher(pointTopic, geometry_msgs.PointStamped::class.java).also { pointPublisher = it }
        val msg = geometry_msgs.PointStamped()
        msg.header.setFrameId(frame)
        msg.point.x = x; msg.point.y = y; msg.point.z = z
        publishSoon { pub.publish(msg) }
        lastToolPoint = doubleArrayOf(x, y, z)
        onMessage(String.format(Locale.ROOT, tr("rviz_tool_point_sent"), x, y, z, frame, pointTopic))
    }

    /** Publishes the pose in the fixed frame (stamp 0 = "the latest TF" for Nav2/AMCL). */
    fun publishPose(t: RvizTool, x: Double, y: Double, yaw: Double) {
        val frame = scene.fixedFrame.ifEmpty { "map" }
        val qz = Math.sin(yaw / 2); val qw = Math.cos(yaw / 2)
        when (t) {
            RvizTool.GOAL -> {
                val pub = goalPublisher ?: RosConnection.createPublisher(goalTopic, geometry_msgs.PoseStamped::class.java).also { goalPublisher = it }
                val msg = geometry_msgs.PoseStamped()
                msg.header.setFrameId(frame)
                msg.pose.position.x = x; msg.pose.position.y = y
                msg.pose.orientation.z = qz; msg.pose.orientation.w = qw
                publishSoon { pub.publish(msg) }
            }
            RvizTool.POSE_ESTIMATE -> {
                val pub = posePublisher ?: RosConnection.createPublisher(poseTopic, geometry_msgs.PoseWithCovarianceStamped::class.java).also { posePublisher = it }
                val msg = geometry_msgs.PoseWithCovarianceStamped()
                msg.header.setFrameId(frame)
                msg.pose.pose.position.x = x; msg.pose.pose.position.y = y
                msg.pose.pose.orientation.z = qz; msg.pose.pose.orientation.w = qw
                // RViz default covariance: 0.25 m² in x/y and (π/12)² in yaw.
                msg.pose.covariance[0] = 0.25; msg.pose.covariance[7] = 0.25; msg.pose.covariance[35] = 0.06853891945200942
                publishSoon { pub.publish(msg) }
            }
            else -> return
        }
        lastToolPose = doubleArrayOf(x, y, yaw)
        // Locale.ROOT: numbers always with a decimal point.
        onMessage(String.format(Locale.ROOT, tr("rviz_tool_sent"), tr(t.labelKey), x, y, Math.toDegrees(yaw), frame))
    }

    /**
     * A freshly created publisher takes a moment to match with subscribers; it retries a few times (without
     * blocking the UI) and publishes only once as soon as someone is listening.
     */
    private fun publishSoon(send: () -> Boolean) {
        val t = Thread {
            var tries = 0
            while (tries++ < 30) {
                val matched = (goalPublisher?.matchedSubscribers() ?: 0) + (posePublisher?.matchedSubscribers() ?: 0) + (pointPublisher?.matchedSubscribers() ?: 0)
                if (matched > 0 && send()) return@Thread
                Thread.sleep(100)
            }
            send()
        }
        t.isDaemon = true
        t.start()
    }

    fun destroy() {
        mission.close()
        handleGeo?.destroy(); handleGeo = null
        waypointGeo?.destroy(); waypointGeo = null
        preview?.destroy(); preview = null
        goalPublisher?.close(); posePublisher?.close(); pointPublisher?.close()
        goalPublisher = null; posePublisher = null; pointPublisher = null
    }

    companion object {
        const val KEY_GOAL_TOPIC = "goalTopic"
        const val KEY_POINT_TOPIC = "pointTopic"
        const val KEY_POSE_TOPIC = "poseTopic"
        const val KEY_NAV2 = "nav2Waypoints"
    }
}
