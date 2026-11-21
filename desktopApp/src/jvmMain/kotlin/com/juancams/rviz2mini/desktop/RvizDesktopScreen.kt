// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import com.juancams.rviz2mini.ui.common.onContextClick

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.FilamentHub
import com.juancams.rviz2mini.rviz.RvizScene
import com.juancams.rviz2mini.rviz.RvizTool
import com.juancams.rviz2mini.rviz.RvizTools
import com.juancams.rviz2mini.rviz.RvizInteract
import com.juancams.rviz2mini.rviz.ViewType
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayFactory
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.PropertySpec
import com.juancams.rviz2mini.rviz.display.StatusLevel
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.ui.common.rememberGraph
import com.juancams.rviz2mini.ui.theme.StatusColors
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * RViz on the PC: the same scene as on Android ([RvizScene], rviz3d displays), rendered by Filament (OpenGL)
 * off screen and copied to a Compose image every frame. Mouse: drag rotates, right or middle button
 * pans, the wheel zooms in. On the right, the displays.
 */
@Composable
fun RvizDesktopScreen(spec: TabSpec, modifier: Modifier = Modifier) {
    var ready by remember { mutableStateOf(FilamentHub.isPrepared) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { FilamentHub.whenPrepared { ready = FilamentHub.isPrepared; error = FilamentHub.prepareError } }
    when {
        error != null -> Text(tr("rviz_setup_failed", error), modifier = modifier.padding(24.dp))
        !ready -> Text(tr("rviz_loading"), modifier = modifier.padding(24.dp))
        else -> RvizDesktopView(spec.id, modifier, showPlayback = !spec.isWidget)
    }
}

/** An open interactive marker menu. */
private class ImMenu(
    val display: com.juancams.rviz2mini.rviz.display.InteractiveMarkersDisplay, val marker: com.juancams.rviz2mini.rviz.geom.IMarker,
    val control: com.juancams.rviz2mini.rviz.geom.IMControl, val entries: List<Pair<String, Int>>,
)

/**
 * Off-screen render: the view renders into an RGBA16F texture (linear values, no post-processing), read back as
 * floating point and converted to sRGB here, just like Android's linear color grading does at the end. (With post-processing and an
 * off-screen GLX swap chain, Filament leaves nothing in the image.) One frame is read at a time.
 */
private class Offscreen(private val scene: RvizScene, private val extra: () -> Unit = {}) {
    private val engine = scene.engine
    private var swapChain: SwapChain? = null
    private var target: com.google.android.filament.RenderTarget? = null
    private var color: Texture? = null
    private var depth: Texture? = null
    private var w = 0; private var h = 0
    private var reading = false
    private val onMain = Executor { r -> Platform.main.post { r.run() } }

    init { scene.view.isPostProcessingEnabled = false }

    private fun resize(width: Int, height: Int) {
        destroyTarget()
        color = Texture.Builder().width(width).height(height).levels(1).usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE or Texture.Usage.BLIT_SRC)
            .format(Texture.InternalFormat.RGBA16F).build(engine)
        depth = Texture.Builder().width(width).height(height).levels(1).usage(Texture.Usage.DEPTH_ATTACHMENT)
            .format(Texture.InternalFormat.DEPTH24).build(engine)
        target = com.google.android.filament.RenderTarget.Builder()
            .texture(com.google.android.filament.RenderTarget.AttachmentPoint.COLOR, color)
            .texture(com.google.android.filament.RenderTarget.AttachmentPoint.DEPTH, depth).build(engine)
        scene.view.renderTarget = target
        if (swapChain == null) swapChain = engine.createSwapChain(16, 16, SwapChainFlags.CONFIG_DEFAULT)
        w = width; h = height
        scene.resize(width, height)
    }

    fun frame(timeNanos: Long, width: Int, height: Int, done: (ImageBitmap) -> Unit) {
        if (width <= 0 || height <= 0) return
        if (width != w || height != h || target == null) resize(width, height)
        val fw = w; val fh = h; val rt = target!!
        scene.renderFrame(swapChain, timeNanos, extra = { extra() }, onRendered = { r: Renderer ->
            if (reading) return@renderFrame
            reading = true
            val buf = ByteBuffer.allocateDirect(fw * fh * 16).order(java.nio.ByteOrder.nativeOrder())
            r.readPixels(rt, 0, 0, fw, fh, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.FLOAT, 1, 0, 0, 0, onMain) {
                reading = false
                done(toImage(buf.asFloatBuffer(), fw, fh))
            })
        })
    }

    /** Linear (floating point) -> 8-bit sRGB. */
    private fun toImage(f: java.nio.FloatBuffer, fw: Int, fh: Int): ImageBitmap {
        val bytes = ByteArray(fw * fh * 4)
        for (y in 0 until fh) {
            var src = y * fw * 4
            var dst = y * fw * 4
            for (x in 0 until fw) {
                bytes[dst] = SRGB[lut(f.get(src))]; bytes[dst + 1] = SRGB[lut(f.get(src + 1))]; bytes[dst + 2] = SRGB[lut(f.get(src + 2))]
                bytes[dst + 3] = -1
                src += 4; dst += 4
            }
        }
        val bmp = org.jetbrains.skia.Bitmap()
        bmp.allocPixels(org.jetbrains.skia.ImageInfo(fw, fh, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE))
        bmp.installPixels(bytes)
        return bmp.asComposeImageBitmap()
    }

    private fun lut(v: Float): Int = (v * LUT_MAX).toInt().coerceIn(0, LUT_MAX)

    private fun destroyTarget() {
        target?.let { engine.destroyRenderTarget(it) }; color?.let { engine.destroyTexture(it) }; depth?.let { engine.destroyTexture(it) }
        target = null; color = null; depth = null
    }

    fun destroy() {
        scene.view.renderTarget = null
        destroyTarget()
        swapChain?.let { engine.destroySwapChain(it) }; swapChain = null
    }

    private companion object {
        const val LUT_MAX = 4095
        /** From linear (0..1 in 4096 steps) to 8-bit sRGB. */
        val SRGB = ByteArray(LUT_MAX + 1) { i ->
            val c = i / LUT_MAX.toDouble()
            val s = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1 / 2.4) - 0.055
            (s * 255 + 0.5).toInt().coerceIn(0, 255).toByte()
        }
    }
}

@Composable
private fun RvizDesktopView(tabId: String, modifier: Modifier, showPlayback: Boolean = true) {
    val density = LocalDensity.current.density
    var statusVersion by remember { mutableIntStateOf(0) }
    var listVersion by remember { mutableIntStateOf(0) }
    val images = remember(tabId) { ImageOverlays() }
    val scene = remember(tabId) { RvizScene(tabId, density, overlay = images, onStatusChanged = { Platform.main.post { statusVersion++ } }) }
    var message by remember { mutableStateOf<String?>(null) }
    val tools = remember(scene) { RvizTools(scene, density) { message = it } }
    /** Bumped on every change to the Nav2 route (bar and arrows). */
    var navVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(tools) { tools.onNavChanged = { navVersion++ }; onDispose { tools.onNavChanged = {} } }
    var tool by remember { mutableStateOf(RvizTool.NONE) }
    /** The point picked with Select (its values show in the Selection panel). */
    var picked by remember { mutableStateOf<com.juancams.rviz2mini.rviz.display.PickedPoint?>(null) }
    /** Menu of an interactive marker control waiting for a choice. */
    var imMenu by remember { mutableStateOf<ImMenu?>(null) }
    val interact = remember(scene) { RvizInteract(scene, density) { d, im, c, entries -> imMenu = ImMenu(d, im, c, entries) } }
    /** Moving and resizing the Camera display panels. */
    val panels = remember(scene) { com.juancams.rviz2mini.rviz.PanelGesture(scene, density) }
    fun pickTool(t: RvizTool) { val next = if (tool == t) RvizTool.NONE else t; message = tools.setTool(next); tool = next; picked = tools.selected }
    val offscreen = remember(scene) { Offscreen(scene, extra = { tools.drawLabels() }) }
    LaunchedEffect(message) { if (message != null) { kotlinx.coroutines.delay(4000); message = null } }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var size by remember { mutableStateOf(0 to 0) }
    DisposableEffect(scene) {
        scene.visible = true
        onDispose { scene.visible = false; tools.destroy(); offscreen.destroy(); scene.destroy() }
    }
    LaunchedEffect(scene) {
        while (true) withFrameNanos { t -> offscreen.frame(t, size.first, size.second) { image = it } }
    }
    var editing by remember { mutableStateOf<DisplayConfig?>(null) }
    var adding by remember { mutableStateOf(false) }

    // Narrow (e.g. a dashboard widget): the displays panel, hidden and on top of the view; at the bottom, a strip of
    // chips with the displays (as on Android).
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
    val narrow = maxWidth < 760.dp
    var showPanel by remember { mutableStateOf(false) }
    // When wide, the panel docked on the right can be collapsed (remembered); collapsed, chips at the bottom as on Android.
    var panelOpen by remember { mutableStateOf(Platform.prefs("appearance").getBoolean("rvizPanel", true)) }
    val docked = !narrow && panelOpen
    fun togglePanel() {
        if (narrow) showPanel = !showPanel
        else { panelOpen = !panelOpen; Platform.prefs("appearance").edit().putBoolean("rvizPanel", panelOpen).apply() }
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Toolbar(scene, tools, tool, ::pickTool) { message = it }
            // With a bag playing, its bar (pause, speed and jump to an instant: the 3D follows it).
            if (showPlayback) com.juancams.rviz2mini.ui.common.PlaybackBar(Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            // Nav2: the waypoint route and the navigation status.
            if (navVersion >= 0 && com.juancams.rviz2mini.rviz.ui.nav2BarVisible(tools.mission, tool))
                com.juancams.rviz2mini.rviz.ui.Nav2Bar(tools.mission, navVersion, Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) { navVersion++ }
            Box(
                Modifier.fillMaxSize().background(Color(RvizScene.DEFAULT_BACKGROUND))
                    .onSizeChanged { size = it.width to it.height }
                    .pointerInput(scene) {
                        awaitPointerEventScope {
                            var last: androidx.compose.ui.geometry.Offset? = null
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull() ?: continue
                                when (e.type) {
                                    PointerEventType.Scroll -> if (!ch.isConsumed) { scene.viewCtl.pinch(if (ch.scrollDelta.y > 0) 0.9f else 1.1f); scene.saveView(); ch.consume() }
                                    PointerEventType.Press -> {
                                        last = ch.position
                                        if (e.buttons.isPrimaryPressed && panels.down(ch.position.x, ch.position.y)) { ch.consume(); continue }
                                        if (tool == RvizTool.INTERACT && e.buttons.isPrimaryPressed && interact.down(ch.position.x, ch.position.y)) { ch.consume(); continue }
                                        if (e.buttons.isPrimaryPressed && tools.consumesTouchAt(ch.position.x, ch.position.y)) { tools.down(ch.position.x, ch.position.y); ch.consume() }
                                    }
                                    PointerEventType.Move -> {
                                        if (panels.active) { panels.move(ch.position.x, ch.position.y); ch.consume(); continue }
                                        if (interact.dragging) { interact.move(ch.position.x, ch.position.y); ch.consume(); continue }
                                        if (tools.consumesTouches && (e.buttons.isPrimaryPressed || ch.pressed)) { tools.move(ch.position.x, ch.position.y); ch.consume(); continue }
                                        val p = last
                                        if (p != null && (e.buttons.isPrimaryPressed || e.buttons.isSecondaryPressed || e.buttons.isTertiaryPressed)) {
                                            val d = ch.position - p
                                            if (e.buttons.isPrimaryPressed && !e.keyboardModifiers.isShiftPressed) scene.viewCtl.drag(d.x, d.y, size.second)
                                            else scene.viewCtl.pan(d.x, d.y, size.second)
                                            ch.consume()
                                        }
                                        last = ch.position
                                    }
                                    PointerEventType.Release -> {
                                        last = ch.position
                                        if (panels.active) { panels.up(); listVersion++; ch.consume(); continue }
                                        if (interact.up()) { ch.consume(); continue }
                                        if (tools.consumesTouches) { tools.up(ch.position.x, ch.position.y); tool = tools.tool; picked = tools.selected; ch.consume() } else scene.saveView()
                                    }
                                    else -> last = ch.position
                                }
                            }
                        }
                    },
            ) {
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                ImagePanels(images, size.first, size.second) { id, persist, change -> scene.configs.firstOrNull { it.id == id }?.let { scene.updateDisplay(change(it), persist); listVersion++ } }
                imMenu?.let { m ->
                    DropdownMenu(true, { imMenu = null }) {
                        for ((label, id) in m.entries) DropdownMenuItem({ Text(label) }, { imMenu = null; interact.menuSelected(m.display, m.marker, m.control, id) })
                    }
                }
                message?.let { m ->
                    Text(m, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
                }
                if (!docked) DisplayChips(scene, statusVersion + listVersion, Modifier.align(Alignment.BottomStart), onEdit = { editing = it }) { listVersion++ }
                val open = if (narrow) showPanel else panelOpen
                androidx.compose.material3.FilledTonalIconButton(onClick = { togglePanel() }, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp)) {
                    Icon(if (open) Icons.Default.ChevronRight else Icons.Default.Layers, tr(if (open) "rviz_hide_displays" else "rviz_show_displays"))
                }
            }
        }
        if (docked) DisplaysPanel(scene, statusVersion + listVersion, onEdit = { editing = it }, onAdd = { adding = true }, onChanged = { listVersion++ },
                selection = picked?.let { tools.selectText(it) to it })
    }
    if (narrow && showPanel) Box(Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 48.dp).fillMaxHeight(0.9f)) {
        androidx.compose.material3.Surface(shadowElevation = 8.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
            DisplaysPanel(scene, statusVersion + listVersion, onEdit = { editing = it }, onAdd = { adding = true }, onChanged = { listVersion++ },
                selection = picked?.let { tools.selectText(it) to it })
        }
    }
    }
    editing?.let { c ->
        val topics = rememberGraph(3000)?.topics(includeHidden = true).orEmpty().filter { it.types.contains(c.type.rosType) }.map { it.name }
        com.juancams.rviz2mini.rviz.ui.DisplayEditorDialog(c, scene.displays[c.id]?.status, scene.displays[c.id]?.knownFrames() ?: scene.tfTree.frameIds(), topics, scene.displays[c.id]?.fix,
            onDismiss = { editing = null }, onSave = { scene.updateDisplay(it); editing = null; listVersion++ },
            onDelete = { scene.removeDisplay(c.id); editing = null; listVersion++ })
    }
    if (adding) com.juancams.rviz2mini.rviz.ui.AddDisplayDialog(onDismiss = { adding = false }, existing = scene.configs.toList()) { scene.addDisplay(it); adding = false; listVersion++ }
}

/**
 * The Image displays: in their corner (stacked) until dragged, then where they were left. Drag a panel to move it,
 * drag its bottom-right corner to resize it, and use the eye (or double click) to show or hide its annotations.
 * `onUpdate` changes a display configuration (`persist` false while dragging).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ImagePanels(images: ImageOverlays, viewWidthPx: Int, viewHeightPx: Int,
                                                                    onUpdate: (String, Boolean, (DisplayConfig) -> DisplayConfig) -> Unit) {
    val density = LocalDensity.current
    val margin = with(density) { 6.dp.roundToPx() }
    // Corner panels stack: the ones above push the next one.
    val stackOffset = HashMap<String, Int>()
    val ordered = images.panels.values.toList()
    for (corner in listOf("Top left", "Top right", "Bottom left", "Bottom right")) {
        var used = 0
        for (p in ordered.filter { !com.juancams.rviz2mini.rviz.geom.OverlayLayout.isFree(it.x, it.y) && (it.corner == corner || (corner == "Bottom right" && it.corner !in listOf("Top left", "Top right", "Bottom left"))) }) {
            stackOffset[p.id] = used
            val (iw, ih) = p.image?.let { it.width to it.height } ?: (4 to 3)
            used += com.juancams.rviz2mini.rviz.geom.OverlayLayout.rect(corner, -1.0, -1.0, p.size.toDouble(), viewWidthPx, viewHeightPx, iw, ih, margin)[3] + margin
        }
    }
    for (p in ordered) androidx.compose.runtime.key(p.id) {
        val (iw, ih) = p.image?.let { it.width to it.height } ?: (4 to 3)
        val base = com.juancams.rviz2mini.rviz.geom.OverlayLayout.rect(p.corner, p.x, p.y, p.size.toDouble(), viewWidthPx, viewHeightPx, iw, ih, margin)
        val free = com.juancams.rviz2mini.rviz.geom.OverlayLayout.isFree(p.x, p.y)
        val stack = stackOffset[p.id] ?: 0
        val r = if (free) base else base.copyOf().also { it[1] = if (p.corner.startsWith("Top")) base[1] + stack else base[1] - stack }
        var drag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var grow by remember { mutableStateOf(0f) }
        val w = with(density) { (r[2] + grow).toDp() }
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((r[0] + drag.x).toInt(), (r[1] + drag.y).toInt()) }.width(w).background(Color(0xA0000000))
            .pointerInput(p.id, r[0], r[1]) {
                detectDragGestures(onDragEnd = {
                    val (fx, fy) = com.juancams.rviz2mini.rviz.geom.OverlayLayout.moved(r, drag.x, drag.y, viewWidthPx, viewHeightPx)
                    drag = androidx.compose.ui.geometry.Offset.Zero
                    onUpdate(p.id, true) { it.with("x", fx.toString()).with("y", fy.toString()) }
                }, onDragCancel = { drag = androidx.compose.ui.geometry.Offset.Zero }) { ch, d -> ch.consume(); drag += d }
            }
            .pointerInput(p.id, p.hasAnnotations, p.showAnnotations) {
                detectTapGestures(onDoubleTap = { if (p.hasAnnotations) onUpdate(p.id, true) { it.with("showAnnotations", (!p.showAnnotations).toString()) } })
            }) {
            p.image?.let { Image(it, p.name, Modifier.fillMaxWidth().imageAlpha(p.alpha), contentScale = ContentScale.FillWidth) }
                ?: Text(p.name, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
            if (p.hasAnnotations) IconButton(onClick = { onUpdate(p.id, true) { it.with("showAnnotations", (!p.showAnnotations).toString()) } },
                modifier = Modifier.align(Alignment.TopStart).size(28.dp).background(Color(0x80000000))) {
                Icon(if (p.showAnnotations) Icons.Default.Visibility else Icons.Default.VisibilityOff, tr("rviz_image_annotations", p.name), tint = Color.White, modifier = Modifier.size(18.dp))
            }
            // Resize corner.
            Box(Modifier.align(Alignment.BottomEnd).size(16.dp).background(Color(0xB0FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(topStart = 8.dp))
                .pointerInput(p.id, r[2]) {
                    detectDragGestures(onDragEnd = {
                        val size = com.juancams.rviz2mini.rviz.geom.OverlayLayout.resized(p.size.toDouble(), grow, viewWidthPx)
                        grow = 0f
                        onUpdate(p.id, true) { c -> c.with("size", size.toString()).let { if (free) it else it.with("x", (r[0].toDouble() / viewWidthPx).toString()).with("y", (r[1].toDouble() / viewHeightPx).toString()) } }
                    }, onDragCancel = { grow = 0f }) { ch, d -> ch.consume(); grow += d.x }
                })
        }
    }
}

private fun Modifier.imageAlpha(a: Float) = this.then(Modifier.graphicsLayer(alpha = a))

@Composable
private fun Toolbar(scene: RvizScene, tools: RvizTools, tool: RvizTool, onTool: (RvizTool) -> Unit, onMessage: (String) -> Unit) {
    var frames by remember { mutableStateOf<List<String>?>(null) }
    var views by remember { mutableStateOf(false) }
    var fixed by remember { mutableStateOf(scene.fixedFrame) }
    LaunchedEffect(scene) { while (true) { kotlinx.coroutines.delay(500); scene.resolveFixedFrame(); fixed = scene.fixedFrame } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            TextButton(onClick = { frames = scene.tfTree.frameIds().sorted() }) { Text(fixed.ifEmpty { tr("rviz_no_tf") }, fontFamily = FontFamily.Monospace) }
            DropdownMenu(frames != null, { frames = null }) {
                for (f in frames.orEmpty()) DropdownMenuItem({ Text(f, fontFamily = FontFamily.Monospace) }, { frames = null; scene.setPreferredFixedFrame(f); fixed = scene.fixedFrame })
            }
        }
        Box {
            TextButton(onClick = { views = true }) { Text(scene.viewCtl.type.label) }
            var followFrames by remember { mutableStateOf<List<String>?>(null) }
            DropdownMenu(views, { views = false }) {
                for (t in ViewType.entries) DropdownMenuItem({ Text(t.label) }, {
                    views = false
                    if (t == ViewType.FOLLOW) followFrames = scene.tfTree.frameIds().sorted() else scene.setViewType(t)
                })
            }
            // Follow: the frame the camera follows.
            DropdownMenu(followFrames != null, { followFrames = null }) {
                for (f in followFrames.orEmpty()) DropdownMenuItem({ Text(f, fontFamily = FontFamily.Monospace) }, {
                    followFrames = null; scene.viewCtl.followFrame = f; scene.setViewType(ViewType.FOLLOW)
                })
            }
        }
        IconButton(onClick = { scene.resetView() }) { Icon(Icons.Default.CenterFocusStrong, null) }
        // Go back (whatever has arrived most recently, without having recorded a bag).
        IconButton(onClick = { scene.rewindNow { onMessage(it) } }) { Icon(Icons.Default.History, tr("rviz_rewind")) }
        var menu by remember { mutableStateOf(false) }
        var backgrounds by remember { mutableStateOf(false) }
        var topics by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(tr("rviz_menu_background")) }, { menu = false; backgrounds = true })
                DropdownMenuItem({ Text(tr("rviz_menu_tool_topics")) }, { menu = false; topics = true })
                DropdownMenuItem({ Text(tr("rviz_rewind_keep")) }, { menu = false; scene.setRewind(!scene.rewindEnabled); onMessage(tr(if (scene.rewindEnabled) "rewind_kept" else "rewind_off", RvizScene.REWIND_WINDOW_S)) },
                    trailingIcon = { androidx.compose.material3.Checkbox(scene.rewindEnabled, null) })
                val save = com.juancams.rviz2mini.ui.common.FileDialogs.saveText
                if (save != null) DropdownMenuItem({ Text(tr("rviz_menu_export")) }, {
                    menu = false
                    save("vizmini2.rviz", scene.exportRviz(tools.goalTopic, tools.poseTopic, tools.pointTopic)) { ok ->
                        Platform.main.post { if (ok) onMessage(tr("rviz_export_done")) }
                    }
                })
                val open = com.juancams.rviz2mini.ui.common.FileDialogs.openText
                if (open != null) DropdownMenuItem({ Text(tr("rviz_menu_import")) }, {
                    menu = false
                    open(listOf("rviz")) { text ->
                        Platform.main.post {
                            if (text == null) return@post
                            try {
                                val r = com.juancams.rviz2mini.rviz.rvizfile.RvizImporter.import(text, scene.height.takeIf { it > 0 } ?: 1000)
                                scene.applyImport(r); tools.setTopics(r.goalTopic, r.poseTopic, r.pointTopic)
                                onMessage(tr("rviz_import_displays", r.displays.size, r.displays.joinToString { it.name }.ifEmpty { tr("rviz_import_none") }) +
                                    if (r.skipped.isNotEmpty()) " · " + tr("rviz_import_skipped", r.skipped.joinToString()) else "")
                            } catch (e: Exception) { onMessage(tr("rviz_import_failed", e.message)) }
                        }
                    }
                })
                DropdownMenuItem({ Text(tr("rviz_menu_restart_ros")) }, { menu = false; RosConnection.restart() })
            }
            DropdownMenu(backgrounds, { backgrounds = false }) {
                for ((key, c) in listOf("rviz_bg_dark" to RvizScene.DEFAULT_BACKGROUND, "rviz_bg_rviz_gray" to 0xFF303030.toInt(), "rviz_bg_black" to 0xFF000000.toInt(),
                        "rviz_bg_light_gray" to 0xFFB0B0B0.toInt(), "rviz_bg_white" to 0xFFFFFFFF.toInt(), "rviz_bg_midnight_blue" to 0xFF0D1B2A.toInt()))
                    DropdownMenuItem({ Text(tr(key)) }, { backgrounds = false; scene.setBackground(c) })
            }
        }
        if (topics) ToolTopicsDialog(tools) { topics = false }
        // The tools, on the right; if they do not fit (narrow window or widget), they scroll.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (t in listOf(RvizTool.GOAL, RvizTool.POSE_ESTIMATE, RvizTool.PUBLISH_POINT, RvizTool.MEASURE, RvizTool.INTERACT, RvizTool.SELECT, RvizTool.WAYPOINTS, RvizTool.MOVEIT_GOAL)) {
                    TextButton(onClick = { onTool(t) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text(tr(t.labelKey), maxLines = 1, fontSize = 13.sp, color = if (tool == t) Color(t.color) else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** Strip of chips with the displays and their status (like Android's): click = enable or disable; right click = edit. */
@Composable
private fun DisplayChips(scene: RvizScene, version: Int, modifier: Modifier, onEdit: (DisplayConfig) -> Unit, onChanged: () -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (version < 0) return@Row
        for (c in scene.configs.toList()) androidx.compose.runtime.key(c.id) {
            val st = scene.displays[c.id]?.status
            val col = when (st?.level) { StatusLevel.OK -> StatusColors.ok; StatusLevel.WARN -> StatusColors.warn; StatusLevel.ERROR -> StatusColors.error; else -> StatusColors.idle }
            androidx.compose.material3.FilterChip(selected = c.enabled, onClick = { scene.updateDisplay(c.copy(enabled = !c.enabled)); onChanged() },
                label = { Text(c.name, maxLines = 1) },
                leadingIcon = { Box(Modifier.size(10.dp).background(if (c.enabled) col else StatusColors.idle, CircleShape)) },
                modifier = Modifier.pointerInput(c.id) {
                    awaitPointerEventScope {
                        while (true) { val e = awaitPointerEvent(); if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) onEdit(c) }
                    }
                })
        }
    }
}

/**
 * The displays panel like RViz's: a tree with Global Options (fixed frame, background), Global Status and each display
 * with its checkbox, expandable with its properties editable in place (applied immediately). At the bottom, Add, Duplicate,
 * Remove and Rename on the selected display. It widens by dragging its left edge.
 */
@Composable
private fun DisplaysPanel(scene: RvizScene, version: Int, onEdit: (DisplayConfig) -> Unit, onAdd: () -> Unit, onChanged: () -> Unit,
                          selection: Pair<String, com.juancams.rviz2mini.rviz.display.PickedPoint>? = null) {
    val prefs = remember { Platform.prefs("appearance") }
    var width by remember { mutableStateOf(prefs.getFloat("rvizPanelWidth", 340f)) }
    var selected by remember { mutableStateOf<String?>(null) }
    val expanded = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var globalOpen by remember { mutableStateOf(true) }
    var renaming by remember { mutableStateOf<DisplayConfig?>(null) }
    val graph = rememberGraph(3000)
    val density = LocalDensity.current.density
    fun update(c: DisplayConfig) { scene.updateDisplay(c); onChanged() }
    Row(Modifier.width(width.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer)) {
        // The edge: dragging it changes the width (remembered).
        Box(Modifier.width(5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { prefs.edit().putFloat("rvizPanelWidth", width).apply() }) { ch, dx ->
                    ch.consume(); width = (width - dx / density).coerceIn(260f, 640f)
                }
            })
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("Displays", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (version < 0) return@Column // reads `version`: the panel repaints when a status changes
                // Global Options
                // "Global Options" and "Fixed Frame" in English in every language, as RViz calls them (and as on Android).
                TreeHeader(globalOpen, { globalOpen = !globalOpen }) { Text("Global Options", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                if (globalOpen) {
                    com.juancams.rviz2mini.rviz.ui.PropRow("Fixed Frame", 1) {
                        // Written by hand (it may not exist yet: it is used as soon as it appears in TF) or picked.
                        val auto = "(auto: ${scene.fixedFrame.ifEmpty { "—" }})"
                        com.juancams.rviz2mini.rviz.ui.FrameField(scene.preferredFixedFrame.orEmpty(), scene.tfTree.frameIds().sorted(), auto) { f ->
                            scene.setPreferredFixedFrame(f.ifEmpty { null }); onChanged()
                        }
                    }
                    com.juancams.rviz2mini.rviz.ui.PropRow(tr("rviz_background_title"), 1) {
                        val bg = com.juancams.rviz2mini.rviz.display.Colors.parseHex(scene.setting(RvizScene.KEY_BACKGROUND)) ?: RvizScene.DEFAULT_BACKGROUND
                        com.juancams.rviz2mini.rviz.ui.ColorField(bg and 0xFFFFFF, listOf(RvizScene.DEFAULT_BACKGROUND, 0xFF303030.toInt(), 0xFF000000.toInt(), 0xFFB0B0B0.toInt(), 0xFFFFFFFF.toInt(), 0xFF0D1B2A.toInt())) {
                            scene.setBackground(it or (0xFF shl 24)); onChanged()
                        }
                    }
                }
                // Global Status (like RViz: the fixed frame must exist in TF)
                val frames = scene.tfTree.frameIds()
                val (gLevel, gText) = when {
                    frames.isEmpty() -> StatusLevel.WARN to "No TF data"
                    scene.fixedFrame.isEmpty() || !scene.tfTree.contains(scene.fixedFrame) -> StatusLevel.ERROR to "Fixed Frame [${scene.fixedFrame}] does not exist"
                    // Written by hand and not in TF yet: the automatic one is used meanwhile.
                    scene.preferredFixedFrame.let { it != null && it != scene.fixedFrame } ->
                        StatusLevel.WARN to "Fixed Frame [${scene.preferredFixedFrame}] not in TF yet: using [${scene.fixedFrame}]"
                    else -> StatusLevel.OK to "Ok"
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(com.juancams.rviz2mini.rviz.ui.statusColorOf(gLevel), CircleShape))
                    Text("  Global Status: $gText", fontSize = 13.sp, color = com.juancams.rviz2mini.rviz.ui.statusColorOf(gLevel), maxLines = 2)
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 2.dp))
                for (c in scene.configs.toList()) androidx.compose.runtime.key(c.id) {
                    val st = scene.displays[c.id]?.status
                    val open = c.id in expanded
                    var ctxMenu by remember { mutableStateOf(false) }
                    Column(Modifier.background(if (selected == c.id) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                        .onContextClick(c.id) { selected = c.id; ctxMenu = true }) {
                        // Right click: the bottom buttons' actions, on this display.
                        DropdownMenu(ctxMenu, { ctxMenu = false }) {
                            DropdownMenuItem({ Text(if (c.enabled) tr("rviz_disable") else tr("rviz_enable")) }, { ctxMenu = false; update(c.copy(enabled = !c.enabled)) })
                            DropdownMenuItem({ Text(tr("main_duplicate_tab")) }, { ctxMenu = false; scene.addDisplay(c.copy(id = java.util.UUID.randomUUID().toString(), name = c.name + " (2)")); onChanged() })
                            DropdownMenuItem({ Text(tr("common_rename")) }, { ctxMenu = false; renaming = c })
                            DropdownMenuItem({ Text(tr("rviz_action_remove")) }, { ctxMenu = false; scene.removeDisplay(c.id); selected = null; onChanged() })
                        }
                        TreeHeader(open, { if (open) expanded.remove(c.id) else expanded.add(c.id) }, onClick = { selected = c.id }) {
                            Checkbox(c.enabled, { on -> update(c.copy(enabled = on)) }, Modifier.size(28.dp))
                            Text(c.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
                            Box(Modifier.padding(end = 8.dp).size(9.dp).background(if (c.enabled) com.juancams.rviz2mini.rviz.ui.statusColorOf(st?.level) else Color.Gray, CircleShape))
                        }
                        if (open) {
                            val topics = graph?.topics(includeHidden = true).orEmpty()
                                .filter { t -> c.type in DisplayFactory.displaysFor(t.name, t.types) }.map { it.name }.sorted()
                            Box(Modifier.padding(start = 16.dp)) {
                                com.juancams.rviz2mini.rviz.ui.DisplayPropertyGrid(c, st, scene.displays[c.id]?.knownFrames() ?: scene.tfTree.frameIds().sorted(), topics,
                                    scene.displays[c.id]?.fix) { update(it) }
                            }
                        }
                    }
                }
            }
            // Selection (like the RViz panel): the values of the point picked with Select.
            selection?.let { (_, pt) -> SelectionPanel(scene, pt) }
            // Views (like the RViz panel): go back to a saved camera with one click.
            SavedViews(scene) { onChanged() }
            // The RViz buttons, on the selected display.
            val sel = scene.configs.firstOrNull { it.id == selected }
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                TextButton(onClick = onAdd, contentPadding = pad) { Text(tr("rviz_action_add"), fontSize = 13.sp) }
                TextButton(enabled = sel != null, contentPadding = pad, onClick = {
                    sel?.let { c -> scene.addDisplay(c.copy(id = java.util.UUID.randomUUID().toString(), name = c.name + " (2)")); onChanged() }
                }) { Text(tr("main_duplicate_tab"), fontSize = 13.sp) }
                TextButton(enabled = sel != null, contentPadding = pad, onClick = { sel?.let { scene.removeDisplay(it.id); selected = null; onChanged() } }) { Text(tr("rviz_action_remove"), fontSize = 13.sp) }
                TextButton(enabled = sel != null, contentPadding = pad, onClick = { renaming = sel }) { Text(tr("common_rename"), fontSize = 13.sp) }
            }
        }
    }
    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(tr("common_rename")) },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { update(c.copy(name = name.trim().ifEmpty { c.name })); renaming = null }) { Text(tr("rviz_action_save")) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(tr("rviz_action_cancel")) } })
    }
}

/** The point picked with Select: display, position in the fixed frame and in the sensor's frame, and its fields. */
@Composable
private fun SelectionPanel(scene: RvizScene, p: com.juancams.rviz2mini.rviz.display.PickedPoint) {
    var open by remember { mutableStateOf(true) }
    val f = java.util.Locale.ROOT
    androidx.compose.material3.HorizontalDivider()
    TreeHeader(open, { open = !open }) { Text("Selection · ${p.display}", fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (open) Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(bottom = 4.dp)) {
        val rows = buildList {
            add(scene.fixedFrame.ifEmpty { "fixed" } to String.format(f, "%.3f; %.3f; %.3f", p.fixed[0], p.fixed[1], p.fixed[2]))
            if (p.frame.isNotEmpty()) {
                add(p.frame to String.format(f, "%.3f; %.3f; %.3f", p.local[0], p.local[1], p.local[2]))
                add("distance" to String.format(f, "%.3f m", Math.sqrt(p.local[0] * p.local[0] + p.local[1] * p.local[1] + p.local[2] * p.local[2])))
            }
            addAll(p.fields)
        }
        for ((k, v) in rows) com.juancams.rviz2mini.rviz.ui.PropRow(k, 1) {
            androidx.compose.foundation.text.selection.SelectionContainer { Text(v, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        }
    }
}

/** The saved views of this scene: apply (click), delete, and save the current one with a name. */
@Composable
private fun SavedViews(scene: RvizScene, onChanged: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val v = version
    val views = scene.savedViews()
    androidx.compose.material3.HorizontalDivider()
    TreeHeader(open, { open = !open }) {
        Text(tr("rviz_views") + if (views.isNotEmpty()) " (${views.size})" else "", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        TextButton(onClick = { naming = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text(tr("rviz_views_save"), fontSize = 12.sp) }
    }
    if (open) Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
        if (views.isEmpty()) Text(tr("rviz_views_none"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, bottom = 4.dp))
        for ((name, _) in views) Row(Modifier.fillMaxWidth().clickable { scene.applySavedView(name); onChanged() }.padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 13.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { scene.deleteSavedView(name); version++ }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Delete, tr("common_delete_named", name), Modifier.size(16.dp)) }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf(tr("rviz_views_default_name", views.size + 1)) }
        AlertDialog(onDismissRequest = { naming = false }, title = { Text(tr("rviz_views_save")) },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { name.trim().takeIf { it.isNotEmpty() }?.let { scene.saveCurrentView(it) }; naming = false; version++; open = true }) { Text(tr("rviz_action_save")) } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(tr("rviz_action_cancel")) } })
    }
}

/** Header of a tree node: the expand arrow and its content. */
@Composable
private fun TreeHeader(open: Boolean, onToggle: () -> Unit, onClick: () -> Unit = onToggle, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null,
            Modifier.padding(start = 4.dp).size(22.dp).clickable(onClick = onToggle))
        content()
    }
}

@Composable
private fun ToolTopicsDialog(tools: RvizTools, onDismiss: () -> Unit) {
    var goal by remember { mutableStateOf(tools.goalTopic) }
    var pose by remember { mutableStateOf(tools.poseTopic) }
    var point by remember { mutableStateOf(tools.pointTopic) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("rviz_tool_topics_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(goal, { goal = it }, singleLine = true, label = { Text(tr("rviz_tool_goal_label")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pose, { pose = it }, singleLine = true, label = { Text(tr("rviz_tool_pose_label")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(point, { point = it }, singleLine = true, label = { Text(tr("rviz_tool_point_label")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                fun ok(t: String) = com.juancams.rviz2mini.camera.ImageTopics.normalize(t).takeIf { com.juancams.rviz2mini.camera.ImageTopics.isValidName(it) }
                tools.setTopics(ok(goal), ok(pose), ok(point)); onDismiss()
            }) { Text(tr("rviz_action_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("rviz_action_cancel")) } },
    )
}

