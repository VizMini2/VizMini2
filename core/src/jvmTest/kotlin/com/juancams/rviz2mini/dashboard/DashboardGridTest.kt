// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.dashboard

import com.juancams.rviz2mini.dashboard.DashboardGrid.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DashboardGridTest {

    private fun noOverlaps(items: List<Item>) = items.none { a -> items.any { b -> a.overlaps(b) } }

    @Test
    fun movingOntoAnotherPushesItDownAndCompacts() {
        val a = Item("a", 0, 0, 6, 4)
        val b = Item("b", 6, 0, 6, 4)
        val c = Item("c", 0, 4, 12, 3)
        val out = DashboardGrid.place(listOf(a, b, c), b.copy(x = 0.0))
        assert(noOverlaps(out))
        assertEquals(Item("b", 0, 0, 6, 4), out.first { it.id == "b" })
        assertEquals(4.0, out.first { it.id == "a" }.y, 0.0)
        assertEquals(8.0, out.first { it.id == "c" }.y, 0.0)
    }

    @Test
    fun clampKeepsInsideTheGrid() {
        assertEquals(Item("x", 9, 0, 3, 2), DashboardGrid.clamp(Item("x", 11, -3, 1, 1)))
        assertEquals(12.0, DashboardGrid.clamp(Item("x", 0, 0, 40, 4)).w, 0.0)
    }

    @Test
    fun compactionRemovesGapsAbove() {
        val out = DashboardGrid.compact(listOf(Item("a", 0, 5, 6, 3), Item("b", 6, 9, 6, 3)))
        assertEquals(listOf(0.0, 0.0), out.map { it.y })
    }

    @Test
    fun resizingGrowsAndPushes() {
        val items = listOf(Item("a", 0, 0, 6, 4), Item("b", 6, 0, 6, 4))
        val out = DashboardGrid.place(items, Item("a", 0, 0, 12, 4))
        assert(noOverlaps(out))
        assertEquals(4.0, out.first { it.id == "b" }.y, 0.0)
    }

    @Test
    fun firstFreeFillsHolesAndJsonRoundTrips() {
        val items = listOf(Item("a", 0, 0, 6, 4))
        assertEquals(Item("n", 6, 0, 6, 4), DashboardGrid.firstFree(items, 6, 4, "n"))
        assertEquals(Item("n", 0, 4, 12, 3), DashboardGrid.firstFree(items, 12, 3, "n"))
        assertEquals(items, DashboardGrid.fromJson(DashboardGrid.toJson(items)))
        assertFalse(DashboardGrid.fromJson("garbage").isNotEmpty())
    }

    /** A floating widget stays where it is dropped, on top of others, without pushing them; and goes back to the grid. */
    @Test
    fun floatingWidgetsOverlapWithoutPushing() {
        val rviz = Item("rviz", 0, 0, 12, 8)
        val joy = Item("joy", 0, 8, 6, 6)
        val cam = Item("cam", 6, 8, 6, 6)
        // The joystick floats and sits on top of the RViz: nobody moves and the camera does not move up into its gap.
        var out = DashboardGrid.setFloating(listOf(rviz, joy, cam), "joy", true)
        out = DashboardGrid.place(out, out.first { it.id == "joy" }.copy(x = 6.0, y = 2.0, w = 5.0, h = 5.0))
        assertEquals(Item("joy", 6, 2, 5, 5, floating = true), out.first { it.id == "joy" })
        assertEquals(rviz, out.first { it.id == "rviz" })
        // The camera stays on the right, in its row (there was nothing above it that left).
        assertEquals(8.0, out.first { it.id == "cam" }.y, 0.0)
        assert(noOverlaps(out.filter { !it.floating }))
        // Moving a grid widget does not push the floating one.
        val moved = DashboardGrid.place(out, out.first { it.id == "cam" }.copy(x = 0.0, y = 0.0))
        assertEquals(Item("joy", 6, 2, 5, 5, floating = true), moved.first { it.id == "joy" })
        assertEquals(0.0, moved.first { it.id == "cam" }.y, 0.0)
        assertEquals(6.0, moved.first { it.id == "rviz" }.y, 0.0)
        // Free gaps do not account for floating ones (the one next to the camera is under the joystick), and it is saved.
        assertEquals(Item("n", 6, 0, 6, 3), DashboardGrid.firstFree(moved, 6, 3, "n"))
        assertEquals(moved.toSet(), DashboardGrid.fromJson(DashboardGrid.toJson(moved)).toSet())
        // Back on the grid: it pushes whoever it collides with, like any other.
        val back = DashboardGrid.setFloating(moved, "joy", false)
        assertFalse(back.first { it.id == "joy" }.floating)
        assert(noOverlaps(back))
    }

    @org.junit.Test
    fun freePlacementMovesOnlyTheOneDraggedAndPutsItOnTop() {
        val a = DashboardGrid.Item("a", 0, 0, 6, 6); val b = DashboardGrid.Item("b", 6, 0, 6, 6); val c = DashboardGrid.Item("c", 0, 6, 12, 4)
        val out = DashboardGrid.moveFree(listOf(a, b, c), a.copy(x = 4.0, y = 2.0))
        org.junit.Assert.assertEquals(listOf("b", "c", "a"), out.map { it.id })
        org.junit.Assert.assertEquals(b, out[0]); org.junit.Assert.assertEquals(c, out[1])
        org.junit.Assert.assertEquals(4.0, out[2].x, 0.0)
        org.junit.Assert.assertTrue(DashboardGrid.coversAnother(out, out[2]))
        org.junit.Assert.assertFalse(DashboardGrid.coversAnother(out, out[0]))
    }

    @org.junit.Test
    fun freePlacementIsUnboundedAndExact() {
        val a = DashboardGrid.Item("a", 0, 0, 6, 6)
        val out = DashboardGrid.moveFree(listOf(a), a.copy(x = 20.37, y = 3.5))
        org.junit.Assert.assertEquals(20.37, out[0].x, 1e-9)
        org.junit.Assert.assertEquals(3.5, out[0].y, 1e-9)
        org.junit.Assert.assertEquals(26.37, DashboardGrid.columns(out), 1e-9)
        org.junit.Assert.assertEquals(0.0, DashboardGrid.moveFree(listOf(a), a.copy(x = -4.0)).first().x, 0.0)
        // Round trip through JSON, with decimals; old layouts (integers) are read the same way.
        org.junit.Assert.assertEquals(out, DashboardGrid.fromJson(DashboardGrid.toJson(out)))
        org.junit.Assert.assertEquals(listOf(a), DashboardGrid.fromJson("""[{"id":"a","x":0,"y":0,"w":6,"h":6}]"""))
    }
}
