// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.display

import com.juancams.rviz2mini.rviz.geom.OverlayLayout

import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.camera.CameraFrameInfo
import com.juancams.rviz2mini.camera.ImageKind
import com.juancams.rviz2mini.camera.ImageTopic
import com.juancams.rviz2mini.camera.ImageTopics
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.rviz.FilamentHub
import com.juancams.rviz2mini.rviz.geom.CameraMath
import com.juancams.rviz2mini.rviz.geom.MeshBuilder
import com.juancams.rviz2mini.rviz.geom.UrdfModel
import com.juancams.rviz2mini.rviz.geom.UrdfParser
import com.juancams.rviz2mini.rviz.render.ImagePlaneEntity
import com.juancams.rviz2mini.rviz.render.LAYER_CAMERA_BACKGROUND
import com.juancams.rviz2mini.rviz.render.LAYER_MAIN
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** `<image namespace>/camera_info`: /camera/image_raw(/compressed) -> /camera/camera_info. */
internal fun cameraInfoTopicFor(imageTopic: String, override: String = ""): String {
    if (override.isNotBlank()) return DynRos.normalizeName(override)
    var t = DynRos.normalizeName(imageTopic)
    if (t.endsWith("/compressed") || t.endsWith("/compressedDepth")) t = t.substringBeforeLast('/')
    val ns = t.substringBeforeLast('/', "")
    return "$ns/camera_info"
}

/** Subscription to a sensor_msgs/CameraInfo: the intrinsics and optical frame of the latest message. */
private class CameraInfoSource(host: DisplayHost, topic: String) {
    class Info(val intrinsics: CameraMath.Intrinsics, val frame: String)

    val latest = AtomicReference<Info?>()
    @Volatile var received = 0L
    private val sub: DynRos.Subscription? = run {
        DynRos.subscribe(topic, Interfaces.get().msg("sensor_msgs/msg/CameraInfo"), TopicDisplay.qosProfile(QosPreset.SENSOR)) { v ->
            received++
            val i = CameraMath.intrinsics(v) ?: return@subscribe
            val frame = ((v["header"] as? Map<*, *>)?.get("frame_id") as? String).orEmpty()
            latest.set(Info(i, frame))
        }
    }

    fun close() = sub?.close()
}

// ====================================================================== Camera

/**
 * RViz "Camera" display: a camera's image with the 3D scene on top, seen from the camera
 * itself (pose from the TF of the camera_info frame and projection from its K/P). It is a second Filament
 * view over the same scene, in a window in a corner; the image goes in a layer that only
 * that view sees, drawn first and without depth.
 */
internal class CameraDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {

    private var stream: RvizImageStream? = null
    private var info: CameraInfoSource? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var plane: ImagePlaneEntity? = null
    /** Plain background behind the scene when the image is not the background (overlay). */
    private var backdrop: ImagePlaneEntity? = null
    private var shown = false
    private var visible = true
    /** New image waiting to be uploaded (RGBA), with its size. */
    private var pending: Triple<ByteBuffer, Int, Int>? = null
    private var hasImage = false
    private var lastInfo: CameraFrameInfo? = null
    private var error: String? = null
    private val model = DoubleArray(16)

    private val infoTopic get() = cameraInfoTopicFor(config.topic, config.string("cameraInfo"))

    /** Where the panel is drawn (left, top, width, height in pixels, top-left origin), or null while hidden. */
    @Volatile var panelRect: IntArray? = null; private set

    override fun onActivate() {
        val topic = ImageTopics.normalize(config.topic)
        if (!ImageTopics.isValidName(topic)) { setStatus(StatusLevel.ERROR, "Invalid topic: '${config.topic}'"); return }
        error = null; hasImage = false; lastInfo = null; pending = null
        val engine = host.engine
        view = engine.createView().also { v ->
            v.scene = host.scene
            host.colorGrading?.let { v.colorGrading = it }
            // Sees the normal scene and/or the image, depending on the mode.
            v.setVisibleLayers(0xFF, when (mode) {
                MODE_IMAGE -> LAYER_CAMERA_BACKGROUND
                else -> LAYER_MAIN or LAYER_CAMERA_BACKGROUND
            })
            camera = engine.createCamera(EntityManager.get().create()).also { v.camera = it }
        }
        // Overlay: the image last and transparent, on top of everything; otherwise, first and as background.
        plane = if (mode == MODE_OVERLAY) ImagePlaneEntity(engine, host.scene, FilamentHub.matOverlay, LAYER_CAMERA_BACKGROUND, priority = 7)
            .also { it.setAlpha(config.number("overlayAlpha").toFloat()) }
        else ImagePlaneEntity(engine, host.scene, FilamentHub.matBackground, LAYER_CAMERA_BACKGROUND)
        backdrop = if (mode == MODE_OVERLAY) ImagePlaneEntity(engine, host.scene, FilamentHub.matBackground, LAYER_CAMERA_BACKGROUND) else null
        info = CameraInfoSource(host, infoTopic)
        stream = RvizImageStreams.open(host, ImageTopic(topic, kindOf(topic)), config.qosProfile(), annotationsTopic(config), ::onFrame) { error = it }
    }

    override fun onDeactivate() {
        stream?.stop(); stream = null
        info?.close(); info = null
        val engine = host.engine
        view?.let { host.removeOverlayView(it); engine.destroyView(it) }
        camera?.let { c -> val e = c.entity; engine.destroyCameraComponent(e); EntityManager.get().destroy(e) }
        plane?.destroy(); backdrop?.destroy()
        view = null; camera = null; plane = null; backdrop = null; shown = false
    }

    override fun onConfigChanged(old: DisplayConfig) {
        if (active && (old.topic != config.topic || old.string("cameraInfo") != config.string("cameraInfo") || old.qosKey() != config.qosKey() ||
                old.string("annotations") != config.string("annotations") || old.bool("showAnnotations") != config.bool("showAnnotations") ||
                old.string("mode") != config.string("mode"))) {
            onDeactivate(); onActivate()
        } else if (active && mode == MODE_OVERLAY) plane?.setAlpha(config.number("overlayAlpha").toFloat())
    }

    private val mode get() = config.string("mode")

    private fun onFrame(argb: IntArray, w: Int, h: Int, info: CameraFrameInfo) {
        if (!active) return
        pending = Triple(RvizImageStreams.toRgba(argb, w, h), w, h)
        lastInfo = info
        error = null
    }

    override fun render(ctx: FrameContext) {
        val v = view ?: return
        val i = info?.latest?.get()
        val ready = i != null && host.surfaceWidth > 0 && visible
        val pose = i?.let { ctx.transformD(it.frame.ifEmpty { ctx.fixedFrame }) }
        when {
            error != null -> setStatus(StatusLevel.ERROR, error!!)
            i == null -> waiting("Waiting for camera_info on $infoTopic", infoTopic)
            pose == null -> setStatus(StatusLevel.WARN, "No transform from '${i.frame}' to '${ctx.fixedFrame}'")
            lastInfo == null -> waiting("Waiting for images on ${config.topic}")
            RvizImageStreams.annotationsError(stream?.annotations) != null -> setStatus(StatusLevel.WARN, RvizImageStreams.annotationsError(stream?.annotations)!!)
            else -> lastInfo!!.let { f -> setStatus(StatusLevel.OK, String.format(Locale.ROOT, "%d×%d %s · %.1f fps", f.width, f.height, f.encoding, f.fps) +
                RvizImageStreams.annotationsSuffix(stream?.annotations)) }
        }
        if (!ready || pose == null) { show(false); return }
        val intr = i!!.intrinsics
        // In its corner, or wherever it was dragged (top-left origin); Filament wants the origin at the bottom left.
        val r = OverlayLayout.rect(config.string("corner"), config.number("x"), config.number("y"), config.number("size"),
            host.surfaceWidth, host.surfaceHeight, intr.width, intr.height, (6 * host.pxPerDp).toInt())
        panelRect = r
        v.viewport = Viewport(r[0], host.surfaceHeight - r[1] - r[3], r[2], r[3])
        camera!!.setCustomProjection(CameraMath.projection(intr, NEAR, FAR), NEAR, FAR)
        TfMath.multiply(pose, CameraMath.FILAMENT_TO_OPTICAL, model)
        camera!!.setModelMatrix(model)
        val p = plane!!
        val corners = CameraMath.imageCorners(intr, PLANE_DEPTH)
        backdrop?.let { b -> b.update(plain(), 1, 1, corners); b.setTransform(TfMath.toFloat(pose, FloatArray(16))) }
        if (mode == MODE_SCENE) {
            // Scene only: a plain background instead of the image.
            if (!hasImage) p.update(plain(), 1, 1, corners)
            hasImage = true
        } else pending?.let { (buf, w, h) -> p.update(buf, w, h, CameraMath.imageCorners(intr, PLANE_DEPTH)); hasImage = true }
        pending = null
        p.setCorners(CameraMath.imageCorners(intr, PLANE_DEPTH))
        p.setTransform(TfMath.toFloat(pose, FloatArray(16)))
        p.setVisible(hasImage)
        show(true)
    }

    /** A dark gray pixel (the background when the image is not used). */
    private fun plain(): ByteBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply { put(byteArrayOf(0x20, 0x20, 0x24, 0xFF.toByte())); flip() }

    private fun show(on: Boolean) {
        if (!on) panelRect = null
        val v = view ?: return
        if (on == shown) return
        shown = on
        if (on) host.addOverlayView(v) else host.removeOverlayView(v)
    }

    override fun setVisible(v: Boolean) {
        visible = v
        if (!v) { show(false); plane?.setVisible(false); backdrop?.setVisible(false) }
    }

    internal fun viewportForTest(): IntArray? = view?.takeIf { shown }?.viewport?.let { intArrayOf(it.left, it.bottom, it.width, it.height) }

    companion object {
        const val MODE_BOTH = "Image and scene"
        const val MODE_OVERLAY = "Image over the scene"
        const val MODE_IMAGE = "Image only"
        const val MODE_SCENE = "Scene only"
        private const val NEAR = 0.05
        private const val FAR = 500.0
        /** How far away the image plane goes (it does not matter: no depth is used). */
        private const val PLANE_DEPTH = 1.0
    }
}

// ====================================================================== DepthCloud

/**
 * RViz "DepthCloud" display: a depth image (16UC1 in mm or 32FC1 in m) with its
 * camera_info converted into a point cloud in the optical frame, colored by distance or with the
 * colors of another image (the color one from the same camera).
 */
internal class DepthCloudDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {

    private class Cloud(val frame: String, val xyz: FloatArray, val pixels: IntArray, val width: Int, val height: Int)

    private val geometry = GeometrySet(host.engine, host.scene)
    private var depthSub: DynRos.Subscription? = null
    private var info: CameraInfoSource? = null
    private var colorStream: RvizImageStream? = null
    private val latest = AtomicReference<Cloud?>()
    private var cloud: Cloud? = null
    @Volatile private var received = 0L
    @Volatile private var error: String? = null
    private var colors: IntArray? = null
    private var colorW = 0
    private var colorH = 0
    private var dirty = false

    private val infoTopic get() = cameraInfoTopicFor(config.topic, config.string("cameraInfo"))

    override fun onActivate() {
        val topic = DynRos.normalizeName(config.topic)
        info = CameraInfoSource(host, infoTopic)
        error = null; received = 0
        // ".../compressedDepth": CompressedImage with 16-bit PNG (compressed_depth_image_transport).
        val compressed = topic.endsWith("/compressedDepth")
        val type = if (compressed) "sensor_msgs/msg/CompressedImage" else "sensor_msgs/msg/Image"
        depthSub = DynRos.subscribe(topic, Interfaces.get().msg(type), config.qosProfile()) { v ->
            received++
            try {
                latest.set((if (compressed) parseCompressed(v) else parse(v)) ?: return@subscribe)
                error = null
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
        val colorTopic = config.string("colorTopic").trim()
        if (colorTopic.isNotEmpty()) {
            val t = ImageTopics.normalize(colorTopic)
            colorStream = RvizImageStreams.open(host, ImageTopic(t, kindOf(t)), TopicDisplay.qosProfile(QosPreset.SENSOR), null, ::onFrame) {}
        }
    }

    private fun parseCompressed(v: Map<String, Any?>): Cloud? {
        val data = v["data"] as? ByteArray ?: return null
        val d = com.juancams.rviz2mini.rviz.geom.CompressedDepth.decode((v["format"] as? String).orEmpty(), data)
        val image = depthImage(d.width, d.height, com.juancams.rviz2mini.rviz.geom.CompressedDepth.toFloat32Bytes(d), v["header"])
        return parse(image)
    }

    /** A 32FC1 image like the one arriving on the uncompressed topic. */
    private fun depthImage(w: Int, h: Int, data: ByteArray, header: Any?): Map<String, Any?> =
        mapOf("encoding" to "32FC1", "width" to w, "height" to h, "step" to w * 4, "data" to data, "is_bigendian" to 0, "header" to header)

    private fun parse(v: Map<String, Any?>): Cloud? {
        val enc = (v["encoding"] as? String).orEmpty()
        if (!CameraMath.isDepthEncoding(enc)) throw IllegalArgumentException("Encoding '$enc' is not a depth image (16UC1 or 32FC1)")
        val w = (v["width"] as? Number)?.toInt() ?: return null
        val h = (v["height"] as? Number)?.toInt() ?: return null
        val step = (v["step"] as? Number)?.toInt() ?: return null
        val data = v["data"] as? ByteArray ?: return null
        val big = (v["is_bigendian"] as? Number)?.toInt() == 1 || v["is_bigendian"] == true
        val i = info?.latest?.get() ?: return null
        val intr = if (i.intrinsics.width == w && i.intrinsics.height == h) i.intrinsics else CameraMath.scaled(i.intrinsics, w, h)
        val stride = CameraMath.strideFor(w, h, config.int("maxPoints"))
        val pixels = IntArray(((w + stride - 1) / stride) * ((h + stride - 1) / stride) * 2)
        val xyz = CameraMath.depthPoints(data, enc, w, h, step, big, intr, stride, config.number("maxRange"), pixels)
        val frame = ((v["header"] as? Map<*, *>)?.get("frame_id") as? String).orEmpty().ifEmpty { i.frame }
        return Cloud(frame, xyz, pixels.copyOf(xyz.size / 3 * 2), w, h)
    }

    override fun onDeactivate() {
        depthSub?.close(); depthSub = null
        info?.close(); info = null
        colorStream?.stop(); colorStream = null
        latest.set(null); cloud = null; colors = null
    }

    override fun onConfigChanged(old: DisplayConfig) {
        if (active && (old.topic != config.topic || old.string("cameraInfo") != config.string("cameraInfo") ||
                old.string("colorTopic") != config.string("colorTopic") || old.qosKey() != config.qosKey())) {
            onDeactivate(); onActivate()
        }
        dirty = true
    }

    private fun onFrame(argb: IntArray, w: Int, h: Int, info: CameraFrameInfo) {
        if (!active) return
        // The pixels may come from a reused buffer: they are copied.
        colors = argb.copyOf(w * h); colorW = w; colorH = h
        dirty = true
    }

    override fun render(ctx: FrameContext) {
        latest.getAndSet(null)?.let { cloud = it; dirty = true }
        val c = cloud
        when {
            error != null -> { setStatus(StatusLevel.ERROR, error!!); return }
            info?.latest?.get() == null -> { waiting("Waiting for camera_info on $infoTopic", infoTopic); return }
            received == 0L || c == null -> { waiting("Waiting for depth images on ${config.topic}"); return }
        }
        c!!
        if (dirty) { rebuild(c); dirty = false }
        val t = ctx.transformF(c.frame) ?: run { geometry.setVisible(false); tfMissing(c.frame, ctx); return }
        geometry.setVisible(true)
        geometry.setTransform(t)
        setStatus(StatusLevel.OK, "${c.xyz.size / 3} points · ${c.width}×${c.height}")
    }

    private fun rebuild(c: Cloud) {
        val b = MeshBuilder()
        b.circlePoints = config.string("style") == "circles"
        val size = config.number("size").toFloat()
        val alpha = config.number("alpha").toFloat()
        val maxRange = config.number("maxRange").coerceAtLeast(0.01)
        val px = colors
        for (k in 0 until c.xyz.size / 3) {
            val z = c.xyz[k * 3 + 2]
            val rgb = if (px != null && colorW > 0) {
                val u = c.pixels[k * 2] * colorW / c.width; val v = c.pixels[k * 2 + 1] * colorH / c.height
                px[(v.coerceIn(0, colorH - 1)) * colorW + u.coerceIn(0, colorW - 1)] or (0xFF shl 24)
            } else Colors.rainbow((z / maxRange).toFloat())
            b.points.add(c.xyz[k * 3], c.xyz[k * 3 + 1], z, Colors.withAlpha(rgb, alpha), size)
        }
        geometry.update(b)
    }

    override fun setVisible(v: Boolean) = geometry.setVisible(v)
    override fun destroy() { super.destroy(); geometry.destroy() }
}

// ====================================================================== Effort

/**
 * RViz "Effort" display: the effort of each sensor_msgs/JointState joint as an arc
 * around its axis (from the /robot_description URDF): longer and redder the closer to the
 * effort limit; the arrowhead shows the direction.
 */

/**
 * The kind of an image topic: its type in the graph if it is there; otherwise by the image_transport name
 * (".../compressed", ".../foxglove", ".../ffmpeg").
 */
fun kindOf(topic: String): ImageKind {
    val types = com.juancams.rviz2mini.ros.RosConnection.graph()?.topics(includeHidden = true)?.firstOrNull { it.name == topic }?.types.orEmpty()
    ImageKind.entries.firstOrNull { it.rosType in types }?.let { return it }
    return when {
        topic.endsWith("/compressed") || topic.endsWith("/compressedDepth") -> ImageKind.COMPRESSED
        topic.endsWith("/foxglove") -> ImageKind.VIDEO
        topic.endsWith("/ffmpeg") -> ImageKind.FFMPEG
        else -> ImageKind.RAW
    }
}

/** The annotations topic of an Image or Camera display, or null when there is none or they are hidden. */
fun annotationsTopic(c: DisplayConfig): String? =
    c.string("annotations").trim().takeIf { it.isNotEmpty() && (c.type.spec("showAnnotations") == null || c.bool("showAnnotations")) }
