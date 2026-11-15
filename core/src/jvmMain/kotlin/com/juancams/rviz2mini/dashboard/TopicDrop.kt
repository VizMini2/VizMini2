// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.dashboard

import com.juancams.rviz2mini.sliders.SliderMath
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry

/**
 * Dropping a topic (or a field of a topic) on a live tab: the one that knows what to do with it (RViz adds a display,
 * Plot a series...) registers here with its id while it is open. Used by the dashboard drag and drop on the
 * phone and on the PC. Main thread.
 */
object TopicDrop {
    /** What is dragged: the topic, its type and, if a specific field was dragged, its path ("twist.linear.x"). */
    data class Payload(val topic: String, val type: String, val field: String? = null)

    /** What the tab did with the topic (the text for the notice), or null if it is no use to it. */
    fun interface Handler { fun drop(p: Payload): String? }

    private val handlers = java.util.concurrent.ConcurrentHashMap<String, Handler>()

    fun register(tabId: String, h: Handler) { handlers[tabId] = h }
    fun unregister(tabId: String, h: Handler) { handlers.remove(tabId, h) }
    fun accepts(tabId: String) = handlers.containsKey(tabId)
    fun deliver(tabId: String, p: Payload): String? = handlers[tabId]?.drop(p)

    /** The numeric field that makes most sense to plot for a type: "data" if there is one; otherwise, the first outside the header. */
    fun guessField(registry: InterfaceRegistry, type: String): String? {
        val fields = runCatching { SliderMath.numericFields(registry.msg(type)) }.getOrDefault(emptyList()).map { it.first }
        return fields.firstOrNull { it == "data" } ?: fields.firstOrNull { !it.startsWith("header.") && !it.contains("stamp") } ?: fields.firstOrNull()
    }
}
