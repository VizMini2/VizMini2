// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.gps.GpsMath
import com.juancams.rviz2mini.gps.LatLon
import com.juancams.rviz2mini.gps.OsmTiles
import com.juancams.rviz2mini.gps.WebMercator
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.ImageCodecs
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.platform.SystemClock
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.profile
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.common.UiImages
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

const val GPS_KEY_TOPIC = "topic"
const val GPS_KEY_FOLLOW = "follow"
const val GPS_DEFAULT_TOPIC = "/gps/fix"

/**
 * GPS tab (like Foxglove's Map panel): the positions from a sensor_msgs/NavSatFix topic on an OpenStreetMap
 * street map, with the accuracy circle and the track. The map is panned with a finger or the mouse (which stops
 * following), and zoomed with pinch, wheel or double tap.
 */
class GpsController(val tabId: String) {
    private val store = TabStore.get()
    private fun setting(key: String): String? = store.get(tabId)?.settings?.get(key)
    private fun saveSetting(key: String, value: String) = store.updateSetting(tabId, key, value)

    val topic: String get() { topicVersion; return setting(GPS_KEY_TOPIC)?.takeIf { it.isNotBlank() } ?: GPS_DEFAULT_TOPIC }
    private var topicVersion by mutableIntStateOf(0)
    var follow by mutableStateOf(setting(GPS_KEY_FOLLOW) != "false"); private set

    /** Map center and zoom (tile zoom, with decimals). */
    var centerLat by mutableDoubleStateOf(20.0)
    var centerLon by mutableDoubleStateOf(0.0)
    var zoom by mutableDoubleStateOf(3.0)
    private var centered = false

    private var subscription: DynRos.Subscription? = null
    private val latest = AtomicReference<Pair<GpsMath.Fix, Long>?>()
    @Volatile private var received = 0L
    private var shownAt = -1L
    var info by mutableStateOf(""); private set
    var fix by mutableStateOf<GpsMath.Fix?>(null); private set
    /** The track: at least one point every [TRACK_STEP_M], so memory doesn't fill up while standing still. */
    val track = ArrayList<LatLon>()
    var trackVersion by mutableIntStateOf(0); private set
    var choosingTopic by mutableStateOf<List<String>?>(null)
    private var active = false
    private var closed = false

    init { subscribe() }

    fun resume() { if (active || closed) return; active = true; loop() }
    fun pause() { active = false }
    fun close() { closed = true; active = false; subscription?.close(); subscription = null; tiles.close() }

    private fun loop() {
        if (!active || closed) return
        refresh()
        Platform.main.postDelayed({ loop() }, REFRESH_MS)
    }

    private fun subscribe() {
        subscription?.close()
        latest.set(null)
        received = 0
        val spec = Interfaces.get().msg("sensor_msgs/msg/NavSatFix")
        subscription = DynRos.subscribe(DynRos.normalizeName(topic), spec, QosPreset.SENSOR.profile()) { msg ->
            received++
            GpsMath.fixOf(msg)?.let { latest.set(it to SystemClock.uptimeMillis()) }
        }
    }

    fun refresh() {
        val (f, at) = latest.get() ?: run {
            info = tr(if (received > 0) "gps_no_fix" else "gps_waiting", topic)
            return
        }
        val age = (SystemClock.uptimeMillis() - at) / 1000.0
        info = tr("gps_info",
            String.format(Locale.ROOT, "%.7f", f.latitude), String.format(Locale.ROOT, "%.7f", f.longitude),
            if (f.altitude.isFinite()) String.format(Locale.ROOT, "%.1f m", f.altitude) else "—",
            if (f.accuracyM >= 0) String.format(Locale.ROOT, "±%.1f m", f.accuracyM) else "—",
            statusLabel(f.status), String.format(Locale.ROOT, "%.1f", age))
        if (at == shownAt) return
        shownAt = at
        fix = f
        val prev = track.lastOrNull()
        if (prev == null || GpsMath.distance(prev.latitude, prev.longitude, f.latitude, f.longitude) >= TRACK_STEP_M) {
            track += LatLon(f.latitude, f.longitude)
            if (track.size > MAX_TRACK) track.subList(0, track.size - MAX_TRACK).clear()
            trackVersion++
        }
        if (!centered) { zoom = 18.0; centerLat = f.latitude; centerLon = f.longitude; centered = true }
        else if (follow) { centerLat = f.latitude; centerLon = f.longitude }
    }

    private fun statusLabel(status: Int) = tr(when (status) { 1 -> "gps_status_sbas"; 2 -> "gps_status_gbas"; else -> "gps_status_fix" })

    fun changeFollow(on: Boolean) {
        follow = on
        saveSetting(GPS_KEY_FOLLOW, on.toString())
        if (on) fix?.let { centerLat = it.latitude; centerLon = it.longitude }
    }

    fun clearTrack() { track.clear(); trackVersion++ }

    fun pickTopic() {
        RosConnection.discover(waitMs = 300) { g ->
            val found = g?.topics()?.filter { it.type == "sensor_msgs/msg/NavSatFix" }?.map { it.name }.orEmpty()
            Platform.main.post { if (!closed) choosingTopic = found }
        }
    }

    fun setTopic(t: String) {
        val name = DynRos.normalizeName(t)
        if (!Regex("/[A-Za-z0-9_~{}/]*").matches(name) || name == "/") return
        saveSetting(GPS_KEY_TOPIC, name)
        topicVersion++
        clearTrack()
        centered = false
        fix = null
        subscribe()
    }

    /** Pans the map by `dx`, `dy` screen pixels (`tilePx`: the on-screen size of a tile at the integer zoom). */
    fun pan(dx: Float, dy: Float, scalePx: Double) {
        val world = OsmTiles.SIZE * 2.0.pow(zoom) * scalePx
        val x = WebMercator.x(centerLon, world) - dx; val y = WebMercator.y(centerLat, world) - dy
        centerLon = WebMercator.lon(x, world).let { ((it + 540) % 360) - 180 }
        centerLat = WebMercator.lat(y.coerceIn(0.0, world), world)
        if (follow) changeFollow(false)
    }

    /** Zooms in `factor` times keeping the point `(px, py)`, relative to the view center, fixed. */
    fun zoomBy(factor: Double, px: Float, py: Float, scalePx: Double) {
        val nz = (zoom + ln(factor) / ln(2.0)).coerceIn(1.0, OsmTiles.MAX_ZOOM.toDouble())
        if (nz == zoom) return
        val w0 = OsmTiles.SIZE * 2.0.pow(zoom) * scalePx; val w1 = OsmTiles.SIZE * 2.0.pow(nz) * scalePx
        val ax = WebMercator.x(centerLon, w0) + px; val ay = WebMercator.y(centerLat, w0) + py
        val k = w1 / w0
        zoom = nz
        if (!follow) {
            centerLon = WebMercator.lon(ax * k - px, w1)
            centerLat = WebMercator.lat((ay * k - py).coerceIn(0.0, w1), w1)
        }
    }

    val center: LatLon get() = LatLon(centerLat, centerLon)

    // ---------------------------------------------------------------- tiles

    val tiles = TileStore()

    companion object {
        private const val REFRESH_MS = 200L
        private const val TRACK_STEP_M = 0.3
        private const val MAX_TRACK = 20_000
    }
}

/** Already decoded tiles (the last [MAX] seen) and those being downloaded (two at a time). */
class TileStore {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX
    }
    private val pending = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "osm-tiles").apply { isDaemon = true } }
    /** Bumped when a tile arrives (to repaint). */
    var version by mutableIntStateOf(0); private set
    @Volatile private var closed = false

    /** The tile if it is already there; otherwise requests it (and returns null). On the main thread. */
    fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$z/$x/$y"
        cache[key]?.let { return it }
        if (key in pending || (failed[key]?.let { System.currentTimeMillis() - it < RETRY_MS } == true)) return null
        if (pending.size > 24) return null
        pending += key
        io.execute {
            val img = if (closed) null else OsmTiles.load(z, x, y)?.let { b -> ImageCodecs.decode(b, 0, b.size) }?.let { UiImages.fromArgb(it.pixels, it.width, it.height) }
            Platform.main.post {
                pending -= key
                if (img != null) { cache[key] = img; failed.remove(key) } else failed[key] = System.currentTimeMillis()
                version++
            }
        }
        return null
    }

    /** A tile from a lower zoom that covers this one (to avoid gaps while it arrives), with its crop. */
    fun fallback(z: Int, x: Int, y: Int): Triple<ImageBitmap, IntOffset, IntSize>? {
        var dz = 1
        while (dz <= 4 && z - dz >= 0) {
            val k = 1 shl dz
            val img = cache["${z - dz}/${x / k}/${y / k}"]
            if (img != null) {
                val s = OsmTiles.SIZE / k
                return Triple(img, IntOffset((x % k) * s, (y % k) * s), IntSize(s, s))
            }
            dz++
        }
        return null
    }

    fun close() { closed = true; io.shutdownNow() }

    private companion object {
        const val MAX = 160
        const val RETRY_MS = 15_000L
    }
}

/** The tab with its own controller (desktop, dashboards). */
@Composable
fun GpsScreen(tabId: String, modifier: Modifier = Modifier) {
    val c = remember(tabId) { GpsController(tabId) }
    DisposableEffect(c) { c.resume(); onDispose { c.close() } }
    GpsView(c, modifier)
}

@Composable
fun GpsView(c: GpsController, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { c.pickTopic() }, modifier = Modifier.weight(1f)) { Text(c.topic, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                IconButton(onClick = { c.changeFollow(!c.follow) }) {
                    Icon(Icons.Default.CenterFocusStrong, tr("gps_follow"), tint = if (c.follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { c.clearTrack() }) { Icon(Icons.Default.Delete, tr("gps_clear_track"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp).clip(RoundedCornerShape(12.dp))) {
            TileMap(c, Modifier.fillMaxSize())
            Text(c.info, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xD2141418)).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
    c.choosingTopic?.let { found ->
        var text by remember { mutableStateOf(c.topic) }
        AlertDialog(
            onDismissRequest = { c.choosingTopic = null },
            title = { Text(tr("gps_topic_title")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(GPS_DEFAULT_TOPIC) }, modifier = Modifier.fillMaxWidth())
                    if (found.isNotEmpty()) Text(tr("gps_found_topics"), fontSize = 12.sp)
                    for (t in found) TextButton(onClick = { text = t }) { Text(t) }
                }
            },
            confirmButton = { TextButton(onClick = { c.choosingTopic = null; c.setTopic(text) }) { Text(tr("buttons_editor_save")) } },
            dismissButton = { TextButton(onClick = { c.choosingTopic = null }) { Text(tr("buttons_cancel")) } },
        )
    }
}

/** OpenStreetMap tile map with the track, the accuracy circle and the position. */
@Composable
private fun TileMap(c: GpsController, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val tm = rememberTextMeasurer()
    val attribution = TextStyle(fontSize = 10.sp, color = Color(0xFF333333))
    var density by remember { mutableDoubleStateOf(1.0) }
    Canvas(
        modifier
            .background(Color(0xFFE5E3DF))
            .semantics { contentDescription = "map" }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, z, _ ->
                    if (pan != Offset.Zero) c.pan(pan.x, pan.y, density)
                    if (z != 1f) c.zoomBy(z.toDouble(), centroid.x - size.width / 2f, centroid.y - size.height / 2f, density)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { o -> c.zoomBy(2.0, o.x - size.width / 2f, o.y - size.height / 2f, density) }) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll && e.changes.none { it.isConsumed }) {
                            val ch = e.changes.firstOrNull() ?: continue
                            c.zoomBy(exp(-ch.scrollDelta.y * 0.25).toDouble(), ch.position.x - size.width / 2f, ch.position.y - size.height / 2f, density)
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        density = this.density.toDouble()
        c.tiles.version; c.trackVersion
        val zi = floor(c.zoom).toInt().coerceIn(0, OsmTiles.MAX_ZOOM)
        // On-screen tile size: 256 dp at the integer zoom, plus whatever exceeds it.
        val tilePx = OsmTiles.SIZE * this.density * 2.0.pow(c.zoom - zi)
        val n = 1 shl zi
        val worldTiles = 2.0.pow(zi)
        val world = tilePx * worldTiles
        val cx = WebMercator.x(c.centerLon, world); val cy = WebMercator.y(c.centerLat, world)
        val left = cx - size.width / 2; val top = cy - size.height / 2
        val tx0 = floor(left / tilePx).toInt(); val tx1 = floor((left + size.width) / tilePx).toInt()
        val ty0 = floor(top / tilePx).toInt().coerceAtLeast(0); val ty1 = floor((top + size.height) / tilePx).toInt().coerceAtMost(n - 1)
        val ts = tilePx.toFloat()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val wx = Math.floorMod(tx, n)
            val dst = IntOffset((tx * tilePx - left).toInt(), (ty * tilePx - top).toInt())
            val dstSize = IntSize(kotlin.math.ceil(ts).toInt() + 1, kotlin.math.ceil(ts).toInt() + 1)
            val img = c.tiles.get(zi, wx, ty)
            if (img != null) drawImage(img, dstOffset = dst, dstSize = dstSize)
            else c.tiles.fallback(zi, wx, ty)?.let { (fb, src, srcSize) -> drawImage(fb, srcOffset = src, srcSize = srcSize, dstOffset = dst, dstSize = dstSize) }
        }
        fun screen(lat: Double, lon: Double) = Offset((WebMercator.x(lon, world) - left).toFloat(), (WebMercator.y(lat, world) - top).toFloat())
        val d = this.density
        if (c.track.size >= 2) {
            val path = Path()
            for ((i, p) in c.track.withIndex()) { val o = screen(p.latitude, p.longitude); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, accent, style = Stroke(4 * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        c.fix?.let { f ->
            val o = screen(f.latitude, f.longitude)
            if (f.accuracyM > 0) {
                val r = (f.accuracyM / WebMercator.metersPerPixel(f.latitude, world)).toFloat()
                drawCircle(accent.copy(alpha = 50 / 255f), r, o)
                drawCircle(accent.copy(alpha = 140 / 255f), r, o, style = Stroke(d))
            }
            drawCircle(Color.White, 9 * d, o)
            drawCircle(accent, 6 * d, o)
        }
        // Attribution (required by the OpenStreetMap data license).
        val l = tm.measure(OsmTiles.ATTRIBUTION, attribution)
        val at = Offset(size.width - l.size.width - 6 * d, size.height - l.size.height - 4 * d)
        drawRect(Color(0xB3FFFFFF), at - Offset(4 * d, 2 * d), Size(l.size.width + 8 * d, l.size.height + 4 * d))
        drawText(l, topLeft = at)
    }
}
