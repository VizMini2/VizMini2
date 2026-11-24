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
import com.juancams.rviz2mini.dashboard.DashboardFragment
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.display.DisplayFactory
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drag and drop of topics in the dashboard (with the demo robot): long-pressing a topic of a Topics widget and
 * dropping it on the RViz adds its display; dropping it in an empty spot creates a new widget. Screenshots in files/dragdrop/.
 */
@RunWith(AndroidJUnit4::class)
class DragDropDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "dragdrop").apply { deleteRecursively(); mkdirs() }
    private val savedTabs = TestTabs.backup(context)
    private val savedRos = prefs("rviz2mini_ros").all

    private fun prefs(n: String) = context.getSharedPreferences(n, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        if (DemoMode.isActive(context)) InstrumentationRegistry.getInstrumentation().runOnMainSync { DemoMode.exit(context, reconnect = false) {} }
        if (DemoMode.isActive(context)) prefs("rviz2mini_demo").edit().clear().commit()
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply { savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } } }.commit()
    }

    @Test
    fun dragTopicsOntoDashboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
            val dash = TabStore.get().all.first { it.type == TabType.DASHBOARD }
            scenario.onActivity { it.openTab(dash.id) }
            SystemClock.sleep(6000)
            fun frag(): DashboardFragment {
                var f: DashboardFragment? = null
                scenario.onActivity { a -> f = a.supportFragmentManager.findFragmentByTag(dash.id) as DashboardFragment }
                return f!!
            }
            // Only the RViz (right, top) and a topic list (left); bottom right, empty.
            val rvizId = TabStore.get().all.first { it.dashboardId == dash.id && it.type == TabType.RVIZ }.id
            scenario.onActivity { for (i in frag().layoutForTest()) if (i.id != rvizId) frag().removeForTest(i.id) }
            scenario.onActivity { frag().addWidgetForTest(TabSpec.create(TabType.TOPICS, "Topics")) }
            val topicsId = frag().layoutForTest().first { it.id != rvizId }.id
            scenario.onActivity { frag().moveForTest(topicsId, 0, 0, 6, 12); frag().moveForTest(rvizId, 6, 0, 6, 6) }
            SystemClock.sleep(4000)
            Ui.shot(dir, "1_layout")

            fun rviz(): RvizFragment {
                var r: RvizFragment? = null
                scenario.onActivity { r = frag().childFragmentManager.findFragmentByTag(rvizId) as RvizFragment }
                return r!!
            }
            // A topic from the list that the RViz can draw and is not drawing yet.
            val shown = rviz().displayConfigs().map { it.topic }.toSet()
            val graph = RosConnection.graph()!!.topics()
            val candidate = graph.firstOrNull { t -> t.name !in shown && DisplayFactory.displaysFor(t.name, t.types).isNotEmpty() }
                ?: error("no topic the RViz can draw: ${graph.map { it.name }}")
            // Filtered, so its row is on screen whatever the demo publishes.
            val from = rowOf(candidate.name)
            val rv = IntArray(2); var rw = 0; var rh = 0
            scenario.onActivity { rviz().requireView().let { v -> v.getLocationOnScreen(rv); rw = v.width; rh = v.height } }
            longPressDrag(scenario, from.exactCenterX(), from.exactCenterY(), rv[0] + rw / 2f, rv[1] + rh / 2f)
            SystemClock.sleep(1500)
            Ui.shot(dir, "2_dropped_on_rviz")
            val after = rviz().displayConfigs().map { it.topic }
            Log.i(TAG, "${candidate.name} al RViz: $after")
            assertTrue("${candidate.name} no se añadió al RViz: $after", candidate.name in after)

            // Another topic in the empty spot at the bottom right: a new widget.
            val before = frag().layoutForTest().size
            val other = graph.firstOrNull { it.name != candidate.name && it.name.contains("odom") } ?: graph.first { it.name != candidate.name }
            val o = rowOf(other.name)
            val dv = IntArray(2); var dw = 0; var dh = 0
            scenario.onActivity { frag().requireView().let { v -> v.getLocationOnScreen(dv); dw = v.width; dh = v.height } }
            longPressDrag(scenario, o.exactCenterX(), o.exactCenterY(), dv[0] + dw * 0.75f, dv[1] + dh * 0.8f)
            SystemClock.sleep(2500)
            Ui.shot(dir, "3_new_widget")
            val now = frag().layoutForTest()
            Log.i(TAG, "${other.name} en un hueco: ${now.map { TabStore.get().get(it.id)?.let { s -> "${s.type}:${s.name}" } }}")
            assertEquals("${other.name} en un hueco no creó widget", before + 1, now.size)

            scenario.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }
        }
    }

    /** Filters the Topics widget by `name` and returns where its row is (not the filter field, which shows it too). */
    private fun rowOf(name: String): android.graphics.Rect {
        Ui.typeInto(0, name)
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            Ui.findAll(name).firstOrNull { !it.isEditable }?.let { n -> return android.graphics.Rect().also { n.getBoundsInScreen(it) } }
            SystemClock.sleep(200)
        }
        error("the row of $name is not on screen")
    }

    /** Long-press (like a finger, through the window) and drag from (x0, y0) to (x1, y1). */
    private fun longPressDrag(scenario: ActivityScenario<MainActivity>, x0: Float, y0: Float, x1: Float, y1: Float) {
        val down = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float, y: Float) {
            scenario.onActivity { a ->
                val origin = IntArray(2).also { a.window.decorView.getLocationOnScreen(it) }
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x - origin[0], y - origin[1], 0)
                    .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                a.window.decorView.dispatchTouchEvent(e); e.recycle()
            }
        }
        touch(MotionEvent.ACTION_DOWN, x0, y0)
        SystemClock.sleep(900)
        for (i in 1..30) { touch(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 30f, y0 + (y1 - y0) * i / 30f); SystemClock.sleep(20) }
        SystemClock.sleep(150)
        touch(MotionEvent.ACTION_UP, x1, y1)
    }

    companion object {
        private const val TAG = "DragDropDeviceTest"
    }
}
