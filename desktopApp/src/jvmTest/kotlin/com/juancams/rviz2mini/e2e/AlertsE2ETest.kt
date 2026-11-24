// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.e2e

import com.juancams.rviz2mini.alerts.AlertRules
import com.juancams.rviz2mini.alerts.TopicAlertMonitor
import com.juancams.rviz2mini.alerts.TopicRule
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The topic rules against the fake robot's /temperature (sensor_msgs/Temperature, about 10 Hz). */
class AlertsE2ETest {
    @Test fun valueAndSilenceRules() {
        E2E.connect("/temperature", 30_000)
        val got = CopyOnWriteArrayList<AlertRules.Alert>()
        val m = TopicAlertMonitor(E2E.registry) { got += it }
        try {
            val hot = TopicRule(id = "hot", topic = "/temperature", field = "temperature", op = TopicRule.Op.GT, threshold = -1000.0, severity = AlertRules.Severity.ERROR)
            val never = TopicRule(id = "never", topic = "/temperature", field = "temperature", op = TopicRule.Op.GT, threshold = 1e9)
            // Silent for 40 ms: between two messages at 10 Hz it is.
            val gap = TopicRule(id = "gap", topic = "/temperature", kind = TopicRule.Kind.SILENCE, silenceS = 0.04)
            m.sync(listOf(hot, never, gap))
            E2E.waitFor({ "no alert for the value rule: $got" }, 10_000) { got.any { it.key == "rule:hot" && it.severity == AlertRules.Severity.ERROR } }
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && got.none { it.key == "rule:gap" && it.severity == AlertRules.Severity.RECOVERED }) { m.tick(); Thread.sleep(20) }
            assertTrue("silence did not fire and recover: $got", got.any { it.key == "rule:gap" && it.severity != AlertRules.Severity.RECOVERED } &&
                got.any { it.key == "rule:gap" && it.severity == AlertRules.Severity.RECOVERED })
            assertTrue("the rule that never holds fired", got.none { it.key == "rule:never" })
            val (firing, value) = m.state().getValue("hot")
            assertTrue(firing && value != null)
            // Disabling the rules stops them.
            m.sync(emptyList()); got.clear(); Thread.sleep(500)
            assertTrue(got.isEmpty())
        } finally { m.close() }
    }
}
