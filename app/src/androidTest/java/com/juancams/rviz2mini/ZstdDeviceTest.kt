// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.bag.McapReader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A rosbag2 bag with zstd chunks is read on the phone (native zstd library on ARM). */
@RunWith(AndroidJUnit4::class)
class ZstdDeviceTest {
    @Test
    fun readsZstdBag() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val f = File(ins.targetContext.cacheDir, "jazzy_zstd.mcap")
        ins.context.assets.open("jazzy_zstd.mcap").use { i -> f.outputStream().use { i.copyTo(it) } }
        McapReader.open(f).use { r ->
            assertEquals(128L, r.messageCount)
            assertEquals(128, r.messages().count())
        }
    }
}
