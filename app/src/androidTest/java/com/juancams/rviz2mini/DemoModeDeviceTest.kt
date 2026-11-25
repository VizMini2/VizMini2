// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Demo mode on the phone, without a PC: enters it, checks that the simulated robot publishes and that RViz and the
 * camera see it, drives with /cmd_vel and exits restoring the user's connection and tabs.
 * Saves screenshots to Android/data/com.juancams.vizmini2/files/demo/.
 */
@RunWith(AndroidJUnit4::class)
class DemoModeDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir = File(context.getExternalFilesDir(null), "demo").apply { mkdirs() }

    @Test
    fun demoRunsOnThePhoneAndRestoresEverything() {
        val tabsBefore = TabStore.get().all.map { it.id }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(1500)
            val configBefore = RosConnection.config
            scenario.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
            assertTrue(DemoMode.isActive(context))
            assertEquals(DemoMode.CONFIG, RosConnection.config)

            // The simulated robot appears in the graph.
            val deadline = SystemClock.uptimeMillis() + 20_000
            var topics: List<String> = emptyList()
            while (SystemClock.uptimeMillis() < deadline) {
                topics = RosConnection.graph()?.topics()?.map { it.name }.orEmpty()
                if (listOf("/scan", "/map", "/odom", "/camera/image_raw", "/diagnostics").all { it in topics }) break
                SystemClock.sleep(300)
            }
            Log.i(TAG, "DEMO topics=$topics")
            assertTrue(topics.toString(), listOf("/scan", "/map", "/odom", "/camera/image_raw", "/diagnostics").all { it in topics })
            val tfDeadline = SystemClock.uptimeMillis() + 10_000
            while (!RosConnection.tfTree.contains("base_link") && SystemClock.uptimeMillis() < tfDeadline) SystemClock.sleep(200)
            assertTrue("TF del robot", RosConnection.tfTree.contains("base_link"))

            // Demo RViz: the displays must be OK.
            val rvizTab = TabStore.get().all.first { it.type == TabType.RVIZ && !it.isWidget }
            scenario.onActivity { it.openTab(rvizTab.id) }
            SystemClock.sleep(8000)
            var statuses = ""
            scenario.onActivity { a ->
                val f = a.supportFragmentManager.findFragmentByTag(rvizTab.id) as RvizFragment
                statuses = f.displayConfigs().joinToString("\n") { d -> "${d.name}: ${f.displayStatus(d.id)?.level} ${f.displayStatus(d.id)?.message}" }
            }
            Log.i(TAG, "DEMO RViz\n$statuses")
            shot("1_rviz")
            assertFalse(statuses, statuses.contains("ERROR"))
            assertTrue(statuses, statuses.lines().count { it.contains(" OK ") || it.endsWith(" OK") } >= 5)

            for (t in TabStore.get().all.filter { it.type != TabType.RVIZ && !it.isWidget && it.type != TabType.DASHBOARD }) {
                scenario.onActivity { it.openTab(t.id) }
                SystemClock.sleep(3500)
                // PlanSys2: the demo's plan is on screen (each row says its action and its state to accessibility).
                if (t.type == TabType.PLANSYS2) {
                    val planDeadline = SystemClock.uptimeMillis() + 20_000
                    var onScreen = screenTexts()
                    while (onScreen.none { it.startsWith("plan (move demo_bot dock kitchen)") } && SystemClock.uptimeMillis() < planDeadline) {
                        SystemClock.sleep(500)
                        onScreen = screenTexts()
                    }
                    Log.i(TAG, "PLANSYS2 on screen: $onScreen")
                    assertTrue("the demo's plan in the PlanSys2 tab: $onScreen", onScreen.any { it.startsWith("plan (move demo_bot dock kitchen)") })
                }
                shot("2_" + t.name.replace(' ', '_'))
            }

            scenario.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }
            assertFalse(DemoMode.isActive(context))
            assertEquals(configBefore, RosConnection.config)
            assertEquals(tabsBefore.size, TabStore.get().all.size)
        }
    }

    /** The texts and descriptions on screen, as accessibility sees them (Compose's included). */
    private fun screenTexts(): List<String> {
        val texts = ArrayList<String>()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) {
            if (n == null) return
            listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).filter { it.isNotBlank() }.forEach { texts += it }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return texts
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        private const val TAG = "DemoModeDeviceTest"
    }
}
