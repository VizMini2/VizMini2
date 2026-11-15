// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bag

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class BagSessionTest {

    @Test
    fun freeFileNeverReusesAnExistingName() {
        val dir = Files.createTempDirectory("bags").toFile()
        try {
            assertEquals("a.mcap", BagSession.freeFile(dir, "a.mcap").name)
            assertEquals("a.mcap", BagSession.freeFile(dir, "a").name)
            java.io.File(dir, "a.mcap").writeText("x")
            assertEquals("a_2.mcap", BagSession.freeFile(dir, "a.mcap").name)
            java.io.File(dir, "a_2.mcap").writeText("x")
            assertEquals("a_3.mcap", BagSession.freeFile(dir, "a").name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
