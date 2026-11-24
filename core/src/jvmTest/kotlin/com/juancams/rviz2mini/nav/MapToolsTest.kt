// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.nav

import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Maps: reading the grid, exporting it as map_saver does, and the zone masks for Nav2's filters. */
class MapToolsTest {
    private val registry: InterfaceRegistry by lazy {
        InterfaceRegistry().apply { loadBundle(com.juancams.rviz2mini.testing.TestAssets.text("ros_interfaces.txt")) }
    }

    /** 4x3 at 0.5 m from (-1, -1): row 0 free, row 1 occupied in the middle, row 2 unknown. */
    private fun grid(): MapGrid {
        val data = byteArrayOf(0, 0, 0, 0, 0, 100, 100, 0, -1, -1, -1, -1)
        return MapGrid(4, 3, 0.5, -1.0, -1.0, 0.0, "map", data)
    }

    @Test fun readsTheOccupancyGridMessage() {
        val spec = registry.msg("nav_msgs/msg/OccupancyGrid")
        val v = DynCodec.fromJson(spec, Zones.maskMessage(grid(), grid().data, 5_000_000_123L))
        val g = MapGrid.of(v)!!
        assertEquals(4, g.width); assertEquals(3, g.height); assertEquals(0.5, g.resolution, 1e-9)
        assertEquals(-1.0, g.originX, 1e-9); assertEquals("map", g.frameId)
        assertEquals(100, g.data[5].toInt()); assertEquals(-1, g.data[11].toInt())
        assertEquals(5L, ((v["header"] as Map<*, *>)["stamp"] as Map<*, *>)["sec"])
    }

    @Test fun pgmLikeMapSaver() {
        val pgm = MapExport.pgm(grid())
        val head = "P5\n# VizMini2\n4 3\n255\n"
        assertEquals(head, String(pgm, 0, head.length, Charsets.US_ASCII))
        val px = pgm.copyOfRange(head.length, pgm.size).map { it.toInt() and 0xFF }
        // Top row of the image = the map's last row (unknown), then occupied in the middle, then free.
        assertEquals(listOf(205, 205, 205, 205, 254, 0, 0, 254, 254, 254, 254, 254), px)
        val yaml = MapExport.yaml("office.pgm", grid())
        assertTrue(yaml, "image: office.pgm" in yaml && "resolution: 0.500000" in yaml && "origin: [-1.00000, -1.00000, 0.00000]" in yaml && "mode: trinary" in yaml)
        assertEquals(listOf("office.pgm", "office.yaml"), MapExport.files("office", grid()).map { it.first })
    }

    @Test fun zonesBecomeMasks() {
        val g = MapGrid(10, 10, 0.1, 0.0, 0.0, 0.0, "map", ByteArray(100))
        // A square over cells 2..4 x 2..4 (centres at 0.25..0.45).
        val square = listOf(0.2 to 0.2, 0.5 to 0.2, 0.5 to 0.5, 0.2 to 0.5)
        val zones = listOf(Zones.Zone(Zones.Kind.KEEPOUT, square, 100), Zones.Zone(Zones.Kind.SPEED, listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 0.15), 40))
        val keep = Zones.mask(g, zones, Zones.Kind.KEEPOUT)
        assertEquals(9, keep.count { it.toInt() == 100 })
        assertEquals(100, keep[3 * 10 + 3].toInt()); assertEquals(0, keep[1 * 10 + 1].toInt())
        val speed = Zones.mask(g, zones, Zones.Kind.SPEED)
        assertTrue(speed.all { it.toInt() == 0 || it.toInt() == 40 } && speed.any { it.toInt() == 40 })
        assertFalse(Zones.inside(0.1, 0.1, square)); assertTrue(Zones.inside(0.3, 0.3, square))
        // Speed masks are saved as "scale" images: 40 % -> 255 - 102.
        val pgm = MapExport.scalePgm(g.width, g.height, speed)
        assertTrue((pgm.copyOfRange(pgm.size - 100, pgm.size).map { it.toInt() and 0xFF }.toSet()) == setOf(255, 153))
    }

    @Test fun rotatedMapsUseTheirOrigin() {
        val g = MapGrid(10, 10, 0.1, 1.0, 2.0, Math.PI / 2, "map", ByteArray(100))
        val (x, y) = g.cellCenter(3, 0)
        // Local (0.35, 0.05) turned 90°: (-0.05, 0.35) from the origin.
        assertEquals(0.95, x, 1e-9); assertEquals(2.35, y, 1e-9)
        assertEquals(3 to 0, g.cellOf(x, y))
    }

    @Test fun zonesSurviveTheSettings() {
        val z = listOf(Zones.Zone(Zones.Kind.SPEED, listOf(0.0 to 0.0, 1.5 to 0.0, 1.5 to -2.25), 30))
        assertEquals(z, Zones.parse(Zones.format(z)))
        assertTrue(Zones.parse("rubbish\nKEEPOUT 100 1,1;2,2").isEmpty())
    }

    @Test fun filterInfoAndServiceRequests() {
        val info = DynCodec.fromJson(registry.msg("nav2_msgs/msg/CostmapFilterInfo"), Zones.filterInfo(Zones.Kind.SPEED, "/speed_filter_mask", "map"), strict = true)
        assertEquals(1L, (info["type"] as Number).toLong()); assertEquals("/speed_filter_mask", info["filter_mask_topic"])
        for (t in listOf("nav2_msgs/srv/SaveMap", "slam_toolbox/srv/SaveMap", "slam_toolbox/srv/SerializePoseGraph", "slam_toolbox/srv/Pause", "nav2_msgs/srv/ClearEntireCostmap"))
            assertNotNull(t, registry.srv(t))
        assertEquals(listOf<String>(), MapServices.find(null).mapSaver)
    }
}
