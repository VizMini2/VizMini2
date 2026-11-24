// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.explore.MessageFragment
import com.juancams.rviz2mini.params.ParamModel
import com.juancams.rviz2mini.params.ParamsFragment
import com.juancams.rviz2mini.plot.PanelKind
import com.juancams.rviz2mini.plot.PanelSpec
import com.juancams.rviz2mini.plot.PlotFragment
import com.juancams.rviz2mini.plot.PlotLayout
import com.juancams.rviz2mini.plot.SeriesSpec
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
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

/**
 * Message and Parameters tabs and the Plot status panel against the PC (tools/pc_tests.sh
 * PanelsPcTest: fake_robot.py, parameter_blackboard and indicators_test_pub.py).
 */
@RunWith(AndroidJUnit4::class)
class PanelsPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_panels", discoveryPeers = TestArgs.peers))
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
    fun messageTreeWithPaths() {
        val tab = TabSpec.create(TabType.MESSAGE, "Msg test").withSetting(MessageFragment.KEY_TOPIC, "/odom").withSetting(MessageFragment.KEY_TYPE, "nav_msgs/msg/Odometry")
        withTab<MessageFragment>(tab) { msg ->
            waitFor("árbol de /odom") { onMain { msg.treeTextForTest() }.any { it.contains("pose  {PoseWithCovariance}") } }
            val tree = onMain { msg.treeTextForTest() }
            Log.i(TAG, "árbol: ${tree.take(12)}")
            assertTrue(tree.any { it == "child_frame_id: base_link" })
            assertTrue(tree.any { it.startsWith("▾ header") })

            onMain { msg.setPathForTest("pose.pose.position") }
            waitFor("ruta pose.pose.position") { onMain { msg.treeTextForTest() }.map { it.substringBefore(':') } == listOf("x", "y", "z") }
            shot("message.png")

            onMain { msg.setPathForTest("{header.frame_id==odom}.child_frame_id") }
            waitFor("filtro que cumple") { onMain { msg.treeTextForTest() }.singleOrNull()?.endsWith(": base_link") == true }
            onMain { msg.setPathForTest("{header.frame_id==map}.child_frame_id") }
            waitFor("filtro que no cumple") { onMain { msg.treeTextForTest() }.singleOrNull()?.contains("{header.frame_id==map}") == true }

            // Paused: does not change even if messages arrive.
            onMain { msg.setPathForTest("header.stamp"); msg.setPausedForTest(true) }
            SystemClock.sleep(600)
            val frozen = onMain { msg.treeTextForTest() }
            val n = onMain { msg.receivedForTest() }
            SystemClock.sleep(1000)
            assertTrue(onMain { msg.receivedForTest() } > n)
            assertEquals(frozen, onMain { msg.treeTextForTest() })
        }
    }

    @Test
    fun parametersTabReadsAndSets() {
        val node = "/rviz2mini_test/parameter_blackboard"
        val tab = TabSpec.create(TabType.PARAMETERS, "Params test").withSetting(ParamsFragment.KEY_NODE, node)
        withTab<ParamsFragment>(tab) { p ->
            fun speed() = onMain { p.controllerForTest()?.entries }?.firstOrNull { it.descriptor.name == "speed" }?.value?.value
            waitFor("parámetros de $node") { speed() != null }
            Log.i(TAG, "speed = ${speed()}")
            assertEquals(1.5, speed() as Double, 1e-9)
            kotlinx.coroutines.runBlocking { p.controllerForTest()!!.apply("speed", ParamModel.Value(ParamModel.Type.DOUBLE, 2.5)) }
            waitFor("speed = 2.5") { speed() == 2.5 }
            shot("parameters.png")
            kotlinx.coroutines.runBlocking { p.controllerForTest()!!.apply("speed", ParamModel.Value(ParamModel.Type.DOUBLE, 1.5)) }
            waitFor("speed = 1.5") { speed() == 1.5 }
            // A change made by someone else (here, another client): arrives via /parameter_events without reloading.
            val other = com.juancams.rviz2mini.params.ParamClient(node, com.juancams.rviz2mini.ros.dyn.Interfaces.get())
            try {
                assertEquals(null, other.set("speed", ParamModel.Value(ParamModel.Type.DOUBLE, 3.25)))
                waitFor("speed = 3.25 por /parameter_events") { speed() == 3.25 }
                other.set("speed", ParamModel.Value(ParamModel.Type.DOUBLE, 1.5))
            } finally { other.close() }
        }
    }

    @Test
    fun stateTransitionsPanelWithText() {
        val t = "/rviz2mini_test/battery"; val ty = "sensor_msgs/msg/BatteryState"
        val location = SeriesSpec(id = "loc", topic = t, type = ty, field = "location", label = "location")
        val charging = SeriesSpec(id = "st", topic = t, type = ty, field = "power_supply_status", label = "status")
        val pct = SeriesSpec(id = "pct", topic = t, type = ty, field = "percentage", label = "pct")
        val layout = PlotLayout(listOf(PanelSpec(kind = PanelKind.STATES, series = listOf(location, charging)), PanelSpec(series = listOf(pct))), windowS = 10.0)
        val tab = TabSpec.create(TabType.PLOT, "States test").withSetting(PlotFragment.KEY_LAYOUT, layout.toJson())
        withTab<PlotFragment>(tab) { plot ->
            waitFor("muestras de texto") { onMain { plot.bufferSizeForTest("loc") } >= 5 }
            val now = System.currentTimeMillis() * 1_000_000L
            // "base" is the first text: code 0.
            assertEquals(0.0, onMain { plot.valueAtForTest("loc", now) }!!, 0.0)
            assertEquals(listOf("base"), onMain { plot.textLabelsForTest()["loc"] })
            assertEquals(2.0, onMain { plot.valueAtForTest("st", now) }!!, 0.0) // DISCHARGING
            assertTrue(onMain { plot.errorsForTest() }.isEmpty())
            shot("states.png")
        }
    }

    private inline fun <reified F : Fragment> withTab(tab: TabSpec, block: (F) -> Unit) {
        TabStore.get().add(tab)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                    (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
                }
                instrumentation.waitForIdleSync()
                var f: F? = null
                scenario.onActivity { a -> f = a.supportFragmentManager.findFragmentByTag(tab.id) as F }
                block(f!!)
            }
        } finally {
            TabStore.get().remove(tab.id)
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!ok()) {
            assertTrue("no llega: $what", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(200)
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
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private companion object {
        const val TAG = "PanelsPc"
    }
}
