// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.bag.BagPlayer
import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.bag.BagSession
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bag playback only on this phone (isolated connection) with seeks: records a counter published
 * by the app itself, plays it back locally, seeks to 80 % and returns to the previous connection.
 */
@RunWith(AndroidJUnit4::class)
class BagPlaybackDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var savedRos: Map<String, *>? = null
    private val config = RosConfig(domainId = 77, nodeName = "rviz2mini_bag_test")

    @Before
    fun setUp() {
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(config)
        waitConnected(config)
    }

    @After
    fun tearDown() {
        BagSession.leaveLocal()
        RosConnection.stop()
        prefs("rviz2mini_ros").edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun waitConnected(c: RosConfig) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while ((RosConnection.status !is RosConnection.Status.Connected || RosConnection.config != c) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Sin conexión con $c", RosConnection.status is RosConnection.Status.Connected && RosConnection.config == c)
    }

    @Test
    fun playsOnlyLocallyAndSeeks() {
        val registry = Interfaces.get()
        val spec = registry.msg("std_msgs/msg/Float64")
        val topic = "/rviz2mini_test/bag_counter"
        val file = File(BagSession.dir(), "bag_test_${System.currentTimeMillis()}.mcap")
        try {
            // 3 s of a counter (seconds since start) at 20 Hz.
            val pub = DynRos.publisher(topic, spec, RosConnection.defaultQos())
            val rec = BagRecorder(file, listOf(topic to "std_msgs/msg/Float64"), registry)
            SystemClock.sleep(1000) // let them match
            val t0 = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - t0 < 3000) {
                pub.publish(MsgValue().apply { put("data", (SystemClock.uptimeMillis() - t0) / 1000.0) })
                SystemClock.sleep(50)
            }
            rec.stop(); pub.close()
            Log.i(TAG, "grabados ${rec.messages.get()}")
            assertTrue(rec.messages.get() >= 40)

            // Only on this phone.
            BagSession.enterLocal()
            assertTrue(BagSession.isLocal())
            waitConnected(BagSession.LOCAL_CONFIG)
            val got = CopyOnWriteArrayList<Double>()
            val sub = DynRos.subscribe(topic, spec, RosConnection.defaultQos()) { v -> got.add((v["data"] as Number).toDouble()) }
            val positions = CopyOnWriteArrayList<Long>()
            val player = BagPlayer(file, registry, object : BagPlayer.Listener {
                override fun onProgress(positionNs: Long, durationNs: Long) { positions.add(positionNs) }
                override fun onFinished(error: String?) {}
            })
            BagSession.player = player
            player.start()
            SystemClock.sleep(1800) // 0.8 s of matching + 1 s of bag
            val early = got.toList()
            Log.i(TAG, "al principio: ${early.size} valores, último ${early.lastOrNull()}")
            assertTrue("no llega nada en local", early.isNotEmpty())
            assertTrue(early.last() < 1.5)

            // Seek to 80 %: the next thing that arrives is from that segment.
            got.clear()
            player.seek((player.reader.durationNs * 0.8).toLong())
            SystemClock.sleep(400)
            val after = got.toList()
            Log.i(TAG, "tras el salto: $after")
            // The message already going out when the seek was requested may slip through.
            val jumped = after.dropWhile { it < 2.2 }
            assertTrue("no ha saltado: $after", jumped.isNotEmpty() && after.size - jumped.size <= 1)

            // While paused nothing arrives; a backward seek while paused only moves the position.
            player.paused = true
            SystemClock.sleep(200)
            got.clear()
            player.seek(0)
            SystemClock.sleep(400)
            assertTrue("sigue publicando en pausa: $got", got.isEmpty())
            assertEquals(0L, positions.last())
            player.paused = false
            SystemClock.sleep(500)
            assertTrue(got.isNotEmpty() && got.first() < 0.5)

            sub.close()
            player.close()
            BagSession.player = null
        } finally {
            file.delete()
        }
        // Stopping returns to the previous connection.
        BagSession.leaveLocal()
        assertFalse(BagSession.isLocal())
        waitConnected(config)
    }

    /**
     * Direct playback to the app (no DDS): the counter and a TF reach the app's subscriptions
     * with the bag time, the shared timeline appears and playback is stopped from it.
     */
    @Test
    fun playsDirectlyToTheAppWithTheSharedTimeline() {
        val registry = Interfaces.get()
        val spec = registry.msg("std_msgs/msg/Float64")
        val topic = "/rviz2mini_test/bag_direct"
        val file = File(BagSession.dir(), "bag_direct_${System.currentTimeMillis()}.mcap")
        try {
            val pub = DynRos.publisher(topic, spec, RosConnection.defaultQos())
            val rec = BagRecorder(file, listOf(topic to "std_msgs/msg/Float64"), registry)
            SystemClock.sleep(1000)
            val t0 = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - t0 < 2000) {
                pub.publish(MsgValue().apply { put("data", (SystemClock.uptimeMillis() - t0) / 1000.0) })
                SystemClock.sleep(50)
            }
            rec.stop(); pub.close()

            BagSession.enterLocal()
            waitConnected(BagSession.LOCAL_CONFIG)
            val got = CopyOnWriteArrayList<Double>()
            val sub = DynRos.subscribe(topic, spec, RosConnection.defaultQos()) { v -> got.add((v["data"] as Number).toDouble()) }
            val player = BagPlayer(file, registry, object : BagPlayer.Listener {
                override fun onProgress(positionNs: Long, durationNs: Long) {}
                override fun onFinished(error: String?) {}
            }, direct = true)
            BagSession.player = player
            RosConnection.setBagPlayback(true)
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                player.start()
                // Without the DDS matching wait: in 1 s almost a second of bag has already arrived.
                SystemClock.sleep(1000)
                Log.i(TAG, "directo: ${got.size} valores, último ${got.lastOrNull()}")
                assertTrue("no llega nada en directo", got.size >= 10)
                assertTrue(got.last() > 0.6)
                var shown = false
                scenario.onActivity { a -> shown = a.window.decorView.findViewWithTag<android.view.View>("bag-timeline")?.visibility == android.view.View.VISIBLE }
                assertTrue("no se ve la línea de tiempo", shown)
                // Stop from the bar: returns to the previous connection and stops delivering.
                BagSession.stopPlayer()
                val until = SystemClock.uptimeMillis() + 5_000
                while (BagSession.isLocal() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
                assertFalse(BagSession.isLocal())
                assertFalse(RosConnection.bagPlaybackActive)
                // The previous connection comes back (closing the activity makes the app close it: checked here).
                assertEquals(config, RosConnection.config)
            }
            sub.close()
        } finally {
            file.delete()
            if (BagSession.isLocal()) BagSession.leaveLocal()
        }
    }

    /** A "large" bag (with the limit lowered) in Plot: reduced view, and the CSV with all samples. */
    @Test
    fun largeBagOverviewAndFullCsv() {
        val registry = Interfaces.get()
        val spec = registry.msg("std_msgs/msg/Float64")
        val topic = "/rviz2mini_test/bag_large"
        val file = File(BagSession.dir(), "bag_large_${System.currentTimeMillis()}.mcap")
        val limit = com.juancams.rviz2mini.plot.BagPlotSource.fullLimit
        try {
            val pub = DynRos.publisher(topic, spec, RosConnection.defaultQos())
            val rec = BagRecorder(file, listOf(topic to "std_msgs/msg/Float64"), registry)
            SystemClock.sleep(1000)
            for (i in 0 until 400) { pub.publish(MsgValue().apply { put("data", i.toDouble()) }); SystemClock.sleep(5) }
            SystemClock.sleep(500)
            rec.stop(); pub.close()
            val n = rec.messages.get().toInt()
            Log.i(TAG, "grabados $n")
            assertTrue(n >= 390)
            com.juancams.rviz2mini.plot.BagPlotSource.fullLimit = 100
            val s = com.juancams.rviz2mini.plot.SeriesSpec(id = "v", topic = topic, type = "std_msgs/msg/Float64", field = "data", label = "v")
            val twice = com.juancams.rviz2mini.plot.SeriesSpec(id = "f", formula = "{v} * 2", label = "twice")
            val src = com.juancams.rviz2mini.plot.BagPlotSource.load(file, listOf(s, twice), registry)
            assertEquals(setOf("v"), src.decimated)
            val reduced = src.buffers.getValue("v").size()
            val out = java.io.StringWriter()
            src.exportCsv(out)
            val rows = out.toString().trim().lines()
            Log.i(TAG, "reducida $reduced muestras; CSV ${rows.size - 1} filas, ${rows[0]} / ${rows.last()}")
            // Up to 2 samples per bin: with 400 messages in ~2 s almost all fall into different bins and the reduced series
            // may end up just as long (it used to require reduced < n and failed depending on publish timing).
            assertTrue("reducida $reduced de $n", reduced in 1..minOf(n, 2 * com.juancams.rviz2mini.plot.BagPlotSource.OVERVIEW_BUCKETS))
            assertEquals("time_s,stamp_s,v,twice", rows[0])
            assertEquals(n, rows.size - 1)
            // The last row: the last value and its formula.
            val last = rows.last().split(',')
            assertEquals(last[2].toDouble() * 2, last[3].toDouble(), 1e-9)
        } finally {
            com.juancams.rviz2mini.plot.BagPlotSource.fullLimit = limit
            file.delete()
        }
    }

    private companion object {
        const val TAG = "BagPlayback"
    }
}
