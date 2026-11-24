// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.indicators

import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.Prim
import com.juancams.rviz2mini.rviz.display.QosPreset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorSpecTest {

    private val registry = InterfaceRegistry().apply {
        define("builtin_interfaces/msg/Time", "int32 sec\nuint32 nanosec")
        define("std_msgs/msg/Header", "builtin_interfaces/Time stamp\nstring frame_id")
        define("geometry_msgs/msg/Point", "float64 x\nfloat64 y\nfloat64 z")
        define("my/msg/Battery", "std_msgs/Header header\nfloat32 percentage\nbool charging\nstring status\nfloat32[] cells\nuint8[] raw\ngeometry_msgs/Point[] spots\ngeometry_msgs/Point where")
    }
    private val spec = registry.msg("my/msg/Battery")
    private val msg = DynCodec.fromJson(spec, JSONObject("""{"header":{"stamp":{"sec":12,"nanosec":500000000},"frame_id":"base"},
        "percentage":87.25,"charging":true,"status":"OK","cells":[3.7,3.8,3.9,4.0,4.1,4.2,4.3],"raw":[1,2,255],
        "spots":[{"x":1.5},{"x":2.5}],"where":{"x":7}}"""), strict = true)

    @Test
    fun leavesListScalarsTimesAndFirstArrayElements() {
        val paths = IndicatorMath.leaves(spec).map { it.path }
        assertEquals(listOf("header.stamp", "header.frame_id", "percentage", "charging", "status", "cells[0]", "raw[0]",
            "spots[0].x", "spots[0].y", "spots[0].z", "where.x", "where.y", "where.z"), paths)
        assertEquals(Prim.FLOAT32, IndicatorMath.leaves(spec).first { it.path == "percentage" }.prim)
        assertEquals(listOf("", "header", "where"), IndicatorMath.tables(spec))
    }

    @Test
    fun resolveChecksNamesAndIndices() {
        assertEquals(Prim.FLOAT32, IndicatorMath.resolve(spec, "cells[5]")!!.prim)
        assertEquals(Prim.FLOAT64, IndicatorMath.resolve(spec, "spots[1].x")!!.prim)
        assertTrue(IndicatorMath.resolve(spec, "header.stamp")!!.nested!!.isTime)
        assertNull(IndicatorMath.resolve(spec, "cells")) // array without index
        assertNull(IndicatorMath.resolve(spec, "spots.x")) // index missing before descending
        assertNull(IndicatorMath.resolve(spec, "percentage[0]")) // not an array
        assertNull(IndicatorMath.resolve(spec, "nope"))
        assertNull(IndicatorMath.resolve(spec, "cells[x]"))
    }

    @Test
    fun valueAtReadsReceivedMessages() {
        assertEquals(87.25, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "percentage"))!!, 1e-6)
        assertEquals(true, IndicatorMath.valueAt(msg, "charging"))
        assertEquals(4.2, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "cells[5]"))!!, 1e-6)
        assertEquals(2.5, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "spots[1].x"))!!, 0.0)
        assertEquals(12.5, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "header.stamp"))!!, 1e-9)
        assertEquals(255, (IndicatorMath.valueAt(mapOf("raw" to byteArrayOf(1, -1)), "raw[1]") as Number).toInt())
        assertNull(IndicatorMath.valueAt(msg, "cells[99]"))
    }

    @Test
    fun pathsWithNegativeIndicesAndFilters() {
        // Indices from the end.
        assertEquals(4.3, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "cells[-1]"))!!, 1e-6)
        assertEquals(255, (IndicatorMath.valueAt(msg, "raw[-1]") as Number).toInt())
        assertEquals(2.5, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "spots[-1].x"))!!, 0.0)
        assertEquals(Prim.FLOAT64, IndicatorMath.resolve(spec, "spots[-1].x")!!.prim)
        // Filter on an array of messages: the first matching element.
        assertEquals(2.5, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "spots{x>2}.x"))!!, 0.0)
        assertEquals(1.5, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "spots{x==1.5}.x"))!!, 0.0)
        assertNull(IndicatorMath.valueAt(msg, "spots{x>9}.x"))
        assertEquals(Prim.FLOAT64, IndicatorMath.resolve(spec, "spots{x>2}.y")!!.prim)
        assertNull(IndicatorMath.resolve(spec, "spots{nope==1}.x")) // the filter field does not exist
        assertNull(IndicatorMath.resolve(spec, "spots{x>2")) // unclosed
        // Filter on the whole message: only matching ones (texts with or without quotes, bool).
        assertEquals(87.25, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "{status==\"OK\"}.percentage"))!!, 1e-6)
        assertEquals(87.25, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "{header.frame_id==base}.percentage"))!!, 1e-6)
        assertEquals(87.25, IndicatorMath.toDouble(IndicatorMath.valueAt(msg, "{charging==true}.percentage"))!!, 1e-6)
        assertNull(IndicatorMath.valueAt(msg, "{status!='OK'}.percentage"))
        assertNull(IndicatorMath.valueAt(msg, "{percentage<50}.percentage"))
        assertEquals(Prim.FLOAT32, IndicatorMath.resolve(spec, "{status==OK}.percentage")!!.prim)
        assertTrue(IndicatorMath.hasFilter("{status==OK}.percentage"))
        assertFalse(IndicatorMath.hasFilter("cells[-1]"))
        // The usual ones still work the same and malformed ones still fail.
        assertNull(IndicatorMath.resolve(spec, "percentage."))
        assertNull(IndicatorMath.resolve(spec, ".percentage"))
        assertNull(IndicatorMath.resolve(spec, "spots..x"))
        assertNull(IndicatorMath.resolve(spec, "[0]"))
    }

    @Test
    fun formatAndFraction() {
        assertEquals("87.25", IndicatorMath.format(87.25f, 2))
        assertEquals("88", IndicatorMath.format(87.6, 0))
        assertEquals("true", IndicatorMath.format(true, 2))
        assertEquals("—", IndicatorMath.format(null, 2))
        assertEquals("12.500", IndicatorMath.format(mapOf("sec" to 12, "nanosec" to 500_000_000), 3))
        assertEquals(0.5, IndicatorMath.fraction(50.0, 0.0, 100.0), 0.0)
        assertEquals(1.0, IndicatorMath.fraction(500.0, 0.0, 100.0), 0.0)
        assertEquals(0.0, IndicatorMath.fraction(Double.NaN, 0.0, 100.0), 0.0)
        assertEquals(0, IndicatorMath.autoDecimals(Prim.UINT8))
        assertEquals(2, IndicatorMath.autoDecimals(Prim.FLOAT32))
        assertEquals(3, IndicatorMath.autoDecimals(Prim.FLOAT64, 0.0, 0.5))
    }

    @Test
    fun lightRules() {
        assertTrue(IndicatorMath.lightOn(LightRule.NONZERO, "", true))
        assertFalse(IndicatorMath.lightOn(LightRule.NONZERO, "", 0.0))
        assertFalse(IndicatorMath.lightOn(LightRule.NONZERO, "", ""))
        assertTrue(IndicatorMath.lightOn(LightRule.ABOVE, "20", 20.0))
        assertFalse(IndicatorMath.lightOn(LightRule.ABOVE, "20", 19.9f))
        assertTrue(IndicatorMath.lightOn(LightRule.BELOW, "20,5", 20L))
        assertTrue(IndicatorMath.lightOn(LightRule.EQUALS, "3", 3L))
        assertTrue(IndicatorMath.lightOn(LightRule.EQUALS, "OK", "OK"))
        assertFalse(IndicatorMath.lightOn(LightRule.EQUALS, "OK", "FAULT"))
        assertFalse(IndicatorMath.lightOn(LightRule.ABOVE, "x", 5.0))
        assertFalse(IndicatorMath.lightOn(LightRule.NONZERO, "", null))
    }

    @Test
    fun tableRowsFlattenAndSummarize() {
        val rows = IndicatorMath.rows(spec, msg).toMap()
        assertEquals("12.500", rows["header.stamp"])
        assertEquals("base", rows["header.frame_id"])
        assertEquals("87.250", rows["percentage"])
        assertEquals("[3.700, 3.800, 3.900, 4.000, 4.100, 4.200, …] (7)", rows["cells"])
        assertEquals("[1, 2, 255]", rows["raw"])
        assertEquals("[2 Point]", rows["spots"])
        assertEquals("7.000", rows["where.x"])
        assertEquals(listOf("x", "y", "z"), IndicatorMath.rows(registry.msg("geometry_msgs/msg/Point"), mapOf("x" to 1.0)).map { it.first })
        assertNotNull(IndicatorRunner.tableSpec(spec, "spots[1]"))
        assertNull(IndicatorRunner.tableSpec(spec, "spots"))
        assertNull(IndicatorRunner.tableSpec(spec, "percentage"))
    }

    @Test
    fun jsonRoundTripAndRepair() {
        val s = IndicatorSpec(label = "Batería", topic = "/battery", type = "sensor_msgs/msg/BatteryState", field = "percentage",
            kind = IndicatorKind.GAUGE, min = 0.0, max = 1.0, decimals = 2, unit = "%", rule = LightRule.BELOW, threshold = "0.2",
            onColor = 3, offColor = 2, color = 1, qos = QosPreset.RELIABLE, timeoutS = 5.0, definitions = mapOf("a/msg/B" to "int8 c"))
        assertEquals(listOf(s), IndicatorList.fromJson(IndicatorList.toJson(listOf(s))))
        val fixed = IndicatorSpec.fromJson(JSONObject("""{"kind":"NOPE","min":5,"max":1,"decimals":42,"timeoutS":-3}"""))
        assertEquals(IndicatorKind.NUMBER, fixed.kind)
        assertEquals(0.0, fixed.min, 0.0)
        assertEquals(100.0, fixed.max, 0.0)
        assertEquals(9, fixed.decimals)
        assertEquals(0.0, fixed.timeoutS, 0.0)
        assertEquals(emptyList<IndicatorSpec>(), IndicatorList.fromJson("not json"))
        val a = IndicatorSpec(id = "a"); val b = IndicatorSpec(id = "b")
        assertEquals(listOf("b", "a"), IndicatorList.move(listOf(a, b), "a", 1).map { it.id })
    }
}
