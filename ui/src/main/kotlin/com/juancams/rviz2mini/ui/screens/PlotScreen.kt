// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.plot.PanelKind
import com.juancams.rviz2mini.plot.PanelSpec
import com.juancams.rviz2mini.plot.PlotSpecs
import com.juancams.rviz2mini.plot.SeriesSpec
import kotlin.math.exp

/** Plots tab with its own controller (desktop, dashboards). */
@Composable
fun PlotScreen(tabId: String, modifier: Modifier = Modifier) {
    val c = remember(tabId) { PlotController(tabId) }
    DisposableEffect(c) { c.resume(); onDispose { c.close() } }
    PlotView(c, modifier)
}

/** The plots tab of a [PlotController] (on Android, the fragment owns the controller). */
@Composable
fun PlotView(c: PlotController, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(c.message) { c.message?.let { snackbar.showSnackbar(it); c.message = null } }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Strip(c)
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                val panels = c.layout.panels
                for ((i, p) in panels.withIndex()) key(p.id) {
                    Card(
                        shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 6.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    ) {
                        Column(Modifier.padding(start = 4.dp, end = 2.dp, top = 2.dp, bottom = 4.dp)) {
                            PanelHeader(c, p)
                            PlotPanelCanvas(c, p, i, Modifier.fillMaxWidth().weight(1f))
                            if (c.showStats && p.kind == PanelKind.TIME) PanelStats(c, p)
                        }
                    }
                }
            }
            if (c.bag != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(c.timelineFraction, { c.seekTo(it.toDouble()) }, modifier = Modifier.weight(1f))
                Text(c.timelineText, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    val req = c.editRequest
    if (req != null) key(req, c.closeEditorRequests) {
        SeriesEditorDialog(c.registry, req.series ?: SeriesSpec(), req.series == null, req.topics, req.others, req.allowText, req.fieldHints,
            onDismiss = { c.editRequest = null }, onSave = { c.saveSeries(req, it) })
    }
}

@Composable
private fun Strip(c: PlotController) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val running = c.running
            IconButton(onClick = { c.toggleFollow() }) {
                Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow, tr(if (running) "plot_pause" else "plot_follow"))
            }
            var windows by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { windows = true }) { Text(c.windowText) }
                DropdownMenu(windows, { windows = false }) {
                    for (s in PlotSpecs.WINDOWS_S) DropdownMenuItem({ Text(PlotController.windowLabel(s)) }, { windows = false; c.setWindow(s) })
                }
            }
            var sources by remember { mutableStateOf<List<java.io.File>?>(null) }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { sources = c.sources() }) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Text(" " + c.sourceText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(sources != null, { sources = null }) {
                    DropdownMenuItem({ Text(tr("plot_source_live")) }, { sources = null; c.goLive() })
                    for (f in sources.orEmpty()) DropdownMenuItem({ Text(f.name) }, { sources = null; c.pickSource(f) })
                    if (c.canImportCsv) DropdownMenuItem({ Text(tr("plot_import_csv")) }, { sources = null; c.importCsv() })
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, tr("plot_menu")) }
                DropdownMenu(menu, { menu = false }) {
                    if (c.layout.panels.size < PlotSpecs.MAX_PANELS) DropdownMenuItem({ Text(tr("plot_add_panel")) }, { menu = false; c.addPanel() })
                    DropdownMenuItem({ Text(tr("plot_export_csv")) }, { menu = false; c.exportCsv() })
                    DropdownMenuItem({ Text(tr("plot_stats")) }, { menu = false; c.toggleStats() }, trailingIcon = { if (c.showStats) Icon(Icons.Default.Check, null) })
                    if (c.bag == null) DropdownMenuItem({ Text(tr("plot_clear")) }, { menu = false; c.clear() })
                    DropdownMenuItem({ Text(tr("plot_header_stamp")) }, { menu = false; c.setHeaderStamp(!c.layout.headerStamp) },
                        trailingIcon = { if (c.layout.headerStamp) Icon(Icons.Default.Check, null) })
                }
            }
        }
    }
}

/** Statistics of each series in the panel: over what is visible, or between the two cursors if set. */
@Composable
private fun PanelStats(c: PlotController, p: PanelSpec) {
    @Suppress("UNUSED_VARIABLE") val f = c.frame // recomputed on every frame
    val vp = c.viewport
    val a = vp.cursorNs; val b = vp.cursorBNs
    val (from, to) = if (a != null && b != null) minOf(a, b) to maxOf(a, b) else vp.startNs to vp.endNs
    val buffers = c.buffers()
    Column(Modifier.padding(start = 6.dp, top = 2.dp)) {
        for (s in p.series) {
            val buf = buffers[s.id] ?: continue
            val (t, v) = buf.range(from, to)
            val st = com.juancams.rviz2mini.plot.SeriesStats.of(t, v, from, to)
            Text(
                s.displayLabel + ": " + (st?.let { String.format(java.util.Locale.ROOT, "min %.3g · max %.3g · mean %.3g · σ %.3g · %.1f Hz · %d", it.min, it.max, it.mean, it.std, it.hz, it.count) } ?: "—"),
                fontSize = 11.sp, color = Color(p.colorOf(s)), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PanelHeader(c: PlotController, p: PanelSpec) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (p.kind != PanelKind.TIME) Text(
                when (p.kind) { PanelKind.XY -> "XY"; PanelKind.FFT -> "FFT"; PanelKind.HISTOGRAM -> tr("plot_histogram_chip"); else -> tr("plot_states_chip") },
                fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, end = 4.dp),
            )
            for ((i, s) in p.series.withIndex()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    Text(
                        if (p.kind == PanelKind.XY) (if (i == 0) "x: " else "y: ") + s.displayLabel else s.displayLabel,
                        fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.border(1.dp, Color(p.colorOf(s)), RoundedCornerShape(12.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem({ Text(tr("buttons_menu_edit")) }, { open = false; c.editSeries(p, s) })
                        DropdownMenuItem({ Text(tr("buttons_menu_delete")) }, { open = false; c.removeSeries(p, s) })
                    }
                }
            }
            if (p.series.size < (if (p.kind == PanelKind.XY) 2 else PlotSpecs.MAX_SERIES)) TextButton(onClick = { c.editSeries(p, null) }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(tr(if (p.series.isEmpty()) "plot_add_series" else "plot_add_short"))
            }
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, tr("plot_panel_menu")) }
            DropdownMenu(menu, { menu = false }) {
                @Composable fun kind(k: PanelKind, label: String) { if (p.kind != k) DropdownMenuItem({ Text(tr(label)) }, { menu = false; c.setPanelKind(p, k) }) }
                kind(PanelKind.TIME, "plot_panel_time"); kind(PanelKind.XY, "plot_panel_xy"); kind(PanelKind.STATES, "plot_panel_states")
                kind(PanelKind.FFT, "plot_panel_fft"); kind(PanelKind.HISTOGRAM, "plot_panel_histogram")
                if (p.kind == PanelKind.XY) {
                    DropdownMenuItem({ Text(tr("plot_same_scale")) }, { menu = false; c.toggleSameScale(p) }, trailingIcon = { if (p.sameScale) Icon(Icons.Default.Check, null) })
                    DropdownMenuItem({ Text(tr("plot_scatter")) }, { menu = false; c.toggleScatter(p) }, trailingIcon = { if (p.scatter) Icon(Icons.Default.Check, null) })
                }
                if (c.layout.panels.size > 1) DropdownMenuItem({ Text(tr("plot_remove_panel")) }, { menu = false; c.removePanel(p) })
            }
        }
    }
}

/**
 * A panel's canvas. Gestures (all panels at once, through the shared viewport): pinching or the wheel change the
 * window, dragging pans it (and stops following the latest data), tapping places the cursor, long-pressing places the
 * second cursor (measure between the two) and double-tapping removes them and resumes following.
 */
@Composable
private fun PlotPanelCanvas(c: PlotController, panel: PanelSpec, index: Int, modifier: Modifier) {
    val measurer = rememberTextMeasurer(64)
    val painter = remember(measurer) { PlotPainter(measurer) }
    val colors = PlotColors(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary)
    val haptic = LocalHapticFeedback.current
    val p by rememberUpdatedState(panel)
    val vp = c.viewport
    fun timeAxis() = p.kind == PanelKind.TIME || p.kind == PanelKind.STATES
    fun timeAt(x: Float): Long? {
        if (painter.plotRight <= painter.plotLeft) return null
        val f = ((x - painter.plotLeft) / (painter.plotRight - painter.plotLeft)).coerceIn(0f, 1f)
        return vp.startNs + (f * vp.windowNs).toLong()
    }
    fun zoom(f: Float) {
        if (!f.isFinite() || f <= 0f || f == 1f) return
        val minNs = (PlotSpecs.MIN_WINDOW_S * 1e9).toLong(); val maxNs = (PlotSpecs.MAX_WINDOW_S * 1e9).toLong()
        vp.windowNs = (vp.windowNs / f).toLong().coerceIn(minNs, maxNs)
        c.onViewportChanged()
    }
    Canvas(
        modifier
            .semantics { contentDescription = tr("plot_panel_n", index + 1) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o -> if (timeAxis()) timeAt(o.x)?.let { vp.cursorNs = it; c.onViewportChanged() } },
                    onDoubleTap = { vp.cursorNs = null; vp.cursorBNs = null; vp.follow = true; c.onViewportChanged() },
                    onLongPress = { o ->
                        if (timeAxis()) timeAt(o.x)?.let { t ->
                            vp.cursorBNs = t
                            if (vp.cursorNs == null) vp.cursorNs = t
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            c.onViewportChanged()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    zoom(z)
                    if (pan.x != 0f && p.kind != PanelKind.XY && painter.plotRight > painter.plotLeft) {
                        vp.follow = false
                        vp.endNs -= (pan.x / (painter.plotRight - painter.plotLeft) * vp.windowNs).toLong()
                        c.onViewportChanged()
                    }
                }
            }
            .pointerInput(Unit) {
                // Mouse wheel (desktop): zooms in or out.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll && e.changes.none { it.isConsumed }) {
                            val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) { zoom(exp(-dy * 0.15f)); e.changes.forEach { it.consume() } }
                        }
                    }
                }
            },
    ) {
        c.frame
        painter.draw(this, p, vp, c.buffers(), c.errors(), c.textLabels(), colors)
    }
}
