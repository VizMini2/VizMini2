// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.bag

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.MsgValue
import us.ihmc.fastddsjava.cdr.CDRBuffer
import us.ihmc.jros2.ROS2QoSProfile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Records topics into an MCAP compatible with rosbag2 (`ros2 bag play`). Each message arrives
 * deserialized (dynamic type) and is converted back to CDR; writing runs on its own thread so as not to
 * slow down the DDS threads.
 */
class BagRecorder(
    val file: File,
    topics: List<Pair<String, String>>, /* (topic, ROS type "pkg/msg/Type") */
    registry: InterfaceRegistry
) {
    private class Item(val channel: Int, val timeNs: Long, val data: ByteArray)

    private val queue = LinkedBlockingQueue<Item>(20_000)
    private val writer = McapWriter(BufferedOutputStream(FileOutputStream(file), 1 shl 16), fileName = file.name)
    private val subscriptions = ArrayList<DynRos.Subscription>()
    private val startNs = System.currentTimeMillis() * 1_000_000L
    val messages = AtomicLong()
    val dropped = AtomicLong()
    @Volatile private var running = true
    /** Why recording stopped (disk full...), or null if all is well. The UI checks it and stops. */
    @Volatile var error: String? = null
        private set
    /** Messages per topic, to show them while recording. */
    val perTopic = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    private val thread = Thread({
        try {
            while (running || queue.isNotEmpty()) {
                val item = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                writer.writeMessage(item.channel, item.timeNs, item.timeNs, item.data)
            }
        } catch (t: Throwable) {
            Log.e("VizMini2", "Error escribiendo ${file.name}", t)
            error = t.message ?: t.javaClass.simpleName
            running = false
        } finally {
            try { writer.close() } catch (t: Throwable) {
                Log.e("VizMini2", "Error cerrando ${file.name}", t)
                if (error == null) error = t.message ?: t.javaClass.simpleName
            }
        }
    }, "bag-writer")

    init {
        try {
            subscribe(topics, registry)
        } catch (e: Exception) {
            // Not started: nothing open and no half-written file.
            for (sub in subscriptions) sub.close()
            try { writer.close() } catch (_: Exception) {}
            file.delete()
            throw e
        }
        thread.start()
    }

    private fun subscribe(topics: List<Pair<String, String>>, registry: InterfaceRegistry) {
        for ((topic, type) in topics) {
            val spec = registry.msg(type)
            val latched = latched(topic)
            val schema = writer.addSchema(type, "ros2msg", RosSchemas.definition(registry, type))
            val channel = writer.addChannel(schema, topic, "cdr", RosSchemas.channelMetadata(reliable = latched, transientLocal = latched))
            val counter = AtomicLong()
            perTopic[topic] = counter
            subscriptions += DynRos.subscribe(topic, spec, qos(latched)) { v ->
                if (!running) return@subscribe
                val data = try { serialize(spec, v) } catch (e: Exception) { dropped.incrementAndGet(); return@subscribe }
                if (queue.offer(Item(channel, System.currentTimeMillis() * 1_000_000L, data))) { messages.incrementAndGet(); counter.incrementAndGet() } else dropped.incrementAndGet()
            }
        }
    }

    val elapsedMs: Long get() = (System.currentTimeMillis() * 1_000_000L - startNs) / 1_000_000L

    /** Stops and closes the file (waits for pending writes). Returns [error]. */
    fun stop(): String? {
        for (s in subscriptions) s.close()
        running = false
        thread.join(5000)
        return error
    }

    companion object {
        /** Topics that are published only once and kept for late joiners (TRANSIENT_LOCAL). */
        fun latched(topic: String) = topic == "/tf_static" || topic.endsWith("robot_description") || topic == "/map"

        /** Best effort matches any publisher; "latched" ones need reliable + transient local. */
        fun qos(latched: Boolean): ROS2QoSProfile =
            if (!latched) RosConnection.sensorQos().apply { depth(100) }
            else ROS2QoSProfile().apply {
                reliability(ROS2QoSProfile.Reliability.RELIABLE)
                durability(ROS2QoSProfile.Durability.TRANSIENT_LOCAL)
                history(ROS2QoSProfile.History.KEEP_LAST)
                depth(10)
            }

        /** Dynamic value -> full CDR payload (with the encapsulation header). */
        fun serialize(spec: MsgSpec, v: MsgValue): ByteArray = com.juancams.rviz2mini.ros.dyn.CdrBytes.serialize(spec, v)

        /** "vizmini2_2026-10-09_12-30-05.mcap" */
        fun defaultName(): String = "vizmini2_" + java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.ROOT).format(java.util.Date()) + ".mcap"
    }
}
