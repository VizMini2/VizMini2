// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.explore

/**
 * For each line of the YAML echo ([com.juancams.rviz2mini.ros.dyn.MsgFormat.yaml]), the field path if it is a standalone
 * number ("twist.linear.x"), or null (text, submessage headers, array elements). This way a field of the echo can be
 * dragged onto a plot.
 */
object YamlPaths {
    fun numericPaths(yaml: String): List<String?> {
        val stack = ArrayList<Pair<Int, String>>() // (indent, key) of the open submessages
        var arrayIndent = -1 // inside an array of submessages (from this indent): no paths
        return yaml.lines().map { raw ->
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            if (line.isEmpty()) return@map null
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
            if (arrayIndent >= 0 && indent <= arrayIndent && !line.startsWith("-")) arrayIndent = -1
            if (line.startsWith("-")) { if (arrayIndent < 0 || indent < arrayIndent) arrayIndent = indent; return@map null }
            val colon = line.indexOf(':')
            if (colon <= 0) return@map null
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (value.isEmpty()) { stack += indent to key; return@map null }
            if (arrayIndent >= 0) return@map null
            if (value.toDoubleOrNull() == null && value != "true" && value != "false") return@map null
            (stack.map { it.second } + key).joinToString(".")
        }
    }
}
