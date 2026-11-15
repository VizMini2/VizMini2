// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.rvizfile

/**
 * Minimal YAML, enough for the .rviz files RViz 2 saves: block maps and lists by indentation,
 * plain or quoted scalars, empty `{}`/`[]`, `~` and comments. Maps preserve
 * order. Scalars are always returned as String (or null for `~`/empty).
 */
object MiniYaml {

    private class Line(val indent: Int, val text: String, val number: Int)

    class YamlException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val lines = ArrayList<Line>()
        for ((i, raw) in text.lines().withIndex()) {
            val noTabs = raw.replace("\t", "  ")
            val stripped = stripComment(noTabs).trimEnd()
            if (stripped.isBlank()) continue
            if (stripped.trim() == "---" || stripped.trim() == "...") continue
            val indent = stripped.length - stripped.trimStart().length
            lines.add(Line(indent, stripped.trim(), i + 1))
        }
        if (lines.isEmpty()) return null
        val pos = intArrayOf(0)
        return parseBlock(lines, pos, lines[0].indent)
    }

    private fun parseBlock(lines: List<Line>, pos: IntArray, indent: Int): Any? {
        if (pos[0] >= lines.size) return null
        val first = lines[pos[0]]
        if (isSeqItem(first.text)) return parseSeq(lines, pos, first.indent)
        // Value on its own line ("key:\n  {}" or a scalar).
        if (keyOf(first.text) == null) { pos[0]++; return scalar(first.text) }
        return parseMap(lines, pos, first.indent)
    }

    private fun isSeqItem(t: String) = t == "-" || t.startsWith("- ")

    private fun parseSeq(lines: List<Line>, pos: IntArray, indent: Int): List<Any?> {
        val out = ArrayList<Any?>()
        while (pos[0] < lines.size) {
            val line = lines[pos[0]]
            if (line.indent < indent || !isSeqItem(line.text) || line.indent > indent) break
            val rest = line.text.removePrefix("-").trimStart()
            pos[0]++
            when {
                rest.isEmpty() -> {
                    // The content continues on the lines below, more indented.
                    out.add(if (pos[0] < lines.size && lines[pos[0]].indent > indent) parseBlock(lines, pos, lines[pos[0]].indent) else null)
                }
                keyOf(rest) != null -> {
                    // "- key: value": a map whose first key is on the dash line.
                    val itemIndent = indent + (line.text.length - rest.length)
                    val virtual = ArrayList<Line>()
                    virtual.add(Line(itemIndent, rest, line.number))
                    while (pos[0] < lines.size && lines[pos[0]].indent >= itemIndent && !(lines[pos[0]].indent == indent && isSeqItem(lines[pos[0]].text))) {
                        virtual.add(lines[pos[0]]); pos[0]++
                    }
                    out.add(parseMap(virtual, intArrayOf(0), itemIndent))
                }
                else -> out.add(scalar(rest))
            }
        }
        return out
    }

    private fun parseMap(lines: List<Line>, pos: IntArray, indent: Int): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        while (pos[0] < lines.size) {
            val line = lines[pos[0]]
            if (line.indent < indent) break
            if (line.indent > indent) throw YamlException("Unexpected indentation on line ${line.number}")
            if (isSeqItem(line.text)) break
            val key = keyOf(line.text) ?: throw YamlException("Expected 'key: value' on line ${line.number}")
            val value = line.text.substring(key.second).trim()
            pos[0]++
            out[key.first] = when {
                value.isNotEmpty() -> scalar(value)
                pos[0] < lines.size && lines[pos[0]].indent > indent -> parseBlock(lines, pos, lines[pos[0]].indent)
                // List at the same level as the key ("Displays:\n- a"), valid in YAML.
                pos[0] < lines.size && lines[pos[0]].indent == indent && isSeqItem(lines[pos[0]].text) -> parseSeq(lines, pos, indent)
                else -> null
            }
        }
        return out
    }

    /** Key and position after the ':' if the line is "key: ..." (respecting quotes). */
    private fun keyOf(t: String): Pair<String, Int>? {
        if (t.startsWith("\"") || t.startsWith("'")) {
            val q = t[0]
            val end = t.indexOf(q, 1)
            if (end < 0 || end + 1 >= t.length || t[end + 1] != ':') return null
            if (end + 2 < t.length && t[end + 2] != ' ') return null
            return t.substring(1, end) to end + 2
        }
        var i = 0
        while (i < t.length) {
            if (t[i] == ':' && (i + 1 == t.length || t[i + 1] == ' ')) {
                if (i == 0) return null
                return t.substring(0, i).trim() to i + 1
            }
            i++
        }
        return null
    }

    private fun scalar(v: String): Any? = when {
        v == "~" || v == "null" -> null
        v == "{}" -> emptyMap<String, Any?>()
        v == "[]" -> emptyList<Any?>()
        v.length >= 2 && v.startsWith("\"") && v.endsWith("\"") -> unescape(v.substring(1, v.length - 1))
        v.length >= 2 && v.startsWith("'") && v.endsWith("'") -> v.substring(1, v.length - 1).replace("''", "'")
        else -> v
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); '"' -> sb.append('"'); '\\' -> sb.append('\\')
                    else -> { sb.append('\\'); sb.append(n) }
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Removes "# comment" if it is not inside quotes (and is preceded by a space or at the start). */
    private fun stripComment(s: String): String {
        var quote: Char? = null
        for (i in s.indices) {
            val c = s[i]
            if (quote != null) { if (c == quote) quote = null; continue }
            if (c == '"' || c == '\'') {
                // Only counts as a quote at the start of a value (not in "it's").
                val prev = s.substring(0, i).trimEnd()
                if (prev.isEmpty() || prev.endsWith(":") || prev.endsWith("-")) quote = c
                continue
            }
            if (c == '#' && (i == 0 || s[i - 1] == ' ')) return s.substring(0, i)
        }
        return s
    }
}
