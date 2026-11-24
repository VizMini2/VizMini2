// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.graph.GraphNode
import com.juancams.rviz2mini.graph.NodeGraphLayout
import com.juancams.rviz2mini.graph.TfEdge
import com.juancams.rviz2mini.graph.TfTreeLayout
import com.juancams.rviz2mini.graph.toGraphNodes
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pan and zoom of a canvas (in screen pixels: screen = content * scale + offset). */
private class PanZoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var fitted = false

    fun toContent(p: Offset) = (p - offset) / scale

    /** Fits the content (w x h) into the view. */
    fun fit(view: IntSize, w: Float, h: Float) {
        if (w <= 0 || h <= 0 || view.width == 0) return
        scale = min(view.width / w, view.height / h).coerceIn(0.05f, 1.5f) * 0.95f
        offset = Offset((view.width - w * scale) / 2, (view.height - h * scale) / 2)
        fitted = true
    }

    fun zoom(factor: Float, around: Offset) {
        val s = (scale * factor).coerceIn(0.05f, 6f)
        offset = around - (around - offset) * (s / scale)
        scale = s
    }
}

/** Canvas with zoom (pinch or mouse wheel), panning, tap and double tap to fit. */
@Composable
internal fun PanZoomCanvas(
    contentW: Float, contentH: Float, modifier: Modifier, onTap: (Offset) -> Unit, fitRequests: Int = 0, draw: DrawScope.(scale: Float) -> Unit,
) {
    val pz = remember { PanZoom() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(contentW, contentH, size) { if (!pz.fitted) pz.fit(size, contentW, contentH) }
    // Every time it increments, fit (the "fit" button of the caller).
    LaunchedEffect(fitRequests) { if (fitRequests > 0) pz.fit(size, contentW, contentH) }
    Canvas(
        modifier.fillMaxSize()
            .pointerInput(Unit) { detectTransformGestures { centroid, pan, zoom, _ -> pz.offset += pan; if (zoom != 1f) pz.zoom(zoom, centroid) } }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { pz.fit(size, contentW, contentH) }, onTap = { onTap(pz.toContent(it)) }) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll && e.changes.none { it.isConsumed }) {
                            val dy = e.changes.first().scrollDelta.y
                            pz.zoom(if (dy > 0) 0.9f else 1.1f, e.changes.first().position)
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        size = IntSize(this.size.width.toInt(), this.size.height.toInt())
        withTransform({ translate(pz.offset.x, pz.offset.y); scale(pz.scale, pz.scale, Offset.Zero) }) { draw(pz.scale) }
    }
}

internal fun DrawScope.label(tm: TextMeasurer, text: String, cx: Float, cy: Float, style: TextStyle) {
    val r = tm.measure(text, style)
    drawText(r, topLeft = Offset(cx - r.size.width / 2f, cy - r.size.height / 2f))
}

/**
 * Graph of nodes and topics (like rqt_graph): nodes as boxes, topics as pills and edges with arrows. Tapping
 * one highlights it with its connections; tapping a node again opens it (`onOpenNode`). rqt_graph filter and options.
 */
@Composable
fun NodeGraphScreen(modifier: Modifier = Modifier, onOpenNode: ((String) -> Unit)? = null) {
    var options by remember { mutableStateOf(NodeGraphLayout.Options()) }
    var nodes by remember { mutableStateOf<List<GraphNode>>(emptyList()) }
    var selected by remember { mutableStateOf<Pair<NodeGraphLayout.Kind, String>?>(null) }
    var info by remember { mutableStateOf(tr("graph_nodes_help")) }
    LaunchedEffect(Unit) {
        while (true) {
            nodes = withContext(Dispatchers.Default) { RosConnection.graph()?.toGraphNodes().orEmpty() }
            delay(2000)
        }
    }
    val tm = rememberTextMeasurer()
    val nodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    val topicStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    val layout = remember(nodes, options) {
        NodeGraphLayout(measure = { max(tm.measure(it, nodeStyle).size.width.toFloat(), tm.measure(it, topicStyle).size.width.toFloat()) + 30f },
            nodeHeight = 40f, topicHeight = 32f, padX = 12f, layerGap = 90f, vGap = 14f).layout(nodes, options)
    }
    val primary = MaterialColors.primary()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.outline

    Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(options.filter, { options = options.copy(filter = it) }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
            label = { Text(tr("graph_filter_hint")) }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(options.hideDebug, { options = options.copy(hideDebug = !options.hideDebug) }, label = { Text(tr("graph_hide_debug")) })
            FilterChip(options.hideLeafTopics, { options = options.copy(hideLeafTopics = !options.hideLeafTopics) }, label = { Text(tr("graph_hide_dead_ends")) })
        }
        Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val sel = selected?.let { (k, n) -> layout.indexOf(k, n) } ?: -1
        val related = BooleanArray(layout.vertices.size).also { r ->
            if (sel >= 0) { r[sel] = true; for (e in layout.edges) { if (e.from == sel) r[e.to] = true; if (e.to == sel) r[e.from] = true } }
        }
        PanZoomCanvas(layout.width, layout.height, Modifier.weight(1f), onTap = { p ->
            val i = layout.vertices.indexOfLast { it.contains(p.x, p.y) }
            val v = layout.vertices.getOrNull(i)
            if (v == null) { selected = null; return@PanZoomCanvas }
            if (v.kind == NodeGraphLayout.Kind.NODE && selected == (v.kind to v.name)) onOpenNode?.invoke(v.name)
            selected = v.kind to v.name
            info = if (v.kind == NodeGraphLayout.Kind.NODE) tr("graph_node_selected", v.name) else tr("graph_topic_selected", v.name)
        }) { scale ->
            val vs = layout.vertices
            val hasSel = sel >= 0
            for (pass in 0..1) for (e in layout.edges) {
                val hot = hasSel && (e.from == sel || e.to == sel)
                if ((pass == 1) != hot) continue
                val a = vs[e.from]; val b = vs[e.to]
                val col = if (hot) primary else onSurface.copy(alpha = if (hasSel) 0.13f else 0.5f)
                val ah = 8f; val aw = 4f
                val x0 = a.right; val y0 = a.cy; val x1 = b.x - ah; val y1 = b.cy
                val d = max(40f, abs(x1 - x0) / 2)
                drawPath(Path().apply { moveTo(x0, y0); cubicTo(x0 + d, y0, x1 - d, y1, x1, y1) }, col, style = Stroke(if (hot) 2.5f else 1.4f))
                drawPath(Path().apply { moveTo(b.x, y1); lineTo(x1, y1 - aw); lineTo(x1, y1 + aw); close() }, col)
            }
            val showText = 11f * scale >= 5f
            for ((i, v) in vs.withIndex()) {
                val alpha = if (!hasSel || related[i]) 1f else 0.3f
                val topic = v.kind == NodeGraphLayout.Kind.TOPIC
                val r = if (topic) v.h / 2 else 8f
                drawRoundRect(if (topic) primary.copy(alpha = 0.15f * alpha) else surfaceHigh.copy(alpha = alpha), Offset(v.x, v.y), Size(v.w, v.h), CornerRadius(r))
                val border = when { i == sel || v.isLifecycle -> primary; topic -> primary.copy(alpha = 0.63f); else -> outline }
                drawRoundRect(border.copy(alpha = alpha), Offset(v.x, v.y), Size(v.w, v.h), CornerRadius(r), style = Stroke(if (i == sel) 3f else if (v.isLifecycle) 2f else 1.2f))
                if (showText) label(tm, if (v.isLifecycle) v.name + "  LC" else v.name, v.cx, v.cy,
                    (if (topic) topicStyle else nodeStyle).copy(color = onSurface.copy(alpha = alpha)))
            }
        }
    }
}

/** Top-down TF tree (like rqt_tf_tree): static frames with a dashed border, dynamic ones tinted (orange if stale). */
@Composable
fun TfTreeScreen(modifier: Modifier = Modifier) {
    var edges by remember { mutableStateOf<List<TfEdge>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf(tr("graph_tf_help")) }
    LaunchedEffect(Unit) {
        while (true) {
            edges = RosConnection.tfTree.snapshot().map { TfEdge(it.id, it.parent, it.isStatic, it.ageSec) }
            delay(2000)
        }
    }
    val tm = rememberTextMeasurer()
    val nameStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    val subStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val layout = remember(edges) {
        TfTreeLayout(measure = { max(tm.measure(it, nameStyle).size.width.toFloat(), 90f) + 24f }, boxHeight = 46f, minBoxWidth = 64f,
            hGap = 16f, vGap = 40f, treeGap = 40f).layout(edges)
    }
    val primary = MaterialColors.primary()
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.outline
    Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PanZoomCanvas(layout.width, layout.height, Modifier.weight(1f), onTap = { p ->
            val b = layout.boxes.lastOrNull { it.contains(p.x, p.y) } ?: run { selected = null; return@PanZoomCanvas }
            selected = b.id
            info = tr("graph_frame_info", b.id, b.parent ?: tr("graph_root"),
                if (b.isStatic) tr("graph_static") else b.ageSec?.let { tr("graph_updated_ago", it) } ?: tr("graph_no_data"))
        }) { scale ->
            for (b in layout.boxes) {
                val p = layout.byId[b.parent ?: continue] ?: continue
                val ah = 8f; val aw = 4.5f
                val x0 = p.cx; val y0 = p.bottom; val x1 = b.cx; val y1 = b.y - ah; val my = (y0 + y1) / 2
                drawPath(Path().apply { moveTo(x0, y0); cubicTo(x0, my, x1, my, x1, y1) }, outline, style = Stroke(1.5f))
                drawPath(Path().apply { moveTo(x1, b.y); lineTo(x1 - aw, y1); lineTo(x1 + aw, y1); close() }, outline)
            }
            for (b in layout.boxes) {
                val stale = !b.isStatic && (b.ageSec ?: 0.0) > STALE_AFTER_SEC
                val accent = if (stale) StatusColors.warn else primary
                drawRoundRect(if (b.isStatic) surfaceHigh else accent.copy(alpha = 0.19f), Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(10f))
                drawRoundRect(if (b.isStatic && b.id != selected) outline else accent, Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(10f),
                    style = Stroke(if (b.id == selected) 3f else 1.5f, pathEffect = if (b.isStatic && b.id != selected) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null))
                if (12f * scale < 5f) continue
                val sub = if (b.isStatic) tr("graph_static") else b.ageSec?.let { String.format(java.util.Locale.ROOT, "%.1f s", it) }
                if (sub == null) label(tm, b.id, b.cx, b.y + b.h / 2, nameStyle)
                else {
                    label(tm, b.id, b.cx, b.y + b.h * 0.38f, nameStyle)
                    label(tm, sub, b.cx, b.y + b.h * 0.72f, if (stale) subStyle.copy(color = StatusColors.warn) else subStyle)
                }
            }
        }
    }
}

private const val STALE_AFTER_SEC = 5.0

/** The theme's primary color (shortcut). */
private object MaterialColors {
    @Composable fun primary(): Color = MaterialTheme.colorScheme.primary
}
