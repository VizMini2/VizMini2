// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.diag

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Message types shipped with jros2, by ROS name ("sensor_msgs/msg/Imu"). */
object MessageCatalog {

    private val NAMES = """
        actionlib_msgs/GoalStatusArray builtin_interfaces/Time diagnostic_msgs/DiagnosticArray diagnostic_msgs/DiagnosticStatus
        geometry_msgs/Accel geometry_msgs/AccelStamped geometry_msgs/AccelWithCovarianceStamped geometry_msgs/Inertia
        geometry_msgs/InertiaStamped geometry_msgs/Point geometry_msgs/Point32 geometry_msgs/PointStamped geometry_msgs/Polygon
        geometry_msgs/PolygonStamped geometry_msgs/Pose geometry_msgs/Pose2D geometry_msgs/PoseArray geometry_msgs/PoseStamped
        geometry_msgs/PoseWithCovariance geometry_msgs/PoseWithCovarianceStamped geometry_msgs/Quaternion geometry_msgs/QuaternionStamped
        geometry_msgs/Transform geometry_msgs/TransformStamped geometry_msgs/Twist geometry_msgs/TwistStamped
        geometry_msgs/TwistWithCovariance geometry_msgs/TwistWithCovarianceStamped geometry_msgs/Vector3 geometry_msgs/Vector3Stamped
        geometry_msgs/VelocityStamped geometry_msgs/Wrench geometry_msgs/WrenchStamped lifecycle_msgs/State lifecycle_msgs/TransitionEvent
        nav_msgs/GridCells nav_msgs/MapMetaData nav_msgs/OccupancyGrid nav_msgs/Odometry nav_msgs/Path rcl_interfaces/Log
        rosgraph_msgs/Clock sensor_msgs/BatteryState sensor_msgs/CameraInfo sensor_msgs/CompressedImage sensor_msgs/FluidPressure
        sensor_msgs/Illuminance sensor_msgs/Image sensor_msgs/Imu sensor_msgs/JointState sensor_msgs/Joy sensor_msgs/LaserScan
        sensor_msgs/MagneticField sensor_msgs/MultiDOFJointState sensor_msgs/MultiEchoLaserScan sensor_msgs/NavSatFix
        sensor_msgs/PointCloud sensor_msgs/PointCloud2 sensor_msgs/Range sensor_msgs/RelativeHumidity sensor_msgs/Temperature
        sensor_msgs/TimeReference shape_msgs/SolidPrimitive statistics_msgs/MetricsMessage std_msgs/Bool std_msgs/ByteMultiArray
        std_msgs/Char std_msgs/ColorRGBA std_msgs/Empty std_msgs/Float32 std_msgs/Float32MultiArray std_msgs/Float64
        std_msgs/Float64MultiArray std_msgs/Header std_msgs/Int16 std_msgs/Int16MultiArray std_msgs/Int32 std_msgs/Int32MultiArray
        std_msgs/Int64 std_msgs/Int64MultiArray std_msgs/Int8 std_msgs/Int8MultiArray std_msgs/String std_msgs/UInt16
        std_msgs/UInt16MultiArray std_msgs/UInt32 std_msgs/UInt32MultiArray std_msgs/UInt64 std_msgs/UInt64MultiArray std_msgs/UInt8
        std_msgs/UInt8MultiArray stereo_msgs/DisparityImage tf2_msgs/TFMessage trajectory_msgs/JointTrajectory
        trajectory_msgs/MultiDOFJointTrajectory visualization_msgs/ImageMarker visualization_msgs/InteractiveMarkerFeedback
        visualization_msgs/InteractiveMarkerUpdate visualization_msgs/Marker visualization_msgs/MarkerArray
    """.trim().split(Regex("\\s+"))

    /** "sensor_msgs/msg/Imu", sorted. */
    val all: List<String> = NAMES.map { it.replace("/", "/msg/") }.sorted()

    /** Java class of a ROS type ("sensor_msgs/msg/Imu" or "sensor_msgs/Imu"); null if it does not exist. */
    fun classFor(rosType: String): Class<*>? {
        val parts = rosType.trim().split('/').filter { it.isNotEmpty() && it != "msg" }
        if (parts.size != 2) return null
        // std_msgs/String is called String_ in Java.
        val simple = if (parts[1] == "String") "String_" else parts[1]
        return try { Class.forName("${parts[0]}.$simple") } catch (_: Throwable) { null }
    }

    /**
     * Types that make no sense to plot even if they have some number: maps, images,
     * point clouds, markers, trajectories, text... (they are shown in RViz, Camera or the echo).
     */
    private val NOT_PLOTTABLE = setOf(
        "nav_msgs/msg/OccupancyGrid", "nav_msgs/msg/GridCells", "nav_msgs/msg/Path", "nav_msgs/msg/MapMetaData",
        "sensor_msgs/msg/Image", "sensor_msgs/msg/CompressedImage", "sensor_msgs/msg/PointCloud", "sensor_msgs/msg/PointCloud2",
        "sensor_msgs/msg/CameraInfo", "sensor_msgs/msg/MultiEchoLaserScan", "stereo_msgs/msg/DisparityImage",
        "visualization_msgs/msg/Marker", "visualization_msgs/msg/MarkerArray", "visualization_msgs/msg/ImageMarker",
        "visualization_msgs/msg/InteractiveMarkerFeedback", "visualization_msgs/msg/InteractiveMarkerUpdate",
        "trajectory_msgs/msg/JointTrajectory", "trajectory_msgs/msg/MultiDOFJointTrajectory",
        "geometry_msgs/msg/Polygon", "geometry_msgs/msg/PolygonStamped", "geometry_msgs/msg/PoseArray",
        "tf2_msgs/msg/TFMessage", "rcl_interfaces/msg/Log", "diagnostic_msgs/msg/DiagnosticArray",
        "diagnostic_msgs/msg/DiagnosticStatus", "lifecycle_msgs/msg/TransitionEvent", "lifecycle_msgs/msg/State",
        "std_msgs/msg/String", "std_msgs/msg/Empty", "std_msgs/msg/Header", "std_msgs/msg/Char",
        "std_msgs/msg/ByteMultiArray", "std_msgs/msg/UInt8MultiArray", "std_msgs/msg/Int8MultiArray",
        "shape_msgs/msg/SolidPrimitive", "actionlib_msgs/msg/GoalStatusArray", "statistics_msgs/msg/MetricsMessage",
    )

    private val plottableCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Can it be plotted? Not if it is in the list above or if it has no numeric field
     * outside the header (the header time alone is not data).
     */
    fun isPlottable(rosType: String): Boolean {
        val norm = rosType.trim().split('/').filter { it.isNotEmpty() && it != "msg" }.let { if (it.size == 2) "${it[0]}/msg/${it[1]}" else rosType }
        return plottableCache.getOrPut(norm) {
            if (norm in NOT_PLOTTABLE) false
            else classFor(norm)?.let { c -> try { hasNumericData(c.getDeclaredConstructor().newInstance(), 0) } catch (_: Throwable) { false } } ?: false
        }
    }

    private fun hasNumericData(msg: Any, depth: Int): Boolean {
        if (depth > 8) return false
        for (f in MessageIntrospection.fields(msg.javaClass)) {
            if (MessageIntrospection.rosName(f) == "header") continue
            val v = f.get(msg) ?: continue
            when {
                v is Number || v is Boolean -> return true
                MessageIntrospection.isNumericSequence(v) -> return true
                MessageIntrospection.isMessage(v) && hasNumericData(v, depth + 1) -> return true
            }
        }
        return false
    }

    /** Types offered in the plot. */
    val plottable: List<String> by lazy { all.filter { isPlottable(it) } }

    /** Common topics with their type, to suggest in the echo and the plot. */
    val COMMON_TOPICS: List<Pair<String, String>> = listOf(
        "/rosout" to "rcl_interfaces/msg/Log",
        "/diagnostics" to "diagnostic_msgs/msg/DiagnosticArray",
        "/cmd_vel" to "geometry_msgs/msg/Twist",
        "/odom" to "nav_msgs/msg/Odometry",
        "/imu" to "sensor_msgs/msg/Imu",
        "/imu/data" to "sensor_msgs/msg/Imu",
        "/scan" to "sensor_msgs/msg/LaserScan",
        "/joint_states" to "sensor_msgs/msg/JointState",
        "/battery_state" to "sensor_msgs/msg/BatteryState",
        "/battery" to "sensor_msgs/msg/BatteryState",
        "/amcl_pose" to "geometry_msgs/msg/PoseWithCovarianceStamped",
        "/goal_pose" to "geometry_msgs/msg/PoseStamped",
        "/plan" to "nav_msgs/msg/Path",
        "/map" to "nav_msgs/msg/OccupancyGrid",
        "/tf" to "tf2_msgs/msg/TFMessage",
        "/tf_static" to "tf2_msgs/msg/TFMessage",
        "/robot_description" to "std_msgs/msg/String",
        "/clock" to "rosgraph_msgs/msg/Clock",
        "/gps/fix" to "sensor_msgs/msg/NavSatFix",
        "/fix" to "sensor_msgs/msg/NavSatFix",
        "/joy" to "sensor_msgs/msg/Joy",
        "/range" to "sensor_msgs/msg/Range",
        "/temperature" to "sensor_msgs/msg/Temperature",
    )
}

/**
 * Walks jros2 messages via reflection: private fields are named as in ROS with a trailing "_"
 * (`child_frame_id_`). Used for the echo (like `ros2 topic echo`) and to extract numeric
 * values to plot.
 */
object MessageIntrospection {

    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    /**
     * ROS fields of a message in .msg order. Android does not guarantee the order of
     * `declaredFields` (ART returns them by name), but the `toString()` generated by jros2
     * writes them in order ("Twist {linear_=..., angular_=...}"): they are sorted by their position there.
     */
    fun fields(c: Class<*>): List<Field> = fieldCache.getOrPut(c) {
        val fs = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.name.endsWith("_") && !it.isSynthetic }
            .onEach { it.isAccessible = true }
        val text = try { c.getDeclaredConstructor().newInstance().toString() } catch (_: Throwable) { "" }
        fs.sortedBy { f -> topLevelIndex(text, f.name + "=").let { if (it < 0) Int.MAX_VALUE else it } }
    }

    /**
     * Position of `key` in the first brace level of `text` (not inside submessages). jros2 does not
     * separate fields ("STALE=3level_=2"): a digit may precede it, but not a letter or "_"
     * (so "id_=" does not match inside "hardware_id_=").
     */
    private fun topLevelIndex(text: String, key: String): Int {
        var depth = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '{' || ch == '[') depth++
            else if (ch == '}' || ch == ']') depth--
            else if (depth == 1 && text.startsWith(key, i) && (i == 0 || !text[i - 1].isLetter() && text[i - 1] != '_')) return i
            i++
        }
        return -1
    }

    fun rosName(f: Field) = f.name.removeSuffix("_")

    /** Is it a jros2 message (does it have ROS fields)? */
    fun isMessage(v: Any?) = v != null && v.javaClass.name.let { !it.startsWith("java.") && !it.startsWith("us.ihmc.") } && fields(v.javaClass).isNotEmpty()

    /** IDL sequences and Java arrays: size and element i. */
    fun isSequence(v: Any?) = v != null && (v.javaClass.isArray || v.javaClass.name.startsWith("us.ihmc.fastddsjava.cdr.idl.IDL"))

    fun seqSize(v: Any): Int =
        if (v.javaClass.isArray) java.lang.reflect.Array.getLength(v)
        else v.javaClass.getMethod("size").invoke(v) as Int

    /**
     * Sequence or array of numbers that can be plotted element by element. Byte ones cannot: they are
     * raw data (map cells, image pixels...).
     */
    fun isNumericSequence(v: Any): Boolean = when (v) {
        is DoubleArray, is FloatArray, is IntArray, is LongArray, is ShortArray, is BooleanArray -> true
        else -> v.javaClass.simpleName in NUMERIC_IDL_SEQUENCES
    }

    private fun isByteData(v: Any) = v is ByteArray || v is CharArray || v.javaClass.simpleName == "IDLByteSequence" || v.javaClass.simpleName == "IDLCharSequence"

    private val NUMERIC_IDL_SEQUENCES = setOf("IDLDoubleSequence", "IDLFloatSequence", "IDLIntSequence", "IDLLongSequence", "IDLShortSequence", "IDLBoolSequence")

    fun seqGet(v: Any, i: Int): Any? =
        if (v.javaClass.isArray) java.lang.reflect.Array.get(v, i)
        else v.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(v, i)

    // ---------------------------------------------------------------- echo

    /** Text in the style of `ros2 topic echo`. Long lists are truncated to `maxSeq` elements. */
    fun format(msg: Any, maxSeq: Int = 8, maxChars: Int = 20_000): String {
        val sb = StringBuilder()
        formatInto(sb, msg, 0, maxSeq)
        if (sb.length > maxChars) { sb.setLength(maxChars); sb.append("\n… (truncated)") }
        return sb.toString().trimEnd()
    }

    private fun formatInto(sb: StringBuilder, msg: Any, indent: Int, maxSeq: Int) {
        val pad = "  ".repeat(indent)
        val fs = fields(msg.javaClass)
        if (fs.isEmpty()) { sb.append(pad).append("{}\n"); return }
        for (f in fs) {
            val v = f.get(msg)
            sb.append(pad).append(rosName(f)).append(':')
            when {
                isMessage(v) -> { sb.append('\n'); formatInto(sb, v!!, indent + 1, maxSeq) }
                isSequence(v) -> formatSequence(sb, v!!, indent, maxSeq)
                else -> sb.append(' ').append(scalar(v)).append('\n')
            }
        }
    }

    private fun formatSequence(sb: StringBuilder, v: Any, indent: Int, maxSeq: Int) {
        val n = seqSize(v)
        if (n == 0) { sb.append(" []\n"); return }
        val first = seqGet(v, 0)
        if (!isMessage(first)) {
            // Inline numbers/strings: [1, 2, 3, ... (+N)]
            sb.append(" [")
            for (i in 0 until minOf(n, maxSeq)) { if (i > 0) sb.append(", "); sb.append(scalar(seqGet(v, i))) }
            if (n > maxSeq) sb.append(", … (+").append(n - maxSeq).append(')')
            sb.append("]\n")
            return
        }
        sb.append('\n')
        val pad = "  ".repeat(indent)
        for (i in 0 until minOf(n, maxSeq)) {
            sb.append(pad).append("- \n")
            formatInto(sb, seqGet(v, i)!!, indent + 1, maxSeq)
        }
        if (n > maxSeq) sb.append(pad).append("- … (").append(n - maxSeq).append(" more)\n")
    }

    private fun scalar(v: Any?): String = when (v) {
        null -> "null"
        is CharSequence -> com.juancams.rviz2mini.ros.RosStrings.fix(v).let { t ->
            if (t.isEmpty() || t.any { it == ':' || it == '#' || it == '\n' } || t.trim() != t) "'${t.replace("'", "''")}'" else t
        }
        is Float -> if (v.isFinite() && v == Math.rint(v.toDouble()).toFloat() && Math.abs(v) < 1e7f) v.toLong().toString() + ".0" else v.toString()
        is Double -> if (v.isFinite() && v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() + ".0" else v.toString()
        else -> v.toString()
    }

    // ---------------------------------------------------------------- numeric values

    /**
     * Paths of the numeric fields ("twist.twist.linear.x", "ranges[0]"). Of each sequence only
     * the first `maxPerSeq` elements are offered, and byte ones (raw data) are skipped.
     */
    fun numericPaths(msg: Any, maxPerSeq: Int = 4, limit: Int = 300): List<String> {
        val out = ArrayList<String>()
        fun walk(v: Any?, path: String, depth: Int) {
            if (out.size >= limit || depth > 12 || v == null) return
            when {
                v is Number || v is Boolean -> out.add(path)
                isMessage(v) -> for (f in fields(v.javaClass)) walk(f.get(v), if (path.isEmpty()) rosName(f) else "$path.${rosName(f)}", depth + 1)
                isSequence(v) -> if (!isByteData(v)) for (i in 0 until minOf(seqSize(v), maxPerSeq)) walk(seqGet(v, i), "$path[$i]", depth + 1)
            }
        }
        walk(msg, "", 0)
        return out
    }

    /** Already parsed path: evaluated quickly on each message (on the DDS thread). */
    class NumericPath(val text: String) {
        private sealed class Step { class Name(val name: String) : Step(); class Index(val i: Int) : Step() }
        private val steps: List<Step> = Regex("([A-Za-z_][A-Za-z0-9_]*)|\\[(\\d+)]").findAll(text).map {
            if (it.groupValues[1].isNotEmpty()) Step.Name(it.groupValues[1]) else Step.Index(it.groupValues[2].toInt())
        }.toList()

        /** null if the path does not exist in this message or is not numeric. */
        fun eval(msg: Any): Double? {
            var v: Any? = msg
            try {
                for (s in steps) {
                    val cur = v ?: return null
                    v = when (s) {
                        is Step.Name -> fields(cur.javaClass).firstOrNull { rosName(it) == s.name }?.get(cur) ?: return null
                        is Step.Index -> if (isSequence(cur) && s.i < seqSize(cur)) seqGet(cur, s.i) else return null
                    }
                }
            } catch (_: Exception) { return null }
            return when (val r = v) {
                is Number -> r.toDouble()
                is Boolean -> if (r) 1.0 else 0.0
                else -> null
            }
        }
    }
}
