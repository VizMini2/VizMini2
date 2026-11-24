// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.demo

import com.juancams.rviz2mini.buttons.ButtonKind
import com.juancams.rviz2mini.buttons.ButtonList
import com.juancams.rviz2mini.buttons.ButtonSpec
import com.juancams.rviz2mini.demo.DemoServices
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.BUTTONS_KEY
import com.juancams.rviz2mini.ui.screens.CAMERA_KEY_KIND
import com.juancams.rviz2mini.ui.screens.CAMERA_KEY_TOPIC
import com.juancams.rviz2mini.ui.screens.DASHBOARD_KEY_LAYOUT
import com.juancams.rviz2mini.ui.screens.INDICATORS_KEY
import com.juancams.rviz2mini.ui.screens.LIFECYCLE_KEY_NODES
import com.juancams.rviz2mini.ui.screens.PLOT_KEY_LAYOUT
import com.juancams.rviz2mini.ui.screens.SLIDERS_KEY
import org.json.JSONObject

/**
 * The demo's tabs (the same on the phone and on the PC): what the simulated robot ([com.juancams.rviz2mini.demo.DemoRobot])
 * publishes, in RViz, a dashboard and the other tabs, with sample buttons, sliders, indicators and plots.
 */
object DemoTabs {

    /** Sample tabs showing what the simulated robot publishes. */
    fun all(): List<TabSpec> {
        val displays = DisplayList.defaults() + listOf(
            DisplayConfig.create(DisplayType.MAP, "Map", "/map"),
            DisplayConfig.create(DisplayType.ROBOT_MODEL, "Robot", "/robot_description"),
            DisplayConfig.create(DisplayType.LASER_SCAN, "Laser", "/scan"),
            DisplayConfig.create(DisplayType.PATH, "Plan", "/plan"),
            DisplayConfig.create(DisplayType.ODOMETRY, "Odometry", "/odom"),
        )
        val rviz = TabSpec.create(TabType.RVIZ, tr("demo_tab_rviz"))
            .withSetting("displays", DisplayList.toJson(displays))
            .withSetting("fixedFrame", "map")
            .withSetting("view", JSONObject(mapOf("type" to "ORBIT", "distance" to "11.0", "yaw" to "-2.0",
                "pitch" to "0.95", "fx" to "5.0", "fy" to "4.0", "fz" to "0.0")).toString())
        // Sample dashboard: the same data on a single screen.
        val dash = TabSpec.create(TabType.DASHBOARD, tr("demo_tab_dashboard"))
        val widgets = listOf(
            rviz.copy(id = java.util.UUID.randomUUID().toString(), name = "3D"),
            TabSpec.create(TabType.CAMERA, TabType.CAMERA.label()).withSetting(CAMERA_KEY_TOPIC, "/camera/image_raw").withSetting(CAMERA_KEY_KIND, "RAW"),
            TabSpec.create(TabType.TELEOP, "Joystick"),
            TabSpec.create(TabType.DIAGNOSTICS, TabType.DIAGNOSTICS.label()).withSetting("mode", "DIAG"),
        ).map { it.withSetting(TabSpec.KEY_DASHBOARD, dash.id) }
        val layout = listOf(
            com.juancams.rviz2mini.dashboard.DashboardGrid.Item(widgets[0].id, 0, 0, 12, 8),
            com.juancams.rviz2mini.dashboard.DashboardGrid.Item(widgets[1].id, 0, 8, 6, 6),
            com.juancams.rviz2mini.dashboard.DashboardGrid.Item(widgets[2].id, 6, 8, 6, 6),
            com.juancams.rviz2mini.dashboard.DashboardGrid.Item(widgets[3].id, 0, 14, 12, 6),
        )
        val dashboard = dash.withSetting(DASHBOARD_KEY_LAYOUT, com.juancams.rviz2mini.dashboard.DashboardGrid.toJson(layout))
        return listOf(
            // 3/4 view of the whole room (10 × 8 m, centered at 5, 4).
            rviz,
            TabSpec.create(TabType.TELEOP, tr("demo_tab_drive")),
            TabSpec.create(TabType.CAMERA, TabType.CAMERA.label())
                .withSetting(CAMERA_KEY_TOPIC, "/camera/image_raw").withSetting(CAMERA_KEY_KIND, "RAW"),
            TabSpec.create(TabType.DIAGNOSTICS, TabType.DIAGNOSTICS.label()).withSetting("mode", "DIAG"),
            TabSpec.create(TabType.TOPICS, TabType.TOPICS.label()),
            TabSpec.create(TabType.BUTTONS, TabType.BUTTONS.label()).withSetting(BUTTONS_KEY, ButtonList.toJson(buttons())),
            TabSpec.create(TabType.SLIDERS, TabType.SLIDERS.label())
                .withSetting(SLIDERS_KEY, com.juancams.rviz2mini.sliders.SliderList.toJson(sliders())),
            TabSpec.create(TabType.INDICATORS, TabType.INDICATORS.label())
                .withSetting(INDICATORS_KEY, com.juancams.rviz2mini.indicators.IndicatorList.toJson(indicators())),
            TabSpec.create(TabType.GPS, TabType.GPS.label()),
            TabSpec.create(TabType.PLOT, TabType.PLOT.label())
                .withSetting(PLOT_KEY_LAYOUT, plot().toJson()),
            TabSpec.create(TabType.LIFECYCLE, TabType.LIFECYCLE.label()).withSetting(LIFECYCLE_KEY_NODES, DemoServices.CAMERA),
            // The demo's PlanSys2 (DemoPlanSys2): a cleaning robot, one plan after another.
            TabSpec.create(TabType.PLANSYS2, TabType.PLANSYS2.label()),
            dashboard,
        ) + widgets
    }

    /** Sample sliders: drive forward and turn the robot via /cmd_vel; on release they return to 0 (stop). */
    fun sliders() = listOf(
        com.juancams.rviz2mini.sliders.SliderSpec(label = tr("demo_slider_forward"), target = "/cmd_vel",
            type = "geometry_msgs/msg/Twist", field = "linear.x", min = -0.5, max = 0.5, step = 0.05, initial = 0.0,
            rateHz = 10.0, springBack = true, color = 2),
        com.juancams.rviz2mini.sliders.SliderSpec(label = tr("demo_slider_turn"), target = "/cmd_vel",
            type = "geometry_msgs/msg/Twist", field = "angular.z", min = -1.0, max = 1.0, step = 0.1, initial = 0.0,
            rateHz = 10.0, springBack = true, color = 1),
    )

    /** Sample plots: the robot's velocities over time and its trajectory. */
    fun plot(): com.juancams.rviz2mini.plot.PlotLayout {
        fun odom(id: String, field: String, label: String) = com.juancams.rviz2mini.plot.SeriesSpec(id = id, topic = "/odom",
            type = "nav_msgs/msg/Odometry", field = field, label = tr(label))
        return com.juancams.rviz2mini.plot.PlotLayout(listOf(
            com.juancams.rviz2mini.plot.PanelSpec(series = listOf(odom("v", "twist.twist.linear.x", "demo_plot_linear"),
                odom("w", "twist.twist.angular.z", "demo_plot_angular"))),
            com.juancams.rviz2mini.plot.PanelSpec(kind = com.juancams.rviz2mini.plot.PanelKind.XY, sameScale = true,
                series = listOf(odom("x", "pose.pose.position.x", "demo_plot_x"), odom("y", "pose.pose.position.y", "demo_plot_y"))),
        ), windowS = 30.0)
    }

    /** Sample indicators with the robot's battery, odometry and diagnostics. */
    fun indicators(): List<com.juancams.rviz2mini.indicators.IndicatorSpec> {
        fun ind(label: String, topic: String, type: String, field: String, kind: com.juancams.rviz2mini.indicators.IndicatorKind) =
            com.juancams.rviz2mini.indicators.IndicatorSpec(label = tr(label), topic = topic, type = type, field = field, kind = kind)
        val k = com.juancams.rviz2mini.indicators.IndicatorKind.entries.associateBy { it.name }
        return listOf(
            ind("demo_indicator_battery", "/battery_state", "sensor_msgs/msg/BatteryState", "percentage", k.getValue("GAUGE"))
                .copy(min = 0.0, max = 1.0, decimals = 2, color = 2),
            ind("demo_indicator_speed", "/odom", "nav_msgs/msg/Odometry", "twist.twist.linear.x", k.getValue("BAR"))
                .copy(min = -0.5, max = 0.5, unit = "m/s", color = 1),
            ind("demo_indicator_low_battery", "/battery_state", "sensor_msgs/msg/BatteryState", "percentage", k.getValue("LIGHT"))
                .copy(rule = com.juancams.rviz2mini.indicators.LightRule.BELOW, threshold = "0.3", onColor = 3, offColor = 2, decimals = 2),
            ind("demo_indicator_voltage", "/battery_state", "sensor_msgs/msg/BatteryState", "voltage", k.getValue("NUMBER"))
                .copy(unit = "V", decimals = 1, timeoutS = 3.0),
            ind("demo_indicator_status", "/diagnostics", "diagnostic_msgs/msg/DiagnosticArray", "status[0].message", k.getValue("TEXT")),
            ind("demo_indicator_position", "/odom", "nav_msgs/msg/Odometry", "pose.pose.position", k.getValue("TABLE")),
        )
    }

    /** Sample buttons using the robot's services, action and topics ([DemoServices]). */
    fun buttons() = listOf(
        ButtonSpec(label = tr("demo_button_home"), kind = ButtonKind.SERVICE, target = "${DemoServices.ROBOT}/return_home",
            type = "std_srvs/srv/Trigger", color = 1),
        ButtonSpec(label = tr("demo_button_stop"), kind = ButtonKind.PUBLISH, target = "/cmd_vel",
            type = "geometry_msgs/msg/Twist", values = "{}", color = 3),
        ButtonSpec(label = tr("demo_button_fibonacci"), kind = ButtonKind.ACTION, target = DemoServices.FIBONACCI,
            type = DemoServices.FIBONACCI_TYPE, values = """{"order":10}""", color = 5),
        ButtonSpec(label = "2 + 3", kind = ButtonKind.SERVICE, target = "/add_two_ints",
            type = "example_interfaces/srv/AddTwoInts", values = """{"a":2,"b":3}""", color = 2),
    )
}
