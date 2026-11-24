// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.display

import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.rviz.display.NavMapModel.NavMap
import com.juancams.rviz2mini.rviz.geom.MeshBuilder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * NavMap (EasyNavigation, https://github.com/EasyNavigation/NavMap): a navigable surface made of triangles (NavCels),
 * possibly on several floors, with per-cell layers and links between cells that do not share an edge. Like its RViz
 * plugin: the cells colored by one layer, the links as arcs colored by type, and the layer updates (whole or sparse) of
 * `<topic>_layers`. Reading and colors: [NavMapModel].
 */
class NavMapDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {

    private val incomingMap = AtomicReference<NavMap?>()
    private val incomingLayers = ConcurrentLinkedQueue<NavMapModel.LayerUpdate>()
    private var subs = emptyList<DynRos.Subscription>()
    private var map: NavMap? = null
    private var geometry: GeometrySet? = null
    private var dirty = true
    private var updates = 0
    private var note: String? = null

    private fun layerTopic() = config.string("layerTopic").trim().ifEmpty { DynRos.normalizeName(config.topic) + "_layers" }

    override fun onActivate() {
        val reg = Interfaces.get()
        subs = runCatching {
            listOf(
                DynRos.subscribe(DynRos.normalizeName(config.topic), reg.msg(NavMapModel.MAP_TYPE), config.qosProfile()) { v ->
                    incomingMap.set(NavMapModel.parseMap(v))
                },
                DynRos.subscribe(DynRos.normalizeName(layerTopic()), reg.msg(NavMapModel.LAYER_TYPE), QosPreset.RELIABLE.profile()) { v ->
                    incomingLayers.add(NavMapModel.parseLayerUpdate(v))
                }
            )
        }.getOrElse { setStatus(StatusLevel.ERROR, it.message ?: "?"); emptyList() }
    }

    override fun onDeactivate() {
        for (s in subs) s.close()
        subs = emptyList()
        incomingMap.set(null); incomingLayers.clear()
        map = null; updates = 0; note = null
        geometry?.destroy(); geometry = null
        dirty = true
    }

    override fun onConfigChanged(old: DisplayConfig) {
        dirty = true
        if (active && (old.topic != config.topic || old.string("layerTopic") != config.string("layerTopic") || old.qosKey() != config.qosKey())) {
            onDeactivate(); onActivate()
        }
    }

    private fun rebuild(m: NavMap) {
        val alpha = config.number("alpha").toFloat()
        val scheme = config.string("scheme")
        val colors = NavMapModel.Colorizer(m, NavMapModel.selectLayer(m, config.string("layer")).takeIf { scheme != "flat" }, scheme,
            Colors.withAlpha(config.color("color"), alpha), alpha, config.bool("smooth"))
        val edges = config.bool("drawEdges")
        val b = MeshBuilder()
        val p = m.xyz
        fun x(i: Int) = p[i * 3].toDouble()
        fun y(i: Int) = p[i * 3 + 1].toDouble()
        fun z(i: Int) = p[i * 3 + 2].toDouble()
        for (t in 0 until m.cells) {
            val i0 = m.v[t * 3]; val i1 = m.v[t * 3 + 1]; val i2 = m.v[t * 3 + 2]
            when {
                colors.vertexColors -> b.triangleColors(x(i0), y(i0), z(i0), x(i1), y(i1), z(i1), x(i2), y(i2), z(i2),
                    colors.rgbaColor(i0), colors.rgbaColor(i1), colors.rgbaColor(i2))
                colors.smooth && !colors.cellUnknown(t) -> {
                    val f = slopeShade(x(i0), y(i0), z(i0), x(i1), y(i1), z(i1), x(i2), y(i2), z(i2))
                    b.triangleColors(x(i0), y(i0), z(i0), x(i1), y(i1), z(i1), x(i2), y(i2), z(i2),
                        Colors.shade(colors.vertexColor(t, i0), f), Colors.shade(colors.vertexColor(t, i1), f), Colors.shade(colors.vertexColor(t, i2), f))
                }
                else -> b.triangle(x(i0), y(i0), z(i0), x(i1), y(i1), z(i1), x(i2), y(i2), z(i2), colors.cellColor(t))
            }
            if (edges) {
                b.line(x(i0), y(i0), z(i0) + EDGE_LIFT, x(i1), y(i1), z(i1) + EDGE_LIFT, EDGE_COLOR)
                b.line(x(i1), y(i1), z(i1) + EDGE_LIFT, x(i2), y(i2), z(i2) + EDGE_LIFT, EDGE_COLOR)
                b.line(x(i2), y(i2), z(i2) + EDGE_LIFT, x(i0), y(i0), z(i0) + EDGE_LIFT, EDGE_COLOR)
            }
        }
        // Links: from centroid to centroid, raised in the middle so the ones between stacked cells stay visible.
        if (config.bool("drawLinks")) for (l in m.links) {
            val a = m.centroid(l.from); val c = m.centroid(l.to)
            val dx = c[0] - a[0]; val dy = c[1] - a[1]; val dz = c[2] - a[2]
            val lift = 0.25 * sqrt(dx * dx + dy * dy + dz * dz)
            val mx = (a[0] + c[0]) / 2; val my = (a[1] + c[1]) / 2; val mz = (a[2] + c[2]) / 2 + lift
            val col = NavMapModel.LINK_COLORS.getOrElse(l.type) { NavMapModel.LINK_COLORS[0] }
            b.thickLine(a[0], a[1], a[2], mx, my, mz, col, LINK_WIDTH)
            b.thickLine(mx, my, mz, c[0], c[1], c[2], col, LINK_WIDTH)
        }
        (geometry ?: GeometrySet(host.engine, host.scene).also { geometry = it }).update(b)
    }

    override fun render(ctx: FrameContext) {
        if (!active) return
        // The map is republished periodically: the same one again (and its layers) needs no new geometry.
        incomingMap.getAndSet(null)?.let { n -> if (map?.sameAs(n) != true) { map = n; dirty = true; note = null } }
        while (true) {
            val u = incomingLayers.poll() ?: break
            val m = map
            if (m == null) { note = "Layer \"${u.layer.name}\" before any NavMap: ignored"; continue }
            note = NavMapModel.apply(m, u)
            if (note == null) { updates++; dirty = true }
        }
        val m = map
        if (m == null) { waiting("Waiting for a NavMap on ${config.topic}"); return }
        if (dirty) { rebuild(m); dirty = false }
        val g = geometry ?: return
        val t = ctx.transformF(m.frame)
        if (t == null) { g.setVisible(false); tfMissing(m.frame, ctx); return }
        g.setVisible(true); g.setTransform(t)
        val wanted = config.string("layer").trim()
        val layer = NavMapModel.selectLayer(m, wanted)
        val text = buildString {
            append("${m.cells} cells")
            if (m.surfaces > 1) append(" · ${m.surfaces} surfaces")
            if (m.links.isNotEmpty()) append(" · ${m.links.size} links")
            append(" · ")
            append(when {
                config.string("scheme") == "flat" -> "flat color"
                layer != null -> "layer \"${layer.name}\"" + if (m.layers.size > 1) " (of ${m.layers.keys.joinToString()})" else ""
                else -> "no layers"
            })
            if (updates > 0) append(" · $updates updates")
        }
        when {
            wanted.isNotEmpty() && layer == null -> setStatus(StatusLevel.WARN, "No layer \"$wanted\" (there are: ${m.layers.keys.joinToString().ifEmpty { "none" }})")
            note != null -> setStatus(StatusLevel.WARN, "$text · $note")
            else -> setStatus(StatusLevel.OK, text)
        }
    }

    override fun setVisible(v: Boolean) { if (!v) geometry?.setVisible(false) }

    override fun destroy() {
        super.destroy()
        onDeactivate()
    }

    private companion object {
        const val EDGE_LIFT = 0.005
        const val LINK_WIDTH = 0.04
        val EDGE_COLOR = Colors.withAlpha(Colors.rgb(40, 40, 40), 0.6f)

        /** Slopes a little darker than flat floors, so ramps read as ramps. */
        fun slopeShade(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, cx: Double, cy: Double, cz: Double): Float {
            val ux = bx - ax; val uy = by - ay; val uz = bz - az; val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            return if (len < 1e-12) 1f else (0.7 + 0.3 * abs(nz) / len).toFloat()
        }
    }
}
