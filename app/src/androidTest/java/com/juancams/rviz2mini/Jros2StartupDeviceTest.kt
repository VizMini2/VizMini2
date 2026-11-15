// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juancams.rviz2mini.ros.RosConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sensor_msgs.LaserScan
import sensor_msgs.PointCloud2
import sensor_msgs.PointField
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2QoSProfile
import us.ihmc.jros2.ROS2Topic
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * jros2 starts and moves messages on the device itself, without a PC.
 *
 * 0.18.0 regression: on Android 12 and 13 the app crashed at startup with NoSuchMethodError for
 * `java.nio.file.Path.of`, called from the static init of `jros2SettingsFile` (fixed by nio desugaring,
 * `desugar_jdk_libs_nio`). Run it on emulators of several APIs (26, 31, 33...), not just on the phone.
 */
@RunWith(AndroidJUnit4::class)
class Jros2StartupDeviceTest {

    private lateinit var a: ROS2Node
    private lateinit var b: ROS2Node

    @Before
    fun setUp() {
        a = RosConnection.createNode("jros2_startup_pub", DOMAIN)
        b = RosConnection.createNode("jros2_startup_sub", DOMAIN)
    }

    @After
    fun tearDown() {
        RosConnection.closeNode(a)
        RosConnection.closeNode(b)
    }

    @Test
    fun settingsAndQosClassesLoad() {
        // The classes that failed in the static init: if they load, the settings Path resolved fine.
        Class.forName("us.ihmc.jros2.jros2SettingsFile")
        ROS2QoSProfile()
    }

    @Test
    fun laserScanRoundTrip() {
        val sent = LaserScan().apply {
            header.frameId.append("laser")
            angleMin = -1.5f; angleMax = 1.5f; angleIncrement = 0.01f; rangeMax = 30f
            repeat(N_RANGES) { ranges.add(it * 0.01f); intensities.add(100f) }
        }
        val got = roundTrip("/jros2_startup/scan", LaserScan::class.java, sent, LaserScan())
        assertEquals("laser", got.header.frameId.toString())
        assertEquals(N_RANGES, got.ranges.size())
        assertEquals((N_RANGES - 1) * 0.01f, got.ranges.get(N_RANGES - 1), 1e-5f)
        assertEquals(N_RANGES, got.intensities.size())
    }

    @Test
    fun pointCloud2RoundTrip() {
        val points = 20_000 // ~240 KB: several UDP/SHM fragments
        val sent = PointCloud2().apply {
            header.frameId.append("cloud")
            height = 1; width = points; pointStep = 12; rowStep = 12 * points; isDense = true
            for ((i, n) in listOf("x", "y", "z").withIndex()) fields.add().apply {
                name.append(n); offset = 4 * i; datatype = PointField.FLOAT32; count = 1
            }
            val bytes = ByteArray(12 * points) { (it % 251).toByte() }
            data.addAll(bytes)
        }
        val got = roundTrip("/jros2_startup/points", PointCloud2::class.java, sent, PointCloud2())
        assertEquals(points, got.width)
        assertEquals(3, got.fields.size())
        assertEquals("z", got.fields.get(2).name.toString())
        assertEquals(12 * points, got.data.size())
        assertEquals((12 * points - 1) % 251, got.data.get(12 * points - 1).toInt() and 0xFF)
    }

    /** Publishes [msg] on [topic] from node A until node B receives it and returns what was received. */
    private fun <T : us.ihmc.jros2.ROS2Message<T>> roundTrip(topic: String, type: Class<T>, msg: T, into: T): T {
        val latch = CountDownLatch(1)
        val result = AtomicReference<T>()
        val sub = b.createSubscription(ROS2Topic(topic, type), { reader ->
            if (result.get() == null && reader.read(into)) { result.set(into); latch.countDown() }
        }, ROS2QoSProfile())
        val pub: ROS2Publisher<T> = a.createPublisher(ROS2Topic(topic, type), ROS2QoSProfile())
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (latch.count > 0 && System.currentTimeMillis() < deadline) {
                pub.publish(msg)
                latch.await(200, TimeUnit.MILLISECONDS)
            }
            assertTrue("No ha llegado ningún mensaje en $topic", latch.count == 0L)
            return result.get()
        } finally {
            a.destroyPublisher(pub)
            b.destroySubscription(sub)
        }
    }

    private companion object {
        const val DOMAIN = 78 // different from the 77 of the other tests
        const val N_RANGES = 1000
    }
}
