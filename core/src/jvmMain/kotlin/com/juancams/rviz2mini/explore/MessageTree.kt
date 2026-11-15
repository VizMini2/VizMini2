// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.explore

import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.indicators.IndicatorMath
import com.juancams.rviz2mini.ros.dyn.FieldSpec
import com.juancams.rviz2mini.ros.dyn.MsgSpec

/**
 * The Message tab's tree, without UI: one line per field, with submessages and sequences that
 * expand. Each platform draws it (ui/MessageScreen) and the tests read it.
 */
object MessageTree {
    /** `toggle`: the key that expands or collapses this line; `openByDefault`: open until collapsed. */
    data class Line(val text: String, val depth: Int, val dim: Boolean = false, val toggle: String? = null, val open: Boolean = false, val openByDefault: Boolean = false)

    const val MAX_ROWS = 400
    const val MAX_ELEMENTS = 100

    /** What is shown: the message, or the part of the path (with its definition if it is a submessage). */
    fun build(spec: MsgSpec, msg: Map<String, Any?>, path: String, expanded: Set<String>, collapsed: Set<String>): List<Line> {
        val b = Builder(expanded, collapsed)
        val p = path.trim()
        if (p.isEmpty()) { b.fields(spec, msg, "", 0); return b.out }
        val v = IndicatorMath.valueAt(msg, p) ?: return listOf(Line(tr("message_path_nothing", p), 0, dim = true))
        val f = IndicatorMath.resolve(spec, p)
        val nested = f?.nested
        if (v is Map<*, *> && nested != null) b.fields(nested, v, p, 0) else b.value(p, f, v, p, 0)
        return b.out
    }

    /** A line's text as displayed ("▾ pose  {Pose}"). */
    fun text(l: Line): String = if (l.toggle != null) (if (l.open) "▾ " else "▸ ") + l.text else l.text

    private class Builder(val expanded: Set<String>, val collapsed: Set<String>) {
        val out = ArrayList<Line>()
        var rows = 0

        fun fields(s: MsgSpec, v: Map<*, *>, prefix: String, depth: Int) {
            for (f in s.fields) {
                if (rows >= MAX_ROWS) { out += Line("…", depth, dim = true); return }
                val p = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
                value(f.name, f, v[f.name], p, depth)
            }
        }

        fun value(name: String, f: FieldSpec?, v: Any?, p: String, depth: Int) {
            rows++
            val nested = f?.nested
            when {
                nested != null && nested.isTime && !(f.isArray && v is List<*>) -> out += Line("$name: ${IndicatorMath.format(v, 9)}", depth)
                v is Map<*, *> && nested != null -> {
                    val openByDefault = depth == 0
                    val open = if (openByDefault) p !in collapsed else p in expanded
                    out += Line("$name  {${nested.name.substringAfterLast('/')}}", depth, toggle = p, open = open, openByDefault = openByDefault)
                    if (open) fields(nested, v, p, depth + 1)
                }
                v is List<*> || v is ByteArray -> {
                    val n = (v as? List<*>)?.size ?: (v as ByteArray).size
                    val open = p in expanded
                    out += Line("$name  [$n]", depth, toggle = p, open = open)
                    if (!open) return
                    for (k in 0 until minOf(n, MAX_ELEMENTS)) {
                        if (rows >= MAX_ROWS) break
                        val item: Any? = if (v is ByteArray) v[k].toInt() and 0xFF else (v as List<*>)[k]
                        value("[$k]", f, item, "$p[$k]", depth + 1)
                    }
                    if (n > MAX_ELEMENTS) out += Line(tr("message_more", n - MAX_ELEMENTS), depth + 1, dim = true)
                }
                else -> out += Line("$name: ${IndicatorMath.format(v, if (v is Float) 6 else 9)}", depth)
            }
        }
    }
}
