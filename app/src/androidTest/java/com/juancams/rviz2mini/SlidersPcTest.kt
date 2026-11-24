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
import com.juancams.rviz2mini.dashboard.DashboardFragment
import com.juancams.rviz2mini.dashboard.DashboardGrid
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.sliders.SliderList
import com.juancams.rviz2mini.sliders.SliderMode
import com.juancams.rviz2mini.sliders.SliderSpec
import com.juancams.rviz2mini.sliders.SliderState
import com.juancams.rviz2mini.sliders.SlidersFragment
import com.juancams.rviz2mini.tabs.TabList
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Sliders" tab (standalone and as a dashboard widget) publishing to ROS 2. What it publishes is
 * checked with the app's own subscriptions and, on the PC, with `ros2 topic echo`:
 *   adb shell am instrument -w -e pc true -e peers <PC IP> -e class com.juancams.rviz2mini.SlidersPcTest \
 *       com.juancams.vizmini2.test/com.juancams.rviz2mini.TestRunner
 * with, on the PC (ROS_DOMAIN_ID=77):
 *   ros2 topic echo /rviz2mini_test/slider_float std_msgs/msg/Float64
 */
@RunWith(AndroidJUnit4::class)
class SlidersPcTest {

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

    /** Values received on a topic (from the app's own node). */
    private fun listen(topic: String, type: String, pick: (Map<String, Any?>) -> Double): Pair<CopyOnWriteArrayList<Double>, DynRos.Subscription> {
        val got = CopyOnWriteArrayList<Double>()
        val sub = DynRos.subscribe(topic, registry.msg(type), RosConnection.defaultQos()) { v -> got.add(pick(v)) }
        return got to sub
    }

    @Test
    fun slidersPublishWhileDraggingOnReleaseAndSpringBack() {
        val float = SliderSpec(label = "Float", target = "/rviz2mini_test/slider_float", type = "std_msgs/msg/Float64", field = "data",
            min = 0.0, max = 10.0, step = 0.5, initial = 1.0, mode = SliderMode.WHILE_DRAGGING, rateHz = 20.0)
        val speed = SliderSpec(label = "Speed", target = "/rviz2mini_test/slider_twist", type = "geometry_msgs/msg/Twist", field = "linear.x",
            min = -0.5, max = 0.5, step = 0.05, initial = 0.0, rateHz = 10.0, springBack = true, values = """{"angular":{"z":0.3}}""")
        val level = SliderSpec(label = "Level", target = "/rviz2mini_test/slider_u8", type = "std_msgs/msg/UInt8", field = "data",
            min = 0.0, max = 255.0, step = 1.0, initial = 0.0, mode = SliderMode.ON_RELEASE)
        val broken = SliderSpec(label = "Roto", target = "/rviz2mini_test/slider_text", type = "std_msgs/msg/String", field = "data")
        val tab = TabSpec.create(TabType.SLIDERS, "Sliders test").withSetting(SlidersFragment.KEY_SLIDERS, SliderList.toJson(listOf(float, speed, level, broken)))
        TabStore.get().add(tab)

        val (floats, s1) = listen(float.target, float.type) { (it["data"] as Number).toDouble() }
        val (twists, s2) = listen(speed.target, speed.type) { m ->
            val ang = (m["angular"] as Map<*, *>)["z"] as Number
            assertEquals("el resto de campos va con sus valores", 0.3, ang.toDouble(), 1e-9)
            ((m["linear"] as Map<*, *>)["x"] as Number).toDouble()
        }
        val (levels, s3) = listen(level.target, level.type) { (it["data"] as Number).toDouble() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                wakeUp(scenario)
                select(scenario, tab.id)
                val f = fragment<SlidersFragment>(scenario, tab.id)
                assertTrue(f.setupErrorForTest(broken.id).orEmpty(), f.setupErrorForTest(broken.id)!!.contains("data"))
                SystemClock.sleep(3000) // DDS discovery

                // While dragging: ~20 Hz with the value snapped to the step (7.3 -> 7.5); on release, one more.
                instrumentation.runOnMainSync { f.dragForTest(float.id, 7.3) }
                SystemClock.sleep(1000)
                var end = 0.0
                instrumentation.runOnMainSync { end = f.releaseForTest(float.id)!! }
                assertEquals(7.5, end, 0.0)
                val done = waitDone(f, float.id)
                SystemClock.sleep(500)
                Log.i(TAG, "float: ${floats.size} mensajes, últimos ${floats.takeLast(3)}; estado $done")
                assertTrue("pocos mensajes a 20 Hz: ${floats.size}", floats.size in 12..30)
                assertTrue(floats.all { it == 7.5 })
                // "Published 7.5 · …" in the phone's language; the subscribers depend on who is listening on the PC.
                val published = context.resources.getQuantityString(R.plurals.sliders_published, 1, "7.5", 1).substringBefore('·')
                assertTrue(done.toString(), done.ok && done.text.startsWith(published))

                // Springs back: on release the initial value is published (0 = stop).
                instrumentation.runOnMainSync { f.dragForTest(speed.id, 0.33) }
                SystemClock.sleep(700)
                instrumentation.runOnMainSync { end = f.releaseForTest(speed.id)!! }
                assertEquals(0.0, end, 0.0)
                SystemClock.sleep(800)
                Log.i(TAG, "twist linear.x: $twists")
                assertTrue(twists.dropLast(1).isNotEmpty() && twists.dropLast(1).all { Math.abs(it - 0.35) < 1e-9 })
                assertEquals(0.0, twists.last(), 0.0)

                // Only on release, and integers rounded.
                instrumentation.runOnMainSync { f.dragForTest(level.id, 200.0) }
                SystemClock.sleep(600)
                assertTrue("ON_RELEASE no publica mientras se arrastra", levels.isEmpty())
                instrumentation.runOnMainSync { f.releaseForTest(level.id) }
                instrumentation.runOnMainSync { f.setExactForTest(level.id, 42.6) }
                SystemClock.sleep(800)
                Log.i(TAG, "uint8: $levels")
                assertEquals(listOf(200.0, 43.0), levels.toList())

                shot("1_sliders")
                // The editor, with an existing slider and a new one.
                instrumentation.runOnMainSync { f.editForTest(speed.id) }
                shot("2_editor")
                instrumentation.runOnMainSync { com.juancams.rviz2mini.rviz.OpenDialogs.dismissLast() }
                instrumentation.runOnMainSync { f.editForTest(null) }
                shot("3_editor_nuevo")
                instrumentation.runOnMainSync { com.juancams.rviz2mini.rviz.OpenDialogs.dismissLast() }

                // Switching tabs in the middle of a drag stops publishing and goes back to 0.
                instrumentation.runOnMainSync { f.dragForTest(speed.id, -0.4) }
                SystemClock.sleep(500)
                select(scenario, "home")
                SystemClock.sleep(800)
                val n = twists.size
                SystemClock.sleep(800)
                Log.i(TAG, "tras ocultar: ${twists.takeLast(3)}")
                assertEquals("sigue publicando con la pestaña oculta", n, twists.size)
                assertEquals(0.0, twists.last(), 0.0)
            }
        } finally {
            s1.close(); s2.close(); s3.close()
            TabStore.get().remove(tab.id)
        }
    }

    @Test
    fun sliderWorksAsDashboardWidget() {
        val float = SliderSpec(label = "Widget", target = "/rviz2mini_test/slider_float", type = "std_msgs/msg/Float64", field = "data",
            min = 0.0, max = 10.0, step = 0.0, initial = 0.0, mode = SliderMode.ON_RELEASE)
        val dash = TabSpec.create(TabType.DASHBOARD, "Dash test")
        val widget = TabSpec.create(TabType.SLIDERS, "Sliders").withSetting(SlidersFragment.KEY_SLIDERS, SliderList.toJson(listOf(float)))
            .withSetting(TabSpec.KEY_DASHBOARD, dash.id)
        // An empty buttons one, to see its empty notice.
        val buttons = TabSpec.create(TabType.BUTTONS, "Botones").withSetting(TabSpec.KEY_DASHBOARD, dash.id)
        val (w, h) = DashboardFragment.defaultSize(TabType.SLIDERS)
        val (bw, bh) = DashboardFragment.defaultSize(TabType.BUTTONS)
        val withLayout = dash.withSetting(DashboardFragment.KEY_LAYOUT, DashboardGrid.toJson(listOf(
            DashboardGrid.Item(widget.id, 0, 0, w, h), DashboardGrid.Item(buttons.id, 0, h, bw, bh))))
        TabStore.get().add(withLayout)
        TabStore.get().add(widget)
        TabStore.get().add(buttons)
        val (floats, sub) = listen(float.target, float.type) { (it["data"] as Number).toDouble() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                wakeUp(scenario)
                select(scenario, dash.id)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1500)
                var f: SlidersFragment? = null
                scenario.onActivity { a ->
                    val d = a.supportFragmentManager.findFragmentByTag(dash.id) as DashboardFragment
                    f = d.childFragmentManager.findFragmentByTag(widget.id) as? SlidersFragment
                }
                assertNotNull("el dashboard no tiene el widget de sliders", f)
                SystemClock.sleep(2000)
                instrumentation.runOnMainSync { f!!.setExactForTest(float.id, 3.21) }
                SystemClock.sleep(1500)
                Log.i(TAG, "widget: $floats")
                assertEquals(listOf(3.21), floats.toList())
                shot("dashboard_widget")

                // Inside the dashboard there is no bar with "Add": it is added with the "+" of Edit mode.
                var d: DashboardFragment? = null
                scenario.onActivity { a -> d = a.supportFragmentManager.findFragmentByTag(dash.id) as DashboardFragment }
                assertFalse("en un widget no va el botón de añadir", Ui.exists(context.getString(R.string.sliders_add), exact = true))
                instrumentation.runOnMainSync { d!!.setEditingForTest(true) }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(500)
                // The "+" are Compose ones: they are found via accessibility.
                val adds = Ui.findAll(context.getString(R.string.dashboard_add_item))
                assertEquals("un + por widget con elementos", 2, adds.size)
                shot("dashboard_editing")
                Ui.click(context.getString(R.string.dashboard_add_item))
                shot("dashboard_add_slider")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.runOnMainSync { d!!.setEditingForTest(false) }
            }
        } finally {
            sub.close()
            TabStore.get().remove(buttons.id)
            TabStore.get().remove(widget.id)
            TabStore.get().remove(dash.id)
        }
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
        instrumentation.waitForIdleSync()
    }

    /** With the screen off the activity never reaches RESUMED and tabs cannot be switched. */
    private fun wakeUp(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { a -> a.setShowWhenLocked(true); a.setTurnScreenOn(true) }
        val deadline = SystemClock.uptimeMillis() + 10_000
        var resumed = false
        while (!resumed && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { a -> resumed = a.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            if (!resumed) SystemClock.sleep(200)
        }
        assertTrue("La actividad no pasa a primer plano (¿pantalla bloqueada?)", resumed)
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
        val dir = java.io.File(context.getExternalFilesDir(null), "sliders").apply { mkdirs() }
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> fragment(scenario: ActivityScenario<MainActivity>, tag: String): T {
        var f: Any? = null
        scenario.onActivity { a -> f = a.supportFragmentManager.findFragmentByTag(tag) }
        return f as T
    }

    private fun waitDone(f: SlidersFragment, id: String): SliderState.Done {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var s: SliderState? = null
            instrumentation.runOnMainSync { s = f.stateForTest(id) }
            if (s is SliderState.Done) return s as SliderState.Done
            SystemClock.sleep(100)
        }
        throw AssertionError("El slider no ha publicado")
    }

    private companion object {
        const val TAG = "SlidersPc"
    }
}
