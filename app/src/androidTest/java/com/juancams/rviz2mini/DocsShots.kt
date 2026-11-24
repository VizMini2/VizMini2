// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots of the phone app for the manual (not a test of the suite): `-e shots true -e lang en|es`, with the
 * demo robot. Leaves PNGs in /sdcard/clips/<lang>/ as shot_phone_NAME.png (tools/docs_media/phone_media.sh turns them into
 * docs/shots/<lang>/ as WebP). Leaves the tabs, the language and the connection as they were.
 */
@RunWith(AndroidJUnit4::class)
class DocsShots {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private var lang = "en"

    @Test
    fun shots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shots") == "true")
        lang = InstrumentationRegistry.getArguments().getString("lang") ?: "en"
        shell("mkdir -p /sdcard/clips/$lang")
        ActivityScenario.launch(MainActivity::class.java).use { s -> s.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } } }
        val savedLang = AppLanguage.current()
        instrumentation.runOnMainSync { AppLanguage.set(lang) }
        val savedTabs = TestTabs.backup(context)
        val savedRos = context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).all
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
                SystemClock.sleep(5000)
                // A plot next to the demo's tabs: its menu offers the PlotJuggler export too.
                scenario.onActivity { a ->
                    TabStore.get().add(TabSpec.create(TabType.PLOT, TabType.PLOT.label()))
                    a.replaceTabs(TabStore.get().all.toList())
                }
                SystemClock.sleep(2000)
                val byType = TabStore.get().all.filter { !it.isWidget }.associateBy { it.type }
                fun open(type: TabType, settle: Long = 3000) {
                    scenario.onActivity { it.openTab(byType.getValue(type).id) }
                    SystemClock.sleep(settle)
                }

                scenario.onActivity { it.openTab("home") /* MainActivity.HOME_TAB_ID */ }
                SystemClock.sleep(2500)
                shot("home")
                open(TabType.RVIZ, 6000)
                // The tab's menu (long press on its name): rename, duplicate, share, delete and export.
                Ui.longClick(byType.getValue(TabType.RVIZ).name)
                SystemClock.sleep(1200)
                shot("tab_menu")
                Ui.back()
                SystemClock.sleep(800)
                // The "+" screen: Import at the top, then the templates and the tab types.
                scenario.onActivity { it.showNewTabDialog() }
                SystemClock.sleep(2500)
                shot("add_tab")
                Ui.back()
                SystemClock.sleep(1200)
                scenario.onActivity { it.openConfig() }
                SystemClock.sleep(2500)
                shot("config")
                scenario.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }
            }
        } finally {
            RosConnection.stop()
            DemoMode.exit(context, reconnect = false) { TabStore.get().replaceAll(it) }
            TestTabs.restore(context, savedTabs)
            instrumentation.runOnMainSync { AppLanguage.set(savedLang) }
            context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).edit().clear().apply {
                savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
            }.commit()
        }
    }

    private fun shot(name: String) = shell("screencap -p /sdcard/clips/$lang/shot_phone_$name.png")

    private fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use { it.readBytes() }
}
