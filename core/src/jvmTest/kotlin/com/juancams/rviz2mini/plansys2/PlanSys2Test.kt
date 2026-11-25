// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plansys2

import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynTypes
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.ihmc.fastddsjava.cdr.CDRBuffer

/** PlanSys2's messages in both shapes (rolling and the released ones), the plan with its execution and the trace. */
class PlanSys2Test {
    private val registry: InterfaceRegistry by lazy {
        InterfaceRegistry().apply { loadBundle(com.juancams.rviz2mini.testing.TestAssets.text("ros_interfaces.txt")) }
            .also { PlanSys2Messages.install(it) }
    }

    /**
     * What jros2 hands to the message: `previous` (another sample of the same reader) and over it `v`. The buffer
     * keeps its size, so behind `v` there are `previous`'s bytes, not the end.
     */
    private fun decode(name: String, spec: MsgSpec, v: MsgValue, previous: Pair<MsgSpec, MsgValue>? = null): MsgValue {
        val buf = CDRBuffer()
        if (previous != null) write(buf, previous.first, previous.second)
        buf.rewind()
        write(buf, spec, v)
        buf.rewind()
        buf.readPayloadHeader()
        val now = registry.msg(name)
        return DynTypes.decoderFor(now.ddsName)!!.decode(buf)
    }

    private fun write(buf: CDRBuffer, spec: MsgSpec, v: MsgValue) {
        buf.ensureRemainingCapacity(CDRBuffer.PAYLOAD_HEADER.size + DynCodec.size(spec, v, 0) + 8)
        buf.writePayloadHeader()
        DynCodec.write(spec, v, buf)
    }

    private fun old(name: String) = PlanSys2Messages.LEGACY.msg(name)
    private fun now(name: String) = registry.msg(name)

    private fun item(t: Double, a: String, d: Double) = MsgValue().apply { put("time", t); put("action", a); put("duration", d) }
    private val items = listOf(item(0.0, "(move r1 dock kitchen)", 4.0), item(4.001, "(clean r1 kitchen)", 6.0))

    @Test
    fun planInBothShapes() {
        val current = MsgValue().apply { put("items", items); put("has_cost", true); put("cost", 10.002) }
        val a = PlanSys2.plan(decode(PlanSys2Messages.PLAN, now(PlanSys2Messages.PLAN), current))
        assertEquals(listOf("(move r1 dock kitchen)", "(clean r1 kitchen)"), a.items.map { it.action })
        assertEquals(4.001, a.items[1].time, 1e-9)
        assertEquals(10.002, a.cost!!, 1e-9)

        // float32 times and no cost, after a longer plan of the new shape.
        val longer = MsgValue().apply { put("items", items + items + items) }
        val b = PlanSys2.plan(decode(PlanSys2Messages.PLAN, old(PlanSys2Messages.PLAN), MsgValue().apply { put("items", items) },
            now(PlanSys2Messages.PLAN) to longer))
        assertEquals(listOf("(move r1 dock kitchen)", "(clean r1 kitchen)"), b.items.map { it.action })
        assertEquals(4.001, b.items[1].time, 1e-6)
        assertEquals(6.0, b.items[1].duration, 1e-6)
        assertNull(b.cost)
    }

    private fun info(status: Long, full: String) = MsgValue().apply {
        put("status", status); put("action_full_name", full); put("action", "move"); put("arguments", listOf("r1", "dock", "kitchen"))
        put("duration", MsgValue().apply { put("sec", 4L); put("nanosec", 0L) }); put("completion", 0.25); put("message_status", "1.2 m to go")
    }

    @Test
    fun executionInfoInBothShapes() {
        for (spec in listOf(now(PlanSys2Messages.EXECUTION_INFO), old(PlanSys2Messages.EXECUTION_INFO))) {
            val i = PlanSys2.info(decode(PlanSys2Messages.EXECUTION_INFO, spec, info(2, "(move r1 dock kitchen):0"),
                spec to info(4, "(a much longer action name with many arguments r1 r2 r3 r4):123456")))
            assertEquals(ExecutionInfo.EXECUTING, i.status)
            assertEquals("(move r1 dock kitchen):0", i.fullName)
            assertEquals(listOf("r1", "dock", "kitchen"), i.arguments)
            assertEquals(4.0, i.duration, 1e-9)
            assertEquals(0.25, i.completion, 1e-6)
            assertEquals("1.2 m to go", i.message)
        }
    }

    @Test
    fun actionsHubInBothShapes() {
        val v = MsgValue().apply {
            put("type", HubMessage.FEEDBACK.toLong()); put("node_id", "move_action_node"); put("action", "move")
            put("arguments", listOf("r1", "dock", "kitchen")); put("completion", 0.5); put("status", "halfway")
        }
        for (spec in listOf(now(PlanSys2Messages.ACTION_EXECUTION), old(PlanSys2Messages.ACTION_EXECUTION))) {
            val m = PlanSys2.hub(decode(PlanSys2Messages.ACTION_EXECUTION, spec, v))
            assertEquals(HubMessage.FEEDBACK, m.type)
            assertEquals("move_action_node", m.nodeId)
            assertEquals("move", m.action)
            assertEquals(0.5, m.completion, 1e-6)
            assertEquals("halfway", m.status)
        }
    }

    @Test
    fun knowledgeInBothShapes() {
        val v = MsgValue().apply {
            put("instances", listOf("r1", "kitchen")); put("predicates", listOf("(robot_at r1 kitchen)"))
            put("functions", listOf("(= (battery r1) 80)")); put("goal", "(and (cleaned kitchen))")
            put("metric", "(minimize (total-time))"); put("constraints", "")
        }
        val a = PlanSys2.knowledge(decode(PlanSys2Messages.KNOWLEDGE, now(PlanSys2Messages.KNOWLEDGE), v))
        assertEquals("(minimize (total-time))", a.metric)
        val bigger = MsgValue().apply { put("instances", (1..30).map { "object_number_$it" }); put("predicates", (1..30).map { "(at object_number_$it kitchen)" }) }
        val b = PlanSys2.knowledge(decode(PlanSys2Messages.KNOWLEDGE, old(PlanSys2Messages.KNOWLEDGE), v, old(PlanSys2Messages.KNOWLEDGE) to bigger))
        assertEquals(listOf("r1", "kitchen"), b.instances)
        assertEquals(listOf("(robot_at r1 kitchen)"), b.predicates)
        assertEquals("(and (cleaned kitchen))", b.goal)
        assertEquals("", b.metric)
    }

    @Test
    fun planRowsFindTheirExecutionInfo() {
        val m = PlanSys2Monitor("")
        m.onPlan(Plan(listOf(PlanItem(0.0, "(move r1 dock kitchen)", 4.0), PlanItem(4.001, "(clean r1 kitchen)", 6.0))))
        m.onInfo(ExecutionInfo(ExecutionInfo.SUCCEEDED, "(move r1 dock kitchen):0", "move", emptyList(), 4.0, 1.0, ""))
        // 4.001 s are 4001 ms; a hair different still matches.
        m.onInfo(ExecutionInfo(ExecutionInfo.EXECUTING, "(clean r1 kitchen):4000", "clean", emptyList(), 6.0, 0.3, ""))
        val rows = m.state.rows
        assertEquals(ExecutionInfo.SUCCEEDED, rows[0].info?.status)
        assertEquals(ExecutionInfo.EXECUTING, rows[1].info?.status)
        // Another plan: what was said of the previous one's actions no longer applies.
        m.onPlan(Plan(listOf(PlanItem(0.0, "(charge r1 dock)", 5.0))))
        assertNull(m.state.rows[0].info)
        assertTrue(m.state.info.isEmpty())
    }

    @Test
    fun actionsUnderWayUntilTheyFinish() {
        val m = PlanSys2Monitor("")
        m.onHub(HubMessage(HubMessage.FEEDBACK, "move_action_node", "move", emptyList(), false, 0.4, ""))
        assertEquals(0.4, m.state.active.getValue("move_action_node").completion, 1e-9)
        m.onHub(HubMessage(HubMessage.FINISH, "move_action_node", "move", emptyList(), true, 1.0, ""))
        assertTrue(m.state.active.isEmpty())
    }

    @Test
    fun onlyRunningPerformersShowTheirAction() {
        val m = PlanSys2Monitor("")
        // The executor asks for the action (its REQUEST is never finished under its own name) and the performer does it.
        m.onHub(HubMessage(HubMessage.REQUEST, "executor", "move", listOf("leia", "a", "b"), false, 0.0, ""))
        m.onHub(HubMessage(HubMessage.FEEDBACK, "move_action_node", "move", listOf("leia", "a", "b"), false, 0.5, ""))
        // Before any performer status, everything that came.
        assertEquals(listOf("executor", "move_action_node"), m.state.running.map { it.nodeId })
        m.onPerformer(Performer("move_action_node", Performer.RUNNING, "move", listOf("leia", "a", "b")))
        assertEquals(listOf("move_action_node"), m.state.running.map { it.nodeId })
        // Done: the performer is ready again and nothing is under way, although the executor's request is still the last one.
        m.onHub(HubMessage(HubMessage.FINISH, "move_action_node", "move", listOf("leia", "a", "b"), true, 1.0, ""))
        m.onPerformer(Performer("move_action_node", Performer.READY, "move", emptyList()))
        assertTrue(m.state.running.isEmpty())
    }

    @Test
    fun traceOnceEachAndAtMostTheExecutorsLimit() {
        val m = PlanSys2Monitor("")
        fun e(seq: Long) = ExecutionEvent(ExecutionEvent.START, seq, 1000L + seq, 1.0, "(move r1 a b):0", 0.0, 0.0, "")
        m.onEvent(e(1)); m.onEvent(e(2)); m.onEvent(e(1))
        assertEquals(listOf(1L, 2L), m.state.trace.map { it.sequence })
        for (s in 3L..1200L) m.onEvent(e(s))
        assertEquals(PlanSys2.TRACE_LIMIT, m.state.trace.size)
        assertEquals(1200L, m.state.trace.last().sequence)
        m.clearTrace()
        assertTrue(m.state.trace.isEmpty())
    }

    @Test
    fun namesAndTimes() {
        assertEquals("/executing_plan", PlanSys2.topic("", "executing_plan"))
        assertEquals("/executing_plan", PlanSys2.topic("/", "executing_plan"))
        assertEquals("/robot1/problem_expert/knowledge", PlanSys2.topic("robot1/", "problem_expert/knowledge"))
        assertEquals("(move r1 a b)", PlanSys2.actionName("(move r1 a b):3000"))
        assertEquals("after (move r1 a b) ends", PlanSys2.withoutIds("after (move r1 a b):3000 ends"))
        assertEquals(0.0, PlanSys2.lateness(ExecutionEvent(ExecutionEvent.START, 1, 0, 3.02, "", 3.0, 0.0, "")), 0.0)
        assertEquals(0.5, PlanSys2.lateness(ExecutionEvent(ExecutionEvent.END, 1, 0, 4.0, "", 3.0, 0.5, "")), 1e-9)
    }
}
