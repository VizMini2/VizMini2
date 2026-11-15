// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bag

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackClockTest {
    private val s = 1_000_000_000L

    @Test
    fun messagesAreDueAtTheirOffsetScaledByRate() {
        val c = PlaybackClock(wallNs = 0, bagNs = 100 * s, rate = 2.0)
        assertEquals(5 * s, c.dueWallNs(110 * s))
    }

    @Test
    fun speedingUpDoesNotBurstWhatWasAlreadyPlayed() {
        val c = PlaybackClock(wallNs = 0, bagNs = 0, rate = 1.0)
        // At 10 s it switches to 2x. Previously the 11 s message was due at 11/2 = 5.5 s (already past): it came out at once
        // with all those from 5.5 s to 10 s.
        c.setRate(2.0, nowNs = 10 * s)
        assertEquals(10 * s, c.bagNsAt(10 * s))
        assertEquals(10 * s + s / 2, c.dueWallNs(11 * s))
    }

    @Test
    fun slowingDownDoesNotStall() {
        val c = PlaybackClock(wallNs = 0, bagNs = 0, rate = 2.0)
        // At 10 s of clock (20 s of bag) it switches to 0.5x. Previously the 21 s message was due at 42 s: 32 s stalled.
        c.setRate(0.5, nowNs = 10 * s)
        assertEquals(20 * s, c.bagNsAt(10 * s))
        assertEquals(12 * s, c.dueWallNs(21 * s))
    }

    @Test
    fun resumingAfterAPauseContinuesFromTheNextMessage() {
        val c = PlaybackClock(wallNs = 0, bagNs = 0, rate = 1.0)
        c.anchor(wallNs = 60 * s, bagNs = 5 * s) // 55 s paused with the 5 s message pending
        assertEquals(60 * s, c.dueWallNs(5 * s))
        assertEquals(61 * s, c.dueWallNs(6 * s))
    }

    @Test
    fun sameOrInvalidRateIsIgnored() {
        val c = PlaybackClock(wallNs = 0, bagNs = 0, rate = 1.0)
        c.setRate(1.0, nowNs = 5 * s)
        c.setRate(0.0, nowNs = 5 * s)
        assertEquals(1.0, c.rate, 0.0)
        assertEquals(7 * s, c.dueWallNs(7 * s))
    }
}
