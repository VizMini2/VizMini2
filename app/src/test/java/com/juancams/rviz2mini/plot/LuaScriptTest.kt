// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.plot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaScriptTest {

    @Test
    fun globalsPersistAndNilSkips() {
        // Cumulative sum and only the positive samples.
        val s = LuaScript("total = 0", "if value < 0 then return nil end\ntotal = total + value\nreturn total + v1 * 0", 2)
        assertEquals(1.0, s.call(0.0, doubleArrayOf(1.0, 9.0))!!, 0.0)
        assertNull(s.call(0.1, doubleArrayOf(-5.0, 9.0)))
        assertEquals(3.5, s.call(0.2, doubleArrayOf(2.5, 9.0))!!, 0.0)
        // time in seconds, math and booleans.
        assertEquals(2.0, LuaScript("", "return math.floor(time)", 1).call(2.7, doubleArrayOf(0.0))!!, 0.0)
        assertEquals(1.0, LuaScript("", "return value > 1", 1).call(0.0, doubleArrayOf(3.0))!!, 0.0)
    }

    @Test
    fun errorsAndSandbox() {
        assertThrows(ScriptException::class.java) { LuaScript("", "return value +", 1) }
        assertThrows(ScriptException::class.java) { LuaScript("", "return 'text'", 1).call(0.0, doubleArrayOf(0.0)) }
        assertThrows(ScriptException::class.java) { LuaScript("", "return nope.x", 1).call(0.0, doubleArrayOf(0.0)) }
        for (code in listOf("return io.open('/x')", "return os.time()", "return require('x')", "return dofile('/x')")) {
            val e = assertThrows(code, ScriptException::class.java) { LuaScript("", code, 1).call(0.0, doubleArrayOf(0.0)) }
            assertTrue(e.message!!, e.message!!.isNotEmpty())
        }
    }

    @Test
    fun runnerComputesScriptSeries() {
        val a = SeriesSpec(id = "a", topic = "/a", type = "t", field = "x", label = "a")
        val b = SeriesSpec(id = "b", topic = "/b", type = "t", field = "x", label = "b")
        val lua = SeriesSpec(id = "l", script = "n = n + 1\nreturn value * 10 + v1 + n", scriptGlobals = "n = 0", inputs = "a\nb")
        val bad = SeriesSpec(id = "x", script = "return value", inputs = "nope")
        val buffers = listOf(a, b, lua, bad).associate { it.id to SeriesBuffer() }
        val errors = HashMap<String, String>()
        val runner = FormulaRunner(listOf(a, b, lua, bad), buffers, errors)
        assertTrue(errors.getValue("x").contains("nope"))
        val s = 1_000_000_000L
        buffers.getValue("b").add(0, 5.0)
        buffers.getValue("a").add(1 * s, 1.0); buffers.getValue("a").add(2 * s, 2.0)
        runner.update()
        assertEquals(listOf(16.0, 27.0), buffers.getValue("l").all().second.toList())
        assertEquals("lua(a, b)", lua.displayLabel)
        // JSON round trip and renaming an entry.
        val back = PlotLayout.fromJson(PlotLayout(listOf(PanelSpec(series = listOf(a, lua)))).renameReferences("a", "speed").toJson())
        assertEquals(listOf("speed", "b"), back.allSeries[1].inputList)
        assertEquals("n = 0", back.allSeries[1].scriptGlobals)
    }
}
