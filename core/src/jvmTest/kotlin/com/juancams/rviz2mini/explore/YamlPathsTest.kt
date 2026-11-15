// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.explore

import com.juancams.rviz2mini.ros.dyn.MsgFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class YamlPathsTest {
    @Test fun nestedNumbersFromMsgFormat() {
        val msg = linkedMapOf(
            "header" to linkedMapOf("stamp" to linkedMapOf("sec" to 12, "nanosec" to 5), "frame_id" to "odom"),
            "twist" to linkedMapOf("linear" to linkedMapOf("x" to 0.5, "y" to -0.001)),
            "ranges" to listOf(1.0f, 2.0f),
            "status" to listOf(linkedMapOf("name" to "a", "level" to 1), linkedMapOf("name" to "b", "level" to 2)),
            "done" to true,
        )
        val y = MsgFormat.yaml(msg)
        val byLine = y.lines().zip(YamlPaths.numericPaths(y)).filter { it.second != null }.associate { it.first.trim() to it.second }
        assertEquals("header.stamp.sec", byLine["sec: 12"])
        assertEquals("header.stamp.nanosec", byLine["nanosec: 5"])
        assertEquals("twist.linear.x", byLine["x: 0.5"])
        assertEquals("done", byLine["done: true"])
        assertEquals(5, byLine.size, y) // ni frame_id, ni el array, ni lo de dentro de status
    }
}
