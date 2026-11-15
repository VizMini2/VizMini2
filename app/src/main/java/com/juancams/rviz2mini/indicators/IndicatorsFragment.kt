// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.indicators

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
import com.juancams.rviz2mini.ui.screens.INDICATORS_KEY
import com.juancams.rviz2mini.ui.screens.IndicatorsController
import com.juancams.rviz2mini.ui.screens.IndicatorsScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/** Indicators tab: the screen shared with the desktop (ui/IndicatorsScreen). In a dashboard, "+" adds one. */
class IndicatorsFragment : TabFragment(), WidgetItems {

    private var addRequests by mutableIntStateOf(0)
    @Volatile private var controller: IndicatorsController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val widget = WidgetItems.inDashboard(this@IndicatorsFragment)
            setContent { VizTheme { IndicatorsScreen(tabId, widget = widget, addRequests = addRequests, onController = { controller = it }) } }
        }

    override fun addItem() { addRequests++ }

    // ---------------------------------------------------------------- for tests (on the main thread)

    internal fun itemsForTest(): List<IndicatorSpec> = controller?.items.orEmpty()
    internal fun upsertForTest(s: IndicatorSpec) = controller?.upsert(s)
    internal fun editForTest(id: String?) {
        val c = controller ?: return
        c.requestEdit(id)
        com.juancams.rviz2mini.rviz.OpenDialogs.dismissCompose = { c.editRequest = null; c.closeEditorRequests++ }
    }
    internal fun valueTextForTest(id: String): String? = controller?.valueText(id)
    internal fun stateTextForTest(id: String): String? = controller?.stateText(id)
    internal fun tableRowsForTest(id: String): Int = controller?.runners?.get(id)?.latest()?.rows?.size ?: 0
    internal fun receivedForTest(id: String): Long = controller?.runners?.get(id)?.received?.get() ?: 0
    internal fun setupErrorForTest(id: String): String? = controller?.runners?.get(id)?.setupError

    companion object {
        const val KEY_INDICATORS = INDICATORS_KEY
    }
}
