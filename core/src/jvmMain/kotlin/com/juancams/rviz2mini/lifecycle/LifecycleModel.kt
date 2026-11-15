// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.lifecycle

/**
 * States and transitions of ROS 2 lifecycle nodes (lifecycle_msgs/msg/State and Transition).
 * No Android dependencies.
 */
object LifecycleModel {

    // Primary states
    const val UNKNOWN = 0
    const val UNCONFIGURED = 1
    const val INACTIVE = 2
    const val ACTIVE = 3
    const val FINALIZED = 4

    /** State kind, to choose the color. */
    enum class Kind { UNKNOWN, UNCONFIGURED, INACTIVE, ACTIVE, FINALIZED, TRANSITIONING, ERROR }

    data class State(val id: Int, val label: String) {
        val kind: Kind get() = kindOf(id)
        val isTransitioning: Boolean get() = kind == Kind.TRANSITIONING || kind == Kind.ERROR
        /** "active", "configuring"... capitalized for the UI. */
        val title: String get() = label.ifBlank { defaultLabel(id) }.replaceFirstChar { it.uppercase() }
    }

    data class Transition(val id: Int, val label: String, val goalStateId: Int = -1) {
        val title: String get() = label.ifBlank { "transition $id" }.replaceFirstChar { it.uppercase() }
        /** Transitions that shut down or destroy the node: they ask for confirmation. */
        val isDestructive: Boolean get() = label.contains("shutdown") || label == "destroy"
    }

    fun kindOf(id: Int): Kind = when (id) {
        UNCONFIGURED -> Kind.UNCONFIGURED
        INACTIVE -> Kind.INACTIVE
        ACTIVE -> Kind.ACTIVE
        FINALIZED -> Kind.FINALIZED
        15 -> Kind.ERROR // TRANSITION_STATE_ERRORPROCESSING
        in 10..14 -> Kind.TRANSITIONING
        else -> Kind.UNKNOWN
    }

    fun defaultLabel(id: Int): String = when (id) {
        UNCONFIGURED -> "unconfigured"
        INACTIVE -> "inactive"
        ACTIVE -> "active"
        FINALIZED -> "finalized"
        10 -> "configuring"
        11 -> "cleaningup"
        12 -> "shuttingdown"
        13 -> "activating"
        14 -> "deactivating"
        15 -> "errorprocessing"
        else -> "unknown"
    }

    /**
     * Order in which the buttons are shown: the "normal" path first (configure, activate...)
     * and the shutdown ones at the end.
     */
    fun sortTransitions(list: List<Transition>): List<Transition> =
        list.distinctBy { it.id }.sortedWith(compareBy({ it.isDestructive }, { ORDER.indexOf(it.label).let { i -> if (i < 0) 99 else i } }, { it.id }))

    private val ORDER = listOf("configure", "activate", "deactivate", "cleanup")

    /** List of nodes saved in the tab settings (one per line). */
    fun parseNodes(setting: String?): List<String> =
        setting.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.map { normalizeNode(it) }.distinct()

    fun formatNodes(nodes: List<String>): String = nodes.joinToString("\n")

    /** "talker" -> "/talker"; without trailing slash. */
    fun normalizeNode(name: String): String {
        val t = name.trim().trimEnd('/')
        return if (t.startsWith("/")) t else "/$t"
    }
}
