// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.ui.showComposeDialog
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.View as AndroidView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.google.android.filament.*
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.android.UiHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.juancams.rviz2mini.R
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.display.Display
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayFactory
import com.juancams.rviz2mini.rviz.display.DisplayHost
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.FrameContext
import com.juancams.rviz2mini.rviz.display.LabelSink
import com.juancams.rviz2mini.rviz.display.StatusLevel
import com.juancams.rviz2mini.tabs.TabFragment

/**
 * RViz-style 3D viewer: top bar (fixed frame, view, menu), 3D view and a strip with the
 * displays. Tap a display = enable/disable; long press = edit.
 * There can be several RViz tabs; each one has its own displays, camera and fixed frame.
 */
class RvizFragment : TabFragment() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var uiHelper: UiHelper
    /** The scene shared with the PC (rviz3d): Filament, displays, fixed frame, camera and texts. */
    private var rs: RvizScene? = null
    private val engine: Engine get() = rs!!.engine
    private val scene: Scene get() = rs!!.scene
    private var swapChain: SwapChain? = null
    private var filamentReady = false
    /** Identifies the current view so a preparation that finishes after it is destroyed is discarded. */
    private var setupToken: Any? = null

    private lateinit var labelsContainer: FrameLayout
    private lateinit var fixedFrameButton: MaterialButton
    private lateinit var displayStrip: LinearLayout

    private val tfTree get() = RosConnection.tfTree
    private val fixedFrame: String get() = rs?.fixedFrame ?: ""

    private val configs: ArrayList<DisplayConfig> get() = rs?.configs ?: ArrayList()
    private val displays: LinkedHashMap<String, Display> get() = rs?.displays ?: LinkedHashMap()
    private var resumed = false
    private var stripRefreshPending = false

    private val viewCtl = ViewController()
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTwistAngle: Double? = null
    private lateinit var viewTypeButton: MaterialButton

    /**
     * RViz tools: the Nav2 ones ("2D Goal Pose" / "2D Pose Estimate"), "Publish Point"
     * (/clicked_point) and "Measure".
     */
    enum class Tool(@androidx.annotation.StringRes val labelRes: Int, val color: Int) {
        NONE(0, 0), GOAL(R.string.rviz_tool_goal, 0xFF4CAF50.toInt()), POSE_ESTIMATE(R.string.rviz_tool_pose, 0xFFE040FB.toInt()),
        PUBLISH_POINT(R.string.rviz_tool_point, 0xFFFFC107.toInt()), MEASURE(R.string.rviz_tool_measure, 0xFF29B6F6.toInt()),
        INTERACT(R.string.rviz_tool_interact, 0xFFFF7043.toInt()), SELECT(R.string.rviz_tool_select, 0xFF7C4DFF.toInt()), // violet: white was not visible on the map floor
        WAYPOINTS(R.string.rviz_tool_waypoints, 0xFFFF9800.toInt()),
        MOVEIT_GOAL(R.string.rviz_tool_moveit, 0xFF42A5F5.toInt())
    }

    /** Interact with interactive markers, shared with the PC (rviz3d). */
    private var interact: RvizInteract? = null
    private var tool = Tool.NONE
    private lateinit var goalButton: MaterialButton
    private lateinit var poseButton: MaterialButton
    private lateinit var moreToolsButton: android.widget.ImageButton
    /** Measurement in progress or last measurement (Measure): endpoints in the fixed frame. */
    /** The tools shared with the PC (rviz3d): Goal, Pose, Point, Measure and Select. */
    private var tools: RvizTools? = null
    /** For the Nav2 bar (Compose): the active tool and a counter of route changes. */
    private val navTool = androidx.compose.runtime.mutableStateOf(RvizTool.NONE)
    private val navVersion = androidx.compose.runtime.mutableIntStateOf(0)
    private val navMission = androidx.compose.runtime.mutableStateOf<com.juancams.rviz2mini.nav.Nav2Mission?>(null)
    private val goalTopic get() = setting(KEY_GOAL_TOPIC) ?: "/goal_pose"
    private val poseTopic get() = setting(KEY_POSE_TOPIC) ?: "/initialpose"
    private val pointTopic get() = setting(KEY_POINT_TOPIC) ?: "/clicked_point"
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private val screenTmp = FloatArray(2)
    private val vpMatrix = DoubleArray(16)
    private val labels: LabelSink get() = rs!!.labelSink

    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            renderFrame(frameTimeNanos)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): AndroidView =
        inflater.inflate(R.layout.fragment_rviz, container, false)

    override fun onViewCreated(root: AndroidView, savedInstanceState: Bundle?) {
        surfaceView = root.findViewById(R.id.filamentSurfaceView)
        labelsContainer = root.findViewById(R.id.labelsContainer)
        fixedFrameButton = root.findViewById(R.id.fixedFrameButton)
        displayStrip = root.findViewById(R.id.displayStrip)

        viewTypeButton = root.findViewById(R.id.viewTypeButton)
        viewTypeButton.setOnClickListener { pickView() }
        setting(KEY_VIEW)?.let { json ->
            try {
                val o = org.json.JSONObject(json)
                viewCtl.loadFrom(o.keys().asSequence().associateWith { o.getString(it) })
            } catch (_: Exception) {}
        }
        viewTypeButton.text = viewCtl.type.label
        goalButton = root.findViewById(R.id.goalToolButton)
        poseButton = root.findViewById(R.id.poseToolButton)
        goalButton.setOnClickListener { setTool(if (tool == Tool.GOAL) Tool.NONE else Tool.GOAL) }
        poseButton.setOnClickListener { setTool(if (tool == Tool.POSE_ESTIMATE) Tool.NONE else Tool.POSE_ESTIMATE) }
        moreToolsButton = root.findViewById(R.id.moreToolsButton)
        moreToolsButton.setOnClickListener { v ->
            if (tool == Tool.PUBLISH_POINT || tool == Tool.MEASURE || tool == Tool.INTERACT || tool == Tool.SELECT || tool == Tool.WAYPOINTS || tool == Tool.MOVEIT_GOAL) { setTool(Tool.NONE); return@setOnClickListener }
            android.widget.PopupMenu(requireContext(), v).apply {
                menu.add(0, 1, 0, getString(R.string.rviz_tool_point_menu, pointTopic))
                menu.add(0, 2, 1, R.string.rviz_tool_measure)
                menu.add(0, 3, 2, R.string.rviz_tool_interact_menu)
                menu.add(0, 4, 3, R.string.rviz_tool_select_menu)
                menu.add(0, 5, 4, R.string.rviz_tool_waypoints_menu)
                menu.add(0, 6, 5, R.string.rviz_tool_moveit_menu)
                setOnMenuItemClickListener {
                    setTool(when (it.itemId) { 1 -> Tool.PUBLISH_POINT; 2 -> Tool.MEASURE; 4 -> Tool.SELECT; 5 -> Tool.WAYPOINTS; 6 -> Tool.MOVEIT_GOAL; else -> Tool.INTERACT })
                    true
                }
            }.show()
        }
        fixedFrameButton.setOnClickListener { pickFixedFrame() }
        // Nav2 (shared with the PC): the waypoint route and the navigation state, below the top bar.
        (root.findViewById<AndroidView>(R.id.rvizTopBar).parent as android.widget.FrameLayout).addView(
            androidx.compose.ui.platform.ComposeView(requireContext()).apply {
                setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    com.juancams.rviz2mini.ui.theme.VizTheme(dark = true) {
                        // (The time bar of a bag or of the replay is added by the activity, at the bottom.)
                        androidx.compose.foundation.layout.Column {
                            val m = navMission.value
                            val v = navVersion.intValue
                            if (m != null && com.juancams.rviz2mini.rviz.ui.nav2BarVisible(m, navTool.value))
                                com.juancams.rviz2mini.rviz.ui.Nav2Bar(m, v, androidx.compose.ui.Modifier.padding(horizontal = 6.dp)) { navVersion.intValue++ }
                        }
                    }
                }
            },
            android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP).apply { topMargin = dp(48f) })
        root.findViewById<ImageButton>(R.id.resetViewButton).setOnClickListener { resetView() }
        // Go back: the latest data received (no bag), with the time bar.
        root.findViewById<ImageButton>(R.id.rewindButton).setOnClickListener {
            rs?.rewindNow(com.juancams.rviz2mini.demo.DemoMode.isActive(requireContext())) { msg ->
                if (context != null) android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        root.findViewById<ImageButton>(R.id.rvizMenuButton).setOnClickListener { showMenu(it) }

        // The 3D materials are compiled in the background (the first time takes several seconds):
        // until they are ready, a notice is shown instead of blocking the app.
        val loading = root.findViewById<TextView>(R.id.rvizLoading)
        val token = Any()
        setupToken = token
        setControlsEnabled(false)
        if (!FilamentHub.isPrepared) loading.visibility = AndroidView.VISIBLE
        FilamentHub.whenPrepared {
            if (setupToken !== token) return@whenPrepared // the view no longer exists
            if (!FilamentHub.isPrepared) {
                loading.text = getString(R.string.rviz_setup_failed, FilamentHub.prepareError.toString())
                return@whenPrepared
            }
            loading.visibility = AndroidView.GONE
            finishSetup()
        }
    }

    private fun finishSetup() {
        setupFilament()
        setupGestures()
        setControlsEnabled(true)
        applyActivation()
    }

    /** Buttons that create displays or use the scene: without a viewer they do nothing yet. */
    private fun setControlsEnabled(enabled: Boolean) {
        val root = view ?: return
        for (id in intArrayOf(R.id.rvizMenuButton, R.id.goalToolButton, R.id.poseToolButton, R.id.fixedFrameButton, R.id.viewTypeButton, R.id.resetViewButton)) {
            root.findViewById<AndroidView>(id)?.isEnabled = enabled
        }
    }

    /** The viewer is already set up (the materials are prepared in the background). For tests. */
    val isReadyForTest: Boolean get() = filamentReady

    override fun onResume() {
        super.onResume()
        resumed = true
        applyActivation()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        // Hidden tab or app in the background: nothing is rendered and the displays unsubscribe.
        resumed = false
        choreographer.removeFrameCallback(frameCallback)
        applyActivation()
        saveView()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        setupToken = null
        choreographer.removeFrameCallback(frameCallback)
        destroyFilament()
    }

    // ---------------------------------------------------------------- displays

    private fun applyActivation() {
        rs?.visible = resumed
        refreshStrip()
    }

    private fun addDisplay(c: DisplayConfig) { rs?.addDisplay(c); refreshStrip() }
    private fun updateDisplay(c: DisplayConfig) { rs?.updateDisplay(c); refreshStrip() }
    private fun removeDisplay(id: String) { rs?.removeDisplay(id); refreshStrip() }

    private fun onDisplayStatusChanged() {
        // May arrive many times per frame: batched into a single refresh.
        if (stripRefreshPending || view == null) return
        stripRefreshPending = true
        displayStrip.post {
            stripRefreshPending = false
            if (view != null) refreshStrip()
        }
    }

    private fun refreshStrip() {
        val ctx = context ?: return
        val inflater = LayoutInflater.from(ctx)
        // Reuse the existing chips when possible (avoids flicker).
        val needed = configs.size + 1
        while (displayStrip.childCount > needed) displayStrip.removeViewAt(displayStrip.childCount - 1)
        for ((i, c) in configs.withIndex()) {
            var chip = displayStrip.getChildAt(i) as? Chip
            if (chip == null || chip.tag == ADD_TAG) {
                chip = inflater.inflate(R.layout.item_display_chip, displayStrip, false) as Chip
                displayStrip.addView(chip, i)
            }
            bindChip(chip, c)
        }
        val last = displayStrip.getChildAt(configs.size)
        if (last == null || last.tag != ADD_TAG) {
            val add = inflater.inflate(R.layout.item_add_chip, displayStrip, false) as Chip
            add.tag = ADD_TAG
            add.setOnClickListener {
                showComposeDialog { dismiss -> com.juancams.rviz2mini.rviz.ui.AddDisplayDialog(onDismiss = dismiss, existing = configs.toList()) { addDisplay(it); dismiss() } }
            }
            displayStrip.addView(add)
        }
    }

    private fun bindChip(chip: Chip, c: DisplayConfig) {
        chip.tag = c.id
        chip.text = DisplayList.shortName(c.name)
        chip.setOnCheckedChangeListener(null)
        chip.isChecked = c.enabled
        val level = displays[c.id]?.status?.level ?: StatusLevel.DISABLED
        val dot = (chip.chipIcon as? GradientDrawable) ?: GradientDrawable().apply { shape = GradientDrawable.OVAL }
        dot.setColor(statusColor(if (c.enabled) level else StatusLevel.DISABLED))
        dot.setSize(dp(10f), dp(10f))
        chip.chipIcon = dot
        chip.contentDescription = chip.context.getString(R.string.rviz_chip_description, c.name, displays[c.id]?.status?.message.orEmpty())
        chip.setOnCheckedChangeListener { _, checked ->
            configs.firstOrNull { it.id == c.id }?.let { updateDisplay(it.copy(enabled = checked)) }
        }
        chip.setOnLongClickListener {
            val current = configs.firstOrNull { it.id == c.id } ?: return@setOnLongClickListener true
            val status = displays[c.id]?.status ?: return@setOnLongClickListener true
            val frames = displays[c.id]?.knownFrames() ?: tfTree.frameIds()
            showComposeDialog { dismiss ->
                val topics = RosConnection.graph()?.topics(includeHidden = true).orEmpty()
                    .filter { t -> current.type in com.juancams.rviz2mini.rviz.display.DisplayFactory.displaysFor(t.name, t.types) }.map { it.name }.sorted()
                com.juancams.rviz2mini.rviz.ui.DisplayEditorDialog(current, status, frames, topics, displays[c.id]?.fix, onDismiss = dismiss,
                    onSave = { updateDisplay(it); dismiss() }, onDelete = { removeDisplay(c.id); dismiss() })
            }
            true
        }
    }

    // ---------------------------------------------------------------- fixed frame, view and menu

    private fun resolveFixedFrame() {
        rs?.resolveFixedFrame()
        val label = fixedFrame.ifEmpty { getString(R.string.rviz_no_tf) }
        if (fixedFrameButton.text != label) fixedFrameButton.text = label
    }

    private fun pickFixedFrame() {
        FixedFrameDialog.show(requireContext(), tfTree.frameIds(), rs?.preferredFixedFrame, tfTree.chooseDefaultFixedFrame() ?: "—") { picked ->
            rs?.setPreferredFixedFrame(picked)
            resolveFixedFrame()
        }
    }

    private fun resetView() {
        viewCtl.reset()
        saveView()
    }

    private fun saveView() = saveSetting(KEY_VIEW, org.json.JSONObject(viewCtl.toMap()).toString())
    private fun invalidateProjection() { rs?.invalidateProjection() }

    private fun pickView() {
        val types = ViewType.entries
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rviz_view_title)
            .setSingleChoiceItems(types.map { getString(R.string.rviz_view_item, it.label, it.description) }.toTypedArray(), types.indexOf(viewCtl.type)) { d, i ->
                d.dismiss()
                val t = types[i]
                if (t == ViewType.FOLLOW) {
                    val frames = tfTree.frameIds()
                    if (frames.isEmpty()) { setView(t); return@setSingleChoiceItems }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.rviz_target_frame)
                        .setSingleChoiceItems(frames.toTypedArray(), frames.indexOf(viewCtl.followFrame)) { d2, j ->
                            viewCtl.followFrame = frames[j]; d2.dismiss(); setView(t)
                        }.show()
                } else setView(t)
            }
            .setNegativeButton(R.string.rviz_action_cancel, null)
            .show().also { OpenDialogs.last = it }
    }

    private fun setView(t: ViewType) {
        viewCtl.type = t
        viewTypeButton.text = t.label
        invalidateProjection()
        saveView()
    }

    private fun applyBackground() { rs?.applyBackground() }

    /** Saved views (like RViz's Views panel): apply one, delete it or save the current one. */
    private fun savedViewsDialog() {
        val scene = rs ?: return
        val views = scene.savedViews()
        val ctx = requireContext()
        val items = views.map { it.first }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle(R.string.rviz_views)
            .apply { if (items.isEmpty()) setMessage(R.string.rviz_views_none) else setItems(items) { _, i -> scene.applySavedView(items[i]) } }
            .setPositiveButton(R.string.rviz_views_save) { _, _ ->
                val input = android.widget.EditText(ctx).apply { setText(com.juancams.rviz2mini.i18n.tr("rviz_views_default_name", views.size + 1)); setSingleLine() }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle(R.string.rviz_views_save).setView(input)
                    .setPositiveButton(R.string.rviz_action_save) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { scene.saveCurrentView(it) } }
                    .setNegativeButton(R.string.rviz_action_cancel, null).show()
            }
            .setNeutralButton(R.string.rviz_views_delete) { _, _ ->
                if (items.isEmpty()) return@setNeutralButton
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle(R.string.rviz_views_delete)
                    .setItems(items) { _, i -> scene.deleteSavedView(items[i]) }.setNegativeButton(R.string.rviz_action_cancel, null).show()
            }
            .setNegativeButton(R.string.rviz_action_cancel, null)
            .show().also { OpenDialogs.last = it }
    }

    private fun pickBackground() {
        val options = listOf(R.string.rviz_bg_dark to DEFAULT_BACKGROUND, R.string.rviz_bg_rviz_gray to 0xFF303030.toInt(), R.string.rviz_bg_black to 0xFF000000.toInt(),
            R.string.rviz_bg_light_gray to 0xFFB0B0B0.toInt(), R.string.rviz_bg_white to 0xFFFFFFFF.toInt(), R.string.rviz_bg_midnight_blue to 0xFF0D1B2A.toInt())
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rviz_background_title)
            .setItems(options.map { getString(it.first) }.toTypedArray()) { _, i ->
                saveSetting(KEY_BACKGROUND, com.juancams.rviz2mini.rviz.display.Colors.toHex(options[i].second))
                applyBackground()
            }.show().also { OpenDialogs.last = it }
    }

    private fun showMenu(anchor: AndroidView) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.rviz_reset_view)
            menu.add(0, 3, 1, R.string.rviz_menu_view_type)
            menu.add(0, 8, 1, R.string.rviz_views)
            menu.add(0, 4, 2, R.string.rviz_menu_background)
            menu.add(0, 5, 3, R.string.rviz_menu_tool_topics)
            menu.add(0, 6, 4, R.string.rviz_menu_import)
            menu.add(0, 7, 4, R.string.rviz_menu_export)
            menu.add(0, 9, 4, R.string.rviz_rewind_keep).apply { isCheckable = true; isChecked = rs?.rewindEnabled == true }
            menu.add(0, 2, 5, R.string.rviz_menu_restart_ros)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> resetView()
                    2 -> RosConnection.restart()
                    3 -> pickView()
                    8 -> savedViewsDialog()
                    9 -> rs?.let { s ->
                        s.setRewind(!s.rewindEnabled)
                        android.widget.Toast.makeText(requireContext(), getString(if (s.rewindEnabled) R.string.rewind_kept else R.string.rewind_off, RvizScene.REWIND_WINDOW_S),
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                    4 -> pickBackground()
                    5 -> editToolTopics()
                    6 -> pickRvizFile()
                    7 -> try { saveRvizFile.launch("vizmini2.rviz") } catch (e: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(requireContext(), R.string.rviz_no_file_picker, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                true
            }
        }.show()
    }

    // ---------------------------------------------------------------- render

    private fun renderFrame(frameTimeNanos: Long) {
        if (!filamentReady) return
        val r = rs ?: return
        resolveFixedFrame()
        val sc = swapChain?.takeIf { uiHelper.isReadyToRender }
        r.renderFrame(sc, frameTimeNanos, extra = { drawMeasureLabel() })
    }

    // ---------------------------------------------------------------- Filament

    private fun setupFilament() {
        rs = RvizScene(tabId, resources.displayMetrics.density, overlay = labelsContainer, androidContext = context, viewCtl = viewCtl,
            onStatusChanged = { onDisplayStatusChanged() })
        interact = RvizInteract(rs!!, resources.displayMetrics.density) { d, im, c, entries -> showImMenu(d, im, c, entries) }
        tools = RvizTools(rs!!, resources.displayMetrics.density) { msg ->
            if (context != null) android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
        }
        tools?.let { t -> t.onNavChanged = { navVersion.intValue++ }; navMission.value = t.mission }

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: android.view.Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
            }
            override fun onDetachedFromSurface() {
                swapChain?.let {
                    engine.destroySwapChain(it)
                    // Must wait for Filament to release the surface before Android destroys it.
                    engine.flushAndWait()
                    swapChain = null
                }
            }
            override fun onResized(width: Int, height: Int) {
                if (width == 0 || height == 0) return
                rs?.resize(width, height)
            }
        }
        // UiHelper listens to the SurfaceHolder: hiding the tab destroys the surface
        // (onDetachedFromSurface) and showing it again recreates it (onNativeWindowChanged).
        uiHelper.attachTo(surfaceView)
        filamentReady = true
    }

    private fun destroyFilament() {
        if (!filamentReady) return
        filamentReady = false
        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        navMission.value = null
        panels = null
        tools?.destroy(); tools = null
        interact = null
        rs?.destroy()
        rs = null
    }

    // ---------------------------------------------------------------- camera and gestures

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                viewCtl.pinch(detector.scaleFactor)
                return true
            }
        })
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }
        })

        // 1 finger: orbit (or pan in top-down view, or look in FPS). 2 fingers: zoom + pan
        // (+ rotate in top-down view). Double tap: reset the view.
        surfaceView.setOnTouchListener { _, event ->
            // Camera display panels: drag to move them, pinch or drag their corner to resize them.
            if (panelTouch(event)) return@setOnTouchListener true
            // Interact: if the finger lands on a control it moves it; otherwise the view moves as usual.
            if (tool == Tool.INTERACT) {
                if (interactTouch(event)) return@setOnTouchListener true
            } else if (tool == Tool.MOVEIT_GOAL) {
                // MoveIt: if the finger lands on an arrow or ring of the gripper, it drags it; otherwise the view moves.
                if (moveItTouch(event)) return@setOnTouchListener true
            } else if (tool != Tool.NONE) {
                handleToolTouch(event)
                return@setOnTouchListener true
            }
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    val skip = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
                    touchCentroid(event, skip)
                    lastTouchX = screenTmp[0]
                    lastTouchY = screenTmp[1]
                    lastTwistAngle = null
                }
                MotionEvent.ACTION_MOVE -> {
                    touchCentroid(event, -1)
                    val dx = screenTmp[0] - lastTouchX
                    val dy = screenTmp[1] - lastTouchY
                    if (event.pointerCount == 1) viewCtl.drag(dx, dy, surfaceView.height)
                    else {
                        viewCtl.pan(dx, dy, surfaceView.height)
                        if (viewCtl.type == ViewType.TOP_DOWN) {
                            val a = Math.atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble())
                            lastTwistAngle?.let { prev ->
                                var d = a - prev
                                if (d > Math.PI) d -= 2 * Math.PI
                                if (d < -Math.PI) d += 2 * Math.PI
                                viewCtl.twist(d)
                            }
                            lastTwistAngle = a
                        }
                    }
                    lastTouchX = screenTmp[0]
                    lastTouchY = screenTmp[1]
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveView()
            }
            true
        }
    }

    // ---------------------------------------------------------------- Nav2 tools

    private fun setTool(t: Tool) {
        interact?.up(cancel = true)
        tool = t
        tools?.setTool(shared(t))
        for ((b, bt) in listOf(goalButton to Tool.GOAL, poseButton to Tool.POSE_ESTIMATE)) {
            b.setTextColor(if (t == bt) bt.color else android.graphics.Color.WHITE)
        }
        androidx.core.widget.ImageViewCompat.setImageTintList(moreToolsButton, android.content.res.ColorStateList.valueOf(
            if (t == Tool.PUBLISH_POINT || t == Tool.MEASURE || t == Tool.INTERACT || t == Tool.SELECT || t == Tool.WAYPOINTS || t == Tool.MOVEIT_GOAL) t.color else android.graphics.Color.WHITE))
        navTool.value = shared(t)
        val hint = when (t) {
            Tool.NONE -> null
            Tool.GOAL -> getString(R.string.rviz_tool_hint, goalTopic)
            Tool.POSE_ESTIMATE -> getString(R.string.rviz_tool_hint, poseTopic)
            Tool.PUBLISH_POINT -> getString(R.string.rviz_tool_point_hint, pointTopic)
            Tool.MEASURE -> getString(R.string.rviz_tool_measure_hint)
            Tool.INTERACT -> getString(R.string.rviz_tool_interact_hint)
            Tool.SELECT -> getString(R.string.rviz_tool_select_hint)
            Tool.WAYPOINTS -> getString(R.string.rviz_tool_waypoints_hint)
            Tool.MOVEIT_GOAL -> getString(if (com.juancams.rviz2mini.moveit.MoveItGoal.client == null) R.string.rviz_tool_moveit_none else R.string.rviz_tool_moveit_hint)
        }
        if (hint != null) android.widget.Toast.makeText(requireContext(), hint, android.widget.Toast.LENGTH_SHORT).show()
    }

    private var panels: com.juancams.rviz2mini.rviz.PanelGesture? = null
    private var panelDragging = false
    private var panelPinchStart = 0f

    private fun panelTouch(event: MotionEvent): Boolean {
        val scene = rs ?: return false
        val g = panels ?: com.juancams.rviz2mini.rviz.PanelGesture(scene, resources.displayMetrics.density).also { panels = it }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { panelDragging = g.down(event.x, event.y); panelPinchStart = 0f }
            MotionEvent.ACTION_POINTER_DOWN -> if (panelDragging && event.pointerCount == 2)
                panelPinchStart = Math.hypot((event.getX(0) - event.getX(1)).toDouble(), (event.getY(0) - event.getY(1)).toDouble()).toFloat()
            MotionEvent.ACTION_MOVE -> if (panelDragging) {
                if (event.pointerCount >= 2 && panelPinchStart > 0f)
                    g.pinch(Math.hypot((event.getX(0) - event.getX(1)).toDouble(), (event.getY(0) - event.getY(1)).toDouble()).toFloat() / panelPinchStart)
                else if (panelPinchStart == 0f) g.move(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> if (panelDragging) { g.up(); panelDragging = false; return true }
            MotionEvent.ACTION_CANCEL -> if (panelDragging) { g.cancel(); panelDragging = false; return true }
        }
        return panelDragging
    }

    private var moveItDragging = false

    private fun moveItTouch(event: MotionEvent): Boolean {
        val t = tools ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { moveItDragging = t.consumesTouchAt(event.x, event.y); if (moveItDragging) t.down(event.x, event.y) }
            MotionEvent.ACTION_MOVE -> if (moveItDragging) t.move(event.x, event.y)
            MotionEvent.ACTION_UP -> if (moveItDragging) { t.up(event.x, event.y); moveItDragging = false; return true }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> if (moveItDragging) { t.cancel(); moveItDragging = false; return true }
        }
        return moveItDragging
    }

    private fun handleToolTouch(event: MotionEvent) {
        val t = tools ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> t.down(event.x, event.y)
            MotionEvent.ACTION_MOVE -> t.move(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                t.up(event.x, event.y)
                // Goal, Pose and Point remove themselves after publishing.
                if (t.tool == RvizTool.NONE && tool != Tool.NONE) setTool(Tool.NONE)
            }
            MotionEvent.ACTION_CANCEL -> { t.cancel(); if (t.tool == RvizTool.NONE && tool != Tool.NONE) setTool(Tool.NONE) }
        }
    }

    private fun shared(t: Tool) = when (t) {
        Tool.NONE -> RvizTool.NONE; Tool.GOAL -> RvizTool.GOAL; Tool.POSE_ESTIMATE -> RvizTool.POSE_ESTIMATE
        Tool.PUBLISH_POINT -> RvizTool.PUBLISH_POINT; Tool.MEASURE -> RvizTool.MEASURE; Tool.INTERACT -> RvizTool.INTERACT; Tool.SELECT -> RvizTool.SELECT
        Tool.WAYPOINTS -> RvizTool.WAYPOINTS
        Tool.MOVEIT_GOAL -> RvizTool.MOVEIT_GOAL
    }

    // ---------------------------------------------------------------- interactive markers

    private fun frameContext() = FrameContext(tfTree, fixedFrame, SystemClock.uptimeMillis(), labels)

    private fun projector(): (DoubleArray) -> FloatArray? {
        val w = surfaceView.width.toFloat(); val h = surfaceView.height.toFloat()
        val vp = viewCtl.viewProjection(w.toDouble() / h, DoubleArray(16))
        return { p -> FloatArray(2).takeIf { viewCtl.project(vp, p[0], p[1], p[2], w, h, it) } }
    }

    /** Returns true if the gesture belongs to a control (otherwise the camera handles it). */
    private fun interactTouch(event: MotionEvent): Boolean {
        val i = interact ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> i.down(event.x, event.y)
            MotionEvent.ACTION_MOVE -> i.move(event.x, event.y)
            MotionEvent.ACTION_UP -> i.up(cancel = false)
            MotionEvent.ACTION_CANCEL -> i.up(cancel = true)
            else -> i.dragging
        }
    }

    /** A control's menu (a tap without dragging on a MENU control). */
    private fun showImMenu(display: com.juancams.rviz2mini.rviz.display.InteractiveMarkersDisplay, im: com.juancams.rviz2mini.rviz.geom.IMarker,
                           c: com.juancams.rviz2mini.rviz.geom.IMControl, entries: List<Pair<String, Int>>) {
        if (context == null) return
        android.widget.PopupMenu(requireContext(), surfaceView).apply {
            for ((i, e) in entries.withIndex()) menu.add(0, i, i, e.first)
            setOnMenuItemClickListener { interact?.menuSelected(display, im, c, entries[it.itemId].second); true }
        }.show()
    }
    private val lastImEvent: String? get() = interact?.lastImEvent

    private fun drawMeasureLabel() { tools?.drawLabels() }

    private fun editToolTopics() {
        val ctx = requireContext()
        val box = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        val goal = android.widget.EditText(ctx).apply { setText(goalTopic); hint = "/goal_pose" }
        val pose = android.widget.EditText(ctx).apply { setText(poseTopic); hint = "/initialpose" }
        box.addView(android.widget.TextView(ctx).apply { setText(R.string.rviz_tool_goal_label) }); box.addView(goal)
        box.addView(android.widget.TextView(ctx).apply { setText(R.string.rviz_tool_pose_label) }); box.addView(pose)
        val point = android.widget.EditText(ctx).apply { setText(pointTopic); hint = "/clicked_point" }
        box.addView(android.widget.TextView(ctx).apply { setText(R.string.rviz_tool_point_label) }); box.addView(point)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle(R.string.rviz_tool_topics_title).setView(box)
            .setNegativeButton(R.string.rviz_action_cancel, null)
            .setPositiveButton(R.string.rviz_action_save) { _, _ ->
                val g = com.juancams.rviz2mini.camera.ImageTopics.normalize(goal.text.toString())
                val p = com.juancams.rviz2mini.camera.ImageTopics.normalize(pose.text.toString())
                val c = com.juancams.rviz2mini.camera.ImageTopics.normalize(point.text.toString())
                fun ok(t: String) = t.takeIf { com.juancams.rviz2mini.camera.ImageTopics.isValidName(it) }
                tools?.setTopics(ok(g), ok(p), ok(c))
            }.show().also { OpenDialogs.last = it }
    }

    // ---------------------------------------------------------------- import .rviz

    /** Export the view as an RViz 2 .rviz file (the same exporter as the PC). */
    private val saveRvizFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@registerForActivityResult
        val scene = rs ?: return@registerForActivityResult
        val t = tools ?: return@registerForActivityResult
        val text = scene.exportRviz(t.goalTopic, t.poseTopic, t.pointTopic)
        val ok = runCatching { requireContext().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.isSuccess
        android.widget.Toast.makeText(requireContext(), if (ok) R.string.rviz_export_done else R.string.rviz_no_file_picker, android.widget.Toast.LENGTH_SHORT).show()
    }

    private val openRvizFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readRvizFile(uri)
    }

    private fun pickRvizFile() {
        // .rviz files have no MIME type of their own: each file manager reports them differently.
        try {
            openRvizFile.launch(arrayOf("*/*"))
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(requireContext(), R.string.rviz_no_file_picker, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun readRvizFile(uri: android.net.Uri) {
        val resolver = requireContext().contentResolver
        val height = surfaceView.height.takeIf { it > 0 } ?: 1000
        // Resolved here: the reading thread must not depend on the fragment still being attached.
        val openError = getString(R.string.rviz_import_open_error)
        // May come from a network provider (Drive...): off the UI thread.
        Thread({
            val result = try {
                val text = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error(openError)
                Result.success(com.juancams.rviz2mini.rviz.rvizfile.RvizImporter.import(text, height))
            } catch (t: Throwable) {
                Result.failure(t)
            }
            activity?.runOnUiThread {
                if (view == null) return@runOnUiThread
                result.fold({ confirmImport(it) }, {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.rviz_import_failed).setMessage(it.message ?: it.javaClass.simpleName)
                        .setPositiveButton(R.string.rviz_action_ok, null).show().also { d -> OpenDialogs.last = d }
                })
            }
        }, "rviz-import").start()
    }

    private fun confirmImport(r: com.juancams.rviz2mini.rviz.rvizfile.RvizImport) {
        val msg = StringBuilder()
        msg.append(getString(R.string.rviz_import_displays, r.displays.size, r.displays.joinToString { it.name }.ifEmpty { getString(R.string.rviz_import_none) }))
        r.fixedFrame?.let { msg.append('\n').append(getString(R.string.rviz_import_fixed_frame, it)) }
        r.view?.get("type")?.let { t -> msg.append('\n').append(getString(R.string.rviz_import_view, ViewType.fromName(t).label)) }
        if (r.goalTopic != null || r.poseTopic != null || r.pointTopic != null)
            msg.append('\n').append(getString(R.string.rviz_import_tools, listOfNotNull(r.goalTopic, r.poseTopic, r.pointTopic).joinToString()))
        if (r.skipped.isNotEmpty()) msg.append("\n\n").append(getString(R.string.rviz_import_skipped, r.skipped.joinToString()))
        msg.append("\n\n").append(getString(R.string.rviz_import_replace_warning))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rviz_import_title).setMessage(msg)
            .setNegativeButton(R.string.rviz_action_cancel, null)
            .setPositiveButton(R.string.rviz_action_import) { _, _ -> applyImport(r) }
            .show().also { OpenDialogs.last = it }
    }

    private fun applyImport(r: com.juancams.rviz2mini.rviz.rvizfile.RvizImport) {
        if (!filamentReady) return
        rs?.applyImport(r)
        tools?.setTopics(r.goalTopic, r.poseTopic, r.pointTopic)
        viewTypeButton.text = viewCtl.type.label
        refreshStrip()
    }

    internal fun importRvizForTest(text: String) =
        applyImport(com.juancams.rviz2mini.rviz.rvizfile.RvizImporter.import(text, surfaceView.height.takeIf { it > 0 } ?: 1000))

    private val lastToolPose: DoubleArray? get() = tools?.lastToolPose
    private val lastToolPoint: DoubleArray? get() = tools?.lastToolPoint

    /** "2.35 m (dx 1.20, dy -2.02)" (like RViz's Measure panel). */
    private fun measureText(a: DoubleArray, b: DoubleArray): String =
        com.juancams.rviz2mini.rviz.geom.Measure.text(a, b)

    private fun touchCentroid(event: MotionEvent, skipIndex: Int) {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sx += event.getX(i); sy += event.getY(i); n++
        }
        if (n == 0) n = 1
        screenTmp[0] = sx / n
        screenTmp[1] = sy / n
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    // For instrumented tests.
    internal fun displayConfigs(): List<DisplayConfig> = configs.toList()
    internal fun displayStatus(id: String) = displays[id]?.status
    internal fun addDisplayForTest(c: DisplayConfig) = addDisplay(c)
    internal fun updateDisplayForTest(c: DisplayConfig) = updateDisplay(c)
    internal fun removeDisplayForTest(id: String) = removeDisplay(id)
    internal fun currentFixedFrame() = fixedFrame
    internal fun preferredFixedFrame() = rs?.preferredFixedFrame
    internal fun setCameraForTest(radius: Double, azimuth: Double, elevation: Double, tx: Double, ty: Double) {
        viewCtl.type = ViewType.ORBIT; viewTypeButton.text = viewCtl.type.label
        viewCtl.distance = radius; viewCtl.yaw = azimuth; viewCtl.pitch = elevation
        viewCtl.focal[0] = tx; viewCtl.focal[1] = ty; viewCtl.focal[2] = 0.0
        invalidateProjection()
    }
    internal fun viewForTest() = viewCtl
    internal fun setViewForTest(t: ViewType) = setView(t)
    internal fun setToolForTest(t: Tool) = setTool(t)
    internal fun navMissionForTest() = tools?.mission
    internal fun sceneForTest() = rs
    internal fun toolsForTest() = tools
    internal fun labelCountForTest() = rs?.labelCount ?: 0

    /**
     * A cloud or laser point that Select can pick, in 3D view coordinates (for the catalog videos,
     * with [touchToolForTest]): scans the view in a grid from [fromFy] of its height (up to [maxFx] of its width, so the
     * label fits) and returns the first hit.
     * Picks nothing (the "no point" notice does not appear).
     */
    internal fun selectablePointForTest(fromFy: Float = 0.25f, maxFx: Float = 0.85f): Pair<Float, Float>? {
        val project = projector()
        val step = 12 * resources.displayMetrics.density
        var y = surfaceView.height * fromFy
        while (y < surfaceView.height * 0.85f) {
            var x = surfaceView.width * 0.15f
            while (x < surfaceView.width * maxFx) {
                if (displays.values.any { (it as? com.juancams.rviz2mini.rviz.display.PointPicker)?.pickPoint(x, y, step / 2, project) != null }) return x to y
                x += step
            }
            y += step
        }
        return null
    }
    internal fun surfaceForTest() = surfaceView
    internal fun lastToolPoseForTest() = lastToolPose
    internal fun lastToolPointForTest() = lastToolPoint
    internal fun lastImEventForTest() = lastImEvent
    internal fun interactTouchForTest(action: Int, x: Float, y: Float): Boolean {
        val e = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, x, y, 0)
        val r = interactTouch(e)
        e.recycle()
        return r
    }
    internal fun projectForTest(p: DoubleArray): FloatArray? = projector()(p)
    internal fun selectForTest(x: Float, y: Float): String? { val t = tools ?: return null; t.selectPoint(x, y); return t.selected?.let { t.selectText(it) } }
    internal fun displayForTest(id: String) = displays[id]
    internal fun measureForTest(): String? = tools?.measureText()
    internal fun touchToolForTest(action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(0, 0, action, x, y, 0)
        handleToolTouch(e)
        e.recycle()
    }
    internal fun setBackgroundForTest(hex: String) { saveSetting(KEY_BACKGROUND, hex); applyBackground() }

    companion object {
        const val KEY_FIXED_FRAME = "fixedFrame"
        const val KEY_DISPLAYS = "displays"
        const val ADD_TAG = "__add__"
        const val KEY_VIEW = "view"
        const val KEY_GOAL_TOPIC = "goalTopic"
        const val KEY_POINT_TOPIC = "pointTopic"
        const val KEY_POSE_TOPIC = "poseTopic"
        const val KEY_BACKGROUND = "background"
        val DEFAULT_BACKGROUND = 0xFF141419.toInt()
    }
}
