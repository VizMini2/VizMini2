// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosForegroundService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Background service with the fake robot (domain 77): when enabled it goes to the foreground,
 * with the activity stopped the node keeps receiving /tf, and when disabled it stops.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val saved = HashMap<String, Map<String, *>>()

    @Before
    fun setUp() {
        for (p in PREFS) saved[p] = prefs(p).all.toMap()
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_bg_test"))
    }

    @After
    fun tearDown() {
        RosForegroundService.stop(context)
        RosConnection.stop()
        for (p in PREFS) prefs(p).edit().clear().apply {
            saved[p]?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v); is Boolean -> putBoolean(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun serviceInForeground(): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(50)
            .any { it.service.className == RosForegroundService::class.java.name && it.foreground }

    @Test
    fun keepsNodeAliveInBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == "config" }.select()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { a -> (a.supportFragmentManager.findFragmentByTag("config") as ConfigFragment).setKeepConnection(true) }
            assertTrue("El servicio no arranca", waitFor(5000) { RosForegroundService.running && serviceInForeground() })

            // TF from the fake robot (base_link at 20 Hz) arriving.
            assertTrue("Sin TF", waitFor(20_000) { fresh() })

            // App in the background (onStop): the node keeps receiving.
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(15_000)
            assertTrue("Servicio parado en segundo plano", serviceInForeground())
            assertTrue("Sin conexión en segundo plano: ${RosConnection.status}", RosConnection.status is RosConnection.Status.Connected)
            assertTrue("El nodo dejó de recibir TF en segundo plano", fresh())

            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { a -> (a.supportFragmentManager.findFragmentByTag("config") as ConfigFragment).setKeepConnection(false) }
            assertTrue("El servicio no se para", waitFor(5000) { !RosForegroundService.running })
            assertFalse(serviceInForeground())
        }
    }

    private fun fresh(): Boolean {
        val tf = RosConnection.tfTree
        return "base_link" in tf.frameIds() && !tf.isStale("base_link", System.nanoTime(), 1_000_000_000L)
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (cond()) return true
            SystemClock.sleep(200)
        }
        return cond()
    }

    private companion object {
        val PREFS = listOf("rviz2mini_ros", "rviz2mini_app")
    }
}
