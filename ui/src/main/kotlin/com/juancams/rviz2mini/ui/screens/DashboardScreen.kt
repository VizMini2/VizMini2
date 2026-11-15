// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.juancams.rviz2mini.dashboard.DashboardGrid
import com.juancams.rviz2mini.ui.common.onContextClick
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.dashboard.TopicDrop
import com.juancams.rviz2mini.ui.common.TopicDragOverlay
import com.juancams.rviz2mini.ui.common.topicDropTarget
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import kotlin.math.roundToInt

private const val MIN_ZOOM = 0.2f
private const val MAX_ZOOM = 3f
private const val ZOOM_STEP = 1.1f
/** Widgets used with several fingers: with one finger on them, two-finger gestures do not belong to the board. */
private val TWO_FINGER_WIDGETS = setOf(TabType.TELEOP, TabType.TELEOP_DUAL)

/** Key of the dashboard layout (the same as Android's DashboardFragment). */
const val DASHBOARD_KEY_LAYOUT = "layout"

/** Icon of each tab type (the same drawings as on Android). */
fun tabIcon(type: TabType): ImageVector = when (type) {
    TabType.RVIZ -> Icons.Default.ViewInAr
    TabType.CAMERA -> Icons.Default.PhotoCamera
    TabType.TELEOP, TabType.TELEOP_DUAL -> Icons.Default.SportsEsports
    TabType.DIAGNOSTICS -> Icons.Default.MonitorHeart
    TabType.BUTTONS, TabType.SERVICES -> Icons.Default.TouchApp
    TabType.SLIDERS -> Icons.Default.Tune
    TabType.INDICATORS -> Icons.Default.Speed
    TabType.PLOT -> Icons.Default.Timeline
    TabType.GPS -> Icons.Default.Map
    TabType.BEHAVIOR_TREE, TabType.TF_TREE -> Icons.Default.AccountTree
    TabType.MESSAGE -> Icons.Default.DataObject
    TabType.PARAMETERS -> Icons.Default.Settings
    TabType.LIFECYCLE -> Icons.Default.Autorenew
    TabType.BAGS -> Icons.Default.Inventory2
    TabType.NODES -> Icons.Default.Hub
    TabType.TOPICS -> Icons.Default.WifiTethering
    TabType.ACTIONS -> Icons.Default.PlayArrow
    TabType.DASHBOARD -> Icons.Default.Dashboard
    TabType.NODE_GRAPH -> Icons.Default.Lan
    TabType.MOVEIT -> Icons.Default.PrecisionManufacturing
    TabType.CONTROLLERS -> Icons.Default.DeveloperBoard
    TabType.MAPS -> Icons.Default.Layers
    TabType.ALERTS -> Icons.Default.NotificationsActive
}

/**
 * The state of a dashboard: the widgets (tabs saved separately with KEY_DASHBOARD pointing here) and their
 * placement on a 12-column grid with square cells, saved in the [DASHBOARD_KEY_LAYOUT] setting.
 */
class DashboardController(val tabId: String) {
    private val store = TabStore.get()
    var items by mutableStateOf<List<DashboardGrid.Item>>(emptyList()); private set
    var editing by mutableStateOf(false)

    fun widgets(): List<TabSpec> = store.all.filter { it.dashboardId == tabId }

    /** Saved layout, without widgets that no longer exist and with room for missing ones (the rest, where they were). */
    fun reload() {
        val specs = widgets().associateBy { it.id }
        var list = DashboardGrid.fromJson(store.get(tabId)?.settings?.get(DASHBOARD_KEY_LAYOUT)).filter { it.id in specs }.map { DashboardGrid.clampFree(it) }
        for (s in specs.values) if (list.none { it.id == s.id }) {
            val (w, h) = DashboardGrid.defaultSize(s.type)
            list = list + DashboardGrid.firstFree(list, w, h, s.id)
        }
        items = list
    }

    /** The state before each change, for undo (Ctrl+Z on the PC, ↶ in edit mode). */
    private val history = ArrayDeque<Pair<List<DashboardGrid.Item>, TabSpec?>>()
    var canUndo by mutableStateOf(false); private set

    private fun save(list: List<DashboardGrid.Item>, removed: TabSpec? = null) {
        history.addLast(items to removed); while (history.size > 50) history.removeFirst(); canUndo = true
        items = list; store.updateSetting(tabId, DASHBOARD_KEY_LAYOUT, DashboardGrid.toJson(list))
    }

    /** Undoes the last change (move, resize, remove a widget: it comes back with its configuration). */
    fun undo() {
        val (prev, removed) = history.removeLastOrNull() ?: return
        removed?.let { store.add(it) }
        items = prev; store.updateSetting(tabId, DASHBOARD_KEY_LAYOUT, DashboardGrid.toJson(prev))
        canUndo = history.isNotEmpty()
    }

    /** Free placement: it stays where it is dropped, on top of the others, and nothing else moves. */
    fun move(id: String, x: Double, y: Double, w: Double, h: Double) = save(DashboardGrid.moveFree(items, DashboardGrid.Item(id, x, y, w, h)))
    fun move(id: String, x: Int, y: Int, w: Int, h: Int) = move(id, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())

    fun remove(id: String) { val spec = store.get(id); store.remove(id); save(items.filter { it.id != id }, removed = spec) }

    /** Adds a tab as a widget of this dashboard, in the first free slot. */
    fun add(spec: TabSpec) {
        val widget = spec.withSetting(TabSpec.KEY_DASHBOARD, tabId)
        store.add(widget)
        val (w, h) = DashboardGrid.defaultSize(widget.type)
        save(items + DashboardGrid.firstFree(items, w, h, widget.id))
    }

    /** Adds a widget with its corner at (x, y) (cells), e.g. where a topic was dropped. */
    fun addAt(spec: TabSpec, x: Double, y: Double) {
        val widget = spec.withSetting(TabSpec.KEY_DASHBOARD, tabId)
        store.add(widget)
        val (w, h) = DashboardGrid.defaultSize(widget.type)
        save(DashboardGrid.moveFree(items, DashboardGrid.clampFree(DashboardGrid.Item(widget.id, x.coerceAtLeast(0.0), y.coerceAtLeast(0.0), w.toDouble(), h.toDouble()))))
    }

    init { reload() }

    companion object {
        /** The dashboard currently shown (for the PC keyboard shortcuts). */
        @Volatile var visible: DashboardController? = null
        /** Reset the visible dashboard to 100 % zoom (Ctrl+0). */
        @Volatile var resetZoom: (() -> Unit)? = null
    }
}

/** Dashboard on the PC (with its own add-widget dialog); `widget` draws each one. */
@Composable
fun DashboardScreen(tabId: String, modifier: Modifier = Modifier, canAdd: Set<TabType>, widget: @Composable (TabSpec, Modifier) -> Unit) {
    val ctl = remember(tabId) { DashboardController(tabId) }
    var adding by remember { mutableStateOf(false) }
    DashboardView(ctl, modifier, onAddWidget = { adding = true }, widget = widget)
    if (adding) AddTabDialog(canAdd, forWidget = true, onDismiss = { adding = false }) { spec -> adding = false; ctl.add(spec) }
}

/**
 * The view of a dashboard: bar (widget count, Add, Edit) and the grid. In edit mode each widget can be
 * dragged, resized from the corner, floated above the others or removed; `onAddItem` (if present for that
 * widget) adds an item of its own (button, slider...).
 */
@Composable
fun DashboardView(
    ctl: DashboardController,
    modifier: Modifier = Modifier,
    onAddWidget: () -> Unit,
    onAddItem: ((TabSpec) -> Unit)? = null,
    hasItems: (TabSpec) -> Boolean = { false },
    widget: @Composable (TabSpec, Modifier) -> Unit,
) {
    val items = ctl.items
    val editing = ctl.editing
    val drag = remember { mutableStateMapOf<String, Offset>() }
    val resize = remember { mutableStateMapOf<String, Offset>() }
    val primary = MaterialTheme.colorScheme.primary
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Board zoom (cell size): per device, per dashboard. Ctrl + wheel (PC) or two-finger pinch
    // (phone), which also pans the board. The point under the mouse or fingers stays fixed.
    val zoomStore = remember { com.juancams.rviz2mini.platform.Platform.prefs("dashboard") }
    var zoom by remember(ctl.tabId) { androidx.compose.runtime.mutableFloatStateOf(zoomStore.getFloat("zoom:${ctl.tabId}", 1f).coerceIn(MIN_ZOOM, MAX_ZOOM)) }
    val hs = rememberScrollState()
    val vs = rememberScrollState()
    var anchor by remember { mutableStateOf<Pair<Offset, Float>?>(null) }
    fun setZoom(z: Float, at: Offset) {
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (kotlin.math.abs(nz - zoom) < 0.001f) return
        anchor = at to (nz / zoom) * (anchor?.second ?: 1f)
        zoom = nz
        zoomStore.edit().putFloat("zoom:${ctl.tabId}", nz).apply()
    }
    androidx.compose.runtime.DisposableEffect(ctl) {
        DashboardController.visible = ctl
        DashboardController.resetZoom = { setZoom(1f, Offset.Zero) }
        onDispose { if (DashboardController.visible === ctl) { DashboardController.visible = null; DashboardController.resetZoom = null } }
    }
    LaunchedEffect(zoom) {
        val (p, k) = anchor ?: return@LaunchedEffect
        anchor = null
        androidx.compose.runtime.withFrameNanos { } // after measuring the board with the new zoom
        hs.scrollTo(((hs.value + p.x) * k - p.x).roundToInt().coerceAtLeast(0))
        vs.scrollTo(((vs.value + p.y) * k - p.y).roundToInt().coerceAtLeast(0))
    }

    Column(modifier.fillMaxSize().padding(start = 8.dp, top = 8.dp, end = 8.dp)) {
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 2.dp, end = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                // Pause everything at once (live) or the bag that is playing.
                com.juancams.rviz2mini.ui.common.LivePauseButton()
                Text(tr("dashboard_count", items.size), fontSize = 13.sp, modifier = Modifier.weight(1f))
                // With zoom, the % (tapping it returns to 100 %: the whole board across the width).
                if (kotlin.math.abs(zoom - 1f) > 0.01f) TextButton(onClick = { setZoom(1f, Offset.Zero) }) {
                    Text("${(zoom * 100).roundToInt()} %", fontSize = 13.sp, modifier = Modifier.semantics { contentDescription = tr("dashboard_zoom_reset") })
                }
                if (editing && ctl.canUndo) IconButton(onClick = { ctl.undo() }) {
                    Icon(Icons.AutoMirrored.Filled.Undo, tr("dashboard_undo"))
                }
                if (editing) TextButton(onClick = onAddWidget) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(tr("dashboard_add_widget")) }
                OutlinedButton(onClick = { ctl.editing = !editing }) { Text(tr(if (editing) "dashboard_done" else "dashboard_edit")) }
            }
        }
        com.juancams.rviz2mini.ui.common.PlaybackBar(Modifier.padding(top = 6.dp))
        Box(Modifier.fillMaxSize().padding(top = 6.dp).pointerInput(editing) {
            awaitPointerEventScope {
                var lastDist = 0f
                var lastCentroid: Offset? = null
                var decided = false
                var blocked = false
                while (true) {
                    // Before the widgets (Initial): Ctrl + wheel and the edit pinch belong to the board.
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Scroll && e.keyboardModifiers.isCtrlPressed) {
                        val ch = e.changes.first()
                        val dy = ch.scrollDelta.y
                        if (dy != 0f) setZoom(zoom * if (dy < 0) ZOOM_STEP else 1f / ZOOM_STEP, ch.position)
                        e.changes.forEach { it.consume() }
                        continue
                    }
                    // Two fingers, always: zoom (pinch) and board panning. One finger belongs to each widget. If
                    // a finger lands on a joystick nothing is touched (the dual-joystick one is used with two fingers).
                    val down = e.changes.filter { it.pressed }
                    if (down.size >= 2) {
                        if (!decided) {
                            decided = true
                            val types = ctl.widgets().associate { it.id to it.type }
                            val unit = size.width / DashboardGrid.COLUMNS.toFloat() * zoom
                            blocked = !editing && down.any { ch ->
                                val bx = (ch.position.x + hs.value) / unit; val by = (ch.position.y + vs.value) / unit
                                ctl.items.any { it -> types[it.id] in TWO_FINGER_WIDGETS && bx >= it.x && bx <= it.x + it.w && by >= it.y && by <= it.y + it.h }
                            }
                        }
                        if (!blocked) {
                            val a = down[0].position; val b = down[1].position
                            val dist = (a - b).getDistance()
                            val c = (a + b) / 2f
                            lastCentroid?.let { prev -> hs.dispatchRawDelta(prev.x - c.x); vs.dispatchRawDelta(prev.y - c.y) }
                            if (lastDist > 0f && dist > 0f) setZoom(zoom * dist / lastDist, c)
                            lastDist = dist; lastCentroid = c
                            e.changes.forEach { it.consume() }
                        }
                    } else if (down.isEmpty()) { lastDist = 0f; lastCentroid = null; decided = false; blocked = false }
                    else { lastDist = 0f; lastCentroid = null }
                }
            }
        }) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val unitDp = maxWidth / DashboardGrid.COLUMNS * zoom
                val unitPx = with(density) { unitDp.toPx() }
                // Unlimited board: as big as its content (and at least the screen); when editing, with plenty of room
                // to the right and below to keep placing.
                val viewCols = DashboardGrid.COLUMNS / zoom
                val cols = maxOf(viewCols, DashboardGrid.columns(items).toFloat() + if (editing) viewCols / 2 else 0f)
                val rows = DashboardGrid.rows(items).toFloat() + if (editing) viewCols / 2 else 0f
                var boardOrigin by remember { mutableStateOf(Offset.Zero) }
                // A topic dropped into an empty slot (anywhere in the view, also below the last widget): a
                // new widget to view it (camera, 3D, plot...), right there.
                Box(Modifier.fillMaxSize().topicDropTarget("dashboard:${ctl.tabId}", accepts = { DashboardTemplates.widgetFor(it) != null }) { p, at ->
                        val spec = DashboardTemplates.widgetFor(p) ?: return@topicDropTarget null
                        val g = (at - boardOrigin) / unitPx
                        // Where it is dropped, but fully inside the visible width.
                        val w = DashboardGrid.defaultSize(spec.type).first
                        val left = hs.value / unitPx
                        ctl.addAt(spec, g.x.toDouble().coerceAtMost(left + viewCols.toDouble() - w).coerceAtLeast(left.toDouble()), g.y.toDouble())
                        tr("drop_new_widget", spec.name)
                    }.verticalScroll(vs).horizontalScroll(hs)) {
                Box(Modifier.size(unitDp * cols, unitDp * rows.coerceAtLeast(1f))
                    .onGloballyPositioned { boardOrigin = it.positionInWindow() }) {
                    // In a fixed order (that of the tabs): if it changed with the layout, Compose would rebuild the cards and,
                    // on Android, their widgets. What goes on top is set by zIndex (the last floating one moved, on top).
                    val byId = items.withIndex().associateBy { it.value.id }
                    for (spec in ctl.widgets()) { val (index, item) = byId[spec.id] ?: continue; key(item.id) {
                        val d = drag[item.id]
                        val r = resize[item.id] ?: Offset.Zero
                        val gap = 3.dp
                        val covers = DashboardGrid.coversAnother(items, item)
                        val shape = RoundedCornerShape(16.dp)
                        OutlinedCard(
                            shape = shape,
                            // Over another one: translucent (what is below shows through) and with a primary-color border.
                            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.let { if (covers) it.copy(alpha = 0.7f) else it }),
                            border = if (covers) androidx.compose.foundation.BorderStroke(1.dp, primary.copy(alpha = 0.6f)) else CardDefaults.outlinedCardBorder(),
                            modifier = Modifier
                                .offset { IntOffset((item.x * unitPx + (d?.x ?: 0f)).roundToInt(), (item.y * unitPx + (d?.y ?: 0f)).roundToInt()) }
                                .size(unitDp * item.w.toFloat() + with(density) { r.x.toDp() }, unitDp * item.h.toFloat() + with(density) { r.y.toDp() })
                                .padding(gap)
                                // Floating ones on top; and the one being dragged, above everything.
                                // In list order (the last one moved, on top); the one being dragged, above everything.
                                .zIndex(if (d != null) 1000f else index.toFloat())
                                // A topic dropped on it: to the widget (RViz adds a display, Plot a series...).
                                .topicDropTarget(item.id, accepts = { TopicDrop.accepts(spec.id) }) { p, _ -> TopicDrop.deliver(spec.id, p) },
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                var menuAt by remember { mutableStateOf<Offset?>(null) }
                                Box(Modifier.fillMaxSize().onContextClick(item.id) { at -> if (!editing) menuAt = at }) { widget(spec, Modifier.fillMaxSize()) }
                                // Right click (PC): the edit actions without entering edit mode; the menu, where the click was.
                                menuAt?.let { at ->
                                    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.size(1.dp)) {
                                        androidx.compose.material3.DropdownMenu(true, { menuAt = null }) {
                                            androidx.compose.material3.DropdownMenuItem({ Text(tr("dashboard_bring_front")) }, { menuAt = null; ctl.move(item.id, item.x, item.y, item.w, item.h) })
                                            androidx.compose.material3.DropdownMenuItem({ Text(tr("dashboard_edit")) }, { menuAt = null; ctl.editing = true })
                                            androidx.compose.material3.DropdownMenuItem({ Text(tr("dashboard_remove")) }, { menuAt = null; ctl.remove(item.id) })
                                        }
                                    }
                                }
                                if (editing) Box(
                                    Modifier.fillMaxSize().background(primary.copy(alpha = 0.25f))
                                        .pointerInput(item.id) {
                                            detectDragGestures(onDragEnd = {
                                                val off = drag.remove(item.id) ?: Offset.Zero
                                                val cur = ctl.items.firstOrNull { it.id == item.id } ?: return@detectDragGestures
                                                // Exactly where it is dropped (without snapping to the cell).
                                                ctl.move(item.id, cur.x + off.x / unitPx, cur.y + off.y / unitPx, cur.w, cur.h)
                                            }, onDragCancel = { drag.remove(item.id) }) { ch, amount -> ch.consume(); drag[item.id] = (drag[item.id] ?: Offset.Zero) + amount }
                                        },
                                ) {
                                    Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(tabIcon(spec.type), null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(spec.name, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                    FilledIconButton(onClick = { ctl.remove(item.id) }, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935), contentColor = Color.White)) { Icon(Icons.Default.Delete, tr("dashboard_remove")) }
                                    if (onAddItem != null && hasItems(spec)) FilledIconButton(onClick = { onAddItem(spec) }, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(36.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = primary, contentColor = Color.White)) { Icon(Icons.Default.Add, tr("dashboard_add_item")) }
                                    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(36.dp).clip(RoundedCornerShape(50)).background(primary)
                                        .pointerInput(item.id) {
                                            detectDragGestures(onDragEnd = {
                                                val off = resize.remove(item.id) ?: Offset.Zero
                                                val cur = ctl.items.firstOrNull { it.id == item.id } ?: return@detectDragGestures
                                                ctl.move(item.id, cur.x, cur.y, (cur.w * unitPx + off.x) / unitPx, (cur.h * unitPx + off.y) / unitPx)
                                            }, onDragCancel = { resize.remove(item.id) }) { ch, amount -> ch.consume(); resize[item.id] = (resize[item.id] ?: Offset.Zero) + amount }
                                        }, contentAlignment = Alignment.Center) { Icon(Icons.Default.OpenInFull, tr("dashboard_resize"), tint = Color.White) }
                                }
                            }
                        }
                    } }
                }
                }
            }
            TopicDragOverlay()
            if (items.isEmpty() && !editing) Text(tr("dashboard_empty"), fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
    }
}
