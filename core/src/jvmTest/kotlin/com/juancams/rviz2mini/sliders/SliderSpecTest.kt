// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.sliders

import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.Prim
import com.juancams.rviz2mini.ros.dyn.ValueException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderSpecTest {

    private val registry = InterfaceRegistry().apply {
        define("std_msgs/msg/Float64", "float64 data")
        define("std_msgs/msg/UInt8", "uint8 data")
        define("std_msgs/msg/String", "string data")
        define("geometry_msgs/msg/Vector3", "float64 x\nfloat64 y\nfloat64 z")
        define("geometry_msgs/msg/Twist", "Vector3 linear\nVector3 angular")
        define("my/msg/Mixed", "string name\nint32[] ids\nint16 level\ngeometry_msgs/Vector3 v\nbool on")
    }

    @Test
    fun numericFieldsWalkSubmessagesAndSkipArraysTextAndBool() {
        assertEquals(listOf("data" to Prim.FLOAT64), SliderMath.numericFields(registry.msg("std_msgs/msg/Float64")))
        assertEquals(listOf("linear.x", "linear.y", "linear.z", "angular.x", "angular.y", "angular.z"),
            SliderMath.numericFields(registry.msg("geometry_msgs/msg/Twist")).map { it.first })
        assertEquals(listOf("level", "v.x", "v.y", "v.z"), SliderMath.numericFields(registry.msg("my/msg/Mixed")).map { it.first })
        assertTrue(SliderMath.numericFields(registry.msg("std_msgs/msg/String")).isEmpty())
    }

    @Test
    fun quantizeClampsAndSnapsWithoutFloatNoise() {
        assertEquals(0.3, SliderMath.quantize(0.29, 0.0, 1.0, 0.1), 0.0)
        assertEquals(1.0, SliderMath.quantize(7.0, 0.0, 1.0, 0.1), 0.0)
        assertEquals(-0.5, SliderMath.quantize(-9.0, -0.5, 0.5, 0.05), 0.0)
        assertEquals(0.123, SliderMath.quantize(0.123, 0.0, 1.0, 0.0), 0.0)
        // The step counts from the minimum, not from 0.
        assertEquals(1.5, SliderMath.quantize(1.4, 0.5, 3.0, 1.0), 0.0)
    }

    @Test
    fun payloadSetsTheFieldAndKeepsTheRest() {
        val twist = registry.msg("geometry_msgs/msg/Twist")
        val m = SliderMath.payload(twist, """{"angular":{"z":0.3}}""", "linear.x", 0.25)
        @Suppress("UNCHECKED_CAST")
        val linear = m["linear"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val angular = m["angular"] as Map<String, Any?>
        assertEquals(0.25, linear["x"])
        assertEquals(0.0, linear["y"])
        assertEquals(0.3, angular["z"])
    }

    @Test
    fun integerFieldsAreRoundedAndRangeChecked() {
        val u8 = registry.msg("std_msgs/msg/UInt8")
        assertEquals(43L, SliderMath.payload(u8, "{}", "data", 42.6)["data"])
        try {
            SliderMath.payload(u8, "{}", "data", 300.0)
            throw AssertionError("300 no cabe en uint8")
        } catch (_: ValueException) { }
        try {
            SliderMath.payload(u8, "{}", "nope", 1.0)
            throw AssertionError("campo inexistente")
        } catch (_: ValueException) { }
    }

    @Test
    fun jsonRoundTripAndRepairsImpossibleValues() {
        val s = SliderSpec(label = "Speed", target = "/cmd_vel", type = "geometry_msgs/msg/Twist", field = "linear.x",
            min = -0.5, max = 0.5, step = 0.05, initial = 0.0, mode = SliderMode.WHILE_DRAGGING, rateHz = 20.0, springBack = true,
            values = """{"angular":{"z":0}}""", color = 2, definitions = mapOf("my/msg/X" to "int32 a"))
        assertEquals(s, SliderList.fromJson(SliderList.toJson(listOf(s))).single())

        val broken = SliderSpec.fromJson(org.json.JSONObject("""{"min":5,"max":1,"step":-3,"initial":99,"rateHz":1000,"mode":"WAT"}"""))
        assertEquals(0.0, broken.min, 0.0)
        assertEquals(1.0, broken.max, 0.0)
        assertEquals(0.0, broken.step, 0.0)
        assertEquals(1.0, broken.initial, 0.0)
        assertEquals(SliderSpec.MAX_RATE_HZ, broken.rateHz, 0.0)
        assertEquals(SliderMode.WHILE_DRAGGING, broken.mode)
        assertTrue(SliderList.fromJson("not json").isEmpty())
    }

    @Test
    fun initialIsSnappedToTheStep() {
        val s = SliderSpec.fromJson(org.json.JSONObject("""{"min":0,"max":10,"step":2,"initial":3.1}"""))
        assertEquals(4.0, s.initial, 0.0)
    }

    @Test
    fun validationAndMoving() {
        assertNull(SliderMath.validate(-1.0, 1.0, 0.1, 0.0))
        assertEquals(SliderMath.Problem.NOT_A_NUMBER, SliderMath.validate(null, 1.0, 0.1, 0.0))
        assertEquals(SliderMath.Problem.MIN_NOT_BELOW_MAX, SliderMath.validate(1.0, 1.0, 0.0, 1.0))
        assertEquals(SliderMath.Problem.BAD_STEP, SliderMath.validate(0.0, 1.0, 2.0, 0.0))
        assertEquals(SliderMath.Problem.INITIAL_OUT_OF_RANGE, SliderMath.validate(0.0, 1.0, 0.0, 2.0))

        val a = SliderSpec(id = "a"); val b = SliderSpec(id = "b"); val c = SliderSpec(id = "c")
        assertEquals(listOf("b", "a", "c"), SliderList.move(listOf(a, b, c), "a", +1).map { it.id })
        assertEquals(listOf("a", "b", "c"), SliderList.move(listOf(a, b, c), "a", -1).map { it.id })
    }

    @Test
    fun formattingUsesTheStepDecimalsAndNoLocaleCommas() {
        assertEquals(2, SliderMath.decimals(0.0, 1.0, 0.05))
        assertEquals(0, SliderMath.decimals(0.0, 255.0, 1.0))
        assertEquals("0.25", SliderMath.format(0.25, 2, integer = false))
        assertEquals("43", SliderMath.format(42.6, 2, integer = true))
        assertFalse(SliderMath.format(1234.5, 1, integer = false).contains(','))
    }
}
