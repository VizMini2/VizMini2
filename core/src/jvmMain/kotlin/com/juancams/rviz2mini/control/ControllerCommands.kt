// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.control

import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosStrings
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.rviz.geom.UrdfModel
import com.juancams.rviz2mini.rviz.geom.UrdfParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Moving the joints of a controller: a joint trajectory controller takes a trajectory_msgs/JointTrajectory on
 * `<controller>/joint_trajectory`; a forward command controller (and the position/velocity/effort JointGroup ones)
 * takes one value per joint, a std_msgs/Float64MultiArray on `<controller>/commands`.
 */
object ControllerCommands {
    enum class Kind { TRAJECTORY, FORWARD, NONE }

    fun kind(type: String): Kind = when {
        type.endsWith("JointTrajectoryController") -> Kind.TRAJECTORY
        type.endsWith("/ForwardCommandController") || type.endsWith("/JointGroupPositionController") ||
            type.endsWith("/JointGroupVelocityController") || type.endsWith("/JointGroupEffortController") -> Kind.FORWARD
        else -> Kind.NONE
    }

    /** The namespace of the manager's controllers ("" for /controller_manager, "/robot1" for /robot1/controller_manager). */
    fun namespaceOf(manager: String): String = ControllerManagerClient.normalize(manager).substringBeforeLast('/')

    fun topic(manager: String, controller: String, kind: Kind): String? = when (kind) {
        Kind.TRAJECTORY -> "${namespaceOf(manager)}/$controller/joint_trajectory"
        Kind.FORWARD -> "${namespaceOf(manager)}/$controller/commands"
        Kind.NONE -> null
    }

    fun type(kind: Kind): String? = when (kind) {
        Kind.TRAJECTORY -> "trajectory_msgs/msg/JointTrajectory"
        Kind.FORWARD -> "std_msgs/msg/Float64MultiArray"
        Kind.NONE -> null
    }

    /** A trajectory with a single point: reach `positions` in `durationS` seconds (zero stamp: start now). */
    fun trajectory(joints: List<String>, positions: List<Double>, durationS: Double): JSONObject {
        val sec = durationS.coerceAtLeast(0.0).toLong()
        val nsec = ((durationS - sec) * 1e9).toLong().coerceIn(0, 999_999_999)
        return JSONObject().put("joint_names", JSONArray(joints)).put("points", JSONArray().put(JSONObject()
            .put("positions", JSONArray(positions)).put("time_from_start", JSONObject().put("sec", sec).put("nanosec", nsec))))
    }

    fun forward(values: List<Double>): JSONObject = JSONObject().put("data", JSONArray(values))

    /**
     * Sends the joint values to the controller (in the order of `joints`). False if there is no ROS connection.
     * Blocks up to [MATCH_WAIT_MS] the first time for each controller (run it off the main thread): a message
     * written before the controller's subscription is matched would be lost.
     */
    fun send(registry: InterfaceRegistry, manager: String, c: ControllerManagerClient.Controller, joints: List<String>, values: List<Double>,
             durationS: Double, publishers: MutableMap<String, DynRos.Publisher>): Boolean = synchronized(publishers) {
        val topic = topic(manager, c.name, c.kind) ?: return false
        val spec = registry.msg(type(c.kind)!!)
        val fresh = topic !in publishers
        val pub = publishers.getOrPut(topic) { DynRos.publisher(topic, spec, RosConnection.defaultQos()) }
        if (fresh) {
            val end = System.currentTimeMillis() + MATCH_WAIT_MS
            while (pub.matched() == 0 && System.currentTimeMillis() < end) Thread.sleep(50)
        }
        val json = if (c.kind == Kind.TRAJECTORY) trajectory(joints, values, durationS) else forward(values)
        return pub.publish(DynCodec.fromJson(spec, json))
    }

    const val MATCH_WAIT_MS = 2000L
}

/**
 * The robot around a controller manager: the current joint positions (`joint_states` of its namespace) and the
 * joint limits of its description (`robot_description`).
 */
class RobotJoints(private val registry: InterfaceRegistry, manager: String, private val onChanged: () -> Unit = {}) {
    private val ns = ControllerCommands.namespaceOf(manager)
    val current = ConcurrentHashMap<String, Double>()
    @Volatile var urdf: UrdfModel? = null; private set
    private val subs = ArrayList<DynRos.Subscription>()
    private var lastChange = 0L

    fun start() {
        if (subs.isNotEmpty()) return
        runCatching {
            subs += DynRos.subscribe("$ns/robot_description", registry.msg("std_msgs/msg/String"), BagRecorder.qos(true)) { v ->
                val xml = RosStrings.fix(v["data"] as? String ?: return@subscribe)
                runCatching { UrdfParser.parse(xml) }.getOrNull()?.let { urdf = it; onChanged() }
            }
            subs += DynRos.subscribe("$ns/joint_states", registry.msg("sensor_msgs/msg/JointState"), RosConnection.defaultQos()) { v ->
                val names = v["name"] as? List<*> ?: return@subscribe
                val pos = v["position"] as? List<*> ?: return@subscribe
                for (i in names.indices) (pos.getOrNull(i) as? Number)?.toDouble()?.let { current[names[i].toString()] = it }
                val now = System.currentTimeMillis()
                if (now - lastChange > 200) { lastChange = now; onChanged() }
            }
        }
    }

    /** The joint's range from the description; for joints without limits, ±π (position) or ±1 (velocity, effort...). */
    fun range(joint: String, commandKind: String?): ClosedFloatingPointRange<Double> {
        val j = urdf?.joints?.firstOrNull { it.name == joint }
        if (commandKind == null || commandKind == "position") {
            if (j != null && !j.lower.isNaN() && !j.upper.isNaN() && j.upper > j.lower) return j.lower..j.upper
            return -Math.PI..Math.PI
        }
        return -1.0..1.0
    }

    fun close() { subs.forEach { runCatching { it.close() } }; subs.clear() }
}
