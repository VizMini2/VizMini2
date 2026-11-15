// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.indicators

import com.juancams.rviz2mini.ros.dyn.FieldSpec
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.Prim
import com.juancams.rviz2mini.rviz.display.QosPreset
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** How an indicator is displayed. */
enum class IndicatorKind {
    /** The value in large type. */
    NUMBER,
    /** Needle on an arc from min to max. */
    GAUGE,
    /** Horizontal bar from min to max. */
    BAR,
    /** Light on or off according to a rule. */
    LIGHT,
    /** Text (a string, an enum...). */
    TEXT,
    /** All fields of the message (or of a submessage) in a table. */
    TABLE;

    val numeric get() = this == NUMBER || this == GAUGE || this == BAR

    companion object {
        fun fromName(name: String?): IndicatorKind = entries.firstOrNull { it.name == name } ?: NUMBER
    }
}

/** When a light turns on. */
enum class LightRule {
    /** true, a non-zero number or a non-empty text. */
    NONZERO,
    /** value >= threshold. */
    ABOVE,
    /** value <= threshold. */
    BELOW,
    /** value == threshold (as a number or, if it is not one, as text). */
    EQUALS;

    companion object {
        fun fromName(name: String?): LightRule = entries.firstOrNull { it.name == name } ?: NONZERO
    }
}

/**
 * An indicator in the "Indicators" tab: subscribes to `topic` (type `type`) and shows the field
 * `field` ("battery.percentage", "ranges[0]", "" = the whole message in a table).
 *
 * @param decimals decimals of the value; -1 = based on the field type.
 * @param timeoutS if more seconds pass without messages, the indicator is marked as having no data (0 = never).
 * @param definitions own definitions used by the type (to load it on another phone).
 */
data class IndicatorSpec(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val topic: String = "",
    val type: String = "",
    val field: String = "",
    val kind: IndicatorKind = IndicatorKind.NUMBER,
    val min: Double = 0.0,
    val max: Double = 100.0,
    val decimals: Int = -1,
    val unit: String = "",
    val rule: LightRule = LightRule.NONZERO,
    val threshold: String = "",
    val onColor: Int = 2,
    val offColor: Int = 6,
    val color: Int = 0,
    val qos: QosPreset = QosPreset.SENSOR,
    val timeoutS: Double = 0.0,
    val definitions: Map<String, String> = emptyMap()
) {
    val displayLabel: String get() = label.ifBlank { listOf(topic, this.field).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Indicator" } }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("topic", topic).put("type", type).put("field", field)
        .put("kind", kind.name).put("min", min).put("max", max).put("decimals", decimals).put("unit", unit)
        .put("rule", rule.name).put("threshold", threshold).put("onColor", onColor).put("offColor", offColor)
        .put("color", color).put("qos", qos.name).put("timeoutS", timeoutS)
        .apply { if (definitions.isNotEmpty()) put("definitions", JSONObject(definitions as Map<*, *>)) }

    companion object {
        /** Ignores impossible values by setting the defaults. */
        fun fromJson(o: JSONObject): IndicatorSpec {
            fun num(key: String, default: Double) = o.optDouble(key, default).takeIf { it.isFinite() } ?: default
            var min = num("min", 0.0)
            var max = num("max", 100.0)
            if (min >= max) { min = 0.0; max = 100.0 }
            return IndicatorSpec(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                label = o.optString("label"),
                topic = o.optString("topic"),
                type = o.optString("type"),
                field = o.optString("field"),
                kind = IndicatorKind.fromName(o.optString("kind")),
                min = min,
                max = max,
                decimals = o.optInt("decimals", -1).coerceIn(-1, 9),
                unit = o.optString("unit"),
                rule = LightRule.fromName(o.optString("rule")),
                threshold = o.optString("threshold"),
                onColor = o.optInt("onColor", 2).coerceAtLeast(0),
                offColor = o.optInt("offColor", 6).coerceAtLeast(0),
                color = o.optInt("color").coerceAtLeast(0),
                qos = QosPreset.entries.firstOrNull { it.name == o.optString("qos") } ?: QosPreset.SENSOR,
                timeoutS = num("timeoutS", 0.0).coerceIn(0.0, 3600.0),
                definitions = o.optJSONObject("definitions")?.let { d -> d.keys().asSequence().associateWith { d.optString(it) } }.orEmpty()
            )
        }
    }
}

/** List of indicators saved as JSON in the tab settings. */
object IndicatorList {

    fun toJson(items: List<IndicatorSpec>): String = JSONArray().apply { for (s in items) put(s.toJson()) }.toString()

    /** Ignores corrupt entries instead of failing. */
    fun fromJson(json: String?): List<IndicatorSpec> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { IndicatorSpec.fromJson(it) } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Moves indicator `id` one position (`delta` = -1 earlier, +1 later). */
    fun move(items: List<IndicatorSpec>, id: String, delta: Int): List<IndicatorSpec> {
        val i = items.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in items.indices) return items
        return items.toMutableList().apply { add(j, removeAt(i)) }
    }
}

/** Indicator calculations, without Android (tested on the JVM). */
object IndicatorMath {

    /** A field that can be displayed: its path and the type shown when choosing it. */
    data class Leaf(val path: String, val typeName: String, val prim: Prim?)

    /**
     * Individual fields of a message: scalars, times (`header.stamp`, in seconds) and the first
     * element of each array (`ranges[0]`; the index can be changed by hand).
     */
    fun leaves(spec: MsgSpec, prefix: String = "", depth: Int = 0): List<Leaf> {
        val out = ArrayList<Leaf>()
        if (depth > 8) return out
        for (f in spec.fields) {
            val path = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
            val nested = f.nested
            when {
                nested != null && nested.isTime && !f.isArray -> out += Leaf(path, "time", null)
                nested != null && f.isArray -> out += leaves(nested, "$path[0]", depth + 1)
                nested != null -> out += leaves(nested, path, depth + 1)
                f.isArray -> out += Leaf("$path[0]", f.prim!!.rosName, f.prim)
                else -> out += Leaf(path, f.prim!!.rosName, f.prim)
            }
        }
        return out
    }

    /** Submessages that can be shown in a table ("" = the whole message). */
    fun tables(spec: MsgSpec, prefix: String = "", depth: Int = 0): List<String> {
        val out = ArrayList<String>()
        if (depth == 0) out += ""
        if (depth > 6) return out
        for (f in spec.fields) {
            val nested = f.nested ?: continue
            if (f.isArray || nested.isTime) continue
            val path = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
            out += path
            out += tables(nested, path, depth + 1)
        }
        return out
    }

    /** A filter comparison: `field op value`. */
    private class Cond(val lhs: List<Step>, val op: String, val literal: String, val number: Double?)

    private sealed class Step {
        class Name(val name: String) : Step()
        /** Array index; negative counts from the end ([-1] = the last one). */
        class Index(val i: Int) : Step()
        /** `{field==value}`: in an array, the first matching element; in a message, the message if it matches. */
        class Filter(val cond: Cond) : Step()
    }

    private val COND = Regex("""^\s*([A-Za-z_][A-Za-z0-9_.\[\]-]*)\s*(==|!=|<=|>=|<|>)\s*(.+?)\s*$""")

    /**
     * "a.b[2].c", "poses[-1].x", "status{name==\"motor\"}.level", "{mode==2}.speed" -> steps; null if
     * the path is malformed. Filters, as in Foxglove, go in braces after an array (it keeps
     * the first matching element) or at the start (only matching messages count).
     */
    private fun parse(path: String): List<Step>? {
        val out = ArrayList<Step>()
        var i = 0
        val p = path.trim()
        var expectName = true
        while (i < p.length) {
            val c = p[i]
            when {
                c == '[' -> {
                    val close = p.indexOf(']', i)
                    if (close < 0 || out.isEmpty() && expectName) return null
                    out += Step.Index(p.substring(i + 1, close).trim().toIntOrNull() ?: return null)
                    i = close + 1; expectName = false
                }
                c == '{' -> {
                    val close = closing(p, i) ?: return null
                    val m = COND.matchEntire(p.substring(i + 1, close)) ?: return null
                    val lhs = parse(m.groupValues[1])?.takeIf { l -> l.isNotEmpty() && l.none { it is Step.Filter } } ?: return null
                    var lit = m.groupValues[3]
                    val quoted = lit.length >= 2 && (lit[0] == '"' || lit[0] == '\'') && lit.last() == lit[0]
                    if (quoted) lit = lit.substring(1, lit.length - 1)
                    val num = if (quoted) null else when (lit) { "true" -> 1.0; "false" -> 0.0; else -> lit.toDoubleOrNull() }
                    out += Step.Filter(Cond(lhs, m.groupValues[2], lit, num))
                    i = close + 1; expectName = false
                }
                c == '.' -> { if (expectName || i == p.length - 1) return null; i++; expectName = true }
                else -> {
                    if (!expectName) return null
                    val m = Regex("""^[A-Za-z_][A-Za-z0-9_]*""").find(p.substring(i)) ?: return null
                    out += Step.Name(m.value)
                    i += m.value.length; expectName = false
                }
            }
        }
        if (expectName && out.isNotEmpty()) return null
        return out
    }

    /** Brace that closes the one at `open` (respecting quotes). */
    private fun closing(p: String, open: Int): Int? {
        var quote: Char? = null
        for (k in open + 1 until p.length) {
            val c = p[k]
            if (quote != null) { if (c == quote) quote = null; continue }
            if (c == '"' || c == '\'') quote = c else if (c == '}') return k
        }
        return null
    }

    /** Whether the path has filters (a message that does not match does not count, instead of leaving the value empty). */
    fun hasFilter(path: String): Boolean = parse(path)?.any { it is Step.Filter } == true

    /** The path's field in the definition: its FieldSpec (if it is an element, the array's) or null if it does not exist. */
    fun resolve(spec: MsgSpec, path: String): FieldSpec? {
        val steps = parse(path) ?: return null
        var cur: MsgSpec = spec
        var field: FieldSpec? = null
        var indexed = true
        for (s in steps) {
            when (s) {
                is Step.Name -> {
                    if (field != null) {
                        // To enter a submessage the element must have been chosen if it is an array.
                        if (field.isArray && !indexed) return null
                        cur = field.nested ?: return null
                    }
                    field = cur.fields.firstOrNull { it.name == s.name } ?: return null
                    indexed = false
                }
                is Step.Index -> {
                    if (field == null || !field.isArray || indexed) return null
                    indexed = true
                }
                is Step.Filter -> {
                    // The filter field is looked up in the element (array of messages) or in the current message.
                    val target = when {
                        field == null -> cur
                        field.isArray && !indexed -> field.nested ?: return null
                        else -> field.nested ?: return null
                    }
                    resolveSteps(target, s.cond.lhs) ?: return null
                    if (field != null && field.isArray && !indexed) indexed = true
                }
            }
        }
        if (field != null && field.isArray && !indexed) return null
        return field
    }

    private fun resolveSteps(spec: MsgSpec, steps: List<Step>): FieldSpec? =
        resolve(spec, steps.joinToString("") { s -> when (s) { is Step.Name -> ".${s.name}"; is Step.Index -> "[${s.i}]"; is Step.Filter -> "" } }.removePrefix("."))

    /** Value of the path in a received message (MsgValue), or null if absent (index out of range, or a filter does not match). */
    fun valueAt(msg: Map<String, Any?>, path: String): Any? = parse(path)?.let { walk(msg, it) }

    private fun walk(msg: Any?, steps: List<Step>): Any? {
        var cur: Any? = msg
        for (s in steps) {
            cur = when (s) {
                is Step.Name -> (cur as? Map<*, *>)?.get(s.name) ?: return null
                is Step.Index -> when (cur) {
                    is List<*> -> cur.getOrNull(if (s.i < 0) cur.size + s.i else s.i)
                    is ByteArray -> cur.getOrNull(if (s.i < 0) cur.size + s.i else s.i)?.toInt()?.and(0xFF)
                    else -> null
                } ?: return null
                is Step.Filter -> when (cur) {
                    is List<*> -> cur.firstOrNull { matches(it, s.cond) }
                    is Map<*, *> -> cur.takeIf { matches(it, s.cond) }
                    else -> null
                } ?: return null
            }
        }
        return cur
    }

    private fun matches(item: Any?, c: Cond): Boolean {
        val v = walk(item, c.lhs) ?: return false
        val d = if (v is String) null else toDouble(v)
        val cmp: Int = if (d != null && c.number != null) d.compareTo(c.number) else {
            val text = v.toString()
            if (c.op != "==" && c.op != "!=") return false
            if (text == c.literal) 0 else 1
        }
        return when (c.op) {
            "==" -> cmp == 0
            "!=" -> cmp != 0
            "<" -> cmp < 0
            "<=" -> cmp <= 0
            ">" -> cmp > 0
            else -> cmp >= 0
        }
    }

    /** Number from a value: numbers, bool (1/0), times (seconds) and texts that are numbers. */
    fun toDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is Map<*, *> -> if (v.containsKey("sec") && v.containsKey("nanosec"))
            (v["sec"] as Number).toDouble() + (v["nanosec"] as Number).toDouble() / 1e9 else null
        is String -> v.trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }

    /** Default decimals: none for integers and bool, 2 for reals (more if the range is small). */
    fun autoDecimals(prim: Prim?, min: Double = 0.0, max: Double = 100.0): Int = when {
        prim != null && (prim.isInteger || prim == Prim.BOOL) -> 0
        prim == null -> 3 // times
        max - min < 1 -> 3
        else -> 2
    }

    /** Text of a received value. */
    fun format(v: Any?, decimals: Int): String = when (v) {
        null -> "—"
        is Boolean -> v.toString()
        is Float, is Double -> {
            val d = (v as Number).toDouble()
            if (d.isNaN()) "NaN" else if (d.isInfinite()) (if (d > 0) "∞" else "-∞") else String.format(Locale.ROOT, "%.${decimals.coerceIn(0, 9)}f", d)
        }
        is Number -> v.toString()
        is Map<*, *> -> toDouble(v)?.let { String.format(Locale.ROOT, "%.${decimals.coerceIn(0, 9)}f", it) } ?: v.toString()
        is ByteArray -> "[${v.size} bytes]"
        is List<*> -> "[${v.size}]"
        else -> v.toString()
    }

    /** Position of the value between min and max (0..1). */
    fun fraction(v: Double, min: Double, max: Double): Double =
        if (!v.isFinite() || max <= min) 0.0 else ((v - min) / (max - min)).coerceIn(0.0, 1.0)

    /** Whether the light is on with this value. */
    fun lightOn(rule: LightRule, threshold: String, v: Any?): Boolean {
        if (v == null) return false
        val d = toDouble(v)
        val t = threshold.trim().replace(',', '.').toDoubleOrNull()
        return when (rule) {
            LightRule.NONZERO -> when (v) { is String -> v.isNotEmpty(); else -> d != null && d != 0.0 && !d.isNaN() }
            LightRule.ABOVE -> d != null && t != null && d >= t
            LightRule.BELOW -> d != null && t != null && d <= t
            LightRule.EQUALS -> if (d != null && t != null && v !is String) d == t else v.toString() == threshold.trim()
        }
    }

    /**
     * "path -> value" rows of a message or submessage for the table. Arrays are summarized (the
     * first elements and how many there are), just like `ros2 topic echo --truncate-length`.
     */
    fun rows(spec: MsgSpec, value: Map<String, Any?>, decimals: Int = 3, maxRows: Int = 200): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun walk(s: MsgSpec, v: Map<*, *>, prefix: String) {
            for (f in s.fields) {
                if (out.size >= maxRows) return
                val path = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
                val x = v[f.name]
                val nested = f.nested
                when {
                    nested != null && nested.isTime && !f.isArray -> out += path to format(x, 3)
                    nested != null && !f.isArray -> walk(nested, (x as? Map<*, *>) ?: emptyMap<String, Any?>(), path)
                    nested != null -> out += path to "[${(x as? List<*>)?.size ?: 0} ${nested.name.substringAfterLast('/')}]"
                    f.isArray -> out += path to summary(x, f.prim!!, decimals)
                    else -> out += path to format(x, if (f.prim!!.isFloat) decimals else 0)
                }
            }
        }
        walk(spec, value, "")
        return out
    }

    private fun summary(x: Any?, prim: Prim, decimals: Int): String {
        val items: List<Any?> = when (x) {
            is ByteArray -> x.take(8).map { it.toInt() and 0xFF } + if (x.size > 8) listOf(Unit) else emptyList()
            is List<*> -> x
            else -> emptyList()
        }
        val n = (x as? ByteArray)?.size ?: (x as? List<*>)?.size ?: 0
        val shown = items.filter { it != Unit }.take(6).joinToString(", ") { format(it, if (prim.isFloat) decimals else 0) }
        return if (n > 6) "[$shown, …] ($n)" else "[$shown]"
    }
}
