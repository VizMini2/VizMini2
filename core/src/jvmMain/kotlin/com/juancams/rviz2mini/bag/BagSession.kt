// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.bag

import com.juancams.rviz2mini.platform.Platform
import java.io.File

/**
 * Recording and playback in progress, shared by all Bags tabs: they continue even if
 * the tab changes or the view is recreated. Main thread only.
 */
object BagSession {
    var recorder: BagRecorder? = null
    var player: BagPlayer? = null

    /** App data folder (Android: the app's external files; PC: [com.juancams.rviz2mini.platform.Platform.dataDir]). */
    @Volatile var baseDir: () -> File = { com.juancams.rviz2mini.platform.Platform.dataDir }

    /**
     * Isolated connection to play a bag only on this machine: another domain and only the local
     * interface, like the demo. All tabs see it and nothing goes out to the network.
     */
    val LOCAL_CONFIG = com.juancams.rviz2mini.ros.RosConfig(domainId = 232, nodeName = "vizmini2_bag", interfaceWhitelist = "127.0.0.1")
    private const val PREFS = "rviz2mini_bag_local"
    private const val KEY_BACKUP = "backup"
    private val prefs get() = Platform.prefs(PREFS)

    /** Switches to the isolated connection, saving the user's one (which [leaveLocal] restores). */
    fun enterLocal() {
        if (prefs.contains(KEY_BACKUP)) return
        val saved = com.juancams.rviz2mini.ros.RosConfig.load()
        prefs.edit().putString(KEY_BACKUP, saved.toJson().toString()).commit()
        com.juancams.rviz2mini.ros.RosConnection.applyConfig(LOCAL_CONFIG)
    }

    /** Returns to the user's connection if it had switched to the isolated one. */
    fun leaveLocal() {
        com.juancams.rviz2mini.ros.RosConnection.setBagPlayback(false)
        val json = prefs.getString(KEY_BACKUP, null) ?: return
        val saved = try { com.juancams.rviz2mini.ros.RosConfig.fromJson(org.json.JSONObject(json)) } catch (_: Exception) { com.juancams.rviz2mini.ros.RosConfig() }
        prefs.edit().remove(KEY_BACKUP).commit()
        com.juancams.rviz2mini.ros.RosConnection.applyConfig(saved)
    }

    /** Stops the playback in progress (from any tab or the time bar). */
    fun stopPlayer() {
        val p = player ?: return
        player = null
        Thread({ p.close(); Platform.main.post { leaveLocal() } }, "bag-stop").start()
    }

    fun isLocal() = prefs.contains(KEY_BACKUP)

    /** On app start: if it was closed while playing locally, the user's connection is restored. */
    fun restoreIfStale() {
        if (player == null && isLocal()) leaveLocal()
    }

    fun dir(): File = File(baseDir(), "bags").apply { mkdirs() }

    fun files(): List<File> =
        dir().listFiles { f -> f.isFile && f.name.endsWith(".mcap") }.orEmpty().sortedByDescending { it.lastModified() }

    /** `name` in `dir` without overwriting any: "a.mcap", "a_2.mcap", "a_3.mcap"... */
    fun freeFile(dir: File, name: String): File {
        val base = name.removeSuffix(".mcap")
        var f = File(dir, "$base.mcap")
        var n = 2
        while (f.exists()) f = File(dir, "${base}_${n++}.mcap")
        return f
    }
}
