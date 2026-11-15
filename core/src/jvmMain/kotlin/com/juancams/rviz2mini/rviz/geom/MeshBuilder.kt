// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.geom

import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.rviz.display.Colors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Growable float array (avoids the boxing of ArrayList<Float>). */
class FloatList(initial: Int = 64) {
    var data = FloatArray(initial); private set
    var size = 0; private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(max(16, data.size * 2))
        data[size++] = v
    }

    fun add3(x: Float, y: Float, z: Float) { add(x); add(y); add(z) }
    fun clear() { size = 0 }
    fun toArray(): FloatArray = data.copyOf(size)
    operator fun get(i: Int) = data[i]
}

class IntList(initial: Int = 64) {
    var data = IntArray(initial); private set
    var size = 0; private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(max(16, data.size * 2))
        data[size++] = v
    }

    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
    operator fun get(i: Int) = data[i]
}

/** Non-indexed vertices: xyz positions + ARGB color per vertex. */
class VertexList {
    val positions = FloatList()
    val colors = IntList()
    val vertexCount: Int get() = colors.size
    val isEmpty: Boolean get() = colors.size == 0

    fun add(x: Float, y: Float, z: Float, color: Int) {
        positions.add3(x, y, z); colors.add(color)
    }

    fun clear() { positions.clear(); colors.clear() }
}

/** Points drawn as camera-facing squares: position, color and size (m) per point. */
class PointList {
    val positions = FloatList()
    val colors = IntList()
    val sizes = FloatList()
    val count: Int get() = colors.size

    fun add(x: Float, y: Float, z: Float, color: Int, size: Float) {
        positions.add3(x, y, z); colors.add(color); sizes.add(size)
    }

    fun clear() { positions.clear(); colors.clear(); sizes.clear() }
}

/** Thick segments (billboards): endpoints a/b, color per endpoint and width (m) per segment. */
class SegmentList {
    val positions = FloatList()   // 6 floats per segment
    val colors = IntList()        // 2 per segment
    val widths = FloatList()      // 1 per segment
    val count: Int get() = widths.size
    val isEmpty: Boolean get() = widths.size == 0

    fun add(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, ca: Int, cb: Int, width: Float) {
        positions.add3(ax, ay, az); positions.add3(bx, by, bz)
        colors.add(ca); colors.add(cb); widths.add(width)
    }

    fun clear() { positions.clear(); colors.clear(); widths.clear() }
}

/**
 * Builds geometry in a frame's coordinates. Applies the current transform (`push`/`pop`)
 * to every vertex and gives a fixed per-face shading (directional light "painted" into the color) so
 * that solids look 3D with an unlit material.
 *
 * Triangles with alpha < 255 go to a separate list (transparent material).
 */
class MeshBuilder {
    val lines = VertexList()
    val triangles = VertexList()
    val transparentTriangles = VertexList()
    val points = PointList()
    val segments = SegmentList()
    /** Point style of this builder: squares (false) or circles (true). */
    var circlePoints = false

    private val stack = ArrayList<DoubleArray>()
    private var m = TfMath.identity()
    private val tmp = DoubleArray(3)

    val isEmpty: Boolean
        get() = lines.isEmpty && triangles.isEmpty && transparentTriangles.isEmpty && points.count == 0 && segments.isEmpty

    fun clear() {
        lines.clear(); triangles.clear(); transparentTriangles.clear(); points.clear(); segments.clear()
        stack.clear(); TfMath.setIdentity(m)
    }

    /** Multiplies the current transform by `t` (column-major) until the matching `pop()`. */
    fun push(t: DoubleArray) {
        stack.add(m.copyOf())
        m = TfMath.multiply(m, t, DoubleArray(16))
    }

    fun pop() {
        m = stack.removeAt(stack.size - 1)
    }

    inline fun withTransform(t: DoubleArray, block: MeshBuilder.() -> Unit) {
        push(t)
        try { block() } finally { pop() }
    }

    // ------------------------------------------------------------------ primitives

    private fun xform(x: Double, y: Double, z: Double): DoubleArray {
        tmp[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        tmp[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        tmp[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        return tmp
    }

    fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Int, color2: Int = color) {
        xform(x1, y1, z1).let { lines.add(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), color) }
        xform(x2, y2, z2).let { lines.add(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), color2) }
    }

    /** Line with a real width in meters (billboard). `width <= 0` = 1 px line. */
    fun thickLine(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Int, width: Double, color2: Int = color) {
        if (width <= 0.0) { line(x1, y1, z1, x2, y2, z2, color, color2); return }
        val a = xform(x1, y1, z1); val ax = a[0].toFloat(); val ay = a[1].toFloat(); val az = a[2].toFloat()
        val b = xform(x2, y2, z2)
        segments.add(ax, ay, az, b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), color, color2, width.toFloat())
    }

    fun point(x: Double, y: Double, z: Double, color: Int, size: Float) {
        val p = xform(x, y, z)
        points.add(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), color, size)
    }

    /** Triangle shaded according to its normal (in frame coordinates). */
    fun triangle(
        ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double,
        cx: Double, cy: Double, cz: Double, color: Int, shaded: Boolean = true
    ) {
        val c = if (shaded) Colors.shade(color, shadeFactor(ax, ay, az, bx, by, bz, cx, cy, cz)) else color
        triangleColors(ax, ay, az, bx, by, bz, cx, cy, cz, c, c, c)
    }

    fun triangleColors(
        ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double,
        cx: Double, cy: Double, cz: Double, ca: Int, cb: Int, cc: Int
    ) {
        val target = if (Colors.alpha(ca) < 255 || Colors.alpha(cb) < 255 || Colors.alpha(cc) < 255) transparentTriangles else triangles
        xform(ax, ay, az).let { target.add(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), ca) }
        xform(bx, by, bz).let { target.add(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), cb) }
        xform(cx, cy, cz).let { target.add(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), cc) }
    }

    private fun quad(
        ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double,
        cx: Double, cy: Double, cz: Double, dx: Double, dy: Double, dz: Double, color: Int
    ) {
        triangle(ax, ay, az, bx, by, bz, cx, cy, cz, color)
        triangle(ax, ay, az, cx, cy, cz, dx, dy, dz, color)
    }

    /** Light factor for a face: 0.55 (facing away) .. 1.0 (facing the light). */
    private fun shadeFactor(
        ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, cx: Double, cy: Double, cz: Double
    ): Float {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        // Normal in parent coordinates (rotation only).
        val wx = m[0] * nx + m[4] * ny + m[8] * nz
        val wy = m[1] * nx + m[5] * ny + m[9] * nz
        val wz = m[2] * nx + m[6] * ny + m[10] * nz
        val len = sqrt(wx * wx + wy * wy + wz * wz)
        if (len < 1e-12) return 1f
        nx = wx / len; ny = wy / len; nz = wz / len
        val d = nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z
        return (0.55 + 0.45 * max(0.0, d)).toFloat()
    }

    // ------------------------------------------------------------------ solids (centered at the origin)

    /** Box with sides sx, sy, sz centered at the origin. */
    fun box(sx: Double, sy: Double, sz: Double, color: Int) {
        val x = sx / 2; val y = sy / 2; val z = sz / 2
        quad(-x, -y, z, x, -y, z, x, y, z, -x, y, z, color)       // +Z
        quad(-x, y, -z, x, y, -z, x, -y, -z, -x, -y, -z, color)   // -Z
        quad(x, -y, -z, x, y, -z, x, y, z, x, -y, z, color)       // +X
        quad(-x, y, -z, -x, -y, -z, -x, -y, z, -x, y, z, color)   // -X
        quad(x, y, -z, -x, y, -z, -x, y, z, x, y, z, color)       // +Y
        quad(-x, -y, -z, x, -y, -z, x, -y, z, -x, -y, z, color)   // -Y
    }

    /** Cylinder along Z, centered, with radii rx/ry and height h. */
    fun cylinder(rx: Double, ry: Double, h: Double, color: Int, segments: Int = 16, caps: Boolean = true) {
        val z0 = -h / 2; val z1 = h / 2
        for (i in 0 until segments) {
            val a0 = 2 * Math.PI * i / segments
            val a1 = 2 * Math.PI * (i + 1) / segments
            val x0 = rx * cos(a0); val y0 = ry * sin(a0)
            val x1 = rx * cos(a1); val y1 = ry * sin(a1)
            quad(x0, y0, z0, x1, y1, z0, x1, y1, z1, x0, y0, z1, color)
            if (caps) {
                triangle(0.0, 0.0, z1, x0, y0, z1, x1, y1, z1, color)
                triangle(0.0, 0.0, z0, x1, y1, z0, x0, y0, z0, color)
            }
        }
    }

    /** Cone along +Z: base at z=0 with radius r, apex at z=h. */
    fun cone(r: Double, h: Double, color: Int, segments: Int = 16) {
        for (i in 0 until segments) {
            val a0 = 2 * Math.PI * i / segments
            val a1 = 2 * Math.PI * (i + 1) / segments
            val x0 = r * cos(a0); val y0 = r * sin(a0)
            val x1 = r * cos(a1); val y1 = r * sin(a1)
            triangle(x0, y0, 0.0, x1, y1, 0.0, 0.0, 0.0, h, color)
            triangle(0.0, 0.0, 0.0, x1, y1, 0.0, x0, y0, 0.0, color)
        }
    }

    /** Centered ellipsoid with semi-axes rx, ry, rz. */
    fun sphere(rx: Double, ry: Double, rz: Double, color: Int, rings: Int = 8, segments: Int = 12) {
        fun p(ring: Int, seg: Int): DoubleArray {
            val th = Math.PI * ring / rings
            val ph = 2 * Math.PI * seg / segments
            return doubleArrayOf(rx * sin(th) * cos(ph), ry * sin(th) * sin(ph), rz * cos(th))
        }
        for (r in 0 until rings) for (s in 0 until segments) {
            val a = p(r, s); val b = p(r + 1, s); val c = p(r + 1, s + 1); val d = p(r, s + 1)
            if (r != 0) triangle(a[0], a[1], a[2], b[0], b[1], b[2], d[0], d[1], d[2], color)
            if (r != rings - 1) triangle(b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2], color)
        }
    }

    /**
     * Arrow along +X from the origin (proportions of RViz's Pose arrow).
     */
    fun arrow(
        length: Double, color: Int,
        shaftRadius: Double = length * 0.05, headLength: Double = length * 0.23, headRadius: Double = length * 0.1
    ) {
        val shaft = max(0.0, length - headLength)
        withTransform(ROT_Z_TO_X) {
            withTransform(translation(0.0, 0.0, shaft / 2)) { cylinder(shaftRadius, shaftRadius, shaft, color, 12) }
            withTransform(translation(0.0, 0.0, shaft)) { cone(headRadius, headLength, color, 12) }
        }
    }

    /** Arrow between two points. */
    fun arrowBetween(
        x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, color: Int,
        shaftRadius: Double, headRadius: Double, headLength: Double
    ) {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-9) return
        withTransform(alignXTo(x0, y0, z0, dx / len, dy / len, dz / len)) {
            arrow(len, color, shaftRadius, headLength.coerceAtMost(len), headRadius)
        }
    }

    /** X (red), Y (green), Z (blue) axes as cylinders. */
    fun axes(length: Double, radius: Double, alpha: Int = 255) {
        val r = Colors.rgb(255, 0, 0, alpha); val g = Colors.rgb(0, 255, 0, alpha); val b = Colors.rgb(0, 0, 255, alpha)
        withTransform(ROT_Z_TO_X) { withTransform(translation(0.0, 0.0, length / 2)) { cylinder(radius, radius, length, r, 10) } }
        withTransform(ROT_Z_TO_Y) { withTransform(translation(0.0, 0.0, length / 2)) { cylinder(radius, radius, length, g, 10) } }
        withTransform(translation(0.0, 0.0, length / 2)) { cylinder(radius, radius, length, b, 10) }
    }

    /** Axes as lines (cheap, for TF with many frames). */
    fun lineAxes(length: Double) {
        line(0.0, 0.0, 0.0, length, 0.0, 0.0, Colors.rgb(255, 0, 0))
        line(0.0, 0.0, 0.0, 0.0, length, 0.0, Colors.rgb(0, 255, 0))
        line(0.0, 0.0, 0.0, 0.0, 0.0, length, Colors.rgb(0, 128, 255))
    }

    companion object {
        private val LIGHT = run {
            val x = 0.3; val y = 0.5; val z = 0.8
            val l = sqrt(x * x + y * y + z * z)
            doubleArrayOf(x / l, y / l, z / l)
        }
        private val LIGHT_X = LIGHT[0]
        private val LIGHT_Y = LIGHT[1]
        private val LIGHT_Z = LIGHT[2]

        fun translation(x: Double, y: Double, z: Double) = TfMath.identity().also { it[12] = x; it[13] = y; it[14] = z }

        /** Rotation that takes +Z to +X (+90° rotation about Y). */
        val ROT_Z_TO_X: DoubleArray = TfMath.fromTranslationQuaternion(0.0, 0.0, 0.0, 0.0, sin(Math.PI / 4), 0.0, cos(Math.PI / 4))

        /** Rotation that takes +Z to +Y (-90° rotation about X). */
        val ROT_Z_TO_Y: DoubleArray = TfMath.fromTranslationQuaternion(0.0, 0.0, 0.0, -sin(Math.PI / 4), 0.0, 0.0, cos(Math.PI / 4))

        /** Transform with origin at (x,y,z) whose X axis points along the unit direction (dx,dy,dz). */
        fun alignXTo(x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double): DoubleArray {
            // Non-parallel auxiliary axis.
            var ux = 0.0; var uy = 0.0; var uz = 1.0
            if (abs(dz) > 0.9) { ux = 0.0; uy = 1.0; uz = 0.0 }
            // y = up × x, z = x × y
            var yx = uy * dz - uz * dy; var yy = uz * dx - ux * dz; var yz = ux * dy - uy * dx
            val yl = sqrt(yx * yx + yy * yy + yz * yz)
            yx /= yl; yy /= yl; yz /= yl
            val zx = dy * yz - dz * yy; val zy = dz * yx - dx * yz; val zz = dx * yy - dy * yx
            return doubleArrayOf(dx, dy, dz, 0.0, yx, yy, yz, 0.0, zx, zy, zz, 0.0, x, y, z, 1.0)
        }

        /** Pose matrix from a position and a quaternion. */
        fun pose(px: Double, py: Double, pz: Double, qx: Double, qy: Double, qz: Double, qw: Double) =
            TfMath.fromTranslationQuaternion(px, py, pz, qx, qy, qz, qw)

        fun scale(sx: Double, sy: Double, sz: Double) = TfMath.identity().also { it[0] = sx; it[5] = sy; it[10] = sz }
    }
}
