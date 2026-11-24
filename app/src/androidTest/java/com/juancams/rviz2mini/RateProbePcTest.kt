// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diagnostic probe (not a suite test): subscribes to `-e topic` (`-e type`, `-e qos sensor|default`)
 * on domain 77 for `-e seconds` and writes to logcat (tag RateProbe) how many messages arrive each second.
 */
@RunWith(AndroidJUnit4::class)
class RateProbePcTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() = RosConnection.stop()

    @Test
    fun probe() {
        val topic = args.getString("topic") ?: "/points"
        val type = args.getString("type") ?: "sensor_msgs/msg/PointCloud2"
        val seconds = args.getString("seconds")?.toInt() ?: 40
        val qos = if (args.getString("qos") == "default") RosConnection.defaultQos() else RosConnection.sensorQos()
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rate_probe", discoveryPeers = TestArgs.peers))
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val n = AtomicInteger()
        val sub = DynRos.subscribe(topic, Interfaces.get().msg(type), qos) { n.incrementAndGet() }
        // -e secondAt S -e secondFor D: a second subscription to the same topic between S and S+D (temporary remote reader).
        val secondAt = args.getString("secondAt")?.toInt() ?: -1
        val secondFor = args.getString("secondFor")?.toInt() ?: 8
        var second: DynRos.Subscription? = null
        try {
            for (s in 1..seconds) {
                SystemClock.sleep(1000)
                if (s == secondAt) second = DynRos.subscribe(topic, Interfaces.get().msg(type), qos) { }
                if (s == secondAt + secondFor) { second?.close(); second = null }
                Log.i("RateProbe", "t=${s}s $topic ${n.getAndSet(0)} msg/s${if (second != null) " (2ª suscripción)" else ""}")
            }
        } finally {
            second?.close()
            sub.close()
        }
    }
}
