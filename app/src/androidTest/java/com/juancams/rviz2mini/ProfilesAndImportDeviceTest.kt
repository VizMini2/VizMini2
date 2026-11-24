// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.profiles.ProfileStore
import com.juancams.rviz2mini.profiles.RobotProfile
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.ViewType
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.StatusLevel
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
 * Robot profiles (save, load another one with other tabs and another connection, go back) and importing
 * a .rviz into the profile's RViz tab, with the PC's fake robot (domain 77) providing data.
 * Leaves the user's tabs, connection and profiles as they were.
 */
@RunWith(AndroidJUnit4::class)
class ProfilesAndImportDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val saved = HashMap<String, Map<String, *>>()
    private lateinit var dir: File

    @Before
    fun setUp() {
        for (p in PREFS) saved[p] = prefs(p).all.toMap()
        dir = File(context.getExternalFilesDir(null), "profiles").apply { deleteRecursively(); mkdirs() }
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
    fun saveLoadProfilesAndImportRvizFile() {
        val originalTabs = TabStore.get().all.map { it.name }
        val robotB = RobotProfile(
            "Prueba B", RosConfig(77, "rviz2mini_profile_test"),
            listOf(TabSpec.create(TabType.RVIZ, "Robot B"), TabSpec.create(TabType.TELEOP_DUAL, "Mando B"))
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, "config")
            val config = onMainGet { scenario.fragment<ConfigFragment>("config") }
            val originalRos = RosConnection.config // the saved one, already loaded by MainActivity

            // 1) Current state as profile "Prueba A"; "Prueba B" as if it had been imported.
            onMain { config.saveProfileForTest("Prueba A"); ProfileStore.get().save(robotB) }
            assertEquals(originalTabs, ProfileStore.get().get("Prueba A")!!.tabs.map { it.name })

            // 2) Load B: another connection and other tabs.
            onMain { config.loadProfile(robotB) }
            instrumentation.waitForIdleSync()
            assertEquals(listOf("home", "config", "Robot B", "Mando B"), tabNames(scenario))
            assertEquals(77, RosConnection.config.domainId)
            assertEquals("Prueba B", ProfileStore.get().activeName)
            shot("01_perfil_B_config")

            // 3) Import a .rviz into the profile's RViz tab.
            val rvizTab = TabStore.get().all.first { it.name == "Robot B" }
            select(scenario, rvizTab.id)
            val rviz = rviz(scenario)
            onMain { rviz.importRvizForTest(RVIZ) }
            val types = onMainGet { rviz.displayConfigs().map { it.type } }
            assertEquals(listOf(DisplayType.GRID, DisplayType.TF, DisplayType.MAP, DisplayType.LASER_SCAN, DisplayType.ROBOT_MODEL, DisplayType.IMAGE), types)
            val scan = onMainGet { rviz.displayConfigs().first { it.type == DisplayType.LASER_SCAN } }
            assertEquals(QosPreset.SENSOR, scan.qos())
            assertEquals("flat", scan.string("colorMode"))
            assertEquals(ViewType.TOP_DOWN, onMainGet { rviz.viewForTest().type })
            val settings = TabStore.get().get(rvizTab.id)!!.settings
            assertEquals("map", settings["fixedFrame"])
            assertEquals("/rviz2mini_test/goal", settings["goalTopic"])

            // With the fake robot the imported displays receive data.
            val failures = ArrayList<String>()
            for (t in listOf(DisplayType.MAP, DisplayType.LASER_SCAN, DisplayType.TF)) {
                val id = onMainGet { rviz.displayConfigs().first { it.type == t }.id }
                val deadline = SystemClock.uptimeMillis() + 20_000
                var level: StatusLevel? = null
                while (SystemClock.uptimeMillis() < deadline) {
                    level = onMainGet { rviz.displayStatus(id)?.level }
                    if (level == StatusLevel.OK) break
                    SystemClock.sleep(200)
                }
                if (level != StatusLevel.OK) failures += "$t: ${onMainGet { rviz.displayStatus(id) }}"
            }
            assertEquals("map", onMainGet { rviz.currentFixedFrame() })
            SystemClock.sleep(1500)
            shot("02_rviz_importado")
            assertTrue("Sin datos:\n" + failures.joinToString("\n"), failures.isEmpty())

            // 4) Back to profile A: original tabs and connection.
            select(scenario, "config")
            onMain { config.loadProfile(ProfileStore.get().get("Prueba A")!!) }
            instrumentation.waitForIdleSync()
            // The tab bar does not show the dashboards' widgets (if the phone has one, e.g. the demo).
            assertEquals(listOf("home", "config") + TabStore.get().all.filter { !it.isWidget }.map { it.name }, tabNames(scenario))
            assertEquals(originalTabs, TabStore.get().all.map { it.name })
            assertEquals(originalRos, RosConnection.config)
            shot("03_perfil_A")
        }
    }

    private fun tabNames(scenario: ActivityScenario<MainActivity>): List<String> = onMainGet {
        var names = listOf<String>()
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            names = (0 until tabs.tabCount).map { i -> tabs.getTabAt(i)!!.let { t -> if (t.tag == "config" || t.tag == "home") t.tag as String else t.text.toString() } }
        }
        names
    }

    private inline fun <reified T> ActivityScenario<MainActivity>.fragment(tag: String): T {
        var f: T? = null
        onActivity { a -> f = a.supportFragmentManager.findFragmentByTag(tag) as T }
        return f!!
    }

    private fun rviz(scenario: ActivityScenario<MainActivity>): RvizFragment {
        var f: RvizFragment? = null
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
        val PREFS = listOf("rviz2mini_tabs", "rviz2mini_ros", "rviz2mini_profiles")

        val RVIZ = """
            Visualization Manager:
              Class: ""
              Displays:
                - Class: rviz_default_plugins/Grid
                  Name: Grid
                  Cell Size: 1
                  Plane Cell Count: 20
                  Color: 120; 120; 120
                  Value: true
                - Class: rviz_default_plugins/TF
                  Name: TF
                  Show Names: true
                  Value: true
                - Class: rviz_default_plugins/Map
                  Name: Mapa
                  Alpha: 0.6
                  Color Scheme: map
                  Topic:
                    Durability Policy: Transient Local
                    Reliability Policy: Reliable
                    Value: /map
                  Value: true
                - Class: rviz_default_plugins/LaserScan
                  Name: Láser
                  Color: 255; 0; 0
                  Color Transformer: FlatColor
                  Size (m): 0.06
                  Style: Spheres
                  Topic:
                    Reliability Policy: Best Effort
                    Value: /scan
                  Value: true
                - Class: rviz_default_plugins/RobotModel
                  Name: Robot
                  Description Source: Topic
                  Description Topic:
                    Durability Policy: Transient Local
                    Value: /robot_description
                  Value: true
                - Class: rviz_default_plugins/Image
                  Name: Cámara
                  Value: true
              Global Options:
                Background Color: 30; 30; 40
                Fixed Frame: map
              Tools:
                - Class: rviz_default_plugins/SetGoal
                  Topic:
                    Value: /rviz2mini_test/goal
              Views:
                Current:
                  Class: rviz_default_plugins/TopDownOrtho
                  Scale: 60
                  X: 0
                  Y: 0
                  Angle: 0
        """.trimIndent()
    }
}
