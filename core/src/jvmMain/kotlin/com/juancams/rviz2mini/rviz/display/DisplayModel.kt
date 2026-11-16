// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.display

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** How a display subscribes (the names match RViz's QoS options). */
enum class QosPreset(val label: String) {
    SENSOR("Best effort"),
    RELIABLE("Reliable"),
    LATCHED("Transient local");
}

/** Definition of an editable property. The edit screen generates them automatically. */
sealed class PropertySpec(val key: String, val label: String) {
    abstract val defaultValue: String

    class Bool(key: String, label: String, val default: Boolean) : PropertySpec(key, label) {
        override val defaultValue = default.toString()
    }

    /** `textEntry` = text field instead of a slider (large ranges: color limits, offsets). */
    class Number(
        key: String, label: String, val default: Double,
        val min: Double, val max: Double, val step: Double, val unit: String = "", val textEntry: Boolean = false
    ) : PropertySpec(key, label) {
        override val defaultValue = default.toString()
        fun clamp(v: Double) = v.coerceIn(min, max)
    }

    class Choice(key: String, label: String, val options: List<String>, val default: String) : PropertySpec(key, label) {
        override val defaultValue = default
    }

    class Color(key: String, label: String, val default: Int) : PropertySpec(key, label) {
        override val defaultValue = Colors.toHex(default)
    }

    /** Free text (e.g. mesh server URL). */
    class Text(key: String, label: String, val default: String, val hint: String = "") : PropertySpec(key, label) {
        override val defaultValue = default
    }

    /** Name of a TF frame. */
    class Frame(key: String, label: String, val default: String) : PropertySpec(key, label) {
        override val defaultValue = default
    }

    /**
     * TF frames that are not drawn: one per line, the name or a pattern with `*` (e.g. `*wheel*`). The editor
     * shows the incoming frames with a checkbox for each one.
     */
    class FrameSet(key: String, label: String) : PropertySpec(key, label) {
        override val defaultValue = ""
    }
}

private val QOS_SPEC_SENSOR = PropertySpec.Choice("qos", "QoS", QosPreset.entries.map { it.name }, QosPreset.SENSOR.name)
private val QOS_SPEC_RELIABLE = PropertySpec.Choice("qos", "QoS", QosPreset.entries.map { it.name }, QosPreset.RELIABLE.name)
private val QOS_SPEC_LATCHED = PropertySpec.Choice("qos", "QoS", QosPreset.entries.map { it.name }, QosPreset.LATCHED.name)

// Common property blocks (same names and default values as RViz 2).
private object P {
    fun alpha(default: Double = 1.0) = PropertySpec.Number("alpha", "Alpha", default, 0.0, 1.0, 0.05)
    fun color(default: Int, label: String = "Color") = PropertySpec.Color("color", label, default)
    val lineStyle = PropertySpec.Choice("lineStyle", "Line Style", listOf("lines", "billboards"), "lines")
    fun lineWidth(default: Double = 0.03) = PropertySpec.Number("lineWidth", "Line Width", default, 0.005, 1.0, 0.005, "m")
    val pointStyle = PropertySpec.Choice("style", "Style", listOf("squares", "circles"), "squares")
    fun pointSize(default: Double) = PropertySpec.Number("size", "Size (m)", default, 0.005, 1.0, 0.005, "m")
    val decay = PropertySpec.Number("decay", "Decay Time", 0.0, 0.0, 30.0, 0.5, "s")
    val autoBounds = PropertySpec.Bool("autoBounds", "Autocompute Value Bounds", true)
    val minValue = PropertySpec.Number("minValue", "Min Value", 0.0, -100000.0, 100000.0, 0.01, textEntry = true)
    val maxValue = PropertySpec.Number("maxValue", "Max Value", 4096.0, -100000.0, 100000.0, 0.01, textEntry = true)
    val shape = PropertySpec.Choice("shape", "Shape", listOf("arrow", "axes"), "arrow")
    fun arrow(shaft: Double, shaftR: Double, head: Double, headR: Double) = listOf(
        PropertySpec.Number("shaftLength", "Shaft Length", shaft, 0.01, 10.0, 0.01, "m"),
        PropertySpec.Number("shaftRadius", "Shaft Radius", shaftR, 0.005, 2.0, 0.005, "m"),
        PropertySpec.Number("headLength", "Head Length", head, 0.01, 5.0, 0.01, "m"),
        PropertySpec.Number("headRadius", "Head Radius", headR, 0.005, 2.0, 0.005, "m")
    )
    fun axes(length: Double, radius: Double) = listOf(
        PropertySpec.Number("axesLength", "Axes Length", length, 0.01, 10.0, 0.01, "m"),
        PropertySpec.Number("axesRadius", "Axes Radius", radius, 0.005, 2.0, 0.005, "m")
    )
    val annotations = PropertySpec.Text("annotations", "Annotations Topic", "", "ImageMarker or foxglove ImageAnnotations (optional)")
    val showAnnotations = PropertySpec.Bool("showAnnotations", "Show Annotations", true)
    /** Where the panel was dragged to, as a fraction of the view (-1: in its corner). */
    val panelX = PropertySpec.Number("x", "Left (fraction, -1 = corner)", -1.0, -1.0, 1.0, 0.01)
    val panelY = PropertySpec.Number("y", "Top (fraction, -1 = corner)", -1.0, -1.0, 1.0, 0.01)
    fun offset(axis: String) = PropertySpec.Number("offset$axis", "Offset $axis", 0.0, -1000.0, 1000.0, 0.01, "m", textEntry = true)
}

/**
 * Display types, as in RViz. Empty `rosType` = needs no topic (Grid, TF, Axes).
 * Properties and their default values follow RViz 2.
 */
enum class DisplayType(
    val label: String,
    val description: String,
    val rosType: String,
    val defaultTopic: String,
    val commonTopics: List<String>,
    val properties: List<PropertySpec>
) {
    GRID(
        "Grid", "Reference grid", "", "", emptyList(),
        listOf(
            PropertySpec.Frame("frame", "Reference Frame", ""),
            PropertySpec.Choice("plane", "Plane", listOf("XY", "XZ", "YZ"), "XY"),
            PropertySpec.Number("cellSize", "Cell Size", 1.0, 0.1, 10.0, 0.1, "m"),
            PropertySpec.Number("cellCount", "Plane Cell Count", 20.0, 2.0, 200.0, 2.0),
            P.color(Colors.rgb(160, 160, 164)), P.alpha(0.5),
            P.lineStyle, P.lineWidth(0.03),
            P.offset("X"), P.offset("Y"), P.offset("Z")
        )
    ),
    TF(
        "TF", "Axes and names of all frames in /tf and /tf_static", "", "", emptyList(),
        listOf(
            PropertySpec.Bool("showNames", "Show Names", true),
            PropertySpec.Bool("showAxes", "Show Axes", true),
            PropertySpec.Bool("showArrows", "Show Arrows (parent-child)", true),
            PropertySpec.FrameSet("hiddenFrames", "Frames"),
            PropertySpec.Text("topics", "TF Topics", TfFrames.DEFAULT_TOPICS, "Comma-separated, e.g. /robot1/tf, /robot1/tf_static"),
            PropertySpec.Number("scale", "Marker Scale", 1.0, 0.1, 5.0, 0.1),
            PropertySpec.Number("nameSize", "Name Size", 14.0, 6.0, 40.0, 1.0, "dp"),
            PropertySpec.Number("frameTimeout", "Frame Timeout", 15.0, 1.0, 120.0, 1.0, "s")
        )
    ),
    AXES(
        "Axes", "X/Y/Z axes of a single frame", "", "", emptyList(),
        listOf(
            PropertySpec.Frame("frame", "Frame", ""),
            PropertySpec.Number("length", "Length", 1.0, 0.1, 10.0, 0.1, "m"),
            PropertySpec.Number("radius", "Radius", 0.1, 0.01, 1.0, 0.01, "m"),
            P.alpha()
        )
    ),
    ROBOT_MODEL(
        "RobotModel", "Robot model from the URDF (std_msgs/String)", "std_msgs/msg/String", "/robot_description",
        listOf("/robot_description"),
        listOf(
            PropertySpec.Bool("visual", "Visual Enabled", true),
            PropertySpec.Bool("collision", "Collision Enabled", false),
            P.alpha(1.0),
            PropertySpec.Text("meshUrl", "Mesh server (optional)", "", "http://ROBOT_IP:8000"),
            QOS_SPEC_LATCHED
        )
    ),
    MAP(
        "Map", "Occupancy grid map (nav_msgs/OccupancyGrid)", "nav_msgs/msg/OccupancyGrid", "/map",
        listOf("/map", "/global_costmap/costmap", "/local_costmap/costmap", "/costmap"),
        listOf(
            PropertySpec.Choice("scheme", "Color Scheme", listOf("map", "costmap", "raw"), "map"),
            P.alpha(0.7),
            PropertySpec.Bool("drawBehind", "Draw Behind", false),
            QOS_SPEC_LATCHED
        )
    ),
    LASER_SCAN(
        "LaserScan", "2D laser scan (sensor_msgs/LaserScan)", "sensor_msgs/msg/LaserScan", "/scan",
        listOf("/scan", "/laser_scan", "/scan_filtered", "/lidar/scan", "/base_scan"),
        listOf(
            P.pointStyle, P.pointSize(0.05), P.alpha(),
            PropertySpec.Choice("colorMode", "Color", listOf("intensity", "range", "x", "y", "z", "flat"), "intensity"),
            P.color(Colors.rgb(255, 255, 255), "Flat Color"),
            P.autoBounds, P.minValue, P.maxValue, P.decay,
            QOS_SPEC_SENSOR
        )
    ),
    POINT_CLOUD2(
        "PointCloud2", "Point cloud (sensor_msgs/PointCloud2)", "sensor_msgs/msg/PointCloud2", "/points",
        listOf(
            "/points", "/cloud", "/velodyne_points", "/points_raw", "/camera/depth/points",
            "/camera/camera/depth/color/points", "/livox/lidar", "/ouster/points"
        ),
        listOf(
            P.pointStyle, P.pointSize(0.03), P.alpha(),
            PropertySpec.Choice("colorMode", "Color", listOf("auto", "rgb", "intensity", "x", "y", "z", "flat"), "auto"),
            P.color(Colors.rgb(255, 255, 255), "Flat Color"),
            P.autoBounds, P.minValue, P.maxValue, P.decay,
            PropertySpec.Number("maxPoints", "Max Points", 100_000.0, 1_000.0, 500_000.0, 1_000.0),
            QOS_SPEC_SENSOR
        )
    ),
    POINT_CLOUD(
        "PointCloud", "Legacy point cloud (sensor_msgs/PointCloud)", "sensor_msgs/msg/PointCloud", "/cloud_old",
        listOf("/cloud_old", "/point_cloud"),
        listOf(
            P.pointStyle, P.pointSize(0.03), P.alpha(),
            PropertySpec.Choice("colorMode", "Color", listOf("auto", "intensity", "x", "y", "z", "flat"), "auto"),
            P.color(Colors.rgb(255, 255, 255), "Flat Color"),
            P.autoBounds, P.minValue, P.maxValue, P.decay,
            QOS_SPEC_SENSOR
        )
    ),
    ODOMETRY(
        "Odometry", "nav_msgs/Odometry trail as arrows", "nav_msgs/msg/Odometry", "/odom",
        listOf("/odom", "/odometry/filtered", "/odometry", "/wheel/odometry"),
        listOf(
            P.shape, P.color(Colors.rgb(255, 25, 0)), P.alpha(),
            PropertySpec.Number("keep", "Keep", 100.0, 1.0, 1000.0, 1.0),
            PropertySpec.Number("positionTolerance", "Position Tolerance", 0.1, 0.0, 5.0, 0.05, "m"),
            PropertySpec.Number("angleTolerance", "Angle Tolerance", 0.1, 0.0, 3.14, 0.01, "rad")
        ) + P.arrow(1.0, 0.05, 0.3, 0.1) + P.axes(1.0, 0.1) + QOS_SPEC_SENSOR
    ),
    PATH(
        "Path", "Path (nav_msgs/Path)", "nav_msgs/msg/Path", "/plan",
        listOf("/plan", "/path", "/global_plan", "/local_plan", "/received_global_plan", "/transformed_global_plan"),
        listOf(
            P.color(Colors.rgb(25, 255, 0)), P.alpha(), P.lineStyle, P.lineWidth(0.03),
            PropertySpec.Choice("poseStyle", "Poses", listOf("none", "axes", "arrows"), "none"),
            PropertySpec.Number("poseLength", "Pose Length", 0.3, 0.05, 2.0, 0.05, "m"),
            P.offset("Z"),
            QOS_SPEC_RELIABLE
        )
    ),
    POSE(
        "Pose", "Pose (geometry_msgs/PoseStamped)", "geometry_msgs/msg/PoseStamped", "/goal_pose",
        listOf("/goal_pose", "/pose", "/move_base_simple/goal", "/target_pose"),
        listOf(P.shape, P.color(Colors.rgb(255, 25, 0)), P.alpha()) +
            P.arrow(1.0, 0.05, 0.3, 0.1) + P.axes(1.0, 0.1) + QOS_SPEC_RELIABLE
    ),
    POSE_COV(
        "PoseWithCovariance", "Pose with covariance (e.g. /amcl_pose)",
        "geometry_msgs/msg/PoseWithCovarianceStamped", "/amcl_pose",
        listOf("/amcl_pose", "/initialpose", "/pose_with_covariance"),
        listOf(
            P.shape, P.color(Colors.rgb(255, 25, 0)), P.alpha(),
            PropertySpec.Bool("covariance", "Show Covariance (position)", true),
            PropertySpec.Color("covColor", "Covariance Color", Colors.rgb(204, 51, 204)),
            PropertySpec.Number("covAlpha", "Covariance Alpha", 0.3, 0.0, 1.0, 0.05)
        ) + P.arrow(1.0, 0.05, 0.3, 0.1) + P.axes(1.0, 0.1) + QOS_SPEC_RELIABLE
    ),
    POSE_ARRAY(
        "PoseArray", "Array of poses (geometry_msgs/PoseArray)", "geometry_msgs/msg/PoseArray", "/pose_array",
        listOf("/pose_array", "/particlecloud", "/poses"),
        listOf(P.shape, P.color(Colors.rgb(255, 25, 0)), P.alpha()) +
            P.arrow(0.23, 0.01, 0.07, 0.03) + P.axes(0.3, 0.01) + QOS_SPEC_SENSOR
    ),
    POINT(
        "PointStamped", "Point (geometry_msgs/PointStamped)", "geometry_msgs/msg/PointStamped", "/clicked_point",
        listOf("/clicked_point", "/point"),
        listOf(
            P.color(Colors.rgb(204, 41, 204)), P.alpha(),
            PropertySpec.Number("radius", "Radius", 0.2, 0.01, 2.0, 0.01, "m"),
            PropertySpec.Number("keep", "Keep", 1.0, 1.0, 100.0, 1.0),
            QOS_SPEC_RELIABLE
        )
    ),
    POLYGON(
        "Polygon", "Polygon (geometry_msgs/PolygonStamped)", "geometry_msgs/msg/PolygonStamped",
        "/local_costmap/published_footprint",
        listOf("/local_costmap/published_footprint", "/global_costmap/published_footprint", "/footprint", "/polygon"),
        listOf(P.color(Colors.rgb(25, 255, 0)), P.alpha(), P.lineStyle, P.lineWidth(0.03), QOS_SPEC_RELIABLE)
    ),
    MARKER(
        "Marker", "Markers (visualization_msgs/Marker)", "visualization_msgs/msg/Marker", "/visualization_marker",
        listOf("/visualization_marker", "/marker", "/markers"),
        listOf(PropertySpec.Bool("lineWidths", "Use the marker's line width (scale.x)", true),
            PropertySpec.Text("meshUrl", "Mesh server (optional)", "", "http://ROBOT_IP:8000"), QOS_SPEC_RELIABLE)
    ),
    MARKER_ARRAY(
        "MarkerArray", "Marker group (visualization_msgs/MarkerArray)", "visualization_msgs/msg/MarkerArray",
        "/visualization_marker_array",
        listOf("/visualization_marker_array", "/marker_array", "/markers_array"),
        listOf(PropertySpec.Bool("lineWidths", "Use the marker's line width (scale.x)", true),
            PropertySpec.Text("meshUrl", "Mesh server (optional)", "", "http://ROBOT_IP:8000"), QOS_SPEC_RELIABLE)
    ),
    RANGE(
        "Range", "Range sensor cone (sensor_msgs/Range)", "sensor_msgs/msg/Range", "/range",
        listOf("/range", "/sonar", "/ultrasonic", "/tof"),
        listOf(P.color(Colors.rgb(255, 255, 255)), P.alpha(0.5), QOS_SPEC_SENSOR)
    ),
    IMAGE(
        "Image", "Camera: the image in a window over the 3D view (sensor_msgs/Image, or CompressedImage for .../compressed)", "sensor_msgs/msg/Image", "/camera/image_raw",
        listOf("/camera/image_raw", "/camera/color/image_raw", "/image_raw", "/camera/image_raw/compressed", "/camera/color/image_raw/compressed"),
        listOf(
            PropertySpec.Choice("corner", "Corner", listOf("Top left", "Top right", "Bottom left", "Bottom right"), "Bottom right"),
            PropertySpec.Number("size", "Width (fraction of the view)", 0.4, 0.1, 1.0, 0.05),
            P.panelX, P.panelY,
            P.alpha(1.0), P.annotations, P.showAnnotations, QOS_SPEC_SENSOR
        )
    ),
    CAMERA(
        "Camera", "Camera image with the 3D scene drawn over it, seen from the camera (sensor_msgs/Image + CameraInfo)",
        "sensor_msgs/msg/Image", "/camera/image_raw",
        listOf("/camera/image_raw", "/camera/color/image_raw", "/camera/image_raw/compressed", "/camera/color/image_raw/compressed"),
        listOf(
            PropertySpec.Text("cameraInfo", "Camera Info Topic", "", "Empty: <image namespace>/camera_info"),
            PropertySpec.Choice("corner", "Corner", listOf("Top left", "Top right", "Bottom left", "Bottom right"), "Bottom left"),
            PropertySpec.Number("size", "Width (fraction of the view)", 0.5, 0.1, 1.0, 0.05),
            P.panelX, P.panelY,
            PropertySpec.Choice("mode", "Image Rendering", listOf("Image and scene", "Image over the scene", "Image only", "Scene only"), "Image and scene"),
            PropertySpec.Number("overlayAlpha", "Overlay Alpha", 0.5, 0.0, 1.0, 0.05),
            P.annotations, P.showAnnotations, QOS_SPEC_SENSOR
        )
    ),
    DEPTH_CLOUD(
        "DepthCloud", "Point cloud from a depth image and its camera_info (sensor_msgs/Image 16UC1 or 32FC1)",
        "sensor_msgs/msg/Image", "/camera/depth/image_raw",
        listOf("/camera/depth/image_raw", "/camera/depth/image_rect_raw", "/camera/aligned_depth_to_color/image_raw", "/depth/image_raw",
            "/camera/depth/image_raw/compressedDepth"),
        listOf(
            PropertySpec.Text("cameraInfo", "Camera Info Topic", "", "Empty: <image namespace>/camera_info"),
            PropertySpec.Text("colorTopic", "Color Image Topic", "", "Empty: color by distance"),
            PropertySpec.Number("maxRange", "Max Range", 10.0, 0.1, 100.0, 0.1, "m", textEntry = true),
            PropertySpec.Number("maxPoints", "Max Points", 40000.0, 1000.0, 300000.0, 1000.0, textEntry = true),
            P.pointStyle, P.pointSize(0.01), P.alpha(1.0), QOS_SPEC_SENSOR
        )
    ),
    EFFORT(
        "Effort", "Joint efforts as arcs around each joint axis (sensor_msgs/JointState + URDF)",
        "sensor_msgs/msg/JointState", "/joint_states",
        listOf("/joint_states"),
        listOf(
            PropertySpec.Text("description", "Description Topic", "/robot_description"),
            PropertySpec.Number("radius", "Radius", 0.15, 0.01, 2.0, 0.01, "m"),
            PropertySpec.Number("width", "Width", 0.02, 0.002, 0.5, 0.002, "m"),
            PropertySpec.Number("maxEffort", "Effort for a full circle (joints without a limit)", 10.0, 0.1, 100000.0, 0.1, textEntry = true),
            P.alpha(1.0), QOS_SPEC_SENSOR
        )
    ),
    GRID_CELLS(
        "GridCells", "Grid cells (nav_msgs/GridCells)", "nav_msgs/msg/GridCells", "/grid_cells",
        listOf("/grid_cells", "/cells", "/obstacles"),
        listOf(P.color(Colors.rgb(25, 255, 0)), P.alpha(1.0), QOS_SPEC_SENSOR)
    ),
    WRENCH(
        "Wrench", "Force and torque arrows (geometry_msgs/WrenchStamped)", "geometry_msgs/msg/WrenchStamped", "/wrench",
        listOf("/wrench", "/ft_sensor/wrench", "/force_torque"),
        listOf(
            PropertySpec.Color("linearColor", "Force Color", Colors.rgb(204, 51, 51)),
            PropertySpec.Color("angularColor", "Torque Color", Colors.rgb(204, 204, 51)),
            PropertySpec.Number("linearScale", "Force Arrow Scale", 0.5, 0.0, 100.0, 0.1, textEntry = true),
            PropertySpec.Number("angularScale", "Torque Arrow Scale", 0.5, 0.0, 100.0, 0.1, textEntry = true),
            PropertySpec.Number("width", "Arrow Width", 0.05, 0.005, 1.0, 0.005, "m"),
            P.alpha(1.0), QOS_SPEC_SENSOR
        )
    ),
    TWIST(
        "TwistStamped", "Linear and angular velocity arrows (geometry_msgs/TwistStamped)", "geometry_msgs/msg/TwistStamped", "/twist",
        listOf("/twist", "/cmd_vel_stamped", "/velocity"),
        listOf(
            PropertySpec.Color("linearColor", "Linear Color", Colors.rgb(51, 204, 51)),
            PropertySpec.Color("angularColor", "Angular Color", Colors.rgb(51, 102, 255)),
            PropertySpec.Number("linearScale", "Linear Arrow Scale", 1.0, 0.0, 100.0, 0.1, textEntry = true),
            PropertySpec.Number("angularScale", "Angular Arrow Scale", 1.0, 0.0, 100.0, 0.1, textEntry = true),
            PropertySpec.Number("width", "Arrow Width", 0.05, 0.005, 1.0, 0.005, "m"),
            P.alpha(1.0), QOS_SPEC_SENSOR
        )
    ),
    ACCEL(
        "AccelStamped", "Linear and angular acceleration arrows (geometry_msgs/AccelStamped)", "geometry_msgs/msg/AccelStamped", "/accel",
        listOf("/accel", "/acceleration"),
        listOf(
            PropertySpec.Color("linearColor", "Linear Color", Colors.rgb(255, 153, 0)),
            PropertySpec.Color("angularColor", "Angular Color", Colors.rgb(153, 51, 255)),
            PropertySpec.Number("linearScale", "Linear Arrow Scale", 0.5, 0.0, 100.0, 0.1, textEntry = true),
            PropertySpec.Number("angularScale", "Angular Arrow Scale", 0.5, 0.0, 100.0, 0.1, textEntry = true),
            PropertySpec.Number("width", "Arrow Width", 0.05, 0.005, 1.0, 0.005, "m"),
            P.alpha(1.0), QOS_SPEC_SENSOR
        )
    ),
    TEMPERATURE(
        "Temperature", "Temperature sensor (sensor_msgs/Temperature)", "sensor_msgs/msg/Temperature", "/temperature",
        listOf("/temperature", "/imu/temperature"),
        listOf(
            PropertySpec.Number("minValue", "Min Value", 0.0, -1e9, 1e9, 1.0, "°C", textEntry = true),
            PropertySpec.Number("maxValue", "Max Value", 50.0, -1e9, 1e9, 1.0, "°C", textEntry = true),
            PropertySpec.Number("size", "Size (m)", 0.15, 0.01, 2.0, 0.01, "m"),
            PropertySpec.Bool("showValue", "Show Value", true),
            P.alpha(1.0), QOS_SPEC_SENSOR
        )
    ),
    ILLUMINANCE(
        "Illuminance", "Light sensor (sensor_msgs/Illuminance)", "sensor_msgs/msg/Illuminance", "/illuminance",
        listOf("/illuminance", "/light"),
        listOf(
            PropertySpec.Number("minValue", "Min Value", 0.0, -1e9, 1e9, 1.0, "lx", textEntry = true),
            PropertySpec.Number("maxValue", "Max Value", 1000.0, -1e9, 1e9, 1.0, "lx", textEntry = true),
            PropertySpec.Number("size", "Size (m)", 0.15, 0.01, 2.0, 0.01, "m"),
            PropertySpec.Bool("showValue", "Show Value", true),
            P.alpha(1.0), QOS_SPEC_SENSOR
        )
    ),
    FLUID_PRESSURE(
        "FluidPressure", "Pressure sensor (sensor_msgs/FluidPressure)", "sensor_msgs/msg/FluidPressure", "/pressure",
        listOf("/pressure", "/fluid_pressure", "/barometer"),
        listOf(
            PropertySpec.Number("minValue", "Min Value", 95000.0, -1e9, 1e9, 1.0, "Pa", textEntry = true),
            PropertySpec.Number("maxValue", "Max Value", 105000.0, -1e9, 1e9, 1.0, "Pa", textEntry = true),
            PropertySpec.Number("size", "Size (m)", 0.15, 0.01, 2.0, 0.01, "m"),
            PropertySpec.Bool("showValue", "Show Value", true),
            P.alpha(1.0), QOS_SPEC_SENSOR
        )
    ),
    RELATIVE_HUMIDITY(
        "RelativeHumidity", "Humidity sensor (sensor_msgs/RelativeHumidity)", "sensor_msgs/msg/RelativeHumidity", "/humidity",
        listOf("/humidity", "/relative_humidity"),
        listOf(
            PropertySpec.Number("minValue", "Min Value", 0.0, -1e9, 1e9, 1.0, "%", textEntry = true),
            PropertySpec.Number("maxValue", "Max Value", 100.0, -1e9, 1e9, 1.0, "%", textEntry = true),
            PropertySpec.Number("size", "Size (m)", 0.15, 0.01, 2.0, 0.01, "m"),
            PropertySpec.Bool("showValue", "Show Value", true),
            P.alpha(1.0), QOS_SPEC_SENSOR
        )
    ),
    TRAJECTORY(
        "Trajectory", "MoveIt planned path, played by a ghost robot (moveit_msgs/DisplayTrajectory)",
        "moveit_msgs/msg/DisplayTrajectory", "/display_planned_path", listOf("/display_planned_path"),
        listOf(
            PropertySpec.Text("description", "Robot Description", "/robot_description", "/robot_description"),
            P.color(Colors.rgb(255, 152, 0), "Robot Color"), P.alpha(0.5),
            PropertySpec.Bool("loop", "Loop Animation", true),
            PropertySpec.Number("speed", "Speed", 1.0, 0.1, 5.0, 0.1, "x"),
            PropertySpec.Bool("showGoal", "Show Goal (MoveIt panel)", true),
            PropertySpec.Text("meshUrl", "Mesh server (optional)", "", "http://ROBOT_IP:8000"),
            QOS_SPEC_RELIABLE
        )
    ),
    PLANNING_SCENE(
        "PlanningScene", "MoveIt planning scene: the obstacles (moveit_msgs/PlanningScene)",
        "moveit_msgs/msg/PlanningScene", "/monitored_planning_scene", listOf("/monitored_planning_scene"),
        listOf(P.color(Colors.rgb(50, 230, 50), "Scene Color"), P.alpha(0.9), QOS_SPEC_RELIABLE)
    ),
    NAVMAP(
        "NavMap", "EasyNavigation's navigable mesh map: floors, ramps and stairs, colored by a layer (navmap_ros_interfaces/NavMap)",
        "navmap_ros_interfaces/msg/NavMap", "/navmap", listOf("/navmap", "/navmap_server/navmap"),
        listOf(
            PropertySpec.Text("layer", "Layer", "", "Name of the layer that colors the cells (empty: the first one)"),
            PropertySpec.Choice("scheme", "Color Scheme", listOf("viridis", "heat", "rainbow", "flat"), "viridis"),
            PropertySpec.Bool("smooth", "Smooth Colors", true),
            P.color(Colors.rgb(180, 180, 180), "Flat Color"), P.alpha(1.0),
            PropertySpec.Text("layerTopic", "Update Layer Topic", "", "navmap_ros_interfaces/NavMapLayer (empty: the map's topic + _layers)"),
            PropertySpec.Bool("drawLinks", "Draw Links", true),
            PropertySpec.Bool("drawEdges", "Draw Edges", false),
            QOS_SPEC_RELIABLE
        )
    ),
    INTERACTIVE_MARKERS(
        "InteractiveMarkers", "Interactive markers you move with the Interact tool (visualization_msgs/InteractiveMarkerUpdate)",
        "visualization_msgs/msg/InteractiveMarkerUpdate", "/basic_controls/update",
        listOf("/basic_controls/update", "/simple_marker/update", "/menu/update", "/interactive_marker_server/update"),
        listOf(PropertySpec.Bool("showDescriptions", "Show Descriptions", true), QOS_SPEC_RELIABLE)
    );

    val needsTopic: Boolean get() = rosType.isNotEmpty()

    fun spec(key: String): PropertySpec? = properties.firstOrNull { it.key == key }

    companion object {
        fun fromName(name: String?): DisplayType? = entries.firstOrNull { it.name == name }

        /** Types that can be added by topic. */
        val TOPIC_TYPES: List<DisplayType> get() = entries.filter { it.needsTopic }
    }
}

/** Persisted configuration of a display. */
data class DisplayConfig(
    val id: String,
    val type: DisplayType,
    val name: String,
    val enabled: Boolean = true,
    val topic: String = "",
    val props: Map<String, String> = emptyMap()
) {
    fun string(key: String): String = props[key] ?: type.spec(key)?.defaultValue ?: ""

    fun bool(key: String): Boolean = string(key) == "true"

    fun number(key: String): Double {
        val spec = type.spec(key) as? PropertySpec.Number
        val v = props[key]?.toDoubleOrNull() ?: spec?.default ?: 0.0
        return spec?.clamp(v) ?: v
    }

    fun int(key: String): Int = Math.round(number(key)).toInt()

    fun color(key: String): Int {
        val spec = type.spec(key) as? PropertySpec.Color
        return Colors.parseHex(props[key]) ?: spec?.default ?: Colors.rgb(255, 255, 255)
    }

    fun qos(): QosPreset = QosPreset.entries.firstOrNull { it.name == string("qos") } ?: QosPreset.SENSOR

    /** The full QoS: the preset's, plus whatever was changed by hand on top (Depth, History, Reliability, Durability). */
    fun qosSettings(): QosSettings = QosSettings.of(qos()).let { d ->
        QosSettings(
            string(QosSettings.DEPTH).toIntOrNull()?.takeIf { it > 0 } ?: d.depth,
            string(QosSettings.HISTORY).takeIf { it in QosSettings.HISTORIES } ?: d.history,
            string(QosSettings.RELIABILITY).takeIf { it in QosSettings.RELIABILITIES } ?: d.reliability,
            string(QosSettings.DURABILITY).takeIf { it in QosSettings.DURABILITIES } ?: d.durability,
        )
    }

    fun qosProfile(): us.ihmc.jros2.ROS2QoSProfile = qosSettings().profile()

    /** Changes if anything in the QoS changes (to resubscribe). */
    fun qosKey(): String = listOf("qos", QosSettings.DEPTH, QosSettings.HISTORY, QosSettings.RELIABILITY, QosSettings.DURABILITY).joinToString("|") { string(it) }

    /** Another preset: manual changes are forgotten (like choosing a profile in RViz). */
    fun withQosPreset(p: QosPreset): DisplayConfig =
        copy(props = props - QosSettings.DEPTH - QosSettings.HISTORY - QosSettings.RELIABILITY - QosSettings.DURABILITY + ("qos" to p.name))

    fun with(key: String, value: String): DisplayConfig = copy(props = props + (key to value))

    companion object {
        fun create(type: DisplayType, name: String = type.label, topic: String = type.defaultTopic) =
            DisplayConfig(UUID.randomUUID().toString(), type, name, true, if (type.needsTopic) topic else "")
    }
}

object DisplayList {

    const val MAX_CHIP_CHARS = 14

    fun defaults(): List<DisplayConfig> = listOf(
        DisplayConfig.create(DisplayType.GRID),
        DisplayConfig.create(DisplayType.TF)
    )

    /** Name for the strip: if it is too long it is cut with "…". */
    fun shortName(name: String, max: Int = MAX_CHIP_CHARS): String =
        if (name.length <= max) name else name.take(max - 1).trimEnd() + "…"

    /** Default name when adding by topic: the topic without the leading "/", or the type if it is a duplicate. */
    fun nameForTopic(type: DisplayType, topic: String): String = topic.trimStart('/').ifEmpty { type.label }

    fun toJson(list: List<DisplayConfig>): String {
        val arr = JSONArray()
        for (d in list) {
            val props = JSONObject()
            for ((k, v) in d.props) props.put(k, v)
            arr.put(
                JSONObject().put("id", d.id).put("type", d.type.name).put("name", d.name)
                    .put("enabled", d.enabled).put("topic", d.topic).put("props", props)
            )
        }
        return arr.toString()
    }

    /** Ignores corrupt entries or unknown types; null if nothing is saved. */
    fun fromJson(json: String?): List<DisplayConfig>? {
        if (json.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<DisplayConfig>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = DisplayType.fromName(o.optString("type")) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val props = HashMap<String, String>()
                o.optJSONObject("props")?.let { p -> for (k in p.keys()) props[k] = p.optString(k) }
                out.add(
                    DisplayConfig(
                        id, type, o.optString("name", type.label), o.optBoolean("enabled", true),
                        o.optString("topic", type.defaultTopic), props
                    )
                )
            }
            out
        } catch (e: Exception) {
            null
        }
    }
}

/** Colors as ARGB Int (same format as android.graphics.Color, without depending on Android). */
object Colors {
    fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun rgbf(r: Float, g: Float, b: Float, a: Float = 1f): Int =
        rgb((r * 255f + 0.5f).toInt(), (g * 255f + 0.5f).toInt(), (b * 255f + 0.5f).toInt(), (a * 255f + 0.5f).toInt())

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun alpha(c: Int) = (c ushr 24) and 0xFF

    fun withAlpha(c: Int, alpha: Float): Int = (c and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)

    /** Multiplies the RGB (for the fake per-face shading). */
    fun shade(c: Int, factor: Float): Int =
        rgb((red(c) * factor).toInt(), (green(c) * factor).toInt(), (blue(c) * factor).toInt(), alpha(c))

    fun toHex(c: Int): String = String.format("#%02X%02X%02X", red(c), green(c), blue(c))

    fun parseHex(s: String?): Int? {
        if (s == null) return null
        val t = s.trim().removePrefix("#")
        if (t.length != 6) return null
        val v = t.toIntOrNull(16) ?: return null
        return (0xFF shl 24) or v
    }

    /**
     * RViz rainbow for intensities: 0 = red → yellow → green → cyan → 1 = blue.
     */
    fun rainbow(t: Float): Int {
        val v = if (t.isNaN()) 0f else t.coerceIn(0f, 1f)
        val h = v * 4f // 0..4
        val i = h.toInt().coerceAtMost(3)
        val f = h - i
        return when (i) {
            0 -> rgbf(1f, f, 0f)          // red → yellow
            1 -> rgbf(1f - f, 1f, 0f)     // yellow → green
            2 -> rgbf(0f, 1f, f)          // green → cyan
            else -> rgbf(0f, 1f - f, 1f)  // cyan → blue
        }
    }

    /** Fixed colors to pick from in the edit screen. */
    val PALETTE = listOf(
        rgb(255, 25, 0), rgb(255, 0, 0), rgb(255, 165, 0), rgb(255, 235, 59), rgb(25, 255, 0),
        rgb(0, 200, 83), rgb(0, 229, 255), rgb(33, 150, 243), rgb(124, 77, 255), rgb(204, 41, 204),
        rgb(255, 255, 255), rgb(160, 160, 164), rgb(0, 0, 0)
    )
}

/** A display's QoS as in RViz 2: Depth, History, Reliability and Durability. */
data class QosSettings(val depth: Int, val history: String, val reliability: String, val durability: String) {
    fun profile(): us.ihmc.jros2.ROS2QoSProfile = us.ihmc.jros2.ROS2QoSProfile().also { q -> val s = this
        q.reliability(if (s.reliability == BEST_EFFORT) us.ihmc.jros2.ROS2QoSProfile.Reliability.BEST_EFFORT else us.ihmc.jros2.ROS2QoSProfile.Reliability.RELIABLE)
        q.durability(if (s.durability == TRANSIENT_LOCAL) us.ihmc.jros2.ROS2QoSProfile.Durability.TRANSIENT_LOCAL else us.ihmc.jros2.ROS2QoSProfile.Durability.VOLATILE)
        q.history(if (s.history == KEEP_ALL) us.ihmc.jros2.ROS2QoSProfile.History.KEEP_ALL else us.ihmc.jros2.ROS2QoSProfile.History.KEEP_LAST)
        q.depth(s.depth)
    }

    companion object {
        const val DEPTH = "qosDepth"; const val HISTORY = "qosHistory"; const val RELIABILITY = "qosReliability"; const val DURABILITY = "qosDurability"
        const val KEEP_LAST = "Keep Last"; const val KEEP_ALL = "Keep All"
        const val RELIABLE = "Reliable"; const val BEST_EFFORT = "Best Effort"
        const val VOLATILE = "Volatile"; const val TRANSIENT_LOCAL = "Transient Local"
        val HISTORIES = listOf(KEEP_LAST, KEEP_ALL)
        val RELIABILITIES = listOf(RELIABLE, BEST_EFFORT)
        val DURABILITIES = listOf(VOLATILE, TRANSIENT_LOCAL)

        /** What each preset brings (the same values as [QosPreset.profile]). */
        fun of(p: QosPreset) = when (p) {
            QosPreset.SENSOR -> QosSettings(5, KEEP_LAST, BEST_EFFORT, VOLATILE)
            QosPreset.RELIABLE -> QosSettings(10, KEEP_LAST, RELIABLE, VOLATILE)
            QosPreset.LATCHED -> QosSettings(1, KEEP_LAST, RELIABLE, TRANSIENT_LOCAL)
        }
    }
}

/** The QoS profile of each preset (used by displays, plots, indicators and buttons). */
fun QosPreset.profile(): us.ihmc.jros2.ROS2QoSProfile = us.ihmc.jros2.ROS2QoSProfile().apply {
    when (this@profile) {
        QosPreset.SENSOR -> {
            reliability(us.ihmc.jros2.ROS2QoSProfile.Reliability.BEST_EFFORT)
            durability(us.ihmc.jros2.ROS2QoSProfile.Durability.VOLATILE)
            history(us.ihmc.jros2.ROS2QoSProfile.History.KEEP_LAST); depth(5)
        }
        QosPreset.RELIABLE -> {
            reliability(us.ihmc.jros2.ROS2QoSProfile.Reliability.RELIABLE)
            durability(us.ihmc.jros2.ROS2QoSProfile.Durability.VOLATILE)
            history(us.ihmc.jros2.ROS2QoSProfile.History.KEEP_LAST); depth(10)
        }
        QosPreset.LATCHED -> {
            reliability(us.ihmc.jros2.ROS2QoSProfile.Reliability.RELIABLE)
            durability(us.ihmc.jros2.ROS2QoSProfile.Durability.TRANSIENT_LOCAL)
            history(us.ihmc.jros2.ROS2QoSProfile.History.KEEP_LAST); depth(1)
        }
    }
}

/** Topics and hidden frames of the TF display (no Android or Filament: tested on the JVM). */
object TfFrames {
    const val DEFAULT_TOPICS = "/tf, /tf_static"

    /** The typed topics ("/a/tf, /a/tf_static" or one per line), normalized. Empty = the usual ones. */
    fun topics(value: String): List<String> =
        value.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.map { if (it.startsWith("/")) it else "/$it" }.distinct()
            .ifEmpty { listOf("/tf", "/tf_static") }

    fun isDefault(topics: List<String>) = topics.toSet() == setOf("/tf", "/tf_static")

    /** A static (latched) TF topic: those ending in `_static`. */
    fun isStatic(topic: String) = topic.endsWith("_static")

    fun patterns(value: String): List<String> = value.split('\n').map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() }.distinct()

    fun join(patterns: Collection<String>) = patterns.joinToString("\n")

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /** Is it hidden by any of the patterns? (`*` = anything; without `*`, the exact name). */
    fun hidden(id: String, patterns: List<String>): Boolean {
        val f = id.removePrefix("/")
        return patterns.any { p ->
            if ('*' !in p) p == f
            else cache.getOrPut(p) { Regex(p.split('*').joinToString(".*") { Regex.escape(it) }) }.matches(f)
        }
    }
}
