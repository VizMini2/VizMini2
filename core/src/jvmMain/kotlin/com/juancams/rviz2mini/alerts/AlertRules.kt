// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.alerts

/**
 * Alert rules, without Android: they decide when to notify based on what arrives from the network.
 * Each tracker remembers the previous state and only notifies on changes (and does not repeat the same notice
 * within `cooldownMs`).
 */
object AlertRules {

    data class Alert(val key: String, val title: String, val text: String, val severity: Severity)

    enum class Severity { WARNING, ERROR, RECOVERED }

    /** Diagnostics: notifies when a status rises to ERROR or STALE (diagnostic_msgs levels: 0 OK, 1 WARN, 2 ERROR, 3 STALE). */
    class Diagnostics(private val cooldownMs: Long = 60_000, private val includeWarnings: Boolean = false) {
        private val levels = HashMap<String, Int>()
        private val lastAlert = HashMap<String, Long>()

        fun update(name: String, level: Int, message: String, nowMs: Long): Alert? {
            val previous = levels.put(name, level)
            val threshold = if (includeWarnings) 1 else 2
            // Only when the level rises above the threshold.
            if (level < threshold || (previous != null && level <= previous)) return null
            val last = lastAlert[name]
            if (last != null && nowMs - last < cooldownMs) return null
            lastAlert[name] = nowMs
            val label = when (level) { 1 -> "WARN"; 2 -> "ERROR"; else -> "STALE" }
            return Alert("diag:$name", "$label · $name", message.ifBlank { "Diagnostic level changed to $label" },
                if (level == 1) Severity.WARNING else Severity.ERROR)
        }
    }

    /**
     * Presence of a set of things (lifecycle nodes): notifies when one disappears for
     * `missingTicks` consecutive checks and when it comes back.
     */
    class Presence(private val what: String, private val missingTicks: Int = 2) {
        private val known = HashSet<String>()
        private val missing = HashMap<String, Int>()
        private val lost = HashSet<String>()

        fun update(present: Set<String>): List<Alert> {
            val out = ArrayList<Alert>()
            for (p in present) {
                missing.remove(p)
                if (lost.remove(p)) out += Alert("presence:$p", "$what back: $p", "$p is reachable again.", Severity.RECOVERED)
                known.add(p)
            }
            for (k in known) {
                if (k in present || k in lost) continue
                val n = (missing[k] ?: 0) + 1
                missing[k] = n
                if (n >= missingTicks) {
                    lost.add(k)
                    out += Alert("presence:$k", "$what lost: $k", "$k is no longer on the network.", Severity.ERROR)
                }
            }
            return out
        }

        /** When the network changes, the previous state no longer counts. */
        fun reset() { known.clear(); missing.clear(); lost.clear() }
    }

    /** Network contact: there were other participants and none are left (or they come back). */
    class Contact(private val missingTicks: Int = 3) {
        private var seenOthers = false
        private var missing = 0
        private var lost = false

        fun update(othersVisible: Boolean): Alert? {
            if (othersVisible) {
                seenOthers = true
                missing = 0
                if (lost) {
                    lost = false
                    return Alert("contact", "Robot reachable again", "ROS 2 nodes are visible on the network again.", Severity.RECOVERED)
                }
                return null
            }
            if (!seenOthers || lost) return null
            if (++missing >= missingTicks) {
                lost = true
                return Alert("contact", "Lost contact with the robot", "No other ROS 2 node is visible on the network.", Severity.ERROR)
            }
            return null
        }

        fun reset() { seenOthers = false; missing = 0; lost = false }
    }

    /** Lifecycle states worth a notice (lifecycle_msgs/State): finalized and errorprocessing. */
    fun lifecycleAlert(node: String, stateId: Int, label: String): Alert? = when (stateId) {
        4 -> Alert("lc:$node", "Lifecycle node finalized: $node", "$node shut down (state: ${label.ifBlank { "finalized" }}).", Severity.WARNING)
        15 -> Alert("lc:$node", "Lifecycle node error: $node", "$node is processing an error (state: ${label.ifBlank { "errorprocessing" }}).", Severity.ERROR)
        else -> null
    }
}
