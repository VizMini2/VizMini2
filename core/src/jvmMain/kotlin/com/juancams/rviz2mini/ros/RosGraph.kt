// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

/**
 * ROS 2 graph as seen by the node's DDS discovery (like `ros2 topic/service/action list`).
 * Built from the endpoints returned by the native listener; no Android
 * dependencies so it can be tested on the JVM.
 *
 * rmw_fastrtps DDS names: topics "rt/x", requests "rq/xRequest", replies "rr/xReply";
 * actions are the services "<action>/_action/send_goal"... and their feedback/status topics.
 */
class RosGraph(
    val endpoints: List<Endpoint>,
    val participants: List<String> = emptyList(),
    /** From "ros_discovery_info": participant GID -> its nodes (see [RosNodes]). */
    val nodeInfo: Map<String, List<RosNodes.Entry>> = emptyMap(),
    /** Participant GUID prefix -> its DDS name (for those that do not announce nodes). */
    val participantNames: Map<String, String> = emptyMap(),
    /** Name of the app's node: its participant does not appear in the discovered list. */
    val ownName: String? = null
) {

    data class Endpoint(val isWriter: Boolean, val ddsTopic: String, val ddsType: String, val guid: String)

    data class Topic(val name: String, val types: List<String>, val publishers: Int, val subscribers: Int) {
        val type: String get() = types.first()
    }

    data class Service(val name: String, val type: String, val servers: Int, val clients: Int)

    data class Action(val name: String, val type: String, val servers: Int)

    /** A node with what it publishes, what it subscribes to and the services it offers (like `ros2 node info`). */
    data class Node(
        val name: String,
        val isLifecycle: Boolean,
        val publishers: List<String> = emptyList(),
        val subscribers: List<String> = emptyList(),
        val services: List<String> = emptyList(),
        /** DDS of its participant according to the GUID vendor ("Fast DDS", "Cyclone DDS"...); null if unknown. */
        val dds: String? = null,
    )

    /**
     * GUID prefix (24 hex) of our own participant: that of the /tf readers, which the app's node
     * always has. Null if not seen.
     */
    fun ownPrefix(): String? = endpoints.firstOrNull { it.ddsTopic == "rt/tf" && !it.isWriter }?.guid?.take(PREFIX_HEX)

    /** True if any endpoint of another participant is seen (someone else on the network). */
    fun hasOthers(): Boolean {
        val own = ownPrefix() ?: return endpoints.isNotEmpty()
        return endpoints.any { !it.guid.startsWith(own) }
    }

    /** All topics ("rt/..."), with hidden ones ("/_..." at some level) only if requested. */
    fun topics(includeHidden: Boolean = false): List<Topic> {
        val byName = LinkedHashMap<String, MutableList<Endpoint>>()
        for (e in endpoints) {
            if (!e.ddsTopic.startsWith("rt/")) continue
            byName.getOrPut(e.ddsTopic.substring(2)) { ArrayList() }.add(e)
        }
        return byName.filterKeys { includeHidden || !isHidden(it) }.map { (name, list) ->
            Topic(
                name,
                list.map { rosTypeName(it.ddsType) }.distinct().sorted(),
                list.count { it.isWriter },
                list.count { !it.isWriter }
            )
        }.sortedBy { it.name }
    }

    /** Topics with a publisher of any of the given ROS types ("sensor_msgs/msg/Image"). */
    fun topicsOfType(vararg rosTypes: String): List<Topic> =
        topics(includeHidden = true).filter { t -> t.publishers > 0 && t.types.any { it in rosTypes } }

    /**
     * Services: servers read "rq/<name>Request" and clients write to it. Those of
     * actions ("/_action/") are hidden just like `ros2 service list` does.
     */
    fun services(includeHidden: Boolean = false): List<Service> {
        val byName = LinkedHashMap<String, MutableList<Endpoint>>()
        for (e in endpoints) {
            if (!e.ddsTopic.startsWith("rq/") || !e.ddsTopic.endsWith("Request")) continue
            byName.getOrPut(e.ddsTopic.substring(2, e.ddsTopic.length - "Request".length)) { ArrayList() }.add(e)
        }
        return byName.filterKeys { includeHidden || !isHidden(it) }.mapNotNull { (name, list) ->
            val type = serviceTypeName(list.first().ddsType) ?: return@mapNotNull null
            Service(name, type, list.count { !it.isWriter }, list.count { it.isWriter })
        }.sortedBy { it.name }
    }

    /** Actions with a server: those serving "<action>/_action/send_goal". */
    fun actions(): List<Action> =
        services(includeHidden = true).filter { it.name.endsWith(SEND_GOAL) && it.servers > 0 }.mapNotNull { s ->
            val type = actionTypeName(s.type) ?: return@mapNotNull null
            Action(s.name.removeSuffix(SEND_GOAL), type, s.servers)
        }.sortedBy { it.name }

    /**
     * Network nodes (without hidden ones, "_..."), including lifecycle ones. Only those of participants
     * still alive (with some endpoint in the graph). Lifecycle nodes seen through their services
     * but whose participant does not announce nodes are also included.
     */
    fun nodes(): List<Node> {
        val alive = endpoints.mapTo(HashSet()) { it.guid.take(PREFIX_HEX) }
        val byGuid = endpoints.associateBy { it.guid }
        val lifecycle = lifecycleNodes().toSet()
        val out = LinkedHashMap<String, Node>()
        for ((participant, entries) in nodeInfo) {
            if (participant.take(PREFIX_HEX) !in alive) continue
            for (e in entries) {
                if (e.name.isEmpty() || isHidden(e.fullName)) continue
                val writers = e.writers.mapNotNull { byGuid[it] }
                val readers = e.readers.mapNotNull { byGuid[it] }
                out[e.fullName] = Node(
                    e.fullName, e.fullName in lifecycle, dds = ddsVendor(participant),
                    publishers = writers.filter { it.ddsTopic.startsWith("rt/") }.map { it.ddsTopic.substring(2) }.distinct().sorted(),
                    subscribers = readers.filter { it.ddsTopic.startsWith("rt/") }.map { it.ddsTopic.substring(2) }.distinct().sorted(),
                    services = readers.filter { it.ddsTopic.startsWith("rq/") && it.ddsTopic.endsWith("Request") }
                        .map { it.ddsTopic.substring(2, it.ddsTopic.length - "Request".length) }.filter { !isHidden(it) }.distinct().sorted()
                )
            }
        }
        // Participants without "ros_discovery_info" (jros2, like the demo robot, or other DDS): a
        // node with its DDS name if it has a useful one.
        val announced = nodeInfo.keys.mapTo(HashSet()) { it.take(PREFIX_HEX) }
        val own = ownPrefix()
        for (prefix in alive.sorted()) {
            if (prefix in announced) continue
            val raw = (participantNames[prefix] ?: ownName?.takeIf { prefix == own })?.trim().orEmpty()
            if (raw.isEmpty() || raw == "/") continue
            val name = if (raw.startsWith("/")) raw else "/$raw"
            if (name in out || isHidden(name)) continue
            val mine = endpoints.filter { it.guid.startsWith(prefix) }
            out[name] = Node(
                name, name in lifecycle, dds = ddsVendor(prefix),
                publishers = mine.filter { it.isWriter && it.ddsTopic.startsWith("rt/") }.map { it.ddsTopic.substring(2) }.distinct().sorted(),
                subscribers = mine.filter { !it.isWriter && it.ddsTopic.startsWith("rt/") }.map { it.ddsTopic.substring(2) }.distinct().sorted(),
                services = mine.filter { !it.isWriter && it.ddsTopic.startsWith("rq/") && it.ddsTopic.endsWith("Request") }
                    .map { it.ddsTopic.substring(2, it.ddsTopic.length - "Request".length) }.filter { !isHidden(it) }.distinct().sorted()
            )
        }
        for (n in lifecycle) if (n !in out) out[n] = Node(n, true)
        return out.values.sortedBy { it.name }
    }

    /** Lifecycle nodes: those with a "<node>/get_state" server of type lifecycle_msgs/srv/GetState. */
    fun lifecycleNodes(): List<String> =
        services(includeHidden = true).filter { it.servers > 0 && it.type == LIFECYCLE_GET_STATE && it.name.endsWith("/get_state") }
            .map { it.name.removeSuffix("/get_state") }.filter { it.isNotEmpty() }.distinct().sorted()

    companion object {
        const val LIFECYCLE_GET_STATE = "lifecycle_msgs/srv/GetState"
        /** Participant GUID prefix: 12 bytes = 24 hex characters. */
        private const val PREFIX_HEX = 24
        private const val SEND_GOAL = "/_action/send_goal"

        /** Parses the native listener lines: "W|R \t topic \t type \t guid". */
        fun parse(
            lines: Array<String>, participants: Array<String> = emptyArray(), nodeInfo: Map<String, List<RosNodes.Entry>> = emptyMap(),
            participantNames: Map<String, String> = emptyMap(), ownName: String? = null
        ): RosGraph =
            RosGraph(lines.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 4 || (p[0] != "W" && p[0] != "R")) null else Endpoint(p[0] == "W", p[1], p[2], p[3])
            }, participants.toList(), nodeInfo, participantNames, ownName)

        /**
         * DDS implementation of a participant from the first two bytes of its GUID (the OMG vendor id). Only those
         * used with ROS 2; null if it is not a GUID (e.g. via the bridge) or the vendor is not in the list.
         */
        fun ddsVendor(guidHex: String): String? = when (guidHex.take(4).lowercase()) {
            "010f" -> "Fast DDS"
            "0110" -> "Cyclone DDS"
            "0101" -> "RTI Connext"
            ZenohNames.VENDOR_HEX -> "Zenoh" // synthetic GUID of the nodes seen through Zenoh (see ZenohNames.graph)
            else -> null
        }

        /** A name is hidden if any level starts with "_" (rcl criterion). */
        fun isHidden(name: String) = name.split('/').any { it.startsWith("_") }

        /** "geometry_msgs::msg::dds_::Twist_" -> "geometry_msgs/msg/Twist". */
        fun rosTypeName(dds: String): String =
            dds.split("::").filter { it != "dds_" }.joinToString("/").removeSuffix("_")

        /** "example_interfaces::srv::dds_::AddTwoInts_Request_" -> "example_interfaces/srv/AddTwoInts". */
        fun serviceTypeName(ddsRequestType: String): String? {
            val ros = rosTypeName(ddsRequestType)
            return if (ros.endsWith("_Request")) ros.removeSuffix("_Request") else null
        }

        /** "nav2_msgs/action/NavigateToPose_SendGoal" -> "nav2_msgs/action/NavigateToPose". */
        fun actionTypeName(sendGoalServiceType: String): String? =
            if (sendGoalServiceType.endsWith("_SendGoal")) sendGoalServiceType.removeSuffix("_SendGoal") else null
    }
}
