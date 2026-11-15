// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsMathTest {

    private fun msg(lat: Double, lon: Double, status: Long = 0, covType: Long = 2, cov: List<Double> = listOf(4.0, 0.0, 0.0, 0.0, 9.0, 0.0, 0.0, 0.0, 1.0)) =
        mapOf("latitude" to lat, "longitude" to lon, "altitude" to 650.0, "status" to mapOf("status" to status),
            "position_covariance" to cov, "position_covariance_type" to covType)

    @Test
    fun fixesFromMessages() {
        val f = GpsMath.fixOf(msg(40.4, -3.7))!!
        assertEquals(40.4, f.latitude, 0.0)
        assertEquals(3.0, f.accuracyM, 1e-12) // the largest deviation: sqrt(9)
        assertEquals(-1.0, GpsMath.fixOf(msg(40.4, -3.7, covType = 0))!!.accuracyM, 0.0)
        assertNull(GpsMath.fixOf(msg(Double.NaN, -3.7)))
        assertNull(GpsMath.fixOf(msg(91.0, 0.0)))
        assertNull(GpsMath.fixOf(msg(40.4, -3.7, status = -1)))
        assertNull(GpsMath.fixOf(mapOf("latitude" to 1.0)))
    }

    @Test
    fun offsetsAndDistances() {
        val (lat, lon) = GpsMath.offset(40.0, -3.0, 100.0, 50.0)
        assertEquals(111.8, GpsMath.distance(40.0, -3.0, lat, lon), 0.1)
        assertEquals(50.0, GpsMath.distance(40.0, -3.0, lat, -3.0), 0.05)
        assertEquals(0.0, GpsMath.distance(1.0, 2.0, 1.0, 2.0), 0.0)
    }
}
