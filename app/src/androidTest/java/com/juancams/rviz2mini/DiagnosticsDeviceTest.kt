// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.diag.DiagLevel
import com.juancams.rviz2mini.diag.DiagnosticsFragment
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.display.QosPreset
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
 * Diagnostics tab against the PC's fake robot (tools/fake_robot.py, domain 77):
 * /rosout, /diagnostics, echo of /odom and plot of two fields. Screenshots in files/diagnostics/.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val saved = HashMap<String, Map<String, *>>()
    private lateinit var dir: File

    @Before
    fun setUp() {
        for (p in PREFS) saved[p] = prefs(p).all.toMap()
        dir = File(context.getExternalFilesDir(null), "diagnostics").apply { deleteRecursively(); mkdirs() }
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_diag_test"))
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
    fun logDiagnosticsEchoAndPlot() {
        val tab = TabSpec.create(TabType.DIAGNOSTICS, "Diag test")
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, tab.id)
            val f = onMainGet { var x: DiagnosticsFragment? = null; scenario.onActivity { a -> x = a.supportFragmentManager.findFragmentByTag(tab.id) as DiagnosticsFragment }; x!! }

            // /rosout: the robot writes INFO every 2 s, WARN every 5 s and ERROR every 7 s.
            onMain { f.setModeForTest(DiagnosticsFragment.Mode.LOG) }
            assertTrue("Sin logs: ${onMainGet { f.statusForTest() }}", waitFor(30_000) { onMainGet { f.logCountForTest() } >= 3 })
            SystemClock.sleep(6000)
            shot("01_log")

            // /diagnostics: three components, the ERROR one first.
            onMain { f.setModeForTest(DiagnosticsFragment.Mode.DIAG) }
            assertTrue("Sin diagnostics: ${onMainGet { f.statusForTest() }}", waitFor(20_000) { onMainGet { f.diagSnapshotForTest() }.size >= 3 })
            val snap = onMainGet { f.diagSnapshotForTest() }
            assertEquals(DiagLevel.ERROR, snap.first().level)
            assertEquals("fake_robot: Lidar", snap.first().name)
            assertTrue(snap.first { it.name == "fake_robot: Motores" }.values.any { it.first == "temperatura" })
            SystemClock.sleep(800)
            shot("02_diagnostics")

            // Echo of /odom.
            onMain { f.setModeForTest(DiagnosticsFragment.Mode.ECHO); f.setSourceForTest("/odom", "nav_msgs/msg/Odometry", QosPreset.SENSOR) }
            assertTrue("Sin eco: ${onMainGet { f.statusForTest() }}", waitFor(20_000) { onMainGet { f.echoTextForTest() }.contains("child_frame_id: base_link") })
            val echo = onMainGet { f.echoTextForTest() }
            assertTrue(echo, echo.contains("frame_id: odom") && echo.contains("twist:"))
            SystemClock.sleep(800)
            shot("03_echo")

            // Plot of the position and the angular velocity.
            onMain { f.setModeForTest(DiagnosticsFragment.Mode.PLOT); f.setPlotPathsForTest(listOf("pose.pose.position.x", "pose.pose.position.y")) }
            assertTrue("Sin puntos", waitFor(20_000) { onMainGet { f.plotPointsForTest() }.all { it >= 40 } })
            SystemClock.sleep(4000)
            shot("04_plot")

            // A map cannot be plotted: a notice is shown instead of offering its cells.
            onMain { f.setSourceForTest("/map", "nav_msgs/msg/OccupancyGrid", QosPreset.LATCHED) }
            SystemClock.sleep(500)
            val status = onMainGet { f.statusForTest() }
            assertTrue(status, status.contains("cannot be plotted"))
            shot("05_plot_map")
        }
        TabStore.get().remove(tab.id)
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (cond()) return true
            SystemClock.sleep(200)
        }
        return cond()
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> onMainGet(block: () -> T): T {
        var r: T? = null
        instrumentation.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private companion object {
        val PREFS = listOf("rviz2mini_tabs", "rviz2mini_ros")
    }
}
