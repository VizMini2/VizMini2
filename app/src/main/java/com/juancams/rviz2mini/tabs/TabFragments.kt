// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.tabs

import com.juancams.rviz2mini.R

/** Creates the fragment for each tab type (also dashboard widgets) and its icon. */
object TabFragments {

    fun create(spec: TabSpec): TabFragment {
        val fragment: TabFragment = when (spec.type) {
            TabType.RVIZ -> com.juancams.rviz2mini.rviz.RvizFragment()
            TabType.CAMERA -> com.juancams.rviz2mini.camera.CameraFragment()
            TabType.TELEOP, TabType.TELEOP_DUAL -> com.juancams.rviz2mini.teleop.TeleopFragment()
            TabType.DIAGNOSTICS -> com.juancams.rviz2mini.diag.DiagnosticsFragment()
            TabType.BUTTONS -> com.juancams.rviz2mini.buttons.ButtonsFragment()
            TabType.SLIDERS -> com.juancams.rviz2mini.sliders.SlidersFragment()
            TabType.INDICATORS -> com.juancams.rviz2mini.indicators.IndicatorsFragment()
            TabType.PLOT -> com.juancams.rviz2mini.plot.PlotFragment()
            TabType.GPS -> com.juancams.rviz2mini.gps.GpsFragment()
            TabType.BEHAVIOR_TREE -> com.juancams.rviz2mini.bt.BehaviorTreeFragment()
            TabType.MESSAGE -> com.juancams.rviz2mini.explore.MessageFragment()
            TabType.PARAMETERS -> com.juancams.rviz2mini.params.ParamsFragment()
            TabType.LIFECYCLE -> com.juancams.rviz2mini.lifecycle.LifecycleFragment()
            TabType.BAGS -> com.juancams.rviz2mini.bag.BagsFragment()
            TabType.NODES, TabType.TOPICS, TabType.SERVICES, TabType.ACTIONS -> com.juancams.rviz2mini.explore.GraphListFragment()
            TabType.DASHBOARD -> com.juancams.rviz2mini.dashboard.DashboardFragment()
            TabType.NODE_GRAPH, TabType.TF_TREE -> com.juancams.rviz2mini.graph.GraphTabFragment()
            TabType.MOVEIT -> com.juancams.rviz2mini.moveit.MoveItFragment()
            TabType.CONTROLLERS -> com.juancams.rviz2mini.control.ControllersFragment()
            TabType.MAPS -> com.juancams.rviz2mini.control.SharedScreenFragment.of(TabType.MAPS)
            TabType.ALERTS -> com.juancams.rviz2mini.control.SharedScreenFragment.of(TabType.ALERTS)
        }
        return TabFragment.withTabId(fragment, spec.id)
    }

    fun icon(type: TabType): Int = when (type) {
        TabType.RVIZ -> R.drawable.ic_rviz
        TabType.CAMERA -> R.drawable.ic_camera
        TabType.TELEOP, TabType.TELEOP_DUAL -> R.drawable.ic_gamepad
        TabType.DIAGNOSTICS -> R.drawable.ic_monitor_heart
        TabType.BUTTONS -> R.drawable.ic_touch
        TabType.SLIDERS -> R.drawable.ic_tune
        TabType.INDICATORS -> R.drawable.ic_speed
        TabType.PLOT -> R.drawable.ic_show_chart
        TabType.GPS -> R.drawable.ic_map
        TabType.BEHAVIOR_TREE -> R.drawable.ic_account_tree
        TabType.MESSAGE -> R.drawable.ic_data_object
        TabType.PARAMETERS -> R.drawable.ic_settings
        TabType.LIFECYCLE -> R.drawable.ic_lifecycle
        TabType.BAGS -> R.drawable.ic_bag
        TabType.NODES -> R.drawable.ic_hub
        TabType.TOPICS -> R.drawable.ic_wifi_tethering
        TabType.SERVICES -> R.drawable.ic_touch
        TabType.ACTIONS -> R.drawable.ic_play
        TabType.DASHBOARD -> R.drawable.ic_dashboard
        TabType.NODE_GRAPH -> R.drawable.ic_lan
        TabType.TF_TREE -> R.drawable.ic_tf_tree
        TabType.MOVEIT -> R.drawable.ic_precision_manufacturing
        TabType.CONTROLLERS -> R.drawable.ic_developer_board
        TabType.MAPS -> R.drawable.ic_layers
        TabType.ALERTS -> R.drawable.ic_notifications_active
    }
}
