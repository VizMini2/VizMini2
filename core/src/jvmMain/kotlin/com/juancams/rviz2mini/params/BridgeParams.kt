// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.params

import com.juancams.rviz2mini.ros.FoxgloveBridge
import com.juancams.rviz2mini.ros.dyn.CallException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Parameters through foxglove_bridge's `parameters` capability (getParameters / setParameters), for
 * when the bridge does not advertise the node's services. Names look like "/node.parameter".
 */
object BridgeParams {

    fun all(b: FoxgloveBridge, node: String, timeoutMs: Long): List<ParamClient.Entry> {
        val list = call(timeoutMs) { done -> b.getParameters(emptyList(), done) }
        val prefix = "$node."
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optString("name").startsWith(prefix) }
            .map { p ->
                val v = valueOf(p)
                ParamClient.Entry(ParamModel.Descriptor(p.optString("name").removePrefix(prefix), v.type), v)
            }.sortedBy { it.descriptor.name }
    }

    fun set(b: FoxgloveBridge, node: String, name: String, value: ParamModel.Value, timeoutMs: Long): String? {
        val full = "$node.$name"
        val p = JSONObject().put("name", full).put("value", toJson(value))
        when (value.type) {
            ParamModel.Type.DOUBLE -> p.put("type", "float64")
            ParamModel.Type.DOUBLE_ARRAY -> p.put("type", "float64_array")
            else -> {}
        }
        val result = call(timeoutMs) { done -> b.setParameters(JSONArray().put(p), done) }
        // The response carries the resulting value: if it is not the requested one, the node rejected it.
        val now = (0 until result.length()).mapNotNull { result.optJSONObject(it) }.firstOrNull { it.optString("name") == full }
            ?: return null
        return if (valueOf(now) == value) null else "Rejected by the node"
    }

    private fun call(timeoutMs: Long, send: ((JSONArray?) -> Unit) -> String): JSONArray {
        val q = ArrayBlockingQueue<Pair<JSONArray?, Boolean>>(1)
        send { q.offer(it to true) }
        val r = q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw CallException("foxglove_bridge did not answer about parameters")
        return r.first ?: throw CallException("No connection with foxglove_bridge")
    }

    /** {"name", "value", "type"?} -> model value. */
    fun valueOf(p: JSONObject): ParamModel.Value {
        val type = p.optString("type")
        val v = p.opt("value")
        fun list(a: JSONArray) = (0 until a.length()).map { a.opt(it) }
        return when {
            v == null || v == JSONObject.NULL -> ParamModel.Value(ParamModel.Type.NOT_SET, null)
            v is Boolean -> ParamModel.Value(ParamModel.Type.BOOL, v)
            v is String && type == "byte_array" -> ParamModel.Value(ParamModel.Type.BYTE_ARRAY, java.util.Base64.getMimeDecoder().decode(v).map { it.toLong() and 0xFF })
            v is String -> ParamModel.Value(ParamModel.Type.STRING, v)
            v is Number && (type == "float64" || v is Double || v is Float) -> ParamModel.Value(ParamModel.Type.DOUBLE, v.toDouble())
            v is Number -> ParamModel.Value(ParamModel.Type.INTEGER, v.toLong())
            v is JSONArray -> {
                val items = list(v)
                when {
                    type == "float64_array" || items.any { it is Double || it is Float } -> ParamModel.Value(ParamModel.Type.DOUBLE_ARRAY, items.map { (it as Number).toDouble() })
                    items.all { it is Boolean } && items.isNotEmpty() -> ParamModel.Value(ParamModel.Type.BOOL_ARRAY, items)
                    items.all { it is Number } -> ParamModel.Value(ParamModel.Type.INTEGER_ARRAY, items.map { (it as Number).toLong() })
                    else -> ParamModel.Value(ParamModel.Type.STRING_ARRAY, items.map { it.toString() })
                }
            }
            else -> ParamModel.Value(ParamModel.Type.STRING, v.toString())
        }
    }

    private fun toJson(v: ParamModel.Value): Any? = when (v.type) {
        ParamModel.Type.NOT_SET -> JSONObject.NULL
        ParamModel.Type.BYTE_ARRAY -> java.util.Base64.getEncoder().encodeToString((v.value as List<*>).map { (it as Long).toByte() }.toByteArray())
        else -> if (v.type.isArray) JSONArray(v.value as List<*>) else v.value
    }
}
