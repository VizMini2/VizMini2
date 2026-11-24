// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesStatsTest {
    @Test
    fun statsInRange() {
        val t = LongArray(11) { it * 100_000_000L } // 10 Hz durante 1 s
        val v = DoubleArray(11) { it.toDouble() }
        val s = SeriesStats.of(t, v, 0, 1_000_000_000L)!!
        assertEquals(11, s.count); assertEquals(0.0, s.min, 0.0); assertEquals(10.0, s.max, 0.0); assertEquals(5.0, s.mean, 1e-9)
        assertEquals(10.0, s.hz, 1e-6)
        assertEquals(3, SeriesStats.of(t, v, 200_000_000L, 400_000_000L)!!.count)
        assertNull(SeriesStats.of(t, v, 5_000_000_000L, 6_000_000_000L))
    }
}
