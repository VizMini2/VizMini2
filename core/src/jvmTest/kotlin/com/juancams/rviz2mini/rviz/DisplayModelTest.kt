// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.PropertySpec
import com.juancams.rviz2mini.rviz.display.QosPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModelTest {

    @Test
    fun defaultsAreGridAndTf() {
        assertEquals(listOf(DisplayType.GRID, DisplayType.TF), DisplayList.defaults().map { it.type })
        assertTrue(DisplayList.defaults().all { it.enabled })
    }

    @Test
    fun topicTypesHaveRosTypeAndDefaultTopic() {
        for (t in DisplayType.TOPIC_TYPES) {
            assertTrue(t.name, t.rosType.matches(Regex("^[a-z_]+/msg/[A-Za-z0-9]+$")))
            assertTrue(t.name, t.defaultTopic.startsWith("/"))
            assertTrue(t.name, t.defaultTopic in t.commonTopics || t.commonTopics.isNotEmpty())
            assertTrue("${t.name} debe tener QoS", t.spec("qos") != null)
        }
        for (t in listOf(DisplayType.GRID, DisplayType.TF, DisplayType.AXES)) assertFalse(t.needsTopic)
    }

    @Test
    fun propertyKeysAreUniquePerType() {
        for (t in DisplayType.entries) assertEquals(t.name, t.properties.size, t.properties.map { it.key }.toSet().size)
    }

    @Test
    fun numberDefaultsAreInsideTheirRange() {
        for (t in DisplayType.entries) for (p in t.properties) if (p is PropertySpec.Number) {
            assertTrue("${t.name}.${p.key}", p.default in p.min..p.max)
        }
    }

    @Test
    fun numberRangesAreWholeMultiplesOfTheStep() {
        // Material's Slider throws an exception if (max - min) is not a multiple of the step.
        for (t in DisplayType.entries) for (p in t.properties) if (p is PropertySpec.Number) {
            val steps = (p.max - p.min) / p.step
            assertEquals("${t.name}.${p.key}: $steps pasos", Math.round(steps).toDouble(), steps, 1e-6)
            val fromDefault = (p.default - p.min) / p.step
            assertEquals("${t.name}.${p.key}: el valor por defecto no cae en un paso", Math.round(fromDefault).toDouble(), fromDefault, 1e-6)
        }
    }

    @Test
    fun valuesFallBackToDefaultsAndAreClamped() {
        val d = DisplayConfig.create(DisplayType.LASER_SCAN)
        assertEquals("/scan", d.topic)
        assertEquals(0.05, d.number("size"), 1e-9)
        assertEquals("intensity", d.string("colorMode"))
        assertEquals(Colors.rgb(255, 255, 255), d.color("color"))
        assertEquals(QosPreset.SENSOR, d.qos())
        val big = d.with("size", "99")
        assertEquals(1.0, big.number("size"), 1e-9)
        assertEquals(0.05, d.with("size", "no-es-un-número").number("size"), 1e-9)
        assertEquals(Colors.rgb(0, 255, 0), d.with("color", "#00FF00").color("color"))
        assertEquals(QosPreset.LATCHED, DisplayConfig.create(DisplayType.MAP).qos())
    }

    @Test
    fun displaysWithoutTopicHaveEmptyTopic() {
        assertEquals("", DisplayConfig.create(DisplayType.GRID).topic)
        assertEquals("", DisplayConfig.create(DisplayType.TF, topic = "/tf").topic)
    }

    @Test
    fun shortNameEllipsizes() {
        assertEquals("LaserScan", DisplayList.shortName("LaserScan"))
        val s = DisplayList.shortName("local_costmap/published_footprint")
        assertEquals(DisplayList.MAX_CHIP_CHARS, s.length)
        assertTrue(s.endsWith("…"))
    }

    @Test
    fun nameForTopic() {
        assertEquals("scan", DisplayList.nameForTopic(DisplayType.LASER_SCAN, "/scan"))
        assertEquals("Map", DisplayList.nameForTopic(DisplayType.MAP, "/"))
    }

    @Test
    fun jsonRoundTripAndCorruptInput() {
        val list = listOf(
            DisplayConfig.create(DisplayType.GRID).with("cellSize", "0.5"),
            DisplayConfig.create(DisplayType.MAP).copy(enabled = false, name = "Mapa \"global\""),
            DisplayConfig.create(DisplayType.MARKER_ARRAY, topic = "/viz")
        )
        assertEquals(list, DisplayList.fromJson(DisplayList.toJson(list)))
        assertNull(DisplayList.fromJson(null))
        assertNull(DisplayList.fromJson("{roto"))
        val partial = DisplayList.fromJson("""[{"id":"a","type":"GRID"},{"id":"b","type":"HOLOGRAM"},{"type":"TF"}]""")!!
        assertEquals(1, partial.size)
        assertEquals("Grid", partial[0].name)
    }
}

class ColorsTest {
    @Test
    fun hexRoundTrip() {
        val c = Colors.rgb(25, 255, 0)
        assertEquals("#19FF00", Colors.toHex(c))
        assertEquals(c, Colors.parseHex("#19ff00"))
        assertNull(Colors.parseHex("19ff"))
        assertNull(Colors.parseHex("#zzzzzz"))
    }

    @Test
    fun channelsAndAlpha() {
        val c = Colors.withAlpha(Colors.rgb(10, 20, 30), 0.5f)
        assertEquals(10, Colors.red(c)); assertEquals(20, Colors.green(c)); assertEquals(30, Colors.blue(c))
        assertEquals(128, Colors.alpha(c))
        assertEquals(Colors.rgb(5, 10, 15, 128), Colors.shade(c, 0.5f))
    }

    @Test
    fun rainbowEndsAndClamps() {
        assertEquals(Colors.rgb(255, 0, 0), Colors.rainbow(0f))
        assertEquals(Colors.rgb(0, 0, 255), Colors.rainbow(1f))
        assertEquals(Colors.rgb(0, 255, 0), Colors.rainbow(0.5f))
        assertEquals(Colors.rainbow(1f), Colors.rainbow(7f))
        assertEquals(Colors.rainbow(0f), Colors.rainbow(Float.NaN))
    }
}
