// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sensor_msgs.LaserScan
import us.ihmc.jros2.ROS2QoSProfile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app over Zenoh against a robot with rmw_zenoh_cpp: on the PC `ros2 run rmw_zenoh_cpp rmw_zenohd` and
 * tools/fake_robot.py with RMW_IMPLEMENTATION=rmw_zenoh_cpp, domain 77 (tools/pc_tests.sh -r rmw_zenoh_cpp).
 * Connects to the router, sees the graph, receives a correctly decoded /scan and the TF tree.
 */
@RunWith(AndroidJUnit4::class)
class ZenohPcTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        // The PC servers must run over Zenoh (tools/pc_tests.sh -r rmw_zenoh_cpp also starts the router).
        org.junit.Assume.assumeTrue("Solo con -r rmw_zenoh_cpp", TestArgs.rmw == "rmw_zenoh_cpp")
        savedRos = context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_zenoh", zenohRouter = "tcp/${TestArgs.pcIp}:7447"))
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val s = RosConnection.status
        assertTrue("Sin conexión por Zenoh: $s", s is RosConnection.Status.Connected && s.zenoh != null)
    }

    @After
    fun tearDown() {
        if (savedRos == null) return
        RosConnection.stop()
        context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    @Test
    fun graphTopicsAndTf() {
        // The graph comes from the liveliness tokens: the robot's node, its topics and its services.
        var g = RosConnection.graph()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while ((g?.nodes()?.none { it.name == "/fake_robot" } != false) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(200); g = RosConnection.graph()
        }
        val robot = g!!.nodes().first { it.name == "/fake_robot" }
        Log.i(TAG, "nodo: $robot")
        assertEquals("Zenoh", robot.dds)
        assertTrue(robot.publishers.toString(), "/scan" in robot.publishers && "/tf" in robot.publishers)
        assertEquals("sensor_msgs/msg/LaserScan", g.topics().first { it.name == "/scan" }.type)
        assertTrue(g.services().map { it.name }.toString(), g.services().any { it.name == "/fake_robot/list_parameters" })

        // /scan arrives and is decoded (rmw_zenoh's CDR carries the encapsulation header, as over DDS).
        val scans = CopyOnWriteArrayList<Int>()
        val sub = RosConnection.subscribe("/scan", LaserScan::class.java, ROS2QoSProfile()) { reader ->
            reader.read()?.let { scans.add(it.ranges.size()) }
        }
        try {
            val until = SystemClock.uptimeMillis() + 8000
            while (scans.size < 10 && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
            Log.i(TAG, "scans: ${scans.size}, puntos ${scans.firstOrNull()}")
            assertTrue("sin /scan: ${sub.error}", scans.size >= 10)
            assertEquals(360, scans.first()) // fake_robot: 360 readings
        } finally {
            sub.cancel()
        }

        // TF: the robot's frames in the tree.
        val tfUntil = SystemClock.uptimeMillis() + 8000
        while (!RosConnection.tfTree.contains("base_link") && SystemClock.uptimeMillis() < tfUntil) SystemClock.sleep(200)
        Log.i(TAG, "frames: ${RosConnection.tfTree.frameIds()}")
        assertTrue(RosConnection.tfTree.frameIds().toString(), RosConnection.tfTree.contains("base_link"))
    }

    /** The app publishes over Zenoh: a Twist to buttons_test_server and its echo back (the hash comes from its subscription). */
    @Test
    fun publishTwistAndReceiveTheEcho() {
        val echoes = CopyOnWriteArrayList<String>()
        val registry = com.juancams.rviz2mini.ros.dyn.Interfaces.get()
        val sub = com.juancams.rviz2mini.ros.dyn.DynRos.subscribe("/rviz2mini_test/button_echo", registry.msg("std_msgs/msg/String"),
            RosConnection.defaultQos()) { echoes.add(it["data"].toString()) }
        val pub = com.juancams.rviz2mini.ros.dyn.DynRos.publisher("/rviz2mini_test/button_twist", registry.msg("geometry_msgs/msg/Twist"),
            RosConnection.defaultQos())
        try {
            val msg = com.juancams.rviz2mini.ros.dyn.MsgValue().apply {
                put("linear", com.juancams.rviz2mini.ros.dyn.MsgValue().apply { put("x", 0.4); put("y", 0.0); put("z", 0.0) })
                put("angular", com.juancams.rviz2mini.ros.dyn.MsgValue().apply { put("x", 0.0); put("y", 0.0); put("z", 0.2) })
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (echoes.none { it == "twist 0.40 0.20" } && SystemClock.uptimeMillis() < deadline) {
                pub.publish(msg)
                SystemClock.sleep(300)
            }
            Log.i(TAG, "ecos: ${echoes.take(3)}, suscriptores ${pub.matched()}")
            assertTrue("sin eco: $echoes", echoes.any { it == "twist 0.40 0.20" })
            assertTrue(pub.matched() >= 1)
            // The app announces itself in the robot's graph: its node and its publisher.
            val me = RosConnection.graph()!!.nodes().firstOrNull { it.name == "/rviz2mini_zenoh" }
            assertTrue("la app no sale en el grafo: ${RosConnection.graph()!!.nodes().map { it.name }}", me != null && "/rviz2mini_test/button_twist" in me.publishers)
        } finally {
            pub.close(); sub.close()
        }
    }

    /** Services, parameters and actions over Zenoh: queries to each server's queryable (C++ and Python). */
    @Test
    fun servicesParametersAndAction() {
        val registry = com.juancams.rviz2mini.ros.dyn.Interfaces.get()
        // Service (C++ add_two_ints_server).
        val add = com.juancams.rviz2mini.ros.dyn.DynServiceClient("/rviz2mini_test/cpp/add_two_ints", registry.srv("example_interfaces/srv/AddTwoInts"))
        try {
            val r = add.call(com.juancams.rviz2mini.ros.dyn.MsgValue().apply { put("a", 2L); put("b", 40L) }, 8000)
            Log.i(TAG, "add_two_ints: $r")
            assertEquals(42L, r["sum"])
        } finally {
            add.close()
        }
        // Parameters (fake_robot's, Python).
        val params = com.juancams.rviz2mini.params.ParamClient("/fake_robot", registry)
        try {
            val all = params.all()
            Log.i(TAG, "parámetros de fake_robot: ${all.map { it.descriptor.name }}")
            assertTrue(all.map { it.descriptor.name }.toString(), all.any { it.descriptor.name == "use_sim_time" })
        } finally {
            params.close()
        }
        // Action (C++ fibonacci_action_server): goal, feedback and result.
        val a = registry.action(CppFibonacci.type())
        val client = com.juancams.rviz2mini.ros.dyn.DynActionClient(CppFibonacci.ACTION, a, registry.srv("action_msgs/srv/CancelGoal"))
        try {
            var feedback = 0
            val o = client.run(com.juancams.rviz2mini.ros.dyn.MsgValue().apply { put("order", 5L) }, 15_000, {}, { feedback++ }, { false })
            Log.i(TAG, "fibonacci: ${o.statusLabel} ${o.result} (feedback $feedback)")
            assertEquals(6, (o.result["sequence"] as List<*>).size)
            assertTrue("sin feedback", feedback > 0)
        } finally {
            client.close()
        }
    }

    /**
     * TRANSIENT_LOCAL over Zenoh: fake_robot publishes /tf_static, /robot_description and /map only once at startup, and
     * they still arrive (the advanced subscriber requests the history from each publisher's cache). And the other way round: what the app
     * publishes "latched" is received by a subscriber that arrives later.
     */
    @Test
    fun transientLocalLateJoiners() {
        // fake_robot's static frames (they are only on /tf_static).
        val tfUntil = SystemClock.uptimeMillis() + 10_000
        while (!RosConnection.tfTree.contains("camera_optical") && SystemClock.uptimeMillis() < tfUntil) SystemClock.sleep(200)
        Log.i(TAG, "frames con los estáticos: ${RosConnection.tfTree.frameIds()}")
        assertTrue(RosConnection.tfTree.frameIds().toString(), RosConnection.tfTree.contains("camera_optical"))

        val registry = com.juancams.rviz2mini.ros.dyn.Interfaces.get()
        val latched = com.juancams.rviz2mini.rviz.display.TopicDisplay.qosProfile(com.juancams.rviz2mini.rviz.display.QosPreset.LATCHED)
        val urdf = CopyOnWriteArrayList<String>()
        val maps = CopyOnWriteArrayList<Long>()
        val urdfSub = com.juancams.rviz2mini.ros.dyn.DynRos.subscribe("/robot_description", registry.msg("std_msgs/msg/String"), latched) {
            urdf.add(it["data"].toString())
        }
        val mapSub = com.juancams.rviz2mini.ros.dyn.DynRos.subscribe("/map", registry.msg("nav_msgs/msg/OccupancyGrid"), latched) {
            maps.add(((it["info"] as com.juancams.rviz2mini.ros.dyn.MsgValue)["width"] as Number).toLong())
        }
        try {
            val until = SystemClock.uptimeMillis() + 10_000
            while ((urdf.isEmpty() || maps.isEmpty()) && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
            Log.i(TAG, "robot_description: ${urdf.firstOrNull()?.length} caracteres; mapa de ${maps.firstOrNull()} celdas de ancho")
            assertTrue("sin /robot_description", urdf.any { it.contains("<robot") })
            assertTrue("sin /map", maps.any { it > 0 })
        } finally {
            urdfSub.close(); mapSub.close()
        }

        // The app publishes latched on a new topic (the hash, from the same type on /robot_description) and a subscriber
        // that arrives later receives the message.
        val pub = com.juancams.rviz2mini.ros.dyn.DynRos.publisher("/rviz2mini_test/zenoh_latched", registry.msg("std_msgs/msg/String"), latched)
        val got = CopyOnWriteArrayList<String>()
        try {
            // The publisher attaches in the background: until then publish returns false (same as over DDS).
            val ready = SystemClock.uptimeMillis() + 5000
            while (!pub.isReady && SystemClock.uptimeMillis() < ready) SystemClock.sleep(50)
            assertTrue("no se pudo publicar latched",
                pub.publish(com.juancams.rviz2mini.ros.dyn.MsgValue().apply { put("data", "latched desde la app") }))
            SystemClock.sleep(1000)
            val late = com.juancams.rviz2mini.ros.dyn.DynRos.subscribe("/rviz2mini_test/zenoh_latched", registry.msg("std_msgs/msg/String"), latched) {
                got.add(it["data"].toString())
            }
            try {
                val until = SystemClock.uptimeMillis() + 8000
                while (got.isEmpty() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
                Log.i(TAG, "latched de la app: $got")
                assertEquals(listOf("latched desde la app"), got.take(1))
            } finally {
                late.close()
            }
        } finally {
            pub.close()
        }
    }

    private companion object {
        const val TAG = "ZenohPcTest"
    }
}
