// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.profiles.ProfileStore
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks through the app tapping like a user (see [Ui]): Home, tour, changelog, demo, each tab,
 * "+" catalog, graph, bags, buttons, lifecycle, Config (profiles, QR, alerts) and renaming/deleting
 * tabs. It uses demo mode, so it does not need the PC. Screenshots in files/ui/.
 */
@RunWith(AndroidJUnit4::class)
class UiWalkthroughDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "ui").apply { deleteRecursively(); mkdirs() }
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null
    private val profileName = "UI test profile"

    @Before
    fun setUp() {
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ProfileStore.get().delete(profileName) }
        // If the test fails inside the demo, the simulated robot would keep publishing and the following tests
        // would receive its data: really exit (stops the robot) and then delete whatever is left.
        if (DemoMode.isActive(context)) InstrumentationRegistry.getInstrumentation().runOnMainSync { DemoMode.exit(context, reconnect = false) {} }
        if (DemoMode.isActive(context)) prefs("rviz2mini_demo").edit().clear().commit()
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply { savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } } }.commit()
    }

    private fun prefs(n: String) = context.getSharedPreferences(n, Context.MODE_PRIVATE)

    @Test
    fun walkThroughTheApp() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // ---- Home, tour and changelog
            Ui.click("Home")
            Ui.waitFor("On the network")
            Ui.click("Quick tour")
            Ui.waitFor("Welcome to VizMini2")
            repeat(7) { Ui.click("Next") }
            Ui.waitFor("Try it without a robot")
            Ui.shot(dir, "01_tour_last")
            Ui.click("Get started")
            Ui.click("Home")
            Ui.scrollToTop()
            Ui.click("Changelog")
            Ui.waitFor("Latest")
            Ui.back()

            // ---- Demo mode: enter and open its RViz
            Ui.click("Try demo")
            Ui.click("Start demo")
            Ui.waitFor("Demo RViz")
            SystemClock.sleep(6000)
            Ui.shot(dir, "02_demo_rviz")
            Ui.click("Add")                         // add display sheet
            Ui.waitFor("Grid")
            Ui.back()

            // ---- Each demo tab
            Ui.click("Camera"); Ui.waitFor("/camera/image_raw"); Ui.shot(dir, "03_camera")
            Ui.click("Diagnostics")
            for (mode in listOf("Log", "Diagnostics", "Echo", "Plot")) Ui.click(mode)
            Ui.click("Diagnostics"); Ui.waitFor("demo_robot: Battery")
            Ui.click("Drive"); Ui.waitFor("STOP"); Ui.click("STOP")
            Ui.click("Settings"); Ui.waitFor("Teleop settings"); Ui.click("Cancel")
            Ui.click("Topics"); Ui.click("/odom"); Ui.waitFor(" Hz"); Ui.shot(dir, "04_topic"); Ui.back()

            // ---- Graph and TF tree from Home
            Ui.click("Home"); Ui.scrollToTop()
            // Both open the same screen (Network graph) in its tab. It is left with the bar button:
            // the global back gesture is sometimes lost on MIUI right after opening it.
            Ui.click("TF tree"); Ui.waitFor("frames"); Ui.shot(dir, "05_tf"); Ui.click("Back")
            Ui.waitFor("On the network")
            Ui.click("Node graph"); Ui.waitFor("Nodes & topics"); Ui.click("Back")
            Ui.waitFor("On the network")

            // ---- "+" catalog: a Bags tab, record, play and delete
            Ui.click("New tab")
            Ui.waitFor("Choose what you want to add")
            Ui.shot(dir, "06_catalog")
            Ui.click("Bags")
            Ui.waitFor("What it does")
            Ui.click("Create")
            Ui.waitFor("Recordings")
            Ui.click("Record")
            Ui.waitFor("Record topics")
            Ui.click("All")
            Ui.click("Record")
            SystemClock.sleep(4000)
            Ui.waitFor("messages")
            Ui.shot(dir, "07_recording")
            Ui.click("Stop")
            Ui.waitFor(".mcap", 15_000)
            Ui.click("Play")
            Ui.waitFor("Pause", 10_000)
            Ui.shot(dir, "08_playing")
            Ui.click("Stop")
            Ui.click("Delete")
            Ui.click("Delete")                      // confirmation

            // ---- Buttons: the editor opens
            Ui.click("New tab"); Ui.click("Buttons"); Ui.click("Create")
            Ui.click("Add button"); Ui.waitFor("New button"); Ui.shot(dir, "09_button_editor"); Ui.click("Cancel")

            // ---- Lifecycle: the add nodes dialog
            Ui.click("New tab"); Ui.click("Lifecycle"); Ui.click("Create")
            Ui.click("Add node"); SystemClock.sleep(2500)
            Ui.shot(dir, "10_lifecycle_add")
            Ui.back()

            // ---- Config: profiles (save and load), QR and alerts
            Ui.click("Config")
            Ui.click("Save as…")
            Ui.type("e.g. TurtleBot 4", profileName)
            Ui.click("Save")
            Ui.waitFor("Profile: $profileName")
            Ui.click("Load…")
            Ui.click(profileName, exact = false)
            Ui.waitFor("Reconnects on domain", exact = false)
            Ui.click("Cancel")
            Ui.click("Share as QR")
            Ui.click("Current setup (this phone)")
            Ui.waitFor("Connection only")
            Ui.click("Connection only")
            Ui.shot(dir, "11_qr")
            Ui.click("Done")
            Ui.waitFor("Robot alerts")
            Ui.shot(dir, "12_config")

            // ---- Rename and delete a tab with a long press
            Ui.longClick("Bags")
            Ui.click("Rename")
            Ui.type("Bags", "My bags")
            Ui.click("Save")
            Ui.waitFor("My bags")
            Ui.longClick("My bags")
            Ui.click("Delete")
            Ui.click("Delete")
            assertTrue("Pestaña borrada", !Ui.exists("My bags", exact = true))

            // ---- Leaving the demo restores the connection
            Ui.click("Home"); Ui.scrollToTop()
            Ui.click("Exit demo")
            SystemClock.sleep(1500)
            assertTrue(!DemoMode.isActive(context))
            assertEquals(savedRos?.get("domainId") ?: 0, RosConnection.config.domainId)
        }
    }
}
