// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSharingTest {

    private val dash = TabSpec("d1", TabType.DASHBOARD, "Robot").withSetting("layout", """[{"id":"w1","x":0},{"id":"w2","x":6}]""")
    private val w1 = TabSpec("w1", TabType.PLOT, "Plot").withSetting(TabSpec.KEY_DASHBOARD, "d1").withSetting("plotLayout", "{}")
    private val w2 = TabSpec("w2", TabType.BUTTONS, "Buttons").withSetting(TabSpec.KEY_DASHBOARD, "d1")
    private val other = TabSpec("x", TabType.RVIZ, "RViz")

    @Test
    fun exportImportADashboardWithItsWidgets() {
        val json = TabSharing.export(dash, listOf(dash, w1, w2, other))
        val parsed = TabSharing.parse(json)!!
        assertEquals(listOf("d1", "w1", "w2"), parsed.map { it.id })

        val copies = TabSharing.copies(parsed, listOf(dash, w1, w2, other))
        assertEquals(3, copies.size)
        val (d, c1, c2) = copies
        assertEquals("Robot 2", d.name) // there is already a "Robot"
        assertTrue(copies.none { it.id in setOf("d1", "w1", "w2") })
        assertEquals(d.id, c1.dashboardId); assertEquals(d.id, c2.dashboardId)
        // The dashboard layout names the new widgets.
        val layout = d.settings.getValue("layout")
        assertTrue(layout, c1.id in layout && c2.id in layout && "w1" !in layout)
        assertEquals("{}", c1.settings["plotLayout"])
    }

    @Test
    fun keepsTheNameWhenFreeAndRejectsOtherFiles() {
        val copies = TabSharing.copies(TabSharing.parse(TabSharing.export(other, listOf(other)))!!, emptyList())
        assertEquals("RViz", copies.single().name)
        // A plain list of tabs works too.
        assertEquals(1, TabSharing.parse(TabList.toJson(listOf(other)))!!.size)
        assertNull(TabSharing.parse("""{"format":"something-else","tabs":[]}"""))
        assertNull(TabSharing.parse("not json"))
        assertNull(TabSharing.parse(TabList.toJson(listOf(w1)))) // widgets only
        assertFalse(TabSharing.copies(listOf(w1), emptyList()).isNotEmpty())
    }
}
