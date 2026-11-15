// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.ros.KnownRobots
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Known robots against the PC (`-e pc true`, `-e pcIp <IP>`): the app learns the PC's IP and, when
 * reconnecting without typed peers, uses it on its own to find it. Also opens Network check.
 */
@RunWith(AndroidJUnit4::class)
class KnownRobotsPcTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pcIp: String get() = TestArgs.pcIp
    private val savedRos = context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).all

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("pc") != "true") return
        RosConnection.stop()
        context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).edit().clear().apply {
            savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    private fun connect(cfg: RosConfig) {
        RosConnection.applyConfig(cfg)
        SystemClock.sleep(500)
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
    }

    @Test
    fun learnsTheRobotAndFindsItWithoutPeers() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        KnownRobots.setEnabled(true)
        for (r in KnownRobots.list(77)) KnownRobots.forget(77, r.ip)

        // 1. With the PC as a typed peer: its IP is learned.
        RosConnection.start()
        connect(RosConfig(domainId = 77, nodeName = "rviz2mini_known", discoveryPeers = pcIp))
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (KnownRobots.list(77).none { it.ip == pcIp } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(500)
        Log.i(TAG, "aprendidos: ${KnownRobots.list(77)}")
        assertTrue("No se aprendió $pcIp", KnownRobots.list(77).any { it.ip == pcIp })
        assertTrue("Solo IPs alcanzables: ${KnownRobots.list(77)}", KnownRobots.list(77).none { it.ip.startsWith("172.17.") })

        // 2. Without typed peers: the learned IP is used on its own and the PC appears.
        connect(RosConfig(domainId = 77, nodeName = "rviz2mini_known"))
        assertTrue("peers en uso: ${RosConnection.activePeers}", pcIp in RosConnection.activePeers)
        val until = SystemClock.uptimeMillis() + 20_000
        while (RosConnection.remoteParticipants().isEmpty() && SystemClock.uptimeMillis() < until) SystemClock.sleep(300)
        Log.i(TAG, "remotos: ${RosConnection.remoteParticipants()}")
        assertTrue(RosConnection.remoteParticipants().any { (_, ips) -> pcIp in ips })

        // 3. Network check explains it.
        ActivityScenario.launch(MainActivity::class.java).use {
            Ui.click(context.getString(R.string.main_tab_config))
            Ui.click(context.getString(R.string.config_network_check))
            // The phone may be in another language: look for the start of the text, without the numbers.
            Ui.waitFor(context.getString(R.string.config_network_ok).substringBefore("%").trim(), exact = false)
            Ui.shot(File(context.getExternalFilesDir(null), "known"), "network_check")
            Ui.click(context.getString(R.string.common_ok))
        }
    }

    companion object {
        private const val TAG = "KnownRobotsPcTest"
    }
}
