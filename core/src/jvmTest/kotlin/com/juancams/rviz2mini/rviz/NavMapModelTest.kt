// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.ros.dyn.CdrBytes
import com.juancams.rviz2mini.ros.dyn.DynCodecTest
import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.NavMapModel
import com.juancams.rviz2mini.rviz.rvizfile.RvizImporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The NavMap display's model against messages serialized by rclpy (navmap_ros_interfaces, test resources in navmap/):
 * a 3-cell map (two on the floor, one raised; vertex colors, two surfaces, a stairs link, F32/U8/I16 layers) and a
 * sparse update of its "cost" layer.
 */
class NavMapModelTest {
    private val reg = DynCodecTest.registry
    private fun bytes(name: String) = javaClass.classLoader!!.getResource("navmap/$name")!!.readBytes()
    private fun map() = NavMapModel.parseMap(CdrBytes.deserialize(reg.msg(NavMapModel.MAP_TYPE), bytes("small_map.cdr")))
    private fun sparse() = NavMapModel.parseLayerUpdate(CdrBytes.deserialize(reg.msg(NavMapModel.LAYER_TYPE), bytes("sparse_cost.cdr")))

    @Test
    fun readsWhatRclpyWrites() {
        val m = map()
        assertEquals("map", m.frame)
        assertEquals(5, m.vertices); assertEquals(3, m.cells)
        assertArrayEquals(intArrayOf(0, 1, 2, 0, 2, 3, 1, 4, 2), m.v)
        assertEquals(0.5f, m.xyz[4 * 3 + 2], 0f)
        assertEquals(2, m.surfaces)
        // uint64 above Long.MAX_VALUE: the same bits.
        assertEquals(0xF000000000000001uL.toLong(), m.geometryId)
        assertEquals(listOf("cost", "occupancy", "logodds"), m.layers.keys.toList())
        val cost = m.layers.getValue("cost")
        assertEquals(NavMapModel.F32, cost.type)
        assertTrue(cost.hasUnknown && cost.unknown.isNaN())
        assertEquals(10.0, cost.values[0], 0.0); assertTrue(cost.isUnknown(cost.values[1]))
        assertArrayEquals(doubleArrayOf(0.0, 254.0, 255.0), m.layers.getValue("occupancy").values, 0.0)
        assertTrue(m.layers.getValue("logodds").isLogOdds)
        assertEquals(1, m.links.size); assertEquals(2, m.links[0].type); assertEquals(2, m.links[0].to)
        // Vertex colors: RGBA of vertex 3.
        assertEquals(10, m.rgba!![12].toInt() and 0xFF); assertEquals(128, m.rgba!![15].toInt() and 0xFF)
        // Centroid of the raised cell (vertices 1, 4, 2).
        assertArrayEquals(doubleArrayOf(4.0 / 3, 0.5, 0.5 / 3), m.centroid(2), 1e-6)
        assertTrue("the same message again is the same map", m.sameAs(map()))
    }

    @Test
    fun sparseAndWholeLayerUpdates() {
        val m = map()
        val before = map()
        assertNull(NavMapModel.apply(m, sparse()))
        assertEquals(20.0, m.layers.getValue("cost").values[1], 0.0)
        assertEquals(10.0, m.layers.getValue("cost").values[0], 0.0)
        assertFalse("a patched layer is a different map", m.sameAs(before))

        // A whole layer replaces (or adds) the one with its name; with the wrong size it is rejected.
        val whole = NavMapModel.LayerUpdate(NavMapModel.Layer("slope", NavMapModel.F32, doubleArrayOf(1.0, 2.0, 3.0), false, 0.0, "deg"), IntArray(0), 0)
        assertNull(NavMapModel.apply(m, whole))
        assertTrue("slope" in m.layers)
        val short = NavMapModel.LayerUpdate(NavMapModel.Layer("slope", NavMapModel.F32, doubleArrayOf(1.0), false, 0.0, ""), IntArray(0), 0)
        assertNotNull(NavMapModel.apply(m, short))

        // Another geometry, a layer that does not exist, another type, or values that do not match the indices: ignored.
        val other = NavMapModel.LayerUpdate(sparse().layer, intArrayOf(1), 42)
        assertTrue(NavMapModel.apply(m, other)!!.contains("another geometry"))
        val missing = NavMapModel.LayerUpdate(NavMapModel.Layer("nope", NavMapModel.F32, doubleArrayOf(1.0), false, 0.0, ""), intArrayOf(0), 0)
        assertNotNull(NavMapModel.apply(m, missing))
        val wrongType = NavMapModel.LayerUpdate(NavMapModel.Layer("cost", NavMapModel.U8, doubleArrayOf(1.0), false, 0.0, ""), intArrayOf(0), 0)
        assertNotNull(NavMapModel.apply(m, wrongType))
        val mismatched = NavMapModel.LayerUpdate(NavMapModel.Layer("cost", NavMapModel.F32, doubleArrayOf(1.0, 2.0), false, 0.0, ""), intArrayOf(0), 0)
        assertNotNull(NavMapModel.apply(m, mismatched))
        // Indices out of range are skipped (the rest applies).
        assertNull(NavMapModel.apply(m, NavMapModel.LayerUpdate(NavMapModel.Layer("cost", NavMapModel.F32, doubleArrayOf(5.0, 6.0), false, 0.0, ""), intArrayOf(99, 2), 0)))
        assertEquals(6.0, m.layers.getValue("cost").values[2], 0.0)
    }

    @Test
    fun layerSelection() {
        val m = map()
        assertEquals("cost", NavMapModel.selectLayer(m, "")!!.name)
        assertEquals("occupancy", NavMapModel.selectLayer(m, " occupancy ")!!.name)
        assertNull(NavMapModel.selectLayer(m, "elevation"))
    }

    @Test
    fun colors() {
        val m = map()
        val flat = Colors.rgb(1, 2, 3)
        fun colorizer(layer: String?, scheme: String = "viridis", smooth: Boolean = false) =
            NavMapModel.Colorizer(m, layer?.let { m.layers.getValue(it) }, scheme, flat, 1f, smooth)

        // Viridis over the known values (10..30): the ends, and the unknown cell translucent grey.
        val cost = colorizer("cost")
        assertEquals(10.0, cost.lo, 0.0); assertEquals(30.0, cost.hi, 0.0)
        assertEquals(Colors.rgb(0x44, 0x01, 0x54), cost.cellColor(0))
        assertEquals(Colors.rgb(0xFD, 0xE7, 0x25), cost.cellColor(2))
        assertTrue(cost.cellUnknown(1))
        assertTrue(Colors.alpha(cost.cellColor(1)) < 128)
        // Heat and rainbow as navmap_rviz_plugin: green to red, blue to red.
        assertEquals(Colors.rgb(0, 255, 0), colorizer("cost", "heat").cellColor(0))
        assertEquals(Colors.rgb(255, 0, 0), colorizer("cost", "heat").cellColor(2))
        assertEquals(Colors.rgb(0, 0, 255), colorizer("cost", "rainbow").cellColor(0))
        assertEquals(Colors.rgb(255, 0, 0), colorizer("cost", "rainbow").cellColor(2))
        // Occupancy: free white, lethal black, unknown dark grey; log-odds: -2 white-ish, +3 dark.
        val occ = colorizer("occupancy")
        assertEquals(Colors.rgb(255, 255, 255), occ.cellColor(0))
        assertEquals(Colors.rgb(0, 0, 0), occ.cellColor(1))
        assertEquals(Colors.rgb(63, 63, 63), occ.cellColor(2))
        val lo = colorizer("logodds")
        assertTrue(Colors.red(lo.cellColor(0)) > 200 && Colors.red(lo.cellColor(2)) < 20)
        assertFalse("occupancy is never smoothed", colorizer("occupancy", smooth = true).smooth)
        // No layer: the map's vertex colors, or the flat color with the "flat" scheme.
        assertTrue(colorizer(null).vertexColors)
        assertEquals(Colors.withAlpha(Colors.rgb(10, 30, 50), 128 / 255f), colorizer(null).rgbaColor(3))
        assertFalse(colorizer(null, "flat").vertexColors)
        assertEquals(flat, colorizer(null, "flat").cellColor(0))
    }

    @Test
    fun smoothColorsAverageTheKnownCellsOfEachVertex() {
        val m = map()
        val c = NavMapModel.Colorizer(m, m.layers.getValue("cost"), "viridis", 0, 1f, true)
        assertTrue(c.smooth)
        // Vertex 0: cells 0 (10) and 1 (unknown) -> 10; vertex 2: cells 0, 1, 2 -> (10 + 30) / 2 = 20; vertex 4: only cell 2.
        assertEquals(c.valueColor(10.0), c.vertexColor(0, 0))
        assertEquals(c.valueColor(20.0), c.vertexColor(0, 2))
        assertEquals(c.valueColor(30.0), c.vertexColor(2, 4))
        // Vertex 3 belongs only to the unknown cell: that cell's color.
        assertEquals(c.cellColor(1), c.vertexColor(1, 3))
    }

    @Test
    fun badGeometryIsAnError() {
        val v = CdrBytes.deserialize(reg.msg(NavMapModel.MAP_TYPE), bytes("small_map.cdr"))
        v["navcels_v2"] = listOf(2L, 3L, 9L)
        assertTrue(runCatching { NavMapModel.parseMap(v) }.exceptionOrNull()!!.message!!.contains("out of range"))
        v["navcels_v2"] = listOf(2L, 3L)
        assertTrue(runCatching { NavMapModel.parseMap(v) }.isFailure)
    }

    @Test
    fun importsTheDemosRvizFile() {
        val text = listOf("../tools/navmap_demo.rviz", "tools/navmap_demo.rviz").map(::File).first { it.isFile }.readText()
        val r = RvizImporter.import(text)
        val d = r.displays.single { it.type == DisplayType.NAVMAP }
        assertEquals("/navmap", d.topic)
        assertEquals("cost", d.string("layer"))
        assertEquals("/navmap_layers", d.string("layerTopic"))
        assertTrue(d.bool("drawLinks"))
        assertEquals("map", r.fixedFrame)
        // And back to RViz: the plugin's class and properties (rainbow is one of its schemes; viridis is not).
        val out = com.juancams.rviz2mini.rviz.rvizfile.RvizExporter.export(listOf(d.with("scheme", "rainbow")), "map", 0, null, null, null, null)
        assertTrue(out, out.contains("Class: navmap_rviz_plugin/NavMapDisplay"))
        assertTrue(out, out.contains("Color Scheme: Rainbow"))
        val again = RvizImporter.import(out).displays.single()
        assertEquals(DisplayType.NAVMAP, again.type)
        assertEquals("cost", again.string("layer")); assertEquals("rainbow", again.string("scheme")); assertEquals("/navmap_layers", again.string("layerTopic"))
    }
}
