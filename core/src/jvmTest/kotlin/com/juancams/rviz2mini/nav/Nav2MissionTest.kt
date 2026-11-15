// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.nav

import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class Nav2MissionTest {
    private val registry = InterfaceRegistry().apply {
        loadBundle(listOf("../app/src/main/assets/ros_interfaces.txt", "app/src/main/assets/ros_interfaces.txt").map(::File).first { it.isFile }.readText())
    }

    @Test fun goalForBothActionsAndSaveLoad() {
        val m = Nav2Mission(registry)
        m.add(Nav2Mission.Waypoint(1.0, 2.0, Math.PI / 2), "map")
        m.add(Nav2Mission.Waypoint(-3.0, 0.5, 0.0), "odom") // the frame is that of the first one
        m.loops = 2
        val follow = DynCodec.fromJson(registry.action(Nav2Mission.Mode.FOLLOW.type).goal, m.goalJson())
        assertEquals(2L, (follow["number_of_loops"] as Number).toLong())
        val poses = follow["poses"] as List<*>
        assertEquals(2, poses.size)
        val p0 = poses[0] as MsgValue
        assertEquals("map", (p0["header"] as MsgValue)["frame_id"])
        val pose = p0["pose"] as MsgValue
        assertEquals(1.0, ((pose["position"] as MsgValue)["x"] as Number).toDouble(), 1e-9)
        assertEquals(Math.sqrt(0.5), ((pose["orientation"] as MsgValue)["z"] as Number).toDouble(), 1e-9)

        m.mode = Nav2Mission.Mode.THROUGH
        val through = DynCodec.fromJson(registry.action(Nav2Mission.Mode.THROUGH.type).goal, m.goalJson())
        assertEquals(2, (through["poses"] as List<*>).size)
        // Nav2 from Kilted onwards: the poses inside a nav_msgs/Goals.
        val newer = DynCodec.fromJson(m.newRegistry.action(Nav2Mission.Mode.THROUGH.type).goal, m.goalJson(newLayout = true))
        val goals = newer["poses"] as MsgValue
        assertEquals("map", (goals["header"] as MsgValue)["frame_id"])
        assertEquals(2, (goals["goals"] as List<*>).size)
        // The app's record does not change.
        assertEquals("poses", registry.action(Nav2Mission.Mode.THROUGH.type).goal.fields.first().name)
        assertEquals(true, registry.action(Nav2Mission.Mode.THROUGH.type).goal.fields.first().isArray)

        val copy = Nav2Mission(registry).apply { load(m.toJson()) }
        assertEquals(m.waypoints, copy.waypoints)
        assertEquals(Nav2Mission.Mode.THROUGH, copy.mode)
        assertEquals(2, copy.loops)
        copy.undo(); assertEquals(1, copy.waypoints.size)
    }
}
