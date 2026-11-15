// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class TfTimeTest {
    private val s = 1_000_000_000L
    private val out = DoubleArray(16)

    private fun TfTree.put(parent: String, child: String, x: Double, yaw: Double = 0.0, stamp: Long, static: Boolean = false, rx: Long = 0) =
        update(parent, child, x, 0.0, 0.0, 0.0, 0.0, sin(yaw / 2), cos(yaw / 2), static, stamp, rx)

    private fun yawOf(m: DoubleArray) = atan2(m[1], m[0])

    @Test
    fun interpolatesTranslationAndRotation() {
        val t = TfTree()
        t.put("odom", "base", 0.0, 0.0, stamp = 1 * s)
        t.put("odom", "base", 10.0, Math.PI / 2, stamp = 2 * s)
        assertTrue(t.lookupInFixed("base", "odom", 1 * s + s / 2, out))
        assertEquals(5.0, out[12], 1e-9)
        assertEquals(Math.PI / 4, yawOf(out), 1e-9)
        assertTrue(t.lookupInFixed("base", "odom", 1 * s + s / 4, out))
        assertEquals(2.5, out[12], 1e-9)
    }

    @Test
    fun outsideHistoryClampsAndZeroMeansLatest() {
        val t = TfTree()
        t.put("odom", "base", 1.0, stamp = 1 * s)
        t.put("odom", "base", 3.0, stamp = 2 * s)
        t.lookupInFixed("base", "odom", s / 2, out); assertEquals(1.0, out[12], 1e-9)
        t.lookupInFixed("base", "odom", 5 * s, out); assertEquals(3.0, out[12], 1e-9)
        t.lookupInFixed("base", "odom", 0, out); assertEquals(3.0, out[12], 1e-9)
    }

    @Test
    fun outOfOrderSamplesAreSorted() {
        val t = TfTree()
        t.put("odom", "base", 0.0, stamp = 1 * s)
        t.put("odom", "base", 20.0, stamp = 3 * s)
        t.put("odom", "base", 10.0, stamp = 2 * s) // arrives late
        t.lookupInFixed("base", "odom", 2 * s + s / 2, out)
        assertEquals(15.0, out[12], 1e-9)
    }

    @Test
    fun historyIsLimitedToTenSeconds() {
        val t = TfTree()
        for (i in 0..30) t.put("odom", "base", i.toDouble(), stamp = i * s)
        // The sample at t=5 s is gone (more than 10 s before the latest, t=30): the oldest one (t=20) is used.
        t.lookupInFixed("base", "odom", 5 * s, out)
        assertEquals(20.0, out[12], 1e-9)
    }

    /** Bag from 1000 s to 1060 s at 10 Hz: on lap `lap` the robot is at x = 100·lap + (t - 1000). */
    private fun TfTree.playLap(lap: Int, untilSec: Double = 1060.0) {
        var t = 1000.0
        while (t <= untilSec + 1e-9) {
            put("map", "odom", 0.0, stamp = (t * s).toLong())
            put("odom", "base", 100.0 * lap + (t - 1000.0), stamp = (t * s).toLong())
            t += 0.1
        }
    }

    @Test
    fun bagLoopClearsTheHistoryAndUsesTheNewLap() {
        val t = TfTree()
        t.playLap(0)
        assertEquals(0, t.timeJumps)
        // It starts over: previously the new samples were discarded (more than 10 s from the 1060 s one of the
        // previous lap) and the data was placed with that lap's 1050 s pose (x = 50).
        t.playLap(1, untilSec = 1000.5)
        assertEquals(1, t.timeJumps)
        assertTrue(t.lookupInFixed("base", "map", (1000.25 * s).toLong(), out))
        assertEquals(100.25, out[12], 1e-6)
    }

    @Test
    fun afterTheJumpBothLapsAreNotMixed() {
        val t = TfTree()
        t.playLap(0)
        t.playLap(1)
        // In 1050–1060 s there were interleaved samples from both laps: interpolation jumped between them.
        for (sec in listOf(1050.05, 1053.0, 1055.55, 1059.95)) {
            assertTrue(t.lookupInFixed("base", "map", (sec * s).toLong(), out))
            assertEquals("t=$sec", 100.0 + (sec - 1000.0), out[12], 1e-6)
        }
        assertEquals("Un salto se cuenta una vez aunque lo vean varios frames", 1, t.timeJumps)
    }

    @Test
    fun simulationResetToZeroIsDetected() {
        val t = TfTree()
        for (i in 1..3000) t.put("odom", "base", i / 10.0, stamp = i * s / 10) // /clock up to 300 s
        t.put("odom", "base", -1.0, stamp = s / 10) // simulation restart
        t.put("odom", "base", -2.0, stamp = 2 * s / 10)
        assertEquals(1, t.timeJumps)
        t.lookupInFixed("base", "odom", 3 * s / 20, out)
        assertEquals(-1.5, out[12], 1e-9)
    }

    @Test
    fun networkReorderingIsNotAJump() {
        val t = TfTree()
        t.put("odom", "base", 0.0, stamp = 10 * s)
        t.put("odom", "base", 20.0, stamp = 12 * s)
        t.put("odom", "base", 10.0, stamp = 11 * s + s / 2) // half a second late
        assertEquals(0, t.timeJumps)
        t.lookupInFixed("base", "odom", 10 * s + 3 * s / 4, out)
        assertEquals(5.0, out[12], 1e-9)
    }

    @Test
    fun clockSkewBetweenMachinesIsNotAJump() {
        val t = TfTree()
        // map -> odom is published by the PC; odom -> base by the robot, with its clock 5 s behind.
        for (i in 0..50) {
            t.put("map", "odom", 1.0, stamp = 100 * s + i * s / 10)
            t.put("odom", "base", i.toDouble(), stamp = 95 * s + i * s / 10)
        }
        assertEquals(0, t.timeJumps)
        t.lookupInFixed("base", "odom", 95 * s + s / 2, out)
        assertEquals(5.0, out[12], 1e-9)
    }

    @Test
    fun forwardJumpsAndStaticFramesDoNotCount() {
        val t = TfTree()
        t.put("odom", "base", 0.0, stamp = 10 * s)
        t.put("odom", "base", 1.0, stamp = 500 * s) // jump forward (advancing bag)
        t.put("base", "laser", 0.2, stamp = 900 * s, static = true)
        t.put("base", "laser", 0.2, stamp = 1 * s, static = true) // static transforms have no history
        assertEquals(0, t.timeJumps)
    }

    @Test
    fun chainAndFixedFrameAreEvaluatedAtTheSameInstant() {
        val t = TfTree()
        t.put("map", "odom", 1.0, stamp = 0, static = true)
        t.put("odom", "base", 0.0, stamp = 1 * s)
        t.put("odom", "base", 2.0, stamp = 3 * s)
        t.put("base", "laser", 0.5, stamp = 0, static = true)
        assertTrue(t.lookupInFixed("laser", "map", 2 * s, out))
        assertEquals(1.0 + 1.0 + 0.5, out[12], 1e-9)
        // Fixed frame = base (which moves): the laser is always 0.5 from it.
        assertTrue(t.lookupInFixed("laser", "base", 2 * s, out))
        assertEquals(0.5, out[12], 1e-9)
        assertTrue(t.lookupInFixed("map", "map", 2 * s, out))
        assertEquals(0.0, out[12], 1e-9)
    }

    @Test
    fun disconnectedOrUnknownFramesFail() {
        val t = TfTree()
        t.put("map", "odom", 1.0, stamp = 0, static = true)
        t.put("cam", "optical", 1.0, stamp = 0, static = true)
        assertFalse(t.lookupInFixed("optical", "map", 0, out))
        assertFalse(t.lookupInFixed("nope", "map", 0, out))
        assertTrue("Sin fixed frame, relativo a su raíz", t.lookupInFixed("optical", "", 0, out))
    }

    @Test
    fun staticFramesIgnoreStampAndNeverGoStale() {
        val t = TfTree()
        t.put("base", "laser", 0.3, stamp = 99 * s, static = true, rx = 1)
        t.lookupInFixed("laser", "base", 1 * s, out); assertEquals(0.3, out[12], 1e-9)
        assertFalse(t.isStale("laser", 1_000 * s, 15 * s))
    }

    @Test
    fun dynamicFramesGoStale() {
        val t = TfTree()
        t.put("odom", "base", 0.0, stamp = 1 * s, rx = 100 * s)
        assertFalse(t.isStale("base", 110 * s, 15 * s))
        assertTrue(t.isStale("base", 116 * s, 15 * s))
        t.put("odom", "base", 0.0, stamp = 2 * s, rx = 116 * s)
        assertFalse(t.isStale("base", 116 * s, 15 * s))
    }

    @Test
    fun slerpTakesShortestPath() {
        // q and -q are the same rotation: interpolating between them must not make a full turn.
        val q = TfMath.slerp(0.0, 0.0, sin(0.2), cos(0.2), 0.0, 0.0, -sin(0.2), -cos(0.2), 0.5)
        assertEquals(0.4, 2 * atan2(q[2], q[3]), 1e-9)
    }
}
