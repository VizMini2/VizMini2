// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui

import com.juancams.rviz2mini.dashboard.DashboardGrid
import com.juancams.rviz2mini.dashboard.TopicDrop
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.DASHBOARD_KEY_LAYOUT
import com.juancams.rviz2mini.ui.screens.DashboardController
import com.juancams.rviz2mini.ui.screens.DashboardTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Dashboard templates, the dashboard controller (add, move, undo, remove) and what a dropped topic becomes. */
class DashboardLogicTest {
    @Before fun setup() = UiTestSetup.install()

    @Test fun everyTemplateIsConsistent() {
        for (t in DashboardTemplates.Template.entries) {
            val tabs = DashboardTemplates.build(t)
            val dash = tabs.first()
            assertEquals(TabType.DASHBOARD, dash.type)
            val widgets = tabs.drop(1)
            assertTrue("$t has no widgets", widgets.isNotEmpty())
            assertTrue("$t: every widget belongs to its dashboard", widgets.all { it.dashboardId == dash.id })
            val layout = DashboardGrid.fromJson(dash.settings[DASHBOARD_KEY_LAYOUT])
            assertEquals("$t: one layout item per widget", widgets.map { it.id }.toSet(), layout.map { it.id }.toSet())
            assertTrue("$t: items inside the board", layout.all { it.x >= 0 && it.y >= 0 && it.w > 0 && it.h > 0 })
            // Every RViz widget has valid displays.
            for (r in widgets.filter { it.type == TabType.RVIZ }) assertNotNull("$t: displays of ${r.name}", DisplayList.fromJson(r.settings["displays"]))
        }
        val manipulation = DashboardTemplates.build(DashboardTemplates.Template.MANIPULATION)
        assertTrue(manipulation.any { it.type == TabType.MOVEIT })
        val displays = DisplayList.fromJson(manipulation.first { it.type == TabType.RVIZ }.settings["displays"])!!.map { it.type }
        assertTrue(displays.containsAll(listOf(DisplayType.ROBOT_MODEL, DisplayType.TRAJECTORY, DisplayType.PLANNING_SCENE)))
    }

    @Test fun droppedTopicBecomesTheRightWidget() {
        assertEquals(TabType.CAMERA, DashboardTemplates.widgetFor(TopicDrop.Payload("/camera/image_raw", "sensor_msgs/msg/Image"))?.type)
        assertEquals(TabType.RVIZ, DashboardTemplates.widgetFor(TopicDrop.Payload("/scan", "sensor_msgs/msg/LaserScan"))?.type)
        assertEquals(TabType.DIAGNOSTICS, DashboardTemplates.widgetFor(TopicDrop.Payload("/diagnostics", "diagnostic_msgs/msg/DiagnosticArray"))?.type)
        assertEquals(TabType.PLOT, DashboardTemplates.widgetFor(TopicDrop.Payload("/battery", "sensor_msgs/msg/BatteryState"))?.type)
        // A field always goes to a plot, whatever the type.
        assertEquals(TabType.PLOT, DashboardTemplates.widgetFor(TopicDrop.Payload("/odom", "nav_msgs/msg/Odometry", "twist.twist.linear.x"))?.type)
        // Nothing to plot or show.
        assertNull(DashboardTemplates.widgetFor(TopicDrop.Payload("/chatter", "std_msgs/msg/String")))
    }

    @Test fun topicDropRegistry() {
        val h = TopicDrop.Handler { p -> "got ${p.topic}" }
        TopicDrop.register("tab1", h)
        assertTrue(TopicDrop.accepts("tab1"))
        assertEquals("got /x", TopicDrop.deliver("tab1", TopicDrop.Payload("/x", "std_msgs/msg/Float64")))
        TopicDrop.unregister("tab1", TopicDrop.Handler { null }) // another handler does not remove it
        assertTrue(TopicDrop.accepts("tab1"))
        TopicDrop.unregister("tab1", h)
        assertNull(TopicDrop.deliver("tab1", TopicDrop.Payload("/x", "std_msgs/msg/Float64")))
    }

    @Test fun controllerAddMoveUndoRemove() {
        val store = TabStore.get()
        val dash = TabSpec.create(TabType.DASHBOARD, "Test")
        store.add(dash)
        val c = DashboardController(dash.id)
        c.add(TabSpec.create(TabType.TELEOP, "Joystick"))
        c.addAt(TabSpec.create(TabType.PLOT, "Plot"), 4.5, 2.0)
        assertEquals(2, c.items.size)
        val plot = c.items.first { store.get(it.id)?.type == TabType.PLOT }
        assertEquals(4.5, plot.x, 1e-9); assertEquals(2.0, plot.y, 1e-9)
        c.move(plot.id, 1.0, 1.0, plot.w, plot.h)
        assertEquals(1.0, c.items.first { it.id == plot.id }.x, 1e-9)
        c.undo()
        assertEquals(4.5, c.items.first { it.id == plot.id }.x, 1e-9)
        c.remove(plot.id)
        assertEquals(1, c.items.size)
        assertNull(store.get(plot.id))
        c.undo() // brings the removed widget back
        assertEquals(2, c.items.size)
        assertNotNull(store.get(plot.id))
        // The layout is saved with the dashboard.
        assertEquals(c.items.map { it.id }.toSet(), DashboardGrid.fromJson(store.get(dash.id)!!.settings[DASHBOARD_KEY_LAYOUT]).map { it.id }.toSet())
    }
}
