// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.geom

import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.rviz.display.Colors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A control of an interactive marker (visualization_msgs/InteractiveMarkerControl). */
class IMControl(
    val name: String,
    /** Quaternion (x, y, z, w): the control's axis is its x axis. */
    val orientation: DoubleArray,
    val mode: Int,
    val alwaysVisible: Boolean,
    val markers: List<MarkerData>,
    val description: String
) {
    companion object {
        const val NONE = 0; const val MENU = 1; const val BUTTON = 2; const val MOVE_AXIS = 3; const val MOVE_PLANE = 4
        const val ROTATE_AXIS = 5; const val MOVE_ROTATE = 6; const val MOVE_3D = 7; const val ROTATE_3D = 8; const val MOVE_ROTATE_3D = 9
    }
}

class IMMenuEntry(val id: Int, val parentId: Int, val title: String)

/** An interactive marker: its pose (in `frame`), its controls and its menu. */
class IMarker(
    val name: String,
    val frame: String,
    var pose: DoubleArray,
    val scale: Double,
    val description: String,
    val controls: List<IMControl>,
    val menu: List<IMMenuEntry>
)

/** The interactive marker parts that do not need Android (tested on the JVM). */
object InteractiveMarkers {

    const val FEEDBACK_KEEP_ALIVE = 0; const val FEEDBACK_POSE_UPDATE = 1; const val FEEDBACK_MENU_SELECT = 2
    const val FEEDBACK_BUTTON_CLICK = 3; const val FEEDBACK_MOUSE_DOWN = 4; const val FEEDBACK_MOUSE_UP = 5
    const val UPDATE_KEEP_ALIVE = 0; const val UPDATE_UPDATE = 1

    // ---------------------------------------------------------------- messages (MsgValue) -> model

    private fun d(v: Any?) = (v as? Number)?.toDouble() ?: 0.0
    private fun m(v: Any?) = v as? Map<*, *> ?: emptyMap<String, Any?>()

    fun poseMatrix(p: Map<*, *>): DoubleArray {
        val pos = m(p["position"]); val o = m(p["orientation"])
        var qx = d(o["x"]); var qy = d(o["y"]); var qz = d(o["z"]); var qw = d(o["w"])
        val n = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        if (n < 1e-9) { qx = 0.0; qy = 0.0; qz = 0.0; qw = 1.0 } else { qx /= n; qy /= n; qz /= n; qw /= n }
        return TfMath.fromTranslationQuaternion(d(pos["x"]), d(pos["y"]), d(pos["z"]), qx, qy, qz, qw)
    }

    private fun color(c: Map<*, *>): Int = Colors.rgbf(d(c["r"]).toFloat(), d(c["g"]).toFloat(), d(c["b"]).toFloat(), d(c["a"]).toFloat())

    /** A Marker inside a control (in the interactive marker's frame). */
    fun marker(v: Map<*, *>, index: Int): MarkerData {
        val pts = (v["points"] as? List<*>).orEmpty()
        val xyz = FloatArray(pts.size * 3)
        for ((i, p) in pts.withIndex()) { val q = m(p); xyz[i * 3] = d(q["x"]).toFloat(); xyz[i * 3 + 1] = d(q["y"]).toFloat(); xyz[i * 3 + 2] = d(q["z"]).toFloat() }
        val colors = (v["colors"] as? List<*>).orEmpty().map { color(m(it)) }.toIntArray()
        val s = m(v["scale"])
        return MarkerData("", index, (v["type"] as? Number)?.toInt() ?: 0, 0, "", poseMatrix(m(v["pose"])),
            d(s["x"]), d(s["y"]), d(s["z"]), color(m(v["color"])), xyz, colors, v["text"]?.toString().orEmpty(), 0)
    }

    fun control(v: Map<*, *>): IMControl {
        val o = m(v["orientation"])
        val q = doubleArrayOf(d(o["x"]), d(o["y"]), d(o["z"]), d(o["w"]))
        if (q.all { it == 0.0 }) q[3] = 1.0
        val markers = (v["markers"] as? List<*>).orEmpty().mapIndexed { i, mk -> marker(m(mk), i) }
        return IMControl(v["name"]?.toString().orEmpty(), q, (v["interaction_mode"] as? Number)?.toInt() ?: 0,
            v["always_visible"] == true, markers, v["description"]?.toString().orEmpty())
    }

    fun marker(v: Map<*, *>): IMarker {
        val header = m(v["header"])
        return IMarker(
            name = v["name"]?.toString().orEmpty(),
            frame = header["frame_id"]?.toString().orEmpty(),
            pose = poseMatrix(m(v["pose"])),
            scale = d(v["scale"]).takeIf { it > 0 } ?: 1.0,
            description = v["description"]?.toString().orEmpty(),
            controls = (v["controls"] as? List<*>).orEmpty().map { control(m(it)) },
            menu = (v["menu_entries"] as? List<*>).orEmpty().map { e -> m(e).let { IMMenuEntry((it["id"] as? Number)?.toInt() ?: 0, (it["parent_id"] as? Number)?.toInt() ?: 0, it["title"]?.toString().orEmpty()) } }
        )
    }

    // ---------------------------------------------------------------- geometry

    /** The control's axis (its x) in the interactive marker's frame. */
    fun axis(c: IMControl): DoubleArray {
        val (x, y, z, w) = c.orientation.toList()
        return normalize(doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w)))
    }

    /** RViz color for a control: the axis components (x red, y green, z blue). */
    fun axisColor(axis: DoubleArray, alpha: Float = 0.8f): Int =
        Colors.rgbf(abs(axis[0]).toFloat(), abs(axis[1]).toFloat(), abs(axis[2]).toFloat(), alpha)

    /** Two vectors perpendicular to the axis (to draw rings). */
    fun perpendiculars(a: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val ref = if (abs(a[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val u = normalize(cross(a, ref))
        return u to cross(a, u)
    }

    /**
     * What is drawn for a control, in the interactive marker's frame: its markers or, if it
     * has none, RViz's (arrows to move along the axis, rings to rotate or move
     * in the plane).
     */
    fun visuals(im: IMarker, c: IMControl, b: MeshBuilder, labels: MutableList<TextLabel>, highlight: Boolean) {
        if (c.markers.isNotEmpty()) {
            for (mk in c.markers) MarkerGeometry.add(mk, b, labels)
            return
        }
        val a = axis(c)
        val s = im.scale
        val color = axisColor(a, if (highlight) 1f else 0.75f)
        when (c.mode) {
            IMControl.MOVE_AXIS -> for (sign in listOf(1.0, -1.0)) {
                val p0 = scaled(a, sign * 0.55 * s); val p1 = scaled(a, sign * 0.95 * s)
                b.arrowBetween(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], color, 0.05 * s, 0.11 * s, 0.17 * s)
            }
            IMControl.ROTATE_AXIS, IMControl.MOVE_PLANE, IMControl.MOVE_ROTATE -> ring(b, a, 0.5 * s, if (c.mode == IMControl.ROTATE_AXIS) 0.05 * s else 0.025 * s, color)
            IMControl.MOVE_3D, IMControl.ROTATE_3D, IMControl.MOVE_ROTATE_3D -> b.sphere(0.12 * s, 0.12 * s, 0.12 * s, color)
        }
    }

    private fun ring(b: MeshBuilder, a: DoubleArray, r: Double, width: Double, color: Int, segments: Int = 40) {
        val (u, v) = perpendiculars(a)
        for (i in 0 until segments) {
            val t0 = 2 * Math.PI * i / segments; val t1 = 2 * Math.PI * (i + 1) / segments
            b.thickLine(r * (cos(t0) * u[0] + sin(t0) * v[0]), r * (cos(t0) * u[1] + sin(t0) * v[1]), r * (cos(t0) * u[2] + sin(t0) * v[2]),
                r * (cos(t1) * u[0] + sin(t1) * v[0]), r * (cos(t1) * u[1] + sin(t1) * v[1]), r * (cos(t1) * u[2] + sin(t1) * v[2]), color, width)
        }
    }

    /**
     * Points (in the interactive marker's frame) where a control can be grabbed: the arrows or
     * the default ring, or the center of its markers.
     */
    fun handles(im: IMarker, c: IMControl): List<DoubleArray> {
        if (c.markers.isNotEmpty()) return c.markers.map { mk ->
            val n = mk.points.size / 3
            if (n > 0) {
                var x = 0.0; var y = 0.0; var z = 0.0
                for (i in 0 until n) { x += mk.points[i * 3]; y += mk.points[i * 3 + 1]; z += mk.points[i * 3 + 2] }
                transformPoint(mk.pose, x / n, y / n, z / n)
            } else doubleArrayOf(mk.pose[12], mk.pose[13], mk.pose[14])
        }
        val a = axis(c); val s = im.scale
        return when (c.mode) {
            IMControl.MOVE_AXIS -> listOf(scaled(a, 0.75 * s), scaled(a, -0.75 * s))
            IMControl.ROTATE_AXIS, IMControl.MOVE_PLANE, IMControl.MOVE_ROTATE -> {
                val (u, v) = perpendiculars(a)
                (0 until 12).map { i -> val t = 2 * Math.PI * i / 12; doubleArrayOf(0.5 * s * (cos(t) * u[0] + sin(t) * v[0]), 0.5 * s * (cos(t) * u[1] + sin(t) * v[1]), 0.5 * s * (cos(t) * u[2] + sin(t) * v[2])) }
            }
            IMControl.NONE -> emptyList()
            else -> listOf(doubleArrayOf(0.0, 0.0, 0.0))
        }
    }

    // ---------------------------------------------------------------- dragging

    /**
     * Meters along the axis that correspond to an on-screen drag: `o` and `e` are the
     * projection of the origin and of origin + axis (1 m); the part of the drag in that direction is taken.
     */
    fun axisDelta(ox: Float, oy: Float, ex: Float, ey: Float, dx: Float, dy: Float): Double {
        val ax = (ex - ox).toDouble(); val ay = (ey - oy).toDouble()
        val len2 = ax * ax + ay * ay
        if (len2 < 1e-6) return 0.0
        return (dx * ax + dy * ay) / len2
    }

    /**
     * Angle (rad) the finger turns around the projection of the center, from point `p0` to `p1`.
     * On screen y grows downwards, so a counterclockwise turn as seen is positive if the axis
     * points towards the camera (`towardsCamera`).
     */
    fun rotateDelta(cx: Float, cy: Float, x0: Float, y0: Float, x1: Float, y1: Float, towardsCamera: Boolean): Double {
        val a0 = Math.atan2(-(y0 - cy).toDouble(), (x0 - cx).toDouble())
        val a1 = Math.atan2(-(y1 - cy).toDouble(), (x1 - cx).toDouble())
        var dA = a1 - a0
        while (dA > Math.PI) dA -= 2 * Math.PI
        while (dA < -Math.PI) dA += 2 * Math.PI
        return if (towardsCamera) dA else -dA
    }

    /** Pose moved `dist` meters along `worldAxis` (in the marker's frame). */
    fun translated(pose: DoubleArray, worldAxis: DoubleArray, dist: Double): DoubleArray = pose.copyOf().also {
        it[12] += worldAxis[0] * dist; it[13] += worldAxis[1] * dist; it[14] += worldAxis[2] * dist
    }

    /** Pose rotated `angle` rad around `worldAxis` passing through its origin. */
    fun rotated(pose: DoubleArray, worldAxis: DoubleArray, angle: Double): DoubleArray {
        val (x, y, z) = normalize(worldAxis).toList()
        val c = cos(angle); val s = sin(angle); val t = 1 - c
        val r = doubleArrayOf(
            t * x * x + c, t * x * y + s * z, t * x * z - s * y, 0.0,
            t * x * y - s * z, t * y * y + c, t * y * z + s * x, 0.0,
            t * x * z + s * y, t * y * z - s * x, t * z * z + c, 0.0,
            0.0, 0.0, 0.0, 1.0
        )
        val out = TfMath.multiply(r, pose.copyOf().also { it[12] = 0.0; it[13] = 0.0; it[14] = 0.0 }, DoubleArray(16))
        out[12] = pose[12]; out[13] = pose[13]; out[14] = pose[14]
        return out
    }

    /** The control's axis in the marker's frame (applying the interactive marker's orientation). */
    fun worldAxis(pose: DoubleArray, c: IMControl): DoubleArray {
        val a = axis(c)
        return normalize(doubleArrayOf(
            pose[0] * a[0] + pose[4] * a[1] + pose[8] * a[2],
            pose[1] * a[0] + pose[5] * a[1] + pose[9] * a[2],
            pose[2] * a[0] + pose[6] * a[1] + pose[10] * a[2]))
    }

    /** Menu entries in tree order (children indented by their depth). */
    fun menuTree(entries: List<IMMenuEntry>): List<Pair<IMMenuEntry, Int>> {
        val out = ArrayList<Pair<IMMenuEntry, Int>>()
        fun walk(parent: Int, depth: Int) {
            if (depth > 8) return
            for (e in entries.filter { it.parentId == parent }) { out += e to depth; walk(e.id, depth + 1) }
        }
        walk(0, 0)
        return out
    }

    /** Quaternion (x, y, z, w) of the rotation of a 4×4 matrix (columns). */
    fun quaternion(m: DoubleArray): DoubleArray {
        val tr = m[0] + m[5] + m[10]
        return if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            doubleArrayOf((m[6] - m[9]) / s, (m[8] - m[2]) / s, (m[1] - m[4]) / s, 0.25 * s)
        } else if (m[0] > m[5] && m[0] > m[10]) {
            val s = sqrt(1.0 + m[0] - m[5] - m[10]) * 2
            doubleArrayOf(0.25 * s, (m[4] + m[1]) / s, (m[8] + m[2]) / s, (m[6] - m[9]) / s)
        } else if (m[5] > m[10]) {
            val s = sqrt(1.0 + m[5] - m[0] - m[10]) * 2
            doubleArrayOf((m[4] + m[1]) / s, 0.25 * s, (m[9] + m[6]) / s, (m[8] - m[2]) / s)
        } else {
            val s = sqrt(1.0 + m[10] - m[0] - m[5]) * 2
            doubleArrayOf((m[8] + m[2]) / s, (m[9] + m[6]) / s, 0.25 * s, (m[1] - m[4]) / s)
        }
    }

    fun transformPoint(m: DoubleArray, x: Double, y: Double, z: Double) = doubleArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14])

    private fun scaled(a: DoubleArray, k: Double) = doubleArrayOf(a[0] * k, a[1] * k, a[2] * k)
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun normalize(a: DoubleArray): DoubleArray {
        val n = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        return if (n < 1e-12) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }
}
