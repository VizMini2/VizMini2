// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros

import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.QosSettings
import com.juancams.rviz2mini.ros.TopicDiagnosis.Fix
import com.juancams.rviz2mini.ros.TopicDiagnosis.TopicInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicDiagnosisTest {
    private val scan = "sensor_msgs/msg/LaserScan"
    private val accepts: (List<String>) -> Boolean = { scan in it }
    private val graph = listOf(TopicInfo("/robot1/scan", listOf(scan), 1, 0), TopicInfo("/map", listOf("nav_msgs/msg/OccupancyGrid"), 1, 0),
        TopicInfo("/cmd_vel", listOf("geometry_msgs/msg/Twist"), 0, 2))

    private fun d(topic: String, qos: QosSettings? = QosSettings.of(QosPreset.SENSOR), waited: Long = 10_000, connected: Boolean = true, acc: ((List<String>) -> Boolean)? = accepts) =
        TopicDiagnosis.diagnose(topic, acc, scan, qos, waited, connected, graph)

    @Test
    fun casesAndFixes() {
        assertTrue(d("/scan", connected = false)!!.fix == null)
        // Nobody publishes it, but there is one with the same ending and type: it is suggested.
        assertEquals("/robot1/scan", (d("/scan")!!.fix as Fix.UseTopic).topic)
        // Wrong type.
        assertTrue(d("/map")!!.message.contains("wrong_type"))
        // Only subscribers.
        assertNull(d("/cmd_vel", acc = null)!!.fix)
        // Publisher and nothing arrives: early on nothing is said; later, QoS.
        assertNull(d("/robot1/scan", waited = 1000))
        val reliable = d("/robot1/scan", qos = QosSettings.of(QosPreset.RELIABLE))!!.fix as Fix.UseQos
        assertEquals(QosSettings.BEST_EFFORT, reliable.reliability)
        val latched = d("/map", qos = QosSettings.of(QosPreset.SENSOR), acc = null)!!.fix as Fix.UseQos
        assertEquals(QosSettings.TRANSIENT_LOCAL, latched.durability)
    }
}
