// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.tabs

import com.juancams.rviz2mini.platform.Platform

/** Tab persistence (on Android, the usual SharedPreferences). UI thread only. */
class TabStore private constructor() {

    private val prefs = Platform.prefs(PREFS)
    private val tabs = ArrayList<TabSpec>(TabList.fromJson(prefs.getString(KEY_TABS, null)) ?: TabList.defaultTabs())

    val all: List<TabSpec> get() = tabs

    fun get(id: String): TabSpec? = tabs.firstOrNull { it.id == id }

    fun add(tab: TabSpec) {
        tabs.add(tab)
        save()
    }

    fun remove(id: String) {
        tabs.removeAll { it.id == id }
        save()
    }

    fun update(tab: TabSpec) {
        val i = tabs.indexOfFirst { it.id == tab.id }
        if (i < 0) return
        tabs[i] = tab
        save()
    }

    /** Replaces all the tabs (when loading a robot profile). */
    fun replaceAll(newTabs: List<TabSpec>) {
        tabs.clear()
        tabs.addAll(newTabs)
        save()
    }

    fun updateSetting(id: String, key: String, value: String?) {
        get(id)?.let { update(it.withSetting(key, value)) }
    }

    private fun save() {
        prefs.edit().putString(KEY_TABS, TabList.toJson(tabs)).apply()
    }

    companion object {
        private const val PREFS = "rviz2mini_tabs"
        private const val KEY_TABS = "tabs"

        @Volatile private var instance: TabStore? = null

        fun get(): TabStore =
            instance ?: synchronized(this) { instance ?: TabStore().also { instance = it } }
    }
}
