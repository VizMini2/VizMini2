// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.geom

import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.rviz.display.Colors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Conversion of ROS messages (already copied into simple structures) to geometry.
// Nothing here depends on Android or jros2: it is tested on the JVM.

// ====================================================================== point color

/**
 * How to color points: `mode` (intensity, range, x, y, z, rgb, flat...), automatic or
 * manual limits [min, max] for the rainbow, flat color and alpha.
 */
class ColorOptions(
    val mode: String, val flat: Int, val autoBounds: Boolean = true,
    val min: Double = 0.0, val max: Double = 1.0, val alpha: Float = 1f
) {
    /** Range of the scale: the automatic one (lo, hi) or the manual one. */
    fun bounds(lo: Double, hi: Double): Pair<Double, Double> = if (autoBounds) lo to hi else min to max

    fun scale(v: Double, lo: Double, hi: Double): Int {
        val span = if (hi > lo) hi - lo else 1.0
        return Colors.withAlpha(Colors.rainbow(((v - lo) / span).toFloat()), alpha)
    }

    fun flatColor(): Int = Colors.withAlpha(flat, alpha)
}

// ====================================================================== LaserScan

class LaserScanData(
    val angleMin: Float, val angleIncrement: Float, val rangeMin: Float, val rangeMax: Float,
    val ranges: FloatArray, val intensities: FloatArray
)

object LaserScanGeometry {
    /** Valid points (within [range_min, range_max] and finite) in the laser frame. */
    fun build(scan: LaserScanData, color: ColorOptions, size: Float, out: PointList) {
        out.clear()
        val n = scan.ranges.size
        val xs = FloatArray(n); val ys = FloatArray(n); val ok = BooleanArray(n)
        for (i in 0 until n) {
            val r = scan.ranges[i]
            if (!valid(scan, r)) continue
            val a = scan.angleMin + i * scan.angleIncrement
            xs[i] = r * cos(a); ys[i] = r * sin(a); ok[i] = true
        }
        val mode = if (color.mode == "intensity" && scan.intensities.size != n) "flat" else color.mode
        fun value(i: Int): Double = when (mode) {
            "intensity" -> scan.intensities[i].toDouble()
            "range" -> scan.ranges[i].toDouble()
            "x" -> xs[i].toDouble()
            "y" -> ys[i].toDouble()
            else -> 0.0 // z is always 0 in a 2D laser
        }
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        if (mode != "flat") for (i in 0 until n) if (ok[i]) { val v = value(i); if (v.isFinite()) { lo = min(lo, v); hi = max(hi, v) } }
        val (bLo, bHi) = color.bounds(lo, hi)
        for (i in 0 until n) {
            if (!ok[i]) continue
            val c = if (mode == "flat") color.flatColor() else color.scale(value(i), bLo, bHi)
            out.add(xs[i], ys[i], 0f, c, size)
        }
    }

    private fun valid(scan: LaserScanData, r: Float) = r.isFinite() && r >= scan.rangeMin && r <= scan.rangeMax
}

// ====================================================================== PointCloud2

class PointFieldInfo(val name: String, val offset: Int, val datatype: Int, val count: Int = 1)

object PointCloud2Decoder {
    const val INT8 = 1; const val UINT8 = 2; const val INT16 = 3; const val UINT16 = 4
    const val INT32 = 5; const val UINT32 = 6; const val FLOAT32 = 7; const val FLOAT64 = 8

    fun sizeOf(datatype: Int) = when (datatype) {
        INT8, UINT8 -> 1; INT16, UINT16 -> 2; INT32, UINT32, FLOAT32 -> 4; FLOAT64 -> 8; else -> 0
    }

    class Result(val total: Int, val drawn: Int, val colorUsed: String, val error: String?)

    /**
     * Decodes x/y/z and the color. If there are more than `maxPoints` points, one in every N is taken.
     * Points with NaN/Inf are discarded ("non-dense" clouds).
     */
    fun decode(
        fields: List<PointFieldInfo>, width: Int, height: Int, pointStep: Int, rowStep: Int,
        bigEndian: Boolean, data: ByteArray, size: Int,
        color: ColorOptions, pointSize: Float, maxPoints: Int, out: PointList
    ): Result {
        val colorMode = color.mode
        out.clear()
        val fx = fields.firstOrNull { it.name == "x" }
        val fy = fields.firstOrNull { it.name == "y" }
        val fz = fields.firstOrNull { it.name == "z" }
        if (fx == null || fy == null || fz == null) return Result(0, 0, colorMode, "The cloud has no x/y/z fields")
        if (pointStep <= 0 || width < 0 || height < 0) return Result(0, 0, colorMode, "Invalid point_step")
        val total = width.toLong() * height
        val rs = if (rowStep > 0) rowStep else width * pointStep
        if (total == 0L) return Result(0, 0, colorMode, null)
        if (rs.toLong() * (height - 1) + width.toLong() * pointStep > size) return Result(0, 0, colorMode, "The cloud data is truncated")

        val rgbField = fields.firstOrNull { it.name == "rgb" || it.name == "rgba" }
        val intensityField = fields.firstOrNull { it.name == "intensity" || it.name == "i" }
        val mode = when (colorMode) {
            "auto" -> when { rgbField != null -> "rgb"; intensityField != null -> "intensity"; else -> "z" }
            "rgb" -> if (rgbField != null) "rgb" else "flat"
            "intensity" -> if (intensityField != null) "intensity" else "flat"
            else -> colorMode
        }
        val alphaByte = (color.alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val stride = max(1L, (total + maxPoints - 1) / max(1, maxPoints)).toInt()

        // First pass for the color scale range.
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        val scalarField = when (mode) { "intensity" -> intensityField; "x" -> fx; "y" -> fy; "z" -> fz; else -> null }
        if (scalarField != null) {
            forEachPoint(width, height, pointStep, rs, stride) { base ->
                val v = read(data, base + scalarField.offset, scalarField.datatype, bigEndian)
                if (v.isFinite()) { lo = min(lo, v); hi = max(hi, v) }
            }
        }
        val (bLo, bHi) = color.bounds(lo, hi)

        forEachPoint(width, height, pointStep, rs, stride) { base ->
            val x = read(data, base + fx.offset, fx.datatype, bigEndian)
            val y = read(data, base + fy.offset, fy.datatype, bigEndian)
            val z = read(data, base + fz.offset, fz.datatype, bigEndian)
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return@forEachPoint
            val color = when (mode) {
                "rgb" -> {
                    val bits = readRawInt(data, base + rgbField!!.offset, bigEndian)
                    Colors.rgb((bits shr 16) and 0xFF, (bits shr 8) and 0xFF, bits and 0xFF, alphaByte)
                }
                "intensity", "x", "y", "z" -> {
                    val v = read(data, base + scalarField!!.offset, scalarField.datatype, bigEndian)
                    color.scale(v, bLo, bHi)
                }
                else -> color.flatColor()
            }
            out.add(x.toFloat(), y.toFloat(), z.toFloat(), color, pointSize)
        }
        return Result(total.toInt(), out.count, mode, null)
    }

    private inline fun forEachPoint(width: Int, height: Int, pointStep: Int, rowStep: Int, stride: Int, f: (Int) -> Unit) {
        var idx = 0L
        for (row in 0 until height) for (col in 0 until width) {
            if (idx++ % stride != 0L) continue
            f(row * rowStep + col * pointStep)
        }
    }

    fun readRawInt(d: ByteArray, i: Int, bigEndian: Boolean): Int {
        val b0 = d[i].toInt() and 0xFF; val b1 = d[i + 1].toInt() and 0xFF
        val b2 = d[i + 2].toInt() and 0xFF; val b3 = d[i + 3].toInt() and 0xFF
        return if (bigEndian) (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        else (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    fun read(d: ByteArray, i: Int, datatype: Int, bigEndian: Boolean): Double = when (datatype) {
        INT8 -> d[i].toDouble()
        UINT8 -> (d[i].toInt() and 0xFF).toDouble()
        INT16, UINT16 -> {
            val a = d[i].toInt() and 0xFF; val b = d[i + 1].toInt() and 0xFF
            val v = if (bigEndian) (a shl 8) or b else (b shl 8) or a
            (if (datatype == INT16) v.toShort().toInt() else v).toDouble()
        }
        INT32 -> readRawInt(d, i, bigEndian).toDouble()
        UINT32 -> (readRawInt(d, i, bigEndian).toLong() and 0xFFFFFFFFL).toDouble()
        FLOAT32 -> java.lang.Float.intBitsToFloat(readRawInt(d, i, bigEndian)).toDouble()
        FLOAT64 -> {
            val hiBits = readRawInt(d, if (bigEndian) i else i + 4, bigEndian).toLong()
            val loBits = readRawInt(d, if (bigEndian) i + 4 else i, bigEndian).toLong() and 0xFFFFFFFFL
            java.lang.Double.longBitsToDouble((hiBits shl 32) or loBits)
        }
        else -> Double.NaN
    }
}

// ====================================================================== PointCloud (sensor_msgs/PointCloud)

object PointCloudGeometry {
    /** `xyz` = x0,y0,z0,x1,...; `channels` = name -> per-point values. */
    fun build(xyz: FloatArray, channels: Map<String, FloatArray>, color: ColorOptions, size: Float, out: PointList) {
        out.clear()
        val n = xyz.size / 3
        val rgb = channels["rgb"]?.takeIf { it.size == n }
        val intensity = (channels["intensity"] ?: channels["intensities"])?.takeIf { it.size == n }
        val mode = when (color.mode) {
            "auto" -> when { rgb != null -> "rgb"; intensity != null -> "intensity"; else -> "z" }
            "intensity" -> if (intensity != null) "intensity" else "flat"
            else -> color.mode
        }
        fun value(i: Int): Float = when (mode) {
            "intensity" -> intensity!![i]; "x" -> xyz[i * 3]; "y" -> xyz[i * 3 + 1]; else -> xyz[i * 3 + 2]
        }
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        if (mode in setOf("intensity", "x", "y", "z")) for (i in 0 until n) {
            val v = value(i).toDouble(); if (v.isFinite()) { lo = min(lo, v); hi = max(hi, v) }
        }
        val (bLo, bHi) = color.bounds(lo, hi)
        val alphaByte = (color.alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        for (i in 0 until n) {
            val x = xyz[i * 3]; val y = xyz[i * 3 + 1]; val z = xyz[i * 3 + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            val c = when (mode) {
                "rgb" -> {
                    val bits = java.lang.Float.floatToRawIntBits(rgb!![i])
                    Colors.rgb((bits shr 16) and 0xFF, (bits shr 8) and 0xFF, bits and 0xFF, alphaByte)
                }
                "intensity", "x", "y", "z" -> color.scale(value(i).toDouble(), bLo, bHi)
                else -> color.flatColor()
            }
            out.add(x, y, z, c, size)
        }
    }
}

// ====================================================================== OccupancyGrid

object OccupancyGridImage {
    /**
     * RGBA (4 bytes per cell) in the same order as `data`: row 0 is the one at the map origin.
     * - map: free white, occupied black, unknown gray (like map_server).
     * - costmap: 0 transparent, 1..98 blue→red, 99 cyan (inscribed), 100 magenta (lethal).
     * - raw: the value as gray.
     */
    fun toRgba(data: ByteArray, width: Int, height: Int, scheme: String, alpha: Float, out: ByteArray = ByteArray(width * height * 4)): ByteArray {
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val n = width * height
        for (i in 0 until n) {
            val v = if (i < data.size) data[i].toInt() else -1 // signed: -1 = unknown
            val c = color(v, scheme, a)
            val o = i * 4
            out[o] = Colors.red(c).toByte(); out[o + 1] = Colors.green(c).toByte()
            out[o + 2] = Colors.blue(c).toByte(); out[o + 3] = Colors.alpha(c).toByte()
        }
        return out
    }

    fun color(v: Int, scheme: String, a: Int): Int = when (scheme) {
        "costmap" -> when {
            v == 0 -> Colors.rgb(0, 0, 0, 0)
            v in 1..98 -> Colors.withAlpha(costRamp(v / 98f), a / 255f)
            v == 99 -> Colors.rgb(0, 255, 255, a)
            v == 100 -> Colors.rgb(255, 0, 255, a)
            else -> Colors.rgb(0, 0, 0, 0)
        }
        "raw" -> { val g = v and 0xFF; Colors.rgb(g, g, g, a) }
        else -> when {
            v < 0 || v > 100 -> Colors.rgb(128, 128, 128, a)
            else -> { val g = 255 - (v * 255) / 100; Colors.rgb(g, g, g, a) }
        }
    }

    private fun costRamp(t: Float): Int = Colors.rgbf(t, 0f, 1f - t)
}

// ====================================================================== Odometry

/** Trail of Odometry poses with RViz's tolerances. Poses in the message frame. */
class OdometryTrail(var keep: Int, var positionTolerance: Double, var angleTolerance: Double) {
    class Pose(val x: Double, val y: Double, val z: Double, val qx: Double, val qy: Double, val qz: Double, val qw: Double)

    val poses = ArrayDeque<Pose>()
    var frameId: String = ""
        private set

    /** Returns true if the pose was added (changes what has to be drawn). */
    fun add(frame: String, p: Pose): Boolean {
        if (frame != frameId) { poses.clear(); frameId = frame }
        val last = poses.lastOrNull()
        if (last != null) {
            val dx = p.x - last.x; val dy = p.y - last.y; val dz = p.z - last.z
            val moved = sqrt(dx * dx + dy * dy + dz * dz) >= positionTolerance
            val turned = angleBetween(last, p) >= angleTolerance
            if (!moved && !turned) return false
        }
        poses.addLast(p)
        while (poses.size > max(1, keep)) poses.removeFirst()
        return true
    }

    fun clear() = poses.clear()

    companion object {
        fun angleBetween(a: Pose, b: Pose): Double {
            val dot = abs(a.qx * b.qx + a.qy * b.qy + a.qz * b.qz + a.qw * b.qw)
            return 2 * acos(dot.coerceIn(-1.0, 1.0))
        }
    }
}

// ====================================================================== Markers

class MarkerData(
    val ns: String, val id: Int, val type: Int, val action: Int, val frameId: String,
    val pose: DoubleArray, val sx: Double, val sy: Double, val sz: Double, val color: Int,
    val points: FloatArray, val colors: IntArray, val text: String, val lifetimeMs: Long,
    /** MESH_RESOURCE: the mesh ("package://…", "http://…") and whether its own colors are used. */
    val meshUri: String = "", val meshEmbedded: Boolean = false
) {
    companion object {
        const val ARROW = 0; const val CUBE = 1; const val SPHERE = 2; const val CYLINDER = 3
        const val LINE_STRIP = 4; const val LINE_LIST = 5; const val CUBE_LIST = 6; const val SPHERE_LIST = 7
        const val POINTS = 8; const val TEXT_VIEW_FACING = 9; const val MESH_RESOURCE = 10; const val TRIANGLE_LIST = 11
        const val ADD = 0; const val DELETE = 2; const val DELETEALL = 3
    }
}

class TextLabel(val text: String, val x: Double, val y: Double, val z: Double, val color: Int, val heightM: Double = 0.2)

/** State of the received markers: add/modify, delete, delete all and expiry. */
class MarkerStore {
    private class Entry(val marker: MarkerData, val expiresAt: Long)

    private val entries = LinkedHashMap<Pair<String, Int>, Entry>()
    var unsupported = 0; private set

    val size: Int get() = entries.size

    /** Returns true if anything changes. */
    fun apply(m: MarkerData, nowMs: Long): Boolean = when (m.action) {
        MarkerData.DELETEALL -> {
            val changed = entries.isNotEmpty()
            // Since ROS 2 Foxy, DELETEALL with a non-empty ns deletes only that namespace.
            if (m.ns.isEmpty()) entries.clear() else entries.keys.removeAll { it.first == m.ns }
            changed
        }
        MarkerData.DELETE -> entries.remove(m.ns to m.id) != null
        else -> {
            val expires = if (m.lifetimeMs > 0) nowMs + m.lifetimeMs else Long.MAX_VALUE
            entries[m.ns to m.id] = Entry(m, expires)
            true
        }
    }

    /** Removes the expired ones. Returns true if any was deleted. */
    fun expire(nowMs: Long): Boolean = entries.values.removeAll { it.expiresAt <= nowMs }

    fun clear() = entries.clear()

    fun markers(): List<MarkerData> = entries.values.map { it.marker }

    /** MESH_RESOURCEs that cannot be drawn yet (downloading, no server, unusual format). */
    var meshesMissing = 0; private set

    /**
     * Geometry grouped by frame. `meshes` gives the mesh for a URI if it is already loaded (null: not yet,
     * or it cannot be); without it, MESH_RESOURCEs count as unsupported.
     */
    fun build(useLineWidths: Boolean = true, meshes: ((String) -> MeshData?)? = null): Map<String, Pair<MeshBuilder, List<TextLabel>>> {
        unsupported = 0
        meshesMissing = 0
        val out = LinkedHashMap<String, Pair<MeshBuilder, MutableList<TextLabel>>>()
        for (e in entries.values) {
            val m = e.marker
            val (b, labels) = out.getOrPut(m.frameId) { MeshBuilder() to mutableListOf() }
            if (m.type == MarkerData.MESH_RESOURCE) {
                if (meshes == null) { unsupported++; continue }
                val mesh = meshes(m.meshUri)
                if (mesh == null) meshesMissing++ else MarkerGeometry.addMesh(m, mesh, b)
                continue
            }
            if (!MarkerGeometry.add(m, b, labels, useLineWidths)) unsupported++
        }
        return out
    }
}

object MarkerGeometry {
    /**
     * A MESH_RESOURCE: the mesh scaled by `scale`, with the marker color or, if
     * `mesh_use_embedded_materials` and the marker color is fully transparent (as in RViz),
     * with the mesh's colors.
     */
    fun addMesh(m: MarkerData, mesh: MeshData, b: MeshBuilder) {
        val embedded = m.meshEmbedded && Colors.alpha(m.color) == 0 && (m.color and 0xFFFFFF) == 0
        b.withTransform(m.pose) {
            withTransform(MeshBuilder.scale(m.sx, m.sy, m.sz)) {
                val t = mesh.triangles
                for (i in 0 until mesh.triangleCount) {
                    val o = i * 9
                    val own = mesh.colors[i]
                    val c = if (embedded) (if (own != 0) own else Colors.rgb(200, 200, 200)) else m.color
                    triangle(t[o].toDouble(), t[o + 1].toDouble(), t[o + 2].toDouble(), t[o + 3].toDouble(), t[o + 4].toDouble(),
                        t[o + 5].toDouble(), t[o + 6].toDouble(), t[o + 7].toDouble(), t[o + 8].toDouble(), c)
                }
            }
        }
    }

    /** Adds a marker's geometry. Returns false if the type is not supported. */
    fun add(m: MarkerData, b: MeshBuilder, labels: MutableList<TextLabel>, useLineWidths: Boolean = true): Boolean {
        val width = if (useLineWidths) m.sx else 0.0
        val n = m.points.size / 3
        fun pc(i: Int) = if (m.colors.size == n) m.colors[i] else m.color
        b.withTransform(m.pose) {
            when (m.type) {
                MarkerData.ARROW -> if (n >= 2) {
                    val p = m.points
                    val headLen = if (m.sz > 0) m.sz else 0.23 * dist(p, 0, 1)
                    arrowBetween(
                        p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(),
                        m.color, m.sx / 2, max(m.sy / 2, m.sx / 2), headLen
                    )
                } else {
                    // scale.x = length, scale.y/z = width/height.
                    val r = max(m.sy, m.sz) / 2
                    arrow(m.sx, m.color, shaftRadius = r * 0.5, headLength = m.sx * 0.23, headRadius = r)
                }
                MarkerData.CUBE -> box(m.sx, m.sy, m.sz, m.color)
                MarkerData.SPHERE -> sphere(m.sx / 2, m.sy / 2, m.sz / 2, m.color)
                MarkerData.CYLINDER -> cylinder(m.sx / 2, m.sy / 2, m.sz, m.color)
                MarkerData.LINE_STRIP -> for (i in 0 until n - 1) {
                    thickLine(px(m, i), py(m, i), pz(m, i), px(m, i + 1), py(m, i + 1), pz(m, i + 1), pc(i), width, pc(i + 1))
                }
                MarkerData.LINE_LIST -> for (i in 0 until n - 1 step 2) {
                    thickLine(px(m, i), py(m, i), pz(m, i), px(m, i + 1), py(m, i + 1), pz(m, i + 1), pc(i), width, pc(i + 1))
                }
                MarkerData.CUBE_LIST -> for (i in 0 until n) withTransform(MeshBuilder.translation(px(m, i), py(m, i), pz(m, i))) {
                    box(m.sx, m.sy, m.sz, pc(i))
                }
                MarkerData.SPHERE_LIST -> for (i in 0 until n) {
                    // Many spheres are expensive to draw: from 500 on they are drawn as points.
                    if (n > 500) point(px(m, i), py(m, i), pz(m, i), pc(i), m.sx.toFloat())
                    else withTransform(MeshBuilder.translation(px(m, i), py(m, i), pz(m, i))) { sphere(m.sx / 2, m.sy / 2, m.sz / 2, pc(i), 6, 8) }
                }
                MarkerData.POINTS -> for (i in 0 until n) point(px(m, i), py(m, i), pz(m, i), pc(i), m.sx.toFloat())
                MarkerData.TRIANGLE_LIST -> for (i in 0 until n - 2 step 3) {
                    triangleColors(
                        px(m, i), py(m, i), pz(m, i), px(m, i + 1), py(m, i + 1), pz(m, i + 1),
                        px(m, i + 2), py(m, i + 2), pz(m, i + 2), pc(i), pc(i + 1), pc(i + 2)
                    )
                }
                MarkerData.TEXT_VIEW_FACING -> {
                    // TEXT_VIEW_FACING: scale.z is the height of the letter "A" (RViz).
                    labels.add(TextLabel(m.text, m.pose[12], m.pose[13], m.pose[14], m.color, if (m.sz > 0) m.sz else 0.2))
                }
                else -> return false
            }
        }
        return true
    }

    private fun px(m: MarkerData, i: Int) = m.points[i * 3].toDouble()
    private fun py(m: MarkerData, i: Int) = m.points[i * 3 + 1].toDouble()
    private fun pz(m: MarkerData, i: Int) = m.points[i * 3 + 2].toDouble()
    private fun dist(p: FloatArray, a: Int, b: Int): Double {
        val dx = p[b * 3] - p[a * 3]; val dy = p[b * 3 + 1] - p[a * 3 + 1]; val dz = p[b * 3 + 2] - p[a * 3 + 2]
        return sqrt((dx * dx + dy * dy + dz * dz).toDouble())
    }
}

// ====================================================================== Covariance

object Covariance {
    /**
     * 1-sigma ellipse of the XY covariance (ROS 6x6 matrix, row-major):
     * returns semi-axes (a, b) and the major axis angle relative to X, or null if it is not valid.
     */
    fun ellipseXY(cov: DoubleArray): Triple<Double, Double, Double>? {
        if (cov.size < 8) return null
        val xx = cov[0]; val xy = cov[1]; val yy = cov[7]
        if (!xx.isFinite() || !yy.isFinite() || !xy.isFinite()) return null
        val tr = xx + yy
        val det = xx * yy - xy * xy
        val disc = sqrt(max(0.0, tr * tr / 4 - det))
        val l1 = tr / 2 + disc
        val l2 = tr / 2 - disc
        if (l1 <= 0) return null
        val angle = 0.5 * atan2(2 * xy, xx - yy)
        return Triple(sqrt(l1), sqrt(max(0.0, l2)), angle)
    }
}

/** Planar pose from an angle (for tests and axes). */
fun yawPose(x: Double, y: Double, yaw: Double): DoubleArray =
    TfMath.fromTranslationQuaternion(x, y, 0.0, 0.0, 0.0, sin(yaw / 2), cos(yaw / 2))
