// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.rviz.geom.OverlayLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayLayoutTest {
    @Test fun cornersAsBefore() {
        // 40 % of a 1000×800 view, 4:3 image: 400×300, 10 px from the edge.
        assertArrayEquals(intArrayOf(10, 10, 400, 300), OverlayLayout.rect("Top left", -1.0, -1.0, 0.4, 1000, 800, 640, 480, 10))
        assertArrayEquals(intArrayOf(590, 490, 400, 300), OverlayLayout.rect("Bottom right", -1.0, -1.0, 0.4, 1000, 800, 640, 480, 10))
    }

    @Test fun freePositionStaysInside() {
        assertArrayEquals(intArrayOf(250, 200, 400, 300), OverlayLayout.rect("Top left", 0.25, 0.25, 0.4, 1000, 800, 640, 480, 10))
        // Dragged past the right and bottom edges: it stops at the edge.
        assertArrayEquals(intArrayOf(600, 500, 400, 300), OverlayLayout.rect("Top left", 0.9, 0.9, 0.4, 1000, 800, 640, 480, 10))
        // Too tall for the view: it shrinks keeping the aspect ratio.
        val tall = OverlayLayout.rect("Top left", -1.0, -1.0, 1.0, 1000, 500, 480, 640, 10)
        assertEquals(480, tall[3]); assertEquals(360, tall[2])
    }

    @Test fun dragAndResize() {
        val r = intArrayOf(10, 10, 400, 300)
        val (x, y) = OverlayLayout.moved(r, 240f, 190f, 1000, 800)
        assertEquals(0.25, x, 1e-9); assertEquals(0.25, y, 1e-9)
        val (cx, cy) = OverlayLayout.moved(r, -100f, 5000f, 1000, 800)
        assertEquals(0.0, cx, 1e-9); assertEquals(500.0 / 800, cy, 1e-9)
        assertEquals(0.5, OverlayLayout.resized(0.4, 100f, 1000), 1e-6)
        assertEquals(OverlayLayout.MIN_SIZE, OverlayLayout.resized(0.4, -900f, 1000), 1e-9)
        assertEquals(0.6, OverlayLayout.resized(0.4, 0f, 1000, scale = 1.5f), 1e-6)
    }
}
