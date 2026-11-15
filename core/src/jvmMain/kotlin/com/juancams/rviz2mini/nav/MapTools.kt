// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.nav

import com.juancams.rviz2mini.ros.RosGraph
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** A nav_msgs/OccupancyGrid: cells row by row from the origin (-1 unknown, 0..100 occupancy). */
class MapGrid(
    val width: Int, val height: Int, val resolution: Double,
    val originX: Double, val originY: Double, val originYaw: Double,
    val frameId: String, val data: ByteArray,
) {
    /** Map coordinates of the centre of cell (col, row). */
    fun cellCenter(col: Int, row: Int): Pair<Double, Double> {
        val lx = (col + 0.5) * resolution; val ly = (row + 0.5) * resolution
        return originX + lx * cos(originYaw) - ly * sin(originYaw) to originY + lx * sin(originYaw) + ly * cos(originYaw)
    }

    /** The cell (col, row) of a point in map coordinates, possibly outside the grid. */
    fun cellOf(x: Double, y: Double): Pair<Int, Int> {
        val dx = x - originX; val dy = y - originY
        val lx = dx * cos(originYaw) + dy * sin(originYaw); val ly = -dx * sin(originYaw) + dy * cos(originYaw)
        return floor(lx / resolution).toInt() to floor(ly / resolution).toInt()
    }

    /** Map coordinates of a point in grid units (col, row as fractions, row 0 at the origin). */
    fun pointAt(col: Double, row: Double): Pair<Double, Double> {
        val lx = col * resolution; val ly = row * resolution
        return originX + lx * cos(originYaw) - ly * sin(originYaw) to originY + lx * sin(originYaw) + ly * cos(originYaw)
    }

    /** The inverse of [pointAt]. */
    fun gridPoint(x: Double, y: Double): Pair<Double, Double> {
        val dx = x - originX; val dy = y - originY
        return (dx * cos(originYaw) + dy * sin(originYaw)) / resolution to (-dx * sin(originYaw) + dy * cos(originYaw)) / resolution
    }

    companion object {
        fun of(v: MsgValue): MapGrid? {
            val info = v["info"] as? Map<*, *> ?: return null
            val w = (info["width"] as? Number)?.toInt() ?: return null
            val h = (info["height"] as? Number)?.toInt() ?: return null
            val origin = info["origin"] as? Map<*, *>
            val p = origin?.get("position") as? Map<*, *>
            val q = origin?.get("orientation") as? Map<*, *>
            fun d(m: Map<*, *>?, k: String) = (m?.get(k) as? Number)?.toDouble() ?: 0.0
            val yaw = atan2(2 * (d(q, "w") * d(q, "z") + d(q, "x") * d(q, "y")), 1 - 2 * (d(q, "y") * d(q, "y") + d(q, "z") * d(q, "z")))
            val raw = v["data"]
            val data = when (raw) {
                is ByteArray -> raw
                is List<*> -> ByteArray(raw.size) { (raw[it] as? Number)?.toInt()?.toByte() ?: -1 }
                else -> return null
            }
            if (w <= 0 || h <= 0 || data.size < w.toLong() * h) return null
            val header = v["header"] as? Map<*, *>
            return MapGrid(w, h, (info["resolution"] as? Number)?.toDouble() ?: 0.05, d(p, "x"), d(p, "y"), yaw,
                header?.get("frame_id")?.toString().orEmpty(), data)
        }
    }
}

/** The map as map_saver writes it: a PGM image and its YAML (map_server reads both). */
object MapExport {
    const val FREE_THRESH = 0.25
    const val OCCUPIED_THRESH = 0.65

    /** P5 PGM, top row first (the map's last row). Trinary: occupied 0, free 254, unknown 205. */
    fun pgm(g: MapGrid, freeThresh: Double = FREE_THRESH, occupiedThresh: Double = OCCUPIED_THRESH): ByteArray =
        pgmOf(g.width, g.height) { v ->
            when {
                v < 0 || v > 100 -> 205
                v >= occupiedThresh * 100 -> 0
                v <= freeThresh * 100 -> 254
                else -> 205
            }
        }.let { it(g.data) }

    /** A Nav2 filter mask as a "scale" image: value v (0..100) as 255 - v·2.55 (map_server gives v back). */
    fun scalePgm(width: Int, height: Int, data: ByteArray): ByteArray =
        pgmOf(width, height) { v -> if (v <= 0) 255 else 255 - Math.round(v.coerceAtMost(100) * 2.55).toInt() }(data)

    private fun pgmOf(w: Int, h: Int, pixel: (Int) -> Int): (ByteArray) -> ByteArray = { data ->
        val head = "P5\n# VizMini2\n$w $h\n255\n".toByteArray(Charsets.US_ASCII)
        val out = ByteArray(head.size + w * h)
        System.arraycopy(head, 0, out, 0, head.size)
        var o = head.size
        for (row in h - 1 downTo 0) for (col in 0 until w) out[o++] = pixel(data[row * w + col].toInt()).toByte()
        out
    }

    fun yaml(image: String, g: MapGrid, mode: String = "trinary", freeThresh: Double = FREE_THRESH, occupiedThresh: Double = OCCUPIED_THRESH): String {
        val f = Locale.ROOT
        return """
            image: $image
            mode: $mode
            resolution: ${String.format(f, "%.6g", g.resolution)}
            origin: [${String.format(f, "%.6g", g.originX)}, ${String.format(f, "%.6g", g.originY)}, ${String.format(f, "%.6g", g.originYaw)}]
            negate: 0
            occupied_thresh: $occupiedThresh
            free_thresh: $freeThresh
        """.trimIndent() + "\n"
    }

    /** The files for [com.juancams.rviz2mini.ui.common.FileDialogs] (name.pgm, name.yaml). */
    fun files(name: String, g: MapGrid): List<Pair<String, ByteArray>> =
        listOf("$name.pgm" to pgm(g), "$name.yaml" to yaml("$name.pgm", g).toByteArray())
}

/**
 * Zones drawn on the map for Nav2's costmap filters: keepout (cells at 100, the robot does not go in) or speed
 * limit (cells with the percentage of the maximum speed). The mask has the map's size and origin.
 */
object Zones {
    enum class Kind(val labelKey: String, val defaultTopic: String, val filterType: Int) {
        KEEPOUT("maps_zone_keepout", "/keepout_filter_mask", 0),
        SPEED("maps_zone_speed", "/speed_filter_mask", 1);
    }

    /** A polygon in map coordinates; `value`: 100 for keepout, the percentage for a speed limit. */
    data class Zone(val kind: Kind, val points: List<Pair<Double, Double>>, val value: Int)

    fun inside(x: Double, y: Double, poly: List<Pair<Double, Double>>): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]; val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** The mask cells (0 outside every zone of that kind; the highest value where they overlap). */
    fun mask(g: MapGrid, zones: List<Zone>, kind: Kind): ByteArray {
        val out = ByteArray(g.width * g.height)
        for (z in zones.filter { it.kind == kind && it.points.size >= 3 }) {
            // Only the cells of the polygon's bounding box.
            val cells = z.points.map { g.cellOf(it.first, it.second) }
            val c0 = cells.minOf { it.first }.coerceAtLeast(0); val c1 = cells.maxOf { it.first }.coerceAtMost(g.width - 1)
            val r0 = cells.minOf { it.second }.coerceAtLeast(0); val r1 = cells.maxOf { it.second }.coerceAtMost(g.height - 1)
            for (r in r0..r1) for (c in c0..c1) {
                val (x, y) = g.cellCenter(c, r)
                if (inside(x, y, z.points)) { val i = r * g.width + c; if (z.value > out[i]) out[i] = z.value.coerceIn(0, 100).toByte() }
            }
        }
        return out
    }

    /** The nav_msgs/OccupancyGrid of a mask (the map's header and info). */
    fun maskMessage(g: MapGrid, data: ByteArray, stampNs: Long): JSONObject {
        val half = g.originYaw / 2
        return JSONObject()
            .put("header", JSONObject().put("frame_id", g.frameId).put("stamp", JSONObject().put("sec", stampNs / 1_000_000_000).put("nanosec", stampNs % 1_000_000_000)))
            .put("info", JSONObject().put("resolution", g.resolution).put("width", g.width).put("height", g.height)
                .put("origin", JSONObject().put("position", JSONObject().put("x", g.originX).put("y", g.originY).put("z", 0.0))
                    .put("orientation", JSONObject().put("x", 0.0).put("y", 0.0).put("z", sin(half)).put("w", cos(half)))))
            .put("data", JSONArray(data.map { it.toInt() }))
    }

    /** nav2_msgs/CostmapFilterInfo for the mask (keepout 0 and speed in percent 1; mask value = base + multiplier · cell). */
    fun filterInfo(kind: Kind, maskTopic: String, frameId: String): JSONObject = JSONObject()
        .put("header", JSONObject().put("frame_id", frameId)).put("type", kind.filterType)
        .put("filter_mask_topic", maskTopic).put("base", 0.0).put("multiplier", 1.0)

    /** Zones <-> text (for the tab settings): "KIND value x,y;x,y;..." one per line. */
    fun format(zones: List<Zone>): String = zones.joinToString("\n") { z ->
        "${z.kind.name} ${z.value} " + z.points.joinToString(";") { String.format(Locale.ROOT, "%.3f,%.3f", it.first, it.second) }
    }

    fun parse(text: String?): List<Zone> = text.orEmpty().lines().mapNotNull { l ->
        val p = l.trim().split(' ', limit = 3)
        if (p.size < 3) return@mapNotNull null
        val kind = Kind.entries.firstOrNull { it.name == p[0] } ?: return@mapNotNull null
        val pts = p[2].split(';').mapNotNull { xy -> xy.split(',').takeIf { it.size == 2 }?.let { (a, b) -> a.toDoubleOrNull()?.let { x -> b.toDoubleOrNull()?.let { y -> x to y } } } }
        if (pts.size < 3) null else Zone(kind, pts, p[1].toIntOrNull() ?: 100)
    }
}

/**
 * The map services on the network: Nav2's map_saver, SLAM Toolbox (save, serialize, pause) and the costmaps'
 * "clear entirely". Found by type in the graph, so any namespace works. The calls block.
 */
class MapServices(private val registry: InterfaceRegistry) {
    data class Found(
        val mapSaver: List<String>, val slamSave: List<String>, val slamSerialize: List<String>,
        val slamPause: List<String>, val clearCostmaps: List<String>,
    )

    companion object {
        fun find(g: RosGraph?): Found {
            val s = g?.services(includeHidden = true).orEmpty()
            fun of(type: String) = s.filter { it.type == type }.map { it.name }.sorted()
            return Found(of("nav2_msgs/srv/SaveMap"), of("slam_toolbox/srv/SaveMap"), of("slam_toolbox/srv/SerializePoseGraph"),
                of("slam_toolbox/srv/Pause"), of("nav2_msgs/srv/ClearEntireCostmap"))
        }
    }

    private fun call(service: String, type: String, req: JSONObject, timeoutMs: Long = 20_000): MsgValue {
        val c = DynServiceClient(service, registry.srv(type))
        try { return c.call(DynCodec.fromJson(c.srv.request, req), timeoutMs) } finally { c.close() }
    }

    /** map_saver: `url` is the path on the robot without extension (/home/robot/maps/office). */
    fun saveWithMapSaver(service: String, url: String, mapTopic: String = "/map"): Boolean =
        call(service, "nav2_msgs/srv/SaveMap", JSONObject().put("map_topic", mapTopic).put("map_url", url).put("image_format", "pgm")
            .put("map_mode", "trinary").put("free_thresh", MapExport.FREE_THRESH).put("occupied_thresh", MapExport.OCCUPIED_THRESH))["result"] == true

    /** SLAM Toolbox's save_map (PGM + YAML where slam_toolbox runs). */
    fun slamSave(service: String, name: String): Boolean =
        (call(service, "slam_toolbox/srv/SaveMap", JSONObject().put("name", JSONObject().put("data", name)))["result"] as? Number)?.toInt() == 0

    /** SLAM Toolbox's pose graph (to continue mapping or localize later). */
    fun slamSerialize(service: String, filename: String): Boolean =
        (call(service, "slam_toolbox/srv/SerializePoseGraph", JSONObject().put("filename", filename))["result"] as? Number)?.toInt() == 0

    /** Toggles SLAM Toolbox's processing of new scans (it does not say which way: the screen remembers it). */
    fun slamTogglePause(service: String): Boolean = call(service, "slam_toolbox/srv/Pause", JSONObject())["status"] == true

    fun clearCostmap(service: String) { call(service, "nav2_msgs/srv/ClearEntireCostmap", JSONObject(), 10_000) }
}
