// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.bag.BagSession
import com.juancams.rviz2mini.plot.PanelKind
import com.juancams.rviz2mini.plot.PanelSpec
import com.juancams.rviz2mini.plot.PlotFragment
import com.juancams.rviz2mini.plot.PlotLayout
import com.juancams.rviz2mini.plot.SeriesSpec
import com.juancams.rviz2mini.plot.SeriesTransform
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Plots tab against ROS 2 on the PC: live and with a bag recorded right here. Optional
 * (tools/pc_tests.sh PlotPcTest), with `python3 tools/indicators_test_pub.py` on the PC (the battery:
 * the percentage goes from 0 to 1 in 10 s and back to 0). Screenshots in files/plot/.
 */
@RunWith(AndroidJUnit4::class)
class PlotPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        assumeTrue("Solo con -e pc true", InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_phone", discoveryPeers = TestArgs.peers))
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Sin conexión ROS 2", RosConnection.status is RosConnection.Status.Connected)
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("pc") != "true") return
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun liveSeriesXyDerivativeCsvAndBag() {
        val t = "/rviz2mini_test/battery"; val ty = "sensor_msgs/msg/BatteryState"
        val pct = SeriesSpec(topic = t, type = ty, field = "percentage", label = "pct")
        val volt = SeriesSpec(topic = t, type = ty, field = "voltage", label = "V")
        val x = pct.copy(id = "x"); val y = volt.copy(id = "y")
        val rate = SeriesSpec(id = "rate", topic = t, type = ty, field = "percentage", label = "d pct", transform = SeriesTransform.DERIVATIVE)
        val layout = PlotLayout(listOf(PanelSpec(series = listOf(pct, volt)), PanelSpec(kind = PanelKind.XY, series = listOf(x, y)),
            PanelSpec(series = listOf(rate))), windowS = 10.0)
        val tab = TabSpec.create(TabType.PLOT, "Plot test").withSetting(PlotFragment.KEY_LAYOUT, layout.toJson())
        TabStore.get().add(tab)
        var bag: File? = null
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
                assertEquals(3, onMain { plot.panelViewsForTest().size })

                // Live: at 5 Hz, about 20 samples in 4 s after discovery.
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (onMain { plot.bufferSizeForTest(pct.id) } < 20 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200)
                val n = onMain { plot.bufferSizeForTest(pct.id) }
                Log.i(TAG, "muestras: pct=$n V=${onMain { plot.bufferSizeForTest(volt.id) }} rate=${onMain { plot.bufferSizeForTest(rate.id) }}")
                assertTrue("pocas muestras: $n", n >= 20)
                assertTrue(onMain { plot.errorsForTest() }.isEmpty())
                // Derivative: the percentage rises 0.1 per second (except when it goes back to 0).
                val now = System.currentTimeMillis() * 1_000_000L
                val rates = (0 until 20).mapNotNull { k -> onMain { plot.valueAtForTest(rate.id, now - k * 200_000_000L) } }
                val median = rates.sorted()[rates.size / 2]
                Log.i(TAG, "derivada mediana: $median")
                assertEquals(0.1, median, 0.03)
                // V = 22 + 4·pct in the same message.
                val vNow = onMain { plot.valueAtForTest(volt.id, now) }!!
                val pNow = onMain { plot.valueAtForTest(pct.id, now) }!!
                assertEquals(22.0 + 4 * pNow, vNow, 1e-3)
                shot("1_en_vivo")

                // Pause: the window stops advancing.
                onMain { plot.toggleFollowForTest() }
                assertFalse(onMain { plot.viewportForTest().follow })
                val end = onMain { plot.viewportForTest().endNs }
                SystemClock.sleep(500)
                assertEquals(end, onMain { plot.viewportForTest().endNs })
                onMain { plot.viewportForTest().cursorNs = end - 3_000_000_000L; plot.onViewportChanged() }
                shot("2_pausa_cursor")
                onMain { plot.toggleFollowForTest() }

                val csv = onMain { plot.csvForTest() }.trim().lines()
                Log.i(TAG, "CSV: ${csv.size} filas, ${csv[0]}")
                assertEquals("time_s,stamp_s,pct,V,pct,V,d pct", csv[0])
                assertTrue(csv.size > 20)

                // Bag: 4 s of battery recorded by the app and opened in the plot.
                val f2 = File(BagSession.dir(), "plot_test_${System.currentTimeMillis()}.mcap")
                bag = f2
                val rec = BagRecorder(f2, listOf(t to ty), Interfaces.get())
                SystemClock.sleep(4000)
                rec.stop()
                Log.i(TAG, "bag: ${rec.messages.get()} mensajes")
                assertTrue(rec.messages.get() >= 10)
                onMain { plot.openBagForTest(f2) }
                val loaded = SystemClock.uptimeMillis() + 10_000
                while (onMain { plot.bagForTest() } == null && SystemClock.uptimeMillis() < loaded) SystemClock.sleep(100)
                val b = onMain { plot.bagForTest() }
                assertNotNull("no se cargó el bag", b)
                val inBag = onMain { plot.bufferSizeForTest(pct.id) }
                Log.i(TAG, "bag cargado: $inBag muestras, ${(b!!.endNs - b.startNs) / 1e9} s")
                assertTrue(inBag >= 10)
                assertTrue(b.endNs - b.startNs in 2_000_000_000L..6_000_000_000L)
                onMain { plot.seekForTest(0.5) }
                val c = onMain { plot.viewportForTest().cursorNs }!!
                assertEquals((b.startNs + b.endNs) / 2.0, c.toDouble(), 2e8)
                assertNotNull(onMain { plot.valueAtForTest(pct.id, c) })
                shot("3_bag")
                onMain { plot.goLiveForTest() }
                assertEquals(null, onMain { plot.bagForTest() })
            }
        } finally {
            bag?.delete()
            TabStore.get().remove(tab.id)
        }
    }

    @Test
    fun filtersFormulasHeaderStampAndMeasure() {
        val t = "/rviz2mini_test/battery"; val ty = "sensor_msgs/msg/BatteryState"
        val pct = SeriesSpec(id = "pct", topic = t, type = ty, field = "percentage", label = "pct")
        val volt = SeriesSpec(id = "volt", topic = t, type = ty, field = "{location==base}.voltage", label = "V")
        val none = SeriesSpec(id = "none", topic = t, type = ty, field = "{location==arm}.voltage", label = "arm")
        val cell = SeriesSpec(id = "cell", topic = t, type = ty, field = "cell_voltage[-1]", label = "last cell")
        val k = SeriesSpec(id = "k", formula = "{V} - 4 * {pct}", label = "k")
        val layout = PlotLayout(listOf(PanelSpec(series = listOf(pct, volt, none, cell, k))), windowS = 10.0)
        val tab = TabSpec.create(TabType.PLOT, "Plot paths").withSetting(PlotFragment.KEY_LAYOUT, layout.toJson())
        TabStore.get().add(tab)
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
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (onMain { plot.bufferSizeForTest(k.id) } < 10 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200)
                Log.i(TAG, "rutas: pct=${onMain { plot.bufferSizeForTest(pct.id) }} V=${onMain { plot.bufferSizeForTest(volt.id) }} " +
                    "arm=${onMain { plot.bufferSizeForTest(none.id) }} cell=${onMain { plot.bufferSizeForTest(cell.id) }} k=${onMain { plot.bufferSizeForTest(k.id) }} ${onMain { plot.errorsForTest() }}")
                assertTrue(onMain { plot.errorsForTest() }.isEmpty())
                assertTrue(onMain { plot.bufferSizeForTest(volt.id) } >= 10)
                assertEquals("un filtro que no cumple nadie", 0, onMain { plot.bufferSizeForTest(none.id) })
                val now = System.currentTimeMillis() * 1_000_000L
                assertEquals(4.2, onMain { plot.valueAtForTest(cell.id, now) }!!, 1e-4)
                // V = 22 + 4·pct, so the formula always gives 22.
                assertEquals(22.0, onMain { plot.valueAtForTest(k.id, now) }!!, 1e-3)
                assertTrue(onMain { plot.bufferSizeForTest(k.id) } >= 10)

                // Measurement with two cursors, 2 s apart.
                onMain {
                    val v = plot.viewportForTest()
                    v.follow = false
                    v.cursorNs = v.endNs - 4_000_000_000L; v.cursorBNs = v.endNs - 2_000_000_000L
                    plot.onViewportChanged()
                }
                shot("3_medida")

                // header.stamp time: the PC clock (may not match the phone's); it follows the latest data.
                onMain { plot.setHeaderStampForTest(true) }
                val stamped = SystemClock.uptimeMillis() + 10_000
                while (onMain { plot.bufferSizeForTest(pct.id) } < 10 && SystemClock.uptimeMillis() < stamped) SystemClock.sleep(200)
                SystemClock.sleep(300)
                val (end, latest) = onMain { plot.viewportForTest().endNs to plot.latestForTest() }
                Log.i(TAG, "header.stamp: fin=$end último=$latest (móvil ${System.currentTimeMillis() * 1_000_000L})")
                assertTrue(onMain { plot.layoutForTest().headerStamp })
                assertTrue(onMain { plot.bufferSizeForTest(pct.id) } >= 10)
                assertTrue("la ventana no sigue al último dato", latest != null && Math.abs(end - latest) < 500_000_000L)
                assertEquals(22.0, onMain { plot.valueAtForTest(k.id, latest!!) }!!, 1e-3)
                shot("4_header_stamp")
            }
        } finally {
            TabStore.get().remove(tab.id)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var r: T? = null
        instrumentation.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val dir = File(context.getExternalFilesDir(null), "plot").apply { mkdirs() }
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
    }

    private companion object {
        const val TAG = "PlotPc"
    }
}
