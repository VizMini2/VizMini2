// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.drawText
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.camera.CameraFrameInfo
import com.juancams.rviz2mini.camera.FrameStream
import com.juancams.rviz2mini.camera.ImageKind
import com.juancams.rviz2mini.camera.ImageTopic
import com.juancams.rviz2mini.camera.ImageTopics
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.common.UiImages
import com.juancams.rviz2mini.ui.common.rememberRosStatus
import java.util.Locale

/** Keys of the Camera tab (the same as before on Android). */
const val CAMERA_KEY_TOPICS = "topics"
const val CAMERA_KEY_TOPIC = "topic"
const val CAMERA_KEY_KIND = "kind"
const val CAMERA_KEY_CUSTOM = "customTopics"
const val CAMERA_MAX = 4
/** "false": the annotations of the images are hidden. */
const val CAMERA_KEY_ANNOTATIONS = "annotations"

/** What is seen of each camera (for the tests): its topic, whether there is an image yet and where it is in the window. */
data class CameraTileState(val topic: String, val hasImage: Boolean, val bounds: Rect?)

/**
 * Camera tab: up to four cameras at once (one fills everything, two are stacked, three or four in 2x2), search the
 * network's image topics or add one by hand. `compact` (in a dashboard): the images fill everything and the
 * list opens with a button.
 */
@Composable
fun CameraScreen(tabId: String, modifier: Modifier = Modifier, compact: Boolean = false, onTiles: ((() -> List<CameraTileState>)?) -> Unit = {}) {
    val store = TabStore.get()
    fun setting(k: String) = store.get(tabId)?.settings?.get(k)
    val selected = remember(tabId) { mutableStateListOf<ImageTopic>().apply { addAll(loadSelected(setting(CAMERA_KEY_TOPICS), setting(CAMERA_KEY_TOPIC), setting(CAMERA_KEY_KIND))) } }
    val topics = remember(tabId) { mutableStateListOf<ImageTopic>().apply { addAll(selected) } }
    var searching by remember { mutableStateOf(false) }
    var searchStatus by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var searchedOnce by remember { mutableStateOf(false) }
    val frames = remember { mutableStateMapOf<ImageTopic, Pair<ImageBitmap, CameraFrameInfo>>() }
    val errors = remember { mutableStateMapOf<ImageTopic, String>() }
    val bounds = remember { mutableStateMapOf<ImageTopic, Rect>() }
    val status = rememberRosStatus()

    DisposableEffect(Unit) {
        onTiles { selected.map { CameraTileState(it.name, frames[it] != null, bounds[it]) } }
        onDispose { onTiles(null) }
    }
    fun save() = store.updateSetting(tabId, CAMERA_KEY_TOPICS, selected.joinToString("\n") { "${it.kind.name}\t${it.name}" })
    fun merge(found: List<ImageTopic>, replace: Boolean) {
        val all = LinkedHashSet<ImageTopic>()
        if (!replace) all.addAll(topics)
        all.addAll(found); all.addAll(selected)
        topics.clear(); topics.addAll(all.sortedWith(compareBy({ it.name }, { it.kind })))
    }
    fun customTopics() = setting(CAMERA_KEY_CUSTOM)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
    fun search() {
        if (searching) return
        if (RosConnection.status !is RosConnection.Status.Connected) { searchStatus = tr("camera_ros_not_connected"); return }
        searching = true; searchedOnce = true; searchStatus = tr("camera_searching")
        RosConnection.discover { graph ->
            searching = false
            // Without ROS hidden ones (a segment starting with "_", like the internal .../_buf_cpu).
            val found = graph?.topicsOfType(*ImageKind.rosTypes).orEmpty()
                .filter { t -> t.name.split('/').none { it.startsWith("_") } }.flatMap { t ->
                t.types.mapNotNull { type -> ImageKind.entries.firstOrNull { it.rosType == type }?.let { ImageTopic(t.name, it) } }
            }
            merge(found, replace = true)
            searchStatus = if (found.isEmpty()) tr("camera_no_topics_found") else tr("camera_found_topics", found.size)
        }
    }
    LaunchedEffect(status) { if (status is RosConnection.Status.Connected && !searchedOnce) search() }

    // One subscription per selected camera, recreated when the selection changes.
    DisposableEffect(selected.toList()) {
        val streams = selected.map { t ->
            FrameStream(t, { img, info -> if (!com.juancams.rviz2mini.LiveView.frozen) { frames[t] = UiImages.fromArgb(img.pixels, img.width, img.height) to info; errors.remove(t) } }, { errors[t] = it }).also { it.start() }
        }
        onDispose { streams.forEach { it.stop() } }
    }

    // The annotations of each image (ImageMarker or foxglove ImageAnnotations in its namespace), found in the graph.
    var showAnnotations by remember(tabId) { mutableStateOf(setting(CAMERA_KEY_ANNOTATIONS) != "false") }
    val annotations = remember { mutableStateMapOf<ImageTopic, com.juancams.rviz2mini.camera.ImageAnnotationSource>() }
    val graph = com.juancams.rviz2mini.ui.common.rememberGraph(3000)
    val found = selected.associateWith { t -> graph?.topics(includeHidden = true)?.associate { it.name to it.types }?.let { com.juancams.rviz2mini.camera.ImageAnnotationTopics.find(t.name, it) } }
    DisposableEffect(found.toList(), showAnnotations) {
        val opened = found.mapNotNull { (t, topic) -> topic?.takeIf { showAnnotations }?.let { t to com.juancams.rviz2mini.camera.ImageAnnotationSource(it) } }
        annotations.clear(); annotations.putAll(opened)
        onDispose { opened.forEach { it.second.close() }; annotations.clear() }
    }
    fun toggleAnnotations() { showAnnotations = !showAnnotations; store.updateSetting(tabId, CAMERA_KEY_ANNOTATIONS, showAnnotations.toString()) }

    fun toggle(t: ImageTopic) {
        if (!selected.remove(t)) {
            if (selected.size >= CAMERA_MAX) { searchStatus = tr("camera_max", CAMERA_MAX); return }
            selected.add(t)
        }
        frames.remove(t); save()
    }

    fun addManual() {
        val name = ImageTopics.normalize(manual)
        if (!ImageTopics.isValidName(name)) { manualError = true; return }
        manual = ""
        val custom = LinkedHashSet(customTopics())
        if (custom.add(name)) store.updateSetting(tabId, CAMERA_KEY_CUSTOM, custom.joinToString("\n"))
        if (RosConnection.status !is RosConnection.Status.Connected) { searchStatus = tr("camera_saved_pending"); return }
        RosConnection.discover { g ->
            val types = g?.topics()?.firstOrNull { it.name == name }?.types.orEmpty()
            val found = types.mapNotNull { type -> ImageKind.entries.firstOrNull { it.rosType == type }?.let { ImageTopic(name, it) } }
                .ifEmpty { listOf(ImageTopic(name, ImageKind.RAW)) }
            merge(found, replace = false)
            found.firstOrNull()?.let { if (it !in selected) toggle(it) }
        }
    }

    /** Search, the topic list (with add-by-hand at the end, inside the scroll). */
    @Composable
    fun Picker(modifier: Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("camera_image_topics"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            FilledTonalButton(enabled = !searching, onClick = { search() }) { Text(tr("camera_search")) }
        }
        if (searchStatus.isNotEmpty()) Text(searchStatus, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(topics.toList(), key = { it.kind.name + it.name }) { t ->
                val on = t in selected
                Column(Modifier.fillMaxWidth().background(if (on) Color(0x332196F3) else Color.Transparent).clickable { toggle(t) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(t.name, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontFamily = FontFamily.Monospace)
                    Text(t.kind.rosType + if (on) "  ·  " + tr("camera_showing") else "", style = MaterialTheme.typography.bodySmall)
                }
            }
            item(key = "manual") {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(manual, { manual = it; manualError = false }, singleLine = true, isError = manualError,
                        label = { Text(tr("camera_add_topic_hint")) }, modifier = Modifier.weight(1f),
                        supportingText = if (manualError) { { Text(tr("camera_invalid_topic")) } } else null)
                    Button(onClick = { addManual() }) { Text(tr("camera_add")) }
                }
            }
        }
    }

    Column(modifier.fillMaxSize().padding(if (compact) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().weight(if (compact) 1f else 1.2f).clip(RoundedCornerShape(if (compact) 0.dp else 20.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (selected.isEmpty()) {
                if (compact) TextButton(onClick = { pickerOpen = true }) { Text(tr("camera_choose_topics"), color = Color(0xFFCCCCCC)) }
                else Text(tr("camera_select_topic"), color = Color(0xFFAAAAAA), modifier = Modifier.padding(24.dp))
            } else CameraGrid(selected.toList(), frames, errors, compact, annotations, found.filterValues { it != null }.keys, showAnnotations, ::toggleAnnotations) { t, r -> bounds[t] = r }
            if (compact) IconButton(onClick = { pickerOpen = true }, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp)
                .background(Color(0x99000000), RoundedCornerShape(50))) { Icon(Icons.Default.Tune, tr("camera_choose_topics"), tint = Color.White) }
        }
        // In the tab, the list below; in a (small) widget, in a normal-size dialog.
        if (!compact) Picker(Modifier.fillMaxWidth().weight(1f))
    }
    if (compact && pickerOpen) AlertDialog(
        onDismissRequest = { pickerOpen = false },
        text = { Picker(Modifier.fillMaxWidth().heightIn(max = 520.dp)) },
        confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text(tr("camera_back_to_images")) } },
    )
}

/** A single camera fills everything; two are stacked; three or four go in 2x2. */
@Composable
private fun CameraGrid(
    selected: List<ImageTopic>, frames: Map<ImageTopic, Pair<ImageBitmap, CameraFrameInfo>>, errors: Map<ImageTopic, String>,
    compact: Boolean, annotations: Map<ImageTopic, com.juancams.rviz2mini.camera.ImageAnnotationSource>, withAnnotations: Set<ImageTopic>,
    showAnnotations: Boolean, onToggleAnnotations: () -> Unit, onBounds: (ImageTopic, Rect) -> Unit,
) {
    val columns = if (selected.size >= 3) 2 else 1
    Column(Modifier.fillMaxSize()) {
        for (row in selected.chunked(columns)) Row(Modifier.fillMaxWidth().weight(1f)) {
            for (t in row) Box(Modifier.weight(1f).fillMaxSize().padding(1.dp).onGloballyPositioned { onBounds(t, it.boundsInWindow()) }, contentAlignment = Alignment.Center) {
                val f = frames[t]
                if (f == null) Text(errors[t] ?: tr("camera_waiting_images", t.name), color = Color(0xFFAAAAAA), modifier = Modifier.padding(12.dp))
                else {
                    Image(f.first, contentDescription = t.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    annotations[t]?.let { a -> AnnotationsOverlay(a.state.shapes(com.juancams.rviz2mini.camera.ImageAnnotationSource.nowMs()), f.first.width, f.first.height) }
                    if (t in withAnnotations) IconButton(onClick = onToggleAnnotations, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp)
                        .background(Color(0x99000000), RoundedCornerShape(50))) {
                        Icon(if (showAnnotations) Icons.Default.Visibility else Icons.Default.VisibilityOff, tr("camera_annotations"), tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    val info = f.second
                    Text(
                        String.format(Locale.ROOT, tr("camera_frame_info"), t.name, info.width, info.height, info.encoding, info.fps),
                        color = Color.White, fontSize = if (compact) 10.sp else 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(if (compact) Alignment.BottomStart else Alignment.TopStart).padding(8.dp).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            if (row.size < columns) Box(Modifier.weight(1f))
        }
    }
}

/** Annotations drawn on top of an image shown with ContentScale.Fit (in image pixels). */
@Composable
private fun AnnotationsOverlay(shapes: List<com.juancams.rviz2mini.camera.ImageShape>, imageW: Int, imageH: Int) {
    if (shapes.isEmpty()) return
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val k = minOf(size.width / imageW, size.height / imageH)
        val ox = (size.width - imageW * k) / 2; val oy = (size.height - imageH * k) / 2
        fun p(x: Double, y: Double) = androidx.compose.ui.geometry.Offset(ox + x.toFloat() * k, oy + y.toFloat() * k)
        fun c(argb: Int) = Color(argb)
        for (s in shapes) when (s) {
            is com.juancams.rviz2mini.camera.ImageShape.Circle -> {
                if (s.fill ushr 24 != 0) drawCircle(c(s.fill), s.radius.toFloat() * k, p(s.x, s.y))
                if (s.outline ushr 24 != 0) drawCircle(c(s.outline), s.radius.toFloat() * k, p(s.x, s.y), style = androidx.compose.ui.graphics.drawscope.Stroke(maxOf(1f, s.thickness.toFloat() * k)))
            }
            is com.juancams.rviz2mini.camera.ImageShape.Points -> {
                val n = s.xy.size / 2
                val pts = (0 until n).map { p(s.xy[it * 2], s.xy[it * 2 + 1]) }
                val w = maxOf(1f, s.thickness.toFloat() * k)
                val color = c(if (s.outline ushr 24 != 0) s.outline else s.colors.firstOrNull() ?: 0xFFFFFFFF.toInt())
                when (s.kind) {
                    com.juancams.rviz2mini.camera.ImageShape.Points.Kind.POINTS -> pts.forEachIndexed { i, q -> drawCircle(s.colors.getOrNull(i)?.let { c(it) } ?: color, w, q) }
                    com.juancams.rviz2mini.camera.ImageShape.Points.Kind.LIST -> for (i in 0 until n / 2) drawLine(color, pts[i * 2], pts[i * 2 + 1], w)
                    else -> {
                        val path = androidx.compose.ui.graphics.Path().apply { pts.firstOrNull()?.let { moveTo(it.x, it.y) }; pts.drop(1).forEach { lineTo(it.x, it.y) }
                            if (s.kind == com.juancams.rviz2mini.camera.ImageShape.Points.Kind.LOOP) close() }
                        if (s.kind == com.juancams.rviz2mini.camera.ImageShape.Points.Kind.LOOP && s.fill ushr 24 != 0) drawPath(path, c(s.fill))
                        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                    }
                }
            }
            is com.juancams.rviz2mini.camera.ImageShape.Text -> {
                val layout = measurer.measure(s.text, androidx.compose.ui.text.TextStyle(color = c(s.color), fontSize = androidx.compose.ui.unit.TextUnit(maxOf(8f, s.size.toFloat() * k) / density, androidx.compose.ui.unit.TextUnitType.Sp)))
                val at = p(s.x, s.y)
                if (s.background ushr 24 != 0) drawRect(c(s.background), at, androidx.compose.ui.geometry.Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
                drawText(layout, topLeft = at)
            }
        }
    }
}

private fun loadSelected(topics: String?, topic: String?, kind: String?): List<ImageTopic> {
    topics?.let { saved ->
        return saved.split("\n").mapNotNull { line ->
            val p = line.split('\t')
            if (p.size == 2) ImageKind.fromName(p[0])?.let { ImageTopic(p[1], it) } else null
        }.distinct().take(CAMERA_MAX)
    }
    val name = topic ?: return emptyList()
    val k = ImageKind.fromName(kind) ?: return emptyList()
    return listOf(ImageTopic(name, k))
}
