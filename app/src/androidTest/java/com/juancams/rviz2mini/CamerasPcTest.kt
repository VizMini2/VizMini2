// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.camera.CameraFragment
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Several cameras at once (tools/pc_tests.sh CamerasPcTest: /camera/image_raw from fake_robot.py and
 * /image from cam2image): two in the Camera tab, one above the other, and two Image displays in the
 * same corner of RViz, stacked.
 */
@RunWith(AndroidJUnit4::class)
class CamerasPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_cams", discoveryPeers = TestArgs.peers))
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
    fun twoCamerasInTheCameraTabAndStackedImagesInRviz() {
        val camera = TabSpec.create(TabType.CAMERA, "Cams test").withSetting(CameraFragment.KEY_TOPICS, "RAW\t/camera/image_raw\nRAW\t/image")
        val a = DisplayConfig.create(DisplayType.IMAGE).copy(topic = "/camera/image_raw").with("annotations", "/camera/image_markers")
        val depth = DisplayConfig.create(DisplayType.DEPTH_CLOUD).copy(topic = "/camera/depth/image_raw/compressedDepth")
        val cam = DisplayConfig.create(DisplayType.CAMERA).with("corner", "Top left").with("annotations", "/camera/image_markers")
        val b = DisplayConfig.create(DisplayType.IMAGE).copy(topic = "/image")
        val rvizTab = TabSpec.create(TabType.RVIZ, "Images test").withSetting("displays", DisplayList.toJson(listOf(DisplayConfig.create(DisplayType.GRID), a, b, depth, cam)))
        TabStore.get().add(camera)
        TabStore.get().add(rvizTab)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                select(scenario, camera.id)
                var cams: CameraFragment? = null
                val found = SystemClock.uptimeMillis() + 10_000
                while (cams == null && SystemClock.uptimeMillis() < found) {
                    scenario.onActivity { act -> cams = act.supportFragmentManager.fragments.filterIsInstance<CameraFragment>().firstOrNull { it.isVisible } }
                    SystemClock.sleep(100)
                }
                val until = SystemClock.uptimeMillis() + 20_000
                while (onMain { cams!!.tilesForTest() }.count { it.second } < 2 && SystemClock.uptimeMillis() < until) SystemClock.sleep(200)
                val tiles = onMain { cams!!.tilesForTest() }
                Log.i(TAG, "cámaras: $tiles")
                assertEquals(listOf("/camera/image_raw", "/image"), tiles.map { it.first })
                assertTrue("no llegan las dos imágenes: $tiles", tiles.all { it.second })
                // Two cameras: one above the other, without overlapping.
                assertTrue(tiles[0].third.bottom <= tiles[1].third.top)
                shot("cameras.png")

                select(scenario, rvizTab.id)
                val stacked = SystemClock.uptimeMillis() + 20_000
                fun column(): LinearLayout? {
                    var c: LinearLayout? = null
                    scenario.onActivity { act -> c = act.window.decorView.findViewWithTag("image-column:Bottom right") }
                    return c
                }
                // Each panel of the column holds its image (and the annotations button).
                fun imageOf(v: android.view.View) = (v as? ImageView) ?: ((v as? android.view.ViewGroup)?.getChildAt(0) as? ImageView)
                fun withImage() = onMain { column()?.let { c -> (0 until c.childCount).count { imageOf(c.getChildAt(it))?.drawable != null } } ?: 0 }
                while (withImage() < 2 && SystemClock.uptimeMillis() < stacked) SystemClock.sleep(200)
                assertEquals("dos Image en la misma esquina", 2, withImage())
                val (top, bottom) = onMain {
                    val c = column()!!
                    val r0 = android.graphics.Rect(); val r1 = android.graphics.Rect()
                    c.getChildAt(0).getGlobalVisibleRect(r0); c.getChildAt(1).getGlobalVisibleRect(r1)
                    r0 to r1
                }
                Log.i(TAG, "rviz: $top $bottom")
                assertTrue("se tapan: $top $bottom", top.bottom <= bottom.top)
                // Image annotations and the cloud from the compressed depth.
                var rviz: com.juancams.rviz2mini.rviz.RvizFragment? = null
                scenario.onActivity { act -> rviz = act.supportFragmentManager.fragments.filterIsInstance<com.juancams.rviz2mini.rviz.RvizFragment>().first { it.isVisible } }
                val statusUntil = SystemClock.uptimeMillis() + 15_000
                fun status(id: String) = onMain { rviz!!.displayStatus(id) }?.message.orEmpty()
                while ((!status(a.id).contains("2 annotations") || !status(depth.id).contains("4800 points")) && SystemClock.uptimeMillis() < statusUntil) SystemClock.sleep(300)
                Log.i(TAG, "image: ${status(a.id)} · depth: ${status(depth.id)}")
                assertTrue(status(a.id), status(a.id).contains("2 annotations"))
                assertTrue(status(depth.id), status(depth.id).contains("4800 points"))
                shot("rviz_images.png")
                // Camera display in its four modes.
                for (mode in listOf("Image and scene", "Image over the scene", "Image only", "Scene only")) {
                    onMain { rviz!!.updateDisplayForTest(cam.with("mode", mode)) }
                    val okUntil = SystemClock.uptimeMillis() + 8_000
                    while (onMain { rviz!!.displayStatus(cam.id) }?.level != com.juancams.rviz2mini.rviz.display.StatusLevel.OK && SystemClock.uptimeMillis() < okUntil) SystemClock.sleep(200)
                    SystemClock.sleep(600)
                    val camDisplay = onMain { rviz!!.displayForTest(cam.id) } as com.juancams.rviz2mini.rviz.display.CameraDisplay
                    Log.i(TAG, "camera $mode: ${status(cam.id)} viewport=${onMain { camDisplay.viewportForTest() }?.toList()}")
                    assertEquals(com.juancams.rviz2mini.rviz.display.StatusLevel.OK, onMain { rviz!!.displayStatus(cam.id) }?.level)
                    assertTrue(onMain { camDisplay.viewportForTest() } != null)
                    shot("camera_${mode.replace(' ', '_')}.png")
                }
            }
        } finally {
            TabStore.get().remove(camera.id)
            TabStore.get().remove(rvizTab.id)
        }
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { act ->
            val tabs = act.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var r: T? = null
        instrumentation.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private companion object {
        const val TAG = "CamsPc"
    }
}
