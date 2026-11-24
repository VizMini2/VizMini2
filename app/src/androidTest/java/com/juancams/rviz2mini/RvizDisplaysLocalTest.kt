// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.StatusLevel
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Displays UI without data (works on an emulator): add every type, open their editors,
 * enable/disable with a tap, persistence and deletion. Screenshots in files/displays_local/.
 */
@RunWith(AndroidJUnit4::class)
class RvizDisplaysLocalTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null
    private lateinit var dir: File

    @Before
    fun setUp() {
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        dir = File(context.getExternalFilesDir(null), "displays_local").apply { deleteRecursively(); mkdirs() }
        // A domain with nobody on it: only the UI is tested here.
        RosConnection.applyConfig(RosConfig(domainId = 199, nodeName = "rviz2mini_ui_test"))
    }

    @After
    fun tearDown() {
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun allDisplayTypesEditorsTogglesAndPersistence() {
        val tab = TabSpec.create(TabType.RVIZ, "RViz UI").withSetting("displays", DisplayList.toJson(DisplayList.defaults()))
        TabStore.get().add(tab)
        var addedIds = listOf<String>()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, tab.id)
            SystemClock.sleep(1500)
            var rviz = rviz(scenario)
            shot("00_vacio")

            // Add all the missing types.
            val toAdd = DisplayType.entries.filter { it != DisplayType.GRID && it != DisplayType.TF }.map { DisplayConfig.create(it) }
            onMain { for (c in toAdd) rviz.addDisplayForTest(c) }
            addedIds = toAdd.map { it.id }
            SystemClock.sleep(2500)
            assertEquals(DisplayType.entries.size, onMainGet { rviz.displayConfigs().size })
            shot("01_todos_los_chips")

            // Consistent states without data: topic ones wait for messages, Grid and Axes are OK.
            for (c in onMainGet { rviz.displayConfigs() }) {
                val s = onMainGet { rviz.displayStatus(c.id) }!!
                when {
                    c.type == DisplayType.GRID || c.type == DisplayType.AXES -> assertEquals(c.type.label, StatusLevel.OK, s.level)
                    c.type.needsTopic -> {
                        assertEquals("${c.type.label}: ${s.message}", StatusLevel.WARN, s.level)
                        assertTrue(s.message, s.message.startsWith("Waiting for ")) // messages, images, camera_info, the server...
                    }
                }
            }

            // Editor of each type (real long press on the chip).
            for ((i, c) in onMainGet { rviz.displayConfigs() }.withIndex()) {
                val chip = chipFor(scenario, c.id)
                onMain { chip.performLongClick() }
                SystemClock.sleep(700)
                if (i < 4 || c.type in listOf(DisplayType.POINT_CLOUD2, DisplayType.MAP, DisplayType.MARKER_ARRAY, DisplayType.AXES)) {
                    shot("10_editor_${c.type.label}")
                }
                onMain { com.juancams.rviz2mini.rviz.OpenDialogs.dismissLast() }
                SystemClock.sleep(400)
            }

            // Tap = disable / enable.
            val laser = onMainGet { rviz.displayConfigs().first { it.type == DisplayType.LASER_SCAN } }
            val chip = chipFor(scenario, laser.id)
            onMain { chip.performClick() }
            SystemClock.sleep(300)
            assertFalse(onMainGet { rviz.displayConfigs().first { it.id == laser.id }.enabled })
            assertEquals(StatusLevel.DISABLED, onMainGet { rviz.displayStatus(laser.id)?.level })
            onMain { chipFor(scenario, laser.id).performClick() }
            SystemClock.sleep(300)
            assertTrue(onMainGet { rviz.displayConfigs().first { it.id == laser.id }.enabled })

            // Add dialog and fixed frame dialog.
            onMain { addChip(scenario).performClick() }
            SystemClock.sleep(800)
            shot("20_anadir")
            onMain { com.juancams.rviz2mini.rviz.OpenDialogs.dismissLast() }
            SystemClock.sleep(400)
            scenario.onActivity { it.findViewById<android.view.View>(R.id.fixedFrameButton).performClick() }
            SystemClock.sleep(700)
            shot("21_fixed_frame")
            // Written by hand, a frame that is not in TF: kept as the preferred one. While there is TF the automatic one is
            // used; with no TF at all (as here, without a robot), the written one.
            val auto = onMainGet { rviz.currentFixedFrame() }
            onMain { (com.juancams.rviz2mini.rviz.OpenDialogs.last as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick() }
            SystemClock.sleep(700)
            onMain {
                val d = com.juancams.rviz2mini.rviz.OpenDialogs.last as androidx.appcompat.app.AlertDialog
                fun find(v: android.view.View): android.widget.EditText? = v as? android.widget.EditText
                    ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
                find(d.window!!.decorView)!!.setText("/robot_x/base_link")
            }
            shot("22_fixed_frame_written")
            onMain { (com.juancams.rviz2mini.rviz.OpenDialogs.last as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick() }
            SystemClock.sleep(700)
            assertEquals("robot_x/base_link", onMainGet { rviz.preferredFixedFrame() })
            assertEquals(auto.ifEmpty { "robot_x/base_link" }, onMainGet { rviz.currentFixedFrame() })
            // Back to automatic.
            scenario.onActivity { it.findViewById<android.view.View>(R.id.fixedFrameButton).performClick() }
            SystemClock.sleep(700)
            onMain { (com.juancams.rviz2mini.rviz.OpenDialogs.last as androidx.appcompat.app.AlertDialog).listView.performItemClick(null, 0, 0) }
            SystemClock.sleep(500)
            assertEquals(null, onMainGet { rviz.preferredFixedFrame() })

            // Persistence: recreate the activity and check that they are all still there.
            scenario.recreate()
            SystemClock.sleep(2000)
            select(scenario, tab.id)
            SystemClock.sleep(800)
            rviz = rviz(scenario)
            assertEquals(DisplayType.entries.size, onMainGet { rviz.displayConfigs().size })

            // Delete everything that was added.
            onMain { for (id in addedIds) rviz.removeDisplayForTest(id) }
            SystemClock.sleep(500)
            assertEquals(listOf(DisplayType.GRID, DisplayType.TF), onMainGet { rviz.displayConfigs().map { it.type } })
            shot("30_borrados")
        }
    }

    // ------------------------------------------------------------------ utilities

    private fun chipFor(scenario: ActivityScenario<MainActivity>, id: String): Chip {
        var chip: Chip? = null
        scenario.onActivity { a ->
            val strip = a.supportFragmentManager.fragments.filterIsInstance<RvizFragment>().first { it.isVisible }
                .requireView().findViewById<LinearLayout>(R.id.displayStrip)
            chip = (0 until strip.childCount).map { strip.getChildAt(it) }.filterIsInstance<Chip>().first { it.tag == id }
        }
        return chip!!
    }

    private fun addChip(scenario: ActivityScenario<MainActivity>): Chip {
        var chip: Chip? = null
        scenario.onActivity { a ->
            val strip = a.supportFragmentManager.fragments.filterIsInstance<RvizFragment>().first { it.isVisible }
                .requireView().findViewById<LinearLayout>(R.id.displayStrip)
            chip = strip.getChildAt(strip.childCount - 1) as Chip
        }
        return chip!!
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
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

    private fun select(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
        instrumentation.waitForIdleSync()
    }

    private fun rviz(scenario: ActivityScenario<MainActivity>): RvizFragment {
        var f: RvizFragment? = null
        // Wait for the viewer to be set up (materials are prepared in the background).
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (true) {
            var ready = false
            scenario.onActivity { a ->
                f = a.supportFragmentManager.fragments.filterIsInstance<RvizFragment>().first { it.isVisible }
                ready = f!!.isReadyForTest
            }
            if (ready || SystemClock.uptimeMillis() > deadline) break
            SystemClock.sleep(100)
        }
        return f!!
    }
}
