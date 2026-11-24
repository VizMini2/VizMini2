// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Visual tour through the tabs saving screenshots (only with `-e tour true`).
 * The screenshots end up in Android/data/com.juancams.vizmini2/files/tour/.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun tour() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tour") == "true")
        val tabsPrefs = context.getSharedPreferences("rviz2mini_tabs", Context.MODE_PRIVATE)
        val rosPrefs = context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE)
        val savedTabs = TestTabs.backup(context)
        val savedRos = rosPrefs.all
        val dir = File(context.getExternalFilesDir(null), "tour").apply { deleteRecursively(); mkdirs() }

        try {
            RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_tour", discoveryPeers = TestArgs.peers))
            val store = TabStore.get()
            val camera = TabSpec.create(TabType.CAMERA, "Cámara frontal")
                .withSetting("topic", "/image").withSetting("kind", "RAW")
            val teleop = TabSpec.create(TabType.TELEOP, "Teleop")
            val lifecycle = TabSpec.create(TabType.LIFECYCLE, "Lifecycle")
                .withSetting(com.juancams.rviz2mini.lifecycle.LifecycleFragment.KEY_NODES, "/rviz2mini_test/lc_talker")
            val bags = TabSpec.create(TabType.BAGS, "Bags")
            store.add(camera); store.add(teleop); store.add(lifecycle); store.add(bags)
            val rviz = store.all.firstOrNull { it.type == TabType.RVIZ } ?: TabSpec.create(TabType.RVIZ, "RViz").also { store.add(it) }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(6000) // connection + TF
                shot(dir, "1_rviz")
                select(scenario, camera.id); SystemClock.sleep(9000)
                shot(dir, "2_camara")
                select(scenario, teleop.id); SystemClock.sleep(4000)
                val joystick = joystick(scenario)
                val down = SystemClock.uptimeMillis()
                touch(joystick, MotionEvent.ACTION_DOWN, joystick.width / 2f, joystick.height / 2f, down)
                repeat(10) {
                    touch(joystick, MotionEvent.ACTION_MOVE, joystick.width * 0.7f, joystick.height * 0.25f, down)
                    SystemClock.sleep(100)
                }
                shot(dir, "3_teleop_moviendo")
                touch(joystick, MotionEvent.ACTION_UP, joystick.width * 0.7f, joystick.height * 0.25f, down)
                SystemClock.sleep(1500)
                shot(dir, "4_teleop_soltado")
                select(scenario, "config"); SystemClock.sleep(1500)
                shot(dir, "5_config")
                select(scenario, lifecycle.id); SystemClock.sleep(5000)
                shot(dir, "6_lifecycle")
                select(scenario, bags.id); SystemClock.sleep(2500)
                shot(dir, "6b_bags")
                select(scenario, "home"); SystemClock.sleep(4000)
                shot(dir, "8_home")
                select(scenario, rviz.id); SystemClock.sleep(1500)
                shot(dir, "7_rviz_vuelta")
            }
            ActivityScenario.launch(com.juancams.rviz2mini.home.ChangelogActivity::class.java).use {
                SystemClock.sleep(1500)
                shot(dir, "9_changelog")
            }
            // Closing MainActivity shuts down ROS; in real use these screens open on top of it.
            RosConnection.start()
            RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_tour", discoveryPeers = TestArgs.peers))
            SystemClock.sleep(6000)
            val inspector = android.content.Intent(context, com.juancams.rviz2mini.params.NodeInspectorActivity::class.java)
                .putExtra(com.juancams.rviz2mini.params.NodeInspectorActivity.EXTRA_NODE, "/rviz2mini_test/parameter_blackboard")
            ActivityScenario.launch<com.juancams.rviz2mini.params.NodeInspectorActivity>(inspector).use {
                SystemClock.sleep(4000)
                shot(dir, "10_node_params")
            }
            ActivityScenario.launch(com.juancams.rviz2mini.graph.GraphActivity::class.java).use {
                SystemClock.sleep(4000)
                shot(dir, "11_node_graph")
            }
            val tf = android.content.Intent(context, com.juancams.rviz2mini.graph.GraphActivity::class.java).putExtra("tf", true)
            ActivityScenario.launch<com.juancams.rviz2mini.graph.GraphActivity>(tf).use {
                SystemClock.sleep(3000)
                shot(dir, "12_tf_tree")
            }
        } finally {
            RosConnection.stop()
            TestTabs.restore(context, savedTabs)
            rosPrefs.edit().clear().apply {
                savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
            }.commit()
        }
    }

    private fun shot(dir: File, name: String) {
        instrumentation.waitForIdleSync()
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
        instrumentation.waitForIdleSync()
    }

    /** The visible Teleop tab joystick (shared screen, Compose; see TestJoystick). */
    private fun joystick(scenario: ActivityScenario<MainActivity>): TestJoystick = TestJoystick.sticks(scenario).first

    @Suppress("UNUSED_PARAMETER")
    private fun touch(view: TestJoystick, action: Int, x: Float, y: Float, downTime: Long) = view.touch(action, x, y)
}
