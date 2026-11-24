// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros.dyn

import com.juancams.rviz2mini.platform.Platform
import org.json.JSONObject

/**
 * The app's interface registry: the built-in ones (assets/ros_interfaces.txt) plus those defined
 * by the user, which are stored in SharedPreferences and take priority.
 */
object Interfaces {

    private const val ASSET = "ros_interfaces.txt"
    private const val PREFS = "rviz2mini_interfaces"
    private const val KEY_CUSTOM = "custom"

    @Volatile private var registry: InterfaceRegistry? = null

    fun get(): InterfaceRegistry {
        registry?.let { return it }
        synchronized(this) {
            registry?.let { return it }
            val r = InterfaceRegistry()
            r.loadBundle(Platform.openAsset(ASSET).bufferedReader().use { it.readText() })
            for ((name, text) in custom()) {
                try { r.define(name, text) } catch (_: IdlException) { }
            }
            registry = r
            return r
        }
    }

    /** Definitions added by the user (full name -> text). */
    fun custom(): Map<String, String> {
        val json = Platform.prefs(PREFS).getString(KEY_CUSTOM, null) ?: return emptyMap()
        return try {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.optString(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * User definitions that `fullName` depends on (itself and its submessages), to
     * save them along with whatever uses it.
     */
    fun customDependencies(fullName: String): Map<String, String> {
        val custom = custom()
        if (custom.isEmpty()) return emptyMap()
        val r = get()
        val names = LinkedHashSet<String>()
        fun walk(spec: MsgSpec) {
            if (!names.add(spec.name)) return
            for (f in spec.fields) f.nested?.let { walk(it) }
        }
        val n = InterfaceRegistry.normalize(fullName) ?: return emptyMap()
        names.add(n)
        try {
            when (n.split('/')[1]) {
                "msg" -> walk(r.msg(n))
                "srv" -> r.srv(n).let { walk(it.request); walk(it.response) }
                else -> r.action(n).let { walk(it.goal); walk(it.result); walk(it.feedback) }
            }
        } catch (_: IdlException) { }
        return names.mapNotNull { name -> custom[name]?.let { name to it } }.toMap()
    }

    /**
     * Adds the definitions carried by something imported (a profile button) if they don't exist yet.
     * Existing ones are not touched; invalid ones are ignored.
     */
    fun importMissing(definitions: Map<String, String>) {
        if (definitions.isEmpty()) return
        val r = get()
        // There may be dependencies between them: it retries while any of them gets in.
        var pending = definitions.filterKeys { !r.has(it) }
        while (pending.isNotEmpty()) {
            val before = pending.size
            pending = pending.filter { (name, text) -> try { saveCustom(name, text); false } catch (_: IdlException) { true } }
            if (pending.size == before) break
        }
    }

    /** Saves (or replaces) a user definition and applies it. Throws [IdlException] if it is invalid. */
    fun saveCustom(fullName: String, text: String): String {
        val name = get().defineChecked(fullName, text)
        val all = custom().toMutableMap().also { it[name] = text }
        Platform.prefs(PREFS).edit()
            .putString(KEY_CUSTOM, JSONObject(all as Map<*, *>).toString()).apply()
        return name
    }
}
