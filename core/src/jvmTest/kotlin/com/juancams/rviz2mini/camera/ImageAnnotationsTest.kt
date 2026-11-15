// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAnnotationsTest {

    private fun color(r: Double, g: Double, b: Double, a: Double = 1.0) = mapOf("r" to r, "g" to g, "b" to b, "a" to a)
    private fun pt(x: Double, y: Double) = mapOf("x" to x, "y" to y, "z" to 0.0)

    @Test
    fun imageMarkersAccumulateExpireAndRemove() {
        val s = ImageAnnotationState()
        s.applyImageMarker(mapOf("ns" to "a", "id" to 1, "type" to 0, "action" to 0, "position" to pt(10.0, 20.0), "scale" to 5.0f,
            "outline_color" to color(1.0, 0.0, 0.0), "filled" to 1L, "fill_color" to color(0.0, 0.0, 1.0, 0.5),
            "lifetime" to mapOf("sec" to 1, "nanosec" to 0L), "points" to emptyList<Any>(), "outline_colors" to emptyList<Any>()), 0)
        s.applyImageMarker(mapOf("ns" to "a", "id" to 2, "type" to 3, "action" to 0, "scale" to 2.0f, "outline_color" to color(0.0, 1.0, 0.0),
            "filled" to 0L, "points" to listOf(pt(0.0, 0.0), pt(4.0, 0.0), pt(4.0, 3.0)), "outline_colors" to emptyList<Any>()), 0)
        val shapes = s.shapes(500)
        assertEquals(2, shapes.size)
        val c = shapes[0] as ImageShape.Circle
        assertEquals(10.0, c.x, 0.0); assertEquals(5.0, c.radius, 1e-6)
        assertEquals(0xFFFF0000.toInt(), c.outline)
        assertEquals(0x800000FF.toInt(), c.fill)
        val p = shapes[1] as ImageShape.Points
        assertEquals(ImageShape.Points.Kind.LOOP, p.kind)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 4.0, 0.0, 4.0, 3.0), p.xy, 0.0)
        assertEquals(0, p.fill)
        // The circle expires (1 s) and REMOVE removes the polygon.
        assertEquals(1, s.shapes(1500).size)
        s.applyImageMarker(mapOf("ns" to "a", "id" to 2, "action" to 1), 1600)
        assertTrue(s.shapes(1600).isEmpty())
    }

    @Test
    fun foxgloveAnnotationsReplaceEachOther() {
        val s = ImageAnnotationState()
        val msg = mapOf(
            "circles" to listOf(mapOf("position" to mapOf("x" to 5.0, "y" to 6.0), "diameter" to 10.0, "thickness" to 2.0,
                "fill_color" to color(0.0, 0.0, 0.0, 0.0), "outline_color" to color(1.0, 1.0, 1.0))),
            "points" to listOf(mapOf("type" to 4L, "points" to listOf(mapOf("x" to 0.0, "y" to 0.0), mapOf("x" to 1.0, "y" to 1.0)),
                "outline_color" to color(1.0, 0.0, 0.0), "outline_colors" to emptyList<Any>(), "fill_color" to color(0.0, 0.0, 0.0, 0.0), "thickness" to 3.0)),
            "texts" to listOf(mapOf("position" to mapOf("x" to 1.0, "y" to 2.0), "text" to "person 0.92", "font_size" to 14.0,
                "text_color" to color(1.0, 1.0, 1.0), "background_color" to color(0.0, 0.0, 0.0, 0.5))))
        s.applyFoxglove(msg)
        val shapes = s.shapes(0)
        assertEquals(3, shapes.size)
        assertEquals(5.0, (shapes[0] as ImageShape.Circle).radius, 0.0)
        assertEquals(ImageShape.Points.Kind.LIST, (shapes[1] as ImageShape.Points).kind)
        assertEquals("person 0.92", (shapes[2] as ImageShape.Text).text)
        s.applyFoxglove(mapOf("circles" to emptyList<Any>()))
        assertTrue(s.shapes(0).isEmpty())
    }
}
