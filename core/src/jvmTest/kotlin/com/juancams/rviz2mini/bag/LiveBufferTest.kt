// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.bag

import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveBufferTest {
    private val registry = InterfaceRegistry().apply {
        define("std_msgs/msg/Float64", "float64 data")
        define("std_msgs/msg/String", "string data")
    }
    private val s = 1_000_000_000L

    @Test fun keepsTheLastWindowAndLatchedAndDumpsAnMcap() {
        val b = LiveBuffer(registry, windowS = 10)
        val file = File.createTempFile("livebuffer", ".mcap").apply { deleteOnExit() }
        assertFalse("vacío: nada que escribir", b.dump(file))
        b.recordForTest("/map", "std_msgs/msg/String", 1 * s, byteArrayOf(0, 1, 0, 0, 1, 0, 0, 0, 0))
        for (i in 0 until 30) b.recordForTest("/x", "std_msgs/msg/Float64", (i + 1) * s, ByteArray(12) { i.toByte() })
        // Only the last 10 s (from 20 to 30) and the map, even though it is from second 1.
        assertEquals(10.0, b.spanS(), 0.001)
        assertTrue(b.dump(file))
        McapReader.open(file).use { r ->
            val msgs = r.messages().toList()
            assertEquals(1 + 11, msgs.size)
            assertEquals("el latched, al principio y con el instante del primero", 20 * s, msgs.first().logTimeNs)
            assertEquals(30 * s, msgs.last().logTimeNs)
        }
        b.close()
        assertEquals(0L, b.sizeBytes())
    }

    @Test fun capsTheMemory() {
        val b = LiveBuffer(registry, windowS = 1000, maxBytes = 1000)
        for (i in 0 until 100) b.recordForTest("/x", "std_msgs/msg/Float64", i * s, ByteArray(100))
        assertTrue(b.sizeBytes() <= 1000)
    }
}
