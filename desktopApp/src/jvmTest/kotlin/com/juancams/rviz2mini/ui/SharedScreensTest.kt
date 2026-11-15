// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.theme.VizTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every shared screen (the same code as on the phone) draws without ROS and without crashing, as a tab and as a
 * dashboard widget; and a few of them show what they should when nothing is connected.
 */
@OptIn(ExperimentalTestApi::class)
class SharedScreensTest {
    @Before fun setup() = UiTestSetup.install()

    @Test fun everySharedTabDraws() {
        val failures = ArrayList<String>()
        for (type in SHARED_TAB_TYPES) for (widget in listOf(false, true)) {
            val dash = TabSpec.create(TabType.DASHBOARD, "D")
            val spec = TabSpec.create(type, type.label()).let { if (widget) it.withSetting(TabSpec.KEY_DASHBOARD, dash.id) else it }
            TabStore.get().add(spec)
            try {
                runComposeUiTest {
                    setContent { VizTheme(dark = true) { TabScreen(spec) } }
                    waitForIdle()
                }
            } catch (e: Throwable) {
                failures += "$type${if (widget) " (widget)" else ""}: $e"
            } finally {
                TabStore.get().remove(spec.id)
            }
        }
        assertTrue("screens that failed to draw:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun moveItWaitsForMoveIt() = runComposeUiTest {
        val spec = TabSpec.create(TabType.MOVEIT, "MoveIt").also { TabStore.get().add(it) }
        setContent { VizTheme { TabScreen(spec) } }
        waitForIdle()
        // Without MoveIt it says so; in the end-to-end run (MoveIt on the network) it shows the robot instead.
        val waiting = onAllNodesWithText(tr("moveit_waiting"), substring = true).fetchSemanticsNodes().isNotEmpty()
        val robot = onAllNodesWithText("panda").fetchSemanticsNodes().isNotEmpty()
        assertTrue("neither waiting for MoveIt nor showing the robot", waiting || robot)
    }

    @Test fun nav2BarHint() = runComposeUiTest {
        val m = com.juancams.rviz2mini.nav.Nav2Mission(com.juancams.rviz2mini.ros.dyn.Interfaces.get())
        setContent { VizTheme { com.juancams.rviz2mini.rviz.ui.Nav2Bar(m, 0) } }
        waitForIdle()
        assertTrue(onAllNodesWithText(tr("rviz_tool_waypoints_hint")).fetchSemanticsNodes().isNotEmpty())
        m.add(com.juancams.rviz2mini.nav.Nav2Mission.Waypoint(1.0, 2.0, 0.0), "map")
        setContent { VizTheme { com.juancams.rviz2mini.rviz.ui.Nav2Bar(m, 1) } }
        waitForIdle()
        assertTrue(onAllNodesWithText(tr("nav2_go")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText(tr("nav2_count", 1)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun alertsTabCreatesARule() = runComposeUiTest {
        val saved = com.juancams.rviz2mini.alerts.TopicRules.load()
        com.juancams.rviz2mini.alerts.TopicRules.save(emptyList())
        try {
            val spec = TabSpec.create(TabType.ALERTS, "Alerts").also { TabStore.get().add(it) }
            setContent { VizTheme { TabScreen(spec) } }
            waitForIdle()
            assertTrue(onAllNodesWithText(tr("alerts_no_rules")).fetchSemanticsNodes().isNotEmpty())
            onNodeWithText(tr("alerts_add_rule")).performClick()
            waitForIdle()
            onNodeWithText(tr("alerts_topic")).performTextInput("/battery_state")
            onNodeWithText(tr("alerts_field"), substring = true).performTextInput("percentage")
            onNodeWithText(tr("alerts_threshold")).performTextReplacement("0.2")
            onNodeWithText(tr("rviz_action_save")).performClick()
            waitForIdle()
            val r = com.juancams.rviz2mini.alerts.TopicRules.load().single()
            assertEquals("/battery_state", r.topic); assertEquals("percentage", r.field); assertEquals(0.2, r.threshold, 0.0)
            val texts = onAllNodes(androidx.compose.ui.test.hasText("percentage", substring = true)).fetchSemanticsNodes()
                .flatMap { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { t -> t.text } }
            assertTrue("rule not listed: $texts", texts.any { "/battery_state" in it && "percentage < 0.2" in it })
        } finally { com.juancams.rviz2mini.alerts.TopicRules.save(saved) }
    }

    @Test fun mapsAndControllersWithoutRos() = runComposeUiTest {
        val maps = TabSpec.create(TabType.MAPS, "Maps").also { TabStore.get().add(it) }
        setContent { VizTheme { TabScreen(maps) } }
        waitForIdle()
        // In the end-to-end run the fake robot's map arrives instead.
        assertTrue(onAllNodesWithText(tr("maps_waiting", "/map")).fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText(tr("maps_draw_keepout")).fetchSemanticsNodes().isNotEmpty())
        val ctl = TabSpec.create(TabType.CONTROLLERS, "Controllers").also { TabStore.get().add(it) }
        setContent { VizTheme { TabScreen(ctl) } }
        waitForIdle()
        assertTrue(onAllNodesWithText(tr("controllers_manager", "/controller_manager")).fetchSemanticsNodes().isNotEmpty())
    }
}
