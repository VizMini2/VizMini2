// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.params

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.ui.screens.PARAMS_KEY_FILTER
import com.juancams.rviz2mini.ui.screens.PARAMS_KEY_NODE
import com.juancams.rviz2mini.ui.screens.ParamsController
import com.juancams.rviz2mini.ui.screens.ParamsScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/** Parameters tab: the screen shared with the desktop (ui/ParamsScreen). */
class ParamsFragment : TabFragment() {

    @Volatile private var controller: ParamsController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { VizTheme { ParamsScreen(tabId, onController = { controller = it }) } }
        }

    internal fun controllerForTest(): ParamsController? = controller

    companion object {
        const val KEY_NODE = PARAMS_KEY_NODE
        const val KEY_FILTER = PARAMS_KEY_FILTER
    }
}
