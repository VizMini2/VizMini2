// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.rvizfile.MiniYaml
import com.juancams.rviz2mini.rviz.rvizfile.RvizImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniYamlTest {

    @Test
    fun parsesNestedMapsListsAndScalars() {
        val y = MiniYaml.parse(
            """
            a: 1
            b:
              c: "hola: mundo"   # comentario
              d: ~
              e: {}
            list:
              - x
              - k: 1
                j: 2
              -
                z: 3
            same:
            - p
            - q
            quoted: 'it''s'
            """.trimIndent()
        ) as Map<*, *>
        assertEquals("1", y["a"])
        val b = y["b"] as Map<*, *>
        assertEquals("hola: mundo", b["c"])
        assertNull(b["d"])
        assertEquals(emptyMap<String, Any?>(), b["e"])
        val list = y["list"] as List<*>
        assertEquals("x", list[0])
        assertEquals(mapOf("k" to "1", "j" to "2"), list[1])
        assertEquals(mapOf("z" to "3"), list[2])
        assertEquals(listOf("p", "q"), y["same"])
        assertEquals("it's", y["quoted"])
    }

    @Test
    fun keepsColonsAndHashesInsideValues() {
        val y = MiniYaml.parse("Value: Orbit (rviz)\nUrl: http://x:8000/a#b\nColor: 1; 2; 3") as Map<*, *>
        assertEquals("Orbit (rviz)", y["Value"])
        assertEquals("http://x:8000/a#b", y["Url"])
        assertEquals("1; 2; 3", y["Color"])
    }
}

class RvizImportTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResource("rviz/$name")!!.readText()

    @Test
    fun importsRealDummyRobotConfig() {
        val r = RvizImporter.import(resource("dummy_robot.rviz"))
        assertEquals(listOf(DisplayType.GRID, DisplayType.TF, DisplayType.ROBOT_MODEL), r.displays.map { it.type })
        assertEquals("world", r.fixedFrame)
        assertEquals(Colors.rgb(48, 48, 48), r.background)
        assertEquals("/goal_pose", r.goalTopic)
        assertEquals("/initialpose", r.poseTopic)
        assertEquals("/clicked_point", r.pointTopic)
        assertTrue(r.skipped.isEmpty())

        val grid = r.displays[0]
        assertEquals(0.5, grid.number("alpha"), 1e-9)
        assertEquals(10, grid.int("cellCount"))
        assertEquals(Colors.rgb(160, 160, 164), grid.color("color"))
        assertEquals("", grid.string("frame")) // <Fixed Frame>

        val robot = r.displays[2]
        assertEquals("/robot_description", robot.topic)
        assertEquals(QosPreset.RELIABLE, robot.qos())
        assertTrue(robot.bool("visual"))
        assertFalse(robot.bool("collision"))

        val v = r.view!!
        assertEquals("ORBIT", v["type"])
        assertEquals(6.071485996246338, v["distance"]!!.toDouble(), 1e-9)
        assertEquals(0.7203975915908813, v["yaw"]!!.toDouble(), 1e-9)
        assertEquals(-0.8615996837615967, v["fx"]!!.toDouble(), 1e-9)
    }

    @Test
    fun importsGroupsQosStylesAndTopDownView() {
        val r = RvizImporter.import(resource("nav2_like.rviz"), viewHeightPx = 2000)
        assertEquals(
            listOf(DisplayType.GRID, DisplayType.MAP, DisplayType.LASER_SCAN, DisplayType.IMAGE, DisplayType.PATH, DisplayType.ODOMETRY, DisplayType.POINT_CLOUD2),
            r.displays.map { it.type }
        )
        assertTrue(r.skipped.isEmpty())
        assertEquals("/camera/image_raw", r.displays.first { it.type == DisplayType.IMAGE }.topic)
        assertEquals("map", r.fixedFrame)
        assertEquals("/my_goal", r.goalTopic)
        assertEquals("/initialpose_custom", r.poseTopic)
        assertNull(r.pointTopic)

        val grid = r.displays[0]
        assertEquals("billboards", grid.string("lineStyle"))
        assertEquals(0.05, grid.number("lineWidth"), 1e-9)
        assertEquals(0.1, grid.number("offsetZ"), 1e-9)
        assertEquals(2.0, grid.number("cellSize"), 1e-9)

        val map = r.displays[1]
        assertEquals("/map", map.topic)
        assertEquals(QosPreset.LATCHED, map.qos())
        assertTrue(map.bool("drawBehind"))

        // The "Sensores" group is disabled: its displays are imported disabled.
        val scan = r.displays[2]
        assertFalse(scan.enabled)
        assertEquals(QosPreset.SENSOR, scan.qos())
        assertEquals("circles", scan.string("style"))
        assertEquals("flat", scan.string("colorMode"))
        assertEquals(Colors.rgb(255, 0, 0), scan.color("color"))
        assertFalse(scan.bool("autoBounds"))
        assertEquals(0.08, scan.number("size"), 1e-9)

        val path = r.displays[4]
        assertEquals("Plan global", path.name)
        assertEquals("arrows", path.string("poseStyle"))
        assertEquals(Colors.rgb(0, 0, 255), path.color("color"))

        val odom = r.displays[5]
        assertEquals("/odom", odom.topic) // legacy format "Topic: /odom"
        assertEquals("axes", odom.string("shape"))
        assertEquals(50, odom.int("keep"))
        assertEquals(0.5, odom.number("axesLength"), 1e-9)

        val cloud = r.displays[6]
        assertEquals("z", cloud.string("colorMode"))
        assertEquals(-1.0, cloud.number("minValue"), 1e-9)
        assertEquals(2.0, cloud.number("maxValue"), 1e-9)

        val v = r.view!!
        assertEquals("TOP_DOWN", v["type"])
        assertEquals(40.0, v["scale"]!!.toDouble(), 1e-9) // 2000 px / 50 px/m
        assertEquals(1.5, v["fx"]!!.toDouble(), 1e-9)
        assertEquals(0.5, v["angle"]!!.toDouble(), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonRvizYaml() {
        RvizImporter.import("foo: bar\n")
    }
}
