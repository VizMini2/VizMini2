// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros

import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import us.ihmc.jros2.ROS2QoSProfile
import java.util.concurrent.ConcurrentHashMap

/**
 * ROS 2 nodes on the network, like `ros2 node list`. DDS knows nothing about nodes: each rmw participant
 * publishes on the DDS topic "ros_discovery_info" (no "rt" prefix) which nodes it has and the GIDs of their
 * readers and writers. The latest from each participant is stored here; [RosGraph] cross-references it with the
 * live endpoints (so participants that leave disappear).
 */
object RosNodes {

    /** A node as announced by its participant. GIDs in hexadecimal (32 characters). */
    data class Entry(val namespace: String, val name: String, val readers: List<String>, val writers: List<String>) {
        val fullName: String get() = if (namespace.endsWith("/")) namespace + name else "$namespace/$name"
    }

    private const val TOPIC = "ros_discovery_info"
    private const val TYPE = "rmw_dds_common/msg/ParticipantEntitiesInfo"

    /** rmw_dds_common definitions (Jazzy), which are not in the app's interfaces package. */
    val DEFINITIONS = linkedMapOf(
        "rmw_dds_common/msg/Gid" to "char[16] data",
        "rmw_dds_common/msg/NodeEntitiesInfo" to "string<=256 node_namespace\nstring<=256 node_name\nGid[] reader_gid_seq\nGid[] writer_gid_seq",
        TYPE to "Gid gid\nNodeEntitiesInfo[] node_entities_info_seq",
    )

    /** Participant GID (hex) -> its nodes. */
    private val byParticipant = ConcurrentHashMap<String, List<Entry>>()
    @Volatile private var subscription: DynRos.Subscription? = null

    /** Subscribes once; the subscription recreates itself on reconnect. */
    @Synchronized
    fun start() {
        if (subscription != null) return
        try {
            val registry = Interfaces.get()
            for ((name, text) in DEFINITIONS) if (!registry.has(name)) registry.define(name, text)
            // rmw publishes it reliable + transient local: that way everyone's data arrives when joining.
            val qos = ROS2QoSProfile().apply {
                reliability(ROS2QoSProfile.Reliability.RELIABLE)
                durability(ROS2QoSProfile.Durability.TRANSIENT_LOCAL)
                history(ROS2QoSProfile.History.KEEP_LAST)
                depth(100)
            }
            subscription = DynRos.subscribeRaw(TOPIC, registry.msg(TYPE), qos) { v -> onInfo(v) }
        } catch (e: Exception) {
            Log.e("VizMini2", "No se pudo suscribir a $TOPIC", e)
        }
    }

    /** When the network changes (domain...) the previous data is no longer valid. */
    fun clear() = byParticipant.clear()

    /** Copy of what is known: participant GID -> nodes. */
    fun snapshot(): Map<String, List<Entry>> = HashMap(byParticipant)

    private fun onInfo(v: MsgValue) {
        val gid = gidHex(v["gid"]) ?: return
        val nodes = (v["node_entities_info_seq"] as? List<*>).orEmpty().mapNotNull { n ->
            val m = n as? Map<*, *> ?: return@mapNotNull null
            Entry(
                (m["node_namespace"] as? String ?: "/"),
                (m["node_name"] as? String ?: return@mapNotNull null),
                (m["reader_gid_seq"] as? List<*>).orEmpty().mapNotNull { gidHex(it) },
                (m["writer_gid_seq"] as? List<*>).orEmpty().mapNotNull { gidHex(it) },
            )
        }
        byParticipant[gid] = nodes
    }

    /** rmw_dds_common/Gid -> "010f..." (16 bytes, same as the graph GUIDs). */
    fun gidHex(v: Any?): String? {
        val data = ((v as? Map<*, *>)?.get("data") as? List<*>) ?: return null
        if (data.size < 16) return null
        return data.take(16).joinToString("") { "%02x".format(((it as? Number)?.toInt() ?: 0) and 0xFF) }
    }
}
