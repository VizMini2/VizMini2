// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.demo

import com.juancams.rviz2mini.plansys2.ExecutionEvent
import com.juancams.rviz2mini.plansys2.ExecutionInfo
import com.juancams.rviz2mini.plansys2.HubMessage
import com.juancams.rviz2mini.plansys2.Performer
import com.juancams.rviz2mini.plansys2.PlanSys2
import com.juancams.rviz2mini.plansys2.PlanSys2Messages
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import us.ihmc.jros2.ROS2QoSProfile
import kotlin.math.floor

/**
 * The demo's PlanSys2, to try the PlanSys2 tab without one: a cleaning robot that cleans rooms and goes back to charge,
 * one plan after another. It publishes what the executor, the problem expert and the action performers of rolling
 * publish, with their QoS: the plan, how each action goes, the performers and their actions_hub, the knowledge (that
 * changes with the effects of the actions) and the execution trace. One action ends late, as happens, and the rest of
 * the plan goes later.
 */
internal class DemoPlanSys2(private val registry: InterfaceRegistry, private val robot: DemoRobot) {

    private class Step(val time: Double, val name: String, val args: List<String>, val duration: Double, val performer: String, val late: Double = 0.0) {
        val action get() = "($name ${args.joinToString(" ")})"
        val fullName get() = "$action:${floor(time * 1000 + 0.5).toLong()}"
    }

    private class Run(val steps: List<Step>, val goal: String) {
        val status = IntArray(steps.size) { ExecutionInfo.NOT_EXECUTED }
        val completion = DoubleArray(steps.size)
        /** When each one started (plan time). */
        val began = DoubleArray(steps.size)
        val ended = DoubleArray(steps.size)
        var shift = 0.0
        var startMs = 0L
        var finished = false
    }

    private val pubs = ArrayList<DynRos.Publisher>()
    private fun pub(topic: String, type: String, latched: Boolean = false, depth: Int = 100) =
        DynRos.publisher(PlanSys2.topic("", topic), registry.msg(type), ROS2QoSProfile().apply {
            reliability(ROS2QoSProfile.Reliability.RELIABLE)
            durability(if (latched) ROS2QoSProfile.Durability.TRANSIENT_LOCAL else ROS2QoSProfile.Durability.VOLATILE)
            history(ROS2QoSProfile.History.KEEP_LAST)
            depth(depth)
        }).also { pubs += it }

    private val planPub = pub(PlanSys2.PLAN_TOPIC, PlanSys2Messages.PLAN, latched = true)
    private val infoPub = pub(PlanSys2.INFO_TOPIC, PlanSys2Messages.EXECUTION_INFO)
    private val hubPub = pub(PlanSys2.HUB_TOPIC, PlanSys2Messages.ACTION_EXECUTION)
    private val performersPub = pub(PlanSys2.PERFORMERS_TOPIC, PlanSys2Messages.PERFORMER_STATUS)
    private val knowledgePub = pub(PlanSys2.KNOWLEDGE_TOPIC, PlanSys2Messages.KNOWLEDGE, latched = true)
    private val tracePub = pub(PlanSys2.TRACE_TOPIC, PlanSys2Messages.EXECUTION_EVENT, latched = true, depth = PlanSys2.TRACE_LIMIT)

    // The problem: only from the simulator thread.
    private var at = DOCK
    private var battery = 92.0
    private var busy = false
    private val cleaned = LinkedHashSet<String>()
    private var goal = ""
    private var run: Run? = null
    private var plans = 0
    private var sequence = 0L
    private var lastHeartbeat = 0L
    @Volatile private var closed = false

    fun start() {
        publishKnowledge()
        robot.later(2000) { startPlan() }
        tick()
    }

    fun close() {
        closed = true
        for (p in pubs) runCatching { p.close() }
    }

    private fun tick() {
        if (closed) return
        // The next tick even if this one fails: otherwise the plan would stop for good.
        try { step() } catch (e: Exception) { com.juancams.rviz2mini.platform.Log.w("VizMini2", "Demo PlanSys2", e) }
        robot.later(TICK_MS) { tick() }
    }

    // ---------------------------------------------------------------- the plans

    /** Clean two rooms and go back to charge; the next time, the other one. */
    private fun startPlan() {
        if (closed) return
        cleaned.clear()
        val rooms = if (plans++ % 2 == 0) listOf(KITCHEN, BEDROOM) else listOf(LIVING_ROOM)
        val steps = ArrayList<Step>()
        var t = 0.0
        var from = at
        fun add(name: String, args: List<String>, duration: Double, performer: String, late: Double = 0.0) {
            steps += Step(t, name, args, duration, performer, late)
            t = Math.round((t + duration + EPSILON) * 1000) / 1000.0
        }
        for ((i, room) in rooms.withIndex()) {
            add("move", listOf(BOT, from, room), MOVE_S, MOVE_NODE)
            // The first room takes a bit longer than planned: the rest of the plan goes later.
            add("clean", listOf(BOT, room), CLEAN_S, CLEAN_NODE, late = if (i == 0) 0.8 else 0.0)
            from = room
        }
        add("move", listOf(BOT, from, DOCK), MOVE_S, MOVE_NODE)
        add("charge", listOf(BOT, DOCK), CHARGE_S, CHARGE_NODE)
        goal = "(and ${rooms.joinToString(" ") { "(cleaned $it)" }} (robot_at $BOT $DOCK) (charged $BOT))"
        val r = Run(steps, goal).also { it.startMs = System.currentTimeMillis() }
        run = r
        publishKnowledge()
        planPub.publish(MsgValue().apply {
            put("items", steps.map { s -> MsgValue().apply { put("time", s.time); put("action", s.action); put("duration", s.duration) } })
            put("stamp", now())
            put("has_end", false); put("end", 0.0)
            put("has_cost", true); put("cost", t)
        })
        event(ExecutionEvent.PLAN_START, 0.0, "", 0.0, "as planned (timed), ${steps.size} actions")
        publishInfo(r)
    }

    private val random = java.util.Random()
    private fun jitter() = 0.002 + random.nextDouble() * 0.008

    private fun planTime(r: Run) = (System.currentTimeMillis() - r.startMs) / 1000.0

    private fun step() {
        val r = run
        val now = System.currentTimeMillis()
        if (now - lastHeartbeat >= 1000) { lastHeartbeat = now; heartbeat(r) }
        if (r == null || r.finished) return
        val t = planTime(r)
        for ((i, s) in r.steps.withIndex()) {
            val begin = s.time + r.shift
            when (r.status[i]) {
                ExecutionInfo.NOT_EXECUTED -> if (t >= begin && (i == 0 || r.status[i - 1] == ExecutionInfo.SUCCEEDED)) {
                    // When it would have started, not at this tick: a few ms late, as with a real executor.
                    r.began[i] = maxOf(begin, if (i == 0) 0.0 else r.ended[i - 1]) + jitter()
                    begin(r, i, r.began[i])
                }
                ExecutionInfo.EXECUTING -> {
                    val c = ((t - r.began[i]) / (s.duration + s.late)).coerceIn(0.0, 1.0)
                    r.completion[i] = c
                    if (c >= 1.0) { r.ended[i] = r.began[i] + s.duration + s.late; end(r, i, r.ended[i]) }
                    else hub(s, HubMessage.FEEDBACK, completion = c, status = feedback(s, c))
                }
            }
        }
        publishInfo(r)
        if (r.status.all { it == ExecutionInfo.SUCCEEDED }) {
            r.finished = true
            event(ExecutionEvent.PLAN_END, r.ended.last(), "", r.ended.last(), "succeeded")
            robot.later(NEXT_PLAN_MS) { startPlan() }
        }
    }

    private fun begin(r: Run, i: Int, t: Double) {
        val s = r.steps[i]
        r.status[i] = ExecutionInfo.EXECUTING
        hub(s, HubMessage.REQUEST, node = "")
        hub(s, HubMessage.RESPONSE, success = true)
        hub(s, HubMessage.CONFIRM)
        event(ExecutionEvent.PERFORM, t, s.fullName, s.time, "by ${s.performer}", r.shift)
        busy = true
        publishKnowledge()
        event(ExecutionEvent.START, t, s.fullName, s.time, "", r.shift)
        status(s.performer, Performer.RUNNING, s)
    }

    private fun end(r: Run, i: Int, t: Double) {
        val s = r.steps[i]
        r.status[i] = ExecutionInfo.SUCCEEDED
        r.completion[i] = 1.0
        hub(s, HubMessage.FINISH, success = true, completion = 1.0, status = "done")
        when (s.name) {
            "move" -> { at = s.args[2]; battery -= 4.5 }
            "clean" -> { cleaned += s.args[1]; battery -= 9.0 }
            "charge" -> battery = 100.0
        }
        busy = false
        publishKnowledge()
        event(ExecutionEvent.END, t, s.fullName, s.time + s.duration, "", r.shift)
        if (s.late > 0) {
            r.shift += s.late
            event(ExecutionEvent.SHIFT, t, s.fullName, s.time + s.duration, "%.3f s, %s ended later than planned".format(java.util.Locale.ROOT, s.late, s.fullName), r.shift)
        }
        status(s.performer, Performer.READY, null)
    }

    private fun feedback(s: Step, c: Double) = when (s.name) {
        "move" -> "%.1f m to %s".format(java.util.Locale.ROOT, (1 - c) * 3.2, s.args[2])
        "clean" -> "%d%% of %s".format(java.util.Locale.ROOT, (c * 100).toInt(), s.args[1])
        else -> "battery %.0f%%".format(java.util.Locale.ROOT, battery + (100 - battery) * c)
    }

    // ---------------------------------------------------------------- messages

    private fun now() = DynCodec.timeValue(System.currentTimeMillis() * 1_000_000L)
    private fun duration(s: Double) = MsgValue().apply { put("sec", floor(s).toLong()); put("nanosec", ((s - floor(s)) * 1e9).toLong()) }

    private fun publishInfo(r: Run) {
        for ((i, s) in r.steps.withIndex()) infoPub.publish(MsgValue().apply {
            put("status", r.status[i].toLong())
            put("start_stamp", now()); put("status_stamp", now())
            put("action_full_name", s.fullName); put("action", s.name); put("arguments", s.args)
            put("duration", duration(s.duration)); put("completion", r.completion[i])
            put("message_status", if (r.status[i] == ExecutionInfo.EXECUTING) feedback(s, r.completion[i]) else "")
        })
    }

    private fun hub(s: Step, type: Int, node: String = s.performer, success: Boolean = false, completion: Double = 0.0, status: String = "") =
        hubPub.publish(MsgValue().apply {
            put("type", type.toLong()); put("node_id", node); put("request_id", s.fullName)
            put("action", s.name); put("arguments", s.args); put("success", success)
            put("completion", completion); put("status", status); put("planned_duration", s.duration)
        })

    private fun status(node: String, state: Int, s: Step?) = performersPub.publish(MsgValue().apply {
        put("status_stamp", now()); put("state", state.toLong())
        put("action", s?.name ?: ACTIONS.getValue(node)); put("specialized_arguments", s?.args.orEmpty()); put("node_name", node)
    })

    /** The performers say how they are once a second, as PlanSys2's do. */
    private fun heartbeat(r: Run?) {
        for (node in ACTIONS.keys) {
            val s = r?.let { rr -> rr.steps.indices.firstOrNull { rr.status[it] == ExecutionInfo.EXECUTING && rr.steps[it].performer == node }?.let { rr.steps[it] } }
            status(node, if (s != null) Performer.RUNNING else Performer.READY, s)
        }
    }

    private fun event(type: Int, planTime: Double, action: String, planned: Double, detail: String, shift: Double = 0.0) =
        tracePub.publish(MsgValue().apply {
            put("type", type.toLong()); put("sequence", ++sequence); put("stamp", now()); put("plan_time", planTime)
            put("action", action); put("planned_time", planned); put("shift", shift); put("detail", detail)
        })

    private fun publishKnowledge() = knowledgePub.publish(MsgValue().apply {
        put("instances", listOf(BOT, DOCK, KITCHEN, LIVING_ROOM, BEDROOM))
        put("predicates", buildList {
            add("(robot_at $BOT $at)")
            if (!busy) add("(robot_available $BOT)")
            add("(charging_point_at $DOCK)")
            for ((a, b) in CONNECTED) { add("(connected $a $b)"); add("(connected $b $a)") }
            for (room in cleaned) add("(cleaned $room)")
            if (battery >= 99.9) add("(charged $BOT)")
        })
        put("functions", listOf("(= (battery_level $BOT) ${"%.1f".format(java.util.Locale.ROOT, battery).removeSuffix(".0")})",
            "(= (speed $BOT) 0.8)"))
        put("goal", goal)
        put("metric", "(minimize (total-time))")
        put("constraints", "")
    })

    companion object {
        const val EXECUTOR = "/executor"
        const val PROBLEM_EXPERT = "/problem_expert"
        const val MOVE_NODE = "move_action_node"
        const val CLEAN_NODE = "clean_action_node"
        const val CHARGE_NODE = "charge_action_node"
        private val ACTIONS = linkedMapOf(MOVE_NODE to "move", CLEAN_NODE to "clean", CHARGE_NODE to "charge")
        private const val BOT = "demo_bot"
        private const val DOCK = "dock"
        private const val KITCHEN = "kitchen"
        private const val LIVING_ROOM = "living_room"
        private const val BEDROOM = "bedroom"
        private val CONNECTED = listOf(DOCK to KITCHEN, KITCHEN to LIVING_ROOM, LIVING_ROOM to BEDROOM, DOCK to LIVING_ROOM)
        private const val MOVE_S = 4.0
        private const val CLEAN_S = 6.0
        private const val CHARGE_S = 5.0
        private const val EPSILON = 0.001
        private const val TICK_MS = 200L
        private const val NEXT_PLAN_MS = 6000L

        /** Which demo node publishes each PlanSys2 topic (for Nodes and the node graph). */
        fun owner(topic: String): String? = when (topic) {
            PlanSys2.topic("", PlanSys2.KNOWLEDGE_TOPIC) -> PROBLEM_EXPERT
            PlanSys2.topic("", PlanSys2.PERFORMERS_TOPIC) -> "/$MOVE_NODE"
            PlanSys2.topic("", PlanSys2.PLAN_TOPIC), PlanSys2.topic("", PlanSys2.INFO_TOPIC),
            PlanSys2.topic("", PlanSys2.HUB_TOPIC), PlanSys2.topic("", PlanSys2.TRACE_TOPIC) -> EXECUTOR
            else -> null
        }
    }
}
