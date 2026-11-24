// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.display

import com.juancams.rviz2mini.TfMath
import java.util.concurrent.atomic.AtomicReference
import com.juancams.rviz2mini.rviz.geom.UrdfParser
import com.juancams.rviz2mini.rviz.geom.UrdfModel
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.rviz.geom.MeshBuilder
import java.util.Locale

private fun DisplayConfig.alphaColorOf(key: String): Int = Colors.withAlpha(color(key), number("alpha").toFloat())

// ====================================================================== GridCells

/** nav_msgs/GridCells: one flat tile per cell (like the old Nav2 costmaps). */
internal class GridCellsDisplay(host: DisplayHost, config: DisplayConfig) :
    TopicDisplay<nav_msgs.GridCells, GridCellsDisplay.Cells>(host, config, nav_msgs.GridCells::class.java) {

    class Cells(val frame: String, val w: Double, val h: Double, val xyz: DoubleArray)

    private val geometry = GeometrySet(host.engine, host.scene)
    private var cells: Cells? = null
    private var dirty = false

    override val reuseMessage = true
    override fun newMessage() = nav_msgs.GridCells()
    override fun prepareForRead(msg: nav_msgs.GridCells) = msg.cells.clear()

    override fun parse(msg: nav_msgs.GridCells): Cells {
        val n = minOf(msg.cells.size(), MAX_CELLS)
        val xyz = DoubleArray(n * 3)
        for (i in 0 until n) { val p = msg.cells.get(i); xyz[i * 3] = p.x; xyz[i * 3 + 1] = p.y; xyz[i * 3 + 2] = p.z }
        return Cells(msg.header.frameIdAsString, msg.cellWidth.toDouble(), msg.cellHeight.toDouble(), xyz)
    }

    override fun consume(data: Cells, ctx: FrameContext) { cells = data; dirty = true }
    override fun onConfigChanged(old: DisplayConfig) { super.onConfigChanged(old); dirty = true }

    override fun draw(ctx: FrameContext): DisplayStatus? {
        val c = cells ?: return null
        if (dirty) {
            val b = MeshBuilder()
            val color = config.alphaColorOf("color")
            for (i in 0 until c.xyz.size / 3) {
                b.withTransform(MeshBuilder.translation(c.xyz[i * 3], c.xyz[i * 3 + 1], c.xyz[i * 3 + 2])) { box(c.w, c.h, 0.01, color) }
            }
            geometry.update(b)
            dirty = false
        }
        val t = ctx.transformF(c.frame) ?: run { geometry.setVisible(false); return DisplayStatus(StatusLevel.WARN, "No transform from '${c.frame}' to '${ctx.fixedFrame}'") }
        geometry.setVisible(true); geometry.setTransform(t)
        return DisplayStatus(StatusLevel.OK, "${c.xyz.size / 3} cells")
    }

    override fun setVisible(v: Boolean) = geometry.setVisible(v)
    override fun destroy() { super.destroy(); geometry.destroy() }

    private companion object { const val MAX_CELLS = 50_000 }
}

// ====================================================================== Wrench / Twist / Accel

/**
 * Two vectors at the origin of the header frame: force and torque (WrenchStamped), linear and
 * angular (TwistStamped, AccelStamped). The angular part is drawn as an arrow along its axis
 * (right-hand rule), in another color.
 */
internal class VectorPairDisplay<M : us.ihmc.jros2.ROS2Message<M>>(
    host: DisplayHost, config: DisplayConfig, cls: Class<M>,
    private val factory: () -> M,
    private val read: (M) -> Pair<String, DoubleArray>
) : TopicDisplay<M, Pair<String, DoubleArray>>(host, config, cls) {

    private val geometry = GeometrySet(host.engine, host.scene)
    private var last: Pair<String, DoubleArray>? = null
    private var dirty = false

    override fun newMessage() = factory()
    override fun parse(msg: M) = read(msg)
    override fun consume(data: Pair<String, DoubleArray>, ctx: FrameContext) { last = data; dirty = true }
    override fun onConfigChanged(old: DisplayConfig) { super.onConfigChanged(old); dirty = true }

    override fun draw(ctx: FrameContext): DisplayStatus? {
        val (frame, v) = last ?: return null
        if (dirty) {
            val b = MeshBuilder()
            val width = config.number("width")
            fun vec(x: Double, y: Double, z: Double, scale: Double, color: Int) {
                val len = Math.sqrt(x * x + y * y + z * z) * scale
                if (len < 1e-6) return
                val head = minOf(len * 0.3, width * 3)
                b.arrowBetween(0.0, 0.0, 0.0, x * scale, y * scale, z * scale, color, width / 2, width * 1.5, head)
            }
            vec(v[0], v[1], v[2], config.number("linearScale"), config.alphaColorOf("linearColor"))
            vec(v[3], v[4], v[5], config.number("angularScale"), config.alphaColorOf("angularColor"))
            geometry.update(b)
            dirty = false
        }
        val t = ctx.transformF(frame) ?: run { geometry.setVisible(false); return DisplayStatus(StatusLevel.WARN, "No transform from '$frame' to '${ctx.fixedFrame}'") }
        geometry.setVisible(true); geometry.setTransform(t)
        val l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); val a = Math.sqrt(v[3] * v[3] + v[4] * v[4] + v[5] * v[5])
        return DisplayStatus(StatusLevel.OK, String.format(Locale.ROOT, "|%s| %.3f · |%s| %.3f", config.type.linearName, l, config.type.angularName, a))
    }

    override fun setVisible(v: Boolean) = geometry.setVisible(v)
    override fun destroy() { super.destroy(); geometry.destroy() }

    companion object {
        fun v3(a: geometry_msgs.Vector3, b: geometry_msgs.Vector3) = doubleArrayOf(a.x, a.y, a.z, b.x, b.y, b.z)
    }
}

/** Names of the two parts in the display status. */
private val DisplayType.linearName get() = if (this == DisplayType.WRENCH) "force" else "linear"
private val DisplayType.angularName get() = if (this == DisplayType.WRENCH) "torque" else "angular"

// ====================================================================== Temperature, Illuminance...

/**
 * Single-value sensors (Temperature, Illuminance, FluidPressure, RelativeHumidity): a sphere
 * at the origin of the sensor frame, from blue (minimum) to red (maximum), with the value beside it.
 */
internal class ScalarSensorDisplay<M : us.ihmc.jros2.ROS2Message<M>>(
    host: DisplayHost, config: DisplayConfig, cls: Class<M>,
    private val factory: () -> M,
    private val read: (M) -> Pair<String, Double>,
    private val unit: String
) : TopicDisplay<M, Pair<String, Double>>(host, config, cls) {

    private val geometry = GeometrySet(host.engine, host.scene)
    private var last: Pair<String, Double>? = null
    private var dirty = false

    override fun newMessage() = factory()
    override fun parse(msg: M) = read(msg)
    override fun consume(data: Pair<String, Double>, ctx: FrameContext) { last = data; dirty = true }
    override fun onConfigChanged(old: DisplayConfig) { super.onConfigChanged(old); dirty = true }

    override fun draw(ctx: FrameContext): DisplayStatus? {
        val (frame, value) = last ?: return null
        if (dirty) {
            val lo = config.number("minValue"); val hi = config.number("maxValue")
            val f = if (hi > lo) ((value - lo) / (hi - lo)).toFloat() else 0.5f
            val color = Colors.withAlpha(Colors.rainbow(1f - f), config.number("alpha").toFloat())
            val r = config.number("size") / 2
            val b = MeshBuilder()
            b.sphere(r, r, r, color)
            geometry.update(b)
            dirty = false
        }
        val pose = ctx.transformD(frame) ?: run { geometry.setVisible(false); return DisplayStatus(StatusLevel.WARN, "No transform from '$frame' to '${ctx.fixedFrame}'") }
        geometry.setVisible(true); geometry.setTransform(ctx.transformF(frame)!!)
        val text = String.format(Locale.ROOT, "%.2f %s", value, unit).trim()
        // The 3D view text has no "°" in its atlas.
        if (config.bool("showValue")) ctx.labels.label("scalar:${config.id}", text.replace("°", ""), pose[12], pose[13], pose[14] + config.number("size"), 0xFFFFFFFF.toInt(), 14.0, screenDp = true)
        return DisplayStatus(StatusLevel.OK, text)
    }

    override fun setVisible(v: Boolean) = geometry.setVisible(v)
    override fun destroy() { super.destroy(); geometry.destroy() }
}

/** The displays in this file, by type (null if it is not one of them). */
internal fun createExtraDisplay(host: DisplayHost, c: DisplayConfig): Display? = when (c.type) {
    DisplayType.GRID_CELLS -> GridCellsDisplay(host, c)
    DisplayType.WRENCH -> VectorPairDisplay(host, c, geometry_msgs.WrenchStamped::class.java, { geometry_msgs.WrenchStamped() }) { m ->
        m.header.frameIdAsString to VectorPairDisplay.v3(m.wrench.force, m.wrench.torque) }
    DisplayType.TWIST -> VectorPairDisplay(host, c, geometry_msgs.TwistStamped::class.java, { geometry_msgs.TwistStamped() }) { m ->
        m.header.frameIdAsString to VectorPairDisplay.v3(m.twist.linear, m.twist.angular) }
    DisplayType.ACCEL -> VectorPairDisplay(host, c, geometry_msgs.AccelStamped::class.java, { geometry_msgs.AccelStamped() }) { m ->
        m.header.frameIdAsString to VectorPairDisplay.v3(m.accel.linear, m.accel.angular) }
    DisplayType.TEMPERATURE -> ScalarSensorDisplay(host, c, sensor_msgs.Temperature::class.java, { sensor_msgs.Temperature() }, { m -> m.header.frameIdAsString to m.temperature }, "°C")
    DisplayType.ILLUMINANCE -> ScalarSensorDisplay(host, c, sensor_msgs.Illuminance::class.java, { sensor_msgs.Illuminance() }, { m -> m.header.frameIdAsString to m.illuminance }, "lx")
    DisplayType.FLUID_PRESSURE -> ScalarSensorDisplay(host, c, sensor_msgs.FluidPressure::class.java, { sensor_msgs.FluidPressure() }, { m -> m.header.frameIdAsString to m.fluidPressure }, "Pa")
    DisplayType.RELATIVE_HUMIDITY -> ScalarSensorDisplay(host, c, sensor_msgs.RelativeHumidity::class.java, { sensor_msgs.RelativeHumidity() }, { m -> m.header.frameIdAsString to m.relativeHumidity * 100 }, "%")
    else -> null
}

internal class EffortDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {

    private val geometry = GeometrySet(host.engine, host.scene)
    private var statesSub: DynRos.Subscription? = null
    private var urdfSub: DynRos.Subscription? = null
    private val efforts = AtomicReference<Map<String, Double>?>()
    private val model = AtomicReference<UrdfModel?>()
    @Volatile private var received = 0L
    @Volatile private var urdfError: String? = null

    private val descriptionTopic get() = DynRos.normalizeName(config.string("description").ifBlank { "/robot_description" })

    override fun onActivate() {
        val reg = Interfaces.get()
        received = 0
        statesSub = DynRos.subscribe(DynRos.normalizeName(config.topic), reg.msg("sensor_msgs/msg/JointState"), config.qosProfile()) { v ->
            received++
            val names = (v["name"] as? List<*>).orEmpty()
            val e = when (val x = v["effort"]) { is DoubleArray -> x.toList(); is List<*> -> x.map { (it as? Number)?.toDouble() ?: Double.NaN }; else -> emptyList() }
            val map = HashMap<String, Double>()
            for ((k, n) in names.withIndex()) e.getOrNull(k)?.takeIf { it.isFinite() }?.let { map[n.toString()] = it }
            efforts.set(map)
        }
        urdfSub = DynRos.subscribe(descriptionTopic, reg.msg("std_msgs/msg/String"), TopicDisplay.qosProfile(QosPreset.LATCHED)) { v ->
            try {
                model.set(UrdfParser.parse(com.juancams.rviz2mini.ros.RosStrings.fix(v["data"].toString())))
                urdfError = null
            } catch (e: Exception) {
                urdfError = e.message
            }
        }
    }

    override fun onDeactivate() {
        statesSub?.close(); statesSub = null
        urdfSub?.close(); urdfSub = null
    }

    override fun onConfigChanged(old: DisplayConfig) {
        if (active && (old.topic != config.topic || old.string("description") != config.string("description") || old.qosKey() != config.qosKey())) {
            onDeactivate(); onActivate()
        }
    }

    override fun render(ctx: FrameContext) {
        val m = model.get()
        val e = efforts.get()
        when {
            urdfError != null -> { setStatus(StatusLevel.ERROR, "URDF: $urdfError"); return }
            m == null -> { setStatus(StatusLevel.WARN, "Waiting for the robot description on $descriptionTopic"); return }
            e == null -> { waiting("Waiting for messages on ${config.topic}"); return }
        }
        val b = MeshBuilder()
        val radius = config.number("radius"); val width = config.number("width")
        val defaultLimit = config.number("maxEffort").coerceAtLeast(1e-9)
        val alpha = config.number("alpha").toFloat()
        var drawn = 0; var missing: String? = null
        for (j in m!!.joints) {
            val effort = e!![j.name] ?: continue
            if (j.type == "fixed") continue
            val t = ctx.transformD(j.child) ?: run { missing = j.child; null } ?: continue
            val limit = if (j.effortLimit > 0) j.effortLimit else defaultLimit
            val ratio = (Math.abs(effort) / limit).coerceIn(0.0, 1.0)
            val color = Colors.withAlpha(Colors.rgb((255 * ratio).toInt(), (255 * (1 - ratio)).toInt(), 0), alpha)
            b.withTransform(t) { effortArc(this, j.axis, radius, ratio, effort >= 0, color, width) }
            drawn++
        }
        geometry.update(b)
        geometry.setTransform(IDENTITY)
        when {
            drawn == 0 && missing != null -> tfMissing(missing!!, ctx)
            drawn == 0 -> setStatus(StatusLevel.WARN, "No joint in ${config.topic} has an effort and a joint in the URDF")
            else -> setStatus(StatusLevel.OK, "$drawn joint efforts")
        }
    }

    /** Arc of `ratio` turns around `axis` (at the origin), with an arrow in the direction of the effort. */
    private fun effortArc(b: MeshBuilder, axis: DoubleArray, r: Double, ratio: Double, positive: Boolean, color: Int, width: Double) {
        // Two vectors perpendicular to the axis.
        val a = if (Math.abs(axis[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val u = cross(axis, a).let { norm(it) }
        val w = cross(axis, u)
        val sweep = 2 * Math.PI * ratio * (if (positive) 1 else -1)
        val n = maxOf(2, (Math.abs(sweep) / (Math.PI / 24)).toInt())
        fun p(ang: Double) = DoubleArray(3) { k -> r * (Math.cos(ang) * u[k] + Math.sin(ang) * w[k]) }
        var prev = p(0.0)
        for (s in 1..n) {
            val q = p(sweep * s / n)
            b.thickLine(prev[0], prev[1], prev[2], q[0], q[1], q[2], color, width)
            prev = q
        }
        if (ratio > 0.02) {
            // Tip tangent to the end of the arc.
            val end = p(sweep)
            val dir = DoubleArray(3) { k -> (-Math.sin(sweep) * u[k] + Math.cos(sweep) * w[k]) * (if (positive) 1 else -1) }
            val head = r * 0.4
            b.arrowBetween(end[0] - dir[0] * head * 0.2, end[1] - dir[1] * head * 0.2, end[2] - dir[2] * head * 0.2,
                end[0] + dir[0] * head, end[1] + dir[1] * head, end[2] + dir[2] * head, color, width / 2, width * 1.5, head)
        }
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(v: DoubleArray): DoubleArray { val l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return DoubleArray(3) { v[it] / l } }

    override fun setVisible(v: Boolean) = geometry.setVisible(v)
    override fun destroy() { super.destroy(); geometry.destroy() }

    private companion object {
        val IDENTITY = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
    }
}
