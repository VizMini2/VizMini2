// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.juancams.rviz2mini.bag.BagPlayer
import com.juancams.rviz2mini.bag.BagSession
import com.juancams.rviz2mini.demo.DemoRobot
import com.juancams.rviz2mini.desktop.DesktopDemo
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.common.PlaybackBar
import com.juancams.rviz2mini.ui.common.PlaybackBars
import com.juancams.rviz2mini.ui.demo.DemoTabs
import com.juancams.rviz2mini.ui.screens.LIFECYCLE_KEY_NODES
import com.juancams.rviz2mini.ui.theme.VizTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** What the PC gained to match the phone: demo mode, the bag timeline and the node inspector from Lifecycle. */
@OptIn(ExperimentalTestApi::class)
class DesktopFeaturesTest {
    @Before fun setup() = UiTestSetup.install()

    @Test fun demoModeComesBackToTheUsersTabsAndNetwork() {
        val store = TabStore.get()
        val saved = store.all.toList()
        val mine = TabSpec.create(TabType.TOPICS, "Mine")
        store.replaceAll(listOf(mine))
        val myNetwork = RosConfig.load()
        try {
            val demo = DesktopDemo.enter()
            assertTrue(DesktopDemo.active)
            assertEquals(DemoTabs.all().map { it.type }, demo.map { it.type })
            assertEquals(DemoRobot.NETWORK.domainId, RosConfig.load().domainId)
            val back = DesktopDemo.exit()
            assertFalse(DesktopDemo.active)
            assertEquals(listOf(mine.id), back.map { it.id })
            assertEquals(myNetwork.domainId, RosConfig.load().domainId)
        } finally {
            if (DesktopDemo.active) DesktopDemo.exit()
            store.replaceAll(saved)
        }
    }

    @Test fun everyDemoTabDraws() {
        val failures = ArrayList<String>()
        val tabs = DemoTabs.all()
        val store = TabStore.get()
        val saved = store.all.toList()
        store.replaceAll(tabs)
        try {
            for (t in tabs.filter { !it.isWidget }) runCatching {
                runComposeUiTest { setContent { VizTheme { TabScreen(t) } }; waitForIdle() }
            }.onFailure { failures += "${t.name}: $it" }
        } finally { store.replaceAll(saved) }
        assertTrue("demo tabs that failed to draw:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun theWindowsTimelineShowsWhileABagPlays() = runComposeUiTest {
        val bag = File("../core/src/jvmTest/resources/bag/jazzy_plain.mcap")
        assertTrue(bag.isFile)
        PlaybackBars.shownByWindow = true
        try {
            setContent { VizTheme { androidx.compose.foundation.layout.Column { PlaybackBar(inWindow = true); PlaybackBar() } } }
            waitForIdle()
            assertTrue(onAllNodesWithContentDescription(tr("playback_stop")).fetchSemanticsNodes().isEmpty())
            BagSession.player = BagPlayer(bag, Interfaces.get(), object : BagPlayer.Listener {
                override fun onProgress(positionNs: Long, durationNs: Long) {}
                override fun onFinished(error: String?) {}
            })
            mainClock.advanceTimeBy(600)
            waitForIdle()
            // Only the window's one (the dashboard's and the 3D view's step aside).
            assertEquals(1, onAllNodesWithContentDescription(tr("playback_stop")).fetchSemanticsNodes().size)
            onNodeWithContentDescription(tr("playback_pause")).performClick()
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertTrue(BagSession.player!!.paused)
            onNodeWithContentDescription(tr("playback_stop")).performClick()
            waitForIdle()
            assertNull(BagSession.player)
        } finally { PlaybackBars.shownByWindow = false; BagSession.player = null }
    }

    @Test fun lifecycleOpensTheNodeInspector() = runComposeUiTest {
        val spec = TabSpec.create(TabType.LIFECYCLE, "Lifecycle").withSetting(LIFECYCLE_KEY_NODES, "/vm_test_node").also { TabStore.get().add(it) }
        try {
            setContent { VizTheme { TabScreen(spec) } }
            waitForIdle()
            onNodeWithText(tr("lifecycle_menu_params")).performClick()
            waitForIdle()
            onNodeWithContentDescription(tr("params_back")).performClick()
            waitForIdle()
            assertTrue(onAllNodesWithText(tr("lifecycle_menu_params")).fetchSemanticsNodes().isNotEmpty())
        } finally { TabStore.get().remove(spec.id) }
    }
}
