// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.QosSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QosSettingsTest {
    @Test
    fun presetValuesOverridesAndReset() {
        var c = DisplayConfig.create(DisplayType.LASER_SCAN)
        assertEquals(QosSettings(5, QosSettings.KEEP_LAST, QosSettings.BEST_EFFORT, QosSettings.VOLATILE), c.qosSettings())
        val key0 = c.qosKey()
        c = c.with(QosSettings.RELIABILITY, QosSettings.RELIABLE).with(QosSettings.DEPTH, "20")
        assertEquals(QosSettings(20, QosSettings.KEEP_LAST, QosSettings.RELIABLE, QosSettings.VOLATILE), c.qosSettings())
        assertNotEquals(key0, c.qosKey())
        // Another preset: manual changes are discarded.
        c = c.withQosPreset(QosPreset.LATCHED)
        assertEquals(QosSettings.of(QosPreset.LATCHED), c.qosSettings())
        // Odd values: the preset's.
        assertEquals(1, c.with(QosSettings.DEPTH, "-3").qosSettings().depth)
        assertEquals(QosSettings.RELIABLE, c.with(QosSettings.RELIABILITY, "xx").qosSettings().reliability)
    }
}
