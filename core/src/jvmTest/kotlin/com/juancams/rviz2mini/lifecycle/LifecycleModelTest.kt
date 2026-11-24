// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.lifecycle

import com.juancams.rviz2mini.lifecycle.LifecycleModel.Kind
import com.juancams.rviz2mini.lifecycle.LifecycleModel.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleModelTest {

    @Test
    fun stateKinds() {
        assertEquals(Kind.ACTIVE, LifecycleModel.State(3, "active").kind)
        assertEquals(Kind.TRANSITIONING, LifecycleModel.State(10, "configuring").kind)
        assertEquals(Kind.ERROR, LifecycleModel.State(15, "errorprocessing").kind)
        assertTrue(LifecycleModel.State(13, "").isTransitioning)
        assertFalse(LifecycleModel.State(2, "inactive").isTransitioning)
        assertEquals("Inactive", LifecycleModel.State(2, "").title)
    }

    @Test
    fun transitionsNormalPathFirstShutdownLast() {
        val sorted = LifecycleModel.sortTransitions(
            listOf(Transition(6, "shutdown"), Transition(4, "deactivate"), Transition(2, "cleanup"), Transition(3, "activate"), Transition(3, "activate"))
        )
        assertEquals(listOf("activate", "deactivate", "cleanup", "shutdown"), sorted.map { it.label })
        assertTrue(sorted.last().isDestructive)
    }

    @Test
    fun nodesSettingRoundTrip() {
        val nodes = LifecycleModel.parseNodes(" talker\n\n/ns/map_server/\n/talker ")
        assertEquals(listOf("/talker", "/ns/map_server"), nodes)
        assertEquals(nodes, LifecycleModel.parseNodes(LifecycleModel.formatNodes(nodes)))
        assertEquals(emptyList<String>(), LifecycleModel.parseNodes(null))
    }
}
