// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosGraph
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import us.ihmc.jros2.ROS2SubscriptionCallback
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connection over WebSocket with foxglove_bridge instead of DDS (tools/pc_tests.sh BridgePcTest: the
 * bridge on port 8765, fake_robot.py and the buttons server). It does not open any screen.
 */
@RunWith(AndroidJUnit4::class)
class BridgePcTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedRos = prefs("rviz2mini_ros").all
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("pc") != "true") return
        RosConnection.stop()
        prefs("rviz2mini_ros").edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun topicsTfAndPublishingThroughTheBridge() {
        // A different domain on purpose: if anything arrived, it would be through the bridge and not DDS.
        val url = "ws://${TestArgs.pcIp}:8765"
        RosConnection.applyConfig(RosConfig(domainId = 101, nodeName = "rviz2mini_bridge", bridgeUrl = url))
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val status = RosConnection.status
        Log.i(TAG, "estado: $status")
        assertTrue("sin conexión con el bridge: $status", status is RosConnection.Status.Connected && status.bridge == url)

        // Graph: the topics the bridge announces.
        var graph: RosGraph? = null
        val g = CountDownLatch(1)
        RosConnection.discover(waitMs = 1500) { graph = it; g.countDown() }
        assertTrue(g.await(10, TimeUnit.SECONDS))
        val topics = graph!!.topics().map { it.name to it.type }.toMap()
        Log.i(TAG, "topics: ${topics.keys.sorted()}")
        assertEquals("nav_msgs/msg/Odometry", topics["/odom"])
        assertEquals("sensor_msgs/msg/LaserScan", topics["/scan"])

        // Typed messages (like the displays) and dynamic ones (like indicators and plots).
        val odom = AtomicInteger()
        val sub = RosConnection.subscribe("/odom", nav_msgs.Odometry::class.java, RosConnection.sensorQos(), ROS2SubscriptionCallback { r ->
            val m = nav_msgs.Odometry()
            if (r.read(m) && m.header.frameIdAsString.isNotEmpty()) odom.incrementAndGet()
        })
        val ranges = CopyOnWriteArrayList<Int>()
        val scan = DynRos.subscribe("/scan", Interfaces.get().msg("sensor_msgs/msg/LaserScan"), RosConnection.sensorQos()) { v ->
            ranges.add((v["ranges"] as List<*>).size)
        }
        val until = SystemClock.uptimeMillis() + 10_000
        while ((odom.get() < 5 || ranges.size < 5) && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
        Log.i(TAG, "odom ${odom.get()}, scan ${ranges.size} (${ranges.firstOrNull()} rangos)")
        assertTrue("no llega /odom", odom.get() >= 5)
        assertTrue("no llega /scan", ranges.size >= 5 && ranges.first() > 100)

        // TF through the bridge into the same tree.
        val frames = RosConnection.tfTree.frameIds()
        Log.i(TAG, "frames: $frames")
        assertTrue("faltan frames: $frames", frames.containsAll(listOf("map", "odom", "base_link")))

        // Publish: a Twist that the buttons server returns as text.
        val echoes = CopyOnWriteArrayList<String>()
        val echo = DynRos.subscribe("/rviz2mini_test/button_echo", Interfaces.get().msg("std_msgs/msg/String"), RosConnection.defaultQos()) { v ->
            echoes.add(v["data"].toString())
        }
        val pub = DynRos.publisher("/rviz2mini_test/button_twist", Interfaces.get().msg("geometry_msgs/msg/Twist"), RosConnection.defaultQos())
        val sent = SystemClock.uptimeMillis() + 8_000
        while (echoes.none { it.startsWith("twist 0.42") } && SystemClock.uptimeMillis() < sent) {
            pub.publish(MsgValue().apply { put("linear", MsgValue().apply { put("x", 0.42) }) })
            SystemClock.sleep(300)
        }
        Log.i(TAG, "eco: ${echoes.lastOrNull()}")
        assertTrue("el Twist no llega por el bridge: $echoes", echoes.any { it.startsWith("twist 0.42") })

        // Services: not through the bridge (with a clear error, without hanging).
        assertTrue(RosConnection.status is RosConnection.Status.Connected)
        sub.cancel(); scan.close(); echo.close(); pub.close()
    }

    @Test
    fun servicesParametersAndActionsThroughTheBridge() {
        val url = "ws://${TestArgs.pcIp}:8765"
        RosConnection.applyConfig(RosConfig(domainId = 101, nodeName = "rviz2mini_bridge_srv", bridgeUrl = url))
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue(RosConnection.status is RosConnection.Status.Connected)
        val registry = Interfaces.get()

        // Services: in the graph and with a call.
        var graph: RosGraph? = null
        val until = SystemClock.uptimeMillis() + 10_000
        while (graph?.services()?.any { it.name == "/rviz2mini_test/add_two_ints" } != true && SystemClock.uptimeMillis() < until) {
            graph = RosConnection.graph(); SystemClock.sleep(200)
        }
        Log.i(TAG, "servicios: ${graph?.services()?.map { it.name }}")
        assertEquals("example_interfaces/srv/AddTwoInts", graph!!.services().first { it.name == "/rviz2mini_test/add_two_ints" }.type)
        val add = com.juancams.rviz2mini.ros.dyn.DynServiceClient("/rviz2mini_test/add_two_ints", registry.srv("example_interfaces/srv/AddTwoInts"))
        try {
            assertEquals(42L, add.call(MsgValue().apply { put("a", 40L); put("b", 2L) }, 8000)["sum"])
        } finally { add.close() }
        val missing = com.juancams.rviz2mini.ros.dyn.DynServiceClient("/rviz2mini_test/nope", registry.srv("example_interfaces/srv/AddTwoInts"))
        val err = try { missing.call(MsgValue(), 4000); null } catch (e: com.juancams.rviz2mini.ros.dyn.CallException) { e.message } finally { missing.close() }
        Log.i(TAG, "sin servidor: $err")
        assertTrue("$err", err != null && "No server" in err)

        // Parameters: they are node services.
        val params = com.juancams.rviz2mini.params.ParamClient("/rviz2mini_test/parameter_blackboard", registry)
        try {
            val all = params.all().associateBy { it.descriptor.name }
            Log.i(TAG, "parámetros: ${all.keys}")
            assertEquals(1.5, all.getValue("speed").value.value as Double, 1e-9)
            assertEquals(null, params.set("speed", com.juancams.rviz2mini.params.ParamModel.Value(com.juancams.rviz2mini.params.ParamModel.Type.DOUBLE, 2.5)))
            assertEquals(2.5, params.all().first { it.descriptor.name == "speed" }.value.value as Double, 1e-9)
            params.set("speed", com.juancams.rviz2mini.params.ParamModel.Value(com.juancams.rviz2mini.params.ParamModel.Type.DOUBLE, 1.5))
        } finally { params.close() }

        // Nodes and lifecycle: from the bridge's connectionGraph.
        var nodes: List<RosGraph.Node> = emptyList()
        val nodesUntil = SystemClock.uptimeMillis() + 10_000
        while (nodes.none { it.name == "/fake_robot" } && SystemClock.uptimeMillis() < nodesUntil) { nodes = RosConnection.graph()?.nodes().orEmpty(); SystemClock.sleep(300) }
        Log.i(TAG, "nodos: ${nodes.map { it.name }}")
        val robot = nodes.first { it.name == "/fake_robot" }
        assertTrue(robot.publishers.toString(), "/odom" in robot.publishers && "/scan" in robot.publishers)
        val lc = RosConnection.graph()!!.lifecycleNodes()
        Log.i(TAG, "lifecycle: $lc")
        assertTrue(lc.toString(), "/rviz2mini_test/lc_talker" in lc)
        val getState = com.juancams.rviz2mini.ros.dyn.DynServiceClient("/rviz2mini_test/lc_talker/get_state", registry.srv("lifecycle_msgs/srv/GetState"))
        try {
            val st = getState.call(MsgValue(), 8000)["current_state"] as Map<*, *>
            Log.i(TAG, "get_state: $st")
            assertTrue(st["label"].toString().isNotEmpty())
        } finally { getState.close() }

        // Actions: with include_hidden the bridge also carries its internal services and topics.
        val fib = com.juancams.rviz2mini.ros.dyn.DynActionClient("/rviz2mini_test/fibonacci", registry.action("example_interfaces/action/Fibonacci"),
            registry.srv("action_msgs/srv/CancelGoal"))
        try {
            SystemClock.sleep(1000)
            val feedback = AtomicInteger()
            val out = fib.run(MsgValue().apply { put("order", 5L) }, 8000, {}, { feedback.incrementAndGet() }, { false })
            Log.i(TAG, "fibonacci: ${out.statusLabel} ${out.result} feedback=${feedback.get()}")
            assertEquals(com.juancams.rviz2mini.ros.dyn.DynActionClient.STATUS_SUCCEEDED, out.status)
            assertEquals(listOf(0L, 1L, 1L, 2L, 3L, 5L), out.result["sequence"])
            assertTrue("sin feedback por el bridge", feedback.get() > 0)
        } finally { fib.close() }
    }

    private companion object {
        const val TAG = "BridgePc"
    }
}
