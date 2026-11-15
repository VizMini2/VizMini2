// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.MESSAGE_KEY_TOPIC
import com.juancams.rviz2mini.ui.screens.MESSAGE_KEY_TYPE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the phone: a type the app does not bring is defined from the Message tab ("Define the type…"), and the demo
 * (its simulator now shared with the PC) still runs, with the compact joystick in its dashboard (a screenshot in
 * /sdcard/clips/test/, to look at). Leaves the tabs, the user's own types and the connection as they were.
 */
@RunWith(AndroidJUnit4::class)
class CustomTypeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test fun defineAMissingTypeFromTheMessageTab() {
        val type = "vm_test_pkg/msg/Light"
        val savedTabs = TestTabs.backup(context)
        val interfaces = context.getSharedPreferences("rviz2mini_interfaces", Context.MODE_PRIVATE)
        val savedCustom = interfaces.getString("custom", null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val tab = TabSpec.create(TabType.MESSAGE, "Light").withSetting(MESSAGE_KEY_TOPIC, "/vm_light").withSetting(MESSAGE_KEY_TYPE, type)
                scenario.onActivity { a -> TabStore.get().add(tab); a.replaceTabs(TabStore.get().all.toList()); a.openTab(tab.id) }
                assertFalse(Interfaces.get().has(type))
                Ui.click(tr("custom_type_define"), exact = false, timeoutMs = 15_000)
                Ui.waitFor(tr("buttons_editor_custom_title"))
                // The name comes filled in (field 0); the definition is field 1.
                Ui.typeInto(1, "uint8 RED=1\nuint8 color\nfloat32 brightness")
                Ui.click(tr("buttons_editor_save"))
                SystemClock.sleep(1500)
                assertTrue(Interfaces.get().has(type))
                assertFalse("the notice is still there", Ui.exists(tr("custom_type_define")))
            }
        } finally {
            TestTabs.restore(context, savedTabs)
            interfaces.edit().apply { if (savedCustom == null) remove("custom") else putString("custom", savedCustom) }.commit()
        }
    }

    @Test fun demoStillRunsWithTheSharedSimulator() {
        val savedTabs = TestTabs.backup(context)
        val savedRos = context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).all
        shell("mkdir -p /sdcard/clips/test")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
                SystemClock.sleep(6000)
                // The simulated robot publishes: its odometry is on the network.
                var seen = false
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (!seen && SystemClock.uptimeMillis() < deadline) {
                    seen = RosConnection.graph()?.topics()?.any { it.name == "/odom" && it.publishers > 0 } == true
                    SystemClock.sleep(500)
                }
                assertTrue("the demo robot does not publish /odom", seen)
                val dash = TabStore.get().all.first { it.type == TabType.DASHBOARD }
                scenario.onActivity { it.openTab(dash.id) }
                SystemClock.sleep(5000)
                shell("screencap -p /sdcard/clips/test/demo_dashboard.png")
                scenario.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }
            }
        } finally {
            RosConnection.stop()
            DemoMode.exit(context, reconnect = false) { TabStore.get().replaceAll(it) }
            TestTabs.restore(context, savedTabs)
            context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).edit().clear().apply {
                savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v); is Boolean -> putBoolean(k, v) } }
            }.commit()
        }
    }

    private fun shell(cmd: String) =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
}
