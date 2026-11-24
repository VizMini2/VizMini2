// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.indicators

import com.juancams.rviz2mini.platform.SystemClock
import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.IdlException
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.Prim
import com.juancams.rviz2mini.rviz.display.profile
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * An indicator's subscription. Keeps only the latest (the field value or, for a table, its
 * rows) and when it arrived; the UI reads it at its own pace with [latest]. Messages arrive on the
 * DDS thread.
 */
class IndicatorRunner(val spec: IndicatorSpec, registry: InterfaceRegistry) {

    /** The latest received: the field value, or the table rows. */
    class Sample(val value: Any?, val rows: List<Pair<String, String>>?, val atMs: Long)

    private val last = AtomicReference<Sample?>()
    val received = AtomicLong()

    /** Type of the selected field (null for times and tables): for the default decimals. */
    val prim: Prim?
    /** Why it cannot work (unknown type, field that does not exist...), or null. */
    val setupError: String?
    private var subscription: DynRos.Subscription? = null

    init {
        var p: Prim? = null
        var error: String? = null
        try {
            if (spec.topic.isBlank()) throw IdlException("No topic")
            // Without a type (imported from a Foxglove layout): the one the graph says.
            val type = spec.type.ifBlank {
                com.juancams.rviz2mini.ros.RosConnection.graph()?.topics(includeHidden = true)?.firstOrNull { it.name == spec.topic }?.types?.firstOrNull()
                    ?: throw IdlException("${spec.topic} is not on the network yet (its type is unknown)")
            }
            val msg = registry.msg(type)
            val tableSpec: MsgSpec? = if (spec.kind == IndicatorKind.TABLE) tableSpec(msg, spec.field) else null
            if (spec.kind == IndicatorKind.TABLE) {
                if (tableSpec == null) throw IdlException("'${spec.field}' is not a message field of ${spec.type}")
            } else {
                val f = IndicatorMath.resolve(msg, spec.field) ?: throw IdlException("'${spec.field}' is not a field of ${spec.type}")
                if (f.nested?.isTime == false) throw IdlException("'${spec.field}' is a message: use a table")
                p = f.prim
            }
            // With filters, a message that does not match them is ignored (the last value stays).
            val filtered = IndicatorMath.hasFilter(spec.field)
            subscription = DynRos.subscribe(DynRos.normalizeName(spec.topic), msg, spec.qos.profile()) { v ->
                val now = SystemClock.uptimeMillis()
                received.incrementAndGet()
                val value = if (spec.field.isBlank()) v else IndicatorMath.valueAt(v, spec.field)
                if (filtered && value == null) return@subscribe
                last.set(
                    if (tableSpec != null) {
                        @Suppress("UNCHECKED_CAST")
                        Sample(null, IndicatorMath.rows(tableSpec, (value as? Map<String, Any?>) ?: emptyMap(), spec.decimals.takeIf { it >= 0 } ?: 3), now)
                    } else Sample(value, null, now)
                )
            }
        } catch (e: IdlException) {
            error = e.message
        } catch (e: Exception) {
            Log.e("VizMini2", "No se pudo preparar el indicador ${spec.displayLabel}", e)
            error = e.message ?: e.javaClass.simpleName
        }
        prim = p
        setupError = error
    }

    fun latest(): Sample? = last.get()

    /** Decimals the value is shown with. */
    val decimals: Int get() = if (spec.decimals >= 0) spec.decimals else IndicatorMath.autoDecimals(prim, spec.min, spec.max)

    fun close() {
        subscription?.close()
        subscription = null
    }

    companion object {
        /** The definition of the `path` submessage ("" = the message), or null if it is not a submessage. */
        fun tableSpec(msg: MsgSpec, path: String): MsgSpec? {
            if (path.isBlank()) return msg
            val f = IndicatorMath.resolve(msg, path) ?: return null
            return f.nested?.takeIf { !it.isTime && !(f.isArray && !path.endsWith("]") && !path.endsWith("}")) }
        }
    }
}
