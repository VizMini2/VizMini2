// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.DynServiceServer
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.ui.UiTestSetup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Services over real Fast DDS (the desktop natives), on loopback and a domain of their own: each response reaches the
 * call it answers, also when the server answers out of order (as the demo's action get_result does, or any server that
 * answers from another thread). The server writes the request's identity as the response's "related sample identity",
 * as rmw does; without it the client could only guess.
 */
class ServicePairingTest {
    private var previous: RosConfig? = null

    @Before fun connect() {
        UiTestSetup.install()
        previous = if (RosConnection.status is RosConnection.Status.Connected) RosConnection.config else null
        RosConnection.applyConfig(RosConfig(domainId = 197, nodeName = "vizmini2_pairing_test", interfaceWhitelist = "127.0.0.1"))
        val end = System.currentTimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    // Back to whatever another test class had (the end-to-end ones connect once and expect to stay on their domain).
    @After fun restore() {
        previous?.let { RosConnection.applyConfig(it) }
    }

    private fun request(a: Long, b: Long) = MsgValue().apply { put("a", a); put("b", b) }

    @Test fun responsesOutOfOrderReachTheirOwnCalls() {
        val srv = Interfaces.get().srv("example_interfaces/srv/AddTwoInts")
        // The first request (a = 1) is answered after the second one.
        val server = DynServiceServer("/pairing_test/add", srv) { req, reply ->
            val a = req["a"] as Long
            val b = req["b"] as Long
            thread { Thread.sleep(if (a == 1L) 800 else 0); reply(MsgValue().apply { put("sum", a + b) }) }
        }
        val client = DynServiceClient("/pairing_test/add", srv)
        try {
            // A first call to warm up the discovery (its response comes in order).
            assertEquals(0L, client.call(request(0, 0), 15_000)["sum"])
            val first = CompletableFuture.supplyAsync { client.call(request(1, 10), 10_000)["sum"] }
            Thread.sleep(200)
            val second = CompletableFuture.supplyAsync { client.call(request(2, 20), 10_000)["sum"] }
            assertEquals("the second call gets its own response", 22L, second.get(15, TimeUnit.SECONDS))
            assertEquals("the first call gets its own response", 11L, first.get(15, TimeUnit.SECONDS))
        } finally {
            client.close()
            server.close()
        }
    }
}
