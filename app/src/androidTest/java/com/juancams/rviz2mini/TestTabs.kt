// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabStore
import org.json.JSONObject
import java.io.File

/**
 * Saves the user's tabs before a test and restores them afterwards. It also leaves a copy in a
 * file: if a test hangs or the process dies before restoring, the next run finds it and
 * restores the user's tabs first (previously the test tabs were left on the phone).
 */
object TestTabs {
    private const val PREFS = "rviz2mini_tabs"
    private const val KEY = "tabs"

    private fun file(context: Context) = File(context.filesDir, "test_tabs_backup.json")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tabs saved now (JSON or null), to pass to [restore] when finishing. */
    fun backup(context: Context): String? {
        val f = file(context)
        if (f.exists()) {
            runCatching { JSONObject(f.readText()).let { if (it.isNull(KEY)) null else it.getString(KEY) } }
                .onSuccess { restore(context, it) }
        }
        val saved = prefs(context).getString(KEY, null)
        f.writeText(JSONObject().put(KEY, saved ?: JSONObject.NULL).toString())
        return saved
    }

    fun restore(context: Context, saved: String?) {
        // TabStore is a singleton with the tabs in memory: if only the file is restored, the test ones get saved again later.
        TabStore.get().replaceAll(TabList.fromJson(saved) ?: TabList.defaultTabs())
        prefs(context).edit().apply { if (saved == null) remove(KEY) else putString(KEY, saved) }.commit()
        file(context).delete()
    }
}
