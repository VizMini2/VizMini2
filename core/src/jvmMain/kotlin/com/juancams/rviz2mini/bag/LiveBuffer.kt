// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bag

import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * The latest data that arrived on some topics, in memory (like a video's "instant replay"): to go
 * back in 3D without having recorded a bag. Keeps the last `windowS` seconds without exceeding `maxBytes`; for the
 * "latched" ones (/tf_static, the map, the robot description) always the latest, even if it is older. [dump] writes it
 * as MCAP, which plays back with the usual time bar.
 *
 * It does not record while a bag is playing (its messages also reach these subscriptions).
 */
class LiveBuffer(private val registry: InterfaceRegistry, val windowS: Int = 60, private val maxBytes: Long = 200L shl 20) {

    private class Item(val topic: String, val timeNs: Long, val data: ByteArray)

    private val items = ArrayDeque<Item>()
    private val latched = HashMap<String, Item>()
    private var bytes = 0L
    private val subs = HashMap<String, DynRos.Subscription>()
    /** topic → type of what is being stored. */
    private val types = HashMap<String, String>()
    @Volatile private var closed = false

    /** The topics to store (topic → ROS type); those no longer present stop being listened to and are forgotten. */
    @Synchronized fun setTopics(wanted: Map<String, String>) {
        if (closed) return
        for (t in subs.keys.toList()) if (wanted[t] != types[t]) {
            subs.remove(t)?.close(); types.remove(t); latched.remove(t)
            items.removeAll { if (it.topic == t) { bytes -= it.data.size; true } else false }
        }
        for ((topic, type) in wanted) {
            if (topic in subs) continue
            val spec = runCatching { registry.msg(type) }.getOrNull() ?: continue
            val isLatched = BagRecorder.latched(topic)
            val sub = runCatching {
                DynRos.subscribe(topic, spec, BagRecorder.qos(isLatched)) { v ->
                    if (closed || RosConnection.bagPlaybackActive || BagSession.player != null) return@subscribe
                    val data = runCatching { BagRecorder.serialize(spec, v) }.getOrNull() ?: return@subscribe
                    add(Item(topic, System.currentTimeMillis() * 1_000_000L, data), isLatched)
                }
            }.getOrNull() ?: continue
            subs[topic] = sub; types[topic] = type
        }
    }

    /** For the tests (no DDS): as if a message had arrived. */
    @Synchronized internal fun recordForTest(topic: String, type: String, timeNs: Long, data: ByteArray) {
        types[topic] = type; add(Item(topic, timeNs, data), BagRecorder.latched(topic))
    }

    @Synchronized private fun add(item: Item, isLatched: Boolean) {
        if (isLatched) { latched[item.topic] = item; return }
        items.addLast(item); bytes += item.data.size
        val oldest = item.timeNs - windowS * 1_000_000_000L
        while (items.isNotEmpty() && (items.first().timeNs < oldest || bytes > maxBytes)) bytes -= items.removeFirst().data.size
    }

    /** Seconds stored (0 if nothing). */
    @Synchronized fun spanS(): Double = if (items.size < 2) 0.0 else (items.last().timeNs - items.first().timeNs) / 1e9

    @Synchronized fun sizeBytes(): Long = bytes + latched.values.sumOf { it.data.size.toLong() }

    /**
     * Writes what is stored as MCAP (the latched ones first, with the instant of the first message, so that the map and
     * the static ones are there from the start). Returns false if there is nothing.
     */
    fun dump(file: File): Boolean {
        val (list, fixed, typeOf) = synchronized(this) { Triple(items.toList(), latched.values.toList(), HashMap(types)) }
        if (list.isEmpty()) return false
        val t0 = list.first().timeNs
        file.parentFile?.mkdirs()
        val w = McapWriter(BufferedOutputStream(FileOutputStream(file), 1 shl 16), fileName = file.name)
        try {
            val channels = HashMap<String, Int>()
            fun channel(topic: String): Int? = channels[topic] ?: typeOf[topic]?.let { type ->
                val isLatched = BagRecorder.latched(topic)
                val schema = w.addSchema(type, "ros2msg", RosSchemas.definition(registry, type))
                w.addChannel(schema, topic, "cdr", RosSchemas.channelMetadata(reliable = isLatched, transientLocal = isLatched)).also { channels[topic] = it }
            }
            for (it in fixed) channel(it.topic)?.let { c -> w.writeMessage(c, t0, t0, it.data) }
            for (it in list) channel(it.topic)?.let { c -> w.writeMessage(c, it.timeNs, it.timeNs, it.data) }
        } finally {
            w.close()
        }
        return true
    }

    @Synchronized fun close() {
        closed = true
        subs.values.forEach { runCatching { it.close() } }
        subs.clear(); types.clear(); items.clear(); latched.clear(); bytes = 0
    }
}
