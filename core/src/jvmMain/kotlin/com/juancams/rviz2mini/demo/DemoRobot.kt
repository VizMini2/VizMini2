// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.demo

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.MsgValue
import us.ihmc.jros2.ROS2QoSProfile
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where the demo camera goes as H.264 video: [frame] with each rgb8 image (from the simulator thread), [close] at the end. */
interface DemoVideoSink {
    fun frame(rgb: ByteArray, w: Int, h: Int, stamp: MsgValue)
    fun close()
}

/**
 * Simulated robot for demo mode: publishes on the phone's own local network what a mobile robot
 * would publish (TF, odometry, laser, map, camera, diagnostics, logs and its URDF) and obeys /cmd_vel,
 * so it can be driven with a teleop tab and seen in RViz. Without /cmd_vel it drives around on its own.
 *
 * The world is a 10 × 8 m room with some boxes (see [DemoWorld]).
 */
class DemoRobot(private val videoFactory: ((publisher: (String, String) -> Pair<MsgSpec, DynRos.Publisher>) -> DemoVideoSink?)? = null) {

    private val registry: InterfaceRegistry = Interfaces.get()
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "demo-robot").apply { isDaemon = true } }
    private val pubs = ArrayList<DynRos.Publisher>()
    private var cmdSub: DynRos.Subscription? = null

    // Robot state (only from the simulator thread, except the last cmd_vel).
    private var x = HOME_X
    private var y = HOME_Y
    private var yaw = 0.0
    private var v = 0.0
    private var w = 0.0
    private var battery = 100.0
    @Volatile private var cmd = Pair(0.0, 0.0)
    @Volatile private var lastCmdMs = 0L
    private var step = 0L

    // What the demo's parameters, services and lifecycle node change ([DemoServices]).
    @Volatile var maxLinear = 0.6
    @Volatile var maxAngular = 1.5
    @Volatile var autopilotOn = true
    @Volatile var cameraOn = true
    /** The demo controller manager's diff_drive_controller is active: without it the robot does not move. */
    @Volatile var drivesOn = true
    private var services: DemoServices? = null

    /** Takes the robot back to its base (return_home service). */
    fun returnHome() = exec.execute { x = HOME_X; y = HOME_Y; yaw = 0.0; v = 0.0; w = 0.0 }

    /** Runs on the simulator thread after `delayMs` (replies that "take time", action goals). */
    fun later(delayMs: Long, block: () -> Unit) {
        if (!exec.isShutdown) exec.schedule({ safe { block() } }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun spec(type: String): MsgSpec = registry.msg(type)
    private fun pub(topic: String, type: String, latched: Boolean = false): Pair<MsgSpec, DynRos.Publisher> {
        val qos = ROS2QoSProfile().apply {
            reliability(ROS2QoSProfile.Reliability.RELIABLE)
            durability(if (latched) ROS2QoSProfile.Durability.TRANSIENT_LOCAL else ROS2QoSProfile.Durability.VOLATILE)
            history(ROS2QoSProfile.History.KEEP_LAST)
            depth(if (latched) 1 else 10)
        }
        val s = spec(type)
        return s to DynRos.publisher(topic, s, qos).also { pubs += it }
    }

    private val tf = pub("/tf", "tf2_msgs/msg/TFMessage")
    private val tfStatic = pub("/tf_static", "tf2_msgs/msg/TFMessage", latched = true)
    private val odom = pub("/odom", "nav_msgs/msg/Odometry")
    private val scan = pub("/scan", "sensor_msgs/msg/LaserScan")
    private val map = pub("/map", "nav_msgs/msg/OccupancyGrid", latched = true)
    private val image = pub("/camera/image_raw", "sensor_msgs/msg/Image")
    /** The camera intrinsics (so the Camera display works with the demo) and two annotations on the image. */
    private val cameraInfo = pub("/camera/camera_info", "sensor_msgs/msg/CameraInfo")
    private val imageMarkers = pub("/camera/image_markers", "visualization_msgs/msg/ImageMarker")
    /** The same camera as H.264 video, where the platform has an encoder (the phone's MediaCodec, ffmpeg on the PC). */
    private val video: DemoVideoSink? = videoFactory?.invoke { t, type -> pub(t, type) }
    private val diag = pub("/diagnostics", "diagnostic_msgs/msg/DiagnosticArray")
    private val description = pub("/robot_description", "std_msgs/msg/String", latched = true)
    private val rosout = pub("/rosout", "rcl_interfaces/msg/Log")
    private val path = pub("/plan", "nav_msgs/msg/Path")
    private val batteryState = pub("/battery_state", "sensor_msgs/msg/BatteryState")
    private val gps = pub("/gps/fix", "sensor_msgs/msg/NavSatFix")

    fun start() {
        cmdSub = DynRos.subscribe("/cmd_vel", spec("geometry_msgs/msg/Twist"), RosConnection.defaultQos()) { t ->
            val lin = (t["linear"] as? Map<*, *>)?.get("x") as? Number
            val ang = (t["angular"] as? Map<*, *>)?.get("z") as? Number
            cmd = (lin?.toDouble() ?: 0.0) to (ang?.toDouble() ?: 0.0)
            lastCmdMs = System.currentTimeMillis()
        }
        exec.scheduleAtFixedRate({ safe { tick() } }, 300, PERIOD_MS, TimeUnit.MILLISECONDS)
        services = DemoServices(registry, this).also { it.start() }
    }

    fun stop() {
        exec.shutdownNow()
        // The encoder is used from the simulator thread: closed once that one has finished.
        runCatching { exec.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        video?.close()
        services?.close()
        cmdSub?.close()
        for (p in pubs) p.close()
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.e("VizMini2", "Error in the demo robot", t) }
    }

    // ---------------------------------------------------------------- simulation

    private fun tick() {
        step++
        val dt = PERIOD_MS / 1000.0
        // Teleop if there is a recent /cmd_vel; otherwise it drives around the room.
        val manual = System.currentTimeMillis() - lastCmdMs < 600
        val (tv, tw) = when {
            !drivesOn -> 0.0 to 0.0
            manual -> cmd.first.coerceIn(-maxLinear, maxLinear) to cmd.second.coerceIn(-maxAngular, maxAngular)
            autopilotOn -> autopilot()
            else -> 0.0 to 0.0
        }
        // At most 4 m/s² and 8 rad/s² (limiting the step itself: scaling a limited step overshoots, and near the
        // goal the speed kept flipping ±0.05 m/s instead of settling).
        v += (tv - v).coerceIn(-4.0 * dt, 4.0 * dt)
        w += (tw - w).coerceIn(-8.0 * dt, 8.0 * dt)
        val nx = x + v * cos(yaw) * dt
        val ny = y + v * sin(yaw) * dt
        if (!DemoWorld.occupied(nx, ny, ROBOT_RADIUS)) { x = nx; y = ny } else v = 0.0
        yaw = norm(yaw + w * dt)
        battery = max(5.0, battery - 0.002)

        val now = System.currentTimeMillis() * 1_000_000L
        publishTf(now)
        if (step % 2 == 0L) publishOdom(now)
        if (step % 4 == 0L) publishScan(now)
        if (step % 8 == 0L && cameraOn) publishImage(now)
        if (step % 40 == 0L) publishDiagnostics(now)
        if (step % 40 == 20L) publishBattery(now)
        if (step % 20 == 5L) publishGps(now)
        if (step % 20 == 1L) publishPlan(now)
        if (step == 1L || step % 200 == 0L) { publishStatic(now); publishMap(now); publishDescription() }
        if (step == 3L) log(now, 20, "Demo robot ready. Drive it from a Teleop tab.")
        if (step % 600 == 0L) log(now, if (battery < 30) 30 else 20, "Battery at %.0f %%".format(battery))
    }

    /** Follows a route around the room avoiding walls. */
    private fun autopilot(): Pair<Double, Double> {
        val ahead = DemoWorld.ray(x, y, yaw, 3.0)
        val left = DemoWorld.ray(x, y, yaw + 0.6, 3.0)
        val right = DemoWorld.ray(x, y, yaw - 0.6, 3.0)
        return when {
            ahead < 0.8 -> 0.05 to if (left > right) 1.2 else -1.2
            ahead < 1.5 -> 0.25 to (if (left > right) 0.6 else -0.6)
            else -> 0.45 to 0.15 * sin(step / 50.0)
        }
    }

    // ---------------------------------------------------------------- messages

    private fun header(now: Long, frame: String) = MsgValue().apply {
        put("stamp", DynCodec.timeValue(now))
        put("frame_id", frame)
    }

    private fun transform(now: Long, parent: String, child: String, tx: Double, ty: Double, tz: Double, yaw: Double) = MsgValue().apply {
        put("header", header(now, parent))
        put("child_frame_id", child)
        put("transform", MsgValue().apply {
            put("translation", vec(tx, ty, tz))
            put("rotation", quat(yaw))
        })
    }

    private fun vec(x: Double, y: Double, z: Double) = MsgValue().apply { put("x", x); put("y", y); put("z", z) }
    private fun quat(yaw: Double) = MsgValue().apply { put("x", 0.0); put("y", 0.0); put("z", sin(yaw / 2)); put("w", cos(yaw / 2)) }

    private fun publishStatic(now: Long) {
        tfStatic.second.publish(MsgValue().apply {
            put("transforms", listOf(
                transform(now, "map", "odom", 0.0, 0.0, 0.0, 0.0),
                transform(now, "base_link", "laser", 0.12, 0.0, 0.28, 0.0),
                transform(now, "base_link", "camera_link", 0.2, 0.0, 0.22, 0.0),
                // Optical frame (z forward, x right, y down), as REP 103 asks for camera images.
                transform(now, "camera_link", "camera_optical", 0.0, 0.0, 0.0, 0.0).apply {
                    (get("transform") as MsgValue)["rotation"] = MsgValue().apply { put("x", -0.5); put("y", 0.5); put("z", -0.5); put("w", 0.5) }
                },
                transform(now, "base_link", "left_wheel", 0.0, 0.17, 0.05, 0.0),
                transform(now, "base_link", "right_wheel", 0.0, -0.17, 0.05, 0.0),
            ))
        })
    }

    private fun publishTf(now: Long) {
        tf.second.publish(MsgValue().apply { put("transforms", listOf(transform(now, "odom", "base_link", x, y, 0.0, yaw))) })
    }

    private fun publishOdom(now: Long) {
        odom.second.publish(MsgValue().apply {
            put("header", header(now, "odom"))
            put("child_frame_id", "base_link")
            put("pose", MsgValue().apply {
                put("pose", MsgValue().apply { put("position", vec(x, y, 0.0)); put("orientation", quat(yaw)) })
                put("covariance", MutableList(36) { if (it % 7 == 0) 0.01 else 0.0 })
            })
            put("twist", MsgValue().apply {
                put("twist", MsgValue().apply { put("linear", vec(v, 0.0, 0.0)); put("angular", vec(0.0, 0.0, w)) })
                put("covariance", MutableList(36) { 0.0 })
            })
        })
    }

    private fun publishScan(now: Long) {
        val n = 360
        val lx = x + 0.12 * cos(yaw)
        val ly = y + 0.12 * sin(yaw)
        val ranges = ArrayList<Double>(n)
        for (i in 0 until n) {
            val a = yaw - PI + i * 2 * PI / n
            ranges += DemoWorld.ray(lx, ly, a, 12.0) + 0.01 * sin(step * 0.7 + i)
        }
        scan.second.publish(MsgValue().apply {
            put("header", header(now, "laser"))
            put("angle_min", -PI); put("angle_max", PI - 2 * PI / n); put("angle_increment", 2 * PI / n)
            put("time_increment", 0.0); put("scan_time", 0.1)
            put("range_min", 0.1); put("range_max", 12.0)
            put("ranges", ranges); put("intensities", ArrayList<Double>())
        })
    }

    private fun publishMap(now: Long) {
        val res = 0.05
        val w = (DemoWorld.WIDTH / res).toInt()
        val h = (DemoWorld.HEIGHT / res).toInt()
        val data = ArrayList<Long>(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            data += if (DemoWorld.occupied((i + 0.5) * res, (j + 0.5) * res, 0.0)) 100L else 0L
        }
        map.second.publish(MsgValue().apply {
            put("header", header(now, "map"))
            put("info", MsgValue().apply {
                put("map_load_time", DynCodec.timeValue(now))
                put("resolution", res)
                put("width", w.toLong()); put("height", h.toLong())
                put("origin", MsgValue().apply { put("position", vec(0.0, 0.0, 0.0)); put("orientation", quat(0.0)) })
            })
            put("data", data)
        })
    }

    private fun publishPlan(now: Long) {
        // A curved plan toward the center of the room, like a navigator's.
        val gx = DemoWorld.WIDTH / 2; val gy = DemoWorld.HEIGHT / 2
        val poses = (0..20).map { k ->
            val t = k / 20.0
            val px = x + (gx - x) * t + 0.6 * sin(t * PI)
            val py = y + (gy - y) * t
            MsgValue().apply {
                put("header", header(now, "map"))
                put("pose", MsgValue().apply { put("position", vec(px, py, 0.0)); put("orientation", quat(atan2(gy - y, gx - x))) })
            }
        }
        path.second.publish(MsgValue().apply { put("header", header(now, "map")); put("poses", poses) })
    }

    /** Synthetic camera view: sky, floor with perspective and the boxes in front. */
    private fun publishImage(now: Long) {
        val w = 320; val h = 240
        val data = ByteArray(w * h * 3)
        val horizon = h / 2
        for (u in 0 until w) {
            val a = yaw + (0.5 - u.toDouble() / w) * 1.2
            val d = DemoWorld.ray(x, y, a, 15.0)
            val color = DemoWorld.colorAt(x + d * cos(a), y + d * sin(a))
            val wallH = min(horizon.toDouble(), 140.0 / max(d * cos(a - yaw), 0.2)).toInt()
            for (vv in 0 until h) {
                val i = (vv * w + u) * 3
                val (r, g, b) = when {
                    abs(vv - horizon) < wallH -> { val shade = (1.0 - min(d / 12.0, 0.7)); Triple((color shr 16 and 0xFF) * shade, (color shr 8 and 0xFF) * shade, (color and 0xFF) * shade) }
                    vv < horizon -> Triple(150.0 + vv * 0.3, 190.0 + vv * 0.2, 235.0)
                    else -> { val k = (vv - horizon).toDouble() / horizon; val checker = if ((floor(k * 12 + step * v * 0.05).toInt() + u / 40) % 2 == 0) 90 else 110; Triple(checker.toDouble(), checker * 0.95, checker * 0.85) }
                }
                data[i] = r.toInt().coerceIn(0, 255).toByte(); data[i + 1] = g.toInt().coerceIn(0, 255).toByte(); data[i + 2] = b.toInt().coerceIn(0, 255).toByte()
            }
        }
        image.second.publish(MsgValue().apply {
            put("header", header(now, "camera_optical"))
            put("height", h.toLong()); put("width", w.toLong())
            put("encoding", "rgb8"); put("is_bigendian", 0L); put("step", (w * 3).toLong())
            put("data", data)
        })
        video?.frame(data, w, h, DynCodec.timeValue(now))
        // Pinhole with the same horizontal field of view as the synthetic image (1.2 rad).
        val f = (w / 2.0) / kotlin.math.tan(0.6)
        cameraInfo.second.publish(MsgValue().apply {
            put("header", header(now, "camera_optical"))
            put("height", h.toLong()); put("width", w.toLong())
            put("distortion_model", "plumb_bob"); put("d", listOf(0.0, 0.0, 0.0, 0.0, 0.0))
            put("k", listOf(f, 0.0, w / 2.0, 0.0, f, h / 2.0, 0.0, 0.0, 1.0))
            put("r", listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
            put("p", listOf(f, 0.0, w / 2.0, 0.0, 0.0, f, h / 2.0, 0.0, 0.0, 0.0, 1.0, 0.0))
        })
        // A "detection" box in the middle and a circle that goes round: so the annotations can be shown or hidden.
        fun marker(id: Long, type: Long, color: Triple<Double, Double, Double>, points: List<Pair<Double, Double>>, scale: Double = 2.0, center: Pair<Double, Double>? = null) =
            MsgValue().apply {
                put("header", header(now, "camera_optical")); put("ns", "demo"); put("id", id); put("type", type); put("action", 0L)
                put("position", vec(center?.first ?: 0.0, center?.second ?: 0.0, 0.0)); put("scale", scale)
                put("outline_color", MsgValue().apply { put("r", color.first); put("g", color.second); put("b", color.third); put("a", 1.0) })
                put("filled", 0L); put("fill_color", MsgValue().apply { put("r", 0.0); put("g", 0.0); put("b", 0.0); put("a", 0.0) })
                put("lifetime", MsgValue().apply { put("sec", 2L); put("nanosec", 0L) })
                put("points", points.map { (px, py) -> vec(px, py, 0.0) }); put("outline_colors", emptyList<Any>())
            }
        imageMarkers.second.publish(marker(0, 3, Triple(1.0, 0.2, 0.2), listOf(110.0 to 80.0, 210.0 to 80.0, 210.0 to 160.0, 110.0 to 160.0)))
        val t = step * 0.05
        imageMarkers.second.publish(marker(1, 0, Triple(1.0, 0.9, 0.1), emptyList(), scale = 18.0, center = (160.0 + 90 * kotlin.math.cos(t)) to (120.0 + 60 * kotlin.math.sin(t))))
    }

    private fun publishDiagnostics(now: Long) {
        fun status(level: Long, name: String, msg: String, vararg kv: Pair<String, String>) = MsgValue().apply {
            put("level", level); put("name", name); put("message", msg); put("hardware_id", "demo_robot")
            put("values", kv.map { (k, v) -> MsgValue().apply { put("key", k); put("value", v) } })
        }
        val batLevel = when { battery < 15 -> 2L; battery < 30 -> 1L; else -> 0L }
        diag.second.publish(MsgValue().apply {
            put("header", header(now, ""))
            put("status", listOf(
                status(batLevel, "demo_robot: Battery", if (batLevel == 0L) "OK" else "Low battery", "charge" to "%.1f %%".format(battery), "voltage" to "%.2f V".format(22.0 + battery * 0.04)),
                status(0, "demo_robot: Motors", "OK", "left_rpm" to "%.0f".format((v - w * 0.17) * 95), "right_rpm" to "%.0f".format((v + w * 0.17) * 95), "temperature" to "41 °C"),
                status(0, "demo_robot: Lidar", "OK", "frequency" to "10.0 Hz"),
                status(if (abs(v) > 0.6) 1L else 0L, "demo_robot: Speed", if (abs(v) > 0.6) "Fast" else "OK", "linear" to "%.2f m/s".format(v), "angular" to "%.2f rad/s".format(w)),
            ))
        })
    }

    /** Battery as sensor_msgs/BatteryState (for the demo indicators). */
    private fun publishBattery(now: Long) {
        batteryState.second.publish(MsgValue().apply {
            put("header", header(now, "base_link"))
            put("voltage", (22.0 + battery * 0.04).toFloat())
            put("current", (-1.5 - abs(v) * 4).toFloat())
            put("percentage", (battery / 100).toFloat())
            put("power_supply_status", 2L) // DISCHARGING
            put("present", true)
            put("location", "base")
        })
    }

    /** GPS: the robot position (x east, y north) around a fixed point, with ±0.8 m. */
    private fun publishGps(now: Long) {
        val (lat, lon) = com.juancams.rviz2mini.gps.GpsMath.offset(GPS_ORIGIN_LAT, GPS_ORIGIN_LON, x, y)
        gps.second.publish(MsgValue().apply {
            put("header", header(now, "gps"))
            put("status", MsgValue().apply { put("status", 0L); put("service", 1L) })
            put("latitude", lat); put("longitude", lon); put("altitude", 650.0)
            put("position_covariance", listOf(0.64, 0.0, 0.0, 0.0, 0.64, 0.0, 0.0, 0.0, 2.0))
            put("position_covariance_type", 2L) // DIAGONAL_KNOWN
        })
    }

    private fun publishDescription() {
        description.second.publish(MsgValue().apply { put("data", URDF) })
    }

    private fun log(now: Long, level: Long, msg: String) {
        rosout.second.publish(MsgValue().apply {
            put("stamp", DynCodec.timeValue(now)); put("level", level); put("name", "demo_robot"); put("msg", msg)
            put("file", "demo_robot.kt"); put("function", "tick"); put("line", 1L)
        })
    }

    private fun norm(a: Double): Double { var r = a; while (r > PI) r -= 2 * PI; while (r < -PI) r += 2 * PI; return r }

    companion object {
        /** The demo's network: its own domain and only the loopback, so the simulated robot never leaves this device. */
        val NETWORK = com.juancams.rviz2mini.ros.RosConfig(domainId = 231, nodeName = "vizmini2_demo", interfaceWhitelist = "127.0.0.1")
        /** The H.264 camera (foxglove_msgs/CompressedVideo, as foxglove_compressed_video_transport publishes it). */
        const val VIDEO_TOPIC = "/camera/image_raw/foxglove"
        private const val PERIOD_MS = 25L
        /** Where the room's (0, 0) is for the demo GPS (Puerta del Sol, Madrid). */
        private const val GPS_ORIGIN_LAT = 40.416775
        private const val GPS_ORIGIN_LON = -3.703790
        private const val ROBOT_RADIUS = 0.22
        private const val HOME_X = 1.5
        private const val HOME_Y = 4.0

        /** Simple differential robot for the RobotModel display. */
        val URDF = """
            <?xml version="1.0"?>
            <robot name="demo_robot">
              <material name="teal"><color rgba="0.05 0.49 0.53 1"/></material>
              <material name="dark"><color rgba="0.15 0.15 0.17 1"/></material>
              <material name="red"><color rgba="0.9 0.3 0.3 1"/></material>
              <link name="base_link"><visual><origin xyz="0 0 0.12"/><geometry><box size="0.42 0.32 0.14"/></geometry><material name="teal"/></visual></link>
              <link name="laser"><visual><geometry><cylinder radius="0.04" length="0.05"/></geometry><material name="dark"/></visual></link>
              <link name="camera_link"><visual><geometry><box size="0.04 0.1 0.04"/></geometry><material name="red"/></visual></link>
              <link name="left_wheel"><visual><origin rpy="1.5708 0 0"/><geometry><cylinder radius="0.05" length="0.03"/></geometry><material name="dark"/></visual></link>
              <link name="right_wheel"><visual><origin rpy="1.5708 0 0"/><geometry><cylinder radius="0.05" length="0.03"/></geometry><material name="dark"/></visual></link>
              <joint name="laser_joint" type="fixed"><parent link="base_link"/><child link="laser"/><origin xyz="0.12 0 0.28"/></joint>
              <joint name="camera_joint" type="fixed"><parent link="base_link"/><child link="camera_link"/><origin xyz="0.2 0 0.22"/></joint>
              <joint name="left_wheel_joint" type="fixed"><parent link="base_link"/><child link="left_wheel"/><origin xyz="0 0.17 0.05"/></joint>
              <joint name="right_wheel_joint" type="fixed"><parent link="base_link"/><child link="right_wheel"/><origin xyz="0 -0.17 0.05"/></joint>
            </robot>
        """.trimIndent()
    }
}

/** Demo room: walls and boxes in a 10 × 8 m rectangle (origin at one corner). */
object DemoWorld {
    const val WIDTH = 10.0
    const val HEIGHT = 8.0

    /** Boxes: (x0, y0, x1, y1, RGB color). */
    private val boxes = listOf(
        doubleArrayOf(3.0, 1.0, 3.6, 2.6) to 0xE07A5F,
        doubleArrayOf(6.5, 5.0, 8.0, 5.6) to 0x3D9970,
        doubleArrayOf(5.0, 3.2, 5.5, 3.7) to 0xF2CC8F,
        doubleArrayOf(8.2, 1.2, 9.2, 2.2) to 0x6C8EBF,
    )

    fun occupied(px: Double, py: Double, margin: Double): Boolean {
        if (px < margin || py < margin || px > WIDTH - margin || py > HEIGHT - margin) return true
        return boxes.any { (b, _) -> px > b[0] - margin && px < b[2] + margin && py > b[1] - margin && py < b[3] + margin }
    }

    /** Distance to the first obstacle in direction `a` (step by step: good enough for the demo). */
    fun ray(px: Double, py: Double, a: Double, maxRange: Double): Double {
        val dx = cos(a); val dy = sin(a)
        var d = 0.0
        while (d < maxRange) {
            if (occupied(px + dx * d, py + dy * d, 0.0)) return d
            d += 0.03
        }
        return maxRange
    }

    /** Color of the obstacle at that point (walls in light gray). */
    fun colorAt(px: Double, py: Double): Int =
        boxes.firstOrNull { (b, _) -> px > b[0] - 0.05 && px < b[2] + 0.05 && py > b[1] - 0.05 && py < b[3] + 0.05 }?.second
            ?: if (hypot(px - WIDTH / 2, py) < 1.0) 0x9AA5B1 else 0xC9CED6
}
