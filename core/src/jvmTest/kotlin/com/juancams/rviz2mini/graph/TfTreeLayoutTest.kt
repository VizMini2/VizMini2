// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TfTreeLayoutTest {

    private val layout = TfTreeLayout()

    private fun robot() = listOf(
        TfEdge("odom", "map"),
        TfEdge("base_link", "odom", ageSec = 0.02),
        TfEdge("base_footprint", "base_link", isStatic = true),
        TfEdge("laser", "base_link", isStatic = true),
        TfEdge("camera_link", "base_link", isStatic = true),
        TfEdge("camera_optical", "camera_link", isStatic = true),
        TfEdge("imu", "base_link", isStatic = true),
        TfEdge("wheel_left", "base_link", ageSec = 7.0),
        TfEdge("wheel_right", "base_link", ageSec = 0.1)
    )

    /** No pair of boxes at the same level overlaps (also checks between cousins). */
    private fun assertNoOverlaps(r: TfTreeLayout.Result) {
        for ((_, row) in r.boxes.groupBy { it.depth }) {
            val sorted = row.sortedBy { it.x }
            for (i in 1 until sorted.size) {
                assertTrue("${sorted[i - 1].id} solapa ${sorted[i].id}", sorted[i - 1].right <= sorted[i].x + 1e-3f)
            }
        }
    }

    @Test
    fun parentsAboveChildrenAndNoOverlaps() {
        val r = layout.layout(robot())
        assertEquals(10, r.boxes.size)
        assertEquals(listOf("map"), r.roots)
        for (b in r.boxes) {
            val p = b.parent ?: continue
            val pb = r.byId.getValue(p)
            assertTrue("$p encima de ${b.id}", pb.bottom < b.y)
            assertEquals(pb.depth + 1, b.depth)
        }
        assertNoOverlaps(r)
        assertEquals(2, r.byId.getValue("base_link").depth)
        assertTrue(r.byId.getValue("camera_link").isStatic)
        assertEquals(7.0, r.byId.getValue("wheel_left").ageSec!!, 1e-9)
        // The content fits in the declared size.
        for (b in r.boxes) assertTrue(b.x >= -1e-3f && b.right <= r.width + 1e-3f && b.bottom <= r.height + 1e-3f)
    }

    @Test
    fun parentCenteredOverChildrenAndSiblingsSorted() {
        val r = layout.layout(robot())
        val kids = r.children.getValue("base_link")
        assertEquals(listOf("base_footprint", "camera_link", "imu", "laser", "wheel_left", "wheel_right"), kids)
        val xs = kids.map { r.byId.getValue(it).x }
        assertEquals(xs.sorted(), xs)
        val first = r.byId.getValue(kids.first()); val last = r.byId.getValue(kids.last())
        assertEquals((first.x + last.right) / 2, r.byId.getValue("base_link").cx, 1f)
    }

    @Test
    fun forestPlacesTreesSideBySide() {
        val r = layout.layout(listOf(TfEdge("a1", "a"), TfEdge("a2", "a"), TfEdge("b1", "b"), TfEdge("lonely", null)))
        assertEquals(listOf("a", "b", "lonely"), r.roots)
        assertNoOverlaps(r)
        assertTrue(r.byId.getValue("a2").right < r.byId.getValue("b1").x)
        assertTrue(r.byId.getValue("b").right < r.byId.getValue("lonely").x)
        assertEquals(0f, r.byId.getValue("lonely").y)
    }

    @Test(timeout = 2000)
    fun cyclesDoNotHang() {
        val r = layout.layout(listOf(
            TfEdge("a", "b"), TfEdge("b", "c"), TfEdge("c", "a"), // isolated cycle
            TfEdge("x", "x"),                                     // self-loop
            TfEdge("d", "root"), TfEdge("e", "d")
        ))
        assertEquals(setOf("a", "b", "c", "x", "d", "e", "root"), r.boxes.map { it.id }.toSet())
        assertNoOverlaps(r)
        assertNull(r.byId.getValue("x").parent)
        // Exactly one root and one chain come out of the cycle.
        assertEquals(1, listOf("a", "b", "c").count { r.byId.getValue(it).parent == null })
    }

    @Test
    fun parentOnlyFrameIsRootAndEmptyInput() {
        val r = layout.layout(listOf(TfEdge("child", "world")))
        assertEquals(listOf("world"), r.roots)
        assertEquals(0, layout.layout(emptyList()).boxes.size)
    }

    @Test
    fun deepAndWideTreeIsFast() {
        val edges = ArrayList<TfEdge>()
        for (i in 0 until 500) edges += TfEdge("chain$i", if (i == 0) "map" else "chain${i - 1}")
        for (i in 0 until 500) edges += TfEdge("leaf$i", "map")
        val t0 = System.nanoTime()
        val r = layout.layout(edges)
        assertTrue((System.nanoTime() - t0) / 1e6 < 1000)
        assertEquals(1001, r.boxes.size)
        assertNoOverlaps(r)
    }
}
