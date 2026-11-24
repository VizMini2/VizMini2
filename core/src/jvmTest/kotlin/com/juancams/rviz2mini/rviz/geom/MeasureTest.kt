// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.geom

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasureTest {
    @Test
    fun distanceAndText() {
        val a = doubleArrayOf(1.0, 2.0, 0.0)
        val b = doubleArrayOf(4.0, -2.0, 0.0)
        assertEquals(5.0, Measure.distance(a, b), 1e-12)
        assertEquals("5.00 m (dx 3.00, dy -4.00)", Measure.text(a, b))
        assertEquals("1.00 m (dx 0.00, dy 0.00, dz 1.00)", Measure.text(a, doubleArrayOf(1.0, 2.0, 1.0)))
        assertEquals("0.00 m (dx 0.00, dy 0.00)", Measure.text(a, a))
    }
}
