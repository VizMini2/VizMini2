// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.plot

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** What is done to each value before drawing it. */
enum class SeriesTransform {
    NONE,
    /** Change per second relative to the previous sample. */
    DERIVATIVE,
    /** Mean of the last [SeriesSpec.window] samples. */
    MOVING_AVERAGE,
    /** Cumulative sum over time (trapezoidal rule): from a velocity, the distance. */
    INTEGRAL;

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/**
 * A series: the field `field` ("twist.twist.linear.x", "ranges[-1]", "status{name==motor}.level") of
 * the messages on `topic`, or, if it has a `formula`, a series computed from others ([Formula]).
 * After the transform, the value is multiplied by `scale` and `offset` is added.
 *
 * @param color index into [PlotSpecs.PALETTE]; -1 = the next free one in the panel.
 */
data class SeriesSpec(
    val id: String = UUID.randomUUID().toString(),
    val topic: String = "",
    val type: String = "",
    val field: String = "",
    val label: String = "",
    val color: Int = -1,
    val transform: SeriesTransform = SeriesTransform.NONE,
    val window: Int = 5,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val formula: String = "",
    /** Lua script ([LuaScript]): function body, global variables and input series (names, one per line). */
    val script: String = "",
    val scriptGlobals: String = "",
    val inputs: String = ""
) {
    val isScript: Boolean get() = script.isNotBlank()

    /** Computed from other series (formula or script), not read from a source. */
    val isFormula: Boolean get() = formula.isNotBlank() || isScript

    val inputList: List<String> get() = inputs.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    val displayLabel: String get() = label.ifBlank {
        when {
            isScript -> "lua(" + inputList.joinToString(", ") + ")"
            isFormula -> formula.trim()
            else -> listOf(topic, this.field).filter { it.isNotBlank() }.joinToString(".").ifBlank { "?" }
        }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("topic", topic).put("type", type).put("field", field)
        .put("label", label).put("color", color).put("transform", transform.name).put("window", window)
        .put("scale", scale).put("offset", offset).apply {
            if (formula.isNotBlank()) put("formula", formula)
            if (isScript) { put("script", script); put("scriptGlobals", scriptGlobals); put("inputs", inputs) }
        }

    companion object {
        fun fromJson(o: JSONObject): SeriesSpec {
            fun num(key: String, default: Double) = o.optDouble(key, default).takeIf { it.isFinite() } ?: default
            return SeriesSpec(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                topic = o.optString("topic"),
                type = o.optString("type"),
                field = o.optString("field"),
                label = o.optString("label"),
                color = o.optInt("color", -1).coerceIn(-1, PlotSpecs.PALETTE.size - 1),
                transform = SeriesTransform.fromName(o.optString("transform")),
                window = o.optInt("window", 5).coerceIn(2, 1000),
                scale = num("scale", 1.0),
                offset = num("offset", 0.0),
                formula = o.optString("formula"),
                script = o.optString("script"),
                scriptGlobals = o.optString("scriptGlobals"),
                inputs = o.optString("inputs")
            )
        }
    }
}

/**
 * A panel shows its series over time, as XY (the first, x, against the second, y), as
 * state transitions (STATES: one band per series that changes color with each value, like
 * Foxglove's State Transitions panel; also works for texts), as a spectrum (FFT) or as a
 * histogram of the values, these two with what is in the time window.
 * `sameScale`: in XY, the same scale on both axes (for trajectories in meters).
 * `scatter`: in XY, individual points instead of a line.
 */
enum class PanelKind { TIME, XY, STATES, FFT, HISTOGRAM }

data class PanelSpec(
    val id: String = UUID.randomUUID().toString(),
    val kind: PanelKind = PanelKind.TIME,
    val series: List<SeriesSpec> = emptyList(),
    val sameScale: Boolean = false,
    val scatter: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("sameScale", sameScale).put("scatter", scatter)
        .put("series", JSONArray().apply { for (s in series) put(s.toJson()) })

    /** Color of each series: the chosen one or, otherwise, the palette colors nobody uses, in order. */
    fun colorOf(s: SeriesSpec): Int {
        if (s.color >= 0) return PlotSpecs.PALETTE[s.color]
        val used = series.filter { it.color >= 0 }.map { it.color }.toSet()
        val free = PlotSpecs.PALETTE.indices.filter { it !in used }.ifEmpty { PlotSpecs.PALETTE.indices.toList() }
        val n = series.filter { it.color < 0 }.indexOfFirst { it.id == s.id }.coerceAtLeast(0)
        return PlotSpecs.PALETTE[free[n % free.size]]
    }

    companion object {
        fun fromJson(o: JSONObject): PanelSpec = PanelSpec(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            kind = PanelKind.entries.firstOrNull { it.name == o.optString("kind") } ?: PanelKind.TIME,
            series = o.optJSONArray("series")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(SeriesSpec::fromJson) } }.orEmpty(),
            sameScale = o.optBoolean("sameScale"),
            scatter = o.optBoolean("scatter")
        )
    }
}

/**
 * The panels of a plots tab and the visible time window. `headerStamp`: the time of
 * each sample is the message's `header.stamp` (if it has one) instead of the arrival time.
 */
data class PlotLayout(val panels: List<PanelSpec> = listOf(PanelSpec()), val windowS: Double = 30.0, val headerStamp: Boolean = false) {

    val allSeries: List<SeriesSpec> get() = panels.flatMap { it.series }

    /**
     * Renames a series also in the formulas that use it (`{old}` -> `{new}`).
     */
    fun renameReferences(old: String, new: String): PlotLayout = if (old == new) this else copy(panels = panels.map { p ->
        p.copy(series = p.series.map { s ->
            when {
                s.isScript -> s.copy(inputs = s.inputList.joinToString("\n") { if (it == old) new else it })
                s.isFormula -> s.copy(formula = s.formula.replace(Regex("""\{\s*${Regex.escape(old)}\s*}"""), Regex.escapeReplacement("{$new}")))
                else -> s
            }
        })
    })

    fun toJson(): String = JSONObject().put("windowS", windowS).put("headerStamp", headerStamp)
        .put("panels", JSONArray().apply { for (p in panels) put(p.toJson()) }).toString()

    fun replacePanel(p: PanelSpec) = copy(panels = panels.map { if (it.id == p.id) p else it })

    companion object {
        fun fromJson(json: String?): PlotLayout {
            if (json.isNullOrBlank()) return PlotLayout()
            return try {
                val o = JSONObject(json)
                val panels = o.optJSONArray("panels")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PanelSpec::fromJson) } }.orEmpty()
                PlotLayout(panels.ifEmpty { listOf(PanelSpec()) }.take(PlotSpecs.MAX_PANELS),
                    o.optDouble("windowS", 30.0).takeIf { it.isFinite() }?.coerceIn(PlotSpecs.MIN_WINDOW_S, PlotSpecs.MAX_WINDOW_S) ?: 30.0,
                    o.optBoolean("headerStamp"))
            } catch (_: Exception) {
                PlotLayout()
            }
        }
    }
}

object PlotSpecs {
    const val MAX_PANELS = 3
    const val MAX_SERIES = 6
    const val MIN_WINDOW_S = 1.0
    const val MAX_WINDOW_S = 3600.0
    val PALETTE = intArrayOf(
        0xFF42A5F5.toInt(), 0xFFFF7043.toInt(), 0xFF66BB6A.toInt(), 0xFFFFD54F.toInt(),
        0xFFAB47BC.toInt(), 0xFF26C6DA.toInt(), 0xFFEC407A.toInt(), 0xFF8D6E63.toInt()
    )
    val WINDOWS_S = doubleArrayOf(5.0, 10.0, 30.0, 60.0, 300.0, 900.0)
}
