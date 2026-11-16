// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.juancams.rviz2mini.platform.SystemClock
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.display.Display
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayFactory
import com.juancams.rviz2mini.rviz.display.DisplayHost
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.FrameContext
import com.juancams.rviz2mini.rviz.display.LabelSink
import com.juancams.rviz2mini.rviz.render.TextEntity
import com.juancams.rviz2mini.rviz.render.TextItem
import com.juancams.rviz2mini.tabs.TabStore

/**
 * The 3D scene of an RViz tab without the platform-specific parts (the surface it is drawn on and the gestures):
 * Filament (renderer, scene, view and camera), the displays with their configuration saved in the tab, the fixed
 * frame, the camera ([ViewController]) and the texts. Main thread. Requires [FilamentHub.isPrepared].
 *
 * `density`: pixels per dp (fixed on-screen text size). `onStatusChanged`: some display changed state.
 */
class RvizScene(
    val tabId: String, private val density: Float = 1f,
    /** The platform's view layer on top of the 3D one and its context (Android: FrameLayout and Context, for Image/Camera). */
    override val overlay: Any? = null, override val androidContext: Any? = null,
    val viewCtl: ViewController = ViewController(),
    private val onStatusChanged: () -> Unit = {},
) : DisplayHost {
    private val store = TabStore.get()
    fun setting(key: String): String? = store.get(tabId)?.settings?.get(key)
    fun saveSetting(key: String, value: String?) = store.updateSetting(tabId, key, value)

    override val engine: Engine
    override val scene: Scene
    lateinit var renderer: Renderer
        private set
    val view: View
    val camera: Camera
    val tfTree get() = RosConnection.tfTree

    val configs = ArrayList<DisplayConfig>()
    val displays = LinkedHashMap<String, Display>()
    /** Whether it is visible (displays only subscribe then). */
    var visible = false
        set(v) { field = v; applyActivation() }

    /** The chosen fixed frame (null = the one the TF tree suggests) and the one used now. */
    var preferredFixedFrame: String? = setting(KEY_FIXED_FRAME)
        private set
    var fixedFrame = ""
        private set

    var width = 0; private set
    var height = 0; private set
    private var projectionKey = ""
    private val overlayViews = ArrayList<View>()
    private val labels = Labels()
    /** Where this frame's texts are collected (platforms add their own, e.g. the measurement). */
    val labelSink: LabelSink get() = labels
    private var destroyed = false
    /**
     * Whether the background color goes through post-processing (opaque view, Android): then it is linear. With the translucent view
     * (PC, offscreen) it goes straight to the image and is given in sRGB.
     */
    var linearBackground = true
        set(v) { field = v; if (::renderer.isInitialized) applyBackground() }


    /** A topic dropped on top (dashboard): a display of its type, or turn on the one already showing it. */
    private val dropHandler = com.juancams.rviz2mini.dashboard.TopicDrop.Handler { p ->
        val types = DisplayFactory.displaysFor(p.topic, listOf(p.type))
        val type = types.firstOrNull() ?: return@Handler com.juancams.rviz2mini.i18n.tr("drop_rviz_no", p.topic, p.type.substringAfterLast('/'))
        configs.firstOrNull { it.topic == p.topic && it.type in types }?.let { c ->
            if (!c.enabled) updateDisplay(c.copy(enabled = true))
            onStatusChanged()
            return@Handler com.juancams.rviz2mini.i18n.tr("drop_rviz_exists", c.name)
        }
        addDisplay(DisplayConfig.create(type, p.topic.substringAfterLast('/').ifEmpty { type.label }, p.topic))
        onStatusChanged()
        com.juancams.rviz2mini.i18n.tr("drop_rviz_added", type.label, p.topic)
    }

    init {
        FilamentHub.acquire()
        engine = FilamentHub.engine
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(EntityManager.get().create())
        view.scene = scene
        view.camera = camera
        view.colorGrading = FilamentHub.colorGrading
        setting(KEY_VIEW)?.let { json ->
            try {
                val o = org.json.JSONObject(json)
                viewCtl.loadFrom(o.keys().asSequence().associateWith { o.getString(it) })
            } catch (_: Exception) {}
        }
        applyBackground()
        configs.addAll(DisplayList.fromJson(setting(KEY_DISPLAYS)) ?: DisplayList.defaults())
        for (c in configs) displays[c.id] = DisplayFactory.create(this, c)
        com.juancams.rviz2mini.dashboard.TopicDrop.register(tabId, dropHandler)
        // After the constructor (the properties below don't exist yet).
        com.juancams.rviz2mini.platform.Platform.main.post { if (!destroyed && rewindEnabled && rewind == null) setRewind(true) }
    }

    // ---------------------------------------------------------------- going back without a bag

    /** The latest data of this view's topics (and TF), to go back ([com.juancams.rviz2mini.bag.InstantReplay]). */
    var rewind: com.juancams.rviz2mini.bag.LiveBuffer? = null; private set
    /** Recording the latest data: on by default on PC; on the phone, if turned on (uses memory and CPU). */
    val rewindEnabled: Boolean get() = (setting(KEY_REWIND) ?: (com.juancams.rviz2mini.platform.Platform.name != "android").toString()) == "true"

    fun setRewind(on: Boolean) {
        saveSetting(KEY_REWIND, on.toString())
        if (on) {
            // Memory: at most 80 MB on the phone and 300 MB on PC, and never more than 1/8 of what the app has.
            val cap = minOf(if (com.juancams.rviz2mini.platform.Platform.name == "android") 80L shl 20 else 300L shl 20, Runtime.getRuntime().maxMemory() / 8)
            if (rewind == null) rewind = com.juancams.rviz2mini.bag.LiveBuffer(com.juancams.rviz2mini.ros.dyn.Interfaces.get(), REWIND_WINDOW_S, cap)
            refreshRewind()
        } else { rewind?.close(); rewind = null }
    }

    /**
     * The go-back button: if it wasn't recording, it starts (and says so); if it was, it replays what was recorded with the
     * time bar. Same on the phone and on PC.
     */
    fun rewindNow(isDemo: Boolean = false, onMessage: (String) -> Unit) {
        val b = rewind
        if (b == null) { setRewind(true); onMessage(com.juancams.rviz2mini.i18n.tr("rewind_on", REWIND_WINDOW_S)); return }
        com.juancams.rviz2mini.bag.InstantReplay.start(b, isDemo, onMessage)
    }

    /** Which topics it records: those of the enabled displays, with their real type if the network reports it, and TF. */
    fun refreshRewind() {
        val b = rewind ?: return
        val graph = com.juancams.rviz2mini.ros.RosConnection.graph()?.topics(includeHidden = true)?.associate { it.name to it.types }
        val wanted = LinkedHashMap<String, String>()
        wanted["/tf"] = "tf2_msgs/msg/TFMessage"; wanted["/tf_static"] = "tf2_msgs/msg/TFMessage"
        for (c in configs) {
            if (!c.enabled || !c.type.needsTopic || c.topic.isBlank() || c.type == com.juancams.rviz2mini.rviz.display.DisplayType.INTERACTIVE_MARKERS) continue
            val t = com.juancams.rviz2mini.ros.TopicVariables.resolve(c.topic)
            wanted[t] = graph?.get(t)?.firstOrNull() ?: c.type.rosType
        }
        b.setTopics(wanted)
        // No graph yet (just connected): again in a while to pick up the real types.
        if (graph == null) com.juancams.rviz2mini.platform.Platform.main.postDelayed({ if (!destroyed) refreshRewind() }, 3000)
    }


    // ---------------------------------------------------------------- DisplayHost

    override fun onDisplayStatusChanged() = onStatusChanged()
    override fun updateDisplayConfig(c: DisplayConfig, persist: Boolean) { updateDisplay(c, persist); if (persist) onStatusChanged() }
    override val pxPerDp: Float get() = density
    override val surfaceWidth: Int get() = width
    override val surfaceHeight: Int get() = height
    override fun addOverlayView(view: View) {
        if (view in overlayViews) return
        // They draw where the main one does and the same way (on PC: its texture and no post-processing).
        this.view.renderTarget?.let { view.renderTarget = it }
        if (!this.view.isPostProcessingEnabled) view.isPostProcessingEnabled = false
        overlayViews += view
    }
    override fun removeOverlayView(view: View) { overlayViews -= view }
    override val colorGrading: ColorGrading? get() = FilamentHub.colorGrading

    // ---------------------------------------------------------------- displays

    fun applyActivation() { for (c in configs) displays[c.id]?.setActive(visible && c.enabled) }
    private fun saveConfigs() = saveSetting(KEY_DISPLAYS, DisplayList.toJson(configs))

    fun addDisplay(c: DisplayConfig) {
        configs.add(c)
        val d = DisplayFactory.create(this, c)
        displays[c.id] = d
        d.setActive(visible && c.enabled)
        saveConfigs()
        refreshRewind()
    }

    /** `persist` false: only in memory (e.g. while a panel is being dragged); the last change must persist. */
    fun updateDisplay(c: DisplayConfig, persist: Boolean = true) {
        val i = configs.indexOfFirst { it.id == c.id }
        if (i < 0) return
        configs[i] = c
        displays[c.id]?.let { d -> d.updateConfig(c); d.setActive(visible && c.enabled) }
        if (!persist) return
        saveConfigs()
        refreshRewind()
    }

    /** Replaces all the displays (when importing a `.rviz`). */
    fun replaceDisplays(list: List<DisplayConfig>) {
        for (d in displays.values) d.destroy()
        displays.clear()
        configs.clear()
        configs.addAll(list)
        for (c in configs) displays[c.id] = DisplayFactory.create(this, c)
        saveConfigs()
        applyActivation()
    }

    /** Applies an imported `.rviz`: displays, fixed frame, background and view (the tool topics, [RvizTools]). */
    fun applyImport(r: com.juancams.rviz2mini.rviz.rvizfile.RvizImport) {
        replaceDisplays(r.displays)
        r.fixedFrame?.let { setPreferredFixedFrame(it) }
        r.background?.let { saveSetting(KEY_BACKGROUND, Colors.toHex(it)); applyBackground() }
        r.view?.let { v -> viewCtl.loadFrom(v); invalidateProjection(); saveView() }
    }

    /** This view as an RViz 2 .rviz file (see [com.juancams.rviz2mini.rviz.rvizfile.RvizExporter]). */
    fun exportRviz(goalTopic: String?, poseTopic: String?, pointTopic: String?): String =
        com.juancams.rviz2mini.rviz.rvizfile.RvizExporter.export(configs.toList(), preferredFixedFrame ?: fixedFrame,
            Colors.parseHex(setting(KEY_BACKGROUND)) ?: DEFAULT_BACKGROUND, viewCtl.toMap(), goalTopic, poseTopic, pointTopic, height.takeIf { it > 0 } ?: 1000)

    fun setBackground(color: Int) { saveSetting(KEY_BACKGROUND, Colors.toHex(color)); applyBackground() }

    fun removeDisplay(id: String) {
        configs.removeAll { it.id == id }
        displays.remove(id)?.destroy()
        saveConfigs()
        refreshRewind()
    }

    // ---------------------------------------------------------------- view

    /** null = the one the TF tree suggests. */
    /** `null` = automatic. A frame written by hand may not exist yet: until it appears in TF, the automatic one is used. */
    fun setPreferredFixedFrame(f: String?) {
        val id = f?.let { com.juancams.rviz2mini.TfTree.normalizeId(it.trim()) }?.ifEmpty { null }
        preferredFixedFrame = id; saveSetting(KEY_FIXED_FRAME, id); resolveFixedFrame()
    }

    fun resolveFixedFrame() {
        val ids = tfTree.frameIds()
        val preferred = preferredFixedFrame
        fixedFrame = if (preferred != null && (preferred in ids || ids.isEmpty())) preferred
        else tfTree.chooseDefaultFixedFrame() ?: preferred ?: ""
    }

    fun saveView() = saveSetting(KEY_VIEW, org.json.JSONObject(viewCtl.toMap()).toString())
    fun resetView() { viewCtl.reset(); saveView() }

    // ---------------------------------------------------------------- saved views (RViz's Views panel)

    /** The named saved views, in the order they were saved. */
    fun savedViews(): List<Pair<String, Map<String, String>>> = try {
        val a = org.json.JSONArray(setting(KEY_SAVED_VIEWS) ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            val v = o.getJSONObject("view")
            o.getString("name") to v.keys().asSequence().associateWith { k -> v.getString(k) }
        } }
    } catch (_: Exception) { emptyList() }

    private fun storeViews(list: List<Pair<String, Map<String, String>>>) = saveSetting(KEY_SAVED_VIEWS,
        org.json.JSONArray().apply { for ((n, v) in list) put(org.json.JSONObject().put("name", n).put("view", org.json.JSONObject(v))) }.toString())

    /** Saves the current view with that name (if it already exists, it replaces it). */
    fun saveCurrentView(name: String) = storeViews(savedViews().filter { it.first != name } + (name to viewCtl.toMap()))

    fun applySavedView(name: String) {
        val v = savedViews().firstOrNull { it.first == name }?.second ?: return
        viewCtl.loadFrom(v); invalidateProjection(); saveView()
    }

    fun deleteSavedView(name: String) = storeViews(savedViews().filter { it.first != name })
    fun setViewType(t: ViewType) { viewCtl.type = t; projectionKey = ""; saveView() }
    /** The camera changed from outside (tests, import): the projection is recomputed. */
    fun invalidateProjection() { projectionKey = "" }

    fun applyBackground() {
        val c = Colors.parseHex(setting(KEY_BACKGROUND)) ?: DEFAULT_BACKGROUND
        fun ch(v: Int) = (if (linearBackground) FilamentHub.srgbToLinear(v / 255f) else v / 255f).toDouble()
        renderer.setClearOptions(Renderer.ClearOptions().apply {
            // The clear color is linear: it is converted so the chosen sRGB color is seen.
            clearColor = doubleArrayOf(ch(Colors.red(c)), ch(Colors.green(c)), ch(Colors.blue(c)), 1.0)
            clear = true
        })
    }

    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == width && h == height)) return
        width = w; height = h
        view.viewport = Viewport(0, 0, w, h)
        for (v in overlayViews) this.view.renderTarget?.let { v.renderTarget = it }
        projectionKey = ""
    }

    /** Applies the current view to the Filament camera (position every frame, projection if it changes). */
    private fun applyCamera() {
        if (viewCtl.type == ViewType.FOLLOW) {
            val p = DoubleArray(16)
            if (tfTree.poseInFixed(viewCtl.followFrame, p)) viewCtl.setFollowPose(p[12], p[13], p[14], ViewController.yawOf(p))
        }
        val e = viewCtl.eye(); val t = viewCtl.target(); val u = viewCtl.up()
        camera.lookAt(e[0], e[1], e[2], t[0], t[1], t[2], u[0], u[1], u[2])
        if (width <= 0 || height <= 0) return
        val aspect = width.toDouble() / height.toDouble()
        val key = if (viewCtl.isOrtho) "o:$aspect:${viewCtl.scale}" else "p:$aspect:${viewCtl.near}"
        if (key == projectionKey) return
        projectionKey = key
        if (viewCtl.isOrtho) {
            val (hw, hh) = viewCtl.orthoHalfExtents(aspect)
            camera.setProjection(Camera.Projection.ORTHO, -hw, hw, -hh, hh, viewCtl.near, viewCtl.far)
        } else {
            camera.setProjection(viewCtl.fovYDegrees, aspect, viewCtl.near, viewCtl.far, Camera.Fov.VERTICAL)
        }
    }

    fun frameContext() = FrameContext(tfTree, fixedFrame, SystemClock.uptimeMillis(), labels)

    /** Converts from the fixed frame to the screen (px). */
    fun projector(): (DoubleArray) -> FloatArray? {
        val w = width.toFloat(); val h = height.toFloat()
        val vp = viewCtl.viewProjection(w.toDouble() / h, DoubleArray(16))
        return { p -> FloatArray(2).takeIf { viewCtl.project(vp, p[0], p[1], p[2], w, h, it) } }
    }

    /**
     * One frame: TF, camera, displays and texts; then draws it to `swapChain` (if given). `extra`: what the
     * platform adds before closing the texts (measurements...); `onRendered`: before closing the frame (reading
     * the pixels on PC). Returns whether it was drawn.
     */
    fun renderFrame(swapChain: SwapChain?, frameTimeNanos: Long, extra: (FrameContext) -> Unit = {}, onRendered: ((Renderer) -> Unit)? = null): Boolean {
        if (destroyed) return false
        resolveFixedFrame()
        tfTree.compute(fixedFrame)
        applyCamera()
        labels.begin()
        val ctx = frameContext()
        for (c in configs) {
            if (!c.enabled) continue
            displays[c.id]?.render(ctx)
        }
        extra(ctx)
        labels.end()
        val sc = swapChain ?: return false
        if (!renderer.beginFrame(sc, frameTimeNanos)) return false
        renderer.render(view)
        for (v in overlayViews) renderer.render(v)
        onRendered?.invoke(renderer)
        renderer.endFrame()
        return true
    }

    val labelCount: Int get() = labels.count

    fun destroy() {
        if (destroyed) return
        destroyed = true
        com.juancams.rviz2mini.dashboard.TopicDrop.unregister(tabId, dropHandler)
        rewind?.close(); rewind = null
        saveView()
        for (d in displays.values) d.destroy()
        displays.clear()
        labels.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        val cameraEntity = camera.entity
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        FilamentHub.release()
    }

    /** Scene texts (TF names, text markers) drawn inside Filament, synchronized with the view. */
    private inner class Labels : LabelSink {
        private val items = ArrayList<TextItem>()
        private var entity: TextEntity? = null
        private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

        fun begin() = items.clear()

        override fun label(key: String, text: String, x: Double, y: Double, z: Double, color: Int, height: Double, screenDp: Boolean) {
            if (text.isEmpty()) return
            val h = if (screenDp) (height * density).toFloat() else height.toFloat()
            items.add(TextItem(text, x.toFloat(), y.toFloat(), z.toFloat(), color or (0xFF shl 24), h, screenDp))
        }

        fun end() {
            val e = entity ?: TextEntity(engine, scene, FilamentHub.matText, FilamentHub.textAtlas).also { entity = it }
            e.update(ArrayList(items))
            val tm = engine.transformManager
            val inst = tm.getInstance(e.entity)
            if (inst != 0) tm.setTransform(inst, identity)
        }

        val count: Int get() = items.size
        fun destroy() { entity?.destroy(); entity = null }
    }

    companion object {
        const val KEY_FIXED_FRAME = "fixedFrame"
        const val KEY_DISPLAYS = "displays"
        const val KEY_REWIND = "rewind"
        const val REWIND_WINDOW_S = 60
        const val KEY_VIEW = "view"
        const val KEY_SAVED_VIEWS = "savedViews"
        const val KEY_BACKGROUND = "background"
        val DEFAULT_BACKGROUND = 0xFF141419.toInt()
    }
}
