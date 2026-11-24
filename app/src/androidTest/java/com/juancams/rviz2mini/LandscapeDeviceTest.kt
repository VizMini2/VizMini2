// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Landscape layouts (rotating with requestedOrientation, without touching the phone's auto-rotate
 * setting): RViz with the displays column, camera, 2-joystick teleop and diagnostics. Rotating with
 * RViz open must not freeze the app (it used to recompile the materials: 5-10 s).
 */
@RunWith(AndroidJUnit4::class)
class LandscapeDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val saved = HashMap<String, Map<String, *>>()
    private lateinit var dir: File

    @Before
    fun setUp() {
        for (p in PREFS) saved[p] = prefs(p).all.toMap()
        dir = File(context.getExternalFilesDir(null), "landscape").apply { deleteRecursively(); mkdirs() }
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_land_test"))
    }

    @After
    fun tearDown() {
        RosConnection.stop()
        for (p in PREFS) prefs(p).edit().clear().apply {
            saved[p]?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v); is Boolean -> putBoolean(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun landscapeLayouts() {
        val displays = DisplayList.defaults() + listOf(DisplayType.MAP, DisplayType.LASER_SCAN, DisplayType.ROBOT_MODEL, DisplayType.PATH, DisplayType.MARKER_ARRAY)
            .map { DisplayConfig.create(it) }
        val rviz = TabSpec.create(TabType.RVIZ, "RViz land").withSetting("displays", DisplayList.toJson(displays)).withSetting("fixedFrame", "map")
        val cam = TabSpec.create(TabType.CAMERA, "Cam land")
        val dual = TabSpec.create(TabType.TELEOP_DUAL, "Dual land")
        val diag = TabSpec.create(TabType.DIAGNOSTICS, "Diag land").withSetting("mode", "DIAG")
        val store = TabStore.get()
        for (t in listOf(rviz, cam, dual, diag)) store.add(t)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, rviz.id)
            waitRvizReady(scenario)
            SystemClock.sleep(3000)
            shot("00_rviz_vertical")

            // Rotate with RViz visible: the activity is recreated and must come back right away.
            val t0 = SystemClock.uptimeMillis()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            instrumentation.waitForIdleSync()
            waitRvizReady(scenario)
            val rotateMs = SystemClock.uptimeMillis() - t0
            assertTrue("Girar con RViz tardó $rotateMs ms", rotateMs < 3000)
            SystemClock.sleep(3000)
            scenario.onActivity { a ->
                val strip = visibleRviz(a).requireView().findViewById<LinearLayout>(R.id.displayStrip)
                assertEquals("La franja de displays debería ser una columna", LinearLayout.VERTICAL, strip.orientation)
                assertEquals(displays.size + 1, strip.childCount) // + "Add"
            }
            shot("01_rviz_horizontal")

            for ((tab, name) in listOf(cam to "02_camara", dual to "03_teleop_dual", diag to "04_diagnostico")) {
                select(scenario, tab.id)
                SystemClock.sleep(2500)
                shot(name)
            }

            // Back to portrait, again with RViz.
            select(scenario, rviz.id)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            instrumentation.waitForIdleSync()
            waitRvizReady(scenario)
            scenario.onActivity { a -> assertEquals(LinearLayout.HORIZONTAL, visibleRviz(a).requireView().findViewById<LinearLayout>(R.id.displayStrip).orientation) }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    /** Hidden tabs keep their view: we have to search inside the visible RViz. */
    private fun visibleRviz(a: MainActivity) = a.supportFragmentManager.fragments.filterIsInstance<RvizFragment>().first { it.isVisible }

    private fun waitRvizReady(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { a -> ready = a.supportFragmentManager.fragments.filterIsInstance<RvizFragment>().any { it.isVisible && it.isReadyForTest } }
            if (ready) return
            SystemClock.sleep(50)
        }
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
        instrumentation.waitForIdleSync()
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val PREFS = listOf("rviz2mini_tabs", "rviz2mini_ros")
    }
}
