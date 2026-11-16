// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.rvizfile

import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayType

/**
 * An RViz view of the app as an RViz 2 `.rviz` file (the reverse of [RvizImporter]): the displays with their
 * rviz_default_plugins class, the topic with its QoS and its main properties, the Global Options, the
 * tools and the current view. It can be opened with `rviz2 -d file.rviz` and imported back here.
 */
object RvizExporter {

    private val CLASSES = mapOf(
        DisplayType.GRID to "Grid", DisplayType.TF to "TF", DisplayType.AXES to "Axes", DisplayType.ROBOT_MODEL to "RobotModel",
        DisplayType.MAP to "Map", DisplayType.LASER_SCAN to "LaserScan", DisplayType.POINT_CLOUD2 to "PointCloud2",
        DisplayType.POINT_CLOUD to "PointCloud", DisplayType.ODOMETRY to "Odometry", DisplayType.PATH to "Path", DisplayType.POSE to "Pose",
        DisplayType.POSE_COV to "PoseWithCovariance", DisplayType.POSE_ARRAY to "PoseArray", DisplayType.POINT to "PointStamped",
        DisplayType.POLYGON to "Polygon", DisplayType.MARKER to "Marker", DisplayType.MARKER_ARRAY to "MarkerArray", DisplayType.RANGE to "Range",
        DisplayType.IMAGE to "Image", DisplayType.CAMERA to "Camera", DisplayType.DEPTH_CLOUD to "DepthCloud", DisplayType.EFFORT to "Effort",
        DisplayType.GRID_CELLS to "GridCells", DisplayType.WRENCH to "Wrench", DisplayType.TWIST to "TwistStamped", DisplayType.ACCEL to "AccelStamped",
        DisplayType.TEMPERATURE to "Temperature", DisplayType.ILLUMINANCE to "Illuminance", DisplayType.FLUID_PRESSURE to "FluidPressure",
        DisplayType.RELATIVE_HUMIDITY to "RelativeHumidity", DisplayType.INTERACTIVE_MARKERS to "InteractiveMarkers",
        // Not one of RViz's own: EasyNavigation's plugin (navmap_rviz_plugin).
        DisplayType.NAVMAP to "navmap_rviz_plugin/NavMapDisplay",
    )

    fun export(displays: List<DisplayConfig>, fixedFrame: String?, background: Int, view: Map<String, String>?,
               goalTopic: String?, poseTopic: String?, pointTopic: String?, viewHeightPx: Int = 1000): String {
        val out = StringBuilder()
        fun line(indent: Int, text: String) { out.append(" ".repeat(indent)).append(text).append('\n') }
        line(0, "Panels:")
        line(2, "- Class: rviz_common/Displays")
        line(4, "Name: Displays")
        line(0, "Visualization Manager:")
        line(2, "Class: \"\"")
        line(2, "Displays:")
        for (c in displays) {
            val cls = CLASSES[c.type] ?: continue
            line(4, "- Class: " + if ('/' in cls) cls else "rviz_default_plugins/$cls")
            line(6, "Enabled: ${c.enabled}")
            line(6, "Name: ${q(c.name)}")
            line(6, "Value: ${c.enabled}")
            props(c).forEach { (k, v) -> line(6, "$k: $v") }
            if (c.type.needsTopic) {
                val topicKey = when (c.type) { DisplayType.ROBOT_MODEL -> "Description Topic"; DisplayType.DEPTH_CLOUD -> "Depth Map Topic"; else -> "Topic" }
                if (c.type == DisplayType.ROBOT_MODEL) line(6, "Description Source: Topic")
                if (c.type == DisplayType.INTERACTIVE_MARKERS) line(6, "Interactive Markers Namespace: ${q(c.topic.removeSuffix("/update"))}")
                else {
                    val qos = c.qosSettings()
                    line(6, "$topicKey:")
                    line(8, "Depth: ${qos.depth}")
                    line(8, "Durability Policy: ${qos.durability}")
                    line(8, "History Policy: ${qos.history}")
                    line(8, "Reliability Policy: ${qos.reliability}")
                    line(8, "Value: ${q(c.topic)}")
                }
            }
        }
        line(2, "Enabled: true")
        line(2, "Global Options:")
        line(4, "Background Color: ${rgb(background)}")
        line(4, "Fixed Frame: ${q(fixedFrame?.takeIf { it.isNotBlank() } ?: "map")}")
        line(4, "Frame Rate: 30")
        line(2, "Name: root")
        line(2, "Tools:")
        line(4, "- Class: rviz_default_plugins/Interact")
        line(4, "- Class: rviz_default_plugins/MoveCamera")
        line(4, "- Class: rviz_default_plugins/Select")
        line(4, "- Class: rviz_default_plugins/Measure")
        for ((cls, t) in listOf("SetInitialPose" to (poseTopic ?: "/initialpose"), "SetGoal" to (goalTopic ?: "/goal_pose"), "PublishPoint" to (pointTopic ?: "/clicked_point"))) {
            line(4, "- Class: " + if ('/' in cls) cls else "rviz_default_plugins/$cls")
            line(6, "Topic:")
            line(8, "Value: ${q(t)}")
        }
        line(2, "Value: true")
        line(2, "Views:")
        line(4, "Current:")
        viewLines(view, viewHeightPx).forEach { line(6, it) }
        line(4, "Saved: ~")
        return out.toString()
    }

    /** The main properties of each type, with the RViz 2 names. */
    private fun props(c: DisplayConfig): List<Pair<String, String>> {
        val p = ArrayList<Pair<String, String>>()
        fun num(rviz: String, key: String) { if (c.type.spec(key) != null) p += rviz to fmt(c.number(key)) }
        fun flag(rviz: String, key: String) { if (c.type.spec(key) != null) p += rviz to c.bool(key).toString() }
        fun col(rviz: String, key: String) { if (c.type.spec(key) != null) p += rviz to rgb(c.color(key)) }
        when (c.type) {
            DisplayType.GRID -> { num("Cell Size", "cellSize"); num("Plane Cell Count", "cellCount"); col("Color", "color"); num("Alpha", "alpha")
                p += "Plane" to c.string("plane").ifEmpty { "XY" }; p += "Reference Frame" to q(c.string("frame").ifEmpty { "<Fixed Frame>" }) }
            DisplayType.TF -> { flag("Show Names", "showNames"); flag("Show Axes", "showAxes"); flag("Show Arrows", "showArrows"); num("Marker Scale", "scale"); num("Frame Timeout", "frameTimeout") }
            DisplayType.AXES -> { num("Length", "length"); num("Radius", "radius"); num("Alpha", "alpha"); p += "Reference Frame" to q(c.string("frame").ifEmpty { "<Fixed Frame>" }) }
            DisplayType.ROBOT_MODEL -> { flag("Visual Enabled", "visual"); flag("Collision Enabled", "collision"); num("Alpha", "alpha") }
            DisplayType.MAP -> { p += "Color Scheme" to c.string("scheme").replaceFirstChar { it.uppercase() }; num("Alpha", "alpha"); flag("Draw Behind", "drawBehind") }
            DisplayType.LASER_SCAN, DisplayType.POINT_CLOUD2, DisplayType.POINT_CLOUD -> {
                p += "Style" to if (c.string("style") == "circles") "Spheres" else "Squares"
                num("Size (m)", "size"); num("Alpha", "alpha"); num("Decay Time", "decay"); col("Color", "color")
                val mode = c.string("colorMode")
                p += "Color Transformer" to when (mode) { "intensity" -> "Intensity"; "flat" -> "FlatColor"; "rgb" -> "RGB8"; "x", "y", "z" -> "AxisColor"; else -> "Intensity" }
                if (mode in listOf("x", "y", "z")) { p += "Axis" to mode.uppercase(); flag("Autocompute Value Bounds", "autoBounds"); num("Min Value", "minValue"); num("Max Value", "maxValue") }
                else { flag("Autocompute Intensity Bounds", "autoBounds"); num("Min Intensity", "minValue"); num("Max Intensity", "maxValue") }
            }
            DisplayType.ODOMETRY -> { num("Keep", "keep"); num("Position Tolerance", "positionTolerance"); num("Angle Tolerance", "angleTolerance") }
            DisplayType.PATH -> { col("Color", "color"); num("Alpha", "alpha"); num("Line Width", "lineWidth")
                p += "Line Style" to if (c.string("lineStyle") == "billboards") "Billboards" else "Lines" }
            DisplayType.POINT -> { col("Color", "color"); num("Alpha", "alpha"); num("Radius", "radius"); num("History Length", "keep") }
            DisplayType.POLYGON, DisplayType.RANGE, DisplayType.GRID_CELLS -> { col("Color", "color"); num("Alpha", "alpha") }
            DisplayType.WRENCH -> { col("Force Color", "linearColor"); col("Torque Color", "angularColor"); num("Force Arrow Scale", "linearScale"); num("Torque Arrow Scale", "angularScale"); num("Alpha", "alpha") }
            DisplayType.TWIST, DisplayType.ACCEL -> { col("Linear Color", "linearColor"); col("Angular Color", "angularColor"); num("Linear Arrow Scale", "linearScale"); num("Angular Arrow Scale", "angularScale"); num("Alpha", "alpha") }
            DisplayType.TEMPERATURE, DisplayType.ILLUMINANCE, DisplayType.FLUID_PRESSURE, DisplayType.RELATIVE_HUMIDITY -> {
                num("Size (m)", "size"); num("Alpha", "alpha"); num("Min Intensity", "minValue"); num("Max Intensity", "maxValue") }
            DisplayType.DEPTH_CLOUD -> { num("Alpha", "alpha"); num("Size (m)", "size") }
            DisplayType.EFFORT -> { num("Alpha", "alpha"); num("Width", "width") }
            DisplayType.INTERACTIVE_MARKERS -> flag("Show Descriptions", "showDescriptions")
            DisplayType.NAVMAP -> {
                num("Alpha", "alpha"); flag("Draw Links", "drawLinks")
                c.string("layer").takeIf { it.isNotBlank() }?.let { p += "Layer" to q(it) }
                // The plugin only has these two (viridis and flat are VizMini2's).
                when (c.string("scheme")) { "heat" -> p += "Color Scheme" to "Heat"; "rainbow" -> p += "Color Scheme" to "Rainbow" }
                c.string("layerTopic").takeIf { it.isNotBlank() }?.let { p += "Update Layer Topic" to q(it) }
            }
            else -> {}
        }
        return p
    }

    private fun viewLines(v: Map<String, String>?, viewHeightPx: Int): List<String> {
        val m = v.orEmpty()
        fun f(k: String, def: String = "0") = m[k]?.toDoubleOrNull()?.let { fmt(it) } ?: def
        val focal = listOf("Focal Point:", "  X: ${f("fx")}", "  Y: ${f("fy")}", "  Z: ${f("fz")}")
        return when (m["type"]) {
            "TOP_DOWN" -> listOf("Class: rviz_default_plugins/TopDownOrtho", "Name: Current View",
                "Scale: ${m["scale"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { fmt(viewHeightPx / it) } ?: "100"}",
                "X: ${f("fx")}", "Y: ${f("fy")}", "Angle: ${f("angle")}")
            "FPS" -> listOf("Class: rviz_default_plugins/FPS", "Name: Current View", "Position:", "  X: ${f("px")}", "  Y: ${f("py")}", "  Z: ${f("pz")}",
                "Yaw: ${f("fpsYaw")}", "Pitch: ${m["fpsPitch"]?.toDoubleOrNull()?.let { fmt(-it) } ?: "0"}")
            else -> {
                val cls = when (m["type"]) { "XY_ORBIT" -> "XYOrbit"; "FOLLOW" -> "ThirdPersonFollower"; else -> "Orbit" }
                listOf("Class: rviz_default_plugins/$cls", "Name: Current View", "Distance: ${f("distance", "10")}", "Yaw: ${f("yaw", "0.785")}",
                    "Pitch: ${f("pitch", "0.785")}") + focal + listOfNotNull(m["follow"]?.takeIf { cls == "ThirdPersonFollower" && it.isNotBlank() }?.let { "Target Frame: ${q(it)}" })
            }
        }
    }

    private fun rgb(c: Int) = "${(c shr 16) and 255}; ${(c shr 8) and 255}; ${c and 255}".let { q(it) }
    private fun fmt(d: Double): String = if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    /** In single quotes if needed (YAML). */
    private fun q(s: String): String = if (s.isEmpty() || s.any { it in ":#{}[],&*!|>'\"%@`" } || s.first().isWhitespace() || s.last().isWhitespace() || s.startsWith("-") || s.startsWith("<"))
        "'" + s.replace("'", "''") + "'" else s
}
