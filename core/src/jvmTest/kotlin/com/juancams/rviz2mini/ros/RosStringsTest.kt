// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import org.junit.Assert.assertEquals
import org.junit.Test

class RosStringsTest {
    /** Simulates what jros2 does: each UTF-8 byte to a char with sign extension. */
    private fun jros2(s: String) = String(s.toByteArray(Charsets.UTF_8).map { it.toInt().toChar() }.toCharArray())

    @Test
    fun repairsSignExtendedUtf8() {
        assertEquals("Batería al 25 % · ñandú €", RosStrings.fix(jros2("Batería al 25 % · ñandú €")))
        assertEquals("ascii", RosStrings.fix(StringBuilder("ascii")))
        // Already correct (characters outside the byte range): left untouched.
        assertEquals("Ωmega", RosStrings.fix("Ωmega"))
    }
}
