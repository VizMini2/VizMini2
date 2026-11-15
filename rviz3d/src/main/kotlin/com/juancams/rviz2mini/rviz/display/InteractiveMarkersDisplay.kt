// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.display

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.rviz.geom.IMControl
import com.juancams.rviz2mini.rviz.geom.IMarker
import com.juancams.rviz2mini.rviz.geom.InteractiveMarkers
import com.juancams.rviz2mini.rviz.geom.MeshBuilder
import com.juancams.rviz2mini.rviz.geom.TextLabel
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * "InteractiveMarkers" display (like RViz2's): subscribes to `<ns>/update`, requests the initial
 * state from `<ns>/get_interactive_markers` and draws the controls. With the view's "Interact"
 * tool, [pick] picks a control under the finger and the feedback functions publish on
 * `<ns>/feedback` the clicks, menus and poses while dragging.
 *
 * The display topic is the update one ("/basic_controls/update"); the namespace is what comes before it.
 */
class InteractiveMarkersDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {

    /** What is under the finger: the marker, its control and the distance in pixels to the grab point. */
    class Hit(val marker: IMarker, val control: IMControl, val distPx: Float)

    private val markers = LinkedHashMap<String, IMarker>()
    private val geometries = HashMap<String, GeometrySet>()
    private val labels = HashMap<String, List<TextLabel>>()
    private val dirty = HashSet<String>()
    private val incoming = ConcurrentLinkedQueue<(MutableMap<String, IMarker>) -> Unit>()
    private var update: DynRos.Subscription? = null
    private var feedback: DynRos.Publisher? = null
    @Volatile private var updates = 0L
    /** The server answered the initial request (until then, with no updates, it is "waiting for the server"). */
    @Volatile private var answered = false
    private var initThread: Thread? = null
    /** Control being dragged (drawn highlighted). */
    var dragging: Pair<String, String>? = null
        set(v) { field?.first?.let { dirty += it }; field = v; v?.first?.let { dirty += it } }

    val namespace: String get() = DynRos.normalizeName(config.topic).removeSuffix("/update")

    override fun onActivate() {
        val ctx = Interfaces.get()
        val ns = namespace
        update = DynRos.subscribe("$ns/update", ctx.msg("visualization_msgs/msg/InteractiveMarkerUpdate"), config.qosProfile().apply { depth(100) }) { v ->
            updates++
            incoming.add { map -> applyUpdate(map, v) }
        }
        feedback = DynRos.publisher("$ns/feedback", ctx.msg("visualization_msgs/msg/InteractiveMarkerFeedback"), RosConnection.defaultQos())
        // Initial state (like RViz's client): retried until the server appears.
        answered = false
        val srv = ctx.srv("visualization_msgs/srv/GetInteractiveMarkers")
        initThread = Thread({
            val client = DynServiceClient("$ns/get_interactive_markers", srv)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val r = client.call(MsgValue(), INIT_TIMEOUT_MS) { Thread.currentThread().isInterrupted }
                        val list = (r["markers"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(InteractiveMarkers::marker) }
                        incoming.add { map -> map.clear(); for (m in list) map[m.name] = m }
                        answered = true
                        return@Thread
                    } catch (_: Exception) {
                        // Not there yet: try again.
                        Thread.sleep(RETRY_MS)
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                client.close()
            }
        }, "im-init").apply { isDaemon = true; start() }
    }

    override fun onDeactivate() {
        initThread?.interrupt(); initThread = null
        update?.close(); update = null
        feedback?.close(); feedback = null
        markers.clear(); incoming.clear()
        for (g in geometries.values) g.destroy()
        geometries.clear(); labels.clear()
    }

    override fun onConfigChanged(old: DisplayConfig) {
        if (!active || old.topic == config.topic) { markers.keys.forEach { dirty += it }; return }
        onDeactivate(); onActivate()
    }

    private fun applyUpdate(map: MutableMap<String, IMarker>, v: Map<String, Any?>) {
        if ((v["type"] as? Number)?.toInt() == InteractiveMarkers.UPDATE_KEEP_ALIVE) return
        for (mk in (v["markers"] as? List<*>).orEmpty()) (mk as? Map<*, *>)?.let { InteractiveMarkers.marker(it) }?.let { map[it.name] = it }
        for (p in (v["poses"] as? List<*>).orEmpty()) {
            val pm = p as? Map<*, *> ?: continue
            val name = pm["name"]?.toString() ?: continue
            // While dragging the finger rules: it is not overwritten by what the server returns.
            if (dragging?.first == name) continue
            map[name]?.pose = InteractiveMarkers.poseMatrix(pm["pose"] as? Map<*, *> ?: continue)
        }
        for (e in (v["erases"] as? List<*>).orEmpty()) map.remove(e.toString())
    }

    override fun render(ctx: FrameContext) {
        while (true) {
            val change = incoming.poll() ?: break
            val before = markers.keys.toSet()
            change(markers)
            dirty += markers.keys
            for (gone in before - markers.keys) { geometries.remove(gone)?.destroy(); labels.remove(gone) }
        }
        for (name in dirty) {
            val im = markers[name] ?: continue
            val b = MeshBuilder()
            val text = ArrayList<TextLabel>()
            for (c in im.controls) InteractiveMarkers.visuals(im, c, b, text, dragging == (im.name to c.name))
            if (config.bool("showDescriptions") && im.description.isNotBlank()) text += TextLabel(im.description, 0.0, 0.0, 0.6 * im.scale, 0xFFFFFFFF.toInt(), 0.12 * im.scale)
            geometries.getOrPut(name) { GeometrySet(host.engine, host.scene) }.update(b)
            labels[name] = text
        }
        dirty.clear()
        var missing: String? = null
        for ((name, im) in markers) {
            val g = geometries[name] ?: continue
            val frameTf = ctx.transformD(im.frame)
            if (frameTf == null) { g.setVisible(false); missing = im.frame; continue }
            val t = TfMath.multiply(frameTf, im.pose, DoubleArray(16))
            g.setVisible(true)
            g.setTransform(TfMath.toFloat(t, FloatArray(16)))
            labels[name]?.forEachIndexed { i, l ->
                val p = InteractiveMarkers.transformPoint(t, l.x, l.y, l.z)
                ctx.labels.label("im:${config.id}:$name:$i", l.text, p[0], p[1], p[2], l.color, 14.0, screenDp = true)
            }
        }
        when {
            markers.isEmpty() && !answered && updates == 0L -> setStatus(StatusLevel.WARN, "Waiting for the server on $namespace")
            markers.isEmpty() -> setStatus(StatusLevel.OK, "No interactive markers")
            missing != null -> setStatus(StatusLevel.WARN, "No transform from '$missing' to '${ctx.fixedFrame}'")
            else -> setStatus(StatusLevel.OK, "${markers.size} interactive markers")
        }
    }

    // ---------------------------------------------------------------- interaction

    /** Marker pose in the fixed frame, or null without TF. */
    fun fixedPose(im: IMarker, ctx: FrameContext): DoubleArray? = ctx.transformD(im.frame)?.let { TfMath.multiply(it, im.pose, DoubleArray(16)) }

    /**
     * The control closest to screen point (sx, sy), within `maxPx`. `project` maps a
     * fixed-frame point to the screen (null if it ends up behind the camera).
     */
    fun pick(sx: Float, sy: Float, maxPx: Float, ctx: FrameContext, project: (DoubleArray) -> FloatArray?): Hit? {
        var best: Hit? = null
        for (im in markers.values) {
            val t = fixedPose(im, ctx) ?: continue
            for (c in im.controls) {
                if (c.mode == IMControl.NONE) continue
                for (h in InteractiveMarkers.handles(im, c)) {
                    val s = project(InteractiveMarkers.transformPoint(t, h[0], h[1], h[2])) ?: continue
                    val d = Math.hypot((s[0] - sx).toDouble(), (s[1] - sy).toDouble()).toFloat()
                    if (d <= maxPx && (best == null || d < best.distPx)) best = Hit(im, c, d)
                }
            }
        }
        return best
    }

    /** Moves the marker in the view (while dragging) without waiting for the server. */
    fun setPose(im: IMarker, pose: DoubleArray) { im.pose = pose }

    fun markDirty(name: String) { dirty += name }

    fun sendFeedback(im: IMarker, c: IMControl, event: Int, menuEntry: Int = 0, mousePoint: DoubleArray? = null) {
        val pub = feedback ?: return
        val q = InteractiveMarkers.quaternion(im.pose)
        val msg = MsgValue().apply {
            put("header", MsgValue().apply { put("frame_id", im.frame); put("stamp", com.juancams.rviz2mini.ros.dyn.DynCodec.timeValue(System.currentTimeMillis() * 1_000_000L)) })
            put("client_id", "/vizmini2/${RosConnection.config.nodeName}")
            put("marker_name", im.name)
            put("control_name", c.name)
            put("event_type", event.toLong())
            put("pose", MsgValue().apply {
                put("position", MsgValue().apply { put("x", im.pose[12]); put("y", im.pose[13]); put("z", im.pose[14]) })
                put("orientation", MsgValue().apply { put("x", q[0]); put("y", q[1]); put("z", q[2]); put("w", q[3]) })
            })
            put("menu_entry_id", menuEntry.toLong())
            put("mouse_point", MsgValue().apply { put("x", mousePoint?.get(0) ?: 0.0); put("y", mousePoint?.get(1) ?: 0.0); put("z", mousePoint?.get(2) ?: 0.0) })
            put("mouse_point_valid", mousePoint != null)
        }
        try { pub.publish(msg) } catch (e: Exception) { Log.w("VizMini2", "Feedback de ${im.name}", e) }
    }

    override fun setVisible(v: Boolean) { for (g in geometries.values) g.setVisible(v) }

    override fun destroy() {
        super.destroy()
        for (g in geometries.values) g.destroy()
        geometries.clear()
    }

    internal fun markersForTest(): Map<String, IMarker> = markers

    private companion object {
        const val INIT_TIMEOUT_MS = 3000L
        const val RETRY_MS = 1000L
    }
}
