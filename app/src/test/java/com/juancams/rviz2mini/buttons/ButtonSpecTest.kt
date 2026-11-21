// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.buttons

import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.rviz.display.QosPreset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonSpecTest {
    private val registry by lazy { InterfaceRegistry().apply { loadBundle(java.io.File("src/main/assets/ros_interfaces.txt").readText()) } }


    @Test
    fun jsonRoundTripKeepsEverything() {
        val b = ButtonSpec(
            label = "Ir a casa", kind = ButtonKind.ACTION, target = "/navigate_to_pose", type = "nav2_msgs/action/NavigateToPose",
            values = """{"pose":{"header":{"frame_id":"map","stamp":"now"}}}""", color = 3, qos = QosPreset.LATCHED,
            repeatHz = 0.0, timeoutS = 12.5, confirm = true,
            definitions = mapOf("mi_robot/msg/Luz" to "uint8 color\nfloat32 brillo")
        )
        val back = ButtonList.fromJson(ButtonList.toJson(listOf(b)))
        assertEquals(listOf(b), back)
    }

    @Test
    fun corruptOrUnknownEntriesAreSkipped() {
        val list = ButtonList.fromJson("""[{"kind":"NOPE"},{"kind":"SERVICE","target":"/x","type":"std_srvs/srv/Trigger","color":99,"repeatHz":-3}, 7]""")
        assertEquals(1, list.size)
        assertEquals(ButtonSpec.COLORS.size - 1, list[0].color)
        assertEquals(0.0, list[0].repeatHz, 0.0)
        assertTrue(ButtonList.fromJson("no es json").isEmpty())
        assertTrue(ButtonList.fromJson(null).isEmpty())
    }

    @Test
    fun moveReorders() {
        val a = ButtonSpec(label = "a"); val b = ButtonSpec(label = "b"); val c = ButtonSpec(label = "c")
        val l = listOf(a, b, c)
        assertEquals(listOf(b, a, c), ButtonList.move(l, a.id, +1))
        assertEquals(listOf(a, c, b), ButtonList.move(l, c.id, -1))
        assertEquals(l, ButtonList.move(l, a.id, -1))
        assertEquals(l, ButtonList.move(l, c.id, +1))
    }

    @Test
    fun summaryIsOneLine() {
        val spec = registry.srv("std_srvs/srv/Trigger").response
        val v = DynCodec.fromJson(spec, JSONObject("""{"success":true,"message":"hecho"}"""))
        assertEquals("success: true · message: hecho", ButtonRunner.summary(spec, v))
        val fib = registry.action("example_interfaces/action/Fibonacci").result
        assertEquals("sequence: [0, 1, 1, 2]", ButtonRunner.summary(fib, MsgValue().apply { put("sequence", listOf(0L, 1L, 1L, 2L)) }))
        // Fibonacci 10: the first values and how many, not just "[11]".
        val eleven = listOf(0L, 1L, 1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L, 55L)
        assertEquals("sequence: [0, 1, 1, 2, 3, 5, …] (11)", ButtonRunner.summary(fib, MsgValue().apply { put("sequence", eleven) }))
    }

    @Test
    fun stopOnReleaseIsOnByDefaultAlsoForSavedButtons() {
        assertTrue(ButtonSpec().stopOnRelease)
        // Saved with an older version, without the field: also stops on release.
        assertTrue(ButtonSpec.fromJson(JSONObject("""{"kind":"PUBLISH","target":"/cmd_vel","repeatHz":10}"""))!!.stopOnRelease)
        val off = ButtonSpec(kind = ButtonKind.PUBLISH, repeatHz = 10.0, stopOnRelease = false)
        assertFalse(ButtonList.fromJson(ButtonList.toJson(listOf(off))).single().stopOnRelease)
    }

    @Test
    fun stopPayloadZeroesNumbersButKeepsTextAndStamps() {
        val registry = InterfaceRegistry().apply {
            define("builtin_interfaces/msg/Time", "int32 sec\nuint32 nanosec")
            define("std_msgs/msg/Header", "builtin_interfaces/Time stamp\nstring frame_id")
            define("geometry_msgs/msg/Vector3", "float64 x\nfloat64 y\nfloat64 z")
            define("geometry_msgs/msg/Twist", "Vector3 linear\nVector3 angular")
            define("my/msg/Drive", "std_msgs/Header header\ngeometry_msgs/Twist twist\nbool enable\nstring mode\nfloat32[] wheels\nint8 gear")
        }
        val spec = registry.msg("my/msg/Drive")
        val v = DynCodec.fromJson(spec, JSONObject("""{"header":{"stamp":{"sec":5,"nanosec":7},"frame_id":"base_link"},
            "twist":{"linear":{"x":0.5},"angular":{"z":-1.2}},"enable":true,"mode":"auto","wheels":[1.5,2.5],"gear":3}"""), strict = true)
        val stop = ButtonRunner.stopPayload(spec, v)
        @Suppress("UNCHECKED_CAST") val header = stop["header"] as MsgValue
        assertEquals("base_link", header["frame_id"])
        assertEquals(v["header"].let { (it as MsgValue)["stamp"] }, header["stamp"])
        @Suppress("UNCHECKED_CAST") val twist = stop["twist"] as MsgValue
        assertEquals(0.0, (twist["linear"] as MsgValue)["x"])
        assertEquals(0.0, (twist["angular"] as MsgValue)["z"])
        assertEquals(false, stop["enable"])
        assertEquals("auto", stop["mode"])
        assertEquals(2, (stop["wheels"] as List<*>).size)
        assertTrue((stop["wheels"] as List<*>).all { (it as Number).toDouble() == 0.0 })
        assertEquals(0L, (stop["gear"] as Number).toLong())
        // It is still a valid message of the type.
        DynCodec.toJson(spec, stop)
    }
}
