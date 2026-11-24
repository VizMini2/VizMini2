// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.params.NodeInspectorActivity
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.GraphKind
import com.juancams.rviz2mini.ui.screens.GraphListScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/**
 * Nodes, Topics, Services and Actions tabs: the list shared with the desktop (ui/GraphListScreen). Tapping
 * a row opens its Android screen (echo and publish, call the service, send a goal, the
 * node inspector).
 */
class GraphListFragment : TabFragment() {

    private val kind: TabType get() = spec()?.type ?: TabType.NODES

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val graphKind = when (kind) {
                TabType.TOPICS -> GraphKind.TOPICS
                TabType.SERVICES -> GraphKind.SERVICES
                TabType.ACTIONS -> GraphKind.ACTIONS
                else -> GraphKind.NODES
            }
            setContent {
                VizTheme {
                    GraphListScreen(graphKind) { row ->
                        val ctx = context
                        when (graphKind) {
                            GraphKind.TOPICS -> TopicActivity.open(ctx, row.name, row.type)
                            GraphKind.SERVICES -> ServiceActivity.open(ctx, row.name, row.type)
                            GraphKind.ACTIONS -> ActionActivity.open(ctx, row.name, row.type)
                            GraphKind.NODES -> NodeInspectorActivity.open(ctx, row.name)
                        }
                    }
                }
            }
        }
}
