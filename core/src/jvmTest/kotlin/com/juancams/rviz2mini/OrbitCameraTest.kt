// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitCameraTest {

    private val w = 1080f
    private val h = 1920f
    private val px = FloatArray(2)

    private fun vp(cam: OrbitCamera) = cam.viewProjection(w.toDouble() / h, DoubleArray(16))

    @Test
    fun targetProjectsToScreenCenterForAnyOrientation() {
        val cam = OrbitCamera()
        for (az in listOf(0.0, 0.7, 2.0, -2.5)) for (el in listOf(-1.2, 0.0, 0.5, 1.4)) {
            cam.azimuth = az; cam.elevation = el
            cam.target[0] = 3.0; cam.target[1] = -1.0; cam.target[2] = 0.5
            assertTrue(cam.project(vp(cam), 3.0, -1.0, 0.5, w, h, px))
            assertEquals(w / 2, px[0], 0.5f)
            assertEquals(h / 2, px[1], 0.5f)
        }
    }

    @Test
    fun screenAxesMatchCameraOrientation() {
        // Camera at +X looking at the origin with Z up: world +Y to the right, +Z up.
        val cam = OrbitCamera().apply { azimuth = 0.0; elevation = 0.0; radius = 10.0 }
        val m = vp(cam)
        assertTrue(cam.project(m, 0.0, 1.0, 0.0, w, h, px))
        assertTrue(px[0] > w / 2)
        assertTrue(cam.project(m, 0.0, 0.0, 1.0, w, h, px))
        assertTrue(px[1] < h / 2)
    }

    @Test
    fun pointsBehindCameraAreRejected() {
        val cam = OrbitCamera().apply { azimuth = 0.0; elevation = 0.0; radius = 10.0 }
        assertFalse(cam.project(vp(cam), 20.0, 0.0, 0.0, w, h, px))
    }

    @Test
    fun pointsFarOffScreenAreRejected() {
        val cam = OrbitCamera().apply { azimuth = 0.0; elevation = 0.0; radius = 10.0 }
        assertFalse(cam.project(vp(cam), 0.0, 1000.0, 0.0, w, h, px))
    }

    @Test
    fun projectionMatchesFieldOfView() {
        // A point on the top edge of the vertical FOV must land at y = 0.
        val cam = OrbitCamera().apply { azimuth = 0.0; elevation = 0.0; radius = 10.0 }
        val halfHeight = 10.0 * Math.tan(Math.toRadians(cam.fovYDegrees / 2))
        assertTrue(cam.project(vp(cam), 0.0, 0.0, halfHeight, w, h, px))
        assertEquals(0f, px[1], 0.5f)
    }

    @Test
    fun zoomIsClamped() {
        val cam = OrbitCamera()
        repeat(100) { cam.zoom(2f) }
        assertEquals(OrbitCamera.MIN_RADIUS, cam.radius, 1e-9)
        repeat(100) { cam.zoom(0.5f) }
        assertEquals(OrbitCamera.MAX_RADIUS, cam.radius, 1e-9)
        cam.zoom(0f)
        assertEquals(OrbitCamera.MAX_RADIUS, cam.radius, 1e-9)
    }

    @Test
    fun elevationIsClampedAwayFromPoles() {
        val cam = OrbitCamera()
        cam.rotate(0f, 100_000f)
        assertEquals(OrbitCamera.MAX_ELEVATION, cam.elevation, 1e-9)
        cam.rotate(0f, -100_000f)
        assertEquals(OrbitCamera.MIN_ELEVATION, cam.elevation, 1e-9)
    }

    @Test
    fun panMovesSceneWithTheFinger() {
        val cam = OrbitCamera().apply { azimuth = 0.3; elevation = 0.6; radius = 8.0 }
        val world = doubleArrayOf(0.0, 0.0, 0.0)
        assertTrue(cam.project(vp(cam), world[0], world[1], world[2], w, h, px))
        val before = px.copyOf()
        cam.pan(100f, -50f, h.toInt())
        assertTrue(cam.project(vp(cam), world[0], world[1], world[2], w, h, px))
        // The point must follow the finger (same offset in pixels).
        assertEquals(before[0] + 100f, px[0], 1f)
        assertEquals(before[1] - 50f, px[1], 1f)
    }

    @Test
    fun resetRestoresDefaults() {
        val cam = OrbitCamera()
        cam.rotate(300f, 200f); cam.zoom(3f); cam.pan(50f, 50f, 1000)
        cam.reset()
        val def = OrbitCamera()
        assertEquals(def.radius, cam.radius, 1e-9)
        assertEquals(def.azimuth, cam.azimuth, 1e-9)
        assertEquals(def.elevation, cam.elevation, 1e-9)
        assertEquals(0.0, cam.target[0] + cam.target[1] + cam.target[2], 1e-9)
    }
}
