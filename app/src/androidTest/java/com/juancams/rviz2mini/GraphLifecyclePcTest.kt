// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.lifecycle.LifecycleFragment
import com.juancams.rviz2mini.lifecycle.LifecycleModel
import com.juancams.rviz2mini.lifecycle.LifecycleNodeClient
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosGraph
import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.tabs.TabList
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ROS graph discovery, lifecycle nodes and matching of service responses against
 * ROS 2 on the PC. Optional:
 *   adb shell am instrument -w -e pc true -e class com.juancams.rviz2mini.GraphLifecyclePcTest \
 *       com.juancams.vizmini2.test/com.juancams.rviz2mini.TestRunner
 * (add `-e peers <PC IP>` if the Wi-Fi filters multicast) with, on the PC (ROS_DOMAIN_ID=77),
 * what ButtonsPcTest needs plus:
 *   python3 tools/fake_robot.py
 *   ros2 run lifecycle lifecycle_talker --ros-args -r __ns:=/rviz2mini_test
 */
@RunWith(AndroidJUnit4::class)
class GraphLifecyclePcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val registry by lazy { Interfaces.get() }
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        assumeTrue("Solo con -e pc true", InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_phone", discoveryPeers = TestArgs.peers))
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Sin conexión ROS 2", RosConnection.status is RosConnection.Status.Connected)
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

    /** Waits until discovery sees what is asked for. */
    private fun waitGraph(what: String, ok: (RosGraph) -> Boolean): RosGraph {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var g: RosGraph? = null
        while (SystemClock.uptimeMillis() < deadline) {
            g = RosConnection.graph()
            if (g != null && ok(g)) return g
            SystemClock.sleep(200)
        }
        assertNotNull("El grafo no está disponible (¿librerías nativas sin descubrimiento?)", g)
        throw AssertionError("No se ha descubierto $what. Topics: ${g!!.topics(true).map { it.name }} · endpoints ${g.endpoints} · participantes ${g.participants}")
    }

    @Test
    fun graphListsTopicsServicesActionsAndLifecycleNodes() {
        val g = waitGraph("todo") { g ->
            g.topics().any { it.name == "/scan" } &&
                g.services().any { it.name == "/rviz2mini_test/cpp/add_two_ints" } &&
                g.actions().any { it.name == "/rviz2mini_test/cpp/fibonacci" } &&
                "/rviz2mini_test/lc_talker" in g.lifecycleNodes() &&
                // Each node's services can arrive after the general list of services.
                g.nodes().any { it.name == "/rviz2mini_test/lc_talker" && "/rviz2mini_test/lc_talker/change_state" in it.services } &&
                g.nodes().any { it.name == "/fake_robot" }
        }
        Log.i(TAG, "grafo: ${g.topics().size} topics, ${g.services().size} servicios, ${g.actions().size} acciones, lifecycle ${g.lifecycleNodes()}")
        assertEquals("sensor_msgs/msg/LaserScan", g.topics().first { it.name == "/scan" }.type)
        assertEquals("example_interfaces/srv/AddTwoInts", g.services().first { it.name == "/rviz2mini_test/cpp/add_two_ints" }.type)
        // action_tutorials_interfaces up to Jazzy, example_interfaces since Kilted (see CppFibonacci).
        val fibType = g.actions().first { it.name == CppFibonacci.ACTION }.type
        assertTrue(fibType, fibType in setOf("action_tutorials_interfaces/action/Fibonacci", "example_interfaces/action/Fibonacci"))
        val talker = g.nodes().first { it.name == "/rviz2mini_test/lc_talker" }
        assertTrue("El lifecycle sale entre los nodos", talker.isLifecycle)
        assertTrue(talker.services.toString(), "/rviz2mini_test/lc_talker/change_state" in talker.services)
        assertTrue(g.nodes().first { it.name == "/fake_robot" }.publishers.contains("/scan"))
        // Topics that the old search (usual names) could not find.
        assertTrue(g.topics().any { it.name == "/rviz2mini_test/lc_talker/transition_event" || it.name == "/rosout" })
    }

    /** Two clients of the same service at once: each one must receive its own response. */
    @Test
    fun concurrentClientsGetTheirOwnReplies() {
        val srv = registry.srv("example_interfaces/srv/AddTwoInts")
        val clients = List(2) { DynServiceClient("/rviz2mini_test/cpp/add_two_ints", srv) }
        try {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (clients.any { !it.hasServer() } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            val errors = CopyOnWriteArrayList<String>()
            val threads = clients.mapIndexed { c, client ->
                Thread {
                    for (i in 1..15) {
                        val a = (c + 1) * 1000L + i
                        try {
                            val r = client.call(MsgValue().apply { put("a", a); put("b", 1L) }, 5000)
                            if (r["sum"] != a + 1) errors.add("cliente $c: $a + 1 = ${r["sum"]}")
                        } catch (e: Exception) {
                            errors.add("cliente $c: ${e.message}")
                        }
                    }
                }.apply { start() }
            }
            threads.forEach { it.join(60_000) }
            assertTrue(errors.joinToString("\n"), errors.isEmpty())
        } finally {
            clients.forEach { it.close() }
        }
    }

    @Test
    fun lifecycleClientWalksThroughStates() {
        val node = "/rviz2mini_test/lc_talker"
        waitGraph(node) { node in it.lifecycleNodes() }
        val events = CopyOnWriteArrayList<LifecycleModel.State>()
        val client = LifecycleNodeClient(node, registry) { events.add(it) }
        try {
            SystemClock.sleep(500)
            var state = client.state()
            // Back to "unconfigured" in case a previous run left it in another state.
            if (state.id == LifecycleModel.ACTIVE) { client.change(byLabel(client, "deactivate")); state = client.state() }
            if (state.id == LifecycleModel.INACTIVE) { client.change(byLabel(client, "cleanup")); state = client.state() }
            assertEquals(LifecycleModel.UNCONFIGURED, state.id)

            for ((label, expected) in listOf(
                "configure" to LifecycleModel.INACTIVE,
                "activate" to LifecycleModel.ACTIVE,
                "deactivate" to LifecycleModel.INACTIVE,
                "cleanup" to LifecycleModel.UNCONFIGURED
            )) {
                assertTrue("$label rechazado", client.change(byLabel(client, label)))
                assertEquals(label, expected, client.state().id)
            }
            val deadline = SystemClock.uptimeMillis() + 3000
            while (events.none { it.id == LifecycleModel.ACTIVE } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue("No han llegado los transition_event: $events", events.any { it.id == LifecycleModel.ACTIVE })
        } finally {
            client.close()
        }
    }

    private fun byLabel(c: LifecycleNodeClient, label: String) =
        c.availableTransitions().first { it.label == label }

    /** The Lifecycle tab shows the state and its chips change the state of the real node. */
    @Test
    fun lifecycleTabShowsStateAndTransitions() {
        val node = "/rviz2mini_test/lc_talker"
        waitGraph(node) { node in it.lifecycleNodes() }
        val tab = TabSpec.create(TabType.LIFECYCLE, "Lifecycle test").withSetting(LifecycleFragment.KEY_NODES, node)
        TabStore.get().replaceAll(listOf(tab))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
            }
            waitText(scenario, "Unconfigured")
            clickChip(scenario, "Configure")
            waitText(scenario, "Inactive")
            clickChip(scenario, "Activate")
            waitText(scenario, "Active")
            clickChip(scenario, "Deactivate")
            waitText(scenario, "Inactive")
            clickChip(scenario, "Cleanup")
            waitText(scenario, "Unconfigured")
        }
    }

    private fun waitText(scenario: ActivityScenario<MainActivity>, text: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var found = false
            scenario.onActivity { a -> found = findViews(a.findViewById(R.id.tabContent)).any { it is TextView && it !is Chip && it.isShown && it.text.toString() == text } }
            // The Lifecycle tab is the shared screen (Compose): its texts are in the accessibility tree.
            if (found || Ui.exists(text, exact = true)) return
            SystemClock.sleep(150)
        }
        Ui.dump()
        throw AssertionError("No aparece \"$text\"")
    }

    private fun clickChip(scenario: ActivityScenario<MainActivity>, text: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var clicked = false
            scenario.onActivity { a ->
                findViews(a.findViewById(R.id.tabContent)).firstOrNull { it is Chip && it.text == text && it.isEnabled }?.let { it.performClick(); clicked = true }
            }
            if (clicked) return
            // On the shared screen they are Compose buttons.
            if (Ui.exists(text, exact = true)) { Ui.click(text); return }
            SystemClock.sleep(150)
        }
        throw AssertionError("No hay chip \"$text\"")
    }

    private fun findViews(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { findViews(v.getChildAt(it)) } else listOf(v)

    companion object {
        private const val TAG = "GraphLifecyclePcTest"
    }
}
