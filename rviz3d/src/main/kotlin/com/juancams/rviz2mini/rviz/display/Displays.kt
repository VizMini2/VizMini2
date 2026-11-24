// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.display

import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.TfTree
import com.juancams.rviz2mini.rviz.FilamentHub
import com.juancams.rviz2mini.rviz.geom.Covariance
import com.juancams.rviz2mini.rviz.geom.LaserScanData
import com.juancams.rviz2mini.rviz.geom.LaserScanGeometry
import com.juancams.rviz2mini.rviz.geom.MarkerData
import com.juancams.rviz2mini.rviz.geom.MarkerStore
import com.juancams.rviz2mini.rviz.geom.MeshBuilder
import com.juancams.rviz2mini.rviz.geom.OccupancyGridImage
import com.juancams.rviz2mini.rviz.geom.OdometryTrail
import com.juancams.rviz2mini.rviz.geom.PointCloud2Decoder
import com.juancams.rviz2mini.rviz.geom.PointCloudGeometry
import com.juancams.rviz2mini.rviz.geom.PointFieldInfo
import com.juancams.rviz2mini.rviz.geom.PointList
import com.juancams.rviz2mini.rviz.geom.TextLabel
import com.juancams.rviz2mini.rviz.render.TextureQuadEntity
import geometry_msgs.PointStamped
import geometry_msgs.PolygonStamped
import geometry_msgs.Pose
import geometry_msgs.PoseArray
import geometry_msgs.PoseStamped
import geometry_msgs.PoseWithCovarianceStamped
import nav_msgs.OccupancyGrid
import nav_msgs.Odometry
import nav_msgs.Path
import sensor_msgs.LaserScan
import sensor_msgs.PointCloud
import sensor_msgs.PointCloud2
import sensor_msgs.Range
import visualization_msgs.Marker
import visualization_msgs.MarkerArray
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Creates the right display for a configuration. */
object DisplayFactory {
    /** Displays each platform draws its own way: Image (a 2D image over the view). */
    @Volatile var platformDisplays: (DisplayHost, DisplayConfig) -> Display? = { _, _ -> null }

    fun create(host: DisplayHost, c: DisplayConfig): Display = when (c.type) {
        DisplayType.GRID -> GridDisplay(host, c)
        DisplayType.TF -> TfDisplay(host, c)
        DisplayType.AXES -> AxesDisplay(host, c)
        DisplayType.ROBOT_MODEL -> RobotModelDisplay(host, c)
        DisplayType.MAP -> MapDisplay(host, c)
        DisplayType.LASER_SCAN -> LaserScanDisplay(host, c)
        DisplayType.POINT_CLOUD2 -> PointCloud2Display(host, c)
        DisplayType.POINT_CLOUD -> PointCloudDisplay(host, c)
        DisplayType.ODOMETRY -> OdometryDisplay(host, c)
        DisplayType.PATH -> PathDisplay(host, c)
        DisplayType.POSE -> PoseDisplay(host, c)
        DisplayType.POSE_COV -> PoseCovarianceDisplay(host, c)
        DisplayType.POSE_ARRAY -> PoseArrayDisplay(host, c)
        DisplayType.POINT -> PointStampedDisplay(host, c)
        DisplayType.POLYGON -> PolygonDisplay(host, c)
        DisplayType.MARKER -> MarkerDisplay(host, c)
        DisplayType.MARKER_ARRAY -> MarkerArrayDisplay(host, c)
        DisplayType.RANGE -> RangeDisplay(host, c)
        DisplayType.CAMERA -> CameraDisplay(host, c)
        DisplayType.DEPTH_CLOUD -> DepthCloudDisplay(host, c)
        DisplayType.IMAGE -> platformDisplays(host, c) ?: UnavailableDisplay(host, c)
        DisplayType.EFFORT -> EffortDisplay(host, c)
        DisplayType.INTERACTIVE_MARKERS -> InteractiveMarkersDisplay(host, c)
        DisplayType.TRAJECTORY -> TrajectoryDisplay(host, c)
        DisplayType.PLANNING_SCENE -> PlanningSceneDisplay(host, c)
        DisplayType.NAVMAP -> NavMapDisplay(host, c)
        DisplayType.GRID_CELLS, DisplayType.WRENCH, DisplayType.TWIST, DisplayType.ACCEL, DisplayType.TEMPERATURE,
        DisplayType.ILLUMINANCE, DisplayType.FLUID_PRESSURE, DisplayType.RELATIVE_HUMIDITY -> createExtraDisplay(host, c)!!
    }

    /**
     * Displays that can draw a discovered topic with these types. RobotModel only for robot
     * description topics (not every std_msgs/String is a URDF).
     */
    fun displaysFor(topic: String, rosTypes: List<String>): List<DisplayType> =
        DisplayType.TOPIC_TYPES.filter { type ->
            (type.rosType in rosTypes || (type in COMPRESSED_OK && "sensor_msgs/msg/CompressedImage" in rosTypes) ||
                (type in VIDEO_OK && rosTypes.any { t -> com.juancams.rviz2mini.camera.ImageKind.entries.any { it.isVideo && it.rosType == t } })) &&
                (type != DisplayType.ROBOT_MODEL || topic.endsWith("robot_description")) &&
                // A depth image is shown as a cloud; the others, as an image or camera.
                (type != DisplayType.DEPTH_CLOUD || "depth" in topic.lowercase())
        }

    private val COMPRESSED_OK = setOf(DisplayType.IMAGE, DisplayType.CAMERA, DisplayType.DEPTH_CLOUD)
    /** H.264/H.265 video topics: shown as an image or a camera. */
    private val VIDEO_OK = setOf(DisplayType.IMAGE, DisplayType.CAMERA)

    /** Probe request with the type's message class (null if the type does not use a topic). */
    fun probeRequest(type: DisplayType, topic: String): com.juancams.rviz2mini.ros.RosConnection.ProbeRequest<*>? {
        fun <T : us.ihmc.jros2.ROS2Message<T>> r(c: Class<T>) = com.juancams.rviz2mini.ros.RosConnection.ProbeRequest(topic, c)
        return when (type) {
            DisplayType.GRID, DisplayType.TF, DisplayType.AXES, DisplayType.IMAGE, DisplayType.CAMERA, DisplayType.DEPTH_CLOUD, DisplayType.EFFORT,
            DisplayType.TRAJECTORY, DisplayType.PLANNING_SCENE, DisplayType.NAVMAP -> null
            DisplayType.ROBOT_MODEL -> r(std_msgs.String_::class.java)
            DisplayType.MAP -> r(OccupancyGrid::class.java)
            DisplayType.LASER_SCAN -> r(LaserScan::class.java)
            DisplayType.POINT_CLOUD2 -> r(PointCloud2::class.java)
            DisplayType.POINT_CLOUD -> r(PointCloud::class.java)
            DisplayType.ODOMETRY -> r(Odometry::class.java)
            DisplayType.PATH -> r(Path::class.java)
            DisplayType.POSE -> r(PoseStamped::class.java)
            DisplayType.POSE_COV -> r(PoseWithCovarianceStamped::class.java)
            DisplayType.POSE_ARRAY -> r(PoseArray::class.java)
            DisplayType.POINT -> r(PointStamped::class.java)
            DisplayType.POLYGON -> r(PolygonStamped::class.java)
            DisplayType.MARKER -> r(Marker::class.java)
            DisplayType.MARKER_ARRAY -> r(MarkerArray::class.java)
            DisplayType.RANGE -> r(Range::class.java)
            DisplayType.GRID_CELLS -> r(nav_msgs.GridCells::class.java)
            DisplayType.WRENCH -> r(geometry_msgs.WrenchStamped::class.java)
            DisplayType.TWIST -> r(geometry_msgs.TwistStamped::class.java)
            DisplayType.ACCEL -> r(geometry_msgs.AccelStamped::class.java)
            DisplayType.TEMPERATURE -> r(sensor_msgs.Temperature::class.java)
            DisplayType.ILLUMINANCE -> r(sensor_msgs.Illuminance::class.java)
            DisplayType.FLUID_PRESSURE -> r(sensor_msgs.FluidPressure::class.java)
            DisplayType.RELATIVE_HUMIDITY -> r(sensor_msgs.RelativeHumidity::class.java)
            DisplayType.INTERACTIVE_MARKERS -> r(visualization_msgs.InteractiveMarkerUpdate::class.java)
        }
    }
}

// ---------------------------------------------------------------------- conversion utilities

private fun Pose.matrix(): DoubleArray = TfMath.fromTranslationQuaternion(
    position.x, position.y, position.z, orientation.x, orientation.y, orientation.z, orientation.w
)

private fun colorOf(c: std_msgs.ColorRGBA): Int = Colors.rgbf(c.r, c.g, c.b, c.a)

/** Header stamp in ns (0 = "no stamp": the most recent TF will be used). */
private fun std_msgs.Header.stampNs(): Long = stamp.sec * 1_000_000_000L + (stamp.nanosec.toLong() and 0xFFFFFFFFL)

/** Color of property `key` with the display's "alpha" applied. */
private fun DisplayConfig.alphaColor(key: String = "color"): Int = Colors.withAlpha(color(key), number("alpha").toFloat())

private fun DisplayConfig.alpha255(): Int = (number("alpha") * 255 + 0.5).toInt().coerceIn(0, 255)

/** Width in meters if the style is "billboards"; 0 = 1 px lines. */
private fun DisplayConfig.lineWidth(): Double = if (string("lineStyle") == "billboards") number("lineWidth") else 0.0

private fun DisplayConfig.colorOptions() = com.juancams.rviz2mini.rviz.geom.ColorOptions(
    string("colorMode"), color("color"), bool("autoBounds"), number("minValue"), number("maxValue"), number("alpha").toFloat()
)

/** Arrow or axes at the builder's current origin, with RViz's dimensions. */
private fun drawPoseShape(c: DisplayConfig, b: MeshBuilder) {
    if (c.string("shape") == "axes") b.axes(c.number("axesLength"), c.number("axesRadius"), c.alpha255())
    else b.arrow(
        c.number("shaftLength") + c.number("headLength"), c.alphaColor(),
        c.number("shaftRadius"), c.number("headLength"), c.number("headRadius")
    )
}

/** Topic-less display with fixed geometry hanging from a frame. */
private abstract class StaticGeometryDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {
    protected val geometry = GeometrySet(host.engine, host.scene)
    private var dirty = true

    protected abstract fun build(b: MeshBuilder)
    protected abstract fun frame(ctx: FrameContext): String
    /** Additional transform within the frame (offsets). */
    protected open fun local(): DoubleArray? = null

    override fun onConfigChanged(old: DisplayConfig) { dirty = true }
    override fun setVisible(v: Boolean) = geometry.setVisible(v)

    override fun render(ctx: FrameContext) {
        if (dirty) {
            geometry.update(MeshBuilder().also { build(it) })
            dirty = false
        }
        val f = frame(ctx)
        val local = local()
        val t = if (local == null) ctx.transformF(f) else ctx.transformF(f, local)
        if (t == null) {
            geometry.setVisible(false)
            tfMissing(f, ctx)
            return
        }
        geometry.setVisible(true)
        geometry.setTransform(t)
        setStatus(StatusLevel.OK, "")
    }

    override fun destroy() {
        super.destroy()
        geometry.destroy()
    }
}

// ====================================================================== Grid

private class GridDisplay(host: DisplayHost, config: DisplayConfig) : StaticGeometryDisplay(host, config) {
    override fun frame(ctx: FrameContext) = config.string("frame").ifEmpty { ctx.fixedFrame }
    override fun local() = MeshBuilder.translation(config.number("offsetX"), config.number("offsetY"), config.number("offsetZ"))

    override fun build(b: MeshBuilder) {
        val size = config.number("cellSize")
        val n = config.int("cellCount")
        val color = config.alphaColor()
        val width = config.lineWidth()
        val half = size * n / 2
        // (u, v) of the chosen plane -> (x, y, z)
        fun line(u1: Double, v1: Double, u2: Double, v2: Double) = when (config.string("plane")) {
            "XZ" -> b.thickLine(u1, 0.0, v1, u2, 0.0, v2, color, width)
            "YZ" -> b.thickLine(0.0, u1, v1, 0.0, u2, v2, color, width)
            else -> b.thickLine(u1, v1, 0.0, u2, v2, 0.0, color, width)
        }
        for (i in 0..n) {
            val v = -half + i * size
            line(-half, v, half, v)
            line(v, -half, v, half)
        }
    }
}

// ====================================================================== Axes

private class AxesDisplay(host: DisplayHost, config: DisplayConfig) : StaticGeometryDisplay(host, config) {
    override fun frame(ctx: FrameContext) = config.string("frame").ifEmpty { ctx.fixedFrame }
    override fun build(b: MeshBuilder) = b.axes(config.number("length"), config.number("radius"), config.alpha255())
}

// ====================================================================== TF

private class TfDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {
    private val geometry = GeometrySet(host.engine, host.scene)
    private val builder = MeshBuilder()
    private val pose = DoubleArray(16)
    private val parentPose = DoubleArray(16)
    private val rootPose = DoubleArray(16)
    private val tmp = DoubleArray(16)
    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** With topics other than /tf and /tf_static (e.g. from a namespaced robot), its own tree with its own subscriptions. */
    private var own: TfTree? = null
    private val subs = ArrayList<com.juancams.rviz2mini.ros.dyn.DynRos.Subscription>()
    private var subError: String? = null

    override fun setVisible(v: Boolean) = geometry.setVisible(v)

    override fun onActivate() = subscribe()
    override fun onDeactivate() = unsubscribe()
    override fun onConfigChanged(old: DisplayConfig) {
        if (active && old.string("topics") != config.string("topics")) { unsubscribe(); subscribe() }
    }

    private fun subscribe() {
        val topics = TfFrames.topics(config.string("topics"))
        if (TfFrames.isDefault(topics)) return
        val tree = TfTree().also { own = it }
        subError = null
        try {
            val type = com.juancams.rviz2mini.ros.dyn.Interfaces.get().msg("tf2_msgs/msg/TFMessage")
            for (t in topics) {
                val static = TfFrames.isStatic(t)
                subs += com.juancams.rviz2mini.ros.dyn.DynRos.subscribe(t, type,
                    TopicDisplay.qosProfile(if (static) QosPreset.LATCHED else QosPreset.RELIABLE)) { v -> feed(tree, v, static) }
            }
        } catch (e: Exception) { subError = e.message ?: e.toString() }
    }

    private fun unsubscribe() { subs.forEach { runCatching { it.close() } }; subs.clear(); own = null }

    private fun feed(tree: TfTree, v: Map<*, *>, static: Boolean) {
        fun num(m: Any?, k: String) = ((m as? Map<*, *>)?.get(k) as? Number)?.toDouble() ?: 0.0
        for (t in (v["transforms"] as? List<*>).orEmpty()) {
            val m = t as? Map<*, *> ?: continue
            val h = m["header"] as? Map<*, *>
            val st = h?.get("stamp") as? Map<*, *>
            val stamp = ((st?.get("sec") as? Number)?.toLong() ?: 0L) * 1_000_000_000L + ((st?.get("nanosec") as? Number)?.toLong() ?: 0L)
            val tr = m["transform"] as? Map<*, *>
            val p = tr?.get("translation"); val q = tr?.get("rotation")
            tree.update(h?.get("frame_id")?.toString().orEmpty(), m["child_frame_id"]?.toString().orEmpty(),
                num(p, "x"), num(p, "y"), num(p, "z"), num(q, "x"), num(q, "y"), num(q, "z"), num(q, "w").takeIf { it != 0.0 || num(q, "x") != 0.0 } ?: 1.0,
                static, stamp)
        }
    }

    /** The frames this display sees (for the editor: those of its own tree, if it has one). */
    override fun knownFrames(): List<String>? = own?.frameIds()

    override fun render(ctx: FrameContext) {
        builder.clear()
        val scale = config.number("scale")
        val showAxes = config.bool("showAxes")
        val showArrows = config.bool("showArrows")
        val showNames = config.bool("showNames")
        val hiddenPatterns = TfFrames.patterns(config.string("hiddenFrames"))
        val tree = own?.also { it.compute(ctx.fixedFrame) } ?: ctx.tf
        // Own tree without the fixed frame: it hangs from the general tree by its root (if the general one knows it).
        fun place(id: String, out: DoubleArray): Boolean {
            if (!tree.poseInFixed(id, out)) return false
            if (tree === ctx.tf || tree.contains(TfTree.normalizeId(ctx.fixedFrame))) return true
            val root = tree.rootOf(id) ?: return true
            if (!ctx.tf.poseInFixed(root, rootPose)) return true
            out.copyInto(tmp); TfMath.multiply(rootPose, tmp, out)
            return true
        }
        var visibleFrames = 0
        var staleCount = 0
        var hidden = 0
        val staleNow = System.nanoTime()
        val timeoutNs = (config.number("frameTimeout") * 1e9).toLong()
        val ids = tree.frameIds()
        for (id in ids) {
            if (TfFrames.hidden(id, hiddenPatterns)) { hidden++; continue }
            if (!place(id, pose)) continue
            visibleFrames++
            val stale = tree.isStale(id, staleNow, timeoutNs)
            if (stale) staleCount++
            if (showAxes) builder.withTransform(pose) {
                if (stale) {
                    // Stale: gray axes (like RViz).
                    line(0.0, 0.0, 0.0, 0.5 * scale, 0.0, 0.0, STALE_COLOR); line(0.0, 0.0, 0.0, 0.0, 0.5 * scale, 0.0, STALE_COLOR)
                    line(0.0, 0.0, 0.0, 0.0, 0.0, 0.5 * scale, STALE_COLOR)
                } else lineAxes(0.5 * scale)
            }
            if (showArrows) {
                val parent = tree.parentOf(id)
                if (!parent.isNullOrEmpty() && !TfFrames.hidden(parent, hiddenPatterns) && place(parent, parentPose)) {
                    builder.line(parentPose[12], parentPose[13], parentPose[14], pose[12], pose[13], pose[14], ARROW_COLOR)
                }
            }
            if (showNames) ctx.labels.label("tf:$id", if (stale) "$id (stale)" else id, pose[12], pose[13], pose[14], if (stale) STALE_COLOR else WHITE, config.number("nameSize") * scale, screenDp = true)
        }
        geometry.update(builder)
        geometry.setTransform(identity)
        val topics = TfFrames.topics(config.string("topics")).joinToString()
        val hiddenText = if (hidden > 0) " · $hidden hidden" else ""
        when {
            subError != null -> setStatus(StatusLevel.ERROR, subError!!)
            ids.isEmpty() -> setStatus(StatusLevel.WARN, "No TF received ($topics)")
            staleCount > 0 -> setStatus(StatusLevel.WARN, "$staleCount stale frame(s) · $visibleFrames of ${ids.size} connected$hiddenText")
            else -> setStatus(StatusLevel.OK, "$visibleFrames of ${ids.size} frames connected to '${ctx.fixedFrame}'$hiddenText")
        }
    }

    override fun destroy() {
        unsubscribe()
        super.destroy()
        geometry.destroy()
    }

    companion object {
        val ARROW_COLOR = Colors.rgb(255, 255, 0)
        val STALE_COLOR = Colors.rgb(128, 128, 128)
        val WHITE = Colors.rgb(255, 255, 255)
    }
}

// ====================================================================== Map

private class MapDisplay(host: DisplayHost, config: DisplayConfig) :
    TopicDisplay<OccupancyGrid, MapDisplay.MapData>(host, config, OccupancyGrid::class.java) {

    /**
     * `cells` already reduced to `texW`×`texH`; `rgba` is computed on the DDS thread with the scheme and
     * transparency at that moment (a large map is tens of MB: not done on the UI thread).
     */
    class MapData(
        val frame: String, val width: Int, val height: Int, val resolution: Float, val origin: DoubleArray,
        val downsample: Int, val texW: Int, val texH: Int, val cells: ByteArray,
        val scheme: String, val alpha: Float, val rgba: ByteArray
    )

    private var quad: TextureQuadEntity? = null
    private var last: MapData? = null
    private var downsample = 1

    override val reuseMessage = true
    override fun newMessage() = OccupancyGrid()
    override fun prepareForRead(msg: OccupancyGrid) { msg.data.clear() }

    override fun parse(msg: OccupancyGrid): MapData {
        val n = msg.data.size()
        val bytes = ByteArray(n)
        val buf = msg.data.buffer.duplicate(); buf.position(0); buf.limit(n); buf.get(bytes)
        val info = msg.info
        val width = info.width; val height = info.height
        // Mobile GPUs usually limit textures to 4096 px: it is reduced by taking 1 of every k cells.
        val k = max(1, (max(width, height) + MAX_TEXTURE - 1) / MAX_TEXTURE)
        val w = width / k
        val h = height / k
        val cells = if (k == 1) bytes else ByteArray(w * h).also { out ->
            for (y in 0 until h) for (x in 0 until w) out[y * w + x] = bytes.getOrElse((y * k) * width + x * k) { -1 }
        }
        val c = config
        val scheme = c.string("scheme"); val alpha = c.number("alpha").toFloat()
        val rgba = OccupancyGridImage.toRgba(cells, w, h, scheme, alpha)
        return MapData(msg.header.frameIdAsString, width, height, info.resolution, info.origin.matrix(), k, w, h, cells, scheme, alpha, rgba)
    }

    override fun consume(data: MapData, ctx: FrameContext) {
        last = data
        upload()
    }

    override fun onConfigChanged(old: DisplayConfig) {
        super.onConfigChanged(old)
        if (old.string("scheme") != config.string("scheme") || old.number("alpha") != config.number("alpha")) upload()
    }

    private fun upload() {
        val m = last ?: return
        downsample = m.downsample
        val scheme = config.string("scheme"); val alpha = config.number("alpha").toFloat()
        // Only if the user changed the scheme or transparency after receiving the map.
        val rgba = if (scheme == m.scheme && alpha == m.alpha) m.rgba else OccupancyGridImage.toRgba(m.cells, m.texW, m.texH, scheme, alpha)
        val q = quad ?: TextureQuadEntity(host.engine, host.scene, FilamentHub.matTexture).also { quad = it; it.setVisible(active) }
        q.update(rgba, m.texW, m.texH, m.texW * m.resolution * downsample, m.texH * m.resolution * downsample)
    }

    override fun draw(ctx: FrameContext): DisplayStatus? {
        val m = last ?: return null
        val q = quad ?: return null
        // "Draw behind": slightly below z=0 so the grid and everything else stay on top.
        val origin = if (config.bool("drawBehind")) TfMath.multiply(MeshBuilder.translation(0.0, 0.0, -0.02), m.origin, DoubleArray(16)) else m.origin
        val t = ctx.transformF(m.frame, origin)
        if (t == null) { q.setVisible(false); return DisplayStatus(StatusLevel.WARN, "No transform from '${m.frame}' to '${ctx.fixedFrame}'") }
        q.setVisible(true)
        q.setTransform(t)
        val note = if (downsample > 1) " (downsampled 1:$downsample)" else ""
        return DisplayStatus(StatusLevel.OK, "${m.width}×${m.height}, ${"%.3f".format(m.resolution)} m/cell$note")
    }

    override fun setVisible(v: Boolean) { quad?.setVisible(v) }

    override fun destroy() {
        super.destroy()
        quad?.destroy()
    }

    companion object { const val MAX_TEXTURE = 4096 }
}

// ====================================================================== point clouds (base)

/** Data already converted to points in the message frame. */
private class PointsData(val frame: String, val points: PointList, val info: String, val stampNs: Long = 0L)

private abstract class PointsDisplay<M : us.ihmc.jros2.ROS2Message<M>>(
    host: DisplayHost, config: DisplayConfig, cls: Class<M>
) : TopicDisplay<M, PointsData>(host, config, cls), PointPicker {
    private val geometry = GeometrySet(host.engine, host.scene)
    private val builder = MeshBuilder()
    private var frame = ""
    private var info = ""
    private var hasData = false

    /** Decay time: clouds already transformed to the fixed frame when they arrive (like RViz). */
    private class Baked(val timeMs: Long, val points: PointList)
    private val history = ArrayDeque<Baked>()
    private var bakedFixedFrame = ""
    private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    // The accumulated clouds were placed with the previous pass's TFs.
    override fun onTimeJump() {
        if (history.isEmpty()) return
        history.clear()
        geometry.update(MeshBuilder())
    }

    private val decayMs: Long get() = (config.number("decay") * 1000).toLong()
    /** fixed ← frame at the message's time (like RViz: data stays where it was measured). */
    private var msgTransform: FloatArray? = null
    private var msgStamp = 0L
    private var msgFixedFrame = ""

    override fun consume(data: PointsData, ctx: FrameContext) {
        frame = data.frame; info = data.info; hasData = true
        msgStamp = data.stampNs
        msgTransform = ctx.transformAtF(frame, msgStamp); msgFixedFrame = ctx.fixedFrame
        if (decayMs > 0) {
            if (ctx.fixedFrame != bakedFixedFrame) { history.clear(); bakedFixedFrame = ctx.fixedFrame }
            val t = msgTransform?.let { f -> DoubleArray(16) { f[it].toDouble() } } ?: ctx.transformD(frame)?.copyOf() ?: return
            history.addLast(Baked(ctx.nowMs, transformed(data.points, t)))
            rebuildFromHistory(ctx.nowMs)
        } else {
            history.clear()
            builder.clear()
            builder.circlePoints = config.string("style") == "circles"
            copy(data.points, builder.points)
            geometry.update(builder)
        }
    }

    private fun transformed(p: PointList, t: DoubleArray): PointList {
        val out = PointList()
        for (i in 0 until p.count) {
            val x = p.positions[i * 3].toDouble(); val y = p.positions[i * 3 + 1].toDouble(); val z = p.positions[i * 3 + 2].toDouble()
            out.add(
                (t[0] * x + t[4] * y + t[8] * z + t[12]).toFloat(),
                (t[1] * x + t[5] * y + t[9] * z + t[13]).toFloat(),
                (t[2] * x + t[6] * y + t[10] * z + t[14]).toFloat(),
                p.colors[i], p.sizes[i]
            )
        }
        return out
    }

    private fun copy(from: PointList, to: PointList) {
        for (i in 0 until from.count) to.add(from.positions[i * 3], from.positions[i * 3 + 1], from.positions[i * 3 + 2], from.colors[i], from.sizes[i])
    }

    private fun rebuildFromHistory(now: Long) {
        while (history.isNotEmpty() && now - history.first().timeMs > decayMs) history.removeFirst()
        builder.clear()
        builder.circlePoints = config.string("style") == "circles"
        for (h in history) copy(h.points, builder.points)
        geometry.update(builder)
    }

    override fun draw(ctx: FrameContext): DisplayStatus? {
        if (!hasData) return null
        if (decayMs > 0 && history.isNotEmpty()) {
            if (ctx.fixedFrame != bakedFixedFrame) { history.clear(); geometry.update(MeshBuilder()) }
            else if (ctx.nowMs - history.first().timeMs > decayMs) rebuildFromHistory(ctx.nowMs)
            geometry.setVisible(true)
            geometry.setTransform(identity)
            return DisplayStatus(StatusLevel.OK, "$info · ${history.size} accumulated")
        }
        if (msgFixedFrame != ctx.fixedFrame) { msgTransform = ctx.transformAtF(frame, msgStamp); msgFixedFrame = ctx.fixedFrame }
        // If the TF at the message's time was not there yet (it arrived before the TF), the current one is used.
        val t = msgTransform ?: ctx.transformF(frame) ?: run {
            geometry.setVisible(false)
            return DisplayStatus(StatusLevel.WARN, "No transform from '${TfTree.normalizeId(frame)}' to '${ctx.fixedFrame}'")
        }
        geometry.setVisible(true)
        geometry.setTransform(t)
        drawnTransform = t.copyOf()
        return DisplayStatus(StatusLevel.OK, info)
    }

    override fun setVisible(v: Boolean) = geometry.setVisible(v)

    /** Last transform used when drawing (fixed ← frame), for picking points. */
    private var drawnTransform: FloatArray? = null

    override fun pickPoint(sx: Float, sy: Float, maxPx: Float, project: (DoubleArray) -> FloatArray?): PickedPoint? {
        if (!hasData || !active) return null
        // With decay the points are already in the fixed frame; otherwise, in the sensor's.
        val baked = decayMs > 0 && history.isNotEmpty()
        val t = if (baked) identity else (msgTransform ?: drawnTransform ?: return null)
        val pts = builder.points
        var best: PickedPoint? = null
        val p = DoubleArray(3)
        for (i in 0 until pts.count) {
            val x = pts.positions[i * 3].toDouble(); val y = pts.positions[i * 3 + 1].toDouble(); val z = pts.positions[i * 3 + 2].toDouble()
            p[0] = t[0] * x + t[4] * y + t[8] * z + t[12]; p[1] = t[1] * x + t[5] * y + t[9] * z + t[13]; p[2] = t[2] * x + t[6] * y + t[10] * z + t[14]
            val s = project(p) ?: continue
            val d = Math.hypot((s[0] - sx).toDouble(), (s[1] - sy).toDouble()).toFloat()
            if (d <= maxPx && (best == null || d < best.distPx)) best = PickedPoint(p.copyOf(), doubleArrayOf(x, y, z), if (baked) "" else frame, config.name, d)
        }
        return best?.let { b -> if (baked) b else PickedPoint(b.fixed, b.local, b.frame, b.display, b.distPx, runCatching { pickFields(b.local) }.getOrDefault(emptyList())) }
    }

    /** The point values from the last message closest to `local` (in the sensor frame). */
    protected open fun pickFields(local: DoubleArray): List<Pair<String, String>> = emptyList()

    override fun destroy() {
        super.destroy()
        geometry.destroy()
    }
}

// ====================================================================== LaserScan

private class LaserScanDisplay(host: DisplayHost, config: DisplayConfig) : PointsDisplay<LaserScan>(host, config, LaserScan::class.java) {
    @Volatile private var last: LaserScanData? = null

    /** Index, angle, range and intensity of the ray passing through the point. */
    override fun pickFields(local: DoubleArray): List<Pair<String, String>> {
        val s = last ?: return emptyList()
        val angle = Math.atan2(local[1], local[0])
        val i = Math.round((angle - s.angleMin) / s.angleIncrement).toInt().coerceIn(0, s.ranges.size - 1)
        val f = java.util.Locale.ROOT
        return listOfNotNull("index" to "$i", "angle" to String.format(f, "%.3f rad", s.angleMin + i * s.angleIncrement),
            "range" to String.format(f, "%.3f m", s.ranges[i]), s.intensities.getOrNull(i)?.let { "intensity" to String.format(f, "%.3f", it) })
    }

    override val reuseMessage = true
    override fun newMessage() = LaserScan()
    override fun prepareForRead(msg: LaserScan) { msg.ranges.clear(); msg.intensities.clear() }

    override fun parse(msg: LaserScan): PointsData {
        val ranges = FloatArray(msg.ranges.size()) { msg.ranges.get(it) }
        val intensities = FloatArray(msg.intensities.size()) { msg.intensities.get(it) }
        val scan = LaserScanData(msg.angleMin, msg.angleIncrement, msg.rangeMin, msg.rangeMax, ranges, intensities)
        last = scan
        val c = config
        val out = PointList()
        LaserScanGeometry.build(scan, c.colorOptions(), c.number("size").toFloat(), out)
        return PointsData(msg.header.frameIdAsString, out, "${out.count} of ${ranges.size} points", msg.header.stampNs())
    }
}

// ====================================================================== PointCloud2

private class PointCloud2Display(host: DisplayHost, config: DisplayConfig) : PointsDisplay<PointCloud2>(host, config, PointCloud2::class.java) {
    private class Raw(val fields: List<PointFieldInfo>, val count: Int, val step: Int, val bigEndian: Boolean, val bytes: ByteArray)
    @Volatile private var last: Raw? = null

    /** All fields (x, y, z, intensity, rgb...) of the cloud point closest. */
    override fun pickFields(local: DoubleArray): List<Pair<String, String>> {
        val r = last ?: return emptyList()
        val bb = java.nio.ByteBuffer.wrap(r.bytes).order(if (r.bigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN)
        fun value(f: PointFieldInfo, base: Int): Double? {
            val o = base + f.offset
            val size = when (f.datatype) { 1, 2 -> 1; 3, 4 -> 2; 5, 6, 7 -> 4; 8 -> 8; else -> return null }
            if (o < 0 || o + size > r.bytes.size) return null
            return runCatching {
                when (f.datatype) {
                    1 -> bb.get(o).toDouble(); 2 -> (bb.get(o).toInt() and 0xFF).toDouble()
                    3 -> bb.getShort(o).toDouble(); 4 -> (bb.getShort(o).toInt() and 0xFFFF).toDouble()
                    5 -> bb.getInt(o).toDouble(); 6 -> (bb.getInt(o).toLong() and 0xFFFFFFFFL).toDouble()
                    7 -> bb.getFloat(o).toDouble(); 8 -> bb.getDouble(o); else -> null
                }
            }.getOrNull()
        }
        val fx = r.fields.firstOrNull { it.name == "x" } ?: return emptyList()
        val fy = r.fields.firstOrNull { it.name == "y" } ?: return emptyList()
        val fz = r.fields.firstOrNull { it.name == "z" }
        var best = -1; var bestD = Double.MAX_VALUE
        for (i in 0 until r.count) {
            val b = i * r.step
            val x = value(fx, b) ?: continue; val y = value(fy, b) ?: continue; val z = fz?.let { value(it, b) } ?: 0.0
            if (x.isNaN()) continue
            val d = (x - local[0]) * (x - local[0]) + (y - local[1]) * (y - local[1]) + (z - local[2]) * (z - local[2])
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0) return emptyList()
        val f = java.util.Locale.ROOT
        return listOf("index" to "$best") + r.fields.mapNotNull { fi ->
            val v = value(fi, best * r.step) ?: return@mapNotNull null
            fi.name to if (fi.name == "rgb" || fi.name == "rgba") {
                val bits = java.lang.Float.floatToRawIntBits(v.toFloat())
                "${(bits shr 16) and 255}; ${(bits shr 8) and 255}; ${bits and 255}"
            } else String.format(f, "%.4g", v)
        }
    }

    override val reuseMessage = true
    override fun newMessage() = PointCloud2()
    override fun prepareForRead(msg: PointCloud2) { msg.fields.clear(); msg.data.clear() }

    override fun parse(msg: PointCloud2): PointsData {
        val fields = (0 until msg.fields.size()).map {
            val f = msg.fields.get(it)
            PointFieldInfo(f.name.toString(), f.offset, f.datatype.toInt(), f.count)
        }
        val n = msg.data.size()
        val bytes = ByteArray(n)
        val buf = msg.data.buffer.duplicate(); buf.position(0); buf.limit(n); buf.get(bytes)
        last = Raw(fields, msg.width * msg.height, msg.pointStep, msg.isBigendian, bytes)
        val c = config
        val out = PointList()
        val r = PointCloud2Decoder.decode(
            fields, msg.width, msg.height, msg.pointStep, msg.rowStep, msg.isBigendian, bytes, n,
            c.colorOptions(), c.number("size").toFloat(), c.int("maxPoints"), out
        )
        r.error?.let { throw IllegalStateException(it) }
        val shown = if (r.drawn < r.total) "${r.drawn} of ${r.total}" else "${r.drawn}"
        return PointsData(msg.header.frameIdAsString, out, "$shown points · color ${r.colorUsed}", msg.header.stampNs())
    }
}

// ====================================================================== PointCloud (legacy)

private class PointCloudDisplay(host: DisplayHost, config: DisplayConfig) : PointsDisplay<PointCloud>(host, config, PointCloud::class.java) {
    override fun newMessage() = PointCloud()

    override fun parse(msg: PointCloud): PointsData {
        val n = msg.points.size()
        val xyz = FloatArray(n * 3)
        for (i in 0 until n) {
            val p = msg.points.get(i)
            xyz[i * 3] = p.x; xyz[i * 3 + 1] = p.y; xyz[i * 3 + 2] = p.z
        }
        val channels = HashMap<String, FloatArray>()
        for (i in 0 until msg.channels.size()) {
            val ch = msg.channels.get(i)
            channels[ch.name.toString()] = FloatArray(ch.values.size()) { ch.values.get(it) }
        }
        val out = PointList()
        PointCloudGeometry.build(xyz, channels, config.colorOptions(), config.number("size").toFloat(), out)
        return PointsData(msg.header.frameIdAsString, out, "${out.count} points", msg.header.stampNs())
    }
}

// ====================================================================== meshes rebuilt per message (base)

/** Display whose geometry is rebuilt on every message and hangs from the message frame. */
private abstract class MeshDisplay<M : us.ihmc.jros2.ROS2Message<M>, D : Any>(
    host: DisplayHost, config: DisplayConfig, cls: Class<M>
) : TopicDisplay<M, D>(host, config, cls) {
    protected val geometry = GeometrySet(host.engine, host.scene)
    protected var frame = ""
    private var lastData: D? = null
    protected var info = ""

    protected abstract fun frameOf(data: D): String
    protected abstract fun build(data: D, b: MeshBuilder)

    override fun consume(data: D, ctx: FrameContext) {
        lastData = data
        rebuild()
    }

    protected fun rebuild() {
        val d = lastData ?: return
        frame = frameOf(d)
        geometry.update(MeshBuilder().also { build(d, it) })
    }

    override fun onConfigChanged(old: DisplayConfig) {
        super.onConfigChanged(old)
        rebuild()
    }

    override fun draw(ctx: FrameContext): DisplayStatus? {
        if (lastData == null) return null
        val t = ctx.transformF(frame) ?: run {
            geometry.setVisible(false)
            return DisplayStatus(StatusLevel.WARN, "No transform from '${TfTree.normalizeId(frame)}' to '${ctx.fixedFrame}'")
        }
        geometry.setVisible(true)
        geometry.setTransform(t)
        return DisplayStatus(StatusLevel.OK, info)
    }

    override fun setVisible(v: Boolean) = geometry.setVisible(v)

    override fun destroy() {
        super.destroy()
        geometry.destroy()
    }

    /** Arrow or axes at `pose` with the configured dimensions. */
    protected fun poseShape(b: MeshBuilder, pose: DoubleArray) {
        b.withTransform(pose) { drawPoseShape(config, this) }
    }
}

// ====================================================================== Pose / PoseWithCovariance / PoseArray

private class PoseData(val frame: String, val poses: List<DoubleArray>, val covariance: DoubleArray? = null)

private class PoseDisplay(host: DisplayHost, config: DisplayConfig) : MeshDisplay<PoseStamped, PoseData>(host, config, PoseStamped::class.java) {
    override fun newMessage() = PoseStamped()
    override fun parse(msg: PoseStamped) = PoseData(msg.header.frameIdAsString, listOf(msg.pose.matrix()))
    override fun frameOf(data: PoseData) = data.frame
    override fun build(data: PoseData, b: MeshBuilder) {
        poseShape(b, data.poses[0])
        info = "x=%.2f y=%.2f".format(data.poses[0][12], data.poses[0][13])
    }
}

private class PoseCovarianceDisplay(host: DisplayHost, config: DisplayConfig) :
    MeshDisplay<PoseWithCovarianceStamped, PoseData>(host, config, PoseWithCovarianceStamped::class.java) {
    override fun newMessage() = PoseWithCovarianceStamped()
    override fun parse(msg: PoseWithCovarianceStamped) =
        PoseData(msg.header.frameIdAsString, listOf(msg.pose.pose.matrix()), msg.pose.covariance.copyOf())

    override fun frameOf(data: PoseData) = data.frame

    override fun build(data: PoseData, b: MeshBuilder) {
        val p = data.poses[0]
        poseShape(b, p)
        info = "x=%.2f y=%.2f".format(p[12], p[13])
        val cov = data.covariance ?: return
        if (!config.bool("covariance")) return
        val (a, bb, angle) = Covariance.ellipseXY(cov) ?: return
        val ellipse = TfMath.fromTranslationQuaternion(p[12], p[13], p[14], 0.0, 0.0, sin(angle / 2), cos(angle / 2))
        b.withTransform(ellipse) {
            cylinder(a, bb, 0.01, Colors.withAlpha(config.color("covColor"), config.number("covAlpha").toFloat()), 24)
        }
        info += " · σ=%.2f×%.2f m".format(a, bb)
    }
}

private class PoseArrayDisplay(host: DisplayHost, config: DisplayConfig) : MeshDisplay<PoseArray, PoseData>(host, config, PoseArray::class.java) {
    override fun newMessage() = PoseArray()
    override fun parse(msg: PoseArray) =
        PoseData(msg.header.frameIdAsString, (0 until msg.poses.size()).map { msg.poses.get(it).matrix() })

    override fun frameOf(data: PoseData) = data.frame
    override fun build(data: PoseData, b: MeshBuilder) {
        for (p in data.poses) poseShape(b, p)
        info = "${data.poses.size} poses"
    }
}

// ====================================================================== Odometry

private class OdometryDisplay(host: DisplayHost, config: DisplayConfig) :
    TopicDisplay<Odometry, Pair<String, OdometryTrail.Pose>>(host, config, Odometry::class.java) {

    private val geometry = GeometrySet(host.engine, host.scene)
    private val trail = OdometryTrail(config.int("keep"), config.number("positionTolerance"), config.number("angleTolerance"))
    private var dirty = false

    override fun newMessage() = Odometry()

    override fun parse(msg: Odometry): Pair<String, OdometryTrail.Pose> {
        val p = msg.pose.pose
        return msg.header.frameIdAsString to OdometryTrail.Pose(
            p.position.x, p.position.y, p.position.z, p.orientation.x, p.orientation.y, p.orientation.z, p.orientation.w
        )
    }

    override fun consume(data: Pair<String, OdometryTrail.Pose>, ctx: FrameContext) {
        if (trail.add(data.first, data.second)) dirty = true
    }

    override fun onConfigChanged(old: DisplayConfig) {
        super.onConfigChanged(old)
        trail.keep = config.int("keep")
        trail.positionTolerance = config.number("positionTolerance")
        trail.angleTolerance = config.number("angleTolerance")
        if (old.topic != config.topic) trail.clear()
        dirty = true
    }

    // The previous pass's trail would mix with the new one.
    override fun onTimeJump() { trail.clear(); dirty = true }

    override fun draw(ctx: FrameContext): DisplayStatus? {
        if (dirty) {
            val b = MeshBuilder()
            for (p in trail.poses) {
                b.withTransform(TfMath.fromTranslationQuaternion(p.x, p.y, p.z, p.qx, p.qy, p.qz, p.qw)) { drawPoseShape(config, this) }
            }
            geometry.update(b)
            dirty = false
        }
        if (trail.poses.isEmpty()) return null
        val t = ctx.transformF(trail.frameId) ?: run {
            geometry.setVisible(false)
            return DisplayStatus(StatusLevel.WARN, "No transform from '${trail.frameId}' to '${ctx.fixedFrame}'")
        }
        geometry.setVisible(true)
        geometry.setTransform(t)
        val last = trail.poses.last()
        return DisplayStatus(StatusLevel.OK, "${trail.poses.size} poses · x=%.2f y=%.2f".format(last.x, last.y))
    }

    override fun setVisible(v: Boolean) = geometry.setVisible(v)

    override fun destroy() {
        super.destroy()
        geometry.destroy()
    }
}

// ====================================================================== Path

private class PathDisplay(host: DisplayHost, config: DisplayConfig) : MeshDisplay<Path, PoseData>(host, config, Path::class.java) {
    override fun newMessage() = Path()
    override fun parse(msg: Path) = PoseData(msg.header.frameIdAsString, (0 until msg.poses.size()).map { msg.poses.get(it).pose.matrix() })
    override fun frameOf(data: PoseData) = data.frame

    override fun build(data: PoseData, b: MeshBuilder) {
        val color = config.alphaColor()
        val width = config.lineWidth()
        val ps = data.poses
        val dz = config.number("offsetZ")
        for (i in 0 until ps.size - 1) {
            b.thickLine(ps[i][12], ps[i][13], ps[i][14] + dz, ps[i + 1][12], ps[i + 1][13], ps[i + 1][14] + dz, color, width)
        }
        val len = config.number("poseLength")
        when (config.string("poseStyle")) {
            "axes" -> for (p in ps) b.withTransform(p) { axes(len, len * 0.05, config.alpha255()) }
            "arrows" -> for (p in ps) b.withTransform(p) { arrow(len, color) }
        }
        info = "${ps.size} poses"
    }
}

// ====================================================================== PointStamped

private class PointStampedDisplay(host: DisplayHost, config: DisplayConfig) :
    TopicDisplay<PointStamped, Pair<String, DoubleArray>>(host, config, PointStamped::class.java) {

    private val geometry = GeometrySet(host.engine, host.scene)
    private val history = ArrayDeque<DoubleArray>()
    private var frame = ""
    private var dirty = false

    override fun newMessage() = PointStamped()
    override fun parse(msg: PointStamped) = msg.header.frameIdAsString to doubleArrayOf(msg.point.x, msg.point.y, msg.point.z)

    override fun consume(data: Pair<String, DoubleArray>, ctx: FrameContext) {
        if (data.first != frame) { history.clear(); frame = data.first }
        history.addLast(data.second)
        while (history.size > config.int("keep")) history.removeFirst()
        dirty = true
    }

    override fun onConfigChanged(old: DisplayConfig) { super.onConfigChanged(old); dirty = true }

    override fun onTimeJump() { history.clear(); dirty = true }

    override fun draw(ctx: FrameContext): DisplayStatus? {
        if (dirty) {
            val b = MeshBuilder()
            val r = config.number("radius")
            for (p in history) b.withTransform(MeshBuilder.translation(p[0], p[1], p[2])) { sphere(r, r, r, config.alphaColor()) }
            geometry.update(b)
            dirty = false
        }
        if (history.isEmpty()) return null
        val t = ctx.transformF(frame) ?: run {
            geometry.setVisible(false)
            return DisplayStatus(StatusLevel.WARN, "No transform from '$frame' to '${ctx.fixedFrame}'")
        }
        geometry.setVisible(true); geometry.setTransform(t)
        val p = history.last()
        return DisplayStatus(StatusLevel.OK, "(%.2f, %.2f, %.2f)".format(p[0], p[1], p[2]))
    }

    override fun setVisible(v: Boolean) = geometry.setVisible(v)
    override fun destroy() { super.destroy(); geometry.destroy() }
}

// ====================================================================== Polygon

private class PolygonData(val frame: String, val xyz: FloatArray)

private class PolygonDisplay(host: DisplayHost, config: DisplayConfig) : MeshDisplay<PolygonStamped, PolygonData>(host, config, PolygonStamped::class.java) {
    override fun newMessage() = PolygonStamped()
    override fun parse(msg: PolygonStamped): PolygonData {
        val pts = msg.polygon.points
        val xyz = FloatArray(pts.size() * 3)
        for (i in 0 until pts.size()) { val p = pts.get(i); xyz[i * 3] = p.x; xyz[i * 3 + 1] = p.y; xyz[i * 3 + 2] = p.z }
        return PolygonData(msg.header.frameIdAsString, xyz)
    }

    override fun frameOf(data: PolygonData) = data.frame

    override fun build(data: PolygonData, b: MeshBuilder) {
        val n = data.xyz.size / 3
        val c = config.alphaColor()
        val width = config.lineWidth()
        for (i in 0 until n) {
            val j = (i + 1) % n
            if (n < 2 || (n == 2 && i == 1)) break
            b.thickLine(
                data.xyz[i * 3].toDouble(), data.xyz[i * 3 + 1].toDouble(), data.xyz[i * 3 + 2].toDouble(),
                data.xyz[j * 3].toDouble(), data.xyz[j * 3 + 1].toDouble(), data.xyz[j * 3 + 2].toDouble(), c, width
            )
        }
        info = "$n vertices"
    }
}

// ====================================================================== Range

private class RangeData(val frame: String, val fov: Float, val range: Float, val min: Float, val max: Float)

private class RangeDisplay(host: DisplayHost, config: DisplayConfig) : MeshDisplay<Range, RangeData>(host, config, Range::class.java) {
    override fun newMessage() = Range()
    override fun parse(msg: Range) = RangeData(msg.header.frameIdAsString, msg.fieldOfView, msg.range, msg.minRange, msg.maxRange)
    override fun frameOf(data: RangeData) = data.frame

    override fun build(data: RangeData, b: MeshBuilder) {
        val r = data.range
        if (!r.isFinite() || r <= 0f) { info = "Out of range"; return }
        val length = r.toDouble()
        val radius = length * tan(data.fov / 2.0).coerceIn(0.0, 10.0)
        val color = Colors.withAlpha(config.color("color"), config.number("alpha").toFloat())
        // Cone with its apex at the sensor and its base at `range`, pointing to +X.
        b.withTransform(MeshBuilder.ROT_Z_TO_X) {
            withTransform(MeshBuilder.translation(0.0, 0.0, length)) {
                withTransform(MeshBuilder.scale(1.0, 1.0, -1.0)) { cone(radius, length, color, 20) }
            }
        }
        info = "%.2f m".format(r)
    }
}

// ====================================================================== Markers

private fun parseMarker(m: Marker): MarkerData {
    val n = m.points.size()
    val pts = FloatArray(n * 3)
    for (i in 0 until n) { val p = m.points.get(i); pts[i * 3] = p.x.toFloat(); pts[i * 3 + 1] = p.y.toFloat(); pts[i * 3 + 2] = p.z.toFloat() }
    val colors = IntArray(m.colors.size()) { colorOf(m.colors.get(it)) }
    val lifetime = m.lifetime.sec * 1000L + m.lifetime.nanosec / 1_000_000L
    return MarkerData(
        m.ns.toString(), m.id, m.type, m.action, m.header.frameIdAsString, m.pose.matrix(),
        m.scale.x, m.scale.y, m.scale.z, colorOf(m.color), pts, colors, com.juancams.rviz2mini.ros.RosStrings.fix(m.text), lifetime,
        m.meshResourceAsString, m.meshUseEmbeddedMaterials
    )
}

private abstract class MarkerDisplayBase<M : us.ihmc.jros2.ROS2Message<M>>(
    host: DisplayHost, config: DisplayConfig, cls: Class<M>
) : TopicDisplay<M, List<MarkerData>>(host, config, cls) {

    override val keepAllMessages = true
    private val store = MarkerStore()
    private val geometries = HashMap<String, GeometrySet>()
    private var labels: Map<String, List<TextLabel>> = emptyMap()
    private var dirty = false

    override fun consume(data: List<MarkerData>, ctx: FrameContext) {
        for (m in data) if (store.apply(m, ctx.nowMs)) dirty = true
    }

    override fun onConfigChanged(old: DisplayConfig) {
        super.onConfigChanged(old)
        if (old.string("meshUrl") != config.string("meshUrl")) MeshCache.forgetFailures()
        dirty = true
    }

    /** Why some mesh is not visible (for the status). */
    private var meshProblem: String? = null

    /** The mesh for a URI if already downloaded; otherwise it requests it (and rebuilds when it arrives). */
    private fun meshFor(uri: String): com.juancams.rviz2mini.rviz.geom.MeshData? {
        if (!com.juancams.rviz2mini.rviz.geom.MeshUris.isSupported(uri)) { meshProblem = "unsupported mesh format (only STL and DAE): $uri"; return null }
        val url = com.juancams.rviz2mini.rviz.geom.MeshUris.resolve(uri, config.string("meshUrl"))
            ?: run { meshProblem = "set the mesh server to load $uri"; return null }
        return when (val e = MeshCache.get(url)) {
            is MeshCache.Entry.Ok -> e.mesh
            is MeshCache.Entry.Failed -> { meshProblem = "mesh $uri: ${e.reason}"; null }
            else -> { if (meshProblem == null) meshProblem = "loading meshes…"; MeshCache.request(url) { dirty = true }; null }
        }
    }

    override fun draw(ctx: FrameContext): DisplayStatus? {
        if (store.expire(ctx.nowMs)) dirty = true
        if (dirty) {
            meshProblem = null
            val built = store.build(config.bool("lineWidths"), ::meshFor)
            for (frame in geometries.keys - built.keys) geometries.remove(frame)?.destroy()
            for ((frame, pair) in built) {
                geometries.getOrPut(frame) { GeometrySet(host.engine, host.scene).also { it.setVisible(active) } }.update(pair.first)
            }
            labels = built.mapValues { it.value.second }
            dirty = false
        }
        var missing: String? = null
        for ((frame, g) in geometries) {
            val t = ctx.transformD(frame)
            if (t == null) { g.setVisible(false); missing = frame; continue }
            g.setVisible(true)
            g.setTransform(TfMath.toFloat(t, FloatArray(16)))
            labels[frame]?.forEachIndexed { i, l ->
                val x = t[0] * l.x + t[4] * l.y + t[8] * l.z + t[12]
                val y = t[1] * l.x + t[5] * l.y + t[9] * l.z + t[13]
                val z = t[2] * l.x + t[6] * l.y + t[10] * l.z + t[14]
                ctx.labels.label("mk:${config.id}:$frame:$i", l.text, x, y, z, l.color, l.heightM)
            }
        }
        if (store.size == 0) return DisplayStatus(StatusLevel.OK, "No markers")
        if (missing != null) return DisplayStatus(StatusLevel.WARN, "No transform from '$missing' to '${ctx.fixedFrame}'")
        if (store.unsupported > 0) return DisplayStatus(StatusLevel.WARN, "${store.size} markers · ${store.unsupported} of unsupported type")
        if (store.meshesMissing > 0) return DisplayStatus(StatusLevel.WARN, "${store.size} markers · ${store.meshesMissing} without mesh: $meshProblem")
        return DisplayStatus(StatusLevel.OK, "${store.size} markers")
    }

    override fun onDeactivate() {
        super.onDeactivate()
        store.clear(); dirty = true
    }

    override fun setVisible(v: Boolean) { for (g in geometries.values) g.setVisible(v) }

    override fun destroy() {
        super.destroy()
        for (g in geometries.values) g.destroy()
        geometries.clear()
    }
}

private class MarkerDisplay(host: DisplayHost, config: DisplayConfig) : MarkerDisplayBase<Marker>(host, config, Marker::class.java) {
    override fun newMessage() = Marker()
    override fun parse(msg: Marker) = listOf(parseMarker(msg))
}

private class MarkerArrayDisplay(host: DisplayHost, config: DisplayConfig) : MarkerDisplayBase<MarkerArray>(host, config, MarkerArray::class.java) {
    override fun newMessage() = MarkerArray()
    override fun parse(msg: MarkerArray) = (0 until msg.markers.size()).map { parseMarker(msg.markers.get(it)) }
}

// ====================================================================== RobotModel

/** Cache of downloaded meshes (shared by all tabs). */
internal object MeshCache {
    sealed class Entry {
        object Loading : Entry()
        class Ok(val mesh: com.juancams.rviz2mini.rviz.geom.MeshData) : Entry()
        class Failed(val reason: String) : Entry()
    }

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r, "mesh-loader").apply { isDaemon = true } }

    /** Who is waiting for each download in progress (guarded by itself). */
    private val waiters = HashMap<String, MutableList<() -> Unit>>()

    fun get(url: String): Entry? = entries[url]

    /**
     * Downloads and parses `url` if not already done; `onDone` on the main thread when ready (also if it was already
     * being downloaded for another display, or if it just finished).
     */
    fun request(url: String, onDone: () -> Unit) {
        synchronized(waiters) {
            when (entries[url]) {
                null -> { entries[url] = Entry.Loading; waiters[url] = mutableListOf(onDone) }
                Entry.Loading -> { waiters.getOrPut(url) { mutableListOf() }.add(onDone); return }
                else -> { com.juancams.rviz2mini.platform.Platform.main.post(onDone); return }
            }
        }
        executor.execute {
            val result = if (url.startsWith("file://")) try {
                Entry.Ok(com.juancams.rviz2mini.rviz.geom.MeshUris.parse(url, java.io.File(url.removePrefix("file://")).readBytes()))
            } catch (e: Exception) { Entry.Failed(e.message ?: e.javaClass.simpleName) } else try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000; conn.readTimeout = 15000
                try {
                    if (conn.responseCode != 200) Entry.Failed("HTTP ${conn.responseCode}")
                    else Entry.Ok(com.juancams.rviz2mini.rviz.geom.MeshUris.parse(url, conn.inputStream.use { it.readBytes() }))
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                Entry.Failed(e.message ?: e.javaClass.simpleName)
            }
            val done = synchronized(waiters) { entries[url] = result; waiters.remove(url).orEmpty() }
            com.juancams.rviz2mini.platform.Platform.main.post { done.forEach { it() } }
        }
    }

    /** Allows retrying the ones that failed (e.g. after changing the server). */
    fun forgetFailures() = entries.entries.removeAll { it.value is Entry.Failed }
}

private class RobotModelDisplay(host: DisplayHost, config: DisplayConfig) :
    TopicDisplay<std_msgs.String_, com.juancams.rviz2mini.rviz.geom.UrdfModel>(host, config, std_msgs.String_::class.java) {

    private var model: com.juancams.rviz2mini.rviz.geom.UrdfModel? = null
    private val links = HashMap<String, GeometrySet>()
    private var dirty = false
    private var meshInfo = ""

    override fun newMessage() = std_msgs.String_()
    override fun parse(msg: std_msgs.String_) = com.juancams.rviz2mini.rviz.geom.UrdfParser.parse(com.juancams.rviz2mini.ros.RosStrings.fix(msg.data))

    override fun consume(data: com.juancams.rviz2mini.rviz.geom.UrdfModel, ctx: FrameContext) {
        model = data
        dirty = true
    }

    override fun onConfigChanged(old: DisplayConfig) {
        super.onConfigChanged(old)
        if (old.string("meshUrl") != config.string("meshUrl")) MeshCache.forgetFailures()
        dirty = true
    }

    private fun rebuild() {
        val m = model ?: return
        val (builders, info) = buildUrdfLinkMeshes(m, config.string("meshUrl"), config.bool("visual"), config.bool("collision"),
            config.number("alpha").toFloat(), null) { dirty = true }
        for (link in links.keys - builders.keys) links.remove(link)?.destroy()
        for ((link, b) in builders) links.getOrPut(link) { GeometrySet(host.engine, host.scene).also { it.setVisible(active) } }.update(b)
        meshInfo = info
    }

    override fun draw(ctx: FrameContext): DisplayStatus? {
        val m = model ?: return null
        if (dirty) { rebuild(); dirty = false }
        var missing = 0
        for ((link, g) in links) {
            val t = ctx.transformF(link)
            if (t == null) { g.setVisible(false); missing++; continue }
            g.setVisible(true); g.setTransform(t)
        }
        val text = "${m.name.ifEmpty { "robot" }}: ${m.links.size} links" +
            (if (missing > 0) " · $missing without TF" else "") + (if (meshInfo.isNotEmpty()) " · $meshInfo" else "")
        val warn = missing > 0 || meshInfo.contains("without mesh server") || meshInfo.contains("unsupported format") || meshInfo.contains("error")
        return DisplayStatus(if (warn) StatusLevel.WARN else StatusLevel.OK, text)
    }

    override fun setVisible(v: Boolean) { for (g in links.values) g.setVisible(v) }

    override fun destroy() {
        super.destroy()
        for (g in links.values) g.destroy()
        links.clear()
    }

    companion object { val DEFAULT_COLOR = Colors.rgbf(0.8f, 0.8f, 0.8f) }
}

/**
 * The meshes of each link of a URDF (visual and/or collision), in the link frame: used by RobotModel and the
 * Trajectory ghost robot. `tint`: one color for everything (the ghost). Also returns the mesh summary.
 */
internal fun buildUrdfLinkMeshes(m: com.juancams.rviz2mini.rviz.geom.UrdfModel, base: String, showVisual: Boolean, showCollision: Boolean,
                                 alpha: Float, tint: Int?, onMeshLoaded: () -> Unit): Pair<HashMap<String, MeshBuilder>, String> {
        val builders = HashMap<String, MeshBuilder>()
        var meshes = 0; var loaded = 0; var pending = 0; var unsupported = 0; var noServer = 0; var failed = 0
        for (g in m.geometries) {
            if (g.collision && !showCollision || !g.collision && !showVisual) continue
            val b = builders.getOrPut(g.link) { MeshBuilder() }
            val color = tint ?: Colors.withAlpha(g.color ?: RobotModelDisplay.DEFAULT_COLOR, alpha * Colors.alpha(g.color ?: RobotModelDisplay.DEFAULT_COLOR) / 255f)
            b.withTransform(g.origin) {
                when (val s = g.shape) {
                    is com.juancams.rviz2mini.rviz.geom.UrdfShape.Box -> box(s.x, s.y, s.z, color)
                    is com.juancams.rviz2mini.rviz.geom.UrdfShape.Cylinder -> cylinder(s.radius, s.radius, s.length, color, 20)
                    is com.juancams.rviz2mini.rviz.geom.UrdfShape.Sphere -> sphere(s.radius, s.radius, s.radius, color)
                    is com.juancams.rviz2mini.rviz.geom.UrdfShape.Mesh -> {
                        meshes++
                        val url = com.juancams.rviz2mini.rviz.geom.MeshUris.resolve(s.uri, base)
                        when {
                            !com.juancams.rviz2mini.rviz.geom.MeshUris.isSupported(s.uri) -> unsupported++
                            url == null -> noServer++
                            else -> when (val e = MeshCache.get(url)) {
                                is MeshCache.Entry.Ok -> {
                                    loaded++
                                    withTransform(MeshBuilder.scale(s.sx, s.sy, s.sz)) {
                                        val t = e.mesh.triangles
                                        for (i in 0 until e.mesh.triangleCount) {
                                            val o = i * 9
                                            // The mesh's own color (Collada) if it has one and the URDF sets no material.
                                            val own = e.mesh.colors[i]
                                            val c = if (tint == null && own != 0 && g.color == null) Colors.withAlpha(own, alpha * Colors.alpha(own) / 255f) else color
                                            triangle(
                                                t[o].toDouble(), t[o + 1].toDouble(), t[o + 2].toDouble(),
                                                t[o + 3].toDouble(), t[o + 4].toDouble(), t[o + 5].toDouble(),
                                                t[o + 6].toDouble(), t[o + 7].toDouble(), t[o + 8].toDouble(), c
                                            )
                                        }
                                    }
                                }
                                is MeshCache.Entry.Failed -> failed++
                                else -> { pending++; MeshCache.request(url) { onMeshLoaded() } }
                            }
                        }
                    }
                }
            }
        }
        val parts = ArrayList<String>()
        if (meshes > 0) parts += "meshes $loaded/$meshes"
        if (pending > 0) parts += "$pending downloading"
        if (noServer > 0) parts += "$noServer without mesh server"
        if (unsupported > 0) parts += "$unsupported in unsupported format (only STL and DAE)"
        if (failed > 0) parts += "$failed with download error"
    return builders to parts.joinToString(" · ")
}
