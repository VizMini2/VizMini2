// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.demo

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.params.ParamModel
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosGraph
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.DynServiceServer
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import us.ihmc.jros2.ROS2QoSProfile
import java.util.concurrent.ConcurrentHashMap

/**
 * What the demo robot offers besides its topics, to try without a real robot the
 * tabs that inspect the network (Nodes, Services, Actions, Lifecycle, Buttons, Node graph).
 * All of it with an effect on the simulation:
 *
 * - /demo_robot: parameters (max speeds, autopilot...), the services
 *   return_home (std_srvs/Trigger), set_autopilot (std_srvs/SetBool) and add_two_ints, and the
 *   fibonacci action (example_interfaces/action/Fibonacci, with feedback and cancellable).
 * - /camera_driver: lifecycle node; the camera only publishes while it is active.
 * - /controller_manager: a ros2_control controller manager (see [DemoControllers]); with diff_drive_controller
 *   inactive the robot does not move.
 * - "ros_discovery_info": announces those nodes and the app's with their endpoints, as rmw would, so
 *   that they show up in Nodes and in the graph with their name.
 *
 * Everything lives in the app's node (see [DynServiceServer] for the limit on responses).
 */
class DemoServices internal constructor(private val registry: InterfaceRegistry, private val robot: DemoRobot) {

    private val servers = ArrayList<DynServiceServer>()
    private val pubs = ArrayList<DynRos.Publisher>()
    @Volatile private var closed = false

    fun start() {
        try {
            startParameters()
            startServices()
            startFibonacci()
            startCameraLifecycle()
            DemoControllers(robot) { name, type, handler -> serve(name, type, handler) }.start()
            graphInfo = DynRos.rawPublisher("ros_discovery_info", registry.msg(DISCOVERY_TYPE), latchedQos()).also { pubs += it }
            announceLoop()
        } catch (e: Exception) {
            Log.e("VizMini2", "Could not create the demo services", e)
        }
    }

    fun close() {
        closed = true
        for (s in servers) s.close()
        for (p in pubs) p.close()
    }

    private fun serve(name: String, type: String, handler: (MsgValue, (MsgValue) -> Unit) -> Unit) {
        servers += DynServiceServer(name, registry.srv(type), handler)
    }

    private fun latchedQos() = ROS2QoSProfile().apply {
        reliability(ROS2QoSProfile.Reliability.RELIABLE)
        durability(ROS2QoSProfile.Durability.TRANSIENT_LOCAL)
        history(ROS2QoSProfile.History.KEEP_LAST)
        depth(1)
    }

    private fun now() = DynCodec.timeValue(System.currentTimeMillis() * 1_000_000L)

    // ---------------------------------------------------------------- /demo_robot parameters

    private class Param(val type: ParamModel.Type, @Volatile var value: Any, val description: String,
                        val readOnly: Boolean = false, val range: ClosedFloatingPointRange<Double>? = null, val step: Double = 0.0)

    private val params = linkedMapOf(
        "autopilot" to Param(ParamModel.Type.BOOL, true, "Drive around the room when nobody is using the joystick"),
        "max_angular_speed" to Param(ParamModel.Type.DOUBLE, 1.5, "Teleop limit for turning (rad/s)", range = 0.2..3.0, step = 0.1),
        "max_linear_speed" to Param(ParamModel.Type.DOUBLE, 0.6, "Teleop limit for driving (m/s)", range = 0.1..1.0, step = 0.05),
        "robot_name" to Param(ParamModel.Type.STRING, "demo_bot", "Name shown in diagnostics", readOnly = true),
        "use_sim_time" to Param(ParamModel.Type.BOOL, false, "Use /clock instead of the system time"),
    )

    private fun startParameters() {
        serve("$ROBOT/list_parameters", "rcl_interfaces/srv/ListParameters") { _, reply ->
            reply(MsgValue().apply { put("result", MsgValue().apply { put("names", params.keys.toList()); put("prefixes", emptyList<String>()) }) })
        }
        serve("$ROBOT/describe_parameters", "rcl_interfaces/srv/DescribeParameters") { req, reply ->
            reply(MsgValue().apply { put("descriptors", names(req).map { describe(it) }) })
        }
        serve("$ROBOT/get_parameters", "rcl_interfaces/srv/GetParameters") { req, reply ->
            reply(MsgValue().apply {
                put("values", names(req).map { n -> params[n]?.let { ParamModel.toMsg(ParamModel.Value(it.type, it.value)) } ?: MsgValue().apply { put("type", 0L) } })
            })
        }
        serve("$ROBOT/set_parameters", "rcl_interfaces/srv/SetParameters") { req, reply ->
            val results = (req["parameters"] as? List<*>).orEmpty().map { p ->
                val m = p as? Map<*, *>
                val reason = setParam(m?.get("name") as? String ?: "", ParamModel.fromMsg(m?.get("value") as? Map<*, *>))
                MsgValue().apply { put("successful", reason == null); put("reason", reason ?: "") }
            }
            reply(MsgValue().apply { put("results", results) })
        }
    }

    private fun names(req: MsgValue) = (req["names"] as? List<*>).orEmpty().map { it.toString() }

    private fun describe(name: String) = MsgValue().apply {
        val p = params[name]
        put("name", name)
        put("type", (p?.type ?: ParamModel.Type.NOT_SET).id.toLong())
        put("description", p?.description ?: "")
        put("read_only", p?.readOnly == true)
        p?.range?.let { r -> put("floating_point_range", listOf(MsgValue().apply { put("from_value", r.start); put("to_value", r.endInclusive); put("step", p.step) })) }
    }

    /** null if it is applied; otherwise, the reason (like a real node). */
    private fun setParam(name: String, v: ParamModel.Value): String? {
        val p = params[name] ?: return "Parameter $name is not declared"
        if (p.readOnly) return "Parameter $name is read-only"
        if (v.type != p.type) return "Wrong type for $name: expected ${p.type.label}"
        val value = v.value ?: return "No value"
        if (p.range != null && (value as Double) !in p.range) return "$name must be between ${p.range.start} and ${p.range.endInclusive}"
        p.value = value
        when (name) {
            "autopilot" -> robot.autopilotOn = value as Boolean
            "max_linear_speed" -> robot.maxLinear = value as Double
            "max_angular_speed" -> robot.maxAngular = value as Double
        }
        return null
    }

    // ---------------------------------------------------------------- /demo_robot services

    private fun startServices() {
        serve("$ROBOT/return_home", "std_srvs/srv/Trigger") { _, reply ->
            robot.returnHome()
            reply(MsgValue().apply { put("success", true); put("message", "Back at the dock") })
        }
        serve("$ROBOT/set_autopilot", "std_srvs/srv/SetBool") { req, reply ->
            val on = req["data"] == true
            setParam("autopilot", ParamModel.Value(ParamModel.Type.BOOL, on))
            reply(MsgValue().apply { put("success", true); put("message", if (on) "Autopilot on" else "Autopilot off") })
        }
        serve("/add_two_ints", "example_interfaces/srv/AddTwoInts") { req, reply ->
            val a = (req["a"] as? Number)?.toLong() ?: 0L
            val b = (req["b"] as? Number)?.toLong() ?: 0L
            reply(MsgValue().apply { put("sum", a + b) })
        }
    }

    // ---------------------------------------------------------------- /fibonacci action

    private class Goal(val id: List<Long>, val order: Int) {
        val sequence = arrayListOf(0L, 1L)
        @Volatile var status = STATUS_EXECUTING
        @Volatile var cancel = false
        val waiting = ArrayList<(MsgValue) -> Unit>()
        val done get() = status >= STATUS_SUCCEEDED
    }

    private val goals = ConcurrentHashMap<List<Long>, Goal>()
    private lateinit var feedback: DynRos.Publisher
    private lateinit var goalStatus: DynRos.Publisher

    private fun startFibonacci() {
        val action = registry.action(FIBONACCI_TYPE)
        feedback = DynRos.publisher("$FIBONACCI/_action/feedback", action.feedbackMessage, RosConnection.defaultQos()).also { pubs += it }
        goalStatus = DynRos.publisher("$FIBONACCI/_action/status", registry.msg("action_msgs/msg/GoalStatusArray"), latchedQos()).also { pubs += it }
        servers += DynServiceServer("$FIBONACCI/_action/send_goal", action.sendGoal) { req, reply ->
            val id = uuid(req["goal_id"])
            val order = ((req["goal"] as? Map<*, *>)?.get("order") as? Number)?.toInt() ?: 0
            val accepted = order in 1..MAX_ORDER
            reply(MsgValue().apply { put("accepted", accepted); put("stamp", now()) })
            if (accepted) {
                val g = Goal(id, order)
                goals[id] = g
                publishStatus()
                robot.later(STEP_MS) { stepGoal(g) }
            }
        }
        servers += DynServiceServer("$FIBONACCI/_action/get_result", action.getResult) { req, reply ->
            val g = goals[uuid(req["goal_id"])]
            if (g == null) { reply(MsgValue().apply { put("status", 0L) }); return@DynServiceServer }
            synchronized(g) { if (g.done) reply(result(g)) else g.waiting += reply }
        }
        serve("$FIBONACCI/_action/cancel_goal", "action_msgs/srv/CancelGoal") { req, reply ->
            val info = req["goal_info"] as? Map<*, *>
            val id = uuid(info?.get("goal_id"))
            // An all-zero uuid cancels everything (like rcl_action).
            val targets = if (id.all { it == 0L }) goals.values.filter { !it.done } else listOfNotNull(goals[id]?.takeIf { !it.done })
            for (g in targets) { g.cancel = true; g.status = STATUS_CANCELING }
            publishStatus()
            reply(MsgValue().apply {
                put("return_code", if (targets.isEmpty()) 2L else 0L)
                put("goals_canceling", targets.map { goalInfo(it) })
            })
        }
    }

    private fun stepGoal(g: Goal) {
        if (closed) return
        if (g.cancel) { finish(g, STATUS_CANCELED); return }
        val s = g.sequence
        s += s[s.size - 1] + s[s.size - 2]
        feedback.publish(MsgValue().apply {
            put("goal_id", MsgValue().apply { put("uuid", g.id) })
            put("feedback", MsgValue().apply { put("sequence", s.toList()) })
        })
        if (s.size > g.order) finish(g, STATUS_SUCCEEDED) else robot.later(STEP_MS) { stepGoal(g) }
    }

    private fun finish(g: Goal, status: Int) {
        val waiting = synchronized(g) {
            g.status = status
            g.waiting.toList().also { g.waiting.clear() }
        }
        val r = result(g)
        for (w in waiting) w(r)
        publishStatus()
        // Forgotten after a while, as rcl_action does with results.
        robot.later(60_000) { goals.remove(g.id) }
    }

    private fun result(g: Goal) = MsgValue().apply {
        put("status", g.status.toLong())
        put("result", MsgValue().apply { put("sequence", g.sequence.toList()) })
    }

    private fun goalInfo(g: Goal) = MsgValue().apply { put("goal_id", MsgValue().apply { put("uuid", g.id) }); put("stamp", now()) }

    private fun publishStatus() {
        goalStatus.publish(MsgValue().apply {
            put("status_list", goals.values.map { g -> MsgValue().apply { put("goal_info", goalInfo(g)); put("status", g.status.toLong()) } })
        })
    }

    private fun uuid(goalId: Any?): List<Long> =
        ((goalId as? Map<*, *>)?.get("uuid") as? List<*>).orEmpty().map { (it as? Number)?.toLong()?.and(0xFF) ?: 0L }

    // ---------------------------------------------------------------- /camera_driver lifecycle

    @Volatile private var cameraState = STATE_ACTIVE
    private lateinit var transitions: DynRos.Publisher

    private fun startCameraLifecycle() {
        transitions = DynRos.publisher("$CAMERA/transition_event", registry.msg("lifecycle_msgs/msg/TransitionEvent"), RosConnection.defaultQos()).also { pubs += it }
        serve("$CAMERA/get_state", "lifecycle_msgs/srv/GetState") { _, reply ->
            reply(MsgValue().apply { put("current_state", state(cameraState)) })
        }
        serve("$CAMERA/get_available_transitions", "lifecycle_msgs/srv/GetAvailableTransitions") { _, reply ->
            reply(MsgValue().apply {
                put("available_transitions", TRANSITIONS.filter { it.from == cameraState }.map { t ->
                    MsgValue().apply { put("transition", transition(t)); put("start_state", state(t.from)); put("goal_state", state(t.to)) }
                })
            })
        }
        serve("$CAMERA/change_state", "lifecycle_msgs/srv/ChangeState") { req, reply ->
            val m = req["transition"] as? Map<*, *>
            val id = (m?.get("id") as? Number)?.toInt() ?: 0
            val label = m?.get("label") as? String ?: ""
            val t = TRANSITIONS.firstOrNull { it.from == cameraState && (it.id == id || (id == 0 && it.label == label)) }
            if (t == null) { reply(MsgValue().apply { put("success", false) }); return@serve }
            // Like a real driver, configuring or activating takes a moment.
            robot.later(if (t.label == "configure" || t.label == "activate") 700 else 250) {
                cameraState = t.to
                robot.cameraOn = t.to == STATE_ACTIVE
                transitions.publish(MsgValue().apply {
                    put("timestamp", System.currentTimeMillis() * 1_000_000L)
                    put("transition", transition(t)); put("start_state", state(t.from)); put("goal_state", state(t.to))
                })
                reply(MsgValue().apply { put("success", true) })
            }
        }
    }

    private class Transition(val id: Int, val label: String, val from: Int, val to: Int)

    private fun state(id: Int) = MsgValue().apply { put("id", id.toLong()); put("label", STATE_LABELS[id] ?: "unknown") }
    private fun transition(t: Transition) = MsgValue().apply { put("id", t.id.toLong()); put("label", t.label) }

    // ---------------------------------------------------------------- nodes in the graph

    private var graphInfo: DynRos.Publisher? = null

    private fun announceLoop() {
        if (closed) return
        try { announce() } catch (e: Exception) { Log.w("VizMini2", "Demo: could not announce the nodes", e) }
        robot.later(ANNOUNCE_MS) { announceLoop() }
    }

    /**
     * Splits our participant's endpoints between the demo nodes and the app's node according to
     * the topic and whether they read or write (a service server reads the request; a client writes it).
     */
    private fun announce() {
        val g = RosConnection.graph() ?: return
        val own = g.ownPrefix() ?: return
        val app = RosConnection.config.nodeName
        val nodes = linkedMapOf(ROBOT to (ArrayList<String>() to ArrayList<String>()), CAMERA to (ArrayList<String>() to ArrayList()),
            DemoControllers.MANAGER to (ArrayList<String>() to ArrayList()),
            "/$app" to (ArrayList<String>() to ArrayList()))
        for (e in g.endpoints) {
            if (!e.guid.startsWith(own)) continue
            val (readers, writers) = nodes.getValue(owner(e) ?: "/$app")
            (if (e.isWriter) writers else readers) += e.guid
        }
        graphInfo?.publish(MsgValue().apply {
            put("gid", gid(own + PARTICIPANT_ENTITY))
            put("node_entities_info_seq", nodes.map { (name, rw) ->
                MsgValue().apply {
                    put("node_namespace", "/"); put("node_name", name.removePrefix("/"))
                    put("reader_gid_seq", rw.first.map { gid(it) }); put("writer_gid_seq", rw.second.map { gid(it) })
                }
            })
        })
    }

    private fun owner(e: RosGraph.Endpoint): String? {
        val t = e.ddsTopic
        val server = (!e.isWriter && t.startsWith("rq/") && t.endsWith("Request")) || (e.isWriter && t.startsWith("rr/") && t.endsWith("Reply"))
        if (server) {
            val name = t.substring(2).removeSuffix("Request").removeSuffix("Reply")
            return when {
                name.startsWith("$CAMERA/") -> CAMERA
                name.startsWith("${DemoControllers.MANAGER}/") -> DemoControllers.MANAGER
                name.startsWith("$ROBOT/") || name == "/add_two_ints" || name.startsWith("$FIBONACCI/") -> ROBOT
                else -> null
            }
        }
        if (!t.startsWith("rt/")) return null
        val topic = t.substring(2)
        return when {
            e.isWriter && (topic == "/camera/image_raw" || topic == DemoRobot.VIDEO_TOPIC || topic == "$CAMERA/transition_event") -> CAMERA
            e.isWriter && (topic in ROBOT_TOPICS || topic.startsWith("$FIBONACCI/")) -> ROBOT
            !e.isWriter && topic == "/cmd_vel" -> ROBOT
            else -> null
        }
    }

    private fun gid(hex: String) = MsgValue().apply {
        put("data", (0 until 16).map { i -> hex.substring(i * 2, i * 2 + 2).toLong(16) })
    }

    companion object {
        const val ROBOT = "/demo_robot"
        const val CAMERA = "/camera_driver"
        const val FIBONACCI = "/fibonacci"
        const val FIBONACCI_TYPE = "example_interfaces/action/Fibonacci"
        private const val DISCOVERY_TYPE = "rmw_dds_common/msg/ParticipantEntitiesInfo"
        /** EntityId of the participant in its GUID (rmw uses the participant's GUID as its GID). */
        private const val PARTICIPANT_ENTITY = "000001c1"
        private const val ANNOUNCE_MS = 2000L
        private const val STEP_MS = 500L
        private const val MAX_ORDER = 40
        private val ROBOT_TOPICS = setOf("/tf", "/tf_static", "/odom", "/scan", "/map", "/diagnostics", "/robot_description", "/rosout", "/plan", "/battery_state", "/gps/fix")

        private const val STATUS_EXECUTING = 2
        private const val STATUS_CANCELING = 3
        private const val STATUS_SUCCEEDED = 4
        private const val STATUS_CANCELED = 5

        private const val STATE_UNCONFIGURED = 1
        private const val STATE_INACTIVE = 2
        private const val STATE_ACTIVE = 3
        private const val STATE_FINALIZED = 4
        private val STATE_LABELS = mapOf(STATE_UNCONFIGURED to "unconfigured", STATE_INACTIVE to "inactive", STATE_ACTIVE to "active", STATE_FINALIZED to "finalized")
        /** Those of lifecycle_msgs/msg/Transition, without the intermediate states. */
        private val TRANSITIONS = listOf(
            Transition(1, "configure", STATE_UNCONFIGURED, STATE_INACTIVE),
            Transition(2, "cleanup", STATE_INACTIVE, STATE_UNCONFIGURED),
            Transition(3, "activate", STATE_INACTIVE, STATE_ACTIVE),
            Transition(4, "deactivate", STATE_ACTIVE, STATE_INACTIVE),
            Transition(5, "shutdown", STATE_UNCONFIGURED, STATE_FINALIZED),
            Transition(6, "shutdown", STATE_INACTIVE, STATE_FINALIZED),
            Transition(7, "shutdown", STATE_ACTIVE, STATE_FINALIZED),
        )
    }
}
