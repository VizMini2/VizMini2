// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.display

import com.juancams.rviz2mini.platform.SystemClock
import com.juancams.rviz2mini.platform.Log
import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.Scene
import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.TfTree
import com.juancams.rviz2mini.camera.ImageTopics
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.FilamentHub
import com.juancams.rviz2mini.rviz.geom.MeshBuilder
import com.juancams.rviz2mini.rviz.render.MeshEntity
import com.juancams.rviz2mini.rviz.render.PointsEntity
import us.ihmc.jros2.ROS2Message
import us.ihmc.jros2.ROS2QoSProfile
import us.ihmc.jros2.ROS2SubscriptionCallback
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

enum class StatusLevel { OK, WARN, ERROR, DISABLED }

data class DisplayStatus(val level: StatusLevel, val message: String)

/** What the displays need from the RViz tab that contains them. Main thread. */
interface DisplayHost {
    val engine: Engine
    val scene: Scene
    /** Some state has changed: refresh the displays strip. */
    fun onDisplayStatusChanged()
    /** Platform view layer over the 3D view (Android: a FrameLayout, for the Image display). */
    val overlay: Any? get() = null
    /** Platform context (Android: the Context; used by the camera displays). */
    val androidContext: Any? get() = null
    /** Pixels per dp of the screen (margins of the camera windows). */
    val pxPerDp: Float get() = 1f
    /** Width and height of the 3D view in pixels (0 if it does not have one yet). */
    val surfaceWidth: Int get() = 0
    val surfaceHeight: Int get() = 0
    /** Filament views drawn on top of the main one, in their viewport (Camera display). */
    fun addOverlayView(view: com.google.android.filament.View) {}
    fun removeOverlayView(view: com.google.android.filament.View) {}
    /** Image settings of the main view, so the others look the same. */
    val colorGrading: com.google.android.filament.ColorGrading? get() = null
    /** A display changes its own configuration (e.g. its image panel was dragged); `persist` false while dragging. */
    fun updateDisplayConfig(c: DisplayConfig, persist: Boolean = true) {}
}

/** Text labels (TF names, text markers). Position in the fixed frame. */
interface LabelSink {
    /** Height in meters, or in screen dp if `screenDp` (constant size when zooming). */
    fun label(key: String, text: String, x: Double, y: Double, z: Double, color: Int, height: Double, screenDp: Boolean = false)
}

/** A point picked with the Select tool: in the fixed frame and the sensor's frame, and how many pixels from the finger. */
class PickedPoint(val fixed: DoubleArray, val local: DoubleArray, val frame: String, val display: String, val distPx: Float,
                  /** The point's values (like RViz's Selection panel): index, distance, intensity, cloud fields... */
                  val fields: List<Pair<String, String>> = emptyList())

/** Displays with points that can be picked with the Select tool (clouds, laser). */
interface PointPicker {
    /** The point closest to (sx, sy) within `maxPx`; `project` maps from the fixed frame to the screen. */
    fun pickPoint(sx: Float, sy: Float, maxPx: Float, project: (DoubleArray) -> FloatArray?): PickedPoint?
}

/** Data for one render frame: TF already resolved against the fixed frame. */
class FrameContext(val tf: TfTree, val fixedFrame: String, val nowMs: Long, val labels: LabelSink) {
    private val tmpD = DoubleArray(16)
    private val tmpF = FloatArray(16)

    /** fixed ← frame (in double precision, shared temporary array) or null if there is no TF. */
    fun transformD(frame: String): DoubleArray? {
        val id = TfTree.normalizeId(frame).ifEmpty { fixedFrame }
        // The fixed frame relative to itself is the identity even if no TF has arrived yet.
        if (id.isEmpty() || id == fixedFrame) return TfMath.setIdentity(tmpD).let { tmpD }
        return if (tf.poseInFixed(id, tmpD)) tmpD else null
    }

    /** Same as [transformD] but in float for Filament (shared temporary array). */
    fun transformF(frame: String): FloatArray? = transformD(frame)?.let { TfMath.toFloat(it, tmpF) }

    /**
     * fixed ← frame at the message's `stampNs` instant (interpolated TF), in float for Filament
     * (new array: it can be kept). stamp 0 or no history = latest TF.
     */
    fun transformAtF(frame: String, stampNs: Long, local: DoubleArray? = null): FloatArray? {
        val id = TfTree.normalizeId(frame).ifEmpty { fixedFrame }
        val m = DoubleArray(16)
        if (id.isEmpty() || id == fixedFrame) TfMath.setIdentity(m)
        else if (!tf.lookupInFixed(id, fixedFrame, stampNs, m)) return null
        val r = if (local != null) TfMath.multiply(m, local, DoubleArray(16)) else m
        return TfMath.toFloat(r, FloatArray(16))
    }

    /** fixed ← frame ← `local`. */
    fun transformF(frame: String, local: DoubleArray): FloatArray? {
        val t = transformD(frame) ?: return null
        return TfMath.toFloat(TfMath.multiply(t, local, DoubleArray(16)), tmpF)
    }
}

/**
 * Filament entities of a display: lines, opaque/transparent triangles, thick segments and
 * points. Created on demand; points and segments switch material by style and alpha.
 */
class GeometrySet(private val engine: Engine, private val scene: Scene) {
    private var lines: MeshEntity? = null
    private var triangles: MeshEntity? = null
    private var transparent: MeshEntity? = null
    private var points: PointsEntity? = null
    private var pointsKind = -1
    private var segments: com.juancams.rviz2mini.rviz.render.ThickLinesEntity? = null
    private var segmentsTransparent = false
    private var visible = true
    private var transform: FloatArray? = null

    fun update(b: MeshBuilder) {
        if (!b.lines.isEmpty || lines != null)
            (lines ?: MeshEntity(engine, scene, PrimitiveType.LINES, FilamentHub.matVertexColor).also { lines = it; init(it) }).update(b.lines)
        if (!b.triangles.isEmpty || triangles != null)
            (triangles ?: MeshEntity(engine, scene, PrimitiveType.TRIANGLES, FilamentHub.matVertexColor).also { triangles = it; init(it) }).update(b.triangles)
        if (!b.transparentTriangles.isEmpty || transparent != null)
            (transparent ?: MeshEntity(engine, scene, PrimitiveType.TRIANGLES, FilamentHub.matVertexColorTransparent).also { transparent = it; init(it) })
                .update(b.transparentTriangles)

        // Points: material by style (squares/circles) and whether there is transparency.
        val pts = b.points
        val anyAlpha = (0 until pts.count).any { com.juancams.rviz2mini.rviz.display.Colors.alpha(pts.colors[it]) < 255 }
        val kind = (if (b.circlePoints) 2 else 0) + (if (anyAlpha) 1 else 0)
        if (pts.count > 0 && kind != pointsKind) {
            points?.destroy()
            val mat = when (kind) {
                0 -> FilamentHub.matPoints; 1 -> FilamentHub.matPointsTransparent
                2 -> FilamentHub.matCircles; else -> FilamentHub.matCirclesTransparent
            }
            points = PointsEntity(engine, scene, mat).also { init(it) }
            pointsKind = kind
        }
        points?.update(pts)

        val segs = b.segments
        val segAlpha = (0 until segs.colors.size).any { com.juancams.rviz2mini.rviz.display.Colors.alpha(segs.colors[it]) < 255 }
        if (segs.count > 0 && (segments == null || segAlpha != segmentsTransparent)) {
            segments?.destroy()
            segments = com.juancams.rviz2mini.rviz.render.ThickLinesEntity(
                engine, scene, if (segAlpha) FilamentHub.matThickLinesTransparent else FilamentHub.matThickLines
            ).also { init(it) }
            segmentsTransparent = segAlpha
        }
        segments?.update(segs)
    }

    private fun init(e: com.juancams.rviz2mini.rviz.render.RenderEntity) {
        e.setVisible(visible)
        transform?.let { e.setTransform(it) }
    }

    fun setTransform(m: FloatArray) {
        val t = transform ?: FloatArray(16).also { transform = it }
        m.copyInto(t)
        lines?.setTransform(m); triangles?.setTransform(m); transparent?.setTransform(m)
        points?.setTransform(m); segments?.setTransform(m)
    }

    fun setVisible(v: Boolean) {
        visible = v
        lines?.setVisible(v); triangles?.setVisible(v); transparent?.setVisible(v)
        points?.setVisible(v); segments?.setVisible(v)
    }

    fun destroy() {
        lines?.destroy(); triangles?.destroy(); transparent?.destroy(); points?.destroy(); segments?.destroy()
        lines = null; triangles = null; transparent = null; points = null; segments = null
    }
}

/**
 * A display of the RViz tab. Lifecycle (main thread):
 * `setActive(true)` when it is enabled and the tab visible; `render` every frame;
 * `setActive(false)` when disabled or when the tab is frozen; `destroy` when removed.
 */
abstract class Display(protected val host: DisplayHost, config: DisplayConfig) {

    var config: DisplayConfig = config
        private set

    var status: DisplayStatus = DisplayStatus(StatusLevel.DISABLED, "Disabled")
        private set

    protected var active = false
        private set

    fun updateConfig(newConfig: DisplayConfig) {
        val old = config
        config = newConfig
        if (old.topic != newConfig.topic || old.qosKey() != newConfig.qosKey()) receiving()
        onConfigChanged(old)
    }

    fun setActive(a: Boolean) {
        if (a == active) return
        active = a
        receiving()
        if (a) onActivate() else onDeactivate()
        setVisible(a)
        if (!a) setStatus(StatusLevel.DISABLED, "Disabled")
    }

    abstract fun render(ctx: FrameContext)

    /** The TF frames this display knows, if they differ from the general tree's (the editor shows them). */
    open fun knownFrames(): List<String>? = null

    protected open fun onActivate() {}
    protected open fun onDeactivate() {}
    protected open fun onConfigChanged(old: DisplayConfig) {}
    protected abstract fun setVisible(v: Boolean)

    open fun destroy() {
        if (active) onDeactivate()
        active = false
    }

    /** A fix for what is happening to the display (another topic, another QoS): the UI offers it with a button. */
    class Fix(val label: String, val apply: (DisplayConfig) -> DisplayConfig)

    /** The current fix, if any (see [waiting]). */
    var fix: Fix? = null
        private set

    private var waitingSince = 0L
    private var diagnosedAt = 0L
    private var lastDiagnosis: com.juancams.rviz2mini.ros.TopicDiagnosis.Result? = null

    /**
     * "Waiting for messages" state with the reason ([com.juancams.rviz2mini.ros.TopicDiagnosis]) and its fix. `what`: the
     * usual text ("Waiting for images on /x"). The graph is checked again every second.
     */
    protected fun waiting(what: String, topic: String = config.topic) {
        val now = SystemClock.uptimeMillis()
        if (waitingSince == 0L) waitingSince = now
        if (now - diagnosedAt > 1000) {
            diagnosedAt = now
            val type = config.type
            lastDiagnosis = com.juancams.rviz2mini.ros.TopicDiagnosis.diagnoseNow(topic, { types -> type in DisplayFactory.displaysFor(topic, types) },
                type.rosType, if (type.spec("qos") != null) config.qosSettings() else null, now - waitingSince)
            fix = when (val f = lastDiagnosis?.fix) {
                is com.juancams.rviz2mini.ros.TopicDiagnosis.Fix.UseTopic -> Fix(f.label) { it.copy(topic = f.topic) }
                is com.juancams.rviz2mini.ros.TopicDiagnosis.Fix.UseQos -> Fix(f.label) { c ->
                    var n = c
                    f.reliability?.let { n = n.with(QosSettings.RELIABILITY, it) }
                    f.durability?.let { n = n.with(QosSettings.DURABILITY, it) }
                    n
                }
                null -> null
            }
        }
        setStatus(StatusLevel.WARN, lastDiagnosis?.let { "$what · ${it.message}" } ?: what)
    }

    /** Data is arriving: the diagnosis is dropped. */
    protected fun receiving() { waitingSince = 0L; lastDiagnosis = null; fix = null }

    protected fun setStatus(level: StatusLevel, message: String) {
        val s = DisplayStatus(level, message)
        if (s != status) {
            status = s
            host.onDisplayStatusChanged()
        }
    }

    protected fun tfMissing(frame: String, ctx: FrameContext) =
        setStatus(StatusLevel.WARN, "No transform from '${TfTree.normalizeId(frame)}' to '${ctx.fixedFrame}'")
}

/**
 * Display that subscribes to a topic. The message is read and converted (`parse`) on the DDS thread;
 * the result is applied to the GPU (`consume`) on the render thread.
 */
abstract class TopicDisplay<M : ROS2Message<M>, D : Any>(
    host: DisplayHost, config: DisplayConfig, private val messageClass: Class<M>
) : Display(host, config) {

    private var subscription: RosConnection.ManagedSubscription<M>? = null
    private val latest = AtomicReference<D?>()
    private val queue = ConcurrentLinkedQueue<D>()
    @Volatile private var received = 0L
    /** Last failure reading or applying a message, how many so far and whether the last message was a bad one. */
    @Volatile private var lastError: String? = null
    @Volatile private var lastErrorMs = 0L
    @Volatile private var errorCount = 0L
    @Volatile private var lastFailed = false
    private var reused: M? = null // DDS thread only
    /** [TfTree.timeJumps] seen in the last render (-1: none yet). Render thread only. */
    private var seenTimeJumps = -1

    /**
     * The TF clock went backwards (looping bag, restarted simulation): displays that accumulate
     * data from the previous run discard it, like RViz on reset. Render thread, before applying
     * the new messages.
     */
    protected open fun onTimeJump() {}

    /** true: no message is dropped (Markers). false: only the latest matters (sensors). */
    protected open val keepAllMessages = false

    /** Reuse the instance (large messages); its sequences must be emptied in [prepareForRead]. */
    protected open val reuseMessage = false

    protected abstract fun newMessage(): M
    protected open fun prepareForRead(msg: M) {}
    protected abstract fun parse(msg: M): D?
    protected abstract fun consume(data: D, ctx: FrameContext)

    /** Draws with the current TF. Returns the status (null = OK with no message). */
    protected abstract fun draw(ctx: FrameContext): DisplayStatus?

    val messagesReceived: Long get() = received

    override fun onActivate() = subscribe()

    override fun onDeactivate() {
        subscription?.cancel()
        subscription = null
        latest.set(null)
        queue.clear()
    }

    override fun onConfigChanged(old: DisplayConfig) {
        if (active && (old.topic != config.topic || old.qosKey() != config.qosKey())) {
            onDeactivate()
            received = 0
            subscribe()
        }
    }

    private fun subscribe() {
        val topic = ImageTopics.normalize(config.topic)
        if (!ImageTopics.isValidName(topic)) {
            setStatus(StatusLevel.ERROR, "Invalid topic: '${config.topic}'")
            return
        }
        lastError = null
        errorCount = 0
        lastFailed = false
        subscription = RosConnection.subscribe(topic, messageClass, config.qosProfile(), ROS2SubscriptionCallback { reader ->
            val msg = if (reuseMessage) (reused ?: newMessage().also { reused = it }) else newMessage()
            if (reuseMessage) prepareForRead(msg)
            if (!reader.read(msg)) return@ROS2SubscriptionCallback
            received++
            try {
                val d = parse(msg)
                lastFailed = false
                if (d == null) return@ROS2SubscriptionCallback
                if (keepAllMessages) {
                    queue.add(d)
                    while (queue.size > MAX_QUEUE) queue.poll()
                } else latest.set(d)
            } catch (t: Throwable) {
                noteError(t)
            }
        })
    }

    private fun noteError(t: Throwable) {
        lastError = t.message ?: t.javaClass.simpleName
        lastErrorMs = SystemClock.uptimeMillis()
        errorCount++
        lastFailed = true
    }

    final override fun render(ctx: FrameContext) {
        // A message that cannot be applied does not crash the app (this runs on the render thread).
        fun apply(d: D) = try { consume(d, ctx) } catch (e: Exception) { Log.w(TAG, "${config.topic}: no se pudo aplicar un mensaje", e); noteError(e) }
        val jumps = ctx.tf.timeJumps
        if (jumps != seenTimeJumps) {
            if (seenTimeJumps >= 0) try { onTimeJump() } catch (e: Exception) { Log.w(TAG, "${config.topic}: error reiniciando tras un salto de reloj", e) }
            seenTimeJumps = jumps
        }
        // Everything paused (LiveView): messages wait; they are applied on resume (the latest, or the queue if it keeps all).
        if (!com.juancams.rviz2mini.LiveView.frozen) {
            if (keepAllMessages) {
                while (true) apply(queue.poll() ?: break)
            } else latest.getAndSet(null)?.let { apply(it) }
        }

        val drawn = try { draw(ctx) } catch (e: Exception) {
            Log.w(TAG, "${config.topic}: error dibujando", e)
            DisplayStatus(StatusLevel.ERROR, "Draw error: ${e.message ?: e.javaClass.simpleName}")
        }
        val subError = subscription?.error
        val recentErrors = errorCount > 0 && ctx.nowMs - lastErrorMs < ERROR_SHOWN_MS
        when {
            subError != null -> setStatus(StatusLevel.ERROR, subError)
            // The last message is bad: what arrives is not being shown.
            lastFailed -> setStatus(StatusLevel.ERROR, "Read error: $lastError")
            received == 0L -> waiting("Waiting for messages on ${config.topic}")
            drawn != null && drawn.level == StatusLevel.ERROR -> { receiving(); setStatus(drawn.level, drawn.message) }
            // The following ones arrive fine: drawing continues, with a warning for a few seconds.
            recentErrors -> { receiving(); setStatus(StatusLevel.WARN, "$errorCount message(s) could not be read: $lastError") }
            drawn != null -> { receiving(); setStatus(drawn.level, drawn.message) }
            else -> { receiving(); setStatus(StatusLevel.OK, "$received messages") }
        }
    }

    companion object {
        private const val MAX_QUEUE = 2000
        private const val TAG = "VizMini2"
        /** How long the bad-message warning lasts once the following ones arrive fine. */
        private const val ERROR_SHOWN_MS = 5000L

        fun qosProfile(p: QosPreset): ROS2QoSProfile = p.profile()

        fun now(): Long = SystemClock.uptimeMillis()
    }
}

/** A display this platform cannot draw yet: it stays in the list with a warning (and its configuration is kept). */
class UnavailableDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {
    override fun render(ctx: FrameContext) { if (active) setStatus(StatusLevel.WARN, "Not available on this platform yet") }
    override fun setVisible(v: Boolean) {}
}
