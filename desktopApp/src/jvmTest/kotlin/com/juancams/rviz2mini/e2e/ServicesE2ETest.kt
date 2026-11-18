// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.e2e

import com.juancams.rviz2mini.ros.dyn.DynServiceServer
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * A ROS 2 client (`ros2 service call`, rclpy over the distro's rmw) calling a service served by this app
 * (DynServiceServer): it only takes the reply if it carries its request's identity, as rmw does.
 */
class ServicesE2ETest {
    @Test fun aRos2ClientGetsTheReplyOfThisAppsServer() {
        // This app's servers speak rmw_fastrtps' request/reply format: not a Cyclone DDS client's, and not over Zenoh.
        val rmw = System.getenv("RMW_IMPLEMENTATION").orEmpty()
        assumeTrue("servers of this app are for rmw_fastrtps clients (RMW_IMPLEMENTATION=$rmw)", rmw.isEmpty() || rmw.contains("fastrtps"))
        E2E.connect("/tf")
        assumeTrue("ros2 CLI not in the PATH", runCatching { ProcessBuilder("ros2", "--help").start().waitFor(30, TimeUnit.SECONDS) }.getOrDefault(false))
        val srv = Interfaces.get().srv("example_interfaces/srv/AddTwoInts")
        val server = DynServiceServer("/vizmini2_e2e/add", srv) { req, reply ->
            reply(MsgValue().apply { put("sum", (req["a"] as Long) + (req["b"] as Long)) })
        }
        try {
            val p = ProcessBuilder("ros2", "service", "call", "/vizmini2_e2e/add", "example_interfaces/srv/AddTwoInts", "{a: 2, b: 3}")
                .redirectErrorStream(true).apply { environment()["ROS_DOMAIN_ID"] = E2E.domain.toString() }.start()
            val finished = p.waitFor(60, TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("ros2 service call did not get the reply:\n$out", finished && "sum=5" in out)
        } finally {
            server.close()
        }
    }
}
