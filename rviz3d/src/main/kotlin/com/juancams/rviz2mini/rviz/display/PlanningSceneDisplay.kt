// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.display

import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.rviz.geom.InteractiveMarkers
import com.juancams.rviz2mini.rviz.geom.MeshBuilder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * PlanningScene (like MoveIt's display in RViz, the geometry part): the planning scene's obstacles
 * (boxes, spheres, cylinders and cones) in their frame. Reads `/monitored_planning_scene` (the full scene or
 * its changes) and, when enabled, requests the whole scene from `get_planning_scene`.
 */
class PlanningSceneDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {

    /** A shape: shape_msgs primitive (1 box, 2 sphere, 3 cylinder, 4 cone) or mesh (`triangles`, 9 per triangle). */
    private class Prim(val pose: DoubleArray, val type: Int, val dims: List<Double>, val triangles: DoubleArray? = null)
    private class Obj(val frame: String, val prims: List<Prim>)

    private val objects = LinkedHashMap<String, Obj>()
    /** Objects carried by the robot (attached), in their link's frame: they move with it. */
    private val attached = LinkedHashMap<String, Obj>()
    private val colors = HashMap<String, Int>()
    private val sets = HashMap<String, GeometrySet>()
    private val incoming = ConcurrentLinkedQueue<Map<*, *>>()
    private var sub: DynRos.Subscription? = null
    private var init: Thread? = null
    private var dirty = true
    @Volatile private var scenes = 0

    override fun onActivate() {
        val reg = Interfaces.get()
        val topic = DynRos.normalizeName(config.topic)
        sub = runCatching {
            DynRos.subscribe(topic, reg.msg("moveit_msgs/msg/PlanningScene"), config.qosProfile()) { v -> scenes++; incoming.add(v) }
        }.getOrElse { setStatus(StatusLevel.ERROR, it.message ?: "?"); null }
        // The whole scene at once (the topic's may arrive with changes only).
        val service = topic.substringBeforeLast('/', "") + "/get_planning_scene"
        init = Thread({
            val srv = runCatching { reg.srv("moveit_msgs/srv/GetPlanningScene") }.getOrNull() ?: return@Thread
            val client = DynServiceClient(service, srv)
            try {
                // World names and geometry, colors and attached objects.
                val req = DynCodec.fromJson(srv.request, org.json.JSONObject().put("components", org.json.JSONObject().put("components", 4 or 8 or 16 or 512)))
                val r = client.call(req, 5000) { Thread.currentThread().isInterrupted }
                (r["scene"] as? Map<*, *>)?.let { s -> scenes++; incoming.add(s) }
            } catch (_: Exception) {
            } finally { client.close() }
        }, "planning-scene-init").apply { isDaemon = true; start() }
    }

    override fun onDeactivate() {
        init?.interrupt(); init = null
        sub?.close(); sub = null
        incoming.clear(); objects.clear(); attached.clear(); colors.clear()
        for (g in sets.values) g.destroy()
        sets.clear(); dirty = true
    }

    override fun onConfigChanged(old: DisplayConfig) {
        dirty = true
        if (active && old.topic != config.topic) { onDeactivate(); onActivate() }
    }

    /** The shapes of a moveit_msgs/CollisionObject (primitives and meshes) relative to its frame. */
    private fun shapes(m: Map<*, *>): List<Prim> {
        val base = (m["pose"] as? Map<*, *>)?.let { InteractiveMarkers.poseMatrix(it) } ?: TfMath.identity()
        fun local(list: Any?, i: Int) = ((list as? List<*>)?.getOrNull(i) as? Map<*, *>)?.let { InteractiveMarkers.poseMatrix(it) } ?: TfMath.identity()
        val prims = (m["primitives"] as? List<*>).orEmpty().mapIndexedNotNull { i, p ->
            val pm = p as? Map<*, *> ?: return@mapIndexedNotNull null
            Prim(TfMath.multiply(base, local(m["primitive_poses"], i), DoubleArray(16)), (pm["type"] as? Number)?.toInt() ?: 0,
                (pm["dimensions"] as? List<*>).orEmpty().map { (it as? Number)?.toDouble() ?: 0.0 })
        }
        val meshes = (m["meshes"] as? List<*>).orEmpty().mapIndexedNotNull { i, mesh ->
            val mm = mesh as? Map<*, *> ?: return@mapIndexedNotNull null
            val v = (mm["vertices"] as? List<*>).orEmpty().map { p -> (p as? Map<*, *>)?.let { doubleArrayOf((it["x"] as? Number)?.toDouble() ?: 0.0, (it["y"] as? Number)?.toDouble() ?: 0.0, (it["z"] as? Number)?.toDouble() ?: 0.0) } ?: DoubleArray(3) }
            val tris = (mm["triangles"] as? List<*>).orEmpty()
            val out = DoubleArray(tris.size * 9)
            var n = 0
            for (t in tris) {
                val idx = ((t as? Map<*, *>)?.get("vertex_indices") as? List<*>)?.map { (it as? Number)?.toInt() ?: -1 } ?: continue
                if (idx.size < 3 || idx.any { it !in v.indices }) continue
                for (k in 0..2) { val p = v[idx[k]]; out[n++] = p[0]; out[n++] = p[1]; out[n++] = p[2] }
            }
            Prim(TfMath.multiply(base, local(m["mesh_poses"], i), DoubleArray(16)), MESH, emptyList(), out.copyOf(n))
        }
        return prims + meshes
    }

    /** Applies a CollisionObject (with its operation) to `map`; `defaultFrame` if it has no frame (attached ones: their link). */
    private fun applyObject(map: LinkedHashMap<String, Obj>, m: Map<*, *>, defaultFrame: String = "") {
        val id = m["id"]?.toString() ?: return
        val frame = ((m["header"] as? Map<*, *>)?.get("frame_id") as? String).orEmpty().ifEmpty { defaultFrame }
        when ((m["operation"] as? Number)?.toInt() ?: 0) {
            1 -> map.remove(id)
            2 -> map[id] = Obj(frame.ifEmpty { map[id]?.frame.orEmpty() }, map[id]?.prims.orEmpty() + shapes(m))
            3 -> { // MOVE: what was there, to the new pose
                val base = (m["pose"] as? Map<*, *>)?.let { InteractiveMarkers.poseMatrix(it) } ?: TfMath.identity()
                val now = shapes(m)
                map[id]?.let { old -> map[id] = Obj(frame.ifEmpty { old.frame }, if (now.isNotEmpty()) now else old.prims.map { Prim(TfMath.multiply(base, it.pose, DoubleArray(16)), it.type, it.dims, it.triangles) }) }
            }
            else -> map[id] = Obj(frame, shapes(m))
        }
    }

    private fun apply(scene: Map<*, *>) {
        val world = scene["world"] as? Map<*, *>
        val full = scene["is_diff"] != true
        if (full) objects.clear()
        for (o in (world?.get("collision_objects") as? List<*>).orEmpty()) (o as? Map<*, *>)?.let { applyObject(objects, it) }
        // Attached to the robot: in the full scene, the whole list; in a diff, the ones that change.
        val rs = scene["robot_state"] as? Map<*, *>
        if (full || rs?.get("is_diff") == false) attached.clear()
        for (a in (rs?.get("attached_collision_objects") as? List<*>).orEmpty()) {
            val am = a as? Map<*, *> ?: continue
            val obj = am["object"] as? Map<*, *> ?: continue
            applyObject(attached, obj, am["link_name"]?.toString().orEmpty())
        }
        for (c in (scene["object_colors"] as? List<*>).orEmpty()) {
            val m = c as? Map<*, *> ?: continue
            val col = m["color"] as? Map<*, *> ?: continue
            fun ch(k: String) = (((col[k] as? Number)?.toDouble() ?: 0.0) * 255).toInt().coerceIn(0, 255)
            colors[m["id"].toString()] = (ch("a") shl 24) or (ch("r") shl 16) or (ch("g") shl 8) or ch("b")
        }
        dirty = true
    }

    /** All of them (world and attached, the latter with "@" to avoid name clashes). */
    private fun all(): Map<String, Obj> = LinkedHashMap(objects).apply { for ((id, o) in attached) put("@$id", o) }

    private fun rebuild() {
        val defaultColor = Colors.withAlpha(config.color("color"), config.number("alpha").toFloat())
        val everything = all()
        for (id in sets.keys - everything.keys) sets.remove(id)?.destroy()
        for ((id, o) in everything) {
            val b = MeshBuilder()
            val c = colors[id.removePrefix("@")]?.takeIf { (it ushr 24) > 0 } ?: if (id.startsWith("@")) Colors.withAlpha(ATTACHED_COLOR, config.number("alpha").toFloat()) else defaultColor
            for (p in o.prims) b.withTransform(p.pose) {
                val d = p.dims
                when (p.type) {
                    1 -> if (d.size >= 3) box(d[0], d[1], d[2], c)
                    2 -> if (d.isNotEmpty()) sphere(d[0], d[0], d[0], c)
                    3 -> if (d.size >= 2) cylinder(d[1], d[1], d[0], c, 24)
                    4 -> if (d.size >= 2) withTransform(MeshBuilder.translation(0.0, 0.0, -d[0] / 2)) { cone(d[1], d[0], c, 24) }
                    MESH -> p.triangles?.let { t -> for (i in 0 until t.size / 9) { val o = i * 9; triangle(t[o], t[o + 1], t[o + 2], t[o + 3], t[o + 4], t[o + 5], t[o + 6], t[o + 7], t[o + 8], c) } }
                }
            }
            sets.getOrPut(id) { GeometrySet(host.engine, host.scene) }.update(b)
        }
    }

    override fun render(ctx: FrameContext) {
        if (!active) return
        while (true) { val s = incoming.poll() ?: break; apply(s) }
        if (dirty) { rebuild(); dirty = false }
        var missing = 0
        val everything = all()
        for ((id, g) in sets) {
            val t = ctx.transformF(everything[id]?.frame.orEmpty())
            if (t == null) { g.setVisible(false); missing++; continue }
            g.setVisible(true); g.setTransform(t)
        }
        when {
            scenes == 0 -> waiting("Waiting for the planning scene on ${config.topic}")
            missing > 0 -> setStatus(StatusLevel.WARN, "${objects.size} objects${attachedText()} · $missing without TF")
            else -> setStatus(StatusLevel.OK, "${objects.size} objects${attachedText()}")
        }
    }

    private fun attachedText() = if (attached.isEmpty()) "" else " · ${attached.size} attached"

    override fun setVisible(v: Boolean) { if (!v) for (g in sets.values) g.setVisible(false) }

    override fun destroy() {
        super.destroy()
        onDeactivate()
    }

    companion object {
        private const val MESH = 100
        /** Attached objects, in another color (like in RViz: purple). */
        val ATTACHED_COLOR = Colors.rgb(170, 100, 230)
    }
}
