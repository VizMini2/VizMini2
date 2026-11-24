// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.e2e

import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.nav.MapExport
import com.juancams.rviz2mini.nav.MapGrid
import com.juancams.rviz2mini.nav.MapServices
import com.juancams.rviz2mini.nav.Zones
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Maps tab's calls against tools/fake_map_services.py (the services of Nav2's map_saver, SLAM Toolbox and the
 * costmaps) and the fake robot's /map: saving, pausing, clearing, and the zone masks a costmap would receive.
 */
class MapsE2ETest {
    private val events = CopyOnWriteArrayList<JSONObject>()
    private var sub: DynRos.Subscription? = null
    private val pubs = ArrayList<DynRos.Publisher>()
    private lateinit var map: MapGrid

    @Before fun connect() {
        E2E.connect("/fake_map_services/events", 30_000)
        sub = DynRos.subscribe("/fake_map_services/events", E2E.registry.msg("std_msgs/msg/String"), BagRecorder.qos(true)) { v ->
            runCatching { events += JSONObject(v["data"].toString()) }
        }
        var g: MapGrid? = null
        val s = DynRos.subscribe("/map", E2E.registry.msg("nav_msgs/msg/OccupancyGrid"), BagRecorder.qos(true)) { v -> MapGrid.of(v)?.let { g = it } }
        try { E2E.waitFor("no /map", 20_000) { g != null } } finally { s.close() }
        map = g!!
        E2E.waitFor("the map services are not in the graph", 20_000) { MapServices.find(RosConnection.graph()).slamPause.isNotEmpty() }
    }

    @After fun close() { sub?.close(); pubs.forEach { runCatching { it.close() } } }

    private fun event(kind: String, timeoutMs: Long = 10_000, ok: (JSONObject) -> Boolean = { true }): JSONObject {
        var hit: JSONObject? = null
        E2E.waitFor({ "no '$kind' event; got $events" }, timeoutMs) { hit = events.firstOrNull { it.optString("event") == kind && ok(it) }; hit != null }
        return hit!!
    }

    @Test fun findsAndCallsTheServices() {
        val f = MapServices.find(RosConnection.graph())
        assertEquals(listOf("/map_saver/save_map"), f.mapSaver)
        assertEquals(listOf("/global_costmap/clear_entirely_global_costmap", "/local_costmap/clear_entirely_local_costmap"), f.clearCostmaps)
        val s = MapServices(E2E.registry)
        assertTrue(s.saveWithMapSaver(f.mapSaver.single(), "/tmp/office"))
        assertEquals("/tmp/office", event("map_saver").getString("url"))
        assertTrue(s.slamSave(f.slamSave.single(), "office"))
        assertEquals("office", event("slam_save").getString("name"))
        assertTrue(s.slamSerialize(f.slamSerialize.single(), "office_graph"))
        event("slam_serialize") { it.getString("filename") == "office_graph" }
        assertTrue(s.slamTogglePause(f.slamPause.single()))
        event("slam_pause") { it.getBoolean("paused") }
        assertTrue(s.slamTogglePause(f.slamPause.single()))
        event("slam_pause") { !it.getBoolean("paused") }
        for (c in f.clearCostmaps) s.clearCostmap(c)
        event("clear") { it.getString("costmap") == "local_costmap" }
        event("clear") { it.getString("costmap") == "global_costmap" }
    }

    @Test fun zoneMasksReachTheCostmap() {
        // A square of 1 m in the middle of the map, as keepout, and the filter info pointing at its mask.
        val (cx, cy) = map.cellCenter(map.width / 2, map.height / 2)
        val square = listOf(cx - 0.5 to cy - 0.5, cx + 0.5 to cy - 0.5, cx + 0.5 to cy + 0.5, cx - 0.5 to cy + 0.5)
        val zones = listOf(Zones.Zone(Zones.Kind.KEEPOUT, square, 100), Zones.Zone(Zones.Kind.SPEED, square, 30))
        val spec = E2E.registry.msg("nav_msgs/msg/OccupancyGrid")
        val ispec = E2E.registry.msg("nav2_msgs/msg/CostmapFilterInfo")
        val mask = DynRos.publisher("/keepout_filter_mask", spec, BagRecorder.qos(true)).also { pubs += it }
        val info = DynRos.publisher("/costmap_filter_info", ispec, BagRecorder.qos(true)).also { pubs += it }
        val speed = DynRos.publisher("/speed_filter_mask", spec, BagRecorder.qos(true)).also { pubs += it }
        val expected = Math.round(1.0 / map.resolution).toInt().let { it * it }
        mask.publish(DynCodec.fromJson(spec, Zones.maskMessage(map, Zones.mask(map, zones, Zones.Kind.KEEPOUT), 0)))
        info.publish(DynCodec.fromJson(ispec, Zones.filterInfo(Zones.Kind.KEEPOUT, "/keepout_filter_mask", map.frameId)))
        speed.publish(DynCodec.fromJson(spec, Zones.maskMessage(map, Zones.mask(map, zones, Zones.Kind.SPEED), 0)))
        val m = event("mask") { it.getString("topic") == "/keepout_filter_mask" }
        assertEquals(map.width, m.getInt("width")); assertEquals(map.height, m.getInt("height"))
        assertEquals(expected.toDouble(), m.getInt("cells").toDouble(), expected * 0.15)
        assertEquals(100, m.getJSONArray("values").getInt(0))
        assertEquals(30, event("mask") { it.getString("topic") == "/speed_filter_mask" }.getJSONArray("values").getInt(0))
        val i = event("info")
        assertEquals(0, i.getInt("type")); assertEquals("/keepout_filter_mask", i.getString("mask"))
        // The download is the same map map_saver would write.
        val files = MapExport.files("office", map)
        assertEquals(map.width * map.height, files[0].second.size - "P5\n# VizMini2\n${map.width} ${map.height}\n255\n".length)
    }
}
