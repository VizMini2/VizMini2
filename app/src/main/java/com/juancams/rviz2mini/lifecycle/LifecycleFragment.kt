// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.lifecycle

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.params.NodeInspectorActivity
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.ui.screens.LIFECYCLE_KEY_NODES
import com.juancams.rviz2mini.ui.screens.LifecycleScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/** Lifecycle tab: the screen shared with the desktop (ui/LifecycleScreen); "Parameters and info" opens the inspector. */
class LifecycleFragment : TabFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { VizTheme { LifecycleScreen(tabId, onOpenNode = { NodeInspectorActivity.open(context, it) }) } }
        }

    companion object {
        /** The tab's nodes (one per line). */
        const val KEY_NODES = LIFECYCLE_KEY_NODES
    }
}
