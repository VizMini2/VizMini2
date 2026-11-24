// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.gps.GpsFragment
import com.juancams.rviz2mini.gps.GpsMath
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** GPS tab with a NavSatFix published by the app itself: centers, follows, trail and accuracy. */
@RunWith(AndroidJUnit4::class)
class GpsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_gps_test"))
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
    }

    @After
    fun tearDown() {
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun showsFixTrackAndAccuracy() {
        val topic = "/rviz2mini_test/gps"
        val tab = TabSpec.create(TabType.GPS, "GPS test").withSetting(GpsFragment.KEY_TOPIC, topic)
        TabStore.get().add(tab)
        val pub = DynRos.publisher(topic, Interfaces.get().msg("sensor_msgs/msg/NavSatFix"), RosConnection.defaultQos())
        fun send(lat: Double, lon: Double, status: Long = 0) = pub.publish(MsgValue().apply {
            put("status", MsgValue().apply { put("status", status) })
            put("latitude", lat); put("longitude", lon); put("altitude", 650.0)
            put("position_covariance", listOf(4.0, 0.0, 0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 9.0))
            put("position_covariance_type", 2L)
        })
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                    (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
                }
                instrumentation.waitForIdleSync()
                var f: GpsFragment? = null
                scenario.onActivity { a -> f = a.supportFragmentManager.findFragmentByTag(tab.id) as GpsFragment }
                val gps = f!!
                val matched = SystemClock.uptimeMillis() + 10_000
                while (pub.matched() == 0 && SystemClock.uptimeMillis() < matched) SystemClock.sleep(100)

                // A 20 m walk to the north, one point per meter.
                for (i in 0..20) {
                    val (lat, lon) = GpsMath.offset(40.416775, -3.703790, 0.0, i.toDouble())
                    send(lat, lon); SystemClock.sleep(250)
                }
                SystemClock.sleep(600)
                val fix = onMain { gps.lastFixForTest() }!!
                val (latEnd, lonEnd) = GpsMath.offset(40.416775, -3.703790, 0.0, 20.0)
                assertEquals(latEnd, fix.latitude, 1e-9)
                assertEquals(2.0, fix.accuracyM, 1e-9)
                val track = onMain { gps.trackSizeForTest() }
                Log.i(TAG, "rastro: $track puntos, ${onMain { gps.infoForTest() }.replace('\n', ' ')}")
                assertTrue("rastro corto: $track", track >= 15)
                assertTrue(onMain { gps.infoForTest() }.contains("±2.0 m"))
                // Follows the position: the map center ends up next to the last point.
                SystemClock.sleep(1500)
                val c = onMain { gps.centerForTest() }
                assertTrue("el mapa no sigue: $c", GpsMath.distance(c.latitude, c.longitude, latEnd, lonEnd) < 5)
                shot()

                // No fix: nothing moves.
                send(0.0, 0.0, status = -1)
                SystemClock.sleep(500)
                assertEquals(latEnd, onMain { gps.lastFixForTest() }!!.latitude, 1e-9)
            }
        } finally {
            pub.close()
            TabStore.get().remove(tab.id)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var r: T? = null
        instrumentation.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun shot() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(2000) // the tiles
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "gps.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
    }

    private companion object {
        const val TAG = "GpsTest"
    }
}
