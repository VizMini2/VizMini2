// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.sliders

import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.ros.dyn.Prim
import com.juancams.rviz2mini.ros.dyn.ValueException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** When a slider publishes: while dragging (at [SliderSpec.rateHz]) or only on release. */
enum class SliderMode {
    WHILE_DRAGGING, ON_RELEASE;

    companion object {
        fun fromName(name: String?): SliderMode = entries.firstOrNull { it.name == name } ?: WHILE_DRAGGING
    }
}

/**
 * A slider in the "Sliders" tab: publishes on `target` a message of `type` with the numeric field
 * `field` ("data", "linear.x") set to the chosen value and the rest from `values`, like a button.
 *
 * @param step 0 = continuous; otherwise the value snaps to min + k·step.
 * @param springBack on release it returns to `initial` and publishes it (velocities: release = stop).
 * @param rateHz publications per second while dragging (also with the finger still).
 * @param definitions custom definitions used by the type (to load it on another phone).
 */
data class SliderSpec(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val target: String = "",
    val type: String = "",
    val field: String = "",
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 0.0,
    val initial: Double = 0.0,
    val mode: SliderMode = SliderMode.WHILE_DRAGGING,
    val rateHz: Double = 10.0,
    val springBack: Boolean = false,
    val values: String = "{}",
    val color: Int = 0,
    val definitions: Map<String, String> = emptyMap()
) {
    val displayLabel: String get() = label.ifBlank { listOf(target, this.field).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Slider" } }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("target", target).put("type", type).put("field", field)
        .put("min", min).put("max", max).put("step", step).put("initial", initial).put("mode", mode.name)
        .put("rateHz", rateHz).put("springBack", springBack).put("values", values).put("color", color)
        .apply { if (definitions.isNotEmpty()) put("definitions", JSONObject(definitions as Map<*, *>)) }

    companion object {
        const val MAX_RATE_HZ = 50.0

        /** Ignores impossible values (min ≥ max, negative step...) by using the defaults. */
        fun fromJson(o: JSONObject): SliderSpec {
            fun num(key: String, default: Double) = o.optDouble(key, default).takeIf { it.isFinite() } ?: default
            var min = num("min", 0.0)
            var max = num("max", 1.0)
            if (min >= max) { min = 0.0; max = 1.0 }
            val step = num("step", 0.0).takeIf { it >= 0 && it <= max - min } ?: 0.0
            return SliderSpec(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                label = o.optString("label"),
                target = o.optString("target"),
                type = o.optString("type"),
                field = o.optString("field"),
                min = min,
                max = max,
                step = step,
                initial = SliderMath.quantize(num("initial", min), min, max, step),
                mode = SliderMode.fromName(o.optString("mode")),
                rateHz = num("rateHz", 10.0).coerceIn(1.0, MAX_RATE_HZ),
                springBack = o.optBoolean("springBack"),
                values = o.optString("values", "{}"),
                color = o.optInt("color").coerceAtLeast(0),
                definitions = o.optJSONObject("definitions")?.let { d -> d.keys().asSequence().associateWith { d.optString(it) } }.orEmpty()
            )
        }
    }
}

/** List of sliders saved as JSON in the tab settings. */
object SliderList {

    fun toJson(sliders: List<SliderSpec>): String = JSONArray().apply { for (s in sliders) put(s.toJson()) }.toString()

    /** Ignores corrupt entries instead of failing. */
    fun fromJson(json: String?): List<SliderSpec> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { SliderSpec.fromJson(it) } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Moves slider `id` one position (`delta` = -1 before, +1 after). */
    fun move(sliders: List<SliderSpec>, id: String, delta: Int): List<SliderSpec> {
        val i = sliders.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in sliders.indices) return sliders
        return sliders.toMutableList().apply { add(j, removeAt(i)) }
    }
}

/** Slider math, without Android (tested on the JVM). */
object SliderMath {

    /**
     * Numeric fields (integers or reals, not arrays) of a message, with their path: "data",
     * "linear.x"... Submessages are traversed; arrays are not.
     */
    fun numericFields(spec: MsgSpec, prefix: String = ""): List<Pair<String, Prim>> {
        val out = ArrayList<Pair<String, Prim>>()
        for (f in spec.fields) {
            if (f.isArray) continue
            val path = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
            val p = f.prim
            if (p != null) { if (p.isInteger || p.isFloat) out += path to p }
            else out += numericFields(f.nested!!, path)
        }
        return out
    }

    /** Type of field `path`, or null if it does not exist or is not numeric. */
    fun fieldPrim(spec: MsgSpec, path: String): Prim? = numericFields(spec).firstOrNull { it.first == path }?.second

    /** Within [min, max] and, with a step, at the nearest multiple of `step` counted from `min`. */
    fun quantize(value: Double, min: Double, max: Double, step: Double): Double {
        val v = value.coerceIn(min, max)
        if (step <= 0) return v
        val snapped = min + Math.round((v - min) / step) * step
        // No floating-point residue (0.30000000000000004) and no exceeding max due to rounding.
        return (Math.round(snapped * 1e9) / 1e9).coerceIn(min, max)
    }

    /** Decimals to show values with: those of the step or, without a step, based on the range. */
    fun decimals(min: Double, max: Double, step: Double): Int {
        if (step > 0) {
            val s = java.math.BigDecimal.valueOf(step).stripTrailingZeros()
            return s.scale().coerceIn(0, 6)
        }
        val range = max - min
        return when {
            range >= 100 -> 0
            range >= 10 -> 1
            range >= 1 -> 2
            else -> 3
        }
    }

    fun format(value: Double, decimals: Int, integer: Boolean): String =
        if (integer) Math.round(value).toString() else String.format(java.util.Locale.ROOT, "%.${decimals}f", value)

    /**
     * Message to publish: `values` (the remaining fields, as in a button) with `field` set to
     * `value`. An integer field is rounded; if it does not fit its type it throws [ValueException].
     */
    fun payload(spec: MsgSpec, values: String, field: String, value: Double, nowNs: Long = System.currentTimeMillis() * 1_000_000L): MsgValue {
        val prim = fieldPrim(spec, field) ?: throw ValueException(field, "is not a numeric field of ${spec.name}")
        val m = DynCodec.fromJson(spec, JSONObject(values.ifBlank { "{}" }), nowNs, strict = true)
        val leaf: Any = if (prim.isInteger) DynCodec.coerceScalar(prim, Math.round(value), field) else value
        val parts = field.split('.')
        var cur: MutableMap<String, Any?> = m
        for (p in parts.dropLast(1)) {
            @Suppress("UNCHECKED_CAST")
            cur = cur[p] as MutableMap<String, Any?>
        }
        cur[parts.last()] = leaf
        return m
    }

    /**
     * Checks a configuration: returns the problem or null. The texts are keys translated by
     * the interface ([Problem]).
     */
    fun validate(min: Double?, max: Double?, step: Double?, initial: Double?): Problem? = when {
        min == null || max == null || step == null || initial == null -> Problem.NOT_A_NUMBER
        !min.isFinite() || !max.isFinite() || !step.isFinite() || !initial.isFinite() -> Problem.NOT_A_NUMBER
        min >= max -> Problem.MIN_NOT_BELOW_MAX
        step < 0 || step > max - min -> Problem.BAD_STEP
        initial < min || initial > max -> Problem.INITIAL_OUT_OF_RANGE
        else -> null
    }

    enum class Problem { NOT_A_NUMBER, MIN_NOT_BELOW_MAX, BAD_STEP, INITIAL_OUT_OF_RANGE }
}
