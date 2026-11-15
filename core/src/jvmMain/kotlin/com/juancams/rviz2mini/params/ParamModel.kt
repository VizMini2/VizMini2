// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.params

import com.juancams.rviz2mini.ros.dyn.MsgValue

/**
 * ROS 2 node parameters (rcl_interfaces/msg/ParameterValue): types, conversion from/to the
 * message's dynamic value and parsing of the text the user types. No Android dependencies.
 */
object ParamModel {

    enum class Type(val id: Int, val label: String) {
        NOT_SET(0, "not set"), BOOL(1, "bool"), INTEGER(2, "integer"), DOUBLE(3, "double"), STRING(4, "string"),
        BYTE_ARRAY(5, "byte[]"), BOOL_ARRAY(6, "bool[]"), INTEGER_ARRAY(7, "integer[]"), DOUBLE_ARRAY(8, "double[]"),
        STRING_ARRAY(9, "string[]");

        val isArray: Boolean get() = id >= 5

        companion object {
            fun of(id: Int): Type = entries.firstOrNull { it.id == id } ?: NOT_SET
        }
    }

    /** A parameter value. `value` is Boolean, Long, Double, String or a list of them. */
    data class Value(val type: Type, val value: Any?) {
        /** Text to display and to edit ("true", "1.5", "[1, 2, 3]", "['a', 'b']"). */
        fun display(): String = when (type) {
            Type.NOT_SET -> "(not set)"
            Type.DOUBLE -> fmt(value as Double)
            Type.STRING -> value as String
            Type.BYTE_ARRAY -> (value as List<*>).joinToString(" ", "[", "]") { "%02x".format((it as Long) and 0xFF) }
            Type.DOUBLE_ARRAY -> (value as List<*>).joinToString(", ", "[", "]") { fmt(it as Double) }
            Type.STRING_ARRAY -> (value as List<*>).joinToString(", ", "[", "]") { "'$it'" }
            else -> if (type.isArray) (value as List<*>).joinToString(", ", "[", "]") else value.toString()
        }
    }

    data class Descriptor(
        val name: String,
        val type: Type,
        val description: String = "",
        val constraints: String = "",
        val readOnly: Boolean = false,
        /** Integer or floating-point range, if any: (from, to, step). */
        val range: Triple<Double, Double, Double>? = null
    )

    /** rcl_interfaces/msg/ParameterValue -> [Value]. */
    fun fromMsg(v: Map<*, *>?): Value {
        if (v == null) return Value(Type.NOT_SET, null)
        val type = Type.of((v["type"] as? Number)?.toInt() ?: 0)
        fun list(key: String): List<*> = when (val x = v[key]) { is ByteArray -> x.map { it.toLong() and 0xFF }; is List<*> -> x; else -> emptyList<Any?>() }
        val value: Any? = when (type) {
            Type.NOT_SET -> null
            Type.BOOL -> v["bool_value"] == true
            Type.INTEGER -> (v["integer_value"] as? Number)?.toLong() ?: 0L
            Type.DOUBLE -> (v["double_value"] as? Number)?.toDouble() ?: 0.0
            Type.STRING -> (v["string_value"] as? String ?: "")
            Type.BYTE_ARRAY -> list("byte_array_value").map { (it as? Number)?.toLong() ?: 0L }
            Type.BOOL_ARRAY -> list("bool_array_value").map { it == true }
            Type.INTEGER_ARRAY -> list("integer_array_value").map { (it as? Number)?.toLong() ?: 0L }
            Type.DOUBLE_ARRAY -> list("double_array_value").map { (it as? Number)?.toDouble() ?: 0.0 }
            Type.STRING_ARRAY -> list("string_array_value").map { (it as? String ?: "") }
        }
        return Value(type, value)
    }

    /** [Value] -> rcl_interfaces/msg/ParameterValue (the remaining fields at their default value). */
    /** Changed parameters from an rcl_interfaces/msg/ParameterEvent: name -> value. */
    fun eventChanges(ev: Map<*, *>): Map<String, Value> =
        (ev["changed_parameters"] as? List<*>).orEmpty().mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            (m["name"]?.toString() ?: return@mapNotNull null) to fromMsg(m["value"] as? Map<*, *>)
        }.toMap()

    fun toMsg(p: Value): MsgValue = MsgValue().apply {
        put("type", p.type.id.toLong())
        put("bool_value", p.type == Type.BOOL && p.value == true)
        put("integer_value", if (p.type == Type.INTEGER) p.value as Long else 0L)
        put("double_value", if (p.type == Type.DOUBLE) p.value as Double else 0.0)
        put("string_value", if (p.type == Type.STRING) p.value as String else "")
        put("byte_array_value", if (p.type == Type.BYTE_ARRAY) p.value as List<*> else emptyList<Long>())
        put("bool_array_value", if (p.type == Type.BOOL_ARRAY) p.value as List<*> else emptyList<Boolean>())
        put("integer_array_value", if (p.type == Type.INTEGER_ARRAY) p.value as List<*> else emptyList<Long>())
        put("double_array_value", if (p.type == Type.DOUBLE_ARRAY) p.value as List<*> else emptyList<Double>())
        put("string_array_value", if (p.type == Type.STRING_ARRAY) p.value as List<*> else emptyList<String>())
    }

    fun descriptorFromMsg(d: Map<*, *>): Descriptor {
        val fp = (d["floating_point_range"] as? List<*>)?.firstOrNull() as? Map<*, *>
        val ir = (d["integer_range"] as? List<*>)?.firstOrNull() as? Map<*, *>
        val range = (fp ?: ir)?.let { r ->
            Triple((r["from_value"] as Number).toDouble(), (r["to_value"] as Number).toDouble(), (r["step"] as Number).toDouble())
        }
        return Descriptor(
            (d["name"] as? String ?: ""),
            Type.of((d["type"] as? Number)?.toInt() ?: 0),
            (d["description"] as? String ?: ""),
            (d["additional_constraints"] as? String ?: ""),
            d["read_only"] == true,
            range
        )
    }

    class ParseException(message: String) : Exception(message)

    /** Parses what the user types for a parameter of type `type`. Throws [ParseException]. */
    fun parse(type: Type, text: String): Value {
        val t = text.trim()
        return when (type) {
            Type.NOT_SET -> throw ParseException("This parameter has no type")
            Type.BOOL -> Value(type, bool(t))
            Type.INTEGER -> Value(type, long(t))
            Type.DOUBLE -> Value(type, double(t))
            Type.STRING -> Value(type, text)
            Type.BYTE_ARRAY -> Value(type, items(t).map { s ->
                (s.removePrefix("0x").toLongOrNull(16)?.takeIf { s.length <= 4 && it in 0..255 }
                    ?: throw ParseException("\"$s\" is not a byte (00–ff)"))
            })
            Type.BOOL_ARRAY -> Value(type, items(t).map { bool(it) })
            Type.INTEGER_ARRAY -> Value(type, items(t).map { long(it) })
            Type.DOUBLE_ARRAY -> Value(type, items(t).map { double(it) })
            Type.STRING_ARRAY -> Value(type, items(t).map { it.trim().removeSurrounding("'").removeSurrounding("\"") })
        }
    }

    private fun bool(s: String) = when (s.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> throw ParseException("\"$s\" is not true or false")
    }

    private fun long(s: String) = s.toLongOrNull() ?: throw ParseException("\"$s\" is not an integer")

    private fun double(s: String) = s.toDoubleOrNull()?.takeIf { it.isFinite() || s.contains("inf", true) || s.contains("nan", true) }
        ?: throw ParseException("\"$s\" is not a number")

    /** "[1, 2, 3]", "1, 2, 3" or "1 2 3" -> elements; "[]" -> empty. */
    private fun items(s: String): List<String> {
        val inner = s.removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()
        return if (inner.contains(',')) inner.split(',').map { it.trim() } else inner.split(Regex("\\s+"))
    }

    private fun fmt(d: Double): String = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) "%.1f".format(java.util.Locale.ROOT, d) else d.toString()

    /** Name grouped as in rqt: "controller.gains.kp" -> ("controller.gains", "kp"). */
    fun group(name: String): Pair<String, String> {
        val i = name.lastIndexOf('.')
        return if (i < 0) "" to name else name.substring(0, i) to name.substring(i + 1)
    }
}
