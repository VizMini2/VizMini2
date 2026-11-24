// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlertRulesTest {

    @Test
    fun diagnosticsAlertOnRiseOnlyWithCooldown() {
        val d = AlertRules.Diagnostics(cooldownMs = 60_000)
        assertNull(d.update("motor", 0, "ok", 0))
        assertNull(d.update("motor", 1, "warm", 1_000))
        assertNotNull(d.update("motor", 2, "overheat", 2_000))
        assertNull(d.update("motor", 2, "overheat", 3_000)) // still in error: not repeated
        assertNull(d.update("motor", 0, "ok", 4_000))
        assertNull(d.update("motor", 2, "overheat", 10_000)) // within the cooldown
        assertNull(d.update("motor", 0, "ok", 70_000))
        assertNotNull(d.update("motor", 3, "stale", 80_000)) // after the cooldown
        assertEquals("ERROR · battery", d.update("battery", 2, "low", 0)!!.title)
    }

    @Test
    fun warningsOnlyWhenEnabled() {
        assertNull(AlertRules.Diagnostics().update("x", 1, "w", 0))
        assertNotNull(AlertRules.Diagnostics(includeWarnings = true).update("x", 1, "w", 0))
    }

    @Test
    fun presenceNeedsConsecutiveMisses() {
        val p = AlertRules.Presence("Lifecycle node", missingTicks = 2)
        assertEquals(0, p.update(setOf("/a", "/b")).size)
        assertEquals(0, p.update(setOf("/a")).size)
        assertEquals(listOf("presence:/b"), p.update(setOf("/a")).map { it.key })
        assertEquals(0, p.update(setOf("/a")).size) // not repeated
        assertEquals(AlertRules.Severity.RECOVERED, p.update(setOf("/a", "/b")).single().severity)
    }

    @Test
    fun contactLostAndRecovered() {
        val c = AlertRules.Contact(missingTicks = 2)
        assertNull(c.update(false)) // there was never anyone: no notice
        assertNull(c.update(true))
        assertNull(c.update(false))
        assertEquals(AlertRules.Severity.ERROR, c.update(false)!!.severity)
        assertNull(c.update(false))
        assertEquals(AlertRules.Severity.RECOVERED, c.update(true)!!.severity)
    }

    @Test
    fun lifecycleStates() {
        assertNotNull(AlertRules.lifecycleAlert("/n", 15, ""))
        assertNotNull(AlertRules.lifecycleAlert("/n", 4, "finalized"))
        assertNull(AlertRules.lifecycleAlert("/n", 3, "active"))
    }
}
