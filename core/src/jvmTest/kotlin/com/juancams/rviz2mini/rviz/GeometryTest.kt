// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.geom.ColorOptions
import com.juancams.rviz2mini.rviz.geom.Covariance
import com.juancams.rviz2mini.rviz.geom.LaserScanData
import com.juancams.rviz2mini.rviz.geom.LaserScanGeometry
import com.juancams.rviz2mini.rviz.geom.MarkerData
import com.juancams.rviz2mini.rviz.geom.MarkerStore
import com.juancams.rviz2mini.rviz.geom.MeshBuilder
import com.juancams.rviz2mini.rviz.geom.OccupancyGridImage
import com.juancams.rviz2mini.rviz.geom.OdometryTrail
import com.juancams.rviz2mini.rviz.geom.PointCloud2Decoder
import com.juancams.rviz2mini.rviz.geom.PointCloudGeometry
import com.juancams.rviz2mini.rviz.geom.PointFieldInfo
import com.juancams.rviz2mini.rviz.geom.PointList
import com.juancams.rviz2mini.rviz.geom.VertexList
import com.juancams.rviz2mini.rviz.geom.yawPose
import com.juancams.rviz2mini.TfMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

private fun VertexList.bounds(): FloatArray {
    val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    for (i in 0 until vertexCount) for (k in 0 until 3) {
        val v = positions[i * 3 + k]
        b[k] = minOf(b[k], v); b[k + 3] = maxOf(b[k + 3], v)
    }
    return b
}

class MeshBuilderTest {
    private val eps = 1e-4f

    @Test
    fun boxHas12TrianglesAndRightBounds() {
        val b = MeshBuilder()
        b.box(2.0, 4.0, 6.0, Colors.rgb(255, 0, 0))
        assertEquals(36, b.triangles.vertexCount)
        val bb = b.triangles.bounds()
        assertEquals(-1f, bb[0], eps); assertEquals(1f, bb[3], eps)
        assertEquals(-2f, bb[1], eps); assertEquals(2f, bb[4], eps)
        assertEquals(-3f, bb[2], eps); assertEquals(3f, bb[5], eps)
    }

    @Test
    fun shadingIsBrightOnTopAndDarkUnderneath() {
        val b = MeshBuilder()
        b.box(1.0, 1.0, 1.0, Colors.rgb(200, 200, 200))
        // The first 6 vertices are the +Z face, the next 6 the -Z face.
        assertTrue(Colors.red(b.triangles.colors[0]) > Colors.red(b.triangles.colors[6]))
        assertEquals(110, Colors.red(b.triangles.colors[6])) // 200 * 0.55
    }

    @Test
    fun transformsAreApplied() {
        val b = MeshBuilder()
        b.withTransform(yawPose(10.0, 0.0, PI / 2)) {
            line(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0)
        }
        assertEquals(10f, b.lines.positions[3], eps) // x of the end point
        assertEquals(1f, b.lines.positions[4], eps)  // rotated 90°: points towards +Y
    }

    @Test
    fun pushPopRestoresTransform() {
        val b = MeshBuilder()
        b.withTransform(MeshBuilder.translation(5.0, 0.0, 0.0)) { }
        b.line(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0)
        assertEquals(0f, b.lines.positions[0], eps)
    }

    @Test
    fun arrowPointsAlongXWithRequestedLength() {
        val b = MeshBuilder()
        b.arrow(2.0, Colors.rgb(255, 25, 0))
        val bb = b.triangles.bounds()
        assertEquals(0f, bb[0], 1e-3f)
        assertEquals(2f, bb[3], 1e-3f)
        assertTrue(bb[4] <= 0.21f && bb[5] <= 0.21f) // head radius = 0.1 * length
    }

    @Test
    fun arrowBetweenTwoPoints() {
        val b = MeshBuilder()
        b.arrowBetween(1.0, 1.0, 1.0, 1.0, 1.0, 4.0, Colors.rgb(0, 255, 0), 0.05, 0.1, 0.5)
        val bb = b.triangles.bounds()
        assertEquals(1f, bb[2], 1e-3f); assertEquals(4f, bb[5], 1e-3f)
        assertEquals(1f, (bb[0] + bb[3]) / 2, 1e-3f)
    }

    @Test
    fun axesHaveRedGreenBlueAlongTheirAxis() {
        val b = MeshBuilder()
        b.axes(1.0, 0.1)
        val bb = b.triangles.bounds()
        assertEquals(1f, bb[3], 1e-3f); assertEquals(1f, bb[4], 1e-3f); assertEquals(1f, bb[5], 1e-3f)
        assertTrue(b.triangles.colors.toArray().any { Colors.red(it) > 0 && Colors.green(it) == 0 })
    }

    @Test
    fun transparentTrianglesGoToTheirOwnList() {
        val b = MeshBuilder()
        b.box(1.0, 1.0, 1.0, Colors.rgb(255, 0, 0, 128))
        assertTrue(b.triangles.isEmpty)
        assertEquals(36, b.transparentTriangles.vertexCount)
    }

    @Test
    fun sphereStaysInsideItsRadius() {
        val b = MeshBuilder()
        b.sphere(1.0, 1.0, 1.0, Colors.rgb(255, 255, 255))
        assertTrue(b.triangles.vertexCount > 100)
        for (i in 0 until b.triangles.vertexCount) {
            val x = b.triangles.positions[i * 3]; val y = b.triangles.positions[i * 3 + 1]; val z = b.triangles.positions[i * 3 + 2]
            assertEquals(1f, sqrt(x * x + y * y + z * z), 1e-4f)
        }
    }
}

class LaserScanGeometryTest {
    private fun scan(ranges: FloatArray, intensities: FloatArray = FloatArray(0)) =
        LaserScanData(-PI.toFloat() / 2, PI.toFloat() / 2, 0.1f, 10f, ranges, intensities)

    @Test
    fun pointsArePolarAndInvalidOnesSkipped() {
        val out = PointList()
        LaserScanGeometry.build(scan(floatArrayOf(1f, Float.NaN, 2f, 50f, 0.05f)), ColorOptions("flat", Colors.rgb(255, 0, 0)), 0.05f, out)
        assertEquals(2, out.count)
        assertEquals(0f, out.positions[0], 1e-5f); assertEquals(-1f, out.positions[1], 1e-5f) // -90°, r=1
        assertEquals(0f, out.positions[3], 1e-5f); assertEquals(2f, out.positions[4], 1e-5f)  // +90°, r=2
        assertEquals(0.05f, out.sizes[0], 0f)
        assertEquals(Colors.rgb(255, 0, 0), out.colors[0])
    }

    @Test
    fun intensityColorsUseFullRange() {
        val out = PointList()
        LaserScanGeometry.build(scan(floatArrayOf(1f, 1f), floatArrayOf(10f, 20f)), ColorOptions("intensity", 0), 0.05f, out)
        assertEquals(Colors.rainbow(0f), out.colors[0])
        assertEquals(Colors.rainbow(1f), out.colors[1])
    }

    @Test
    fun intensityFallsBackToFlatWithoutIntensities() {
        val out = PointList()
        LaserScanGeometry.build(scan(floatArrayOf(1f)), ColorOptions("intensity", Colors.rgb(1, 2, 3)), 0.05f, out)
        assertEquals(Colors.rgb(1, 2, 3), out.colors[0])
    }
}

class PointCloud2DecoderTest {
    private val xyzFields = listOf(PointFieldInfo("x", 0, 7), PointFieldInfo("y", 4, 7), PointFieldInfo("z", 8, 7))

    private fun cloud(points: List<FloatArray>, step: Int = 16, order: ByteOrder = ByteOrder.LITTLE_ENDIAN, extra: (ByteBuffer, Int) -> Unit = { _, _ -> }): ByteArray {
        val bb = ByteBuffer.allocate(points.size * step).order(order)
        points.forEachIndexed { i, p ->
            bb.putFloat(i * step, p[0]); bb.putFloat(i * step + 4, p[1]); bb.putFloat(i * step + 8, p[2])
            extra(bb, i)
        }
        return bb.array()
    }

    @Test
    fun decodesXyzAndSkipsNaN() {
        val data = cloud(listOf(floatArrayOf(1f, 2f, 3f), floatArrayOf(Float.NaN, 0f, 0f), floatArrayOf(-1f, 0f, 5f)))
        val out = PointList()
        val r = PointCloud2Decoder.decode(xyzFields, 3, 1, 16, 48, false, data, data.size, ColorOptions("flat", 7), 0.03f, 1000, out)
        assertNull(r.error)
        assertEquals(3, r.total); assertEquals(2, out.count)
        assertEquals(1f, out.positions[0], 0f); assertEquals(3f, out.positions[2], 0f)
        assertEquals(-1f, out.positions[3], 0f)
        assertEquals(Colors.withAlpha(7, 1f), out.colors[0])
    }

    @Test
    fun bigEndianIsRespected() {
        val data = cloud(listOf(floatArrayOf(1.5f, -2f, 3f)), order = ByteOrder.BIG_ENDIAN)
        val out = PointList()
        PointCloud2Decoder.decode(xyzFields, 1, 1, 16, 16, true, data, data.size, ColorOptions("flat", 0), 0.03f, 1000, out)
        assertEquals(1.5f, out.positions[0], 0f); assertEquals(-2f, out.positions[1], 0f)
    }

    @Test
    fun packedRgbIsUsedInAutoMode() {
        val fields = xyzFields + PointFieldInfo("rgb", 12, 7)
        val data = cloud(listOf(floatArrayOf(0f, 0f, 0f))) { bb, i -> bb.putInt(i * 16 + 12, 0x00FF8010) }
        val out = PointList()
        val r = PointCloud2Decoder.decode(fields, 1, 1, 16, 16, false, data, data.size, ColorOptions("auto", 0), 0.03f, 1000, out)
        assertEquals("rgb", r.colorUsed)
        assertEquals(Colors.rgb(255, 128, 16), out.colors[0])
    }

    @Test
    fun intensityAndZColorModes() {
        val fields = xyzFields + PointFieldInfo("intensity", 12, 7)
        val data = cloud(listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 10f))) { bb, i -> bb.putFloat(i * 16 + 12, (i * 100).toFloat()) }
        val out = PointList()
        assertEquals("intensity", PointCloud2Decoder.decode(fields, 2, 1, 16, 32, false, data, data.size, ColorOptions("auto", 0), 0.03f, 1000, out).colorUsed)
        assertEquals(Colors.rainbow(0f), out.colors[0]); assertEquals(Colors.rainbow(1f), out.colors[1])
        assertEquals("z", PointCloud2Decoder.decode(xyzFields, 2, 1, 16, 32, false, data, data.size, ColorOptions("auto", 0), 0.03f, 1000, out).colorUsed)
        assertEquals(Colors.rainbow(1f), out.colors[1])
    }

    @Test
    fun otherDatatypes() {
        val bb = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0, -2); bb.putShort(2, 65000.toShort()); bb.putInt(4, -7); bb.putDouble(8, 2.25); bb.put(16, 200.toByte())
        val d = bb.array()
        assertEquals(-2.0, PointCloud2Decoder.read(d, 0, PointCloud2Decoder.INT16, false), 0.0)
        assertEquals(65000.0, PointCloud2Decoder.read(d, 2, PointCloud2Decoder.UINT16, false), 0.0)
        assertEquals(-7.0, PointCloud2Decoder.read(d, 4, PointCloud2Decoder.INT32, false), 0.0)
        assertEquals(2.25, PointCloud2Decoder.read(d, 8, PointCloud2Decoder.FLOAT64, false), 0.0)
        assertEquals(200.0, PointCloud2Decoder.read(d, 16, PointCloud2Decoder.UINT8, false), 0.0)
        assertEquals(-56.0, PointCloud2Decoder.read(d, 16, PointCloud2Decoder.INT8, false), 0.0)
        val be = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(0, -3.5).array()
        assertEquals(-3.5, PointCloud2Decoder.read(be, 0, PointCloud2Decoder.FLOAT64, true), 0.0)
    }

    @Test
    fun organizedCloudUsesRowStep() {
        // 2x2 with 8 bytes of padding at the end of each row.
        val bb = ByteBuffer.allocate(2 * 40).order(ByteOrder.LITTLE_ENDIAN)
        for (row in 0 until 2) for (col in 0 until 2) {
            val base = row * 40 + col * 16
            bb.putFloat(base, (row * 2 + col).toFloat()); bb.putFloat(base + 4, 0f); bb.putFloat(base + 8, 0f)
        }
        val out = PointList()
        PointCloud2Decoder.decode(xyzFields, 2, 2, 16, 40, false, bb.array(), 80, ColorOptions("flat", 0), 0.03f, 1000, out)
        assertEquals(listOf(0f, 1f, 2f, 3f), (0 until 4).map { out.positions[it * 3] })
    }

    @Test
    fun decimatesAboveMaxPoints() {
        val data = cloud(List(1000) { floatArrayOf(it.toFloat(), 0f, 0f) })
        val out = PointList()
        val r = PointCloud2Decoder.decode(xyzFields, 1000, 1, 16, 16000, false, data, data.size, ColorOptions("flat", 0), 0.03f, 100, out)
        assertEquals(1000, r.total)
        assertEquals(100, out.count)
    }

    @Test
    fun errorsDoNotThrow() {
        val out = PointList()
        assertNotNull(PointCloud2Decoder.decode(listOf(PointFieldInfo("x", 0, 7)), 1, 1, 16, 16, false, ByteArray(16), 16, ColorOptions("flat", 0), 0.03f, 10, out).error)
        assertNotNull(PointCloud2Decoder.decode(xyzFields, 10, 1, 16, 160, false, ByteArray(16), 16, ColorOptions("flat", 0), 0.03f, 10, out).error)
        assertNull(PointCloud2Decoder.decode(xyzFields, 0, 0, 16, 0, false, ByteArray(0), 0, ColorOptions("flat", 0), 0.03f, 10, out).error)
    }
}

class ColorOptionsTest {
    @Test
    fun manualBoundsOverrideAuto() {
        val auto = ColorOptions("intensity", 0)
        assertEquals(0.0 to 10.0, auto.bounds(0.0, 10.0))
        val manual = ColorOptions("intensity", 0, autoBounds = false, min = 100.0, max = 200.0)
        assertEquals(100.0 to 200.0, manual.bounds(0.0, 10.0))
        assertEquals(Colors.rainbow(0f), manual.scale(50.0, 100.0, 200.0))   // below: saturates
        assertEquals(Colors.rainbow(1f), manual.scale(500.0, 100.0, 200.0))
    }

    @Test
    fun alphaIsApplied() {
        val o = ColorOptions("flat", Colors.rgb(10, 20, 30), alpha = 0.5f)
        assertEquals(128, Colors.alpha(o.flatColor()))
        assertEquals(128, Colors.alpha(o.scale(1.0, 0.0, 2.0)))
    }

    @Test
    fun laserAxisColoring() {
        val out = PointList()
        val scan = LaserScanData(0f, (PI / 2).toFloat(), 0.1f, 10f, floatArrayOf(1f, 3f), FloatArray(0))
        LaserScanGeometry.build(scan, ColorOptions("y", 0), 0.05f, out)
        assertEquals(Colors.rainbow(0f), out.colors[0]) // y = 0
        assertEquals(Colors.rainbow(1f), out.colors[1]) // y = 3
    }

    @Test
    fun cloudAxisColoringAndManualBounds() {
        val fields = listOf(PointFieldInfo("x", 0, 7), PointFieldInfo("y", 4, 7), PointFieldInfo("z", 8, 7))
        val bb = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        bb.putFloat(0, 1f); bb.putFloat(16, 3f)
        val out = PointList()
        PointCloud2Decoder.decode(fields, 2, 1, 16, 32, false, bb.array(), 32, ColorOptions("x", 0, autoBounds = false, min = 0.0, max = 4.0), 0.03f, 100, out)
        assertEquals(Colors.rainbow(0.25f), out.colors[0])
        assertEquals(Colors.rainbow(0.75f), out.colors[1])
    }
}

class SegmentsTest {
    @Test
    fun thickLinesGoToSegmentsAndThinOnesToLines() {
        val b = MeshBuilder()
        b.withTransform(MeshBuilder.translation(1.0, 0.0, 0.0)) {
            thickLine(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, Colors.rgb(255, 0, 0), 0.1)
            thickLine(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, Colors.rgb(0, 255, 0), 0.0)
        }
        assertEquals(1, b.segments.count)
        assertEquals(1f, b.segments.positions[0], 0f)  // transformed
        assertEquals(2f, b.segments.positions[3], 0f)
        assertEquals(0.1f, b.segments.widths[0], 1e-6f)
        assertEquals(2, b.lines.vertexCount)
        assertFalse(b.isEmpty)
    }
}

class PointCloudGeometryTest {
    @Test
    fun usesIntensityChannel() {
        val out = PointList()
        PointCloudGeometry.build(floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f), mapOf("intensity" to floatArrayOf(5f, 15f)), ColorOptions("auto", 0), 0.03f, out)
        assertEquals(2, out.count)
        assertEquals(Colors.rainbow(0f), out.colors[0]); assertEquals(Colors.rainbow(1f), out.colors[1])
    }

    @Test
    fun ignoresChannelsOfWrongLength() {
        val out = PointList()
        PointCloudGeometry.build(floatArrayOf(0f, 0f, 0f), mapOf("intensity" to floatArrayOf(1f, 2f)), ColorOptions("intensity", Colors.rgb(9, 9, 9)), 0.03f, out)
        assertEquals(Colors.rgb(9, 9, 9), out.colors[0])
    }
}

class OccupancyGridImageTest {
    @Test
    fun mapScheme() {
        val rgba = OccupancyGridImage.toRgba(byteArrayOf(0, 100, -1, 50), 2, 2, "map", 1f)
        assertEquals(255, rgba[0].toInt() and 0xFF)            // free: white
        assertEquals(0, rgba[4].toInt() and 0xFF)              // occupied: black
        assertEquals(128, rgba[8].toInt() and 0xFF)            // unknown: gray
        assertEquals(128, rgba[12].toInt() and 0xFF)           // 50%
        assertEquals(255, rgba[3].toInt() and 0xFF)            // alpha
    }

    @Test
    fun costmapScheme() {
        val c = OccupancyGridImage.toRgba(byteArrayOf(0, 100, 99, -1), 4, 1, "costmap", 0.5f)
        assertEquals(0, c[3].toInt() and 0xFF)                 // 0: transparent
        assertEquals(255, c[4].toInt() and 0xFF); assertEquals(255, c[6].toInt() and 0xFF) // lethal magenta
        assertEquals(128, c[7].toInt() and 0xFF)               // alpha 0.5
        assertEquals(0, c[8].toInt() and 0xFF); assertEquals(255, c[9].toInt() and 0xFF)   // inscribed cyan
        assertEquals(0, c[15].toInt() and 0xFF)                // unknown: transparent
    }

    @Test
    fun shortDataIsTreatedAsUnknown() {
        val rgba = OccupancyGridImage.toRgba(byteArrayOf(0), 2, 1, "map", 1f)
        assertEquals(128, rgba[4].toInt() and 0xFF)
    }
}

class OdometryTrailTest {
    private fun p(x: Double, yaw: Double = 0.0) = OdometryTrail.Pose(x, 0.0, 0.0, 0.0, 0.0, kotlin.math.sin(yaw / 2), kotlin.math.cos(yaw / 2))

    @Test
    fun respectsTolerancesAndKeep() {
        val t = OdometryTrail(keep = 3, positionTolerance = 0.1, angleTolerance = 0.1)
        assertTrue(t.add("odom", p(0.0)))
        assertFalse(t.add("odom", p(0.05)))            // has not moved enough
        assertTrue(t.add("odom", p(0.05, yaw = 0.2)))  // but it has rotated
        assertTrue(t.add("odom", p(1.0)))
        assertTrue(t.add("odom", p(2.0)))
        assertEquals(3, t.poses.size)
        assertEquals(2.0, t.poses.last().x, 0.0)
    }

    @Test
    fun changingFrameResets() {
        val t = OdometryTrail(10, 0.1, 0.1)
        t.add("odom", p(0.0)); t.add("odom", p(1.0))
        t.add("map", p(5.0))
        assertEquals(1, t.poses.size)
        assertEquals("map", t.frameId)
    }

    @Test
    fun angleBetweenHandlesQuaternionSign() {
        val a = p(0.0, 0.3)
        val b = OdometryTrail.Pose(0.0, 0.0, 0.0, -a.qx, -a.qy, -a.qz, -a.qw)
        assertEquals(0.0, OdometryTrail.angleBetween(a, b), 1e-6)
        assertEquals(0.3, OdometryTrail.angleBetween(p(0.0), a), 1e-6)
    }
}

class MarkerStoreTest {
    private fun marker(
        id: Int, type: Int = MarkerData.CUBE, action: Int = MarkerData.ADD, ns: String = "a",
        frame: String = "map", points: FloatArray = FloatArray(0), lifetime: Long = 0, text: String = ""
    ) = MarkerData(ns, id, type, action, frame, TfMath.identity(), 1.0, 1.0, 1.0, Colors.rgb(255, 0, 0), points, IntArray(0), text, lifetime)

    @Test
    fun meshResourcesUseTheResolver() {
        val s = MarkerStore()
        val tri = com.juancams.rviz2mini.rviz.geom.MeshData(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(Colors.rgb(0, 255, 0)))
        fun mesh(id: Int, uri: String, color: Int, embedded: Boolean) = MarkerData("m", id, MarkerData.MESH_RESOURCE, MarkerData.ADD, "map",
            TfMath.identity(), 2.0, 2.0, 2.0, color, FloatArray(0), IntArray(0), "", 0, uri, embedded)
        s.apply(mesh(1, "package://p/a.stl", Colors.rgb(255, 0, 0), false), 0)
        s.apply(mesh(2, "package://p/b.stl", 0, true), 0)
        // No resolver: unsupported.
        s.build()
        assertEquals(2, s.unsupported)
        // Only "a" loaded: the other one is missing.
        val built = s.build { uri -> if (uri.endsWith("a.stl")) tri else null }
        assertEquals(0, s.unsupported); assertEquals(1, s.meshesMissing)
        val b = built.getValue("map").first
        assertTrue(b.triangles.vertexCount >= 3)
        // Scaled by scale and with the marker color (shaded: red dominates).
        assertEquals(2f, b.triangles.positions[3], 1e-6f)
        val c0 = b.triangles.colors[0]
        assertTrue(Colors.red(c0) > 0 && Colors.green(c0) == 0)
        // With mesh_use_embedded_materials and zero color, the mesh colors.
        val both = s.build { tri }.getValue("map").first
        assertEquals(0, s.meshesMissing)
        val colors = (0 until both.triangles.vertexCount).map { both.triangles.colors[it] }
        assertTrue(colors.any { Colors.green(it) > 0 && Colors.red(it) == 0 })
    }

    @Test
    fun addModifyDelete() {
        val s = MarkerStore()
        s.apply(marker(1), 0); s.apply(marker(2), 0); s.apply(marker(1, MarkerData.SPHERE), 0)
        assertEquals(2, s.size)
        assertEquals(MarkerData.SPHERE, s.markers().first { it.id == 1 }.type)
        assertTrue(s.apply(marker(1, action = MarkerData.DELETE), 0))
        assertFalse(s.apply(marker(99, action = MarkerData.DELETE), 0))
        assertEquals(1, s.size)
    }

    @Test
    fun deleteAllByNamespace() {
        val s = MarkerStore()
        s.apply(marker(1, ns = "a"), 0); s.apply(marker(1, ns = "b"), 0)
        s.apply(marker(0, action = MarkerData.DELETEALL, ns = "a"), 0)
        assertEquals(listOf("b"), s.markers().map { it.ns })
        s.apply(marker(0, action = MarkerData.DELETEALL, ns = ""), 0)
        assertEquals(0, s.size)
    }

    @Test
    fun lifetimeExpires() {
        val s = MarkerStore()
        s.apply(marker(1, lifetime = 500), 1000)
        s.apply(marker(2), 1000)
        assertFalse(s.expire(1400))
        assertTrue(s.expire(1500))
        assertEquals(listOf(2), s.markers().map { it.id })
    }

    @Test
    fun geometryIsGroupedByFrameAndCountsUnsupported() {
        val s = MarkerStore()
        s.apply(marker(1, MarkerData.CUBE, frame = "map"), 0)
        s.apply(marker(2, MarkerData.LINE_STRIP, frame = "odom", points = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f)), 0)
        s.apply(marker(3, MarkerData.POINTS, frame = "odom", points = floatArrayOf(0f, 0f, 0f, 2f, 2f, 2f)), 0)
        s.apply(marker(4, MarkerData.TEXT_VIEW_FACING, frame = "map", text = "hola"), 0)
        s.apply(marker(5, MarkerData.MESH_RESOURCE), 0)
        val g = s.build()
        assertEquals(setOf("map", "odom"), g.keys)
        assertEquals(36, g["map"]!!.first.triangles.vertexCount)
        assertEquals(listOf("hola"), g["map"]!!.second.map { it.text })
        assertEquals(2, g["odom"]!!.first.segments.count) // 2 segments with thickness scale.x
        assertEquals(2, g["odom"]!!.first.points.count)
        assertEquals(1, s.unsupported)
    }

    @Test
    fun lineListAndTriangleListIgnoreIncompleteTails() {
        val s = MarkerStore()
        s.apply(marker(1, MarkerData.LINE_LIST, points = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f)), 0)
        s.apply(marker(2, MarkerData.TRIANGLE_LIST, points = FloatArray(3 * 4)), 0)
        val b = s.build()["map"]!!.first
        assertEquals(1, b.segments.count)
        assertEquals(2, s.build(useLineWidths = false)["map"]!!.first.lines.vertexCount)
        assertEquals(3, b.triangles.vertexCount + b.transparentTriangles.vertexCount)
    }

    @Test
    fun arrowFromTwoPoints() {
        val s = MarkerStore()
        s.apply(
            MarkerData("a", 1, MarkerData.ARROW, MarkerData.ADD, "map", TfMath.identity(), 0.1, 0.2, 0.0,
                Colors.rgb(0, 255, 0), floatArrayOf(0f, 0f, 0f, 0f, 0f, 2f), IntArray(0), "", 0), 0
        )
        val bb = s.build()["map"]!!.first.triangles.bounds()
        assertEquals(0f, bb[2], 1e-3f); assertEquals(2f, bb[5], 1e-3f)
    }
}

class CovarianceTest {
    @Test
    fun axisAlignedEllipse() {
        val cov = DoubleArray(36).also { it[0] = 4.0; it[7] = 1.0 }
        val (a, b, angle) = Covariance.ellipseXY(cov)!!
        assertEquals(2.0, a, 1e-9); assertEquals(1.0, b, 1e-9); assertEquals(0.0, angle, 1e-9)
    }

    @Test
    fun rotatedEllipse() {
        val cov = DoubleArray(36).also { it[0] = 2.0; it[1] = 1.0; it[6] = 1.0; it[7] = 2.0 }
        val (a, b, angle) = Covariance.ellipseXY(cov)!!
        assertEquals(sqrt(3.0), a, 1e-9); assertEquals(1.0, b, 1e-9); assertEquals(PI / 4, abs(angle), 1e-9)
    }

    @Test
    fun invalidCovariance() {
        assertNull(Covariance.ellipseXY(DoubleArray(36)))
        assertNull(Covariance.ellipseXY(DoubleArray(3)))
    }
}
