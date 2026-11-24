// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros.dyn

/** A dynamic message like the YAML of `ros2 topic echo` (long sequences truncated). */
object MsgFormat {
    fun yaml(v: Any?, maxSeq: Int = 16, maxChars: Int = 20_000): String {
        val sb = StringBuilder()
        write(sb, v, 0, maxSeq)
        return if (sb.length > maxChars) sb.substring(0, maxChars) + "\n…" else sb.toString().trimEnd()
    }

    private fun write(sb: StringBuilder, v: Any?, indent: Int, maxSeq: Int) {
        val pad = "  ".repeat(indent)
        when (v) {
            is Map<*, *> -> for ((k, x) in v) {
                if (x is Map<*, *> && x.isNotEmpty()) { sb.append(pad).append(k).append(":\n"); write(sb, x, indent + 1, maxSeq) }
                else if (x is List<*> && x.isNotEmpty() && x.first() is Map<*, *>) {
                    sb.append(pad).append(k).append(":\n")
                    for ((i, e) in x.withIndex()) {
                        if (i >= maxSeq) { sb.append(pad).append("- … (").append(x.size).append(" items)\n"); break }
                        sb.append(pad).append("-\n"); write(sb, e, indent + 1, maxSeq)
                    }
                } else sb.append(pad).append(k).append(": ").append(scalar(x, maxSeq)).append('\n')
            }
            else -> sb.append(pad).append(scalar(v, maxSeq)).append('\n')
        }
    }

    private fun scalar(x: Any?, maxSeq: Int): String = when (x) {
        null -> "null"
        is String -> "'" + x.replace("\n", "\\n") + "'"
        is ByteArray -> if (x.size <= maxSeq) x.joinToString(", ", "[", "]") { (it.toInt() and 0xFF).toString() } else "[${x.size} bytes]"
        is List<*> -> if (x.size <= maxSeq) x.joinToString(", ", "[", "]") { scalar(it, maxSeq) } else x.take(maxSeq).joinToString(", ", "[", ", … (${x.size})]") { scalar(it, maxSeq) }
        is Double -> if (x == Math.floor(x) && !x.isInfinite() && Math.abs(x) < 1e15) "%.1f".format(java.util.Locale.ROOT, x) else x.toString()
        is Float -> x.toDouble().let { scalar(it, maxSeq) }
        is Map<*, *> -> "{}"
        else -> x.toString()
    }
}
