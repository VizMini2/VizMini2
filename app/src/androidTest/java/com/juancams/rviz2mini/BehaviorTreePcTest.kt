// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.bt.BehaviorTreeFragment
import com.juancams.rviz2mini.bt.BtCategory
import com.juancams.rviz2mini.bt.BtStatus
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
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
 * Behavior tree tab against the PC (tools/pc_tests.sh BehaviorTreePcTest:
 * tools/bt_groot/bt_groot_server with Groot2Publisher on 1667 and tools/bt_log_pub.py).
 */
@RunWith(AndroidJUnit4::class)
class BehaviorTreePcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_bt", discoveryPeers = TestArgs.peers))
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
    fun groot2TreeAndNav2Log() {
        val tab = TabSpec.create(TabType.BEHAVIOR_TREE, "BT test")
            .withSetting(BehaviorTreeFragment.KEY_SOURCE, BehaviorTreeFragment.SOURCE_GROOT2)
            .withSetting(BehaviorTreeFragment.KEY_HOST, TestArgs.pcIp)
        TabStore.get().add(tab)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                    (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
                }
                val bt = fragment(scenario)

                // Groot2: the whole tree, with the SubTree expanded.
                val loaded = SystemClock.uptimeMillis() + 20_000
                while ((onMain { bt.snapshotForTest() }?.tree?.nodes?.size ?: 0) < 10 && SystemClock.uptimeMillis() < loaded) SystemClock.sleep(200)
                val snap = onMain { bt.snapshotForTest() }!!
                Log.i(TAG, "groot2: ${onMain { bt.statusForTest() }} ${snap.error}")
                assertTrue("sin conexión con Groot2: ${snap.error}", snap.connected)
                val nodes = snap.tree.nodes
                assertEquals((1..10).toList(), nodes.keys.toList())
                assertEquals("patrol", nodes.getValue(1).name)
                assertEquals(BtCategory.SUBTREE, nodes.getValue(7).category)
                assertEquals(listOf(9, 10), nodes.getValue(8).children)
                assertEquals(listOf("Main", "Dock::7"), snap.tree.blackboards)

                // Live states: in one tick cycle of the tree the waits run and the condition fails.
                val until = SystemClock.uptimeMillis() + 10_000
                fun seen(name: String, to: BtStatus) = onMain { bt.transitionsForTest() }.any { it.name == name && it.to == to }
                while (!(seen("search", BtStatus.RUNNING) && seen("approach", BtStatus.SUCCESS) && seen("align", BtStatus.RUNNING)) &&
                    SystemClock.uptimeMillis() < until) SystemClock.sleep(200)
                Log.i(TAG, "transiciones: ${onMain { bt.transitionsForTest() }.takeLast(12)}")
                assertTrue(seen("search", BtStatus.RUNNING))
                assertTrue(seen("approach", BtStatus.SUCCESS))
                assertTrue(seen("align", BtStatus.RUNNING))
                assertTrue("target_visible nunca acaba en FAILURE", onMain { bt.snapshotForTest() }!!.states.values.any { it.status == BtStatus.FAILURE || it.previous == BtStatus.FAILURE })

                // The blackboard: what the Script writes.
                val board = SystemClock.uptimeMillis() + 5_000
                while (onMain { bt.snapshotForTest() }!!.blackboard["Main"]?.get("target") == null && SystemClock.uptimeMillis() < board) SystemClock.sleep(200)
                val bb = onMain { bt.snapshotForTest() }!!.blackboard
                Log.i(TAG, "pizarra: $bb")
                assertEquals("dock", bb["Main"]?.get("target"))
                assertEquals(0.5, (bb["Main"]?.get("speed") as Number).toDouble(), 1e-6)
                onMain { bt.setBoardForTest(true) }
                assertTrue(onMain { bt.detailsForTest() }.contains("target = \"dock\""))
                onMain { bt.setBoardForTest(false) }

                // Tapping a node: type and ports.
                onMain { bt.tapNodeForTest(5) }
                val detail = onMain { bt.detailsForTest() }
                Log.i(TAG, "detalle: $detail")
                assertTrue(detail, detail.startsWith("search · Wait") && "ms = 600" in detail)
                shot("behavior_tree.png")

                // Breakpoint on "approach" (uid 6): the tree stops there (nothing changes) until Continue.
                onMain { bt.toggleBreakpointForTest(6) }
                val stopUntil = SystemClock.uptimeMillis() + 10_000
                while (onMain { bt.pausedAtForTest() } != 6 && SystemClock.uptimeMillis() < stopUntil) SystemClock.sleep(100)
                assertEquals("no avisa del breakpoint", 6, onMain { bt.pausedAtForTest() })
                SystemClock.sleep(400)
                val frozen = onMain { bt.snapshotForTest() }!!.states
                SystemClock.sleep(1500)
                Log.i(TAG, "breakpoint: ${onMain { bt.statusForTest() }}")
                assertEquals("el árbol no está parado", frozen, onMain { bt.snapshotForTest() }!!.states)
                assertTrue(onMain { bt.statusForTest() }.contains("approach"))
                shot("behavior_tree_breakpoint.png")
                onMain { bt.resumeForTest() }
                val goUntil = SystemClock.uptimeMillis() + 5_000
                while (onMain { bt.snapshotForTest() }!!.states == frozen && SystemClock.uptimeMillis() < goUntil) SystemClock.sleep(100)
                assertTrue("no sigue tras Continuar", onMain { bt.snapshotForTest() }!!.states != frozen)
                // It stops again on the next cycle; once removed it keeps going without stopping.
                val again = SystemClock.uptimeMillis() + 10_000
                while (onMain { bt.pausedAtForTest() } != 6 && SystemClock.uptimeMillis() < again) SystemClock.sleep(100)
                assertEquals("no se para en la siguiente vuelta", 6, onMain { bt.pausedAtForTest() })
                onMain { bt.toggleBreakpointForTest(6) }
                SystemClock.sleep(600)
                val s1 = onMain { bt.snapshotForTest() }!!.states
                SystemClock.sleep(1500)
                assertTrue("sigue parado tras quitar el breakpoint", onMain { bt.snapshotForTest() }!!.states != s1)

                // Closing with the tree stopped (switching source): it is unblocked and no breakpoints remain.
                onMain { bt.toggleBreakpointForTest(6) }
                val third = SystemClock.uptimeMillis() + 10_000
                while (onMain { bt.pausedAtForTest() } != 6 && SystemClock.uptimeMillis() < third) SystemClock.sleep(100)
                assertEquals(6, onMain { bt.pausedAtForTest() })
                onMain { bt.setSourceForTest(BehaviorTreeFragment.SOURCE_GROOT2, TestArgs.pcIp, 1667, null) }
                val back = SystemClock.uptimeMillis() + 10_000
                while ((onMain { bt.snapshotForTest() }?.states?.size ?: 0) == 0 && SystemClock.uptimeMillis() < back) SystemClock.sleep(200)
                val r1 = onMain { bt.snapshotForTest() }!!.states
                SystemClock.sleep(2000)
                assertTrue("el árbol se ha quedado parado al cerrar", onMain { bt.snapshotForTest() }!!.states != r1)
                assertEquals(null, onMain { bt.pausedAtForTest() })

                // Nav2: the log's nodes as they change.
                onMain { bt.setSourceForTest(BehaviorTreeFragment.SOURCE_NAV2, null, null, "/behavior_tree_log") }
                val nav = SystemClock.uptimeMillis() + 15_000
                while ((onMain { bt.snapshotForTest() }?.tree?.nodes?.size ?: 0) < 4 && SystemClock.uptimeMillis() < nav) SystemClock.sleep(200)
                val n = onMain { bt.snapshotForTest() }!!
                Log.i(TAG, "nav2: ${n.tree.nodes.values.map { it.name }} ${n.states}")
                assertEquals(setOf(1, 4, 7, 11), n.tree.nodes.keys)
                assertEquals("FollowPath", n.tree.nodes.getValue(7).name)
                val failed = SystemClock.uptimeMillis() + 6_000
                while (!seen("FollowPath", BtStatus.FAILURE) && SystemClock.uptimeMillis() < failed) SystemClock.sleep(200)
                assertTrue(seen("FollowPath", BtStatus.FAILURE))
                shot("behavior_tree_nav2.png")

                // Nav2 with the tree XML: the tree is drawn and the states land on their nodes by uid.
                val xml = instrumentation.context.assets.open("bt_nav2_test_tree.xml").use { it.readBytes().toString(Charsets.UTF_8) }
                onMain { bt.setNav2ForTest("/behavior_tree_log", xml, false) }
                val shaped = SystemClock.uptimeMillis() + 10_000
                while (onMain { bt.snapshotForTest() }?.states?.get(7) == null && SystemClock.uptimeMillis() < shaped) SystemClock.sleep(200)
                val x = onMain { bt.snapshotForTest() }!!
                Log.i(TAG, "nav2 con XML: ${x.tree.nodes.size} nodos ${x.states}")
                assertEquals(12, x.tree.nodes.size)
                assertEquals(listOf(5), x.tree.nodes.getValue(2).children.filter { it == 5 })
                assertTrue(x.states.keys.all { it in setOf(1, 4, 7, 11) })
                shot("behavior_tree_nav2_xml.png")
            }
        } finally {
            TabStore.get().remove(tab.id)
        }
    }

    private fun fragment(scenario: ActivityScenario<MainActivity>): BehaviorTreeFragment {
        var f: BehaviorTreeFragment? = null
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (f == null && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { a -> f = a.supportFragmentManager.fragments.filterIsInstance<BehaviorTreeFragment>().firstOrNull { it.isVisible } }
            if (f == null) SystemClock.sleep(100)
        }
        return f!!
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
        const val TAG = "BtPc"
    }
}
