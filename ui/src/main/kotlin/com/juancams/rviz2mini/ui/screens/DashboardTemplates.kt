// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import com.juancams.rviz2mini.dashboard.DashboardGrid
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.indicators.IndicatorKind
import com.juancams.rviz2mini.indicators.IndicatorList
import com.juancams.rviz2mini.indicators.IndicatorSpec
import com.juancams.rviz2mini.plot.PanelKind
import com.juancams.rviz2mini.plot.PanelSpec
import com.juancams.rviz2mini.plot.PlotLayout
import com.juancams.rviz2mini.plot.SeriesSpec
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabType

/**
 * Dashboard templates (the same on the phone and on the PC): a dashboard with its widgets already placed and
 * configured. Topics are taken from the network if there is one of that type (the first with a publisher); otherwise, the
 * usual ones. [build] returns the dashboard first and then its widgets, ready to add.
 */
object DashboardTemplates {
    enum class Template(val titleKey: String, val summaryKey: String) {
        NAVIGATION("template_navigation", "template_navigation_summary"),
        CAMERA_ROBOT("template_camera_robot", "template_camera_robot_summary"),
        MONITORING("template_monitoring", "template_monitoring_summary"),
        SHOWCASE("template_showcase", "template_showcase_summary"),
        MANIPULATION("template_manipulation", "template_manipulation_summary"),
    }

    /** The first network topic of one of those types (with a publisher), or `default`. */
    private fun pick(default: String, vararg types: String): String =
        RosConnection.graph()?.topics(includeHidden = false)?.filter { it.publishers > 0 && it.types.any { t -> t in types } }
            ?.map { it.name }?.sortedWith(compareBy({ it != default }, { it.length }))?.firstOrNull() ?: default

    private fun d(type: DisplayType, name: String, topic: String = type.defaultTopic, vararg props: Pair<String, String>) =
        DisplayConfig.create(type, name, topic).let { c -> props.fold(c) { acc, (k, v) -> acc.with(k, v) } }

    private fun ind(label: String, topic: String, type: String, field: String, kind: IndicatorKind) = IndicatorSpec(label = label, topic = topic, type = type, field = field, kind = kind)
    private fun s(id: String, topic: String, type: String, field: String, label: String) = SeriesSpec(id = id, topic = topic, type = type, field = field, label = label)

    /**
     * The widget to view a topic dropped into a dashboard slot: image → camera; what RViz draws → a 3D view with
     * grid, TF and that display; a field or a number → a plot. Null if nothing can show it.
     */
    fun widgetFor(p: com.juancams.rviz2mini.dashboard.TopicDrop.Payload): TabSpec? {
        val short = p.topic.substringAfterLast('/').ifEmpty { p.topic }
        if (p.field == null) {
            if (p.type == "sensor_msgs/msg/Image" || p.type == "sensor_msgs/msg/CompressedImage")
                return TabSpec.create(TabType.CAMERA, short).withSetting(CAMERA_KEY_TOPIC, p.topic)
                    .withSetting(CAMERA_KEY_KIND, if (p.type.endsWith("CompressedImage")) "COMPRESSED" else "RAW")
            com.juancams.rviz2mini.rviz.display.DisplayFactory.displaysFor(p.topic, listOf(p.type)).firstOrNull()?.let { type ->
                val ds = listOf(d(DisplayType.GRID, "Grid"), d(DisplayType.TF, "TF", "", "scale" to "0.5"), d(type, short, p.topic))
                return TabSpec.create(TabType.RVIZ, short).withSetting("displays", DisplayList.toJson(ds))
            }
            if (p.type == "diagnostic_msgs/msg/DiagnosticArray") return TabSpec.create(TabType.DIAGNOSTICS, "Diagnostics").withSetting("mode", "DIAG")
        }
        val reg = com.juancams.rviz2mini.ros.dyn.Interfaces.get()
        val field = p.field ?: com.juancams.rviz2mini.dashboard.TopicDrop.guessField(reg, p.type) ?: return null
        return TabSpec.create(TabType.PLOT, short).withSetting(PLOT_KEY_LAYOUT, PlotLayout(listOf(
            PanelSpec(series = listOf(SeriesSpec(topic = p.topic, type = p.type, field = field, label = "$short.$field"))),
        ), windowS = 30.0).toJson())
    }

    fun build(t: Template): List<TabSpec> {
        val scan = pick("/scan", "sensor_msgs/msg/LaserScan")
        val map = pick("/map", "nav_msgs/msg/OccupancyGrid")
        val odom = pick("/odom", "nav_msgs/msg/Odometry")
        val plan = pick("/plan", "nav_msgs/msg/Path")
        val image = pick("/camera/image_raw", "sensor_msgs/msg/Image", "sensor_msgs/msg/CompressedImage")
        val points = pick("/points", "sensor_msgs/msg/PointCloud2")
        val dash = TabSpec.create(TabType.DASHBOARD, tr(t.titleKey))
        val placed = ArrayList<Pair<TabSpec, DashboardGrid.Item>>()
        fun add(spec: TabSpec, x: Int, y: Int, w: Int, h: Int) { placed += spec to DashboardGrid.Item(spec.id, x, y, w, h) }
        fun rviz(name: String, displays: List<DisplayConfig>) = TabSpec.create(TabType.RVIZ, name).withSetting("displays", DisplayList.toJson(displays)).withSetting("fixedFrame", "map")
        val camera = TabSpec.create(TabType.CAMERA, "Camera").withSetting(CAMERA_KEY_TOPIC, image)
            .withSetting(CAMERA_KEY_KIND, if (image.endsWith("/compressed")) "COMPRESSED" else "RAW")
        val joystick = TabSpec.create(TabType.TELEOP, "Joystick")
        val diagnostics = TabSpec.create(TabType.DIAGNOSTICS, "Diagnostics").withSetting("mode", "DIAG")
        val speed = ind("Speed", odom, "nav_msgs/msg/Odometry", "twist.twist.linear.x", IndicatorKind.BAR).copy(min = -1.0, max = 1.0, unit = "m/s", color = 2)
        val status = ind("Status", "/diagnostics", "diagnostic_msgs/msg/DiagnosticArray", "status[0].message", IndicatorKind.TEXT)
        val position = ind("Position", odom, "nav_msgs/msg/Odometry", "pose.pose.position", IndicatorKind.TABLE)
        val navDisplays = listOf(d(DisplayType.GRID, "Grid"), d(DisplayType.TF, "TF", "", "scale" to "0.6"), d(DisplayType.MAP, "Map", map),
            d(DisplayType.ROBOT_MODEL, "Robot", "/robot_description"), d(DisplayType.LASER_SCAN, "Laser", scan), d(DisplayType.PATH, "Plan", plan),
            d(DisplayType.ODOMETRY, "Odometry", odom), d(DisplayType.POLYGON, "Footprint", "/local_costmap/published_footprint"))
        when (t) {
            Template.NAVIGATION -> {
                add(rviz("Navigation", navDisplays), 0, 0, 8, 9)
                add(TabSpec.create(TabType.INDICATORS, "Robot").withSetting(INDICATORS_KEY, IndicatorList.toJson(listOf(speed, status, position))), 8, 0, 4, 6)
                add(diagnostics, 8, 6, 4, 5)
                add(joystick, 0, 6, 3, 3)
            }
            Template.CAMERA_ROBOT -> {
                add(camera, 0, 0, 8, 6)
                add(rviz("3D", listOf(d(DisplayType.GRID, "Grid"), d(DisplayType.TF, "TF", "", "scale" to "0.5"), d(DisplayType.ROBOT_MODEL, "Robot", "/robot_description"),
                    d(DisplayType.LASER_SCAN, "Laser", scan))), 8, 0, 4, 6)
                add(TabSpec.create(TabType.INDICATORS, "Robot").withSetting(INDICATORS_KEY, IndicatorList.toJson(listOf(speed, status))), 8, 6, 4, 4)
                add(joystick, 0, 6, 8, 5)
            }
            Template.MONITORING -> {
                add(diagnostics, 0, 0, 6, 7)
                add(TabSpec.create(TabType.PLOT, "Plots").withSetting(PLOT_KEY_LAYOUT, PlotLayout(listOf(
                    PanelSpec(series = listOf(s("v", odom, "nav_msgs/msg/Odometry", "twist.twist.linear.x", "linear x"), s("w", odom, "nav_msgs/msg/Odometry", "twist.twist.angular.z", "angular z"))),
                ), windowS = 30.0).toJson()), 6, 0, 6, 5)
                add(TabSpec.create(TabType.INDICATORS, "Robot").withSetting(INDICATORS_KEY, IndicatorList.toJson(listOf(speed, status, position))), 6, 5, 6, 4)
                add(TabSpec.create(TabType.NODE_GRAPH, "Node graph"), 0, 7, 6, 6)
                add(TabSpec.create(TabType.TF_TREE, "TF tree"), 6, 9, 6, 6)
            }
            Template.MANIPULATION -> {
                // The arm with its plan (ghost robot) and the MoveIt panel beside it; the fixed frame is the robot's.
                add(rviz("Arm", listOf(d(DisplayType.GRID, "Grid", "", "cellSize" to "0.25"), d(DisplayType.ROBOT_MODEL, "Robot", "/robot_description"),
                    d(DisplayType.TRAJECTORY, "Planned path"), d(DisplayType.PLANNING_SCENE, "Scene"))).withSetting("fixedFrame", ""), 0, 0, 7, 10)
                add(TabSpec.create(TabType.MOVEIT, "MoveIt"), 7, 0, 5, 10)
            }
            Template.SHOWCASE -> {
                val displays = navDisplays + listOf(
                    d(DisplayType.POINT_CLOUD2, "Points", points), d(DisplayType.POSE_ARRAY, "Particles", "/pose_array"), d(DisplayType.GRID_CELLS, "Grid cells", "/grid_cells"),
                    d(DisplayType.MARKER_ARRAY, "Markers", "/visualization_marker_array"), d(DisplayType.MARKER, "Marker", "/visualization_marker"),
                    d(DisplayType.RANGE, "Sonar", "/range"), d(DisplayType.WRENCH, "Wrench", "/wrench"),
                    d(DisplayType.IMAGE, "Camera", image, "corner" to "Top right", "size" to "0.28", "annotations" to "/camera/image_markers"))
                add(rviz("3D", displays), 0, 0, 8, 8)
                add(camera, 8, 0, 4, 4)
                add(TabSpec.create(TabType.INDICATORS, "Sensors").withSetting(INDICATORS_KEY, IndicatorList.toJson(listOf(
                    ind("Temperature", "/temperature", "sensor_msgs/msg/Temperature", "temperature", IndicatorKind.GAUGE).copy(min = 15.0, max = 30.0, unit = "°C", decimals = 1, color = 3),
                    ind("Humidity", "/humidity", "sensor_msgs/msg/RelativeHumidity", "relative_humidity", IndicatorKind.BAR).copy(min = 0.0, max = 1.0, decimals = 2, color = 1),
                    ind("Light", "/illuminance", "sensor_msgs/msg/Illuminance", "illuminance", IndicatorKind.NUMBER).copy(unit = "lx", decimals = 0),
                    ind("Pressure", "/pressure", "sensor_msgs/msg/FluidPressure", "fluid_pressure", IndicatorKind.NUMBER).copy(unit = "Pa", decimals = 0),
                    speed, status, position))), 8, 4, 4, 6)
                add(TabSpec.create(TabType.PLOT, "Plots").withSetting(PLOT_KEY_LAYOUT, PlotLayout(listOf(
                    PanelSpec(series = listOf(s("t", "/temperature", "sensor_msgs/msg/Temperature", "temperature", "temperature (°C)"),
                        s("f", "/wrench", "geometry_msgs/msg/WrenchStamped", "wrench.force.z", "force z (N)"))),
                    PanelSpec(kind = PanelKind.XY, sameScale = true, series = listOf(
                        s("x", odom, "nav_msgs/msg/Odometry", "pose.pose.position.x", "x"), s("y", odom, "nav_msgs/msg/Odometry", "pose.pose.position.y", "y"))),
                ), windowS = 30.0).toJson()), 0, 8, 8, 5)
                add(diagnostics, 8, 10, 4, 5)
                add(TabSpec.create(TabType.TF_TREE, "TF tree"), 0, 13, 4, 5)
                add(TabSpec.create(TabType.NODE_GRAPH, "Node graph"), 4, 13, 4, 5)
                add(joystick, 0, 5, 3, 3)
            }
        }
        val widgets = placed.map { it.first.withSetting(TabSpec.KEY_DASHBOARD, dash.id) }
        return listOf(dash.withSetting(DASHBOARD_KEY_LAYOUT, DashboardGrid.toJson(placed.map { it.second }))) + widgets
    }
}
