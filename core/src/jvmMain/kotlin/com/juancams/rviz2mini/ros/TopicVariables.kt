// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import com.juancams.rviz2mini.platform.Platform

/**
 * Variables in topic names, like those of Foxglove layouts: a topic `${ns}/scan` is `/robot1/scan` with
 * `ns = /robot1`. They are resolved when subscribing or publishing (RosConnection), so they apply to everything at once (RViz,
 * plots, indicators, buttons...). Changing a variable reconnects so that everything uses the new name.
 */
object TopicVariables {
    private const val PREFS = "rviz2mini_variables"
    // The escaped "}": Android's regex engine (ICU) does not accept a bare one (Java's does).
    private val RE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")

    fun all(): Map<String, String> = Platform.prefs(PREFS).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap().toSortedMap()

    fun set(name: String, value: String?) {
        val e = Platform.prefs(PREFS).edit()
        if (value == null) e.putString(name, null) else e.putString(name, value)
        e.apply()
        RosConnection.restart()
    }

    /** `${ns}/scan` → `/robot1/scan` (a variable without a value is removed); and without double slashes. */
    fun resolve(topic: String, vars: Map<String, String> = all()): String {
        if ('$' !in topic) return topic
        val r = RE.replace(topic) { m -> vars[m.groupValues[1]]?.trim().orEmpty() }
        return ("/" + r.split('/').filter { it.isNotEmpty() }.joinToString("/"))
    }

    fun validName(n: String) = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(n)
}
