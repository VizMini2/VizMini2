// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageIntrospectionTest {

    private fun odom() = nav_msgs.Odometry().apply {
        header.setFrameId("odom")
        header.stamp.sec = 12; header.stamp.nanosec = 34
        setChildFrameId("base_link")
        pose.pose.position.x = 1.5
        twist.twist.linear.x = 0.25
        twist.twist.angular.z = -0.5
    }

    @Test
    fun formatsLikeRosTopicEcho() {
        val text = MessageIntrospection.format(odom())
        assertTrue(text, text.contains("header:\n  stamp:\n    sec: 12\n    nanosec: 34\n  frame_id: odom"))
        assertTrue(text, text.contains("child_frame_id: base_link"))
        assertTrue(text, text.indexOf("header:") < text.indexOf("child_frame_id:"))
        assertTrue(text, text.contains("orientation:\n      x: 0.0\n      y: 0.0\n      z: 0.0\n      w: 1.0"))
        assertTrue(text, text.contains("    linear:\n      x: 0.25"))
        // covariance (double[36]) inline and truncated
        assertTrue(text, Regex("covariance: \\[0\\.0, 0\\.0, .*… \\(\\+28\\)]").containsMatchIn(text))
    }

    @Test
    fun formatsSequencesOfMessagesAndPrimitives() {
        val scan = sensor_msgs.LaserScan().apply { for (i in 0 until 20) ranges.add(i.toFloat()) }
        val t = MessageIntrospection.format(scan, maxSeq = 3)
        assertTrue(t, t.contains("ranges: [0.0, 1.0, 2.0, … (+17)]"))
        assertTrue(t, t.contains("intensities: []"))

        val arr = diagnostic_msgs.DiagnosticArray()
        arr.status.add().apply { setName("motor"); level = 2 }
        val d = MessageIntrospection.format(arr)
        assertTrue(d, d.contains("status:\n- \n  level: 2\n  name: motor"))
    }

    @Test
    fun numericPathsAndEvaluation() {
        val o = odom()
        val paths = MessageIntrospection.numericPaths(o)
        assertTrue(paths.contains("twist.twist.linear.x"))
        assertTrue(paths.contains("pose.covariance[0]"))
        assertTrue(paths.contains("header.stamp.sec"))
        assertEquals(0.25, MessageIntrospection.NumericPath("twist.twist.linear.x").eval(o)!!, 1e-9)
        assertEquals(-0.5, MessageIntrospection.NumericPath("twist.twist.angular.z").eval(o)!!, 1e-9)
        assertEquals(12.0, MessageIntrospection.NumericPath("header.stamp.sec").eval(o)!!, 1e-9)
        assertNull(MessageIntrospection.NumericPath("twist.nope").eval(o))
        assertNull(MessageIntrospection.NumericPath("child_frame_id").eval(o))

        val scan = sensor_msgs.LaserScan().apply { ranges.add(3f); ranges.add(4f) }
        assertEquals(4.0, MessageIntrospection.NumericPath("ranges[1]").eval(scan)!!, 1e-6)
        assertNull(MessageIntrospection.NumericPath("ranges[5]").eval(scan))
    }

    @Test
    fun onlyPlottableTypesAreOfferedForThePlot() {
        // What is shown in RViz / camera / echo but is not a numeric series.
        for (t in listOf("nav_msgs/msg/OccupancyGrid", "sensor_msgs/msg/Image", "sensor_msgs/msg/CompressedImage",
                "sensor_msgs/msg/PointCloud2", "visualization_msgs/msg/MarkerArray", "std_msgs/msg/String",
                "std_msgs/msg/Empty", "nav_msgs/msg/Path", "tf2_msgs/msg/TFMessage", "rcl_interfaces/msg/Log")) {
            assertTrue(t, !MessageCatalog.isPlottable(t))
            assertTrue(t, t !in MessageCatalog.plottable)
        }
        for (t in listOf("nav_msgs/msg/Odometry", "sensor_msgs/msg/Imu", "geometry_msgs/msg/Twist", "sensor_msgs/msg/LaserScan",
                "sensor_msgs/msg/JointState", "sensor_msgs/msg/BatteryState", "std_msgs/msg/Float64", "std_msgs/msg/Bool",
                "geometry_msgs/msg/PoseStamped", "sensor_msgs/msg/Joy", "std_msgs/msg/Float32MultiArray")) {
            assertTrue(t, MessageCatalog.isPlottable(t))
        }
        assertTrue(MessageCatalog.isPlottable("geometry_msgs/Twist"))
        assertTrue(MessageCatalog.plottable.size > 30)
    }

    @Test
    fun numericPathsSkipRawByteData() {
        val grid = nav_msgs.OccupancyGrid().apply { info.width = 3; for (i in 0 until 9) data.add(i.toByte()) }
        val paths = MessageIntrospection.numericPaths(grid)
        assertTrue(paths.toString(), paths.none { it.startsWith("data") })
        assertTrue(paths.contains("info.width"))
    }

    @Test
    fun catalogResolvesClasses() {
        assertTrue(MessageCatalog.all.contains("sensor_msgs/msg/Imu"))
        for (name in MessageCatalog.all) assertNotNull(name, MessageCatalog.classFor(name))
        assertEquals(std_msgs.String_::class.java, MessageCatalog.classFor("std_msgs/msg/String"))
        assertEquals(geometry_msgs.Twist::class.java, MessageCatalog.classFor("geometry_msgs/Twist"))
        assertNull(MessageCatalog.classFor("foo/msg/Bar"))
        for ((_, type) in MessageCatalog.COMMON_TOPICS) assertNotNull(type, MessageCatalog.classFor(type))
    }
}

class DiagModelsTest {

    @Test
    fun logBufferFiltersByLevelAndTextAndKeepsCapacity() {
        val b = LogBuffer(capacity = 3)
        b.add(LogEntry(1, LogLevel.DEBUG, "nav", "planning"))
        b.add(LogEntry(2, LogLevel.INFO, "nav", "goal reached"))
        b.add(LogEntry(3, LogLevel.WARN, "battery", "low"))
        b.add(LogEntry(4, LogLevel.ERROR, "motor", "overcurrent"))
        assertEquals(3, b.size)
        assertEquals(listOf("goal reached", "low", "overcurrent"), b.filtered(LogLevel.DEBUG, "").map { it.message })
        assertEquals(listOf("low", "overcurrent"), b.filtered(LogLevel.WARN, "").map { it.message })
        assertEquals(listOf("overcurrent"), b.filtered(LogLevel.DEBUG, "MOTOR").map { it.message })
        assertEquals(LogLevel.WARN, LogLevel.of(35))
        assertEquals(LogLevel.DEBUG, LogLevel.of(0))
    }

    @Test
    fun diagnosticsSortsWorstFirstAndMarksStale() {
        val m = DiagnosticsModel(staleMs = 1000)
        m.update(DiagEntry("b_ok", DiagLevel.OK, "", "", emptyList(), 0))
        m.update(DiagEntry("a_warn", DiagLevel.WARN, "hot", "", emptyList(), 900))
        m.update(DiagEntry("z_err", DiagLevel.ERROR, "dead", "", emptyList(), 900))
        assertEquals(listOf("z_err", "a_warn", "b_ok"), m.snapshot(500).map { it.name })
        // At 1500 ms "b_ok" has gone 1500 ms without data: STALE.
        val s = m.snapshot(1500)
        assertEquals(DiagLevel.STALE, s.first { it.name == "b_ok" }.level)
        assertEquals(mapOf(DiagLevel.ERROR to 1, DiagLevel.WARN to 1, DiagLevel.STALE to 1), m.counts(1500))
    }

    @Test
    fun plotSeriesWindowAndRate() {
        val p = PlotSeries("x", windowMs = 1000, maxPoints = 4)
        for (i in 0 until 6) p.add(i * 300L, i.toDouble())
        val (t, v) = p.points(1500)
        assertEquals(listOf(600L, 900L, 1200L, 1500L), t.toList()) // window [500, 1500] and at most 4
        assertEquals(listOf(2.0, 3.0, 4.0, 5.0), v.toList())

        val r = RateMeter(windowMs = 2000)
        for (i in 1..10) r.tick(i * 100L)
        assertEquals(10.0, r.hz(1000), 1e-9) // 10 messages in the first second
        val burst = RateMeter()
        burst.tick(5000); burst.tick(5000)
        assertEquals(2.0, burst.hz(5000), 1e-9) // two at once are not 1000 Hz
        for (i in 1..30) r.tick(1000L + i * 100L)
        assertEquals(10.0, r.hz(4000), 0.6) // window full: 2 s
    }
}
