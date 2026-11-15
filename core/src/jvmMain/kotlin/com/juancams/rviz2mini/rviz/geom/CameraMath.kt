// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.geom

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Camera math (pure Kotlin, for the JVM): the intrinsics of a sensor_msgs/CameraInfo, the
 * projection for Filament, the image corners and the points of a depth image.
 *
 * ROS optical frame: z forward, x to the right, y down. The Filament camera looks
 * towards -z with y up.
 */
object CameraMath {

    /** Intrinsics in pixels of a `width` × `height` image. */
    data class Intrinsics(val width: Int, val height: Int, val fx: Double, val fy: Double, val cx: Double, val cy: Double)

    /**
     * From a CameraInfo (fields `width`, `height`, `k[9]`, `p[12]`): P (rectified image) if it
     * has one, otherwise K. null if they are not valid (uncalibrated camera: all zeros).
     */
    fun intrinsics(msg: Map<String, Any?>): Intrinsics? {
        val w = (msg["width"] as? Number)?.toInt() ?: return null
        val h = (msg["height"] as? Number)?.toInt() ?: return null
        fun arr(key: String): DoubleArray? = when (val v = msg[key]) {
            is DoubleArray -> v
            is List<*> -> v.map { (it as? Number)?.toDouble() ?: 0.0 }.toDoubleArray()
            else -> null
        }
        val p = arr("p"); val k = arr("k")
        val i = when {
            p != null && p.size >= 12 && p[0] > 0 && p[5] > 0 -> Intrinsics(w, h, p[0], p[5], p[2], p[6])
            k != null && k.size >= 9 && k[0] > 0 && k[4] > 0 -> Intrinsics(w, h, k[0], k[4], k[2], k[5])
            else -> return null
        }
        return if (w > 0 && h > 0) i else null
    }

    /** The same camera for an image of another size (e.g. a downscaled depth). */
    fun scaled(i: Intrinsics, width: Int, height: Int): Intrinsics {
        val sx = width.toDouble() / i.width; val sy = height.toDouble() / i.height
        return Intrinsics(width, height, i.fx * sx, i.fy * sy, i.cx * sx, i.cy * sy)
    }

    /**
     * OpenGL projection (column-major, as Filament wants it) that puts each point at the pixel
     * where the camera would see it: u = fx·x/z + cx, v = fy·y/z + cy in the optical frame.
     */
    fun projection(i: Intrinsics, near: Double, far: Double): DoubleArray {
        val m = DoubleArray(16)
        m[0] = 2 * i.fx / i.width
        m[5] = 2 * i.fy / i.height
        m[8] = 1 - 2 * i.cx / i.width
        m[9] = 2 * i.cy / i.height - 1
        m[10] = -(far + near) / (far - near)
        m[11] = -1.0
        m[14] = -2 * far * near / (far - near)
        return m
    }

    /** Converts from the Filament camera (-z forward, y up) to the ROS optical frame (columns). */
    val FILAMENT_TO_OPTICAL: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    /** The pixel (u, v) at distance `depth` in the optical frame. */
    fun unproject(i: Intrinsics, u: Double, v: Double, depth: Double): DoubleArray =
        doubleArrayOf((u - i.cx) / i.fx * depth, (v - i.cy) / i.fy * depth, depth)

    /** Image corners, pixels (0,0), (w,0), (w,h), (0,h), at `depth` meters (optical frame). */
    fun imageCorners(i: Intrinsics, depth: Double): FloatArray {
        val out = FloatArray(12)
        for ((k, uv) in listOf(0.0 to 0.0, i.width.toDouble() to 0.0, i.width.toDouble() to i.height.toDouble(), 0.0 to i.height.toDouble()).withIndex()) {
            val p = unproject(i, uv.first, uv.second, depth)
            out[k * 3] = p[0].toFloat(); out[k * 3 + 1] = p[1].toFloat(); out[k * 3 + 2] = p[2].toFloat()
        }
        return out
    }

    /**
     * Filament viewport (x, y, width, height; origin bottom-left) for a window of
     * `fraction` of the view width, with the image aspect ratio, in a corner.
     */
    fun viewport(corner: String, fraction: Double, surfaceW: Int, surfaceH: Int, imageW: Int, imageH: Int, margin: Int): IntArray {
        var w = (surfaceW * fraction).toInt().coerceAtLeast(1)
        var h = (w.toDouble() * imageH / imageW.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val maxH = (surfaceH - 2 * margin).coerceAtLeast(1)
        if (h > maxH) { w = (w.toDouble() * maxH / h).toInt().coerceAtLeast(1); h = maxH }
        val left = corner.endsWith("left")
        val top = corner.startsWith("Top")
        val x = if (left) margin else surfaceW - w - margin
        val y = if (top) surfaceH - h - margin else margin
        return intArrayOf(x.coerceAtLeast(0), y.coerceAtLeast(0), w, h)
    }

    /** Depth encodings understood by [depthPoints]. */
    fun isDepthEncoding(encoding: String) = encoding.lowercase() in setOf("16uc1", "mono16", "32fc1")

    /**
     * Points (x, y, z in the optical frame) of a depth image: 16UC1/mono16 in mm or 32FC1 in
     * meters. One pixel out of every `stride` is taken on each axis; zeros, NaN and those beyond
     * `maxRange` do not count. `pixels` receives the (u, v) of each point (to color it).
     */
    fun depthPoints(data: ByteArray, encoding: String, width: Int, height: Int, step: Int, bigEndian: Boolean,
                    i: Intrinsics, stride: Int, maxRange: Double, pixels: IntArray? = null): FloatArray {
        val enc = encoding.lowercase()
        val bpp = if (enc == "32fc1") 4 else 2
        val bb = ByteBuffer.wrap(data).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val s = stride.coerceAtLeast(1)
        val cap = ((width + s - 1) / s) * ((height + s - 1) / s)
        val out = FloatArray(cap * 3)
        var n = 0
        var v = 0
        while (v < height) {
            var u = 0
            while (u < width) {
                val off = v * step + u * bpp
                if (off + bpp <= data.size) {
                    val d = if (bpp == 4) bb.getFloat(off).toDouble() else (bb.getShort(off).toInt() and 0xFFFF) / 1000.0
                    if (d > 0 && d.isFinite() && d <= maxRange) {
                        out[n * 3] = ((u - i.cx) / i.fx * d).toFloat()
                        out[n * 3 + 1] = ((v - i.cy) / i.fy * d).toFloat()
                        out[n * 3 + 2] = d.toFloat()
                        if (pixels != null) { pixels[n * 2] = u; pixels[n * 2 + 1] = v }
                        n++
                    }
                }
                u += s
            }
            v += s
        }
        return out.copyOf(n * 3)
    }

    /** Step so as not to exceed `maxPoints` points. */
    fun strideFor(width: Int, height: Int, maxPoints: Int): Int {
        var s = 1
        while ((width / s) * (height / s) > maxPoints) s++
        return s
    }
}
