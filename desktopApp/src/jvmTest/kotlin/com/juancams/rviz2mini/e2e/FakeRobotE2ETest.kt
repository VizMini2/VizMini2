// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.e2e

import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.bag.LiveBuffer
import com.juancams.rviz2mini.bag.McapReader
import com.juancams.rviz2mini.camera.ImageAnnotationTopics
import com.juancams.rviz2mini.ros.DomainScanner
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.TopicDiagnosis
import com.juancams.rviz2mini.ros.dyn.DynRos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Against tools/fake_robot.py: the graph, dynamic subscriptions, the "no data" diagnosis, bags, the rewind buffer... */
class FakeRobotE2ETest {

    @Before fun connect() = E2E.connect("/scan")

    @Test fun graphHasTheRobot() {
        val topics = RosConnection.graph()!!.topics(includeHidden = true).map { it.name }.toSet()
        for (t in listOf("/odom", "/scan", "/tf", "/tf_static", "/map", "/camera/image_raw", "/robot_description")) assertTrue("missing $t in $topics", t in topics)
    }

    @Test fun dynamicSubscriptionReceives() {
        val n = AtomicInteger()
        val sub = DynRos.subscribe("/odom", E2E.registry.msg("nav_msgs/msg/Odometry"), RosConnection.defaultQos()) { v ->
            if ((v["header"] as Map<*, *>)["frame_id"] == "odom") n.incrementAndGet()
        }
        try { E2E.waitFor("no /odom messages", 15_000) { n.get() >= 3 } } finally { sub.close() }
    }

    @Test fun diagnosisSuggestsTheRightTopic() {
        val r = TopicDiagnosis.diagnoseNow("/scann", { types -> "sensor_msgs/msg/LaserScan" in types }, "LaserScan", null, 0)
        // "/scann" is not there; it is not called "scan" at the end either, so no suggestion: just "no topic".
        assertTrue(r?.message.orEmpty(), r != null)
        val wrongNs = TopicDiagnosis.diagnoseNow("/robot1/scan", { types -> "sensor_msgs/msg/LaserScan" in types }, "LaserScan", null, 0)
        assertEquals("/scan", (wrongNs?.fix as? TopicDiagnosis.Fix.UseTopic)?.topic)
    }

    @Test fun recordsABag() {
        val file = java.io.File.createTempFile("e2e", ".mcap").apply { deleteOnExit() }
        val rec = BagRecorder(file, listOf("/odom" to "nav_msgs/msg/Odometry", "/scan" to "sensor_msgs/msg/LaserScan"), E2E.registry)
        Thread.sleep(3000)
        assertEquals(null, rec.stop())
        McapReader.open(file).use { r -> assertTrue("too few messages in the bag", r.messages().count() >= 5) }
    }

    @Test fun rewindBufferKeepsTheLastSeconds() {
        val b = LiveBuffer(E2E.registry, windowS = 60)
        b.setTopics(mapOf("/odom" to "nav_msgs/msg/Odometry", "/tf_static" to "tf2_msgs/msg/TFMessage"))
        try {
            E2E.waitFor("the buffer kept nothing", 15_000) { b.spanS() > 2 }
            val f = java.io.File.createTempFile("e2e-rewind", ".mcap").apply { deleteOnExit() }
            assertTrue(b.dump(f))
            McapReader.open(f).use { r -> assertTrue(r.messages().count() > 3) }
        } finally { b.close() }
    }

    @Test fun annotationsTopicIsFound() {
        val topics = RosConnection.graph()!!.topics(includeHidden = true).associate { it.name to it.types }
        assertEquals("/camera/image_markers", ImageAnnotationTopics.find("/camera/image_raw", topics))
    }

    @Test fun domainScannerSeesTheDomain() {
        val found = DomainScanner.scan(timeoutMs = 5000, domains = E2E.domain..E2E.domain)
        assertTrue("domain ${E2E.domain} not seen: $found", found[E2E.domain].orEmpty().isNotEmpty())
    }
}
