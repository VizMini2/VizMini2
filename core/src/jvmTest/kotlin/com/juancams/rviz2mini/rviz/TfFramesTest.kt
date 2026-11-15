// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.rviz.display.TfFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TfFramesTest {
    @Test
    fun topicsDefaultAndCustom() {
        assertTrue(TfFrames.isDefault(TfFrames.topics("")))
        assertTrue(TfFrames.isDefault(TfFrames.topics("/tf_static, tf")))
        assertEquals(listOf("/robot1/tf", "/robot1/tf_static"), TfFrames.topics("robot1/tf,\n /robot1/tf_static"))
        assertTrue(TfFrames.isStatic("/robot1/tf_static"))
        assertFalse(TfFrames.isStatic("/robot1/tf"))
    }

    @Test
    fun hiddenByNameAndPattern() {
        val p = TfFrames.patterns("odom\n/*wheel*\n\n")
        assertEquals(listOf("odom", "*wheel*"), p)
        assertTrue(TfFrames.hidden("odom", p))
        assertTrue(TfFrames.hidden("/odom", p))
        assertTrue(TfFrames.hidden("left_wheel", p))
        assertTrue(TfFrames.hidden("wheel_rr_link", p))
        assertFalse(TfFrames.hidden("odom_combined", p))
        assertFalse(TfFrames.hidden("base_link", p))
        assertTrue(TfFrames.hidden("a.b", TfFrames.patterns("a.*")))
        assertFalse(TfFrames.hidden("axb", TfFrames.patterns("a.b")))
    }
}
