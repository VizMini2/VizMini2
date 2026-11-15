// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bag

import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.Interfaces
import java.io.File
import java.util.Locale

/**
 * Going back without a bag: what a [LiveBuffer] holds is written as MCAP and played back only on this device (like
 * a local bag: isolated connection and messages delivered directly to the tabs), in a loop, with the usual time bar
 * (pause, speed, seek). Closing it returns to the robot. `isDemo`: in the demo it is already local.
 */
object InstantReplay {
    const val FILE_NAME = "instant_replay.mcap"

    fun start(buffer: LiveBuffer, isDemo: Boolean = false, onMessage: (String) -> Unit) {
        if (BagSession.player != null) return
        val span = buffer.spanS()
        if (span < 0.5) { onMessage(tr("rewind_empty")); return }
        val file = File(File(BagSession.baseDir(), "cache"), FILE_NAME)
        Thread({
            val ok = runCatching { buffer.dump(file) }.getOrDefault(false)
            if (!ok) { Platform.main.post { onMessage(tr("rewind_empty")) }; return@Thread }
            val local = !isDemo
            if (local) {
                val entered = java.util.concurrent.CountDownLatch(1)
                Platform.main.post { BagSession.enterLocal(); entered.countDown() }
                entered.await()
                val until = System.currentTimeMillis() + 10_000
                while ((RosConnection.status !is RosConnection.Status.Connected || RosConnection.config != BagSession.LOCAL_CONFIG) && System.currentTimeMillis() < until) Thread.sleep(100)
            }
            val player = runCatching {
                BagPlayer(file, Interfaces.get(), object : BagPlayer.Listener {
                    override fun onProgress(positionNs: Long, durationNs: Long) {}
                    override fun onFinished(error: String?) {
                        Platform.main.post { BagSession.player?.close(); BagSession.player = null; BagSession.leaveLocal() }
                    }
                }, direct = true)
            }
            Platform.main.post {
                val p = player.getOrNull()
                if (p == null || RosConnection.status !is RosConnection.Status.Connected) {
                    p?.close(); if (local) BagSession.leaveLocal()
                    onMessage(player.exceptionOrNull()?.message ?: tr("bags_not_connected")); return@post
                }
                BagSession.player = p
                RosConnection.setBagPlayback(true)
                p.loop = true
                p.start()
                onMessage(tr("rewind_started", String.format(Locale.ROOT, "%.0f", span)))
            }
        }, "instant-replay").apply { isDaemon = true }.start()
    }
}
