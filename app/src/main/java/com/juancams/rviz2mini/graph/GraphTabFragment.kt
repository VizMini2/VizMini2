// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.graph

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.params.NodeInspectorActivity
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.NodeGraphScreen
import com.juancams.rviz2mini.ui.screens.TfTreeScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/** Tab (or widget) with the node graph or the TF tree: the screens shared with the desktop. */
class GraphTabFragment : TabFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val tf = spec()?.type == TabType.TF_TREE
            setContent { VizTheme { if (tf) TfTreeScreen() else NodeGraphScreen(onOpenNode = { NodeInspectorActivity.open(context, it) }) } }
        }
}
