// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.platform.SystemClock
import com.juancams.rviz2mini.rviz.display.FrameContext
import com.juancams.rviz2mini.rviz.display.InteractiveMarkersDisplay
import com.juancams.rviz2mini.rviz.geom.IMControl
import com.juancams.rviz2mini.rviz.geom.IMarker

/**
 * Interact tool (interactive markers) over an [RvizScene], without the per-platform part: touching a
 * control grabs it; dragging it moves or rotates the marker according to its type and sends feedback to the server (at 20 Hz);
 * a tap without dragging is a button click or opens the control's menu (`onMenu`, which each platform shows).
 * If the gesture does not land on a control, [down] returns false and the camera handles it.
 */
class RvizInteract(
    private val scene: RvizScene, private val pxPerDp: Float = 1f,
    private val onMenu: (InteractiveMarkersDisplay, IMarker, IMControl, List<Pair<String, Int>>) -> Unit = { _, _, _, _ -> },
) {
    /** Drag of a control in progress. */
    private class ImDrag(
        val display: InteractiveMarkersDisplay, val hit: InteractiveMarkersDisplay.Hit,
        val startPose: DoubleArray, val downX: Float, val downY: Float,
        var lastX: Float, var lastY: Float, val grabOffset: DoubleArray?,
    ) {
        var moved = false
        var lastSentMs = 0L
    }

    private var imDrag: ImDrag? = null
    /** The last gesture ("drag:marker:control", "tap:...", "menu:marker:id"), for the tests. */
    var lastImEvent: String? = null; private set
    val dragging: Boolean get() = imDrag != null

    /** Returns true if the gesture belongs to a control (otherwise the camera handles it). */
    fun down(x: Float, y: Float): Boolean {
        val ctx = scene.frameContext()
        val project = scene.projector()
        val best = scene.displays.values.filterIsInstance<InteractiveMarkersDisplay>()
            .mapNotNull { d -> d.pick(x, y, 28 * pxPerDp, ctx, project)?.let { d to it } }
            .minByOrNull { it.second.distPx } ?: return false
        val (display, hit) = best
        // For moving in a horizontal plane: where it was grabbed relative to the origin.
        val origin = display.fixedPose(hit.marker, ctx)
        val ground = DoubleArray(3)
        val offset = if (origin != null && scene.viewCtl.groundPoint(x, y, scene.width.toFloat(), scene.height.toFloat(), ground, origin[14]))
            doubleArrayOf(ground[0] - origin[12], ground[1] - origin[13], 0.0) else null
        imDrag = ImDrag(display, hit, hit.marker.pose.copyOf(), x, y, x, y, offset)
        display.dragging = hit.marker.name to hit.control.name
        display.sendFeedback(hit.marker, hit.control, com.juancams.rviz2mini.rviz.geom.InteractiveMarkers.FEEDBACK_MOUSE_DOWN)
        return true
    }

    fun move(x: Float, y: Float): Boolean {
        val d = imDrag ?: return false
        if (!d.moved && Math.hypot((x - d.downX).toDouble(), (y - d.downY).toDouble()) < 8 * pxPerDp) return true
        d.moved = true
        dragControl(d, x, y, scene.frameContext())
        d.lastX = x; d.lastY = y
        val now = SystemClock.uptimeMillis()
        if (now - d.lastSentMs >= FEEDBACK_MS) {
            d.lastSentMs = now
            d.display.sendFeedback(d.hit.marker, d.hit.control, com.juancams.rviz2mini.rviz.geom.InteractiveMarkers.FEEDBACK_POSE_UPDATE)
        }
        return true
    }

    fun up(cancel: Boolean = false): Boolean {
        val d = imDrag ?: return false
        imDrag = null
        val im = d.hit.marker; val c = d.hit.control
        val IM = com.juancams.rviz2mini.rviz.geom.InteractiveMarkers
        if (!cancel && d.moved) d.display.sendFeedback(im, c, IM.FEEDBACK_POSE_UPDATE)
        d.display.sendFeedback(im, c, IM.FEEDBACK_MOUSE_UP)
        // The click goes after MOUSE_UP: the Python interactive_markers server reads feedback with a queue of 1
        // (QoSProfile(depth=1) by default) and, if they arrive back to back, the last overwrites the previous. This way the click is the one kept.
        if (!cancel && !d.moved) when (c.mode) {
            IMControl.BUTTON -> d.display.sendFeedback(im, c, IM.FEEDBACK_BUTTON_CLICK)
            IMControl.MENU -> {
                val entries = IM.menuTree(im.menu)
                if (entries.isNotEmpty()) onMenu(d.display, im, c, entries.map { ("    ".repeat(it.second) + it.first.title) to it.first.id })
            }
        }
        d.display.dragging = null
        lastImEvent = if (cancel) null else if (d.moved) "drag:${im.name}:${c.name}" else "tap:${im.name}:${c.name}"
        return true
    }

    /** A menu entry of a control was chosen. */
    fun menuSelected(display: InteractiveMarkersDisplay, im: IMarker, c: IMControl, id: Int) {
        display.sendFeedback(im, c, com.juancams.rviz2mini.rviz.geom.InteractiveMarkers.FEEDBACK_MENU_SELECT, id)
        lastImEvent = "menu:${im.name}:$id"
    }

    /** New marker pose according to the control type and the drag. */
    private fun dragControl(d: ImDrag, x: Float, y: Float, ctx: FrameContext) {
        val IM = com.juancams.rviz2mini.rviz.geom.InteractiveMarkers
        val im = d.hit.marker; val c = d.hit.control
        val frameTf = ctx.transformD(im.frame) ?: return
        val inv = DoubleArray(16)
        if (!com.juancams.rviz2mini.TfMath.invertGeneral(frameTf, inv)) return
        val current = im.pose
        val axisFrame = IM.worldAxis(d.startPose, c)
        val axisFixed = doubleArrayOf(
            frameTf[0] * axisFrame[0] + frameTf[4] * axisFrame[1] + frameTf[8] * axisFrame[2],
            frameTf[1] * axisFrame[0] + frameTf[5] * axisFrame[1] + frameTf[9] * axisFrame[2],
            frameTf[2] * axisFrame[0] + frameTf[6] * axisFrame[1] + frameTf[10] * axisFrame[2])
        val start = IM.transformPoint(frameTf, d.startPose[12], d.startPose[13], d.startPose[14])
        val project = scene.projector()
        val o = project(start) ?: return
        fun withOriginFixed(p: DoubleArray): DoubleArray {
            val local = IM.transformPoint(inv, p[0], p[1], p[2])
            return current.copyOf().also { it[12] = local[0]; it[13] = local[1]; it[14] = local[2] }
        }
        val pose = when (c.mode) {
            IMControl.MOVE_AXIS -> {
                val e = project(doubleArrayOf(start[0] + axisFixed[0], start[1] + axisFixed[1], start[2] + axisFixed[2])) ?: return
                val dist = IM.axisDelta(o[0], o[1], e[0], e[1], x - d.downX, y - d.downY)
                IM.translated(d.startPose, axisFrame, dist).also { r -> for (i in 0..11) r[i] = current[i] }
            }
            IMControl.ROTATE_AXIS, IMControl.ROTATE_3D -> {
                val eye = scene.viewCtl.eye()
                val toward = (eye[0] - start[0]) * axisFixed[0] + (eye[1] - start[1]) * axisFixed[1] + (eye[2] - start[2]) * axisFixed[2] > 0
                val dA = IM.rotateDelta(o[0], o[1], d.lastX, d.lastY, x, y, toward)
                IM.rotated(current, axisFrame, dA)
            }
            else -> {
                // Control plane: if horizontal, the ground point under the finger; otherwise, the plane's two axes.
                if (Math.abs(axisFixed[2]) > 0.7 || c.mode >= IMControl.MOVE_3D) {
                    val g = DoubleArray(3)
                    if (!scene.viewCtl.groundPoint(x, y, scene.width.toFloat(), scene.height.toFloat(), g, start[2])) return
                    val off = d.grabOffset ?: doubleArrayOf(0.0, 0.0, 0.0)
                    withOriginFixed(doubleArrayOf(g[0] - off[0], g[1] - off[1], start[2]))
                } else {
                    val (u, v) = IM.perpendiculars(axisFixed)
                    val pu = project(doubleArrayOf(start[0] + u[0], start[1] + u[1], start[2] + u[2])) ?: return
                    val pv = project(doubleArrayOf(start[0] + v[0], start[1] + v[1], start[2] + v[2])) ?: return
                    val a11 = (pu[0] - o[0]).toDouble(); val a21 = (pu[1] - o[1]).toDouble()
                    val a12 = (pv[0] - o[0]).toDouble(); val a22 = (pv[1] - o[1]).toDouble()
                    val det = a11 * a22 - a12 * a21
                    if (Math.abs(det) < 1e-6) return
                    val dx = (x - d.downX).toDouble(); val dy = (y - d.downY).toDouble()
                    val ka = (dx * a22 - dy * a12) / det; val kb = (a11 * dy - a21 * dx) / det
                    withOriginFixed(doubleArrayOf(start[0] + ka * u[0] + kb * v[0], start[1] + ka * u[1] + kb * v[1], start[2] + ka * u[2] + kb * v[2]))
                }
            }
        }
        d.display.setPose(im, pose)
        d.display.markDirty(im.name)
    }


    private companion object {
        /** How often the pose is sent while dragging (20 Hz). */
        const val FEEDBACK_MS = 50L
    }
}
