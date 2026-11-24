// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.geom

import com.juancams.rviz2mini.TfMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class InteractiveMarkersTest {

    private fun q(x: Double, y: Double, z: Double, w: Double) = mapOf("x" to x, "y" to y, "z" to z, "w" to w)
    private val h = sqrt(0.5)

    @Test
    fun axesFromControlOrientation() {
        // Interactive markers tutorial conventions: (w=1, x=1) -> x axis, (w=1, z=1) -> y, (w=1, y=1) -> z.
        fun axis(qx: Double, qy: Double, qz: Double, qw: Double) =
            InteractiveMarkers.axis(IMControl("c", doubleArrayOf(qx, qy, qz, qw).let { a -> val n = sqrt(a.sumOf { it * it }); a.map { it / n }.toDoubleArray() }, 3, false, emptyList(), ""))
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0), axis(1.0, 0.0, 0.0, 1.0), 1e-9)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 0.0), axis(0.0, 0.0, 1.0, 1.0), 1e-9)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, -1.0), axis(0.0, 1.0, 0.0, 1.0), 1e-9)
        val (u, v) = InteractiveMarkers.perpendiculars(doubleArrayOf(0.0, 0.0, 1.0))
        assertEquals(0.0, u[0] * 0 + u[1] * 0 + u[2] * 1, 1e-12)
        assertEquals(0.0, u[0] * v[0] + u[1] * v[1] + u[2] * v[2], 1e-12)
        assertEquals(1.0, sqrt(v.sumOf { it * it }), 1e-12)
    }

    @Test
    fun dragMath() {
        // The axis appears at 50 px per meter towards the right: 100 px to the right = 2 m; downwards, nothing.
        assertEquals(2.0, InteractiveMarkers.axisDelta(100f, 100f, 150f, 100f, 100f, 0f), 1e-9)
        assertEquals(0.0, InteractiveMarkers.axisDelta(100f, 100f, 150f, 100f, 0f, 80f), 1e-9)
        assertEquals(0.0, InteractiveMarkers.axisDelta(1f, 1f, 1f, 1f, 5f, 5f), 0.0)
        // A counterclockwise quarter turn as seen on screen (from right to up, with y pointing down).
        assertEquals(Math.PI / 2, InteractiveMarkers.rotateDelta(0f, 0f, 10f, 0f, 0f, -10f, true), 1e-6)
        assertEquals(-Math.PI / 2, InteractiveMarkers.rotateDelta(0f, 0f, 10f, 0f, 0f, -10f, false), 1e-6)
        val p = TfMath.fromTranslationQuaternion(1.0, 2.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        val r = InteractiveMarkers.rotated(p, doubleArrayOf(0.0, 0.0, 1.0), Math.PI / 2)
        assertEquals(1.0, r[12], 1e-12); assertEquals(2.0, r[13], 1e-12)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, h, h), InteractiveMarkers.quaternion(r), 1e-9)
        val t = InteractiveMarkers.translated(p, doubleArrayOf(0.0, 1.0, 0.0), 3.0)
        assertEquals(5.0, t[13], 1e-12)
    }

    @Test
    fun parsesMarkersControlsAndMenus() {
        val im = InteractiveMarkers.marker(mapOf(
            "header" to mapOf("frame_id" to "map"), "name" to "mover", "description" to "Move me", "scale" to 0.0,
            "pose" to mapOf("position" to mapOf("x" to 1.0, "y" to 0.0, "z" to 0.0), "orientation" to q(0.0, 0.0, 0.0, 0.0)),
            "controls" to listOf(
                mapOf("name" to "move_x", "orientation" to q(h, 0.0, 0.0, h), "interaction_mode" to 3, "markers" to emptyList<Any>()),
                mapOf("name" to "box", "orientation" to q(0.0, 0.0, 0.0, 1.0), "interaction_mode" to 2, "markers" to listOf(mapOf(
                    "type" to 1, "pose" to mapOf("position" to mapOf("x" to 0.0, "y" to 2.0, "z" to 0.0), "orientation" to q(0.0, 0.0, 0.0, 1.0)),
                    "scale" to mapOf("x" to 0.4, "y" to 0.4, "z" to 0.4), "color" to mapOf("r" to 1.0, "g" to 0.5, "b" to 0.0, "a" to 1.0))))),
            "menu_entries" to listOf(mapOf("id" to 1, "parent_id" to 0, "title" to "Go"), mapOf("id" to 2, "parent_id" to 1, "title" to "Fast"),
                mapOf("id" to 3, "parent_id" to 0, "title" to "Stop"))
        ))
        assertEquals("map", im.frame)
        assertEquals(1.0, im.scale, 0.0) // 0 = 1, like RViz
        assertEquals(1.0, im.pose[12], 0.0)
        assertEquals(1.0, im.pose[0], 0.0) // null quaternion = identity
        assertEquals(listOf(3, 2), im.controls.map { it.mode })
        // Default arrows at ±0.75 along the axis; the button is grabbed by its cube.
        val hs = InteractiveMarkers.handles(im, im.controls[0])
        assertArrayEquals(doubleArrayOf(0.75, 0.0, 0.0), hs[0], 1e-9)
        assertArrayEquals(doubleArrayOf(0.0, 2.0, 0.0), InteractiveMarkers.handles(im, im.controls[1])[0], 1e-9)
        val b = MeshBuilder()
        InteractiveMarkers.visuals(im, im.controls[0], b, mutableListOf(), highlight = false)
        assertTrue(!b.isEmpty)
        assertEquals(listOf("Go" to 0, "Fast" to 1, "Stop" to 0), InteractiveMarkers.menuTree(im.menu).map { it.first.title to it.second })
    }
}
