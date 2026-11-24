// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.geom

import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.rviz.display.cameraInfoTopicFor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CameraMathTest {

    private val info = CameraMath.Intrinsics(160, 120, 100.0, 100.0, 80.0, 60.0)

    @Test
    fun intrinsicsFromCameraInfo() {
        val k = listOf(100.0, 0.0, 80.0, 0.0, 110.0, 60.0, 0.0, 0.0, 1.0)
        assertEquals(CameraMath.Intrinsics(160, 120, 100.0, 110.0, 80.0, 60.0), CameraMath.intrinsics(mapOf("width" to 160L, "height" to 120L, "k" to k, "p" to List(12) { 0.0 })))
        // The P (rectified) takes precedence if present.
        val p = doubleArrayOf(90.0, 0.0, 79.0, 0.0, 0.0, 91.0, 59.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        assertEquals(CameraMath.Intrinsics(160, 120, 90.0, 91.0, 79.0, 59.0), CameraMath.intrinsics(mapOf("width" to 160, "height" to 120, "k" to k, "p" to p)))
        assertNull(CameraMath.intrinsics(mapOf("width" to 160, "height" to 120, "k" to List(9) { 0.0 }, "p" to List(12) { 0.0 })))
        assertEquals(CameraMath.Intrinsics(80, 60, 50.0, 50.0, 40.0, 30.0), CameraMath.scaled(info, 80, 60))
    }

    /** Projects a point of the optical frame with the Filament camera and returns the pixel (u, v). */
    private fun pixel(x: Double, y: Double, z: Double): DoubleArray {
        val camToOptical = CameraMath.FILAMENT_TO_OPTICAL
        // Optical -> Filament camera: the same matrix (it is its own inverse).
        val c = TfMath.multiply(camToOptical, TfMath.identity().also { it[12] = x; it[13] = y; it[14] = z }, DoubleArray(16))
        val p = CameraMath.projection(info, 0.1, 100.0)
        val px = c[12]; val py = c[13]; val pz = c[14]
        val cx = p[0] * px + p[4] * py + p[8] * pz + p[12]
        val cy = p[1] * px + p[5] * py + p[9] * pz + p[13]
        val cw = p[3] * px + p[7] * py + p[11] * pz + p[15]
        val nx = cx / cw; val ny = cy / cw
        return doubleArrayOf((nx + 1) / 2 * info.width, (1 - ny) / 2 * info.height)
    }

    @Test
    fun projectionPutsPointsOnTheirPixels() {
        assertArrayEquals(doubleArrayOf(80.0, 60.0), pixel(0.0, 0.0, 2.0), 1e-9)
        // u = fx·x/z + cx, v = fy·y/z + cy (y pointing down in the optical frame).
        assertArrayEquals(doubleArrayOf(130.0, 60.0), pixel(1.0, 0.0, 2.0), 1e-9)
        assertArrayEquals(doubleArrayOf(80.0, 85.0), pixel(0.0, 0.5, 2.0), 1e-9)
        val corners = CameraMath.imageCorners(info, 1.0)
        assertArrayEquals(floatArrayOf(-0.8f, -0.6f, 1f, 0.8f, -0.6f, 1f, 0.8f, 0.6f, 1f, -0.8f, 0.6f, 1f), corners, 1e-6f)
        assertArrayEquals(doubleArrayOf(160.0, 120.0), pixel(0.8, 0.6, 1.0), 1e-9)
    }

    @Test
    fun viewportInEachCorner() {
        // Filament: origin at the bottom left.
        assertArrayEquals(intArrayOf(10, 10, 500, 375), CameraMath.viewport("Bottom left", 0.5, 1000, 2000, 160, 120, 10))
        assertArrayEquals(intArrayOf(490, 1615, 500, 375), CameraMath.viewport("Top right", 0.5, 1000, 2000, 160, 120, 10))
        // In landscape the height is limited.
        assertArrayEquals(intArrayOf(10, 10, 1040, 780), CameraMath.viewport("Bottom left", 1.0, 2000, 800, 160, 120, 10))
    }

    @Test
    fun depthImagesToPoints() {
        // 4x2 in 16UC1 (mm), little endian: a 0 (does not count) and a far one (beyond the maximum).
        val mm = shortArrayOf(1000, 2000, 0, 3000, 1000, 1000, 1000, 9000)
        val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN); for (v in mm) bb.putShort(v)
        val i = CameraMath.Intrinsics(4, 2, 2.0, 2.0, 2.0, 1.0)
        val px = IntArray(16)
        val pts = CameraMath.depthPoints(bb.array(), "16UC1", 4, 2, 8, false, i, 1, 5.0, px)
        assertEquals(6, pts.size / 3)
        // (u=0, v=0, 1 m): x = (0-2)/2·1 = -1, y = (0-1)/2·1 = -0.5.
        assertArrayEquals(floatArrayOf(-1f, -0.5f, 1f), pts.copyOf(3), 1e-6f)
        assertArrayEquals(floatArrayOf(-1f, -1f, 2f), pts.copyOfRange(3, 6), 1e-6f)
        assertEquals(listOf(0, 0, 1, 0, 3, 0), px.take(6))
        // 32FC1 in meters with NaN, and stepping by 2.
        val f = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        for (v in floatArrayOf(1.5f, Float.NaN, 2.5f, 1f, 1f, 1f, 1f, 1f)) f.putFloat(v)
        val pts2 = CameraMath.depthPoints(f.array(), "32FC1", 4, 2, 16, true, i, 2, 10.0)
        assertEquals(2, pts2.size / 3)
        assertEquals(1.5f, pts2[2], 0f); assertEquals(2.5f, pts2[5], 0f)
        assertEquals(1, CameraMath.strideFor(640, 480, 400_000))
        assertEquals(4, CameraMath.strideFor(640, 480, 20_000))
    }

    @Test
    fun cameraInfoTopics() {
        assertEquals("/camera/camera_info", cameraInfoTopicFor("/camera/image_raw"))
        assertEquals("/camera/color/camera_info", cameraInfoTopicFor("/camera/color/image_raw/compressed"))
        assertEquals("/camera_info", cameraInfoTopicFor("image"))
        assertEquals("/cam/info", cameraInfoTopicFor("/camera/image_raw", "cam/info"))
    }

    @Test
    fun urdfJoints() {
        val m = UrdfParser.parse("""<robot name="r"><link name="a"/><link name="b"/><link name="c"/>
            <joint name="j1" type="revolute"><parent link="a"/><child link="b"/><origin xyz="0 0 1"/><axis xyz="0 0 2"/><limit effort="5" lower="-1" upper="1" velocity="1"/></joint>
            <joint name="j2" type="fixed"><parent link="b"/><child link="c"/></joint>
            <joint name="bad" type="fixed"><parent link="b"/></joint></robot>""")
        assertEquals(listOf("j1", "j2"), m.joints.map { it.name })
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 1.0), m.joints[0].axis, 1e-12)
        assertEquals(5.0, m.joints[0].effortLimit, 0.0)
        assertEquals(1.0, m.joints[0].origin[14], 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0), m.joints[1].axis, 0.0)
        assertEquals(0.0, m.joints[1].effortLimit, 0.0)
    }
}
