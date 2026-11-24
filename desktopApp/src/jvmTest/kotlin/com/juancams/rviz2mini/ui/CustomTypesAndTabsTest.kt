// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.juancams.rviz2mini.desktop.DesktopApp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.ServiceCallScreen
import com.juancams.rviz2mini.ui.screens.TypePickerDialog
import com.juancams.rviz2mini.ui.theme.VizTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Defining a type the app does not bring, from the screens that need it; and the PC sidebar's delete confirmation. */
@OptIn(ExperimentalTestApi::class)
class CustomTypesAndTabsTest {
    @Before fun setup() = UiTestSetup.install()

    @Test fun serviceWithAMissingTypeOffersToDefineIt() = runComposeUiTest {
        val type = "vm_test_pkg/srv/SetLight"
        assertFalse(Interfaces.get().has(type))
        setContent { VizTheme { ServiceCallScreen("/set_light", type) } }
        waitForIdle()
        assertTrue(onAllNodesWithText(tr("explore_service_type_missing", type)).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText(tr("custom_type_define")).performClick()
        waitForIdle()
        // The name comes filled in with the robot's type; only the definition is pasted.
        onNode(hasSetTextAction() and hasText(tr("custom_type_definition", "srv"))).performTextInput("uint8 color\nfloat32 brightness\n---\nbool ok")
        onNodeWithText(tr("buttons_editor_save")).performClick()
        waitForIdle()
        assertTrue(Interfaces.get().has(type))
        assertTrue(Interfaces.custom().containsKey(type))
        assertTrue("the call form did not appear", onAllNodesWithText(tr("explore_call")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun invalidDefinitionsAreRejected() = runComposeUiTest {
        val type = "vm_test_pkg/srv/Broken"
        setContent { VizTheme { ServiceCallScreen("/broken", type) } }
        waitForIdle()
        onNodeWithText(tr("custom_type_define")).performClick()
        waitForIdle()
        onNode(hasSetTextAction() and hasText(tr("custom_type_definition", "srv"))).performTextInput("not_a_type field\n---\nbool ok")
        onNodeWithText(tr("buttons_editor_save")).performClick()
        waitForIdle()
        assertFalse(Interfaces.get().has(type))
        // The dialog stays open with the error.
        assertTrue(onAllNodesWithText(tr("buttons_editor_custom_title")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun typePickerDefinesACustomType() = runComposeUiTest {
        var picked: String? = null
        setContent { VizTheme { TypePickerDialog(Interfaces.get(), "msg", onDismiss = {}) { picked = it } } }
        waitForIdle()
        onNodeWithText(tr("buttons_editor_define_custom")).performClick()
        waitForIdle()
        onNode(hasSetTextAction() and hasText(tr("custom_type_name"))).performTextInput("vm_test_pkg/msg/Light")
        onNode(hasSetTextAction() and hasText(tr("custom_type_definition", "msg"))).performTextInput("uint8 RED=1\nuint8 color\nfloat32 brightness")
        onNodeWithText(tr("buttons_editor_save")).performClick()
        waitForIdle()
        assertEquals("vm_test_pkg/msg/Light", picked)
        assertNotNull(Interfaces.get().msg("vm_test_pkg/msg/Light"))
    }

    @Test fun sidebarAsksBeforeDeletingATab() {
        val store = TabStore.get()
        val saved = store.all.toList()
        val dash = TabSpec.create(TabType.DASHBOARD, "Doomed")
        val widget = TabSpec.create(TabType.INDICATORS, "W").withSetting(TabSpec.KEY_DASHBOARD, dash.id)
        store.replaceAll(listOf(dash, widget))
        try {
            runComposeUiTest {
                setContent { DesktopApp(startTab = "CONFIG") }
                waitForIdle()
                val close = onAllNodesWithContentDescription(tr("main_delete_tab_message"))
                close[0].performClick()
                waitForIdle()
                // Cancel keeps it.
                onNodeWithText(tr("common_cancel")).performClick()
                waitForIdle()
                assertNotNull(store.get(dash.id))
                close[0].performClick()
                waitForIdle()
                assertTrue(onAllNodesWithText(tr("common_delete_named", "Doomed")).fetchSemanticsNodes().isNotEmpty())
                onNodeWithText(tr("common_delete")).performClick()
                waitForIdle()
                // The dashboard goes with its widgets.
                assertNull(store.get(dash.id))
                assertNull(store.get(widget.id))
            }
        } finally { store.replaceAll(saved) }
    }
}
