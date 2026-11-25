// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plansys2

import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynTypes
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.MsgValue
import us.ihmc.fastddsjava.cdr.CDRBuffer

/**
 * PlanSys2's messages in their two shapes. The app has those of rolling (October 2026: plans with float64 times, the
 * moments the effects were applied, the request of each action, the metric and constraints of the problem...), those of
 * PlanSys2 4.0.0 (the Jazzy packages) and newer; the earlier releases (3.x and older) publish the earlier ones under the
 * same DDS names. Without the
 * size of the sample, which jros2 does not give, the shape is found by reading: the current one if its values make
 * sense, if not the earlier one, which is then filled out to the current shape (its new fields with their defaults).
 */
object PlanSys2Messages {

    const val PLAN = "plansys2_msgs/msg/Plan"
    const val EXECUTION_INFO = "plansys2_msgs/msg/ActionExecutionInfo"
    const val ACTION_EXECUTION = "plansys2_msgs/msg/ActionExecution"
    const val PERFORMER_STATUS = "plansys2_msgs/msg/ActionPerformerStatus"
    const val KNOWLEDGE = "plansys2_msgs/msg/Knowledge"
    const val EXECUTION_EVENT = "plansys2_msgs/msg/ExecutionEvent"

    /** The earlier shapes (plansys2_msgs 3.x and older). */
    internal val LEGACY = InterfaceRegistry().apply {
        define("builtin_interfaces/msg/Time", "int32 sec\nuint32 nanosec")
        define("builtin_interfaces/msg/Duration", "int32 sec\nuint32 nanosec")
        define("plansys2_msgs/msg/PlanItem", "float32 time\nstring action\nfloat32 duration")
        define(PLAN, "plansys2_msgs/PlanItem[] items")
        define(EXECUTION_INFO, "int8 status\nbuiltin_interfaces/Time start_stamp\nbuiltin_interfaces/Time status_stamp\n" +
            "string action_full_name\nstring action\nstring[] arguments\nbuiltin_interfaces/Duration duration\nfloat32 completion\nstring message_status")
        define(ACTION_EXECUTION, "int16 type\nstring node_id\nstring action\nstring[] arguments\nbool success\nfloat32 completion\nstring status")
        define(KNOWLEDGE, "string[] instances\nstring[] predicates\nstring[] functions\nstring goal")
    }

    /** Registers the readers of the types that changed (once is enough; again does no harm). */
    fun install(current: InterfaceRegistry) {
        for (name in listOf(PLAN, EXECUTION_INFO, ACTION_EXECUTION, KNOWLEDGE)) {
            val now = current.msg(name)
            DynTypes.setDecoder(now.ddsName, TwoShapes(now, LEGACY.msg(name), checks[name] ?: { true }))
        }
    }

    /** Reads the current shape and, if it does not fit, the earlier one. */
    internal class TwoShapes(private val current: MsgSpec, private val legacy: MsgSpec, private val check: (MsgValue) -> Boolean) : DynTypes.Decoder {
        override fun decode(buffer: CDRBuffer): MsgValue {
            val bb = buffer.getBufferUnsafe()
            val start = bb.position()
            try {
                val v = DynCodec.read(current, buffer)
                if (sane(v) && check(v)) return v
            } catch (_: RuntimeException) { }
            bb.position(start)
            return upgrade(DynCodec.read(legacy, buffer), current)
        }
    }

    /** The value in `spec`'s shape: the fields it shares with the old one, the rest with their defaults. */
    internal fun upgrade(old: Map<*, *>, spec: MsgSpec): MsgValue = DynCodec.defaultValue(spec).apply {
        for (f in spec.fields) {
            val v = old[f.name] ?: continue
            val nested = f.nested
            put(f.name, if (nested != null) when (v) {
                is Map<*, *> -> upgrade(v, nested)
                is List<*> -> v.map { e -> if (e is Map<*, *>) upgrade(e, nested) else e }
                else -> v
            } else v)
        }
    }

    private const val MAX_SECONDS = 1e9

    /** Read from the wrong shape, numbers and texts come out as garbage: what real ones never are. */
    internal fun sane(v: Any?): Boolean = when (v) {
        is String -> v.none { it == '�' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') }
        is Double -> v.isFinite() && kotlin.math.abs(v) < 1e15
        is Map<*, *> -> {
            val ns = v["nanosec"]
            (ns !is Long || ns in 0 until 1_000_000_000L) && v.values.all { sane(it) }
        }
        is List<*> -> v.all { sane(it) }
        else -> true
    }

    private fun num(v: Any?) = (v as? Number)?.toDouble() ?: 0.0
    private fun completion(v: MsgValue) = num(v["completion"]).let { it.isFinite() && it in -0.01..1.01 }
    private fun inRange(v: Any?, range: LongRange) = (v as? Number)?.toLong()?.let { it in range } ?: false

    private val checks: Map<String, (MsgValue) -> Boolean> = mapOf(
        PLAN to { v ->
            (v["items"] as? List<*>).orEmpty().all { i ->
                val m = i as? Map<*, *> ?: return@all false
                num(m["time"]) in 0.0..MAX_SECONDS && num(m["duration"]) in 0.0..MAX_SECONDS &&
                    m["action"].toString().let { it.isEmpty() || it.startsWith("(") }
            }
        },
        EXECUTION_INFO to { v -> inRange(v["status"], 1L..5L) && completion(v) },
        ACTION_EXECUTION to { v -> inRange(v["type"], 1L..8L) && completion(v) },
        KNOWLEDGE to { v ->
            (v["predicates"] as? List<*>).orEmpty().all { it.toString().startsWith("(") } &&
                (v["functions"] as? List<*>).orEmpty().all { it.toString().startsWith("(") }
        },
    )
}
