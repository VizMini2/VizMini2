// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.ViewType
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.InteractiveMarkersDisplay
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Interactive markers against a server on the PC (tools/pc_tests.sh InteractiveMarkersPcTest:
 * fake_robot.py and interactive_markers_server.py). Moves, rotates and taps with the Interact tool
 * and checks what the server receives (which it forwards on /rviz2mini_test/im_events).
 */
@RunWith(AndroidJUnit4::class)
class InteractiveMarkersPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_im", discoveryPeers = TestArgs.peers))
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("pc") != "true") return
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun moveRotateAndPressInteractiveMarkers() {
        val im = DisplayConfig.create(DisplayType.INTERACTIVE_MARKERS).copy(topic = "/rviz2mini_test_im/update")
        val tab = TabSpec.create(TabType.RVIZ, "IM test").withSetting("displays", DisplayList.toJson(listOf(DisplayConfig.create(DisplayType.GRID), im)))
        TabStore.get().add(tab)
        val events = CopyOnWriteArrayList<String>()
        val connected = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < connected) SystemClock.sleep(100)
        val sub = DynRos.subscribe("/rviz2mini_test/im_events", Interfaces.get().msg("std_msgs/msg/String"), RosConnection.defaultQos()) { v ->
            events.add(v["data"].toString())
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                    (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
                }
                val rviz = rviz(scenario)
                val display = onMain { rviz.displayForTest(im.id) } as InteractiveMarkersDisplay
                val loaded = SystemClock.uptimeMillis() + 20_000
                while (onMain { display.markersForTest().size } < 4 && SystemClock.uptimeMillis() < loaded) SystemClock.sleep(200)
                assertEquals("markers del servidor", 4, onMain { display.markersForTest().size })
                onMain {
                    rviz.setViewForTest(ViewType.TOP_DOWN)
                    rviz.viewForTest().apply { scale = 10.0; focal[0] = 0.0; focal[1] = 0.0; angle = 0.0 }
                    rviz.setToolForTest(RvizFragment.Tool.INTERACT)
                }
                SystemClock.sleep(1500)
                fun screen(x: Double, y: Double) = onMain { rviz.projectForTest(doubleArrayOf(x, y, 0.0)) }!!
                fun drag(path: List<FloatArray>) {
                    assertTrue("no agarra el control", onMain { rviz.interactTouchForTest(MotionEvent.ACTION_DOWN, path[0][0], path[0][1]) })
                    for (p in path.drop(1)) { onMain { rviz.interactTouchForTest(MotionEvent.ACTION_MOVE, p[0], p[1]) }; SystemClock.sleep(60) }
                    val last = path.last()
                    onMain { rviz.interactTouchForTest(MotionEvent.ACTION_UP, last[0], last[1]) }
                    SystemClock.sleep(800)
                }
                fun lastEvent(prefix: String) = events.lastOrNull { it.startsWith(prefix) }
                // In portrait only about ±2 m are visible across: the view is centered on each marker.
                fun center(x: Double, y: Double) { onMain { rviz.viewForTest().apply { focal[0] = x; focal[1] = y } }; SystemClock.sleep(500) }
                center(1.5, 1.0)

                // Move along x: from the arrow at x = 1.75 to x = 2.75 (1 m).
                val a0 = screen(1.75, 1.0); val a1 = screen(2.75, 1.0)
                drag((0..10).map { i -> floatArrayOf(a0[0] + (a1[0] - a0[0]) * i / 10, a0[1] + (a1[1] - a0[1]) * i / 10) })
                val moved = lastEvent("1 mover move_x")
                Log.i(TAG, "mover: $moved")
                assertNotNull("el servidor no recibe la pose", moved)
                assertEquals(2.0, moved!!.split(' ')[3].toDouble(), 0.1)
                assertTrue(events.any { it.startsWith("4 mover move_x") } && events.any { it.startsWith("5 mover move_x") })

                // Rotate a quarter turn counterclockwise: from the ring at +x to the ring at +y.
                val c = doubleArrayOf(2.0, 1.0) // the mover is now at x = 2
                drag((0..12).map { i -> val t = Math.PI / 2 * i / 12; screen(c[0] + 0.5 * Math.cos(t), c[1] + 0.5 * Math.sin(t)) })
                val turned = lastEvent("1 mover rotate_z")
                Log.i(TAG, "giro: $turned")
                assertNotNull(turned)
                assertEquals(Math.PI / 2, turned!!.split(' ')[5].toDouble(), 0.2)

                // Move in the plane: the "planar" ring from (3.5, -2) to (4.5, -1.5).
                center(3.5, -2.0)
                val p0 = screen(3.5, -2.0); val p1 = screen(4.5, -1.5)
                drag((0..10).map { i -> floatArrayOf(p0[0] + (p1[0] - p0[0]) * i / 10, p0[1] + (p1[1] - p0[1]) * i / 10) })
                val planar = lastEvent("1 planar move_plane")
                Log.i(TAG, "plano: $planar")
                assertNotNull(planar)
                assertEquals(4.0, planar!!.split(' ')[3].toDouble(), 0.15)
                assertEquals(-1.5, planar.split(' ')[4].toDouble(), 0.15)

                // Button: a tap on the cube.
                center(-2.0, 1.0)
                val b = screen(-2.0, 1.0)
                drag(listOf(b))
                Log.i(TAG, "botón: ${lastEvent("3 button")}")
                assertNotNull("sin BUTTON_CLICK", lastEvent("3 button press"))
                assertEquals("tap:button:press", onMain { rviz.lastImEventForTest() })
                shot()
            }
        } finally {
            sub.close()
            TabStore.get().remove(tab.id)
        }
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

    private fun <T> onMain(block: () -> T): T {
        var r: T? = null
        instrumentation.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun shot() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "interactive_markers.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private companion object {
        const val TAG = "ImPc"
    }
}
