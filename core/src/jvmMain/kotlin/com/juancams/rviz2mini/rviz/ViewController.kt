// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.TfMath
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** RViz view types. */
enum class ViewType(val label: String, val description: String) {
    ORBIT("Orbit", "Orbit around a focal point"),
    XY_ORBIT("XYOrbit", "Orbit; the focal point moves on the ground plane"),
    TOP_DOWN("TopDownOrtho", "2D top-down view (orthographic)"),
    FPS("FPS", "First person"),
    FOLLOW("ThirdPersonFollower", "Follows a TF frame");

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: ORBIT
    }
}

/**
 * Camera controller (Z up). Single source of truth for the Filament camera and for
 * projecting labels. No Android: tested on the JVM.
 *
 * Gestures: `drag` (1 finger), `pan` (2 fingers), `pinch` (scale factor), `twist` (2-finger rotation).
 */
class ViewController {
    var type = ViewType.ORBIT

    // Orbit / XYOrbit / Follow
    var distance = 10.0
    var yaw = Math.PI / 4.0       // azimuth around Z
    var pitch = Math.PI / 4.0     // elevation
    val focal = doubleArrayOf(0.0, 0.0, 0.0)
    // TopDownOrtho
    var scale = 20.0              // vertically visible meters
    var angle = 0.0               // rotation around Z
    // FPS
    val position = doubleArrayOf(-6.0, -6.0, 3.0)
    var fpsYaw = Math.PI / 4.0
    var fpsPitch = -0.35
    // Follow
    var followFrame = "base_link"
    /** Pose of the followed frame (position + yaw), updated every frame. */
    private val followPos = doubleArrayOf(0.0, 0.0, 0.0)
    private var followYaw = 0.0

    val fovYDegrees = 45.0
    val isOrtho: Boolean get() = type == ViewType.TOP_DOWN
    val near: Double get() = if (type == ViewType.FPS) 0.01 else 0.05
    val far = 2000.0

    /**
     * Size factor for the tool markers: 1 at about 10 m distance (or 20 m
     * visible in top-down view), so they look the same at any zoom.
     */
    fun toolScale(): Double = when (type) {
        ViewType.TOP_DOWN -> scale / 20.0
        ViewType.FPS -> 0.5
        else -> distance / 10.0
    }.coerceIn(0.05, 50.0)

    fun reset() {
        distance = 10.0; yaw = Math.PI / 4; pitch = Math.PI / 4; focal.fill(0.0)
        scale = 20.0; angle = 0.0
        position[0] = -6.0; position[1] = -6.0; position[2] = 3.0; fpsYaw = Math.PI / 4; fpsPitch = -0.35
    }

    /** Pose of the followed frame (for ThirdPersonFollower). */
    fun setFollowPose(x: Double, y: Double, z: Double, frameYaw: Double) {
        followPos[0] = x; followPos[1] = y; followPos[2] = z; followYaw = frameYaw
    }

    // ------------------------------------------------------------------ camera position

    /** Effective focal point. */
    fun target(): DoubleArray = when (type) {
        ViewType.FOLLOW -> followPos.copyOf()
        ViewType.TOP_DOWN -> doubleArrayOf(focal[0], focal[1], 0.0)
        ViewType.FPS -> doubleArrayOf(
            position[0] + cos(fpsPitch) * cos(fpsYaw), position[1] + cos(fpsPitch) * sin(fpsYaw), position[2] + sin(fpsPitch)
        )
        else -> focal.copyOf()
    }

    fun eye(): DoubleArray = when (type) {
        ViewType.FPS -> position.copyOf()
        ViewType.TOP_DOWN -> doubleArrayOf(focal[0], focal[1], 100.0)
        else -> {
            val t = target()
            val az = if (type == ViewType.FOLLOW) followYaw + yaw else yaw
            doubleArrayOf(
                t[0] + distance * cos(pitch) * cos(az),
                t[1] + distance * cos(pitch) * sin(az),
                t[2] + distance * sin(pitch)
            )
        }
    }

    /** Camera "up" vector (in top-down view, rotated by `angle`). */
    fun up(): DoubleArray = if (type == ViewType.TOP_DOWN) doubleArrayOf(-sin(angle), cos(angle), 0.0) else doubleArrayOf(0.0, 0.0, 1.0)

    /** Half-height and half-width of the orthographic projection (m). */
    fun orthoHalfExtents(aspect: Double): Pair<Double, Double> = (scale / 2 * aspect) to (scale / 2)

    // ------------------------------------------------------------------ gestures

    fun drag(dxPx: Float, dyPx: Float, viewHeightPx: Int) {
        when (type) {
            ViewType.ORBIT, ViewType.XY_ORBIT, ViewType.FOLLOW -> {
                yaw -= dxPx * ROTATE_SPEED
                pitch = (pitch + dyPx * ROTATE_SPEED).coerceIn(MIN_PITCH, MAX_PITCH)
            }
            ViewType.TOP_DOWN -> panTopDown(dxPx, dyPx, viewHeightPx)
            ViewType.FPS -> {
                fpsYaw -= dxPx * ROTATE_SPEED
                fpsPitch = (fpsPitch - dyPx * ROTATE_SPEED).coerceIn(MIN_PITCH, MAX_PITCH)
            }
        }
    }

    fun pan(dxPx: Float, dyPx: Float, viewHeightPx: Int) {
        if (viewHeightPx <= 0) return
        when (type) {
            ViewType.ORBIT -> {
                val mpp = 2.0 * distance * tan(Math.toRadians(fovYDegrees) / 2.0) / viewHeightPx
                val right = doubleArrayOf(-sin(yaw), cos(yaw), 0.0)
                val upV = doubleArrayOf(-sin(pitch) * cos(yaw), -sin(pitch) * sin(yaw), cos(pitch))
                for (i in 0 until 3) focal[i] += (-dxPx * right[i] + dyPx * upV[i]) * mpp
            }
            ViewType.XY_ORBIT -> {
                // On the ground: camera right and horizontal "forward".
                val mpp = 2.0 * distance * tan(Math.toRadians(fovYDegrees) / 2.0) / viewHeightPx
                val right = doubleArrayOf(-sin(yaw), cos(yaw))
                val forward = doubleArrayOf(-cos(yaw), -sin(yaw))
                focal[0] += (-dxPx * right[0] + dyPx * forward[0]) * mpp
                focal[1] += (-dxPx * right[1] + dyPx * forward[1]) * mpp
                focal[2] = 0.0
            }
            ViewType.TOP_DOWN -> panTopDown(dxPx, dyPx, viewHeightPx)
            ViewType.FPS -> {
                val step = 0.01
                val right = doubleArrayOf(sin(fpsYaw), -cos(fpsYaw))
                position[0] += -dxPx * right[0] * step
                position[1] += -dxPx * right[1] * step
                position[2] += dyPx * step
            }
            ViewType.FOLLOW -> {} // the focal point is the followed frame
        }
    }

    private fun panTopDown(dxPx: Float, dyPx: Float, viewHeightPx: Int) {
        if (viewHeightPx <= 0) return
        val mpp = scale / viewHeightPx
        // Screen axes in the world: right = (cos a, sin a), up = (-sin a, cos a).
        val rx = cos(angle); val ry = sin(angle)
        val ux = -sin(angle); val uy = cos(angle)
        focal[0] += (-dxPx * rx + dyPx * ux) * mpp
        focal[1] += (-dxPx * ry + dyPx * uy) * mpp
    }

    fun pinch(scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        when (type) {
            ViewType.TOP_DOWN -> scale = (scale / scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            ViewType.FPS -> {
                val step = (scaleFactor - 1f) * 3.0
                position[0] += cos(fpsPitch) * cos(fpsYaw) * step
                position[1] += cos(fpsPitch) * sin(fpsYaw) * step
                position[2] += sin(fpsPitch) * step
            }
            else -> distance = (distance / scaleFactor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        }
    }

    /** Two-finger rotation (radians, positive = counterclockwise on screen). */
    fun twist(radians: Double) {
        when (type) {
            ViewType.TOP_DOWN -> angle += radians
            ViewType.ORBIT, ViewType.XY_ORBIT, ViewType.FOLLOW -> yaw += radians
            ViewType.FPS -> fpsYaw += radians
        }
    }

    // ------------------------------------------------------------------ matrices

    /** View * Projection (column-major, OpenGL convention), same as the Filament camera. */
    fun viewProjection(aspect: Double, out: DoubleArray): DoubleArray {
        val e = eye(); val t = target(); val u = up()
        var fx = t[0] - e[0]; var fy = t[1] - e[1]; var fz = t[2] - e[2]
        var n = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= n; fy /= n; fz /= n
        // s = f × up
        var sx = fy * u[2] - fz * u[1]; var sy = fz * u[0] - fx * u[2]; var sz = fx * u[1] - fy * u[0]
        n = sqrt(sx * sx + sy * sy + sz * sz)
        if (n < 1e-9) { sx = 1.0; sy = 0.0; sz = 0.0 } else { sx /= n; sy /= n; sz /= n }
        val ux = sy * fz - sz * fy; val uy = sz * fx - sx * fz; val uz = sx * fy - sy * fx

        val view = DoubleArray(16)
        view[0] = sx; view[4] = sy; view[8] = sz
        view[1] = ux; view[5] = uy; view[9] = uz
        view[2] = -fx; view[6] = -fy; view[10] = -fz
        view[12] = -(sx * e[0] + sy * e[1] + sz * e[2])
        view[13] = -(ux * e[0] + uy * e[1] + uz * e[2])
        view[14] = fx * e[0] + fy * e[1] + fz * e[2]
        view[15] = 1.0

        val proj = DoubleArray(16)
        if (isOrtho) {
            val (hw, hh) = orthoHalfExtents(aspect)
            proj[0] = 1.0 / hw; proj[5] = 1.0 / hh
            proj[10] = -2.0 / (far - near); proj[14] = -(far + near) / (far - near); proj[15] = 1.0
        } else {
            val f = 1.0 / tan(Math.toRadians(fovYDegrees) / 2.0)
            proj[0] = f / aspect; proj[5] = f
            proj[10] = (far + near) / (near - far); proj[11] = -1.0
            proj[14] = 2.0 * far * near / (near - far)
        }
        return TfMath.multiply(proj, view, out)
    }

    /** Projects to pixels. false if it is behind the camera or far off screen. */
    fun project(vp: DoubleArray, x: Double, y: Double, z: Double, widthPx: Float, heightPx: Float, outXY: FloatArray): Boolean {
        val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
        val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
        val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        if (cw <= (if (isOrtho) 0.0 else near)) return false
        val ndcX = cx / cw; val ndcY = cy / cw
        if (ndcX < -1.2 || ndcX > 1.2 || ndcY < -1.2 || ndcY > 1.2) return false
        outXY[0] = ((ndcX * 0.5 + 0.5) * widthPx).toFloat()
        outXY[1] = ((0.5 - ndcY * 0.5) * heightPx).toFloat()
        return true
    }

    /**
     * Ground point (z = `groundZ` in the fixed frame) under a screen point, for the
     * "2D Goal Pose" / "2D Pose Estimate" tools. false if the ray does not hit the ground.
     */
    fun groundPoint(sx: Float, sy: Float, widthPx: Float, heightPx: Float, out: DoubleArray, groundZ: Double = 0.0): Boolean {
        if (widthPx <= 0f || heightPx <= 0f) return false
        val inv = DoubleArray(16)
        if (!TfMath.invertGeneral(viewProjection(widthPx.toDouble() / heightPx, DoubleArray(16)), inv)) return false
        val nx = sx / widthPx * 2.0 - 1.0
        val ny = 1.0 - sy / heightPx * 2.0
        fun unproject(z: Double): DoubleArray {
            val x = inv[0] * nx + inv[4] * ny + inv[8] * z + inv[12]
            val y = inv[1] * nx + inv[5] * ny + inv[9] * z + inv[13]
            val zz = inv[2] * nx + inv[6] * ny + inv[10] * z + inv[14]
            val w = inv[3] * nx + inv[7] * ny + inv[11] * z + inv[15]
            return doubleArrayOf(x / w, y / w, zz / w)
        }
        val a = unproject(-1.0); val b = unproject(1.0)
        val dz = b[2] - a[2]
        if (kotlin.math.abs(dz) < 1e-12) return false
        val t = (groundZ - a[2]) / dz
        if (t < 0.0) return false
        out[0] = a[0] + t * (b[0] - a[0]); out[1] = a[1] + t * (b[1] - a[1]); out[2] = groundZ
        return true
    }

    // ------------------------------------------------------------------ persistence

    fun toMap(): Map<String, String> = mapOf(
        "type" to type.name, "distance" to distance.toString(), "yaw" to yaw.toString(), "pitch" to pitch.toString(),
        "fx" to focal[0].toString(), "fy" to focal[1].toString(), "fz" to focal[2].toString(),
        "scale" to scale.toString(), "angle" to angle.toString(),
        "px" to position[0].toString(), "py" to position[1].toString(), "pz" to position[2].toString(),
        "fpsYaw" to fpsYaw.toString(), "fpsPitch" to fpsPitch.toString(), "follow" to followFrame
    )

    fun loadFrom(m: Map<String, String>) {
        fun d(k: String, def: Double) = m[k]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: def
        type = ViewType.fromName(m["type"])
        distance = d("distance", distance).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        yaw = d("yaw", yaw); pitch = d("pitch", pitch).coerceIn(MIN_PITCH, MAX_PITCH)
        focal[0] = d("fx", 0.0); focal[1] = d("fy", 0.0); focal[2] = d("fz", 0.0)
        scale = d("scale", scale).coerceIn(MIN_SCALE, MAX_SCALE); angle = d("angle", angle)
        position[0] = d("px", position[0]); position[1] = d("py", position[1]); position[2] = d("pz", position[2])
        fpsYaw = d("fpsYaw", fpsYaw); fpsPitch = d("fpsPitch", fpsPitch).coerceIn(MIN_PITCH, MAX_PITCH)
        followFrame = m["follow"]?.takeIf { it.isNotBlank() } ?: followFrame
    }

    companion object {
        const val ROTATE_SPEED = 0.005
        const val MIN_PITCH = -Math.PI / 2 + 0.05
        const val MAX_PITCH = Math.PI / 2 - 0.05
        const val MIN_DISTANCE = 0.2
        const val MAX_DISTANCE = 1000.0
        const val MIN_SCALE = 0.5
        const val MAX_SCALE = 2000.0

        /** Yaw of a pose matrix (rotation around Z). */
        fun yawOf(m: DoubleArray): Double = atan2(m[1], m[0])
    }
}
