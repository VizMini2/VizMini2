// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.geom.MeshUris
import com.juancams.rviz2mini.rviz.geom.StlParser
import com.juancams.rviz2mini.rviz.geom.UrdfParser
import com.juancams.rviz2mini.rviz.geom.UrdfShape
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

class UrdfParserTest {

    private val urdf = """<?xml version="1.0"?>
        <robot name="demo">
          <material name="azul"><color rgba="0 0 1 1"/></material>
          <link name="base_link">
            <visual>
              <origin xyz="0 0 0.1" rpy="0 0 1.5707963"/>
              <geometry><box size="0.6 0.4 0.2"/></geometry>
              <material name="azul"/>
            </visual>
            <collision><geometry><box size="0.7 0.5 0.3"/></geometry></collision>
          </link>
          <link name="wheel">
            <visual>
              <geometry><cylinder radius="0.1" length="0.05"/></geometry>
              <material name="rojo"><color rgba="1 0 0 0.5"/></material>
            </visual>
          </link>
          <link name="head"><visual><geometry><sphere radius="0.2"/></geometry></visual></link>
          <link name="body"><visual><geometry><mesh filename="package://demo/meshes/body.stl" scale="0.001 0.001 0.001"/></geometry></visual></link>
          <link name="empty"/>
          <joint name="j" type="fixed"><parent link="base_link"/><child link="wheel"/></joint>
        </robot>"""

    @Test
    fun parsesLinksShapesAndMaterials() {
        val m = UrdfParser.parse(urdf)
        assertEquals("demo", m.name)
        assertEquals(listOf("base_link", "wheel", "head", "body", "empty"), m.links)
        val base = m.geometries.first { it.link == "base_link" && !it.collision }
        assertEquals(UrdfShape.Box(0.6, 0.4, 0.2), base.shape)
        assertEquals(Colors.rgb(0, 0, 255), base.color)       // material by name
        assertTrue(m.geometries.any { it.link == "base_link" && it.collision })
        val wheel = m.geometries.first { it.link == "wheel" }
        assertEquals(UrdfShape.Cylinder(0.1, 0.05), wheel.shape)
        assertEquals(128, Colors.alpha(wheel.color!!))        // inline material with alpha
        assertEquals(UrdfShape.Sphere(0.2), m.geometries.first { it.link == "head" }.shape)
        assertNull(m.geometries.first { it.link == "head" }.color)
        assertEquals(UrdfShape.Mesh("package://demo/meshes/body.stl", 0.001, 0.001, 0.001), m.geometries.first { it.link == "body" }.shape)
    }

    @Test
    fun originAppliesTranslationAndRpy() {
        val o = UrdfParser.parse(urdf).geometries.first { it.link == "base_link" && !it.collision }.origin
        assertEquals(0.1, o[14], 1e-9)
        // yaw 90°: the visual's X axis points to +Y
        assertEquals(0.0, o[0], 1e-6); assertEquals(1.0, o[1], 1e-6)
    }

    @Test
    fun rpyOrderIsFixedAxesZYX() {
        // roll 90° then yaw 90° (fixed axes): local Z -> -Y... we check against a known reference.
        val m = UrdfParser.poseFromXyzRpy(0.0, 0.0, 0.0, PI / 2, 0.0, PI / 2)
        // Local Z axis (column 2) = Rz(90)·Rx(90)·(0,0,1) = Rz(90)·(0,-1,0) = (1,0,0)
        assertEquals(1.0, m[8], 1e-9); assertEquals(0.0, m[9], 1e-9); assertEquals(0.0, m[10], 1e-9)
    }

    @Test(expected = Exception::class)
    fun rejectsNonUrdf() { UrdfParser.parse("<html/>") }

    @Test(expected = Exception::class)
    fun rejectsDoctypeEntities() {
        UrdfParser.parse("""<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><robot name="&x;"/>""")
    }
}

class StlParserTest {
    @Test
    fun binaryStl() {
        val bb = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(80); bb.putInt(1)
        bb.position(84 + 12)
        for (v in floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)) bb.putFloat(v)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), StlParser.parse(bb.array()), 0f)
    }

    @Test
    fun asciiStl() {
        val text = """solid t
            facet normal 0 0 1
              outer loop
                vertex 0 0 0
                vertex 2 0 0
                vertex 0 2 0.5
              endloop
            endfacet
            endsolid t"""
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0.5f), StlParser.parse(text.toByteArray()), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun garbageIsRejected() { StlParser.parse(ByteArray(10) { 7 }) }
}

class ColladaParserTest {
    private val dae = java.io.File("../tools/test_meshes/rviz2mini_test/meshes/antenna.dae").readBytes()

    @Test
    fun parsesPolylistTrianglesMaterialsAndHierarchy() {
        val m = com.juancams.rviz2mini.rviz.geom.ColladaParser.parse(dae)
        // 6 faces of 4 vertices = 12 triangles + 4 for the tip.
        assertEquals(16, m.triangleCount)
        assertEquals(12, m.colors.count { it == Colors.rgb(255, 0, 0) })
        assertEquals(4, m.colors.count { it == Colors.rgb(0, 255, 0) })
    }

    @Test
    fun unitsAndUpAxisAreConvertedToMetersAndZUp() {
        val t = com.juancams.rviz2mini.rviz.geom.ColladaParser.parse(dae).triangles
        var maxZ = -1f; var maxAbsY = 0f
        for (i in 0 until t.size / 3) { maxZ = maxOf(maxZ, t[i * 3 + 2]); maxAbsY = maxOf(maxAbsY, kotlin.math.abs(t[i * 3 + 1])) }
        // 40 cm shaft + 10 cm tip along the "up" axis (Y in Collada) -> 0.5 m in Z.
        assertEquals(0.5f, maxZ, 1e-4f)
        assertEquals(0.05f, maxAbsY, 1e-4f) // the tip's base measures ±5 cm
    }

    @Test(expected = Exception::class)
    fun rejectsNonCollada() { com.juancams.rviz2mini.rviz.geom.ColladaParser.parse("<robot/>".toByteArray()) }

    @Test
    fun meshUrisParseByExtension() {
        assertEquals(16, MeshUris.parse("package://x/antenna.dae", dae).triangleCount)
        val stl = java.io.File("../tools/test_meshes/rviz2mini_test/meshes/arrow.stl").readBytes()
        assertEquals(4, MeshUris.parse("package://x/arrow.STL", stl).triangleCount)
    }
}

class MeshUrisTest {
    @Test
    fun resolve() {
        assertEquals("http://r:8000/pkg/meshes/a.stl", MeshUris.resolve("package://pkg/meshes/a.stl", "http://r:8000/"))
        assertEquals("http://r:8000/__file__/opt/x.stl", MeshUris.resolve("file:///opt/x.stl", "http://r:8000"))
        assertEquals("https://h/a.stl", MeshUris.resolve("https://h/a.stl", ""))
        assertNull(MeshUris.resolve("package://pkg/a.stl", "  "))
        assertTrue(MeshUris.isSupported("package://p/A.STL"))
        assertTrue(MeshUris.isSupported("package://p/a.dae"))
        assertFalse(MeshUris.isSupported("package://p/a.obj"))
    }
}

class ViewControllerTest {
    private val w = 1080f
    private val h = 1920f
    private val px = FloatArray(2)
    private fun vp(v: ViewController) = v.viewProjection(w.toDouble() / h, DoubleArray(16))

    @Test
    fun targetIsCenteredInEveryView() {
        val v = ViewController()
        v.setFollowPose(3.0, -2.0, 0.5, 0.7)
        for (t in ViewType.entries) {
            v.type = t
            val tg = v.target()
            assertTrue(t.name, v.project(vp(v), tg[0], tg[1], tg[2], w, h, px))
            assertEquals(t.name, w / 2, px[0], 1f); assertEquals(t.name, h / 2, px[1], 1f)
        }
    }

    @Test
    fun topDownIsOrthographicAndScaleMatchesScreen() {
        val v = ViewController().apply { type = ViewType.TOP_DOWN; scale = 10.0 }
        assertTrue(v.isOrtho)
        // 5 m up (+Y with angle = 0) = top edge of the screen, whatever the height z.
        assertTrue(v.project(vp(v), 0.0, 5.0, 0.0, w, h, px)); assertEquals(0f, px[1], 1f)
        assertTrue(v.project(vp(v), 0.0, 5.0, 30.0, w, h, px)); assertEquals(0f, px[1], 1f)
        assertTrue(v.project(vp(v), 1.0, 0.0, 0.0, w, h, px)); assertTrue(px[0] > w / 2) // +X to the right
    }

    @Test
    fun topDownGestures() {
        val v = ViewController().apply { type = ViewType.TOP_DOWN; scale = 10.0 }
        v.drag(-100f, 0f, 1000) // drag to the left: the view moves to the right
        assertEquals(1.0, v.focal[0], 1e-9)
        v.pinch(2f)
        assertEquals(5.0, v.scale, 1e-9)
        v.twist(PI / 2)
        // Rotated 90°: world +X ends up pointing up on screen... or down; what matters: no longer to the right.
        assertTrue(v.project(vp(v), v.focal[0] + 1.0, v.focal[1], 0.0, w, h, px))
        assertEquals(w / 2, px[0], 1f)
    }

    @Test
    fun xyOrbitPanStaysOnGround() {
        val v = ViewController().apply { type = ViewType.XY_ORBIT }
        v.pan(50f, 80f, 1000)
        assertEquals(0.0, v.focal[2], 0.0)
        assertTrue(v.focal[0] != 0.0 || v.focal[1] != 0.0)
    }

    @Test
    fun fpsLooksAndMoves() {
        val v = ViewController().apply { type = ViewType.FPS; position[0] = 0.0; position[1] = 0.0; position[2] = 1.0; fpsYaw = 0.0; fpsPitch = 0.0 }
        // Ahead (+X) in the center.
        assertTrue(v.project(vp(v), 5.0, 0.0, 1.0, w, h, px)); assertEquals(w / 2, px[0], 1f)
        assertFalse(v.project(vp(v), -5.0, 0.0, 1.0, w, h, px)) // behind
        v.pinch(1.5f) // move forward
        assertTrue(v.position[0] > 0.0)
        val yaw0 = v.fpsYaw
        v.drag(100f, 0f, 1000)
        assertTrue(v.fpsYaw < yaw0)
    }

    @Test
    fun followerOrbitsBehindTheFrame() {
        val v = ViewController().apply { type = ViewType.FOLLOW; yaw = PI; pitch = 0.3; distance = 5.0 }
        v.setFollowPose(10.0, 0.0, 0.0, 0.0)
        val e = v.eye()
        assertTrue("La cámara debe estar detrás (x < 10)", e[0] < 10.0)
        v.pan(100f, 100f, 1000) // ignored: the focal point is the frame
        assertArrayEquals(doubleArrayOf(10.0, 0.0, 0.0), v.target(), 1e-9)
        v.setFollowPose(10.0, 0.0, 0.0, PI / 2) // the frame rotates: so does the camera
        assertTrue(v.eye()[1] < 0.0)
    }

    @Test
    fun persistenceRoundTrip() {
        val v = ViewController().apply { type = ViewType.FOLLOW; distance = 7.0; scale = 33.0; followFrame = "laser"; focal[0] = 2.5 }
        val r = ViewController().apply { loadFrom(v.toMap()) }
        assertEquals(ViewType.FOLLOW, r.type); assertEquals(7.0, r.distance, 0.0); assertEquals(33.0, r.scale, 0.0)
        assertEquals("laser", r.followFrame); assertEquals(2.5, r.focal[0], 0.0)
        val broken = ViewController().apply { loadFrom(mapOf("type" to "NOPE", "distance" to "NaN", "scale" to "-5")) }
        assertEquals(ViewType.ORBIT, broken.type)
        assertTrue(broken.distance.isFinite())
        assertEquals(ViewController.MIN_SCALE, broken.scale, 0.0)
    }

    @Test
    fun groundPointIsInverseOfProjectionInEveryView() {
        val v = ViewController()
        v.setFollowPose(1.0, 2.0, 0.0, 0.3)
        val out = DoubleArray(3)
        for (t in ViewType.entries) {
            v.type = t
            if (t == ViewType.FPS) { v.position[2] = 2.0; v.fpsPitch = -0.6 } // looking at the ground
            val vp = vp(v)
            for ((gx, gy) in listOf(0.0 to 0.0, 1.5 to -0.7, -2.0 to 3.0)) {
                if (!v.project(vp, gx, gy, 0.0, w, h, px)) continue
                assertTrue("${t.name}: no corta el suelo", v.groundPoint(px[0], px[1], w, h, out))
                assertEquals("${t.name} x", gx, out[0], 1e-3); assertEquals("${t.name} y", gy, out[1], 1e-3)
            }
        }
    }

    @Test
    fun groundPointAboveHorizonFails() {
        val v = ViewController().apply { type = ViewType.FPS; position[2] = 1.0; fpsPitch = 0.3 }
        assertFalse(v.groundPoint(w / 2, 10f, w, h, DoubleArray(3)))  // looking at the sky
    }

    @Test
    fun resetRestoresDefaults() {
        val v = ViewController()
        v.drag(300f, 200f, 1000); v.pinch(3f); v.pan(50f, 50f, 1000)
        v.reset()
        val d = ViewController()
        assertEquals(d.distance, v.distance, 0.0); assertEquals(d.yaw, v.yaw, 0.0); assertArrayEquals(d.focal, v.focal, 0.0)
    }
}
