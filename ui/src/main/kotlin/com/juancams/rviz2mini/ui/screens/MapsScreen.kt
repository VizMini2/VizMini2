// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.nav.MapExport
import com.juancams.rviz2mini.nav.MapGrid
import com.juancams.rviz2mini.nav.MapServices
import com.juancams.rviz2mini.nav.Zones
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.rviz.geom.OccupancyGridImage
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.common.FileDialogs
import com.juancams.rviz2mini.ui.common.UiImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Tab keys: the map topic, the zones and the name to save the map with. */
const val MAPS_KEY_TOPIC = "map_topic"
const val MAPS_KEY_ZONES = "zones"
const val MAPS_KEY_NAME = "map_name"

/** What is visible of each tab (for the tests): the map and the zones. */
object MapsScreens {
    val grids = java.util.concurrent.ConcurrentHashMap<String, MapGrid>()
}

/**
 * Maps (same on the phone and on the PC): the map with zoom, saving it (on the robot with Nav2's map_saver or SLAM
 * Toolbox, or downloading its PGM and YAML), pausing SLAM Toolbox, clearing the costmaps, and drawing keepout and
 * speed limit zones to publish as Nav2 costmap filter masks.
 */
@Composable
fun MapsScreen(tabId: String, modifier: Modifier = Modifier) {
    val store = remember { TabStore.get() }
    fun setting(k: String) = store.get(tabId)?.settings?.get(k)
    val scope = rememberCoroutineScope()
    var topic by remember(tabId) { mutableStateOf(setting(MAPS_KEY_TOPIC)?.ifBlank { null } ?: "/map") }
    var grid by remember(tabId) { mutableStateOf<MapGrid?>(null) }
    var image by remember(tabId) { mutableStateOf<ImageBitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var services by remember { mutableStateOf(MapServices.find(null)) }
    val zones = remember(tabId) { mutableStateListOf<Zones.Zone>().apply { addAll(Zones.parse(setting(MAPS_KEY_ZONES))) } }
    fun saveZones() = store.updateSetting(tabId, MAPS_KEY_ZONES, Zones.format(zones.toList()))

    DisposableEffect(tabId, topic) {
        val sub = runCatching {
            DynRos.subscribe(topic, Interfaces.get().msg("nav_msgs/msg/OccupancyGrid"), BagRecorder.qos(true)) { v ->
                val g = MapGrid.of(v) ?: return@subscribe
                val px = IntArray(g.width * g.height)
                // The image's top row is the map's last one (y up).
                for (r in 0 until g.height) for (c in 0 until g.width) px[(g.height - 1 - r) * g.width + c] = OccupancyGridImage.color(g.data[r * g.width + c].toInt(), "map", 255)
                val img = UiImages.fromArgb(px, g.width, g.height)
                com.juancams.rviz2mini.platform.Platform.main.post { grid = g; image = img; MapsScreens.grids[tabId] = g }
            }
        }.getOrNull()
        onDispose { sub?.close(); MapsScreens.grids.remove(tabId) }
    }
    LaunchedEffect(Unit) { while (true) { services = MapServices.find(RosConnection.graph()); delay(3000) } }

    fun run(what: String, block: () -> Boolean) {
        busy = true; message = tr("controllers_working", what)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { message = it.message; false } }
            busy = false
            message = if (ok) tr("controllers_done", what) else tr("controllers_failed", what)
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TopicRow(topic) { t -> topic = t; grid = null; image = null; store.updateSetting(tabId, MAPS_KEY_TOPIC, t) }
        val g = grid
        if (g == null) {
            Text(tr("maps_waiting", topic), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(tr("maps_info", g.width, g.height, String.format(Locale.ROOT, "%.3f", g.resolution), g.frameId.ifEmpty { "—" }),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val editor = remember { ZoneEditor() }
        MapView(g, image, zones, editor, Modifier.fillMaxWidth().height(340.dp))
        ZoneControls(g, zones, editor, onChanged = { saveZones() })
        if (g != null) {
            SaveCard(tabId, g, services, busy, ::run) { message = it }
            if (services.clearCostmaps.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("maps_costmaps"), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (s in services.clearCostmaps) OutlinedButton(enabled = !busy, onClick = { run(tr("maps_clear_what", costmapName(s))) { MapServices(Interfaces.get()).clearCostmap(s); true } }) {
                            Text(tr("maps_clear", costmapName(s)), fontSize = 12.sp)
                        }
                        if (services.clearCostmaps.size > 1) FilledTonalButton(enabled = !busy, onClick = {
                            run(tr("maps_clear_all")) { services.clearCostmaps.forEach { MapServices(Interfaces.get()).clearCostmap(it) }; true }
                        }) { Text(tr("maps_clear_all"), fontSize = 12.sp) }
                    }
                }
            }
            MaskCard(g, zones, busy) { message = it }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

/** "/global_costmap/clear_entirely_global_costmap" -> "global_costmap". */
private fun costmapName(service: String) = service.trim('/').split('/').dropLast(1).lastOrNull() ?: service

@Composable
private fun TopicRow(topic: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(tr("maps_topic", topic), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(open, { open = false }) {
            val found = RosConnection.graph()?.topicsOfType("nav_msgs/msg/OccupancyGrid").orEmpty().map { it.name }.sorted()
            if (found.isEmpty()) DropdownMenuItem({ Text(tr("maps_no_topics")) }, { open = false })
            for (t in found) DropdownMenuItem({ Text(t, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }, { open = false; onChange(t) })
        }
    }
}

/** The zone being drawn and the view's zoom and pan (shared by the map and its controls). */
private class ZoneEditor {
    var drawing by mutableStateOf<Zones.Kind?>(null)
    val points = mutableStateListOf<Pair<Double, Double>>()
    var value by mutableIntStateOf(50)
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
}

private val KEEPOUT_COLOR = Color(0xFFE53935)
private val SPEED_COLOR = Color(0xFFFFB300)

@Composable
private fun MapView(g: MapGrid?, image: ImageBitmap?, zones: List<Zones.Zone>, e: ZoneEditor, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Screen <-> map: the image fits the view, then zoom and pan.
    fun scale(g: MapGrid) = minOf(size.width / g.width.toFloat(), size.height / g.height.toFloat()) * e.zoom
    fun origin(g: MapGrid): Offset { val s = scale(g); return Offset((size.width - g.width * s) / 2f, (size.height - g.height * s) / 2f) + e.pan }
    fun toScreen(g: MapGrid, p: Pair<Double, Double>): Offset {
        val (col, row) = g.gridPoint(p.first, p.second); val s = scale(g); val o = origin(g)
        return Offset(o.x + col.toFloat() * s, o.y + (g.height - row.toFloat()) * s)
    }
    fun toMap(g: MapGrid, at: Offset): Pair<Double, Double> {
        val s = scale(g); val o = origin(g)
        return g.pointAt(((at.x - o.x) / s).toDouble(), (g.height - (at.y - o.y) / s).toDouble())
    }
    Box(modifier.clipToBounds().background(Color(0xFF808080), RoundedCornerShape(8.dp))
        .semantics { contentDescription = "map view" }
        .onSizeChanged { size = it }
        .pointerInput(g) {
            detectTransformGestures { _, pan, zoom, _ -> e.zoom = (e.zoom * zoom).coerceIn(0.5f, 40f); e.pan += pan }
        }
        .pointerInput(g, e.drawing) {
            detectTapGestures { at -> if (g != null && e.drawing != null) e.points += toMap(g, at) }
        }
        .pointerInput(Unit) {
            // Mouse wheel: zoom (the PC).
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == PointerEventType.Scroll) {
                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        e.zoom = (e.zoom * if (dy < 0) 1.15f else 1 / 1.15f).coerceIn(0.5f, 40f)
                        ev.changes.forEach { it.consume() }
                    }
                }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            if (g == null || image == null) return@Canvas
            val s = scale(g); val o = origin(g)
            drawImage(image, dstOffset = IntOffset(o.x.toInt(), o.y.toInt()), dstSize = IntSize((g.width * s).toInt(), (g.height * s).toInt()), filterQuality = FilterQuality.None)
            fun poly(pts: List<Pair<Double, Double>>, close: Boolean) = Path().apply {
                pts.forEachIndexed { i, p -> val q = toScreen(g, p); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }
                if (close) close()
            }
            for (z in zones) {
                val c = if (z.kind == Zones.Kind.KEEPOUT) KEEPOUT_COLOR else SPEED_COLOR
                val path = poly(z.points, true)
                drawPath(path, c.copy(alpha = 0.35f)); drawPath(path, c, style = Stroke(2f))
            }
            if (e.points.isNotEmpty()) {
                val c = if (e.drawing == Zones.Kind.SPEED) SPEED_COLOR else KEEPOUT_COLOR
                drawPath(poly(e.points, false), c, style = Stroke(3f))
                for (p in e.points) drawCircle(c, 5f, toScreen(g, p))
            }
        }
        if (e.drawing != null) Text(tr("maps_draw_hint"), fontSize = 12.sp, color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        Row(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
            TextButton(onClick = { e.zoom = (e.zoom * 1.5f).coerceAtMost(40f) }) { Text("+", color = Color.White) }
            TextButton(onClick = { e.zoom = (e.zoom / 1.5f).coerceAtLeast(0.5f) }) { Text("−", color = Color.White) }
            TextButton(onClick = { e.zoom = 1f; e.pan = Offset.Zero }) { Text(tr("maps_fit"), color = Color.White, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ZoneControls(g: MapGrid?, zones: MutableList<Zones.Zone>, e: ZoneEditor, onChanged: () -> Unit) {
    val drawing = e.drawing
    if (drawing == null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = g != null, onClick = { e.drawing = Zones.Kind.KEEPOUT; e.points.clear() }) { Text(tr("maps_draw_keepout"), fontSize = 12.sp) }
            OutlinedButton(enabled = g != null, onClick = { e.drawing = Zones.Kind.SPEED; e.points.clear() }) { Text(tr("maps_draw_speed"), fontSize = 12.sp) }
        }
    } else {
        if (drawing == Zones.Kind.SPEED) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("maps_speed_value", e.value), fontSize = 12.sp, modifier = Modifier.width(150.dp))
            Slider(value = e.value.toFloat(), valueRange = 0f..100f, steps = 19, onValueChange = { e.value = Math.round(it / 5f) * 5 }, modifier = Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = e.points.size >= 3, onClick = {
                zones += Zones.Zone(drawing, e.points.toList(), if (drawing == Zones.Kind.KEEPOUT) 100 else e.value)
                e.points.clear(); e.drawing = null; onChanged()
            }) { Text(tr("maps_finish_zone"), fontSize = 12.sp) }
            OutlinedButton(enabled = e.points.isNotEmpty(), onClick = { e.points.removeAt(e.points.size - 1) }) { Text(tr("maps_undo_point"), fontSize = 12.sp) }
            TextButton(onClick = { e.points.clear(); e.drawing = null }) { Text(tr("controllers_cancel"), fontSize = 12.sp) }
        }
    }
    zones.forEachIndexed { i, z ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(10.dp).height(10.dp).background(if (z.kind == Zones.Kind.KEEPOUT) KEEPOUT_COLOR else SPEED_COLOR, RoundedCornerShape(2.dp)))
            Text(if (z.kind == Zones.Kind.KEEPOUT) tr("maps_zone_keepout") else tr("maps_zone_speed_value", z.value),
                fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp).weight(1f))
            TextButton(onClick = { zones.removeAt(i); onChanged() }) { Text(tr("maps_delete_zone"), fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SaveCard(tabId: String, g: MapGrid, services: MapServices.Found, busy: Boolean, run: (String, () -> Boolean) -> Unit, onMessage: (String) -> Unit) {
    val store = remember { TabStore.get() }
    var name by remember(tabId) { mutableStateOf(store.get(tabId)?.settings?.get(MAPS_KEY_NAME) ?: "map") }
    var paused by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("maps_save_title"), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(name, { name = it.trim(); store.updateSetting(tabId, MAPS_KEY_NAME, name) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("maps_name")) })
            val clean = name.ifBlank { "map" }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FileDialogs.saveFiles?.let { save ->
                    FilledTonalButton(onClick = {
                        val base = clean.substringAfterLast('/')
                        save(MapExport.files(base, g)) { where -> onMessage(if (where != null) tr("maps_downloaded", where) else tr("maps_not_downloaded")) }
                    }) { Text(tr("maps_download"), fontSize = 12.sp) }
                }
                for (s in services.mapSaver) OutlinedButton(enabled = !busy, onClick = { run(tr("maps_save_robot_what", clean)) { MapServices(Interfaces.get()).saveWithMapSaver(s, clean, "/map") } }) {
                    Text(tr("maps_save_robot"), fontSize = 12.sp)
                }
                for (s in services.slamSave) OutlinedButton(enabled = !busy, onClick = { run(tr("maps_slam_save_what", clean)) { MapServices(Interfaces.get()).slamSave(s, clean) } }) {
                    Text(tr("maps_slam_save"), fontSize = 12.sp)
                }
                for (s in services.slamSerialize) OutlinedButton(enabled = !busy, onClick = { run(tr("maps_slam_serialize_what", clean)) { MapServices(Interfaces.get()).slamSerialize(s, clean) } }) {
                    Text(tr("maps_slam_serialize"), fontSize = 12.sp)
                }
                for (s in services.slamPause) OutlinedButton(enabled = !busy, onClick = {
                    run(tr(if (paused) "maps_slam_resume" else "maps_slam_pause")) { MapServices(Interfaces.get()).slamTogglePause(s).also { if (it) paused = !paused } }
                }) { Text(tr(if (paused) "maps_slam_resume" else "maps_slam_pause"), fontSize = 12.sp) }
            }
            if (services.mapSaver.isEmpty() && services.slamSave.isEmpty())
                Text(tr("maps_no_saver"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Publishing the zones as Nav2 filter masks (with their filter info, optionally) or downloading them. */
@Composable
private fun MaskCard(g: MapGrid, zones: List<Zones.Zone>, busy: Boolean, onMessage: (String) -> Unit) {
    if (zones.isEmpty()) return
    val publishers = remember { HashMap<String, DynRos.Publisher>() }
    DisposableEffect(Unit) { onDispose { publishers.values.forEach { runCatching { it.close() } } } }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("maps_masks_title"), style = MaterialTheme.typography.titleSmall)
            for (kind in Zones.Kind.entries.filter { k -> zones.any { it.kind == k } }) {
                var maskTopic by remember(kind) { mutableStateOf(kind.defaultTopic) }
                var infoTopic by remember(kind) { mutableStateOf(if (kind == Zones.Kind.KEEPOUT) "/costmap_filter_info" else "/speed_filter_info") }
                var withInfo by remember(kind) { mutableStateOf(true) }
                Text(tr(kind.labelKey), fontSize = 13.sp)
                OutlinedTextField(maskTopic, { maskTopic = it.trim() }, singleLine = true, label = { Text(tr("maps_mask_topic")) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.clickable { withInfo = !withInfo }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withInfo, { withInfo = it })
                    Text(tr("maps_publish_info"), fontSize = 12.sp)
                }
                if (withInfo) OutlinedTextField(infoTopic, { infoTopic = it.trim() }, singleLine = true, label = { Text(tr("maps_info_topic")) }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = !busy, onClick = {
                        val ok = runCatching {
                            val reg = Interfaces.get()
                            val mask = Zones.mask(g, zones, kind)
                            val spec = reg.msg("nav_msgs/msg/OccupancyGrid")
                            // Latched (transient local), as map_server publishes masks: the costmap gets it whenever it starts.
                            val pub = publishers.getOrPut("mask$maskTopic") { DynRos.publisher(maskTopic, spec, BagRecorder.qos(true)) }
                            pub.publish(DynCodec.fromJson(spec, Zones.maskMessage(g, mask, System.currentTimeMillis() * 1_000_000L)))
                            if (withInfo) {
                                val ispec = reg.msg("nav2_msgs/msg/CostmapFilterInfo")
                                val ipub = publishers.getOrPut("info$infoTopic") { DynRos.publisher(infoTopic, ispec, BagRecorder.qos(true)) }
                                ipub.publish(DynCodec.fromJson(ispec, Zones.filterInfo(kind, maskTopic, g.frameId)))
                            }
                        }.isSuccess
                        onMessage(if (ok) tr("maps_mask_published", maskTopic) else tr("controllers_no_connection"))
                    }, modifier = Modifier.semantics { contentDescription = "publish ${kind.name}" }) { Text(tr("maps_publish_mask"), fontSize = 12.sp) }
                    FileDialogs.saveFiles?.let { save ->
                        OutlinedButton(onClick = {
                            val mask = Zones.mask(g, zones, kind)
                            val base = if (kind == Zones.Kind.KEEPOUT) "keepout_mask" else "speed_mask"
                            val pgm = if (kind == Zones.Kind.KEEPOUT) MapExport.pgm(MapGrid(g.width, g.height, g.resolution, g.originX, g.originY, g.originYaw, g.frameId, mask))
                                else MapExport.scalePgm(g.width, g.height, mask)
                            val yaml = if (kind == Zones.Kind.KEEPOUT) MapExport.yaml("$base.pgm", g) else MapExport.yaml("$base.pgm", g, mode = "scale", freeThresh = 0.0, occupiedThresh = 1.0)
                            save(listOf("$base.pgm" to pgm, "$base.yaml" to yaml.toByteArray())) { where -> onMessage(if (where != null) tr("maps_downloaded", where) else tr("maps_not_downloaded")) }
                        }) { Text(tr("maps_download_mask"), fontSize = 12.sp) }
                    }
                }
            }
            Text(tr("maps_masks_hint"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
