// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicTypeRegistryTest {

    @Test
    fun sameTypeIsCompatibleOtherIsNot() {
        val r = TopicTypeRegistry()
        assertTrue(r.isCompatible("/image", "Image"))
        r.register("/image", "Image")
        assertTrue(r.isCompatible("/image", "Image"))
        assertFalse(r.isCompatible("/image", "CompressedImage"))
        assertTrue(r.isCompatible("/other", "CompressedImage"))
    }

    @Test
    fun firstRegistrationWins() {
        val r = TopicTypeRegistry()
        r.register("/cmd_vel", "Twist")
        r.register("/cmd_vel", "TwistStamped")
        assertEquals("Twist", r.typeOf("/cmd_vel"))
    }

    @Test
    fun clearForgetsEverything() {
        val r = TopicTypeRegistry()
        r.register("/cmd_vel", "Twist")
        r.clear()
        assertTrue(r.isCompatible("/cmd_vel", "TwistStamped"))
    }

    @Test
    fun groupsHaveUniqueNamesAndKeepEverything() {
        val items = listOf("/a" to 1, "/a" to 2, "/b" to 1, "/a" to 3, "/b" to 2, "/c" to 1)
        val groups = TopicTypeRegistry.groupWithoutNameClashes(items) { it.first }
        assertEquals(3, groups.size)
        for (g in groups) assertEquals(g.size, g.map { it.first }.toSet().size)
        assertEquals(items.toSet(), groups.flatten().toSet())
        assertEquals(listOf("/a" to 1, "/b" to 1, "/c" to 1), groups[0])
    }

    @Test
    fun noClashesMeansSingleGroup() {
        val groups = TopicTypeRegistry.groupWithoutNameClashes(listOf("/a", "/b", "/c")) { it }
        assertEquals(1, groups.size)
        assertTrue(TopicTypeRegistry.groupWithoutNameClashes(emptyList<String>()) { it }.isEmpty())
    }
}
