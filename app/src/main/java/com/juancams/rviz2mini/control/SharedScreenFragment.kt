// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.control

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.AlertsScreen
import com.juancams.rviz2mini.ui.screens.MapsScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/** A tab whose screen is the one shared with the PC (Maps, Alerts, PlanSys2): only hosts it. */
class SharedScreenFragment : TabFragment() {
    /** The tab's own type (withTabId replaces the arguments). */
    private val type get() = spec()?.type ?: TabType.MAPS

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                VizTheme {
                    when (type) {
                        TabType.ALERTS -> AlertsScreen(tabId)
                        TabType.PLANSYS2 -> com.juancams.rviz2mini.ui.screens.PlanSys2Screen(tabId)
                        else -> MapsScreen(tabId)
                    }
                }
            }
        }

    companion object {
        /** `type` is only documentation at the call site: the screen follows the tab's type. */
        @Suppress("UNUSED_PARAMETER") fun of(type: TabType) = SharedScreenFragment()
    }
}
