// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TfMathTest {

    private val eps = 1e-9

    /** Quaternion of a rotation of `angle` rad around the unit axis (ax, ay, az). */
    private fun quat(ax: Double, ay: Double, az: Double, angle: Double): DoubleArray {
        val s = sin(angle / 2)
        return doubleArrayOf(ax * s, ay * s, az * s, cos(angle / 2))
    }

    private fun tf(tx: Double, ty: Double, tz: Double, q: DoubleArray) =
        TfMath.fromTranslationQuaternion(tx, ty, tz, q[0], q[1], q[2], q[3])

    private fun apply(m: DoubleArray, x: Double, y: Double, z: Double) = doubleArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14]
    )

    @Test
    fun identityQuaternionGivesPureTranslation() {
        val m = tf(1.0, 2.0, 3.0, doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        val expected = TfMath.identity().also { it[12] = 1.0; it[13] = 2.0; it[14] = 3.0 }
        assertArrayEquals(expected, m, eps)
    }

    @Test
    fun yaw90MapsXToY() {
        val m = tf(0.0, 0.0, 0.0, quat(0.0, 0.0, 1.0, Math.PI / 2))
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 0.0), apply(m, 1.0, 0.0, 0.0), eps)
        assertArrayEquals(doubleArrayOf(-1.0, 0.0, 0.0), apply(m, 0.0, 1.0, 0.0), eps)
    }

    @Test
    fun pitch90MapsXToMinusZ() {
        val m = tf(0.0, 0.0, 0.0, quat(0.0, 1.0, 0.0, Math.PI / 2))
        assertArrayEquals(doubleArrayOf(0.0, 0.0, -1.0), apply(m, 1.0, 0.0, 0.0), eps)
    }

    @Test
    fun roll90MapsYToZ() {
        val m = tf(0.0, 0.0, 0.0, quat(1.0, 0.0, 0.0, Math.PI / 2))
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 1.0), apply(m, 0.0, 1.0, 0.0), eps)
    }

    @Test
    fun rotationIsAppliedBeforeTranslation() {
        // T = [R|t]: the point is rotated in the child frame and then translated to the parent.
        val m = tf(10.0, 0.0, 0.0, quat(0.0, 0.0, 1.0, Math.PI / 2))
        assertArrayEquals(doubleArrayOf(10.0, 1.0, 0.0), apply(m, 1.0, 0.0, 0.0), eps)
    }

    @Test
    fun nonUnitQuaternionIsNormalized() {
        val unit = tf(0.0, 0.0, 0.0, quat(0.0, 0.0, 1.0, 0.7))
        val q = quat(0.0, 0.0, 1.0, 0.7).map { it * 3.5 }.toDoubleArray()
        assertArrayEquals(unit, tf(0.0, 0.0, 0.0, q), eps)
    }

    @Test
    fun zeroQuaternionFallsBackToIdentityRotation() {
        val m = TfMath.fromTranslationQuaternion(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val expected = TfMath.identity().also { it[12] = 1.0 }
        assertArrayEquals(expected, m, eps)
    }

    @Test
    fun resultingRotationIsOrthonormal() {
        val axisNorm = sqrt(1.0 + 4.0 + 9.0)
        val m = tf(0.0, 0.0, 0.0, quat(1 / axisNorm, 2 / axisNorm, 3 / axisNorm, 1.234))
        for (i in 0 until 3) for (j in 0 until 3) {
            val dot = m[i * 4] * m[j * 4] + m[i * 4 + 1] * m[j * 4 + 1] + m[i * 4 + 2] * m[j * 4 + 2]
            assertEquals(if (i == j) 1.0 else 0.0, dot, eps)
        }
    }

    @Test
    fun multiplyComposesParentThenChild() {
        val parent = tf(1.0, 0.0, 0.0, quat(0.0, 0.0, 1.0, Math.PI / 2))
        val child = tf(2.0, 0.0, 0.0, doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        val global = TfMath.multiply(parent, child, DoubleArray(16))
        // The child is 2 m along the X of the parent, which faces +Y of the world.
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 0.0), apply(global, 0.0, 0.0, 0.0), eps)
    }

    @Test
    fun multiplyAllowsAliasing() {
        val a = tf(1.0, 2.0, 3.0, quat(0.0, 1.0, 0.0, 0.3))
        val b = tf(-1.0, 0.5, 2.0, quat(1.0, 0.0, 0.0, -0.8))
        val expected = TfMath.multiply(a, b, DoubleArray(16))
        val aliased = a.copyOf()
        TfMath.multiply(aliased, b, aliased)
        assertArrayEquals(expected, aliased, eps)
    }

    @Test
    fun invertRigidTimesOriginalIsIdentity() {
        val m = tf(3.0, -2.0, 0.5, quat(0.0, 0.6, 0.8, 2.1))
        val inv = TfMath.invertRigid(m, DoubleArray(16))
        assertArrayEquals(TfMath.identity(), TfMath.multiply(inv, m, DoubleArray(16)), eps)
        assertArrayEquals(TfMath.identity(), TfMath.multiply(m, inv, DoubleArray(16)), eps)
    }

    @Test
    fun invertRigidAllowsAliasing() {
        val m = tf(3.0, -2.0, 0.5, quat(0.0, 0.6, 0.8, 2.1))
        val expected = TfMath.invertRigid(m, DoubleArray(16))
        TfMath.invertRigid(m, m)
        assertArrayEquals(expected, m, eps)
    }

    @Test
    fun doublePrecisionKeepsMillimetersFarFromOrigin() {
        // Typical of UTM maps: with float at 4,000 km the minimum step is ~0.5 m.
        val far = tf(4_000_000.0, 500_000.0, 0.0, doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        val near = tf(4_000_000.001, 500_000.0, 0.0, doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        val rel = TfMath.multiply(TfMath.invertRigid(far, DoubleArray(16)), near, DoubleArray(16))
        assertEquals(0.001, rel[12], 1e-6)
    }
}
