// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.bag.BagSession
import com.juancams.rviz2mini.plot.BagPlotSource
import com.juancams.rviz2mini.plot.PanelKind
import com.juancams.rviz2mini.plot.PanelSpec
import com.juancams.rviz2mini.plot.PlotFragment
import com.juancams.rviz2mini.plot.PlotLayout
import com.juancams.rviz2mini.plot.SeriesSpec
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Non-ROS Plot sources (tools/pc_tests.sh StreamsPcTest: tools/stream_test_server.py with
 * ZeroMQ and MQTT on the PC); UDP is sent by the test itself. And a CSV as a source.
 */
@RunWith(AndroidJUnit4::class)
class StreamsPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var savedTabs: String? = null

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedTabs = TestTabs.backup(context)
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("pc") != "true") return
        TestTabs.restore(context, savedTabs)
    }

    @Test
    fun udpMqttZmqAndCsv() {
        val pc = TestArgs.pcIp
        val udp = SeriesSpec(id = "udp", topic = "udp://0.0.0.0:9870", type = "json", field = "speed", label = "udp")
        val mqtt = SeriesSpec(id = "mqtt", topic = "mqtt://vizmini:secret@$pc:1883/robot/#", type = "json", field = "battery.pct", label = "mqtt")
        val badAuth = SeriesSpec(id = "bad", topic = "mqtt://vizmini:wrong@$pc:1883/robot/#", type = "json", field = "speed", label = "bad")
        val zmq = SeriesSpec(id = "zmq", topic = "zmq://$pc:9872", type = "json", field = "speed", label = "zmq")
        val mode = SeriesSpec(id = "mode", topic = "mqtt://$pc:1883/robot/#", type = "json", field = "mode", label = "mode")
        val layout = PlotLayout(listOf(PanelSpec(series = listOf(udp, mqtt, zmq, badAuth)), PanelSpec(kind = PanelKind.STATES, series = listOf(mode))), windowS = 10.0)
        val tab = TabSpec.create(TabType.PLOT, "Streams test").withSetting(PlotFragment.KEY_LAYOUT, layout.toJson())
        TabStore.get().add(tab)
        var csv: File? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                    (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
                }
                instrumentation.waitForIdleSync()
                var f: PlotFragment? = null
                scenario.onActivity { a -> f = a.supportFragmentManager.findFragmentByTag(tab.id) as PlotFragment }
                val plot = f!!
                // UDP: the test itself sends JSON to its port.
                val sender = Thread {
                    DatagramSocket().use { s ->
                        for (i in 0 until 60) {
                            val b = """{"speed": ${i * 0.1}}""".toByteArray()
                            s.send(DatagramPacket(b, b.size, InetAddress.getLoopbackAddress(), 9870))
                            Thread.sleep(100)
                        }
                    }
                }.apply { start() }
                val deadline = SystemClock.uptimeMillis() + 15_000
                fun n(id: String) = onMain { plot.bufferSizeForTest(id) }
                while ((n("udp") < 10 || n("mqtt") < 10 || n("zmq") < 10 || n("mode") < 5) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200)
                Log.i(TAG, "udp=${n("udp")} mqtt=${n("mqtt")} zmq=${n("zmq")} mode=${n("mode")} errores=${onMain { plot.errorsForTest() }}")
                assertTrue(n("udp") >= 10); assertTrue(n("mqtt") >= 10); assertTrue(n("zmq") >= 10)
                val now = System.currentTimeMillis() * 1_000_000L
                val pct = onMain { plot.valueAtForTest("mqtt", now) }!!
                assertTrue("pct=$pct", pct in 0.0..1.0)
                assertEquals(listOf("auto"), onMain { plot.textLabelsForTest()["mode"] })
                assertEquals(0, n("bad"))
                assertTrue(onMain { plot.errorsForTest() }["bad"].orEmpty().contains("password"))
                sender.join()

                // CSV as a source: time in s and two columns.
                csv = File(BagSession.dir(), "streams_test_${System.currentTimeMillis()}.csv").apply {
                    writeText("time,speed,temp\n" + (0 until 50).joinToString("\n") { "${it * 0.1},${it * 2},${20 + it % 3}" })
                }
                val csvLayout = PlotLayout(listOf(PanelSpec(series = listOf(
                    SeriesSpec(id = "s", topic = BagPlotSource.CSV_TOPIC, type = "csv", field = "speed"),
                    SeriesSpec(id = "x", topic = BagPlotSource.CSV_TOPIC, type = "csv", field = "nope")))))
                onMain { plot.saveLayoutForTest(csvLayout); plot.openBagForTest(csv!!) }
                val loaded = SystemClock.uptimeMillis() + 10_000
                while (onMain { plot.bagForTest() } == null && SystemClock.uptimeMillis() < loaded) SystemClock.sleep(100)
                val b = onMain { plot.bagForTest() }!!
                assertEquals(listOf("speed", "temp"), b.columns)
                assertEquals(50, n("s"))
                assertEquals(40.0, onMain { plot.valueAtForTest("s", 2_000_000_000L) }!!, 1e-9)
                assertTrue(onMain { plot.errorsForTest() }.getValue("x").contains("nope"))
                onMain { plot.goLiveForTest() }
            }
        } finally {
            TabStore.get().remove(tab.id)
            csv?.delete()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var r: T? = null
        instrumentation.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private companion object {
        const val TAG = "StreamsPc"
    }
}
