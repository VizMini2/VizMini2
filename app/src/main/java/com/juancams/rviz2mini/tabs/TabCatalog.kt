// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.tabs

import androidx.annotation.StringRes
import com.juancams.rviz2mini.R

/**
 * What is shown for each tab type when creating it: short description (list), long
 * explanation (card), the sample screenshot and its video. The texts are resources (strings.xml).
 */
object TabCatalog {

    enum class Group(@StringRes val label: Int) {
        VISUALIZE(R.string.tab_catalog_group_visualize), CONTROL(R.string.tab_catalog_group_control),
        INSPECT(R.string.tab_catalog_group_inspect), RECORD(R.string.tab_catalog_group_record)
    }

    class Entry(
        val type: TabType,
        val group: Group,
        @StringRes val summary: Int,
        val details: List<Int>,
        val image: Int,
        val icon: Int,
        /** Short video of the tab with the demo robot (res/raw/clip_*.mp4). */
        @androidx.annotation.RawRes val video: Int
    )

    val entries: List<Entry> = listOf(
        Entry(TabType.DASHBOARD, Group.VISUALIZE, R.string.tab_catalog_dashboard_summary,
            listOf(R.string.tab_catalog_dashboard_detail1,
                R.string.tab_catalog_dashboard_detail2,
                R.string.tab_catalog_dashboard_detail3),
            R.drawable.shot_dashboard, R.drawable.ic_dashboard, R.raw.clip_dashboard),
        Entry(TabType.RVIZ, Group.VISUALIZE, R.string.tab_catalog_rviz_summary,
            listOf(R.string.tab_catalog_rviz_detail1,
                R.string.tab_catalog_rviz_detail2,
                R.string.tab_catalog_rviz_detail3,
                R.string.tab_catalog_rviz_detail4),
            R.drawable.shot_rviz, R.drawable.ic_rviz, R.raw.clip_rviz),
        Entry(TabType.CAMERA, Group.VISUALIZE, R.string.tab_catalog_camera_summary,
            listOf(R.string.tab_catalog_camera_detail1,
                R.string.tab_catalog_camera_detail2,
                R.string.tab_catalog_camera_detail3),
            R.drawable.shot_camera, R.drawable.ic_camera, R.raw.clip_camera),
        Entry(TabType.TELEOP, Group.CONTROL, R.string.tab_catalog_teleop_summary,
            listOf(R.string.tab_catalog_teleop_detail1,
                R.string.tab_catalog_teleop_detail2,
                R.string.tab_catalog_teleop_detail3,
                R.string.tab_catalog_teleop_detail4),
            R.drawable.shot_teleop, R.drawable.ic_gamepad, R.raw.clip_teleop),
        Entry(TabType.TELEOP_DUAL, Group.CONTROL, R.string.tab_catalog_teleop_dual_summary,
            listOf(R.string.tab_catalog_teleop_dual_detail1,
                R.string.tab_catalog_teleop_dual_detail2),
            R.drawable.shot_teleop_dual, R.drawable.ic_gamepad, R.raw.clip_teleop_dual),
        Entry(TabType.BUTTONS, Group.CONTROL, R.string.tab_catalog_buttons_summary,
            listOf(R.string.tab_catalog_buttons_detail1,
                R.string.tab_catalog_buttons_detail2,
                R.string.tab_catalog_buttons_detail3),
            R.drawable.shot_buttons, R.drawable.ic_touch, R.raw.clip_buttons),
        Entry(TabType.SLIDERS, Group.CONTROL, R.string.tab_catalog_sliders_summary,
            listOf(R.string.tab_catalog_sliders_detail1,
                R.string.tab_catalog_sliders_detail2,
                R.string.tab_catalog_sliders_detail3),
            R.drawable.shot_sliders, R.drawable.ic_tune, R.raw.clip_sliders),
        Entry(TabType.INDICATORS, Group.VISUALIZE, R.string.tab_catalog_indicators_summary,
            listOf(R.string.tab_catalog_indicators_detail1,
                R.string.tab_catalog_indicators_detail2,
                R.string.tab_catalog_indicators_detail3),
            R.drawable.shot_indicators, R.drawable.ic_speed, R.raw.clip_indicators),
        Entry(TabType.PLOT, Group.VISUALIZE, R.string.tab_catalog_plot_summary,
            listOf(R.string.tab_catalog_plot_detail1,
                R.string.tab_catalog_plot_detail2,
                R.string.tab_catalog_plot_detail3),
            R.drawable.shot_plot, R.drawable.ic_show_chart, R.raw.clip_plot),
        Entry(TabType.GPS, Group.VISUALIZE, R.string.tab_catalog_gps_summary,
            listOf(R.string.tab_catalog_gps_detail1,
                R.string.tab_catalog_gps_detail2),
            R.drawable.shot_gps, R.drawable.ic_map, R.raw.clip_gps),
        Entry(TabType.BEHAVIOR_TREE, Group.VISUALIZE, R.string.tab_catalog_behavior_tree_summary,
            listOf(R.string.tab_catalog_behavior_tree_detail1,
                R.string.tab_catalog_behavior_tree_detail2),
            R.drawable.shot_behavior_tree, R.drawable.ic_account_tree, R.raw.clip_behavior_tree),
        Entry(TabType.MESSAGE, Group.INSPECT, R.string.tab_catalog_message_summary,
            listOf(R.string.tab_catalog_message_detail1, R.string.tab_catalog_message_detail2),
            R.drawable.shot_message, R.drawable.ic_data_object, R.raw.clip_message),
        Entry(TabType.PARAMETERS, Group.CONTROL, R.string.tab_catalog_parameters_summary,
            listOf(R.string.tab_catalog_parameters_detail1, R.string.tab_catalog_parameters_detail2),
            R.drawable.shot_parameters, R.drawable.ic_settings, R.raw.clip_parameters),
        Entry(TabType.DIAGNOSTICS, Group.INSPECT, R.string.tab_catalog_diagnostics_summary,
            listOf(R.string.tab_catalog_diagnostics_detail1,
                R.string.tab_catalog_diagnostics_detail2,
                R.string.tab_catalog_diagnostics_detail3),
            R.drawable.shot_diagnostics, R.drawable.ic_monitor_heart, R.raw.clip_diagnostics),
        Entry(TabType.LIFECYCLE, Group.CONTROL, R.string.tab_catalog_lifecycle_summary,
            listOf(R.string.tab_catalog_lifecycle_detail1,
                R.string.tab_catalog_lifecycle_detail2),
            R.drawable.shot_lifecycle, R.drawable.ic_lifecycle, R.raw.clip_lifecycle),
        Entry(TabType.MOVEIT, Group.CONTROL, R.string.tab_catalog_moveit_summary,
            listOf(R.string.tab_catalog_moveit_detail1, R.string.tab_catalog_moveit_detail2, R.string.tab_catalog_moveit_detail3),
            R.drawable.shot_moveit, R.drawable.ic_precision_manufacturing, R.raw.clip_moveit),
        Entry(TabType.CONTROLLERS, Group.CONTROL, R.string.tab_catalog_controllers_summary,
            listOf(R.string.tab_catalog_controllers_detail1, R.string.tab_catalog_controllers_detail2, R.string.tab_catalog_controllers_detail3),
            R.drawable.shot_controllers, R.drawable.ic_developer_board, R.raw.clip_controllers),
        Entry(TabType.MAPS, Group.CONTROL, R.string.tab_catalog_maps_summary,
            listOf(R.string.tab_catalog_maps_detail1, R.string.tab_catalog_maps_detail2, R.string.tab_catalog_maps_detail3),
            R.drawable.shot_maps, R.drawable.ic_layers, R.raw.clip_maps),
        Entry(TabType.ALERTS, Group.INSPECT, R.string.tab_catalog_alerts_summary,
            listOf(R.string.tab_catalog_alerts_detail1, R.string.tab_catalog_alerts_detail2, R.string.tab_catalog_alerts_detail3),
            R.drawable.shot_alerts, R.drawable.ic_notifications_active, R.raw.clip_alerts),
        Entry(TabType.NODE_GRAPH, Group.INSPECT, R.string.tab_catalog_node_graph_summary,
            listOf(R.string.tab_catalog_node_graph_detail1, R.string.tab_catalog_node_graph_detail2, R.string.tab_catalog_node_graph_detail3),
            R.drawable.shot_node_graph, R.drawable.ic_lan, R.raw.clip_node_graph),
        Entry(TabType.TF_TREE, Group.INSPECT, R.string.tab_catalog_tf_tree_summary,
            listOf(R.string.tab_catalog_tf_tree_detail1, R.string.tab_catalog_tf_tree_detail2),
            R.drawable.shot_tf_tree, R.drawable.ic_tf_tree, R.raw.clip_tf_tree),
        Entry(TabType.NODES, Group.INSPECT, R.string.tab_catalog_nodes_summary,
            listOf(R.string.tab_catalog_nodes_detail1,
                R.string.tab_catalog_nodes_detail2),
            R.drawable.shot_nodes, R.drawable.ic_hub, R.raw.clip_nodes),
        Entry(TabType.TOPICS, Group.INSPECT, R.string.tab_catalog_topics_summary,
            listOf(R.string.tab_catalog_topics_detail1),
            R.drawable.shot_topics, R.drawable.ic_wifi_tethering, R.raw.clip_topics),
        Entry(TabType.SERVICES, Group.INSPECT, R.string.tab_catalog_services_summary,
            listOf(R.string.tab_catalog_services_detail1),
            R.drawable.shot_services, R.drawable.ic_touch, R.raw.clip_services),
        Entry(TabType.ACTIONS, Group.INSPECT, R.string.tab_catalog_actions_summary,
            listOf(R.string.tab_catalog_actions_detail1),
            R.drawable.shot_actions, R.drawable.ic_play, R.raw.clip_actions),
        Entry(TabType.BAGS, Group.RECORD, R.string.tab_catalog_bags_summary,
            listOf(R.string.tab_catalog_bags_detail1,
                R.string.tab_catalog_bags_detail2,
                R.string.tab_catalog_bags_detail3),
            R.drawable.shot_bags, R.drawable.ic_bag, R.raw.clip_bags),
    )

    fun of(type: TabType): Entry = entries.first { it.type == type }
}
