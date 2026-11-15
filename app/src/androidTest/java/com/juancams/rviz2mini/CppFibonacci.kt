// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.os.SystemClock
import com.juancams.rviz2mini.ros.RosConnection
import org.junit.Assert.assertNotNull

/**
 * The C++ action server (`ros2 run action_tutorials_cpp fibonacci_action_server`) uses
 * `action_tutorials_interfaces/action/Fibonacci` up to Jazzy and `example_interfaces/action/Fibonacci` since
 * Kilted (Rolling, Lyrical). With another type DDS does not match, and the app does not allow using a topic with two types at
 * once: the type the server announces is read from the graph.
 */
object CppFibonacci {
    const val ACTION = "/rviz2mini_test/cpp/fibonacci"
    private const val GOAL_TOPIC = "rq/rviz2mini_test/cpp/fibonacci/_action/send_goalRequest"

    /** ROS type of the action ("package/action/Fibonacci"); fails the test if the server does not show up. */
    fun type(timeoutMs: Long = 20_000): String {
        var dds: String? = null
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (dds == null && SystemClock.uptimeMillis() < deadline) {
            dds = RosConnection.graph()?.endpoints?.firstOrNull { !it.isWriter && it.ddsTopic == GOAL_TOPIC }?.ddsType
            if (dds == null) SystemClock.sleep(200)
        }
        assertNotNull("No se ve el servidor C++ de Fibonacci en $ACTION", dds)
        return if (dds!!.startsWith("example_interfaces::")) "example_interfaces/action/Fibonacci"
        else "action_tutorials_interfaces/action/Fibonacci"
    }
}
