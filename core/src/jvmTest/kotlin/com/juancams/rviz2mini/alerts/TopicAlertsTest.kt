// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's topic rules: when they fire and recover, silence, and how they are saved. */
class TopicAlertsTest {
    private val battery = TopicRule(id = "b", topic = "/battery_state", field = "percentage", op = TopicRule.Op.LT, threshold = 0.2)

    @Test fun firesOnTheChangeAndRecovers() {
        val t = TopicRuleTracker(battery, cooldownMs = 0)
        assertNull(t.onMessage(0.8, 1000))
        val a = t.onMessage(0.15, 2000)
        assertNotNull(a); assertEquals(AlertRules.Severity.WARNING, a!!.severity); assertTrue(a.text, "0.15" in a.text)
        // Still low: no new notice for every message.
        assertNull(t.onMessage(0.14, 3000)); assertTrue(t.firing)
        val r = t.onMessage(0.5, 4000)
        assertEquals(AlertRules.Severity.RECOVERED, r!!.severity); assertFalse(t.firing)
        // A message without the field does not count.
        assertNull(t.onMessage(null, 5000)); assertEquals(0.5, t.lastValue!!, 0.0)
    }

    @Test fun cooldownHoldsRepeatedNotices() {
        val t = TopicRuleTracker(battery, cooldownMs = 30_000)
        assertNotNull(t.onMessage(0.1, 1000))
        assertNotNull(t.onMessage(0.9, 2000)) // recovered: always said
        assertNull(t.onMessage(0.1, 3000))     // flapping within the cooldown
        assertTrue(t.firing)
        assertNotNull(t.onMessage(0.9, 4000))
        assertNotNull(t.onMessage(0.1, 40_000))
    }

    @Test fun silenceAfterAFirstMessage() {
        val rule = TopicRule(id = "s", topic = "/scan", kind = TopicRule.Kind.SILENCE, silenceS = 5.0)
        val t = TopicRuleTracker(rule, cooldownMs = 0)
        assertNull(t.tick(100_000)) // never seen: nothing to say
        assertNull(t.onMessage(null, 100_000))
        assertNull(t.tick(104_000))
        val a = t.tick(106_000)
        assertNotNull(a); assertTrue(t.firing)
        assertNull(t.tick(110_000)) // said once
        assertEquals(AlertRules.Severity.RECOVERED, t.onMessage(null, 111_000)!!.severity)
    }

    @Test fun operators() {
        assertTrue(TopicRule.Op.LE.test(2.0, 2.0)); assertFalse(TopicRule.Op.LT.test(2.0, 2.0))
        assertTrue(TopicRule.Op.GE.test(3.0, 2.0)); assertTrue(TopicRule.Op.NE.test(1.0, 2.0)); assertTrue(TopicRule.Op.EQ.test(2.0, 2.0))
        assertEquals(1.0, TopicAlertMonitor.number(true)!!, 0.0); assertEquals(3.0, TopicAlertMonitor.number(3L)!!, 0.0); assertNull(TopicAlertMonitor.number(listOf(1)))
    }

    @Test fun savedAsJson() {
        val r = TopicRule(id = "x", name = "Low battery", topic = "/b", field = "status[0].level", op = TopicRule.Op.GE, threshold = 2.0,
            severity = AlertRules.Severity.ERROR, enabled = false)
        assertEquals(r, TopicRule.fromJson(r.toJson()))
        assertEquals("Low battery", r.title())
        assertEquals("/b status[0].level ≥ 2", r.copy(name = "").title())
        assertNull(TopicRule.fromJson(org.json.JSONObject().put("kind", "VALUE")))
    }

    @Test fun logKeepsTheNewestFirst() {
        AlertLog.clear()
        AlertLog.add(AlertRules.Alert("a", "first", "", AlertRules.Severity.WARNING), 1)
        AlertLog.add(AlertRules.Alert("b", "second", "", AlertRules.Severity.ERROR), 2)
        assertEquals(listOf("second", "first"), AlertLog.all().map { it.alert.title })
        repeat(300) { AlertLog.add(AlertRules.Alert("n$it", "n", "", AlertRules.Severity.WARNING)) }
        assertEquals(200, AlertLog.all().size)
        AlertLog.clear()
    }
}
