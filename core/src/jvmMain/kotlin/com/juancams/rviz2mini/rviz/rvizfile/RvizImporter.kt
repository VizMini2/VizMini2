// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.rvizfile

import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.QosPreset

/** What could be extracted from a .rviz. `view` uses the same keys as ViewController.saveTo. */
class RvizImport(
    val displays: List<DisplayConfig>,
    val fixedFrame: String?,
    val background: Int?,
    val view: Map<String, String>?,
    val goalTopic: String?,
    val poseTopic: String?,
    /** Displays that cannot be shown here (e.g. "Image (Camera)"). */
    val skipped: List<String>,
    val pointTopic: String? = null
)

/**
 * Converts an RViz 2 configuration file (.rviz) into app displays. Classes and property names
 * from rviz_default_plugins; anything without an equivalent is ignored and reported.
 */
object RvizImporter {

    /** `viewHeightPx`: viewer height, to convert the TopDownOrtho scale (px/m) to the app's. */
    fun import(text: String, viewHeightPx: Int = 1000): RvizImport {
        val root = MiniYaml.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("The file is not an RViz configuration")
        val vm = root["Visualization Manager"] as? Map<*, *> ?: throw IllegalArgumentException("Missing 'Visualization Manager': is this a .rviz file?")

        val displays = ArrayList<DisplayConfig>()
        val skipped = ArrayList<String>()
        collect(vm["Displays"] as? List<*>, true, displays, skipped)

        val global = vm["Global Options"] as? Map<*, *>
        val fixedFrame = str(global, "Fixed Frame")?.takeIf { it.isNotBlank() }
        val background = color(str(global, "Background Color"))

        var goal: String? = null
        var pose: String? = null
        var point: String? = null
        for (tool in (vm["Tools"] as? List<*>).orEmpty()) {
            val t = tool as? Map<*, *> ?: continue
            when (className(t)) {
                "SetGoal" -> goal = topic(t)
                "SetInitialPose" -> pose = topic(t)
                "PublishPoint" -> point = topic(t)
            }
        }

        val current = (vm["Views"] as? Map<*, *>)?.get("Current") as? Map<*, *>
        return RvizImport(displays, fixedFrame, background, current?.let { view(it, viewHeightPx) }, goal, pose, skipped, point)
    }

    private fun collect(list: List<*>?, parentEnabled: Boolean, out: MutableList<DisplayConfig>, skipped: MutableList<String>) {
        for (item in list.orEmpty()) {
            val d = item as? Map<*, *> ?: continue
            val cls = className(d)
            val name = str(d, "Name") ?: cls
            val enabled = parentEnabled && (bool(d, "Enabled") ?: bool(d, "Value") ?: true)
            if (cls == "Group") {
                collect(d["Displays"] as? List<*>, enabled, out, skipped)
                continue
            }
            val cfg = convert(cls, d, name, enabled)
            if (cfg == null) skipped.add(if (name == cls) cls else "$name ($cls)") else out.add(cfg)
        }
    }

    private fun convert(cls: String, d: Map<*, *>, name: String, enabled: Boolean): DisplayConfig? {
        val type = when (cls) {
            "Grid" -> DisplayType.GRID
            "TF" -> DisplayType.TF
            "Axes" -> DisplayType.AXES
            "RobotModel" -> DisplayType.ROBOT_MODEL
            "Map" -> DisplayType.MAP
            "LaserScan" -> DisplayType.LASER_SCAN
            "PointCloud2" -> DisplayType.POINT_CLOUD2
            "PointCloud" -> DisplayType.POINT_CLOUD
            "Odometry" -> DisplayType.ODOMETRY
            "Path" -> DisplayType.PATH
            "Pose" -> DisplayType.POSE
            "PoseWithCovariance" -> DisplayType.POSE_COV
            "PoseArray" -> DisplayType.POSE_ARRAY
            "PointStamped" -> DisplayType.POINT
            "Polygon" -> DisplayType.POLYGON
            "Marker" -> DisplayType.MARKER
            "MarkerArray" -> DisplayType.MARKER_ARRAY
            "Range" -> DisplayType.RANGE
            "Image" -> DisplayType.IMAGE
            "Camera" -> DisplayType.CAMERA
            "DepthCloud" -> DisplayType.DEPTH_CLOUD
            "Effort" -> DisplayType.EFFORT
            "GridCells" -> DisplayType.GRID_CELLS
            "Wrench" -> DisplayType.WRENCH
            "TwistStamped" -> DisplayType.TWIST
            "AccelStamped" -> DisplayType.ACCEL
            "Temperature" -> DisplayType.TEMPERATURE
            "Illuminance" -> DisplayType.ILLUMINANCE
            "FluidPressure" -> DisplayType.FLUID_PRESSURE
            "RelativeHumidity" -> DisplayType.RELATIVE_HUMIDITY
            "InteractiveMarkers" -> DisplayType.INTERACTIVE_MARKERS
            // MoveIt: the Trajectory display and the "Planned Path" part of MotionPlanning.
            "Trajectory", "MotionPlanning" -> DisplayType.TRAJECTORY
            "PlanningScene" -> DisplayType.PLANNING_SCENE
            // EasyNavigation's plugin (navmap_rviz_plugin).
            "NavMap", "NavMapDisplay" -> DisplayType.NAVMAP
            else -> return null
        }
        val topicMap = when (type) {
            DisplayType.ROBOT_MODEL -> d["Description Topic"]
            DisplayType.DEPTH_CLOUD -> d["Depth Map Topic"] ?: d["Topic"]
            DisplayType.TRAJECTORY -> d["Trajectory Topic"] ?: (d["Planned Path"] as? Map<*, *>)?.get("Trajectory Topic")
            DisplayType.PLANNING_SCENE -> (d["Scene Geometry"] as? Map<*, *>)?.get("Planning Scene Topic") ?: d["Planning Scene Topic"]
            else -> d["Topic"]
        }
        if (type == DisplayType.ROBOT_MODEL && str(d, "Description Source") == "File") return null
        val topic = when {
            // RViz2 stores the server namespace, not the topic: the update one is <ns>/update.
            type == DisplayType.INTERACTIVE_MARKERS -> str(d, "Interactive Markers Namespace")?.takeIf { it.isNotBlank() }
                ?.let { "/" + it.trim('/') + "/update" } ?: type.defaultTopic
            type.needsTopic -> topicValue(topicMap) ?: type.defaultTopic
            else -> ""
        }
        val p = LinkedHashMap<String, String>()
        fun num(key: String, rvizKey: String, src: Map<*, *>? = d) { str(src, rvizKey)?.toDoubleOrNull()?.let { p[key] = it.toString() } }
        fun flag(key: String, rvizKey: String, src: Map<*, *>? = d) { bool(src, rvizKey)?.let { p[key] = it.toString() } }
        fun col(key: String, rvizKey: String, src: Map<*, *>? = d) { color(str(src, rvizKey))?.let { p[key] = Colors.toHex(it) } }

        when (type) {
            DisplayType.GRID -> {
                num("cellSize", "Cell Size"); num("cellCount", "Plane Cell Count")
                col("color", "Color"); num("alpha", "Alpha")
                str(d, "Plane")?.takeIf { it in listOf("XY", "XZ", "YZ") }?.let { p["plane"] = it }
                frame(str(d, "Reference Frame"))?.let { p["frame"] = it }
                (d["Line Style"] as? Map<*, *>)?.let { ls ->
                    lineStyle(str(ls, "Value"))?.let { p["lineStyle"] = it }
                    num("lineWidth", "Line Width", ls)
                }
                (d["Offset"] as? Map<*, *>)?.let { o -> num("offsetX", "X", o); num("offsetY", "Y", o); num("offsetZ", "Z", o) }
            }
            DisplayType.TF -> {
                flag("showNames", "Show Names"); flag("showAxes", "Show Axes"); flag("showArrows", "Show Arrows")
                num("scale", "Marker Scale"); num("frameTimeout", "Frame Timeout")
            }
            DisplayType.AXES -> {
                frame(str(d, "Reference Frame"))?.let { p["frame"] = it }
                num("length", "Length"); num("radius", "Radius"); num("alpha", "Alpha")
            }
            // The RViz2 display is a separate panel: here, a corner window with its image.
            DisplayType.IMAGE, DisplayType.CAMERA -> {}
            DisplayType.DEPTH_CLOUD -> {
                num("alpha", "Alpha"); num("size", "Size (m)")
                topicValue(d["Color Image Topic"])?.let { p["colorTopic"] = it }
            }
            DisplayType.EFFORT -> { num("alpha", "Alpha"); num("width", "Width") }
            DisplayType.INTERACTIVE_MARKERS -> flag("showDescriptions", "Show Descriptions")
            DisplayType.PLANNING_SCENE -> (d["Scene Geometry"] as? Map<*, *>)?.let { g -> col("color", "Scene Color", g); num("alpha", "Scene Alpha", g) }
            DisplayType.TRAJECTORY -> {
                str(d, "Robot Description")?.takeIf { it.isNotBlank() }?.let { p["description"] = "/" + it.trimStart('/') }
                val planned = (d["Planned Path"] as? Map<*, *>) ?: d
                flag("loop", "Loop Animation", planned); col("color", "Robot Color", planned); num("alpha", "Robot Alpha", planned)
            }
            DisplayType.GRID_CELLS -> { col("color", "Color"); num("alpha", "Alpha") }
            DisplayType.NAVMAP -> {
                num("alpha", "Alpha"); flag("drawLinks", "Draw Links")
                str(d, "Layer")?.takeIf { it.isNotBlank() }?.let { p["layer"] = it }
                str(d, "Color Scheme")?.lowercase()?.takeIf { it in listOf("heat", "rainbow") }?.let { p["scheme"] = it }
                topicValue(d["Update Layer Topic"])?.let { p["layerTopic"] = it }
            }
            DisplayType.WRENCH -> {
                col("linearColor", "Force Color"); col("angularColor", "Torque Color")
                num("linearScale", "Force Arrow Scale"); num("angularScale", "Torque Arrow Scale"); num("alpha", "Alpha")
            }
            DisplayType.TWIST, DisplayType.ACCEL -> {
                col("linearColor", "Linear Color"); col("angularColor", "Angular Color")
                num("linearScale", "Linear Arrow Scale"); num("angularScale", "Angular Arrow Scale"); num("alpha", "Alpha")
            }
            DisplayType.TEMPERATURE, DisplayType.ILLUMINANCE, DisplayType.FLUID_PRESSURE, DisplayType.RELATIVE_HUMIDITY -> {
                num("size", "Size (m)"); num("alpha", "Alpha"); num("minValue", "Min Intensity"); num("maxValue", "Max Intensity")
            }
            DisplayType.ROBOT_MODEL -> {
                flag("visual", "Visual Enabled"); flag("collision", "Collision Enabled"); num("alpha", "Alpha")
            }
            DisplayType.MAP -> {
                str(d, "Color Scheme")?.lowercase()?.takeIf { it in listOf("map", "costmap", "raw") }?.let { p["scheme"] = it }
                num("alpha", "Alpha"); flag("drawBehind", "Draw Behind")
            }
            DisplayType.LASER_SCAN, DisplayType.POINT_CLOUD2, DisplayType.POINT_CLOUD -> {
                p["style"] = if (str(d, "Style") == "Spheres") "circles" else "squares"
                num("size", "Size (m)"); num("alpha", "Alpha"); num("decay", "Decay Time")
                col("color", "Color")
                val transformer = str(d, "Color Transformer")
                val axis = str(d, "Axis")?.lowercase()?.takeIf { it in listOf("x", "y", "z") } ?: "z"
                val mode = when (transformer) {
                    "Intensity" -> "intensity"
                    "AxisColor" -> axis
                    "FlatColor" -> "flat"
                    "RGB8", "RGBF32" -> if (type == DisplayType.POINT_CLOUD2) "rgb" else "auto"
                    else -> null
                }
                if (mode != null && (mode != "auto" || type != DisplayType.LASER_SCAN)) p["colorMode"] = mode
                flag("autoBounds", if (transformer == "AxisColor") "Autocompute Value Bounds" else "Autocompute Intensity Bounds")
                if (transformer == "AxisColor") {
                    num("minValue", "Min Value"); num("maxValue", "Max Value")
                } else {
                    num("minValue", "Min Intensity"); num("maxValue", "Max Intensity")
                }
            }
            DisplayType.ODOMETRY -> {
                num("keep", "Keep"); num("positionTolerance", "Position Tolerance"); num("angleTolerance", "Angle Tolerance")
                (d["Shape"] as? Map<*, *>)?.let { s -> shape(s, p) }
            }
            DisplayType.PATH -> {
                col("color", "Color"); num("alpha", "Alpha"); num("lineWidth", "Line Width")
                lineStyle(str(d, "Line Style"))?.let { p["lineStyle"] = it }
                when (str(d, "Pose Style")) { "Axes" -> p["poseStyle"] = "axes"; "Arrows" -> p["poseStyle"] = "arrows"; "None" -> p["poseStyle"] = "none" }
                (d["Offset"] as? Map<*, *>)?.let { o -> num("offsetZ", "Z", o) }
            }
            DisplayType.POSE, DisplayType.POSE_ARRAY -> shape(d, p)
            DisplayType.POSE_COV -> {
                shape(d, p)
                (d["Covariance"] as? Map<*, *>)?.let { c ->
                    flag("covariance", "Value", c)
                    (c["Position"] as? Map<*, *>)?.let { pos -> col("covColor", "Color", pos); num("covAlpha", "Alpha", pos) }
                }
            }
            DisplayType.POINT -> { col("color", "Color"); num("alpha", "Alpha"); num("radius", "Radius"); num("keep", "History Length") }
            DisplayType.POLYGON -> { col("color", "Color"); num("alpha", "Alpha") }
            DisplayType.RANGE -> { col("color", "Color"); num("alpha", "Alpha") }
            DisplayType.MARKER, DisplayType.MARKER_ARRAY -> {}
        }
        if (type.needsTopic && type.spec("qos") != null) qos(topicMap)?.let { preset ->
            p["qos"] = preset.name
            // And the standalone RViz 2 values that do not match the preset (Depth, History, Reliability, Durability).
            val m = topicMap as? Map<*, *>
            val base = com.juancams.rviz2mini.rviz.display.QosSettings.of(preset)
            str(m, "Depth")?.toIntOrNull()?.takeIf { it > 0 && it != base.depth }?.let { p[com.juancams.rviz2mini.rviz.display.QosSettings.DEPTH] = it.toString() }
            str(m, "History Policy")?.takeIf { it in com.juancams.rviz2mini.rviz.display.QosSettings.HISTORIES && it != base.history }?.let { p[com.juancams.rviz2mini.rviz.display.QosSettings.HISTORY] = it }
        }
        // Only properties that exist in the app (RViz ones we do not have are dropped).
        val props = p.filterKeys { type.spec(it) != null || it.startsWith("qos") }
        return DisplayConfig.create(type, name, topic).copy(enabled = enabled, props = props)
    }

    /** Pose/Odometry/PoseArray shape: "Shape" is the value (or a map with "Value" and the dimensions). */
    private fun shape(src: Map<*, *>, p: MutableMap<String, String>) {
        val shapeValue = (src["Shape"] as? String) ?: str(src, "Value")
        if (shapeValue != null) p["shape"] = if (shapeValue.startsWith("Axes")) "axes" else "arrow"
        color(str(src, "Color"))?.let { p["color"] = Colors.toHex(it) }
        str(src, "Alpha")?.toDoubleOrNull()?.let { p["alpha"] = it.toString() }
        for ((key, rviz) in listOf(
            "shaftLength" to "Shaft Length", "shaftRadius" to "Shaft Radius", "headLength" to "Head Length",
            "headRadius" to "Head Radius", "axesLength" to "Axes Length", "axesRadius" to "Axes Radius"
        )) str(src, rviz)?.toDoubleOrNull()?.let { p[key] = it.toString() }
        // PoseArray: "Arrow Length" instead of body + head (RViz splits it 77 % / 23 %).
        str(src, "Arrow Length")?.toDoubleOrNull()?.let { l -> p["shaftLength"] = (l * 0.77).toString(); p["headLength"] = (l * 0.23).toString() }
    }

    private fun view(v: Map<*, *>, viewHeightPx: Int): Map<String, String>? {
        val cls = className(v)
        val out = LinkedHashMap<String, String>()
        fun focal(m: Map<*, *>?) {
            m ?: return
            str(m, "X")?.let { out["fx"] = it }; str(m, "Y")?.let { out["fy"] = it }; str(m, "Z")?.let { out["fz"] = it }
        }
        when (cls) {
            "Orbit", "XYOrbit", "ThirdPersonFollower" -> {
                out["type"] = when (cls) { "XYOrbit" -> "XY_ORBIT"; "ThirdPersonFollower" -> "FOLLOW"; else -> "ORBIT" }
                str(v, "Distance")?.let { out["distance"] = it }
                str(v, "Yaw")?.let { out["yaw"] = it }
                str(v, "Pitch")?.let { out["pitch"] = it }
                focal(v["Focal Point"] as? Map<*, *>)
                if (cls == "ThirdPersonFollower") frame(str(v, "Target Frame"))?.let { out["follow"] = it }
            }
            "TopDownOrtho" -> {
                out["type"] = "TOP_DOWN"
                // RViz: Scale = pixels per meter. Here: meters that fit in the viewer height.
                str(v, "Scale")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { out["scale"] = (viewHeightPx / it).toString() }
                str(v, "X")?.let { out["fx"] = it }; str(v, "Y")?.let { out["fy"] = it }
                str(v, "Angle")?.let { out["angle"] = it }
            }
            "FPS" -> {
                out["type"] = "FPS"
                (v["Position"] as? Map<*, *>)?.let { m ->
                    str(m, "X")?.let { out["px"] = it }; str(m, "Y")?.let { out["py"] = it }; str(m, "Z")?.let { out["pz"] = it }
                }
                str(v, "Yaw")?.let { out["fpsYaw"] = it }
                // In RViz positive pitch looks down; here it looks up.
                str(v, "Pitch")?.toDoubleOrNull()?.let { out["fpsPitch"] = (-it).toString() }
            }
            else -> return null
        }
        return out
    }

    // ---------------------------------------------------------------- utilities

    /** "rviz_default_plugins/LaserScan" -> "LaserScan"; "rviz_common/Group" -> "Group". */
    private fun className(m: Map<*, *>): String = (m["Class"] as? String).orEmpty().substringAfterLast('/')

    private fun str(m: Map<*, *>?, key: String): String? = m?.get(key) as? String

    private fun bool(m: Map<*, *>?, key: String): Boolean? = when (str(m, key)?.lowercase()) {
        "true" -> true; "false" -> false; else -> null
    }

    /** RViz 2 topic ("Topic: {Value: /scan, ...}") or legacy ("Topic: /scan"). */
    private fun topicValue(t: Any?): String? = when (t) {
        is String -> t
        is Map<*, *> -> t["Value"] as? String
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    private fun topic(tool: Map<*, *>): String? = topicValue(tool["Topic"])

    private fun qos(t: Any?): QosPreset? {
        val m = t as? Map<*, *> ?: return null
        return when {
            str(m, "Durability Policy") == "Transient Local" -> QosPreset.LATCHED
            str(m, "Reliability Policy") == "Best Effort" -> QosPreset.SENSOR
            str(m, "Reliability Policy") == "Reliable" -> QosPreset.RELIABLE
            else -> null
        }
    }

    /** "<Fixed Frame>" means "the fixed frame": in the app it is the empty frame. */
    private fun frame(f: String?): String? = f?.trim()?.takeIf { it.isNotEmpty() && it != "<Fixed Frame>" }

    private fun lineStyle(v: String?): String? = when (v) { "Lines" -> "lines"; "Billboards" -> "billboards"; else -> null }

    /** "48; 48; 48" -> color. */
    fun color(s: String?): Int? {
        val parts = s?.split(';')?.map { it.trim().toIntOrNull() } ?: return null
        if (parts.size < 3 || parts.any { it == null }) return null
        return Colors.rgb(parts[0]!!, parts[1]!!, parts[2]!!)
    }
}
