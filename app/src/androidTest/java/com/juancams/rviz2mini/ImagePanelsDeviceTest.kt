// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.display.CameraDisplay
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.ImageDisplay
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
 * The image panels of the 3D view with the demo robot: an Image display panel is dragged with a finger to a free
 * position and its eye button hides the annotations; a Camera display panel is dragged inside the 3D view and
 * resized from its corner. Everything is saved in the display configuration. Screenshots in files/panels/.
 */
@RunWith(AndroidJUnit4::class)
class ImagePanelsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir = File(context.getExternalFilesDir(null), "panels").apply { deleteRecursively(); mkdirs() }
    private val savedTabs = TestTabs.backup(context)
    private val savedRos = prefs("rviz2mini_ros").all

    private fun prefs(n: String) = context.getSharedPreferences(n, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        if (DemoMode.isActive(context)) instrumentation.runOnMainSync { DemoMode.exit(context, reconnect = false) {} }
        if (DemoMode.isActive(context)) prefs("rviz2mini_demo").edit().clear().commit()
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply { savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } } }.commit()
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!ok() && SystemClock.uptimeMillis() < end) SystemClock.sleep(200)
        assertTrue(what, ok())
    }

    @Test
    fun dragResizeAndHideAnnotations() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
            SystemClock.sleep(3000)
            val image = DisplayConfig.create(DisplayType.IMAGE, "Image", "/camera/image_raw").with("corner", "Top right").with("annotations", "/camera/image_markers")
            val camera = DisplayConfig.create(DisplayType.CAMERA, "Camera", "/camera/image_raw").with("corner", "Bottom left").with("size", "0.4")
            val tab = TabSpec.create(TabType.RVIZ, "Panels").withSetting("displays", DisplayList.toJson(DisplayList.defaults() + image + camera))
            sc.onActivity { a -> TabStore.get().add(tab); a.replaceTabs(TabStore.get().all.toList()) }
            SystemClock.sleep(1000)
            sc.onActivity { a ->
                val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
            }
            var rviz: RvizFragment? = null
            waitFor("RViz not ready", 60_000) {
                var ok = false
                sc.onActivity { a -> rviz = a.supportFragmentManager.fragments.filterIsInstance<RvizFragment>().firstOrNull { it.isVisible }; ok = rviz?.isReadyForTest == true }
                ok
            }
            val r = rviz!!
            fun cfg(id: String) = r.displayConfigs().first { it.id == id }
            fun imageDisplay(): ImageDisplay? { var d: ImageDisplay? = null; instrumentation.runOnMainSync { d = r.sceneForTest()?.displays?.get(image.id) as? ImageDisplay }; return d }
            waitFor("no image in the Image panel") { r.displayStatus(image.id)?.message?.contains("fps") == true }
            waitFor("no Camera panel: ${r.displayStatus(camera.id)?.message}") { var ok = false; instrumentation.runOnMainSync { ok = (r.sceneForTest()?.displays?.get(camera.id) as? CameraDisplay)?.panelRect != null }; ok }
            SystemClock.sleep(1000)
            Ui.shot(dir, "1_corners")

            // 1. Drag the Image panel 250 px left and 300 px down: it gets a free position.
            val panel = imageDisplay()!!.panelForTest()!!
            val loc = IntArray(2); instrumentation.runOnMainSync { panel.getLocationOnScreen(loc) }
            var pw = 0; var ph = 0; instrumentation.runOnMainSync { pw = panel.width; ph = panel.height }
            val sx = loc[0] + pw / 2f; val sy = loc[1] + ph / 2f
            drag(sc, sx, sy, sx - 250f, sy + 300f)
            waitFor("the Image panel did not get a position: ${cfg(image.id).props}") { cfg(image.id).number("x") >= 0 }
            val moved = cfg(image.id)
            Log.i(TAG, "Image panel at x=${moved.number("x")} y=${moved.number("y")}")
            SystemClock.sleep(800)
            val after = IntArray(2); val movedPanel = imageDisplay()!!.panelForTest()!!; instrumentation.runOnMainSync { movedPanel.getLocationOnScreen(after) }
            assertEquals("moved left", loc[0] - 250f, after[0].toFloat(), 30f)
            assertEquals("moved down", loc[1] + 300f, after[1].toFloat(), 30f)

            // 2. Its eye hides the annotations (and the setting stays).
            assertTrue(cfg(image.id).bool("showAnnotations"))
            val eye = imageDisplay()!!.eyeForTest()!!
            instrumentation.runOnMainSync { eye.performClick() }
            waitFor("the eye did not hide the annotations") { !cfg(image.id).bool("showAnnotations") }
            SystemClock.sleep(800)
            Ui.shot(dir, "2_image_moved")

            // 3. Drag the Camera panel (inside the 3D view) to the right, then make it bigger from its corner.
            val surface = r.surfaceForTest()
            var rect: IntArray? = null
            instrumentation.runOnMainSync { rect = (r.sceneForTest()!!.displays[camera.id] as CameraDisplay).panelRect }
            val c0 = rect!!
            surfaceDrag(surface, c0[0] + c0[2] / 2f, c0[1] + c0[3] / 2f, c0[0] + c0[2] / 2f + 200f, c0[1] + c0[3] / 2f - 150f)
            waitFor("the Camera panel did not move") { cfg(camera.id).number("x") >= 0 }
            SystemClock.sleep(500)
            instrumentation.runOnMainSync { rect = (r.sceneForTest()!!.displays[camera.id] as CameraDisplay).panelRect }
            val c1 = rect!!
            Log.i(TAG, "Camera panel ${c0.toList()} -> ${c1.toList()}")
            assertEquals("Camera moved right", c0[0] + 200f, c1[0].toFloat(), 30f)
            val size0 = cfg(camera.id).number("size")
            surfaceDrag(surface, c1[0] + c1[2] - 8f, c1[1] + c1[3] - 8f, c1[0] + c1[2] + 150f, c1[1] + c1[3] + 100f)
            waitFor("the Camera panel did not grow") { cfg(camera.id).number("size") > size0 + 0.05 }
            Log.i(TAG, "Camera size $size0 -> ${cfg(camera.id).number("size")}")
            SystemClock.sleep(800)
            Ui.shot(dir, "3_camera_moved")
            sc.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }
        }
    }

    /** A finger on the window (it goes to whatever view is under it). */
    private fun drag(sc: ActivityScenario<MainActivity>, x0: Float, y0: Float, x1: Float, y1: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) = sc.onActivity { a ->
            val o = IntArray(2).also { a.window.decorView.getLocationOnScreen(it) }
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x - o[0], y - o[1], 0)
            a.window.decorView.dispatchTouchEvent(e); e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x0, y0)
        for (i in 1..15) { send(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 15, y0 + (y1 - y0) * i / 15); SystemClock.sleep(16) }
        send(MotionEvent.ACTION_UP, x1, y1)
    }

    /** A finger on the 3D view (its own coordinates). */
    private fun surfaceDrag(view: android.view.View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) = instrumentation.runOnMainSync {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            view.dispatchTouchEvent(e); e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x0, y0)
        for (i in 1..15) { send(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 15, y0 + (y1 - y0) * i / 15); SystemClock.sleep(16) }
        send(MotionEvent.ACTION_UP, x1, y1)
    }

    companion object {
        private const val TAG = "ImagePanelsDeviceTest"
    }
}
