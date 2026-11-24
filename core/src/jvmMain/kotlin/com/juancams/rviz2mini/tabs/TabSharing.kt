// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.tabs

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Sharing individual tabs (like Foxglove layouts): a JSON with the tab and, if it is a
 * dashboard, its widgets. On import (or duplicate) everything gets new ids, also inside
 * the settings (the dashboard layout refers to its widgets by id). No Android: tested on the JVM.
 */
object TabSharing {
    const val FORMAT = "vizmini2-tabs"
    const val VERSION = 1

    /** The tab and its widgets. */
    fun export(tab: TabSpec, all: List<TabSpec>): String {
        val tabs = listOf(tab) + all.filter { it.dashboardId == tab.id }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("tabs", JSONArray(TabList.toJson(tabs))).toString(2)
    }

    /** The tabs of an exported JSON (or of a plain list of tabs); null if it is not one. */
    fun parse(json: String): List<TabSpec>? {
        val text = json.trim()
        val list = try {
            if (text.startsWith("[")) TabList.fromJson(text)
            else {
                val o = JSONObject(text)
                if (o.optString("format") != FORMAT) return null
                TabList.fromJson(o.optJSONArray("tabs")?.toString())
            }
        } catch (_: Exception) {
            null
        }
        return list?.takeIf { l -> l.any { !it.isWidget } }
    }

    /**
     * Copies with new ids, ready to add next to `existing`: first the tab (with a name
     * that does not clash, "Plot 2" if there is already a "Plot") and then its widgets. Loose widgets (without their
     * dashboard in the list) are discarded.
     */
    fun copies(tabs: List<TabSpec>, existing: List<TabSpec>): List<TabSpec> {
        val main = tabs.firstOrNull { !it.isWidget } ?: return emptyList()
        val widgets = tabs.filter { it.dashboardId == main.id }
        val ids = (listOf(main) + widgets).associate { it.id to UUID.randomUUID().toString() }
        fun remap(s: String): String { var out = s; for ((old, new) in ids) out = out.replace(old, new); return out }
        val name = if (existing.any { it.name == main.name && !it.isWidget }) TabList.resolveName("", main.type, existing.filter { !it.isWidget }, main.name) else main.name
        return (listOf(main) + widgets).map { t ->
            TabSpec(ids.getValue(t.id), t.type, if (t === main) name else t.name, t.settings.mapValues { (_, v) -> remap(v) })
        }
    }
}
