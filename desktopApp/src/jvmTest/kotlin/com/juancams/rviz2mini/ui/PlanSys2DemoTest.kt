// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.juancams.rviz2mini.demo.DemoRobot
import com.juancams.rviz2mini.plansys2.ExecutionEvent
import com.juancams.rviz2mini.plansys2.ExecutionInfo
import com.juancams.rviz2mini.plansys2.PlanSys2Monitor
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.theme.VizTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo's PlanSys2 over DDS (the demo's own network, on this machine): what it publishes reaches the monitor in its
 * QoS and shape, and the PlanSys2 tab shows the plan running.
 */
@OptIn(ExperimentalTestApi::class)
class PlanSys2DemoTest {

    private fun waitFor(what: () -> String, timeoutMs: Long = 25_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(100)
        if (!ok()) throw AssertionError(what())
    }

    @Test fun theDemosPlanReachesTheMonitorAndTheTab() {
        UiTestSetup.install()
        RosConnection.start()
        RosConnection.applyConfig(DemoRobot.NETWORK)
        waitFor({ "not connected: ${RosConnection.status}" }) { RosConnection.status is RosConnection.Status.Connected }
        val robot = DemoRobot().also { it.start() }
        val monitor = PlanSys2Monitor("")
        try {
            monitor.start()
            waitFor({ "monitor: ${monitor.state}" }) {
                val s = monitor.state
                s.plan?.items?.size == 6 && s.knowledge?.instances?.contains("demo_bot") == true && s.performers.size == 3 &&
                    s.rows.any { it.info?.status == ExecutionInfo.EXECUTING } && s.trace.any { it.type == ExecutionEvent.START } &&
                    s.active.isNotEmpty()
            }
            val s = monitor.state
            assertTrue(s.plan!!.items.first().action, s.plan!!.items.first().action == "(move demo_bot dock kitchen)")
            assertTrue(s.knowledge!!.goal, s.knowledge!!.goal.contains("(cleaned kitchen)"))
            assertTrue(s.knowledge!!.metric, s.knowledge!!.metric == "(minimize (total-time))")

            val spec = TabSpec.create(TabType.PLANSYS2, "PlanSys2").also { TabStore.get().add(it) }
            try {
                runComposeUiTest {
                    setContent { VizTheme { TabScreen(spec) } }
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("(move demo_bot dock kitchen)", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("demo_bot", substring = true).fetchSemanticsNodes().isNotEmpty() }
                }
            } finally { TabStore.get().remove(spec.id) }
        } finally {
            monitor.close()
            robot.stop()
            RosConnection.stop()
        }
    }
}
