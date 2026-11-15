// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.sliders

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
import com.juancams.rviz2mini.ui.screens.SLIDERS_KEY
import com.juancams.rviz2mini.ui.screens.SlidersController
import com.juancams.rviz2mini.ui.screens.SlidersScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/** Sliders tab: the screen shared with the desktop (ui/SlidersScreen). In a dashboard, "+" adds one. */
class SlidersFragment : TabFragment(), WidgetItems {

    private var addRequests by mutableIntStateOf(0)
    @Volatile private var controller: SlidersController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val widget = WidgetItems.inDashboard(this@SlidersFragment)
            setContent { VizTheme { SlidersScreen(tabId, widget = widget, addRequests = addRequests, onController = { controller = it }) } }
        }

    override fun addItem() { addRequests++ }

    override fun onPause() {
        super.onPause()
        // Tab hidden in the middle of a drag: stops publishing and (if it returns by itself) sends the initial value.
        controller?.pauseAll()
    }

    // ---------------------------------------------------------------- for the tests (on the main thread)

    internal fun slidersForTest(): List<SliderSpec> = controller?.sliders.orEmpty()
    internal fun upsertForTest(s: SliderSpec) = controller?.upsert(s)
    internal fun dragForTest(id: String, v: Double) { controller?.let { c -> c.runners[id]?.touchStart(); c.drag(id, v) } }
    internal fun releaseForTest(id: String): Double? = controller?.release(id)
    internal fun setExactForTest(id: String, v: Double) { controller?.setExact(id, v) }
    internal fun stateForTest(id: String): SliderState? = controller?.states?.get(id)
    internal fun setupErrorForTest(id: String): String? = controller?.runners?.get(id)?.setupError
    internal fun editForTest(id: String?) {
        val c = controller ?: return
        c.requestEdit(id)
        com.juancams.rviz2mini.rviz.OpenDialogs.dismissCompose = { c.editRequest = null; c.closeEditorRequests++ }
    }

    companion object {
        const val KEY_SLIDERS = SLIDERS_KEY
    }
}
