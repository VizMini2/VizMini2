// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.e2e

import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.rviz.display.NavMapModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The NavMap display's reading against tools/navmap_demo.py (EasyNavigation's navmap_ros_interfaces, built by rclpy):
 * the building with two floors, its links and layers on /navmap, and the sparse updates of its "cost" layer on
 * /navmap_layers (a person walking), applied to the map as the display does.
 */
class NavMapE2ETest {
    @Test
    fun mapAndSparseLayerUpdates() {
        E2E.connect("/navmap", 30_000)
        var map: NavMapModel.NavMap? = null
        val updates = CopyOnWriteArrayList<NavMapModel.LayerUpdate>()
        val subs = listOf(
            DynRos.subscribe("/navmap", E2E.registry.msg(NavMapModel.MAP_TYPE), BagRecorder.qos(true)) { v -> map = NavMapModel.parseMap(v) },
            DynRos.subscribe("/navmap_layers", E2E.registry.msg(NavMapModel.LAYER_TYPE), BagRecorder.qos(false)) { v -> updates += NavMapModel.parseLayerUpdate(v) }
        )
        try {
            E2E.waitFor("no NavMap on /navmap", 20_000) { map != null }
            val m = map!!
            assertEquals("map", m.frame)
            assertEquals(3, m.surfaces)
            assertTrue("${m.cells} cells", m.cells > 3000)
            assertEquals(listOf("cost", "elevation", "occupancy"), m.layers.keys.toList())
            // The first floor is 3 m up; the links are the elevator and the door.
            assertEquals(3.0, m.layers.getValue("elevation").values.max(), 1e-3)
            assertEquals(setOf(3, 4), m.links.map { it.type }.toSet())
            for (l in m.links) assertTrue(l.from in 0 until m.cells && l.to in 0 until m.cells)

            E2E.waitFor({ "only ${updates.size} layer updates" }, 10_000) { updates.size >= 5 }
            val before = m.layers.getValue("cost").values.copyOf()
            for (u in updates.toList()) {
                assertEquals("cost", u.layer.name)
                assertTrue("sparse", u.indices.isNotEmpty())
                assertNull(NavMapModel.apply(m, u))
            }
            assertTrue("the person's cost changed some cells", !before.contentEquals(m.layers.getValue("cost").values))
        } finally { subs.forEach { it.close() } }
    }
}
