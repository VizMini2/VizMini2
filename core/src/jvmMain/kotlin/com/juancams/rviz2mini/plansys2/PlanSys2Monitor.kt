// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plansys2

import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import us.ihmc.jros2.ROS2QoSProfile
import kotlin.math.floor

/** An action of the plan: when it starts (seconds from the start of the plan), "(move r1 kitchen hall)" and how long. */
data class PlanItem(val time: Double, val action: String, val duration: Double)

data class Plan(val items: List<PlanItem>, val cost: Double? = null)

/** What the executor says about an action of the plan (plansys2_msgs/ActionExecutionInfo). */
data class ExecutionInfo(
    val status: Int, val fullName: String, val action: String, val arguments: List<String>,
    val duration: Double, val completion: Double, val message: String,
) {
    companion object {
        const val NOT_EXECUTED = 1
        const val EXECUTING = 2
        const val FAILED = 3
        const val SUCCEEDED = 4
        const val CANCELLED = 5
    }
}

/** An action performer, a node that does actions (plansys2_msgs/ActionPerformerStatus). */
data class Performer(val node: String, val state: Int, val action: String, val arguments: List<String>) {
    companion object {
        const val NOT_READY = 0
        const val READY = 1
        const val RUNNING = 2
        const val FAILURE = 4
    }
}

/** The last message of an action on actions_hub (plansys2_msgs/ActionExecution): what it does now. */
data class HubMessage(
    val type: Int, val nodeId: String, val action: String, val arguments: List<String>,
    val success: Boolean, val completion: Double, val status: String,
) {
    companion object {
        const val REQUEST = 1
        const val RESPONSE = 2
        const val CONFIRM = 3
        const val REJECT = 4
        const val FEEDBACK = 5
        const val FINISH = 6
        const val CANCEL = 7
        const val UPDATE = 8
        val LABELS = mapOf(REQUEST to "REQUEST", RESPONSE to "RESPONSE", CONFIRM to "CONFIRM", REJECT to "REJECT",
            FEEDBACK to "FEEDBACK", FINISH to "FINISH", CANCEL to "CANCEL", UPDATE to "UPDATE")
    }
}

/** What the problem expert knows (plansys2_msgs/Knowledge). */
data class Knowledge(
    val instances: List<String>, val predicates: List<String>, val functions: List<String>,
    val goal: String, val metric: String, val constraints: String,
)

/** Something the executor did while running the plan, and why (plansys2_msgs/ExecutionEvent). */
data class ExecutionEvent(
    val type: Int, val sequence: Long, val stampMs: Long, val planTime: Double, val action: String,
    val plannedTime: Double, val shift: Double, val detail: String,
) {
    companion object {
        const val PLAN_START = 1
        const val WAIT = 2
        const val PERFORM = 3
        const val START = 4
        const val END = 5
        const val SHIFT = 6
        const val FAILURE = 7
        const val VIOLATION = 8
        const val REPLAN = 9
        const val PLAN_END = 10
    }
}

/** A row of the plan with what the executor says of it (null: it has not said anything yet). */
data class PlanRow(val item: PlanItem, val info: ExecutionInfo?)

/** Everything seen so far. Immutable: the screen reads it while the DDS threads make the next one. */
data class PlanSys2State(
    val plan: Plan? = null,
    /** By action_full_name, "(move r1 kitchen hall):3000". */
    val info: Map<String, ExecutionInfo> = emptyMap(),
    val performers: Map<String, Performer> = emptyMap(),
    /** The actions under way, by the node that performs them. */
    val active: Map<String, HubMessage> = emptyMap(),
    val knowledge: Knowledge? = null,
    val trace: List<ExecutionEvent> = emptyList(),
) {
    val rows: List<PlanRow> get() = plan?.items.orEmpty().map { PlanRow(it, PlanSys2.infoFor(it, info)) }
    val anything: Boolean get() = plan != null || info.isNotEmpty() || performers.isNotEmpty() || knowledge != null || trace.isNotEmpty()
}

object PlanSys2 {
    const val PLAN_TOPIC = "executing_plan"
    const val INFO_TOPIC = "action_execution_info"
    const val HUB_TOPIC = "actions_hub"
    const val PERFORMERS_TOPIC = "performers_status"
    const val KNOWLEDGE_TOPIC = "problem_expert/knowledge"
    const val TRACE_TOPIC = "executor/execution_trace"
    /** The executor keeps its last 1000 events, and so does the tab. */
    const val TRACE_LIMIT = 1000

    /** "/robot1" + "executing_plan" -> "/robot1/executing_plan"; "" or "/" -> "/executing_plan". */
    fun topic(namespace: String, name: String): String {
        val ns = namespace.trim().trim('/')
        return if (ns.isEmpty()) "/$name" else "/$ns/$name"
    }

    /**
     * The executor's information about a plan item: its action_full_name is the action and the time in ms, rounded as
     * it does; ±1 for times that come out a hair different.
     */
    fun infoFor(item: PlanItem, info: Map<String, ExecutionInfo>): ExecutionInfo? {
        if (info.isEmpty()) return null
        val ms = floor(item.time * 1000 + 0.5).toLong()
        val action = item.action.trim()
        for (c in longArrayOf(ms, ms + 1, ms - 1)) info["$action:$c"]?.let { return it }
        return null
    }

    /** "(move r1 kitchen hall):3000" -> "(move r1 kitchen hall)". */
    fun actionName(action: String): String {
        val colon = action.lastIndexOf(':')
        return if (colon > 0 && action.substring(colon + 1).let { it.isNotEmpty() && it.all(Char::isDigit) }) action.substring(0, colon) else action
    }

    /** Without the plan times of the actions it names: "after (move r1 a b):3000 ends" -> "after (move r1 a b) ends". */
    fun withoutIds(text: String): String = Regex("""\):\d+""").replace(text, ")")

    /** Seconds late (positive), early (negative) or on time (within 50 ms: 0) of an action that started or ended. */
    fun lateness(e: ExecutionEvent): Double {
        val late = e.planTime - e.plannedTime - e.shift
        return if (kotlin.math.abs(late) < 0.05) 0.0 else late
    }

    // ---------------------------------------------------------------- from the messages

    private fun str(v: Any?) = v?.toString().orEmpty()
    private fun num(v: Any?) = (v as? Number)?.toDouble() ?: 0.0
    private fun int(v: Any?) = (v as? Number)?.toInt() ?: 0
    private fun strings(v: Any?) = (v as? List<*>).orEmpty().map { it.toString() }
    private fun seconds(v: Any?): Double = (v as? Map<*, *>)?.let { num(it["sec"]) + num(it["nanosec"]) / 1e9 } ?: 0.0

    fun plan(v: MsgValue) = Plan(
        (v["items"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { m -> PlanItem(num(m["time"]), str(m["action"]), num(m["duration"])) } },
        if (v["has_cost"] == true) num(v["cost"]) else null,
    )

    fun info(v: MsgValue) = ExecutionInfo(int(v["status"]), str(v["action_full_name"]), str(v["action"]), strings(v["arguments"]),
        seconds(v["duration"]), num(v["completion"]), str(v["message_status"]))

    fun performer(v: MsgValue) = Performer(str(v["node_name"]), int(v["state"]), str(v["action"]), strings(v["specialized_arguments"]))

    fun hub(v: MsgValue) = HubMessage(int(v["type"]), str(v["node_id"]), str(v["action"]), strings(v["arguments"]),
        v["success"] == true, num(v["completion"]), str(v["status"]))

    fun knowledge(v: MsgValue) = Knowledge(strings(v["instances"]), strings(v["predicates"]), strings(v["functions"]),
        str(v["goal"]).trim(), str(v["metric"]).trim(), str(v["constraints"]).trim())

    fun event(v: MsgValue) = ExecutionEvent(int(v["type"]), (v["sequence"] as? Number)?.toLong() ?: 0L, (seconds(v["stamp"]) * 1000).toLong(),
        num(v["plan_time"]), str(v["action"]), num(v["planned_time"]), num(v["shift"]), str(v["detail"]))
}

/**
 * Follows a PlanSys2 the way its terminal monitor (plansys2_tui) does, only through topics: the plan being executed and
 * how each action goes, the performers and the actions they are doing, what the problem expert knows and what the
 * executor does and why. `namespace`: that of the PlanSys2 nodes ("" if they have none).
 */
class PlanSys2Monitor(val namespace: String) {

    @Volatile var state = PlanSys2State(); private set
    /** Goes up with each change: the screen redraws when it sees a new one. */
    @Volatile var version = 0L; private set

    private val lock = Any()
    private val subs = ArrayList<DynRos.Subscription>()

    fun start() {
        val registry = Interfaces.get()
        PlanSys2Messages.install(registry)
        fun sub(name: String, type: String, qos: ROS2QoSProfile, on: (MsgValue) -> Unit) {
            subs += DynRos.subscribe(PlanSys2.topic(namespace, name), registry.msg(type), qos) { v ->
                synchronized(lock) { on(v); version++ }
            }
        }
        sub(PlanSys2.PLAN_TOPIC, PlanSys2Messages.PLAN, latched(1)) { v -> onPlan(PlanSys2.plan(v)) }
        sub(PlanSys2.INFO_TOPIC, PlanSys2Messages.EXECUTION_INFO, reliable()) { v -> onInfo(PlanSys2.info(v)) }
        sub(PlanSys2.HUB_TOPIC, PlanSys2Messages.ACTION_EXECUTION, reliable()) { v -> onHub(PlanSys2.hub(v)) }
        sub(PlanSys2.PERFORMERS_TOPIC, PlanSys2Messages.PERFORMER_STATUS, reliable()) { v -> onPerformer(PlanSys2.performer(v)) }
        sub(PlanSys2.KNOWLEDGE_TOPIC, PlanSys2Messages.KNOWLEDGE, latched(1)) { v -> state = state.copy(knowledge = PlanSys2.knowledge(v)) }
        sub(PlanSys2.TRACE_TOPIC, PlanSys2Messages.EXECUTION_EVENT, latched(PlanSys2.TRACE_LIMIT)) { v -> onEvent(PlanSys2.event(v)) }
    }

    fun close() {
        for (s in subs) runCatching { s.close() }
        subs.clear()
    }

    /** Forgets the plan log (the "clear" button). */
    fun clearTrace() = synchronized(lock) { state = state.copy(trace = emptyList()); version++ }

    // Public for the tests: what each message changes.

    fun onPlan(p: Plan) {
        // A new plan: the information of the previous one's actions no longer applies.
        state = state.copy(plan = p, info = if (p.items == state.plan?.items) state.info else emptyMap())
    }

    fun onInfo(i: ExecutionInfo) { state = state.copy(info = state.info + (i.fullName to i)) }

    fun onPerformer(p: Performer) { state = state.copy(performers = state.performers + (p.node to p)) }

    fun onHub(m: HubMessage) {
        state = state.copy(active = if (m.type == HubMessage.FINISH) state.active - m.nodeId else state.active + (m.nodeId to m))
    }

    fun onEvent(e: ExecutionEvent) {
        val t = state.trace
        // Those kept by the executor arrive again if the tab subscribes again: once each.
        if (t.any { it.sequence == e.sequence && it.stampMs == e.stampMs && it.type == e.type }) return
        state = state.copy(trace = (t + e).takeLast(PlanSys2.TRACE_LIMIT))
    }

    private fun reliable() = ROS2QoSProfile().apply {
        reliability(ROS2QoSProfile.Reliability.RELIABLE)
        durability(ROS2QoSProfile.Durability.VOLATILE)
        history(ROS2QoSProfile.History.KEEP_LAST)
        depth(100)
    }

    /** The plan, the knowledge and the trace are TRANSIENT_LOCAL: who arrives later gets the last ones. */
    private fun latched(depth: Int) = ROS2QoSProfile().apply {
        reliability(ROS2QoSProfile.Reliability.RELIABLE)
        durability(ROS2QoSProfile.Durability.TRANSIENT_LOCAL)
        history(ROS2QoSProfile.History.KEEP_LAST)
        depth(depth)
    }
}
