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
import com.juancams.rviz2mini.indicators.IndicatorKind
import com.juancams.rviz2mini.indicators.IndicatorList
import com.juancams.rviz2mini.indicators.IndicatorSpec
import com.juancams.rviz2mini.indicators.IndicatorsFragment
import com.juancams.rviz2mini.indicators.LightRule
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.rviz.OpenDialogs
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Indicators tab against ROS 2 on the PC. Optional (tools/pc_tests.sh IndicatorsPcTest), with
 * `python3 tools/indicators_test_pub.py` on the PC (ROS_DOMAIN_ID=77). The app also publishes its own
 * Float64 and then stops, for the "no data" notice. Screenshots in
 * Android/data/com.juancams.vizmini2/files/indicators/.
 */
@RunWith(AndroidJUnit4::class)
class IndicatorsPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val registry by lazy { Interfaces.get() }
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
    fun everyKindShowsLiveValuesAndGoesStale() {
        val battery = "/rviz2mini_test/battery"
        val bType = "sensor_msgs/msg/BatteryState"
        val gauge = IndicatorSpec(label = "Batería", topic = battery, type = bType, field = "percentage", kind = IndicatorKind.GAUGE, min = 0.0, max = 1.0)
        val bar = IndicatorSpec(label = "Tensión", topic = battery, type = bType, field = "voltage", kind = IndicatorKind.BAR, min = 20.0, max = 28.0, unit = "V", decimals = 1)
        val light = IndicatorSpec(label = "Baja", topic = battery, type = bType, field = "percentage", kind = IndicatorKind.LIGHT, rule = LightRule.BELOW, threshold = "0.5", onColor = 3)
        val number = IndicatorSpec(label = "Celda 3", topic = battery, type = bType, field = "cell_voltage[2]", kind = IndicatorKind.NUMBER, unit = "V")
        val text = IndicatorSpec(label = "Dónde", topic = battery, type = bType, field = "location", kind = IndicatorKind.TEXT)
        val table = IndicatorSpec(label = "Todo", topic = battery, type = bType, field = "", kind = IndicatorKind.TABLE)
        val local = IndicatorSpec(label = "Local", topic = "/rviz2mini_test/indicator_local", type = "std_msgs/msg/Float64", field = "data",
            kind = IndicatorKind.NUMBER, decimals = 2, timeoutS = 1.0)
        val broken = IndicatorSpec(label = "Roto", topic = battery, type = bType, field = "no_existe", kind = IndicatorKind.NUMBER)
        val all = listOf(gauge, bar, light, number, text, local, table, broken)
        val tab = TabSpec.create(TabType.INDICATORS, "Indicadores test").withSetting(IndicatorsFragment.KEY_INDICATORS, IndicatorList.toJson(all))
        TabStore.get().add(tab)
        val pub = DynRos.publisher("/rviz2mini_test/indicator_local", registry.msg("std_msgs/msg/Float64"), RosConnection.defaultQos())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                select(scenario, tab.id)
                var f: IndicatorsFragment? = null
                scenario.onActivity { a -> f = a.supportFragmentManager.findFragmentByTag(tab.id) as IndicatorsFragment }
                val frag = f!!
                assertTrue(onMainGet { frag.setupErrorForTest(broken.id) }!!.contains("no_existe"))
                assertNull(onMainGet { frag.setupErrorForTest(gauge.id) })

                // From the PC: it reaches all the battery indicators.
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < deadline && listOf(gauge, table).any { id -> onMainGet { frag.receivedForTest(id.id) } == 0L }) SystemClock.sleep(200)
                SystemClock.sleep(600)
                val g = onMainGet { frag.valueTextForTest(gauge.id) }
                val n = onMainGet { frag.valueTextForTest(number.id) }
                val b = onMainGet { frag.valueTextForTest(bar.id) }
                val t = onMainGet { frag.valueTextForTest(text.id) }
                val rows = onMainGet { frag.tableRowsForTest(table.id) }
                Log.i(TAG, "aguja=$g barra=$b número=$n texto=$t filas=$rows")
                assertTrue("aguja: $g", g!!.toDouble() in 0.0..1.0)
                assertTrue("barra: $b", b!!.endsWith(" V") && b.removeSuffix(" V").toDouble() in 22.0..26.0)
                assertEquals("3.90 V", n)
                assertEquals("base", t)
                assertTrue("filas: $rows", rows >= 15)
                assertEquals("", onMainGet { frag.stateTextForTest(gauge.id) })

                // What the app publishes: value and, when it stops arriving, the no-data notice.
                assertEquals(context.getString(R.string.indicators_waiting), onMainGet { frag.stateTextForTest(local.id) })
                val until = SystemClock.uptimeMillis() + 2000
                while (SystemClock.uptimeMillis() < until) { pub.publish(MsgValue().apply { put("data", 7.25) }); SystemClock.sleep(100) }
                SystemClock.sleep(300)
                assertEquals("7.25", onMainGet { frag.valueTextForTest(local.id) })
                assertEquals("", onMainGet { frag.stateTextForTest(local.id) })
                shot("1_indicadores")
                SystemClock.sleep(1800)
                val stale = onMainGet { frag.stateTextForTest(local.id) }
                Log.i(TAG, "sin datos: $stale")
                assertTrue("sin aviso de sin datos: $stale", stale!!.isNotEmpty() && stale != context.getString(R.string.indicators_waiting))

                // The editor, with an existing one and a new one.
                instrumentation.runOnMainSync { frag.editForTest(light.id) }
                shot("2_editor")
                instrumentation.runOnMainSync { OpenDialogs.dismissLast() }
                instrumentation.runOnMainSync { frag.editForTest(null) }
                shot("3_editor_nuevo")
                instrumentation.runOnMainSync { OpenDialogs.dismissLast() }
                assertNotNull(TabStore.get().get(tab.id))
            }
        } finally {
            pub.close()
            TabStore.get().remove(tab.id)
        }
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
        instrumentation.waitForIdleSync()
    }

    private fun <T> onMainGet(block: () -> T): T {
        var r: T? = null
        instrumentation.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
        val dir = java.io.File(context.getExternalFilesDir(null), "indicators").apply { mkdirs() }
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
    }

    private companion object {
        const val TAG = "IndicatorsPc"
    }
}
