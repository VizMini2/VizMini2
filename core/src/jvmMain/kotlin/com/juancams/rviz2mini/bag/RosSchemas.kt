// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bag

import com.juancams.rviz2mini.ros.dyn.IdlException
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec

/** Utilities for recording ROS 2 bags with the same content rosbag2 writes. */
object RosSchemas {

    private val SEPARATOR = "=".repeat(80)

    /**
     * Text of a type's "ros2msg" schema, as rosbag2 generates it: the type's .msg and then,
     * for each submessage (recursive, without repeats, in order of appearance), a line of 80 '=',
     * "MSG: pkg/Type" and its .msg. Throws [IdlException] if the type or any dependency does not exist.
     */
    fun definition(registry: InterfaceRegistry, type: String): String {
        val name = InterfaceRegistry.normalize(type, "msg") ?: throw IdlException("Invalid type: $type")
        val spec = registry.msg(name)
        val deps = LinkedHashSet<String>()
        fun walk(s: MsgSpec) {
            for (f in s.fields) {
                val n = f.nested ?: continue
                if (deps.add(n.name)) walk(n)
            }
        }
        walk(spec)
        val sb = StringBuilder(text(registry, name))
        for (d in deps) {
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            // rosbag2 writes the name without "/msg/": "MSG: geometry_msgs/Vector3".
            val p = d.split('/')
            sb.append('\n').append(SEPARATOR).append('\n')
            sb.append("MSG: ").append(p[0]).append('/').append(p[2]).append('\n')
            sb.append(text(registry, d))
        }
        return sb.toString()
    }

    private fun text(registry: InterfaceRegistry, name: String): String =
        registry.definition(name) ?: throw IdlException("Unknown type $name")

    /**
     * Value of a channel's "offered_qos_profiles" key, in the YAML rosbag2 (Jazzy) writes.
     * `ros2 bag play` uses it to choose the QoS when republishing (e.g. transient_local on /tf_static).
     */
    fun offeredQosProfiles(reliable: Boolean = true, transientLocal: Boolean = false, depth: Int = 0): String {
        val inf = "    sec: 9223372036\n    nsec: 854775807\n"
        return "- history: " + (if (depth > 0) "keep_last" else "unknown") + "\n" +
            "  depth: $depth\n" +
            "  reliability: " + (if (reliable) "reliable" else "best_effort") + "\n" +
            "  durability: " + (if (transientLocal) "transient_local" else "volatile") + "\n" +
            "  deadline:\n$inf" +
            "  lifespan:\n$inf" +
            "  liveliness: automatic\n" +
            "  liveliness_lease_duration:\n$inf" +
            "  avoid_ros_namespace_conventions: false"
    }

    /** Channel metadata rosbag2 sets for a topic. */
    fun channelMetadata(reliable: Boolean = true, transientLocal: Boolean = false): Map<String, String> =
        mapOf("offered_qos_profiles" to offeredQosProfiles(reliable, transientLocal))
}
