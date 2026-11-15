// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import kotlin.math.sqrt

/**
 * Rigid-transform math in double precision.
 * 4x4 column-major matrices (same layout as OpenGL / Filament): m[col * 4 + row].
 * No Android dependencies so it can be tested on the JVM.
 */
object TfMath {

    fun identity(): DoubleArray = DoubleArray(16).also { setIdentity(it) }

    fun setIdentity(m: DoubleArray) {
        m.fill(0.0)
        m[0] = 1.0; m[5] = 1.0; m[10] = 1.0; m[15] = 1.0
    }

    /**
     * Builds T = [R(q) | t]. The quaternion is normalized (publishers do not always send it
     * as unit length, and a non-unit quaternion adds scale/shear to the matrix).
     * A zero quaternion is treated as identity.
     */
    fun fromTranslationQuaternion(
        tx: Double, ty: Double, tz: Double,
        qx: Double, qy: Double, qz: Double, qw: Double,
        out: DoubleArray = DoubleArray(16)
    ): DoubleArray {
        val norm = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        var x = 0.0; var y = 0.0; var z = 0.0; var w = 1.0
        if (norm > 1e-9 && norm.isFinite()) {
            x = qx / norm; y = qy / norm; z = qz / norm; w = qw / norm
        }
        val xx = x * x; val xy = x * y; val xz = x * z; val xw = x * w
        val yy = y * y; val yz = y * z; val yw = y * w
        val zz = z * z; val zw = z * w

        out[0] = 1 - 2 * (yy + zz); out[1] = 2 * (xy + zw);     out[2] = 2 * (xz - yw);      out[3] = 0.0
        out[4] = 2 * (xy - zw);     out[5] = 1 - 2 * (xx + zz); out[6] = 2 * (yz + xw);      out[7] = 0.0
        out[8] = 2 * (xz + yw);     out[9] = 2 * (yz - xw);     out[10] = 1 - 2 * (xx + yy); out[11] = 0.0
        out[12] = tx;               out[13] = ty;               out[14] = tz;                out[15] = 1.0
        return out
    }

    /** The quaternion (x, y, z, w) of a transform's rotation (columns). */
    fun toQuaternion(m: DoubleArray): DoubleArray {
        val r00 = m[0]; val r10 = m[1]; val r20 = m[2]; val r01 = m[4]; val r11 = m[5]; val r21 = m[6]; val r02 = m[8]; val r12 = m[9]; val r22 = m[10]
        val tr = r00 + r11 + r22
        return if (tr > 0) { val s = sqrt(tr + 1.0) * 2; doubleArrayOf((r21 - r12) / s, (r02 - r20) / s, (r10 - r01) / s, 0.25 * s) }
        else if (r00 > r11 && r00 > r22) { val s = sqrt(1.0 + r00 - r11 - r22) * 2; doubleArrayOf(0.25 * s, (r01 + r10) / s, (r02 + r20) / s, (r21 - r12) / s) }
        else if (r11 > r22) { val s = sqrt(1.0 + r11 - r00 - r22) * 2; doubleArrayOf((r01 + r10) / s, 0.25 * s, (r12 + r21) / s, (r02 - r20) / s) }
        else { val s = sqrt(1.0 + r22 - r00 - r11) * 2; doubleArrayOf((r02 + r20) / s, (r12 + r21) / s, 0.25 * s, (r10 - r01) / s) }
    }

    /** out = a * b. `out` may be the same array as `a` or `b`. */
    fun multiply(a: DoubleArray, b: DoubleArray, out: DoubleArray): DoubleArray {
        val r = if (out === a || out === b) DoubleArray(16) else out
        for (c in 0 until 4) {
            for (row in 0 until 4) {
                r[c * 4 + row] = a[row] * b[c * 4] + a[4 + row] * b[c * 4 + 1] +
                        a[8 + row] * b[c * 4 + 2] + a[12 + row] * b[c * 4 + 3]
            }
        }
        if (r !== out) r.copyInto(out)
        return out
    }

    /** Inverse of a rigid transform: [R^T | -R^T t]. More stable than a generic inverse. */
    fun invertRigid(m: DoubleArray, out: DoubleArray): DoubleArray {
        val r = if (out === m) DoubleArray(16) else out
        for (c in 0 until 3) for (row in 0 until 3) r[c * 4 + row] = m[row * 4 + c]
        r[3] = 0.0; r[7] = 0.0; r[11] = 0.0; r[15] = 1.0
        for (row in 0 until 3) {
            r[12 + row] = -(m[row * 4] * m[12] + m[row * 4 + 1] * m[13] + m[row * 4 + 2] * m[14])
        }
        if (r !== out) r.copyInto(out)
        return out
    }

    /** Slerp between two quaternions (short path). Returns a normalized [x, y, z, w]. */
    fun slerp(ax: Double, ay: Double, az: Double, aw: Double, bx0: Double, by0: Double, bz0: Double, bw0: Double, t: Double): DoubleArray {
        var bx = bx0; var by = by0; var bz = bz0; var bw = bw0
        var dot = ax * bx + ay * by + az * bz + aw * bw
        if (dot < 0) { bx = -bx; by = -by; bz = -bz; bw = -bw; dot = -dot }
        val (s0, s1) = if (dot > 0.9995) (1 - t) to t else {
            val th = kotlin.math.acos(dot.coerceIn(-1.0, 1.0)); val st = kotlin.math.sin(th)
            kotlin.math.sin((1 - t) * th) / st to kotlin.math.sin(t * th) / st
        }
        val x = s0 * ax + s1 * bx; val y = s0 * ay + s1 * by; val z = s0 * az + s1 * bz; val w = s0 * aw + s1 * bw
        val n = sqrt(x * x + y * y + z * z + w * w)
        return doubleArrayOf(x / n, y / n, z / n, w / n)
    }

    /** General 4x4 inverse (cofactors). Returns false if singular. */
    fun invertGeneral(m: DoubleArray, out: DoubleArray): Boolean {
        val inv = DoubleArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (kotlin.math.abs(det) < 1e-18) return false
        for (i in 0 until 16) out[i] = inv[i] / det
        return true
    }

    fun toFloat(m: DoubleArray, out: FloatArray): FloatArray {
        for (i in 0 until 16) out[i] = m[i].toFloat()
        return out
    }
}
