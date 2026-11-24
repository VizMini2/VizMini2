// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.dashboard.DashboardFragment
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Free-form dashboard with the demo robot: the widgets work inside the board (RViz with
 * data, camera...), they are moved, resized, added and removed (also from the UI) and the layout is
 * saved. Screenshots in files/dashboard/.
 */
@RunWith(AndroidJUnit4::class)
class DashboardDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "dashboard").apply { deleteRecursively(); mkdirs() }
    private val savedTabs = TestTabs.backup(context)
    private val savedRos = prefs("rviz2mini_ros").all

    private fun prefs(n: String) = context.getSharedPreferences(n, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        // If the test fails inside the demo, the simulated robot would keep publishing and the following tests
        // would receive its data: really exit (stopping the robot) and then delete whatever is left.
        if (DemoMode.isActive(context)) InstrumentationRegistry.getInstrumentation().runOnMainSync { DemoMode.exit(context, reconnect = false) {} }
        if (DemoMode.isActive(context)) prefs("rviz2mini_demo").edit().clear().commit()
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply { savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } } }.commit()
    }

    @Test
    fun dashboardWithWidgets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
            val dash = TabStore.get().all.first { it.type == TabType.DASHBOARD }
            scenario.onActivity { it.openTab(dash.id) }
            SystemClock.sleep(9000)
            Ui.shot(dir, "1_dashboard")

            fun frag(): DashboardFragment {
                var f: DashboardFragment? = null
                scenario.onActivity { a -> f = a.supportFragmentManager.findFragmentByTag(dash.id) as DashboardFragment }
                return f!!
            }
            assertEquals(4, frag().layoutForTest().size)
            // The widget's RViz receives real data (the first launch after install also prepares the 3D viewer first).
            var statuses = ""
            val deadline = SystemClock.uptimeMillis() + 30_000
            do {
                scenario.onActivity { _ ->
                    val rvizId = TabStore.get().all.first { it.dashboardId == dash.id && it.type == TabType.RVIZ }.id
                    val r = frag().childFragmentManager.findFragmentByTag(rvizId) as RvizFragment
                    statuses = r.displayConfigs().joinToString("\n") { d -> "${d.name}: ${r.displayStatus(d.id)?.level}" }
                }
                val ok = statuses.lines().count { it.endsWith("OK") } >= 5
                if (!ok) SystemClock.sleep(1000)
            } while (!ok && SystemClock.uptimeMillis() < deadline)
            Log.i(TAG, "RViz en el dashboard:\n$statuses")
            assertTrue("RViz in the dashboard:\n$statuses", statuses.lines().count { it.endsWith("OK") } >= 5)
            Ui.waitFor("/camera/image_raw", timeoutMs = 20_000, scroll = false)

            // Move and resize: free placement, the camera goes where it is dropped and nothing else moves.
            val cam = frag().layoutForTest().first { item -> TabStore.get().get(item.id)?.type == TabType.CAMERA }
            val others = frag().layoutForTest().filter { it.id != cam.id }.toSet()
            scenario.onActivity { frag().moveForTest(cam.id, 6, 0, 6, 6) }
            var layout = frag().layoutForTest()
            assertEquals(6.0, layout.first { it.id == cam.id }.x, 0.0)
            assertEquals(0.0, layout.first { it.id == cam.id }.y, 0.0)
            assertEquals(others, layout.filter { it.id != cam.id }.toSet())
            scenario.onActivity { frag().moveForTest(cam.id, 6, 0, 12, 7) }
            assertEquals(12.0, frag().layoutForTest().first { it.id == cam.id }.w, 0.0)

            // Overlap: the joystick over the RViz without moving anything, and it responds to the finger (not the RViz below).
            val joyId = TabStore.get().all.first { it.dashboardId == dash.id && it.type == TabType.TELEOP }.id
            val rvizItem = frag().layoutForTest().first { item -> TabStore.get().get(item.id)?.type == TabType.RVIZ }
            scenario.onActivity { frag().moveForTest(joyId, 6.0, rvizItem.y + 1, 6.0, 6.0) }
            layout = frag().layoutForTest()
            val joyItem = layout.first { it.id == joyId }
            assertTrue("el joystick tiene que estar encima del RViz", joyItem.overlaps(layout.first { it.id == rvizItem.id }))
            assertEquals("el último que se mueve queda encima", joyId, layout.last().id)
            assertEquals(rvizItem, layout.first { it.id == rvizItem.id })
            SystemClock.sleep(1500)
            Ui.shot(dir, "1b_floating")
            val registry = com.juancams.rviz2mini.ros.dyn.Interfaces.get()
            val forward = java.util.concurrent.CopyOnWriteArrayList<Double>()
            val cmd = com.juancams.rviz2mini.ros.dyn.DynRos.subscribe("/cmd_vel", registry.msg("geometry_msgs/msg/Twist"), RosConnection.defaultQos()) {
                ((it["linear"] as com.juancams.rviz2mini.ros.dyn.MsgValue)["x"] as Number).toDouble().let { x -> if (x > 0) forward.add(x) }
            }
            try {
                var cx = 0f; var cy = 0f; var r = 0f
                // The joystick (Compose) by its accessibility description.
                val jr = android.graphics.Rect().also { Ui.waitFor(context.getString(R.string.teleop_left_joystick), exact = true).getBoundsInScreen(it) }
                cx = jr.exactCenterX(); cy = jr.exactCenterY(); r = minOf(jr.width(), jr.height()) / 2f * 0.7f
                // The touch enters through the activity's window, like a finger: the view hierarchy decides
                // who gets it (the floating one, on top, before the RViz). Injecting it with UiAutomation does not work: MIUI
                // blocks it. Bottom center and upward = forward.
                fun touch(action: Int, down: Long, x: Float, y: Float) {
                    scenario.onActivity { a ->
                        val origin = IntArray(2).also { a.window.decorView.getLocationOnScreen(it) }
                        val e = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x - origin[0], y - origin[1], 0)
                            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                        a.window.decorView.dispatchTouchEvent(e); e.recycle()
                    }
                }
                val down = SystemClock.uptimeMillis()
                touch(android.view.MotionEvent.ACTION_DOWN, down, cx, cy)
                for (i in 1..60) { touch(android.view.MotionEvent.ACTION_MOVE, down, cx, cy - r * minOf(1f, i / 15f)); SystemClock.sleep(25) }
                touch(android.view.MotionEvent.ACTION_UP, down, cx, cy - r)
                SystemClock.sleep(500)
                Log.i(TAG, "/cmd_vel hacia delante desde el joystick flotante: ${forward.size} mensajes, máx ${forward.maxOrNull()}")
                assertTrue("el joystick flotante no publicó al arrastrarlo", forward.isNotEmpty())
            } finally {
                cmd.close()
            }
            // Add a Topics widget and remove it from the UI (edit mode + trash can).
            scenario.onActivity { frag().addWidgetForTest(TabSpec.create(TabType.TOPICS, "Topics widget")) }
            assertEquals(5, frag().layoutForTest().size)
            SystemClock.sleep(2000)
            // Board zoom: two-finger pinch, also outside edit mode.
            val zoomPrefs = context.getSharedPreferences("dashboard", Context.MODE_PRIVATE)
            val zoomKey = "zoom:${dash.id}"
            val zoomBefore = zoomPrefs.getFloat(zoomKey, 1f)
            try {
                val c = IntArray(2); var w = 0; var h = 0
                scenario.onActivity { frag().requireView().let { v -> v.getLocationOnScreen(c); w = v.width; h = v.height } }
                // In the left half: the test's joystick is on the right (and with a finger on a joystick there is no zoom).
                val cx = c[0] + w / 4f; val cy = c[1] + h / 2f
                pinch(scenario, cx, cy, from = 100f, to = 300f)
                SystemClock.sleep(500)
                val zoomAfter = zoomPrefs.getFloat(zoomKey, 1f)
                Log.i(TAG, "zoom del dashboard: $zoomBefore -> $zoomAfter")
                assertTrue("el pellizco no hizo zoom ($zoomBefore -> $zoomAfter)", zoomAfter > zoomBefore * 1.5f)
                Ui.shot(dir, "2b_zoom")
                // The "%" in the bar goes back to 100 %.
                Ui.click("${kotlin.math.round(zoomAfter * 100).toInt()} %")
                SystemClock.sleep(500)
                assertEquals(1f, zoomPrefs.getFloat(zoomKey, 1f), 0.01f)
            } finally {
                zoomPrefs.edit().remove(zoomKey).commit()
            }
            Ui.click("Edit")
            Ui.shot(dir, "2_editing")


            val before = frag().layoutForTest().size
            Ui.click("Remove widget")
            SystemClock.sleep(800)
            assertEquals(before - 1, frag().layoutForTest().size)
            Ui.click("Done")

            // The layout is saved with the tab and the widgets do not appear in the tab bar.
            val saved = TabStore.get().get(dash.id)!!.settings[DashboardFragment.KEY_LAYOUT]
            assertEquals(frag().layoutForTest(), com.juancams.rviz2mini.dashboard.DashboardGrid.fromJson(saved))
            assertFalse(Ui.exists("Joystick", exact = true))
            Ui.shot(dir, "3_after")

            // Recreate the activity (as when rotating the screen): the widgets come back, with the same layout and with data.
            val layoutBefore = frag().layoutForTest()
            scenario.recreate()
            SystemClock.sleep(5000)
            assertEquals(layoutBefore, frag().layoutForTest())
            var missing = ""
            scenario.onActivity { _ ->
                missing = frag().layoutForTest().filter { item ->
                    val f = frag().childFragmentManager.findFragmentByTag(item.id)
                    f?.view?.isAttachedToWindow != true || (f.view?.width ?: 0) == 0
                }.joinToString { TabStore.get().get(it.id)?.name ?: it.id }
            }
            assertEquals("widgets sin su vista tras recrear", "", missing)
            assertTrue(Ui.exists("/camera/image_raw"))
            Ui.shot(dir, "4_recreated")

            scenario.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }
        }
    }

    /** Two fingers spreading from `from` to `to` px around (cx, cy), through the activity's window (like a real finger). */
    private fun pinch(scenario: androidx.test.core.app.ActivityScenario<MainActivity>, cx: Float, cy: Float, from: Float, to: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, n: Int, d: Float) {
            scenario.onActivity { a ->
                val o = IntArray(2).also { a.window.decorView.getLocationOnScreen(it) }
                val props = Array(n) { i -> android.view.MotionEvent.PointerProperties().apply { id = i; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(n) { i -> android.view.MotionEvent.PointerCoords().apply { x = cx - o[0] + (if (i == 0) -d else d) / 2; y = cy - o[1]; pressure = 1f; size = 1f } }
                val e = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, n, props, coords, 0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                a.window.decorView.dispatchTouchEvent(e); e.recycle()
            }
        }
        val pointer1 = 1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT
        send(android.view.MotionEvent.ACTION_DOWN, 1, from)
        send(android.view.MotionEvent.ACTION_POINTER_DOWN or pointer1, 2, from)
        for (i in 1..20) { send(android.view.MotionEvent.ACTION_MOVE, 2, from + (to - from) * i / 20f); SystemClock.sleep(16) }
        send(android.view.MotionEvent.ACTION_POINTER_UP or pointer1, 2, to)
        send(android.view.MotionEvent.ACTION_UP, 1, to)
    }

    companion object {
        private const val TAG = "DashboardDeviceTest"
    }
}
