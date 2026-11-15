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
import com.juancams.rviz2mini.bag.BagSession
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rewinding without a bag against `tools/fake_robot.py` on the PC (domain 77, `-e pc true`): the 3D view keeps the latest data, the
 * ⟲ button replays it locally (isolated connection, time bar) with the laser and the map, and closing the bar returns
 * to the robot. Screenshots in files/rewind/.
 */
@RunWith(AndroidJUnit4::class)
class RewindPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir = File(context.getExternalFilesDir(null), "rewind").apply { deleteRecursively(); mkdirs() }
    private val savedTabs = TestTabs.backup(context)
    private val savedRos = prefs("rviz2mini_ros").all

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { BagSession.player?.close(); BagSession.player = null; BagSession.leaveLocal() }
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply { savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } } }.commit()
    }

    @Test
    fun rewindReplaysTheLastSecondsLocally() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        val robot = RosConfig(domainId = 77, nodeName = "rviz2mini_rewind", discoveryPeers = TestArgs.peers)
        RosConnection.applyConfig(robot)
        val laser = DisplayConfig.create(DisplayType.LASER_SCAN, "Laser", "/scan")
        val tab = TabSpec.create(TabType.RVIZ, "Rewind").withSetting("fixedFrame", "map")
            .withSetting("displays", DisplayList.toJson(DisplayList.defaults() + DisplayConfig.create(DisplayType.MAP, "Map", "/map") + laser))
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
            }
            var rviz: RvizFragment? = null
            val deadline = SystemClock.uptimeMillis() + 60_000
            do {
                var ready = false
                sc.onActivity { a ->
                    rviz = a.supportFragmentManager.fragments.filterIsInstance<RvizFragment>().firstOrNull { it.isVisible }
                    ready = rviz?.isReadyForTest == true
                }
                if (!ready) SystemClock.sleep(100)
            } while (!ready && SystemClock.uptimeMillis() < deadline)
            val r = rviz!!
            // On the phone it starts off: the button turns it on the first time.
            Ui.click(context.getString(R.string.rviz_rewind))
            SystemClock.sleep(500)
            var on = false
            instrumentation.runOnMainSync { on = r.sceneForTest()?.rewind != null }
            assertTrue("el primer toque tiene que encender el guardado", on)
            SystemClock.sleep(8000)
            var span = 0.0
            instrumentation.runOnMainSync { span = r.sceneForTest()!!.rewind!!.spanS() }
            Log.i(TAG, "guardado: %.1f s".format(span))
            assertTrue("no se guardó nada: $span s", span > 3)

            Ui.click(context.getString(R.string.rviz_rewind))
            val end = SystemClock.uptimeMillis() + 15_000
            while ((BagSession.player == null || RosConnection.status !is RosConnection.Status.Connected) && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
            assertTrue("no empezó la repetición", BagSession.player != null)
            assertEquals("la repetición va en local", BagSession.LOCAL_CONFIG, RosConnection.config)
            SystemClock.sleep(3000)
            var laserLevel: StatusLevel? = null
            instrumentation.runOnMainSync { laserLevel = r.displayStatus(laser.id)?.level }
            Ui.shot(dir, "1_replay")
            Log.i(TAG, "láser en la repetición: $laserLevel")
            assertEquals("el láser se ve en la repetición", StatusLevel.OK, laserLevel)

            // Close the bar: back to the robot.
            instrumentation.runOnMainSync { BagSession.stopPlayer() }
            val back = SystemClock.uptimeMillis() + 15_000
            while (RosConnection.config.domainId != 77 && SystemClock.uptimeMillis() < back) SystemClock.sleep(100)
            assertEquals("de vuelta al robot", 77, RosConnection.config.domainId)
            Ui.shot(dir, "2_live")
        }
    }

    companion object {
        private const val TAG = "RewindPcTest"
    }
}
