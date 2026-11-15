// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.params

import com.juancams.rviz2mini.params.ParamModel.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParamModelTest {

    @Test
    fun parsesEveryType() {
        assertEquals(true, ParamModel.parse(Type.BOOL, " True ").value)
        assertEquals(42L, ParamModel.parse(Type.INTEGER, "42").value)
        assertEquals(0.25, ParamModel.parse(Type.DOUBLE, "0.25").value)
        assertEquals(" spaced ", ParamModel.parse(Type.STRING, " spaced ").value)
        assertEquals(listOf(1L, 2L, 3L), ParamModel.parse(Type.INTEGER_ARRAY, "[1, 2, 3]").value)
        assertEquals(listOf(1.0, 2.5), ParamModel.parse(Type.DOUBLE_ARRAY, "1 2.5").value)
        assertEquals(listOf("a", "b c"), ParamModel.parse(Type.STRING_ARRAY, "['a', \"b c\"]").value)
        assertEquals(listOf(255L, 1L), ParamModel.parse(Type.BYTE_ARRAY, "[ff, 0x01]").value)
        assertEquals(emptyList<Long>(), ParamModel.parse(Type.INTEGER_ARRAY, "[]").value)
    }

    @Test
    fun rejectsBadInput() {
        assertThrows(ParamModel.ParseException::class.java) { ParamModel.parse(Type.INTEGER, "1.5") }
        assertThrows(ParamModel.ParseException::class.java) { ParamModel.parse(Type.BOOL, "maybe") }
        assertThrows(ParamModel.ParseException::class.java) { ParamModel.parse(Type.BYTE_ARRAY, "[256]") }
    }

    @Test
    fun msgRoundTripAndDisplay() {
        for (v in listOf(
            ParamModel.Value(Type.BOOL, false), ParamModel.Value(Type.INTEGER, -3L), ParamModel.Value(Type.DOUBLE, 2.0),
            ParamModel.Value(Type.STRING, "hola"), ParamModel.Value(Type.DOUBLE_ARRAY, listOf(1.5, 2.0)),
            ParamModel.Value(Type.STRING_ARRAY, listOf("x"))
        )) assertEquals(v, ParamModel.fromMsg(ParamModel.toMsg(v)))
        assertEquals("2.0", ParamModel.Value(Type.DOUBLE, 2.0).display())
        assertEquals("[1.5, 2.0]", ParamModel.Value(Type.DOUBLE_ARRAY, listOf(1.5, 2.0)).display())
        assertEquals("['a', 'b']", ParamModel.Value(Type.STRING_ARRAY, listOf("a", "b")).display())
        // What is displayed can be read back.
        val arr = ParamModel.Value(Type.STRING_ARRAY, listOf("a", "b"))
        assertEquals(arr, ParamModel.parse(Type.STRING_ARRAY, arr.display()))
    }

    @Test
    fun groupsByDots() {
        assertEquals("controller.gains" to "kp", ParamModel.group("controller.gains.kp"))
        assertEquals("" to "use_sim_time", ParamModel.group("use_sim_time"))
    }
}
