// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.QosSettings
import com.juancams.rviz2mini.rviz.rvizfile.RvizExporter
import com.juancams.rviz2mini.rviz.rvizfile.RvizImporter
import org.junit.Assert.assertEquals
import org.junit.Test

class RvizExportTest {
    @Test
    fun exportThenImportKeepsDisplaysTopicsQosAndView() {
        val displays = listOf(
            DisplayConfig.create(DisplayType.GRID).with("cellCount", "20.0"),
            DisplayConfig.create(DisplayType.TF).with("showNames", "false"),
            DisplayConfig.create(DisplayType.LASER_SCAN, "Front laser", "/front/scan").with("size", "0.1").with("colorMode", "flat").with("color", "#FF0000")
                .with(QosSettings.DEPTH, "20"),
            DisplayConfig.create(DisplayType.MAP, "Map", "/map").copy(enabled = false),
            DisplayConfig.create(DisplayType.ROBOT_MODEL, "Robot", "/robot_description"),
            DisplayConfig.create(DisplayType.PATH, "Plan: global", "/plan"),
        )
        val view = mapOf("type" to "ORBIT", "distance" to "7.5", "yaw" to "1.0", "pitch" to "0.5", "fx" to "1.0", "fy" to "2.0", "fz" to "0.0")
        val text = RvizExporter.export(displays, "odom", 0xFF303030.toInt(), view, "/goal", null, null)
        java.io.File("build/export-sample.rviz").writeText(text)
        val back = RvizImporter.import(text)
        assertEquals(displays.map { it.type }, back.displays.map { it.type })
        assertEquals(displays.map { it.name }, back.displays.map { it.name })
        assertEquals(displays.map { it.topic }, back.displays.map { it.topic })
        assertEquals(displays.map { it.enabled }, back.displays.map { it.enabled })
        assertEquals("odom", back.fixedFrame)
        assertEquals("/goal", back.goalTopic)
        assertEquals("7.5", back.view?.get("distance"))
        val laser = back.displays[2]
        assertEquals(0.1, laser.number("size"), 1e-9)
        assertEquals("flat", laser.string("colorMode"))
        assertEquals(20, laser.qosSettings().depth)
        assertEquals(false, back.displays[1].bool("showNames"))
    }
}
