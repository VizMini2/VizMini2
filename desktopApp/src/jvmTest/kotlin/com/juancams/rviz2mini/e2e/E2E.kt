// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.e2e

import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import org.junit.Assume.assumeTrue

/**
 * End-to-end tests against real ROS 2 servers on this machine (they are skipped unless `VIZMINI2_E2E=1`).
 * `tools/e2e/run.sh` starts the servers (tools/fake_robot.py, tools/nav2_waypoints_server.py and MoveIt with the Panda),
 * runs `./gradlew :desktopApp:jvmTest` and stops them; each test class skips itself when its server is not there.
 * The app settings go to a temporary folder, never to the user's.
 */
object E2E {
    val enabled: Boolean get() = System.getenv("VIZMINI2_E2E") == "1"
    val domain: Int get() = System.getenv("VIZMINI2_E2E_DOMAIN")?.toIntOrNull() ?: 77

    @Volatile private var started = false

    /** Connects once (domain [domain]) and waits until the graph shows `topic`; skips the test if it never does. */
    @Synchronized fun connect(topic: String, timeoutMs: Long = 30_000) {
        assumeTrue("end-to-end tests: set VIZMINI2_E2E=1 (see tools/e2e/run.sh)", enabled)
        if (!started) {
            val dir = java.nio.file.Files.createTempDirectory("vizmini2-e2e").toFile().apply { deleteOnExit() }
            System.setProperty("vizmini2.config", dir.absolutePath)
            com.juancams.rviz2mini.desktop.DesktopPlatform.install()
            RosConnection.start()
            RosConnection.applyConfig(RosConfig(domainId = domain, nodeName = "vizmini2_e2e"))
            started = true
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (RosConnection.status is RosConnection.Status.Connected && RosConnection.graph()?.topics(includeHidden = true)?.any { it.name == topic } == true) return
            Thread.sleep(200)
        }
        assumeTrue("$topic is not on domain $domain (is its server running?)", false)
    }

    /** Waits for `ok` (polling) or fails with `what`. */
    fun waitFor(what: String, timeoutMs: Long = 20_000, ok: () -> Boolean) = waitFor({ what }, timeoutMs, ok)

    /** Like [waitFor], with the failure message built at the end (so it shows the final state). */
    fun waitFor(what: () -> String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(100)
        if (!ok()) throw AssertionError(what())
    }

    val registry get() = com.juancams.rviz2mini.ros.dyn.Interfaces.get()
}
