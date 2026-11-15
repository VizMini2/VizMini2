// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import org.json.JSONObject

/**
 * Demo mode: saves the user's connection and tabs, connects to a local-only network (without
 * touching the real robot's), creates sample tabs and starts [DemoRobot]. On exit it restores everything.
 */
object DemoMode {

    private const val PREFS = "rviz2mini_demo"
    private const val KEY_BACKUP = "backup"
    /** Domain and loopback: the simulated robot's data never leaves the phone. */
    val CONFIG = DemoRobot.NETWORK

    private var robot: DemoRobot? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_BACKUP)

    fun addListener(l: (Boolean) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Boolean) -> Unit) { listeners.remove(l) }

    /** Sample tabs showing what the simulated robot publishes (shared with the PC: [com.juancams.rviz2mini.ui.demo.DemoTabs]). */
    fun demoTabs(@Suppress("UNUSED_PARAMETER") context: Context): List<TabSpec> = com.juancams.rviz2mini.ui.demo.DemoTabs.all()

    /** Enters demo mode. `replaceTabs` changes the visible tabs (MainActivity). */
    fun enter(context: Context, replaceTabs: (List<TabSpec>) -> Unit) {
        if (isActive(context)) return
        // The saved one, not the connection's.
        val backup = JSONObject()
            .put("ros", RosConfig.load().toJson())
            .put("tabs", TabList.toJson(TabStore.get().all))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BACKUP, backup.toString()).commit()
        RosConnection.applyConfig(CONFIG)
        replaceTabs(demoTabs(context))
        startRobot(context)
        for (l in listeners) l(true)
    }

    /**
     * Exits demo mode and leaves the connection and tabs as they were. With `reconnect = false` it only
     * saves the connection, without opening it (e.g. when cleaning up after a test, with the connection already closed).
     */
    fun exit(context: Context, reconnect: Boolean = true, replaceTabs: (List<TabSpec>) -> Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val backup = prefs.getString(KEY_BACKUP, null)?.let { JSONObject(it) } ?: return
        robot?.stop()
        robot = null
        val ros = RosConfig.fromJson(backup.optJSONObject("ros"))
        if (reconnect) RosConnection.applyConfig(ros) else RosConfig.save(ros)
        replaceTabs(TabList.fromJson(backup.optString("tabs")) ?: TabList.defaultTabs())
        prefs.edit().remove(KEY_BACKUP).commit()
        for (l in listeners) l(false)
    }

    /** When the app opens with demo mode active (e.g. after closing it), the robot starts again. */
    fun resumeIfActive(context: Context) {
        if (isActive(context) && robot == null) startRobot(context)
    }

    private fun startRobot(context: Context) {
        val app = context.applicationContext
        // The robot publishes on the app's node: wait until it is connected.
        val main = Handler(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 15_000
        fun tryStart() {
            if (!isActive(app) || robot != null) return
            if (RosConnection.status is RosConnection.Status.Connected && RosConnection.config == CONFIG) {
                robot = DemoRobot { DemoVideo(com.juancams.rviz2mini.ros.dyn.Interfaces.get(), it) }.also { it.start() }
            } else if (System.currentTimeMillis() < deadline) {
                main.postDelayed({ tryStart() }, 300)
            }
        }
        main.postDelayed({ tryStart() }, 500)
    }
}
