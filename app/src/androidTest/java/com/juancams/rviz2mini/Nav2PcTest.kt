// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.ViewType
import com.juancams.rviz2mini.rviz.display.DisplayList
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
 * Nav2 route with the Waypoints tool against `tools/nav2_waypoints_server.py` on the PC (domain 77, `-e pc
 * true`): two waypoints with gestures in 3D, Go, and the bar shows the progress and "Succeeded". Then, passing through them
 * (NavigateThroughPoses). Screenshots in files/nav2/.
 */
@RunWith(AndroidJUnit4::class)
class Nav2PcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir = File(context.getExternalFilesDir(null), "nav2").apply { deleteRecursively(); mkdirs() }
    private val savedTabs = TestTabs.backup(context)
    private val savedRos = prefs("rviz2mini_ros").all

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply { savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } } }.commit()
    }

    @Test
    fun waypointsRouteThroughNav2() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_nav2", discoveryPeers = TestArgs.peers))
        val tab = TabSpec.create(TabType.RVIZ, "Nav2").withSetting("displays", DisplayList.toJson(DisplayList.defaults()))
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
            }
            SystemClock.sleep(2500)
            var rviz: RvizFragment? = null
            val deadline = SystemClock.uptimeMillis() + 60_000
            do {
                var ready = false
                sc.onActivity { a ->
                    rviz = a.supportFragmentManager.fragments.filterIsInstance<RvizFragment>().first { it.isVisible }
                    ready = rviz!!.isReadyForTest
                }
                if (!ready) SystemClock.sleep(100)
            } while (!ready && SystemClock.uptimeMillis() < deadline)
            val r = rviz!!
            instrumentation.runOnMainSync { r.setViewForTest(ViewType.TOP_DOWN); r.viewForTest().apply { scale = 10.0; focal[0] = 0.0; focal[1] = 0.0; angle = 0.0 } }
            SystemClock.sleep(500)
            val surface = r.surfaceForTest()
            val w = surface.width.toFloat(); val h = surface.height.toFloat()

            instrumentation.runOnMainSync { r.setToolForTest(RvizFragment.Tool.WAYPOINTS) }
            SystemClock.sleep(300)
            gesture(surface, w * 0.3f, h * 0.6f, w * 0.3f + 120f, h * 0.6f)
            gesture(surface, w * 0.7f, h * 0.45f, w * 0.7f, h * 0.45f - 120f)
            SystemClock.sleep(800)
            val m = r.navMissionForTest()!!
            assertEquals("waypoints puestos", 2, m.waypoints.size)
            Ui.shot(dir, "1_waypoints")

            // Go: FollowWaypoints, one waypoint per second on the fake server.
            Ui.click(context.getString(R.string.nav2_go))
            var seen = ""
            val end = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < end) {
                val s = m.status.orEmpty()
                if (s.contains("Waypoint")) seen = s
                if (!m.running && s.contains("Succeeded")) break
                SystemClock.sleep(100)
            }
            Log.i(TAG, "follow: visto '$seen', final '${m.status}'")
            Ui.shot(dir, "2_follow_done")
            assertTrue("no se vio el avance: '$seen'", seen.contains("/2"))
            assertTrue("FollowWaypoints no acabó bien: ${m.status}", m.status.orEmpty().contains("Succeeded"))

            // Passing through them (NavigateThroughPoses): with remaining distance and time.
            Ui.click(context.getString(R.string.nav2_mode_through))
            Ui.click(context.getString(R.string.nav2_go))
            var progress = ""
            val end2 = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < end2) {
                val s = m.status.orEmpty()
                if (s.contains(" m")) progress = s
                if (!m.running && s.contains("Succeeded")) break
                SystemClock.sleep(100)
            }
            Log.i(TAG, "through: visto '$progress', final '${m.status}'")
            assertTrue("NavigateThroughPoses sin distancia: '$progress'", progress.contains(" m"))
            assertTrue("NavigateThroughPoses no acabó bien: ${m.status}", m.status.orEmpty().contains("Succeeded"))
            instrumentation.runOnMainSync { m.clear() }
        }
    }

    private fun gesture(view: android.view.View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) = instrumentation.runOnMainSync {
            val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            view.dispatchTouchEvent(ev); ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x0, y0)
        for (i in 1..10) { send(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10); SystemClock.sleep(20) }
        send(MotionEvent.ACTION_UP, x1, y1)
    }

    companion object {
        private const val TAG = "Nav2PcTest"
    }
}
