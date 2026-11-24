// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.tabs

import com.juancams.rviz2mini.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Tab types that can be created from the "+" button. `displayName` is the English name (without
 * Android, for the JVM tests); the UI uses [label], translated.
 */
enum class TabType(val displayName: String, val labelKey: String) {
    RVIZ("RViz", "tab_type_rviz"),
    CAMERA("Camera", "tab_type_camera"),
    TELEOP("Teleop", "tab_type_teleop"),
    TELEOP_DUAL("Teleop (2 joysticks)", "tab_type_teleop_dual"),
    DIAGNOSTICS("Diagnostics", "tab_type_diagnostics"),
    BUTTONS("Buttons", "tab_type_buttons"),
    SLIDERS("Sliders", "tab_type_sliders"),
    INDICATORS("Indicators", "tab_type_indicators"),
    PLOT("Plot", "tab_type_plot"),
    GPS("GPS map", "tab_type_gps"),
    BEHAVIOR_TREE("Behavior tree", "tab_type_behavior_tree"),
    MESSAGE("Message", "tab_type_message"),
    PARAMETERS("Parameters", "tab_type_parameters"),
    LIFECYCLE("Lifecycle", "tab_type_lifecycle"),
    BAGS("Bags", "tab_type_bags"),
    NODES("Nodes", "tab_type_nodes"),
    TOPICS("Topics", "tab_type_topics"),
    SERVICES("Services", "tab_type_services"),
    ACTIONS("Actions", "tab_type_actions"),
    DASHBOARD("Dashboard", "tab_type_dashboard"),
    NODE_GRAPH("Node graph", "tab_type_node_graph"),
    TF_TREE("TF tree", "tab_type_tf_tree"),
    MOVEIT("MoveIt", "tab_type_moveit"),
    CONTROLLERS("Controllers", "tab_type_controllers"),
    MAPS("Maps", "tab_type_maps"),
    ALERTS("Alerts", "tab_type_alerts"),
    PLANSYS2("PlanSys2", "tab_type_plansys2");

    /** Type name in the app's language. */
    fun label(): String = tr(labelKey)

    companion object {
        fun fromName(name: String): TabType? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A tab created by the user. `settings` stores each type's own configuration
 * (camera topic, RViz fixed frame...) so it survives app restarts.
 */
data class TabSpec(
    val id: String,
    val type: TabType,
    val name: String,
    val settings: Map<String, String> = emptyMap()
) {
    /** If it is a dashboard widget, the dashboard id (it does not show in the tab bar). */
    val dashboardId: String? get() = settings[KEY_DASHBOARD]
    val isWidget: Boolean get() = dashboardId != null

    fun withSetting(key: String, value: String?): TabSpec {
        val next = settings.toMutableMap()
        if (value == null) next.remove(key) else next[key] = value
        return copy(settings = next)
    }

    companion object {
        const val KEY_DASHBOARD = "dashboard"

        fun create(type: TabType, name: String) =
            TabSpec(UUID.randomUUID().toString(), type, name)
    }
}

/** Ordered list of tabs with the naming rules. No Android dependencies. */
object TabList {

    const val MAX_NAME_LENGTH = 24

    fun defaultTabs(): List<TabSpec> = listOf(TabSpec.create(TabType.RVIZ, TabType.RVIZ.displayName))

    /**
     * If the user does not give a name: "Camera", "Camera 2", "Camera 3"... `base` is the type name
     * in the app's language ([TabType.label]); English by default.
     */
    fun resolveName(requested: String, type: TabType, existing: List<TabSpec>, base: String = type.displayName): String {
        val clean = requested.trim().take(MAX_NAME_LENGTH)
        if (clean.isNotEmpty()) return clean
        val taken = existing.map { it.name }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }

    fun toJson(tabs: List<TabSpec>): String {
        val arr = JSONArray()
        for (tab in tabs) {
            val settings = JSONObject()
            for ((k, v) in tab.settings) settings.put(k, v)
            arr.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("type", tab.type.name)
                    .put("name", tab.name)
                    .put("settings", settings)
            )
        }
        return arr.toString()
    }

    /** Ignores corrupt entries or entries of types that no longer exist instead of failing. */
    fun fromJson(json: String?): List<TabSpec>? {
        if (json.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<TabSpec>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = TabType.fromName(o.optString("type")) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val settings = HashMap<String, String>()
                o.optJSONObject("settings")?.let { s ->
                    for (key in s.keys()) settings[key] = s.optString(key)
                }
                out.add(TabSpec(id, type, o.optString("name", type.displayName), settings))
            }
            out
        } catch (e: Exception) {
            null
        }
    }
}
