// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.bt.BehaviorTreeFragment
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.lifecycle.LifecycleFragment
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshots of each tab type for the "+" catalog and onboarding (not a test in the
 * suite): `-e shots true -e peers <PC IP>`, with the demo robot and the PC test
 * nodes. Leaves PNGs in files/catalog/ (later cropped to res/drawable-nodpi/shot_*.webp).
 */
@RunWith(AndroidJUnit4::class)
class CatalogShots {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir = File(context.getExternalFilesDir(null), "catalog").apply { deleteRecursively(); mkdirs() }

    @Test
    fun shots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shots") == "true")
        val savedTabs = TestTabs.backup(context)
        val savedRos = context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).all
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // With the demo robot: the visual ones.
                scenario.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
                SystemClock.sleep(4000)
                val demoTabs = TabStore.get().all
                val extra = listOf(TabSpec.create(TabType.TELEOP_DUAL, "Teleop (2 joysticks)"), TabSpec.create(TabType.BAGS, "Bags"),
                    TabSpec.create(TabType.TF_TREE, "TF tree"),
                    TabSpec.create(TabType.MESSAGE, "Message").withSetting(com.juancams.rviz2mini.explore.MessageFragment.KEY_TOPIC, "/odom")
                        .withSetting(com.juancams.rviz2mini.explore.MessageFragment.KEY_TYPE, "nav_msgs/msg/Odometry"),
                    TabSpec.create(TabType.PARAMETERS, "Parameters").withSetting(com.juancams.rviz2mini.params.ParamsFragment.KEY_NODE, "/demo_robot"))
                scenario.onActivity { a -> for (t in extra) { TabStore.get().add(t) }; a.replaceTabs(TabStore.get().all.toList()) }
                SystemClock.sleep(2000)
                val byType = TabStore.get().all.filter { !it.isWidget }.associateBy { it.type }
                for ((type, wait) in listOf(TabType.DASHBOARD to 10000L, TabType.RVIZ to 8000L, TabType.CAMERA to 4000L, TabType.TELEOP to 2500L, TabType.TELEOP_DUAL to 2500L,
                    TabType.DIAGNOSTICS to 4000L, TabType.BUTTONS to 4000L, TabType.SLIDERS to 2500L, TabType.INDICATORS to 4000L, TabType.PLOT to 12000L, TabType.GPS to 8000L, TabType.TOPICS to 3500L, TabType.BAGS to 2500L, TabType.TF_TREE to 4000L,
                    TabType.MESSAGE to 4000L, TabType.PARAMETERS to 5000L, TabType.PLANSYS2 to 9000L)) {
                    val t = byType[type] ?: continue
                    scenario.onActivity { it.openTab(t.id) }
                    SystemClock.sleep(wait)
                    shot(type.name.lowercase())
                }
                scenario.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }

                // With the PC nodes: the ones that inspect the network.
                RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_shots", discoveryPeers = TestArgs.peers))
                val pcTabs = listOf(
                    TabSpec.create(TabType.LIFECYCLE, "Lifecycle").withSetting(LifecycleFragment.KEY_NODES, "/rviz2mini_test/lc_talker"),
                    TabSpec.create(TabType.NODES, "Nodes"), TabSpec.create(TabType.SERVICES, "Services"), TabSpec.create(TabType.ACTIONS, "Actions"),
                    TabSpec.create(TabType.NODE_GRAPH, "Node graph"),
                    // tools/bt_groot/bt_groot_server on the PC.
                    TabSpec.create(TabType.BEHAVIOR_TREE, "Behavior tree").withSetting(BehaviorTreeFragment.KEY_HOST, TestArgs.pcIp),
                )
                scenario.onActivity { a -> a.replaceTabs(pcTabs) }
                SystemClock.sleep(6000)
                for (t in pcTabs) {
                    scenario.onActivity { it.openTab(t.id) }
                    SystemClock.sleep(5000)
                    shot(t.type.name.lowercase())
                }
            }
        } finally {
            RosConnection.stop()
            TestTabs.restore(context, savedTabs)
            context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).edit().clear().apply {
                savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
            }.commit()
        }
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
