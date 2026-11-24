// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.bag.BagPlayer
import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.bag.McapReader
import com.juancams.rviz2mini.bag.McapWriter
import com.juancams.rviz2mini.bag.RosSchemas
import com.juancams.rviz2mini.params.ParamClient
import com.juancams.rviz2mini.params.ParamModel
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosGraph
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Parameters, MCAP bags and Discovery Server against ROS 2 on the PC. Optional (`-e pc true`, and
 * `-e peers <PC IP>` if the Wi-Fi filters multicast). On the PC, besides fake_robot.py:
 *   ros2 run demo_nodes_cpp parameter_blackboard --ros-args -r __ns:=/rviz2mini_test \
 *       -p speed:=1.5 -p name:=robot -p enabled:=true -p gains:="[1.0, 2.0]" -p ids:="[1, 2, 3]"
 *   fastdds discovery -i 0 -l <PC IP> -p 11811
 *   ROS_DOMAIN_ID=79 ROS_DISCOVERY_SERVER=<PC IP>:11811 ros2 run demo_nodes_cpp talker \
 *       --ros-args -r chatter:=/rviz2mini_test/ds_chatter
 */
@RunWith(AndroidJUnit4::class)
class NewFeaturesPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val registry by lazy { Interfaces.get() }
    private var savedRos: Map<String, *>? = null
    private val pcIp: String get() = TestArgs.pcIp

    @Before
    fun setUp() {
        assumeTrue("Solo con -e pc true", InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedRos = prefs("rviz2mini_ros").all
        connect(RosConfig(domainId = 77, nodeName = "rviz2mini_phone", discoveryPeers = TestArgs.peers))
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

    private fun connect(cfg: RosConfig) {
        RosConnection.applyConfig(cfg)
        SystemClock.sleep(500)
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Sin conexión ROS 2", RosConnection.status is RosConnection.Status.Connected)
    }

    @Test
    fun parametersListAndSetEveryType() {
        val c = ParamClient("/rviz2mini_test/parameter_blackboard", registry)
        try {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!c.hasServer() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            val all = c.all().associateBy { it.descriptor.name }
            Log.i(TAG, "PARAMS ${all.values.joinToString { it.descriptor.name + "=" + it.value.display() }}")
            assertEquals(ParamModel.Value(ParamModel.Type.DOUBLE, 1.5), all["speed"]!!.value)
            assertEquals(ParamModel.Value(ParamModel.Type.STRING, "robot"), all["name"]!!.value)
            assertEquals(ParamModel.Value(ParamModel.Type.BOOL, true), all["enabled"]!!.value)
            assertEquals(listOf(1.0, 2.0), all["gains"]!!.value.value)
            assertEquals(listOf(1L, 2L, 3L), all["ids"]!!.value.value)

            assertNull(c.set("speed", ParamModel.parse(ParamModel.Type.DOUBLE, "0.75")))
            assertNull(c.set("name", ParamModel.parse(ParamModel.Type.STRING, "turtle ñ")))
            assertNull(c.set("ids", ParamModel.parse(ParamModel.Type.INTEGER_ARRAY, "[4, 5]")))
            val after = c.all().associateBy { it.descriptor.name }
            assertEquals(0.75, after["speed"]!!.value.value)
            assertEquals("turtle ñ", after["name"]!!.value.value)
            assertEquals(listOf(4L, 5L), after["ids"]!!.value.value)
            // Put them back as they were.
            c.set("speed", ParamModel.Value(ParamModel.Type.DOUBLE, 1.5))
            c.set("name", ParamModel.Value(ParamModel.Type.STRING, "robot"))
            c.set("ids", ParamModel.Value(ParamModel.Type.INTEGER_ARRAY, listOf(1L, 2L, 3L)))
        } finally {
            c.close()
        }
    }

    /** Records real topics from the simulated robot; the file is kept to check it with `ros2 bag info`. */
    @Test
    fun recordsRealTopicsToMcap() {
        val dir = File(context.getExternalFilesDir(null), "bags").apply { mkdirs() }
        val file = File(dir, "test_record.mcap").apply { delete() }
        SystemClock.sleep(2000)
        val rec = BagRecorder(file, listOf(
            "/scan" to "sensor_msgs/msg/LaserScan", "/odom" to "nav_msgs/msg/Odometry",
            "/tf" to "tf2_msgs/msg/TFMessage", "/tf_static" to "tf2_msgs/msg/TFMessage"
        ), registry)
        SystemClock.sleep(5000)
        rec.stop()
        McapReader.open(file).use { r ->
            val counts = r.channels.values.associate { it.topic to (r.channelMessageCounts[it.id] ?: 0L) }
            Log.i(TAG, "BAG ${file.absolutePath} ${r.messageCount} mensajes $counts")
            assertTrue(counts.toString(), counts["/scan"]!! > 10 && counts["/odom"]!! > 10 && counts["/tf"]!! > 10)
            assertTrue("tf_static (transient local)", counts["/tf_static"]!! >= 1)
            // What was recorded can be read back.
            val first = r.messages().first { r.channels[it.channelId]!!.topic == "/odom" }
            val v = BagPlayer.deserialize(registry.msg("nav_msgs/msg/Odometry"), first.data)
            assertEquals("odom", (v["header"] as Map<*, *>)["frame_id"])
        }
    }

    /** Plays a bag: the messages reach a subscriber (on the phone itself). */
    @Test
    fun playsBagToTheNetwork() {
        val file = File(context.cacheDir, "play_test.mcap")
        val type = "std_msgs/msg/String"
        McapWriter(file.outputStream()).use { w ->
            val ch = w.addChannel(w.addSchema(type, "ros2msg", RosSchemas.definition(registry, type)), "/rviz2mini_test/bag_play")
            for (i in 0 until 20) {
                w.writeMessage(ch, 1_000_000_000L + i * 50_000_000L, 0, BagRecorder.serialize(registry.msg(type), MsgValue().apply { put("data", "hello $i") }))
            }
        }
        val got = CopyOnWriteArrayList<String>()
        val sub = DynRos.subscribe("/rviz2mini_test/bag_play", registry.msg(type), RosConnection.defaultQos()) { v -> got.add(v["data"] as String) }
        var done = false
        val player = BagPlayer(file, registry, object : BagPlayer.Listener {
            override fun onProgress(positionNs: Long, durationNs: Long) {}
            override fun onFinished(error: String?) { done = true }
        })
        try {
            SystemClock.sleep(1000)
            player.start()
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!done && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            SystemClock.sleep(500)
            Log.i(TAG, "PLAY recibidos ${got.size}: ${got.take(3)}")
            assertTrue("Reproducción terminada", done)
            assertTrue("Recibidos ${got.size}", got.size >= 15)
        } finally {
            player.close()
            sub.close()
        }
    }

    /** With Discovery Server, what the PC server announces is seen (without multicast or peers). */
    @Test
    fun discoveryServerShowsTheGraph() {
        // The Discovery Server is Fast DDS's: with another RMW the PC talker does not even use it.
        org.junit.Assume.assumeTrue("Discovery Server solo con Fast DDS", TestArgs.rmw.startsWith("rmw_fastrtps"))
        connect(RosConfig(domainId = 79, nodeName = "rviz2mini_phone", discoveryServers = "$pcIp:11811"))
        val deadline = SystemClock.uptimeMillis() + 15_000
        var topics: List<RosGraph.Topic> = emptyList()
        while (SystemClock.uptimeMillis() < deadline) {
            topics = RosConnection.graph()?.topics().orEmpty()
            if (topics.any { it.name == "/rviz2mini_test/ds_chatter" }) break
            SystemClock.sleep(300)
        }
        Log.i(TAG, "DISCOVERY SERVER topics=${topics.map { it.name }}")
        val chatter = topics.firstOrNull { it.name == "/rviz2mini_test/ds_chatter" }
        assertNotNull(topics.map { it.name }.toString(), chatter)
        // And data arrives. With the discovered type, like the app: the demo_nodes talker publishes std_msgs/msg/String
        // up to Jazzy and example_interfaces/msg/String from Kilted on. With a fixed type it did not match and looked like a
        // Discovery Server failure.
        val got = CopyOnWriteArrayList<String>()
        val sub = DynRos.subscribe("/rviz2mini_test/ds_chatter", registry.msg(chatter!!.type), RosConnection.defaultQos()) { v -> got.add(v["data"] as String) }
        try {
            val until = SystemClock.uptimeMillis() + 8000
            while (got.isEmpty() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
            Log.i(TAG, "DISCOVERY SERVER datos: ${got.take(2)}")
            assertTrue("Sin datos por el Discovery Server", got.isNotEmpty())
        } finally {
            sub.close()
        }
    }

    /** One client with several concurrent calls from several threads: each one gets its own. */
    @Test
    fun oneClientManyConcurrentCalls() {
        val client = com.juancams.rviz2mini.ros.dyn.DynServiceClient("/rviz2mini_test/cpp/add_two_ints", registry.srv("example_interfaces/srv/AddTwoInts"))
        try {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!client.hasServer() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            val errors = CopyOnWriteArrayList<String>()
            val threads = (0 until 6).map { t ->
                Thread {
                    for (i in 0 until 5) {
                        val a = t * 100L + i
                        try {
                            val r = client.call(MsgValue().apply { put("a", a); put("b", 1000L) }, 8000)
                            if (r["sum"] != a + 1000) errors += "hilo $t: $a + 1000 = ${r["sum"]}"
                        } catch (e: Exception) { errors += "hilo $t: ${e.message}" }
                    }
                }.apply { start() }
            }
            threads.forEach { it.join(60_000) }
            assertTrue(errors.joinToString("\n"), errors.isEmpty())
        } finally {
            client.close()
        }
    }

    /** Two goals at once with the same action client: each with its own feedback and result. */
    @Test
    fun oneActionClientTwoGoals() {
        val a = registry.action(CppFibonacci.type())
        val client = com.juancams.rviz2mini.ros.dyn.DynActionClient(CppFibonacci.ACTION, a, registry.srv("action_msgs/srv/CancelGoal"))
        try {
            SystemClock.sleep(2000)
            val results = java.util.concurrent.ConcurrentHashMap<Long, Any?>()
            val feedbacks = java.util.concurrent.ConcurrentHashMap<Long, Int>()
            val errors = CopyOnWriteArrayList<String>()
            val threads = listOf(4L, 7L).map { order ->
                Thread {
                    // An uncaught exception in a thread brings down the whole test process.
                    try {
                        val o = client.run(MsgValue().apply { put("order", order) }, 10_000, {}, { feedbacks.merge(order, 1, Int::plus) }, { false })
                        results[order] = (o.result["sequence"] as List<*>).size
                    } catch (t: Throwable) {
                        errors.add("order=$order: $t")
                    }
                }.apply { start() }
            }
            threads.forEach { it.join(60_000) }
            Log.i(TAG, "ACCIONES resultados=$results feedback=$feedbacks errores=$errors")
            assertTrue(errors.joinToString("\n"), errors.isEmpty())
            assertEquals(5, results[4L])
            assertEquals(8, results[7L])
            assertTrue(feedbacks.toString(), (feedbacks[4L] ?: 0) > 0 && (feedbacks[7L] ?: 0) > 0)
        } finally {
            client.close()
        }
    }

    companion object {
        private const val TAG = "NewFeaturesPcTest"
    }
}
