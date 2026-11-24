// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.GraphKind
import com.juancams.rviz2mini.ui.screens.GraphListScreen
import com.juancams.rviz2mini.ui.screens.LifecycleScreen
import com.juancams.rviz2mini.ui.screens.ParamsScreen
import com.juancams.rviz2mini.ui.screens.TeleopScreen
import com.juancams.rviz2mini.ui.screens.TopicEchoScreen

/** Tab types that already have a shared (Compose) screen: the same on Android and on desktop. */
val SHARED_TAB_TYPES = setOf(
    TabType.TOPICS, TabType.NODES, TabType.SERVICES, TabType.ACTIONS,
    TabType.TELEOP, TabType.TELEOP_DUAL, TabType.LIFECYCLE, TabType.PARAMETERS, TabType.MESSAGE, TabType.CAMERA, TabType.NODE_GRAPH, TabType.TF_TREE, TabType.BUTTONS, TabType.SLIDERS, TabType.INDICATORS, TabType.DIAGNOSTICS, TabType.DASHBOARD, TabType.PLOT, TabType.BEHAVIOR_TREE, TabType.GPS, TabType.BAGS, TabType.MOVEIT, TabType.CONTROLLERS, TabType.MAPS, TabType.ALERTS,
)

/** The screen of a tab. Those not yet moved to Compose say so (on Android the usual ones are still used). */
/** Screens provided by each platform (the PC: RViz with its Filament), ahead of the shared ones. */
object PlatformTabs {
    val screens = HashMap<TabType, @Composable (TabSpec, Modifier) -> Unit>()
}

@Composable
fun TabScreen(spec: TabSpec, modifier: Modifier = Modifier) {
    PlatformTabs.screens[spec.type]?.let { it(spec, modifier); return }
    when (spec.type) {
        TabType.TOPICS -> ListWithDetail(GraphKind.TOPICS, modifier) { row -> TopicEchoScreen(row.name, row.type) }
        TabType.NODES -> ListWithDetail(GraphKind.NODES, modifier) { row -> com.juancams.rviz2mini.ui.screens.NodeInspectorScreen(row.name) }
        TabType.SERVICES -> ListWithDetail(GraphKind.SERVICES, modifier) { row -> com.juancams.rviz2mini.ui.screens.ServiceCallScreen(row.name, row.type) }
        TabType.ACTIONS -> ListWithDetail(GraphKind.ACTIONS, modifier) { row -> com.juancams.rviz2mini.ui.screens.ActionGoalScreen(row.name, row.type) }
        TabType.TELEOP -> TeleopScreen(spec.id, modifier, compact = spec.isWidget)
        TabType.TELEOP_DUAL -> TeleopScreen(spec.id, modifier, dual = true, compact = spec.isWidget)
        TabType.MESSAGE -> com.juancams.rviz2mini.ui.screens.MessageScreen(spec.id, modifier)
        TabType.CAMERA -> com.juancams.rviz2mini.ui.screens.CameraScreen(spec.id, modifier, compact = spec.isWidget)
        TabType.NODE_GRAPH -> com.juancams.rviz2mini.ui.screens.NodeGraphScreen(modifier)
        TabType.BUTTONS -> com.juancams.rviz2mini.ui.screens.ButtonsScreen(spec.id, modifier, widget = spec.isWidget)
        TabType.SLIDERS -> com.juancams.rviz2mini.ui.screens.SlidersScreen(spec.id, modifier, widget = spec.isWidget)
        TabType.INDICATORS -> com.juancams.rviz2mini.ui.screens.IndicatorsScreen(spec.id, modifier, widget = spec.isWidget)
        TabType.DIAGNOSTICS -> com.juancams.rviz2mini.ui.screens.DiagnosticsScreen(spec.id, modifier)
        TabType.PLOT -> com.juancams.rviz2mini.ui.screens.PlotScreen(spec.id, modifier)
        TabType.BEHAVIOR_TREE -> com.juancams.rviz2mini.ui.screens.BehaviorTreeScreen(spec.id, modifier)
        TabType.GPS -> com.juancams.rviz2mini.ui.screens.GpsScreen(spec.id, modifier)
        TabType.BAGS -> com.juancams.rviz2mini.ui.screens.BagsScreen(spec.id, modifier)
        TabType.DASHBOARD -> com.juancams.rviz2mini.ui.screens.DashboardScreen(spec.id, modifier, canAdd = SHARED_TAB_TYPES + PlatformTabs.screens.keys) { w, m -> TabScreen(w, m) }
        TabType.TF_TREE -> com.juancams.rviz2mini.ui.screens.TfTreeScreen(modifier)
        TabType.LIFECYCLE -> {
            // "Parameters and info" opens the node's inspector inside the tab, with an arrow back (the phone opens its own screen).
            var node by remember(spec.id) { mutableStateOf<String?>(null) }
            val n = node
            if (n == null) LifecycleScreen(spec.id, modifier, onOpenNode = { node = it })
            else Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
                IconButton(onClick = { node = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("params_back")) }
                com.juancams.rviz2mini.ui.screens.NodeInspectorScreen(n)
            }
        }
        TabType.PARAMETERS -> ParamsScreen(spec.id, modifier)
        TabType.MOVEIT -> com.juancams.rviz2mini.ui.screens.MoveItScreen(spec.id, modifier)
        TabType.CONTROLLERS -> com.juancams.rviz2mini.ui.screens.ControllersScreen(spec.id, modifier)
        TabType.MAPS -> com.juancams.rviz2mini.ui.screens.MapsScreen(spec.id, modifier)
        TabType.ALERTS -> com.juancams.rviz2mini.ui.screens.AlertsScreen(spec.id, modifier)
        else -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(tr("desktop_tab_not_yet", spec.type.label()), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A graph list and, when a row is tapped, its detail (echo, call the service, send a goal), with an arrow to go back. */
@Composable
private fun ListWithDetail(kind: GraphKind, modifier: Modifier, detail: @Composable (com.juancams.rviz2mini.ui.screens.GraphRow) -> Unit) {
    var open by remember { mutableStateOf<com.juancams.rviz2mini.ui.screens.GraphRow?>(null) }
    val o = open
    if (o == null) GraphListScreen(kind, modifier) { row -> open = row }
    else Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
        IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("params_back")) }
        detail(o)
    }
}
