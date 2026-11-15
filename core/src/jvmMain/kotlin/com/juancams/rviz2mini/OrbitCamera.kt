// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * RViz-style orbit camera (Z up). It is the single source of truth for the Filament camera
 * and for projecting labels, so both use exactly the same matrices.
 */
class OrbitCamera {
    var radius = 10.0
    var azimuth = Math.PI / 4.0
    var elevation = Math.PI / 4.0
    val target = doubleArrayOf(0.0, 0.0, 0.0)

    val fovYDegrees = 45.0
    val near = 0.05
    val far = 2000.0

    fun eye(out: DoubleArray = DoubleArray(3)): DoubleArray {
        out[0] = target[0] + radius * cos(elevation) * cos(azimuth)
        out[1] = target[1] + radius * cos(elevation) * sin(azimuth)
        out[2] = target[2] + radius * sin(elevation)
        return out
    }

    fun rotate(dxPx: Float, dyPx: Float) {
        azimuth -= dxPx * ROTATE_SPEED
        elevation = (elevation + dyPx * ROTATE_SPEED).coerceIn(MIN_ELEVATION, MAX_ELEVATION)
    }

    fun zoom(scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        radius = (radius / scaleFactor).coerceIn(MIN_RADIUS, MAX_RADIUS)
    }

    /** Moves the focal point in the screen plane (two-finger drag). */
    fun pan(dxPx: Float, dyPx: Float, viewHeightPx: Int) {
        if (viewHeightPx <= 0) return
        // Meters per pixel in the target plane.
        val metersPerPx = 2.0 * radius * tan(Math.toRadians(fovYDegrees) / 2.0) / viewHeightPx
        val right = doubleArrayOf(-sin(azimuth), cos(azimuth), 0.0)
        val up = doubleArrayOf(
            -sin(elevation) * cos(azimuth), -sin(elevation) * sin(azimuth), cos(elevation)
        )
        for (i in 0 until 3) target[i] += (-dxPx * right[i] + dyPx * up[i]) * metersPerPx
    }

    fun reset() {
        radius = 10.0; azimuth = Math.PI / 4.0; elevation = Math.PI / 4.0
        target.fill(0.0)
    }

    /** View (lookAt, up = +Z) * Projection, column-major, OpenGL convention. */
    fun viewProjection(aspect: Double, out: DoubleArray): DoubleArray {
        val e = eye()
        var fx = target[0] - e[0]; var fy = target[1] - e[1]; var fz = target[2] - e[2]
        var n = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= n; fy /= n; fz /= n
        // s = f x up(0,0,1)
        var sx = fy; var sy = -fx; val sz = 0.0
        n = sqrt(sx * sx + sy * sy)
        sx /= n; sy /= n
        // u = s x f
        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        val view = DoubleArray(16)
        view[0] = sx; view[4] = sy; view[8] = sz
        view[1] = ux; view[5] = uy; view[9] = uz
        view[2] = -fx; view[6] = -fy; view[10] = -fz
        view[12] = -(sx * e[0] + sy * e[1] + sz * e[2])
        view[13] = -(ux * e[0] + uy * e[1] + uz * e[2])
        view[14] = fx * e[0] + fy * e[1] + fz * e[2]
        view[15] = 1.0

        val f = 1.0 / tan(Math.toRadians(fovYDegrees) / 2.0)
        val proj = DoubleArray(16)
        proj[0] = f / aspect
        proj[5] = f
        proj[10] = (far + near) / (near - far)
        proj[11] = -1.0
        proj[14] = 2.0 * far * near / (near - far)

        return TfMath.multiply(proj, view, out)
    }

    /**
     * Projects a world point to screen pixels. Returns false if it is behind the
     * camera or outside the visible volume (with a margin so labels don't "jump").
     */
    fun project(
        vp: DoubleArray, x: Double, y: Double, z: Double,
        widthPx: Float, heightPx: Float, outXY: FloatArray
    ): Boolean {
        val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
        val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
        val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        if (cw <= near) return false
        val ndcX = cx / cw
        val ndcY = cy / cw
        if (ndcX < -1.2 || ndcX > 1.2 || ndcY < -1.2 || ndcY > 1.2) return false
        outXY[0] = ((ndcX * 0.5 + 0.5) * widthPx).toFloat()
        outXY[1] = ((0.5 - ndcY * 0.5) * heightPx).toFloat()
        return true
    }

    companion object {
        const val ROTATE_SPEED = 0.005
        const val MIN_ELEVATION = -Math.PI / 2 + 0.05
        const val MAX_ELEVATION = Math.PI / 2 - 0.05
        const val MIN_RADIUS = 0.2
        const val MAX_RADIUS = 1000.0
    }
}
