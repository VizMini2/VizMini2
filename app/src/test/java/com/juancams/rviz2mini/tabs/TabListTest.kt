// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabListTest {

    @Test
    fun defaultTabsIsASingleRviz() {
        val tabs = TabList.defaultTabs()
        assertEquals(1, tabs.size)
        assertEquals(TabType.RVIZ, tabs[0].type)
        assertEquals("RViz", tabs[0].name)
    }

    @Test
    fun explicitNameIsTrimmedAndLimited() {
        assertEquals("Frontal", TabList.resolveName("  Frontal ", TabType.CAMERA, emptyList()))
        val long = "x".repeat(100)
        assertEquals(TabList.MAX_NAME_LENGTH, TabList.resolveName(long, TabType.CAMERA, emptyList()).length)
    }

    @Test
    fun emptyNameGetsNumberedDefault() {
        val existing = mutableListOf<TabSpec>()
        val n1 = TabList.resolveName("", TabType.CAMERA, existing).also { existing += TabSpec.create(TabType.CAMERA, it) }
        val n2 = TabList.resolveName(" ", TabType.CAMERA, existing).also { existing += TabSpec.create(TabType.CAMERA, it) }
        val n3 = TabList.resolveName("", TabType.CAMERA, existing)
        assertEquals(listOf("Camera", "Camera 2", "Camera 3"), listOf(n1, n2, n3))
    }

    @Test
    fun idsAreUnique() {
        assertNotEquals(TabSpec.create(TabType.RVIZ, "a").id, TabSpec.create(TabType.RVIZ, "a").id)
    }

    @Test
    fun jsonRoundTrip() {
        val tabs = listOf(
            TabSpec.create(TabType.RVIZ, "RViz").withSetting("fixedFrame", "map"),
            TabSpec.create(TabType.CAMERA, "Cámara \"frontal\"")
                .withSetting("topic", "/camera/image_raw")
                .withSetting("customTopics", "/a\n/b"),
            TabSpec.create(TabType.TELEOP, "Teleop")
        )
        assertEquals(tabs, TabList.fromJson(TabList.toJson(tabs)))
    }

    @Test
    fun withSettingNullRemovesKey() {
        val t = TabSpec.create(TabType.CAMERA, "c").withSetting("topic", "/x").withSetting("topic", null)
        assertEquals(emptyMap<String, String>(), t.settings)
    }

    @Test
    fun oldViewModelTabsAreDroppedOnLoad() {
        // "View model" tabs from earlier versions no longer exist: they are ignored without failing.
        val json = """[{"id":"1","type":"RVIZ","name":"RViz"},{"id":"2","type":"MODEL","name":"Modelo"}]"""
        assertEquals(listOf("RViz"), TabList.fromJson(json)!!.map { it.name })
    }

    @Test
    fun corruptOrUnknownEntriesAreSkipped() {
        assertNull(TabList.fromJson(null))
        assertNull(TabList.fromJson(""))
        assertNull(TabList.fromJson("esto no es json"))
        val json = """[{"id":"1","type":"RVIZ","name":"ok"},{"id":"2","type":"LIDAR_3000","name":"x"},{"type":"CAMERA"},42]"""
        val tabs = TabList.fromJson(json)!!
        assertEquals(1, tabs.size)
        assertEquals("ok", tabs[0].name)
    }

    @Test
    fun defaultNameUsesTheTranslatedTypeName() {
        val existing = listOf(TabSpec.create(TabType.CAMERA, "Cámara"))
        assertEquals("Cámara 2", TabList.resolveName("", TabType.CAMERA, existing, base = "Cámara"))
        assertEquals("Camera", TabList.resolveName("", TabType.CAMERA, existing))
    }
}
