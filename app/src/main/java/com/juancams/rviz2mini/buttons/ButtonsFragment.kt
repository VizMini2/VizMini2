// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.buttons

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.dashboard.WidgetItems
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.ui.screens.BUTTONS_KEY
import com.juancams.rviz2mini.ui.screens.ButtonsController
import com.juancams.rviz2mini.ui.screens.ButtonsScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/** Buttons tab: the screen shared with the desktop (ui/ButtonsScreen). In a dashboard, "+" adds one. */
class ButtonsFragment : TabFragment(), WidgetItems {

    private var addRequests by mutableIntStateOf(0)
    @Volatile private var controller: ButtonsController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val widget = WidgetItems.inDashboard(this@ButtonsFragment)
            setContent { VizTheme { ButtonsScreen(tabId, widget = widget, addRequests = addRequests, onController = { controller = it }) } }
        }

    override fun addItem() { addRequests++ }

    override fun onPause() {
        super.onPause()
        // Hidden tab: whatever repeats while held down is released.
        controller?.runners?.values?.forEach { it.release() }
    }

    // ---------------------------------------------------------------- for the tests (on the main thread)

    internal fun buttonsForTest(): List<ButtonSpec> = controller?.buttons.orEmpty()
    internal fun upsertForTest(b: ButtonSpec) = controller?.upsert(b)
    internal fun deleteForTest(id: String) = controller?.delete(id)
    internal fun pressForTest(id: String) = controller?.runners?.get(id)?.press()
    internal fun releaseForTest(id: String) = controller?.runners?.get(id)?.release()
    internal fun cancelForTest(id: String) = controller?.runners?.get(id)?.cancelAction()
    internal fun stateForTest(id: String): ButtonState? = controller?.states?.get(id)
    internal fun setupErrorForTest(id: String): String? = controller?.runners?.get(id)?.setupError
    internal fun columnsForTest(): Int = view?.let { v -> (v.width / resources.displayMetrics.density / 170f).toInt().coerceIn(1, 6) } ?: 1
    /** Opens the editor (for the screenshots); OpenDialogs.dismissLast() closes it. */
    internal fun editForTest(id: String?) {
        val c = controller ?: return
        c.requestEdit(id)
        com.juancams.rviz2mini.rviz.OpenDialogs.dismissCompose = { c.editRequest = null; c.closeEditorRequests++ }
    }

    companion object {
        const val KEY_BUTTONS = BUTTONS_KEY
    }
}
