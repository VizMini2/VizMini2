// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plot

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotDataTest {

    private fun s(v: Long) = v * 1_000_000_000L

    @Test
    fun ringKeepsTheNewestAndFindsByTime() {
        val b = SeriesBuffer(capacity = 4)
        for (i in 1..6) b.add(s(i.toLong()), i * 10.0)
        assertEquals(4, b.size())
        assertEquals(s(3), b.firstTime())
        assertEquals(s(6), b.lastTime())
        assertNull(b.valueAt(s(2)))
        assertEquals(40.0, b.valueAt(s(4) + 500)!!, 0.0)
        assertEquals(60.0, b.valueAt(s(100))!!, 0.0)
        // The range also includes the previous and the next sample.
        val (t, v) = b.range(s(4) + 1, s(5))
        assertArrayEquals(longArrayOf(s(4), s(5), s(6)), t)
        assertArrayEquals(doubleArrayOf(40.0, 50.0, 60.0), v, 0.0)
        assertEquals(0, SeriesBuffer().range(0, 10).first.size)
    }

    @Test
    fun timeGoingBackwardsStartsOver() {
        val b = SeriesBuffer()
        b.add(s(10), 1.0); b.add(s(11), 2.0)
        b.add(s(1), 3.0)
        assertEquals(1, b.size())
        assertEquals(3.0, b.valueAt(s(1))!!, 0.0)
    }

    @Test
    fun transforms() {
        val d = SeriesFilter(SeriesSpec(transform = SeriesTransform.DERIVATIVE))
        assertNull(d.apply(s(0), 0.0))
        assertEquals(2.0, d.apply(s(1), 2.0)!!, 1e-12)
        assertEquals(-1.0, d.apply(s(3), 0.0)!!, 1e-12)
        assertNull(d.apply(s(3), 5.0)) // same instant: no derivative

        val m = SeriesFilter(SeriesSpec(transform = SeriesTransform.MOVING_AVERAGE, window = 3))
        assertEquals(1.0, m.apply(0, 1.0)!!, 1e-12)
        assertEquals(1.5, m.apply(1, 2.0)!!, 1e-12)
        assertEquals(2.0, m.apply(2, 3.0)!!, 1e-12)
        assertEquals(3.0, m.apply(3, 4.0)!!, 1e-12)

        val k = SeriesFilter(SeriesSpec(scale = 57.29577951308232, offset = 1.0))
        assertEquals(181.0, k.apply(0, Math.PI)!!, 1e-9)
    }

    @Test
    fun xyPairsAndBounds() {
        val x = SeriesBuffer(); val y = SeriesBuffer()
        for (i in 0..4) { x.add(s(i.toLong()), i.toDouble()); y.add(s(i.toLong()), i * 2.0) }
        val (px, py) = PlotMath.xyPairs(x, y, s(1), s(3))
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), px, 0.0)
        assertArrayEquals(doubleArrayOf(2.0, 4.0, 6.0), py, 0.0)
        val (lo, hi) = PlotMath.bounds(listOf(doubleArrayOf(0.0, 10.0, Double.NaN)))!!
        assertTrue(lo < 0 && hi > 10)
        assertNull(PlotMath.bounds(listOf(doubleArrayOf(Double.NaN))))
        val (a, c) = PlotMath.bounds(listOf(doubleArrayOf(5.0, 5.0)))!!
        assertTrue(a < 5 && c > 5)
        assertEquals(0.5, PlotMath.niceStep(0.37), 1e-12)
        assertEquals(2.0, PlotMath.niceStep(2.2), 1e-12)
        assertEquals("0", PlotMath.valueLabel(-1e-12, 5.0))
        assertEquals("0.5", PlotMath.valueLabel(0.5, 0.5))
        assertEquals("-10 s", PlotMath.timeLabel(-10.0, 2.0))
    }

    @Test
    fun csvJoinsSeriesByTime() {
        val a = SeriesBuffer(); val b = SeriesBuffer()
        a.add(s(10), 1.0); a.add(s(12), 2.0)
        b.add(s(11), 5.5)
        val csv = PlotMath.csv(listOf("speed" to a, "a,b" to b)).trim().lines()
        assertEquals("time_s,stamp_s,speed,\"a,b\"", csv[0])
        assertEquals("0.000000,10.000000,1.0,", csv[1])
        assertEquals("1.000000,11.000000,1.0,5.5", csv[2])
        assertEquals("2.000000,12.000000,2.0,5.5", csv[3])
        assertEquals(1, PlotMath.csv(emptyList()).trim().lines().size)
    }

    @Test
    fun colorsAndJson() {
        val a = SeriesSpec(id = "a"); val b = SeriesSpec(id = "b", color = 0); val c = SeriesSpec(id = "c")
        val p = PanelSpec(series = listOf(a, b, c))
        // b uses 0: the automatic ones take 1 and 2.
        assertEquals(PlotSpecs.PALETTE[1], p.colorOf(a))
        assertEquals(PlotSpecs.PALETTE[0], p.colorOf(b))
        assertEquals(PlotSpecs.PALETTE[2], p.colorOf(c))

        val layout = PlotLayout(listOf(PanelSpec(kind = PanelKind.XY, sameScale = true, series = listOf(SeriesSpec(topic = "/odom", type = "nav_msgs/msg/Odometry",
            field = "pose.pose.position.x", transform = SeriesTransform.MOVING_AVERAGE, window = 7, scale = 2.0, offset = -1.0)))), windowS = 60.0)
        assertEquals(layout, PlotLayout.fromJson(layout.toJson()))
        PlotLayout.fromJson("nope").let { assertEquals(1, it.panels.size); assertTrue(it.panels[0].series.isEmpty()); assertEquals(30.0, it.windowS, 0.0) }
        val repaired = PlotLayout.fromJson(JSONObject().put("windowS", 1e9).put("panels", org.json.JSONArray()).toString())
        assertEquals(PlotSpecs.MAX_WINDOW_S, repaired.windowS, 0.0)
        assertEquals(1, repaired.panels.size)
        assertEquals("/odom.x", SeriesSpec(topic = "/odom", field = "x").displayLabel)
    }
}
