// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.display

import kotlin.math.abs
import kotlin.math.exp

/**
 * EasyNavigation's NavMap (https://github.com/EasyNavigation/NavMap) as the NavMap display reads it: a navigable surface
 * of triangles (NavCels), possibly on several floors, with per-cell layers and links between cells that do not share an
 * edge (stairs, elevators, doors). Reads navmap_ros_interfaces/NavMap and NavMapLayer (as decoded by DynCodec), applies
 * the layer updates and gives each cell (or vertex) its color. No rendering here: plain JVM, unit tested.
 */
object NavMapModel {
    const val MAP_TYPE = "navmap_ros_interfaces/msg/NavMap"
    const val LAYER_TYPE = "navmap_ros_interfaces/msg/NavMapLayer"
    const val U8 = 0
    const val F32 = 1
    const val F64 = 2
    const val I16 = 3
    /** NavMapLink types, in order: generic, ramp, stairs, elevator, door. */
    val LINK_COLORS = listOf(Colors.rgb(255, 255, 0), Colors.rgb(0, 204, 0), Colors.rgb(255, 128, 0), Colors.rgb(153, 0, 255), Colors.rgb(0, 153, 255))
    /** What navmap_ros writes in `unit` for occupancy in 1/1000 log-odds (int16). */
    const val LOG_ODDS_UNIT = "1/1000 log-odds"

    /** A layer: one value per cell (`values`, as doubles whatever the type). */
    class Layer(val name: String, val type: Int, val values: DoubleArray, val hasUnknown: Boolean, val unknown: Double, val unit: String) {
        /** Not observed: the layer's unknown value, or NaN. */
        fun isUnknown(v: Double) = v.isNaN() || (hasUnknown && v == unknown)
        val isLogOdds get() = type == I16 && unit == LOG_ODDS_UNIT
        fun sameAs(o: Layer) = name == o.name && type == o.type && hasUnknown == o.hasUnknown && unit == o.unit &&
            (unknown == o.unknown || (unknown.isNaN() && o.unknown.isNaN())) && values.contentEquals(o.values)
    }

    class Link(val from: Int, val to: Int, val type: Int)

    /** `xyz`: 3 floats per vertex; `v`: 3 vertex indices per cell; `rgba`: 4 bytes per vertex, or null. */
    class NavMap(
        val frame: String, val xyz: FloatArray, val v: IntArray, val rgba: ByteArray?, val surfaces: Int,
        val layers: LinkedHashMap<String, Layer>, val links: List<Link>, val geometryId: Long
    ) {
        val cells get() = v.size / 3
        val vertices get() = xyz.size / 3

        /** The same map again (it is republished periodically): nothing to redraw. */
        fun sameAs(o: NavMap): Boolean = frame == o.frame && geometryId == o.geometryId && xyz.contentEquals(o.xyz) &&
            v.contentEquals(o.v) && rgba.contentEquals(o.rgba) && surfaces == o.surfaces &&
            links.size == o.links.size && links.indices.all { links[it].from == o.links[it].from && links[it].to == o.links[it].to && links[it].type == o.links[it].type } &&
            layers.keys == o.layers.keys && layers.all { (k, l) -> l.sameAs(o.layers.getValue(k)) }

        fun centroid(t: Int): DoubleArray {
            val r = DoubleArray(3)
            for (k in 0..2) { val i = v[t * 3 + k]; for (d in 0..2) r[d] += xyz[i * 3 + d] / 3.0 }
            return r
        }
    }

    /** A NavMapLayer message: `indices` empty = the whole layer. */
    class LayerUpdate(val layer: Layer, val indices: IntArray, val geometryId: Long)

    // ------------------------------------------------------------------ reading

    /** A navmap_ros_interfaces/NavMap (DynCodec's map). Cells with a vertex out of range are an error, as in navmap_ros. */
    fun parseMap(m: Map<*, *>): NavMap {
        val xs = doubles(m["positions_x"]); val ys = doubles(m["positions_y"]); val zs = doubles(m["positions_z"])
        require(xs.size == ys.size && ys.size == zs.size) { "positions_x/y/z of different sizes (${xs.size}, ${ys.size}, ${zs.size})" }
        val n = xs.size
        val xyz = FloatArray(n * 3).also { for (i in 0 until n) { it[i * 3] = xs[i].toFloat(); it[i * 3 + 1] = ys[i].toFloat(); it[i * 3 + 2] = zs[i].toFloat() } }
        val a = ints(m["navcels_v0"]); val b = ints(m["navcels_v1"]); val c = ints(m["navcels_v2"])
        require(a.size == b.size && b.size == c.size) { "navcels_v0/v1/v2 of different sizes (${a.size}, ${b.size}, ${c.size})" }
        val t = a.size
        val v = IntArray(t * 3).also { for (i in 0 until t) { it[i * 3] = a[i]; it[i * 3 + 1] = b[i]; it[i * 3 + 2] = c[i] } }
        for (i in v) require(i in 0 until n) { "a NavCel uses vertex $i, out of range ($n vertices)" }
        val rgba = if (m["has_vertex_rgba"] == true) {
            val r = bytes(m["colors_r"]); val g = bytes(m["colors_g"]); val bl = bytes(m["colors_b"]); val al = bytes(m["colors_a"])
            if (r.size == n && g.size == n && bl.size == n) ByteArray(n * 4).also {
                for (i in 0 until n) { it[i * 4] = r[i]; it[i * 4 + 1] = g[i]; it[i * 4 + 2] = bl[i]; it[i * 4 + 3] = if (al.size == n) al[i] else -1 }
            } else null
        } else null
        val layers = LinkedHashMap<String, Layer>()
        for (l in (m["layers"] as? List<*>).orEmpty()) (l as? Map<*, *>)?.let { parseLayer(it) }?.takeIf { it.values.size == t }?.let { layers[it.name] = it }
        val links = (m["links"] as? List<*>).orEmpty().mapNotNull { l ->
            (l as? Map<*, *>)?.let { Link(num(it["from_navcel"]).toInt(), num(it["to_navcel"]).toInt(), num(it["type"]).toInt()) }
        }.filter { it.from in 0 until t && it.to in 0 until t }
        val surfaces = (m["surfaces"] as? List<*>).orEmpty()
        val frame = ((m["header"] as? Map<*, *>)?.get("frame_id") as? String).orEmpty()
            .ifEmpty { (surfaces.firstOrNull() as? Map<*, *>)?.get("frame_id") as? String ?: "" }
        return NavMap(frame, xyz, v, rgba, surfaces.size, layers, links, num(m["geometry_id"]))
    }

    fun parseLayerUpdate(m: Map<*, *>) = LayerUpdate(parseLayer(m), ints(m["indices"]), num(m["geometry_id"]))

    private fun parseLayer(m: Map<*, *>): Layer {
        val type = num(m["type"]).toInt()
        val values = when (type) {
            U8 -> bytes(m["data_u8"]).let { b -> DoubleArray(b.size) { (b[it].toInt() and 0xFF).toDouble() } }
            F32 -> doubles(m["data_f32"])
            F64 -> doubles(m["data_f64"])
            I16 -> doubles(m["data_i16"])
            else -> DoubleArray(0)
        }
        return Layer(m["name"]?.toString().orEmpty(), type, values, m["has_unknown"] == true,
            (m["unknown_value"] as? Number)?.toDouble() ?: 0.0, m["unit"]?.toString().orEmpty())
    }

    /**
     * Applies a layer message to `map` (in place): a whole layer replaces (or adds) the one with its name; a sparse one
     * patches it. Returns null if applied, or why not (another geometry, wrong size, no such layer to patch).
     */
    fun apply(map: NavMap, u: LayerUpdate): String? {
        val name = u.layer.name
        if (u.geometryId != 0L && map.geometryId != 0L && u.geometryId != map.geometryId) return "Ignored \"$name\": it belongs to another geometry"
        if (u.indices.isEmpty()) {
            if (u.layer.values.size != map.cells) return "Layer \"$name\": ${u.layer.values.size} values for ${map.cells} cells"
            map.layers[name] = u.layer
            return null
        }
        val cached = map.layers[name]?.takeIf { it.type == u.layer.type }
            ?: return "Sparse update of \"$name\" does not match a received layer"
        if (u.layer.values.size != u.indices.size) return "Sparse update of \"$name\": ${u.layer.values.size} values for ${u.indices.size} cells"
        for (k in u.indices.indices) u.indices[k].takeIf { it in cached.values.indices }?.let { cached.values[it] = u.layer.values[k] }
        return null
    }

    /** The layer that colors the cells: the one named, or the first one if `name` is blank. */
    fun selectLayer(map: NavMap, name: String): Layer? = name.trim().let { n -> if (n.isEmpty()) map.layers.values.firstOrNull() else map.layers[n] }

    // ------------------------------------------------------------------ colors

    /**
     * The colors of a map's cells: by `layer` (null: `flat`, or the map's vertex colors if it has them and `scheme` is
     * not "flat"), with `scheme` viridis, heat or rainbow over the layer's range of known values; U8 and log-odds layers
     * as occupancy (free white, occupied black), as in navmap_rviz_plugin. Unknown cells: translucent grey.
     * `smooth`: each vertex takes the mean of its known cells' values, so the colors blend across cells.
     */
    class Colorizer(private val map: NavMap, val layer: Layer?, private val scheme: String, private val flat: Int, private val alpha: Float, smooth: Boolean) {
        val lo: Double
        val hi: Double
        val vertexColors: Boolean = layer == null && scheme != "flat" && map.rgba != null
        private val vertexValues: DoubleArray?

        init {
            var mn = Double.POSITIVE_INFINITY; var mx = Double.NEGATIVE_INFINITY
            if (layer != null) for (v in layer.values) if (!layer.isUnknown(v)) { if (v < mn) mn = v; if (v > mx) mx = v }
            lo = if (mn.isInfinite()) 0.0 else mn; hi = if (mx.isInfinite()) 0.0 else mx
            vertexValues = if (layer != null && smooth && layer.type != U8 && !layer.isLogOdds) {
                val sum = DoubleArray(map.vertices); val cnt = IntArray(sum.size)
                for (t in 0 until map.cells) {
                    val v = layer.values[t]; if (layer.isUnknown(v)) continue
                    for (k in 0..2) { val i = map.v[t * 3 + k]; sum[i] += v; cnt[i]++ }
                }
                DoubleArray(sum.size) { if (cnt[it] > 0) sum[it] / cnt[it] else Double.NaN }
            } else null
        }

        val smooth get() = vertexValues != null

        fun cellUnknown(t: Int) = layer != null && layer.isUnknown(layer.values[t])

        fun cellColor(t: Int): Int {
            val l = layer ?: return flat
            val v = l.values[t]
            return when {
                l.isUnknown(v) -> UNKNOWN_GREY.let { Colors.withAlpha(it, 0.35f * alpha) }
                l.type == U8 -> occupancy(v.toInt(), alpha)
                l.isLogOdds -> grey(1 - 1.0 / (1.0 + exp(-v / 1000.0)), alpha)
                else -> valueColor(v)
            }
        }

        /** The color at vertex `i` of cell `t` (smooth): its mean value, or the cell's if none of its cells is known. */
        fun vertexColor(t: Int, i: Int): Int = vertexValues?.get(i)?.takeIf { !it.isNaN() }?.let { valueColor(it) } ?: cellColor(t)

        fun rgbaColor(i: Int): Int {
            val c = map.rgba!!
            return Colors.withAlpha(Colors.rgb(c[i * 4].toInt() and 0xFF, c[i * 4 + 1].toInt() and 0xFF, c[i * 4 + 2].toInt() and 0xFF),
                (c[i * 4 + 3].toInt() and 0xFF) / 255f * alpha)
        }

        fun valueColor(v: Double): Int {
            val n = if (hi - lo <= 1e-9) -1.0 else ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            return when (scheme) { "rainbow" -> rainbow(n, alpha); "heat" -> heat(n, alpha); else -> viridis(n, alpha) }
        }
    }

    private val UNKNOWN_GREY = Colors.rgb(128, 128, 128)
    private val VIRIDIS = intArrayOf(0x440154, 0x482878, 0x3E4A89, 0x31688E, 0x26828E, 0x1F9E89, 0x35B779, 0x6DCD59, 0xB4DE2C, 0xFDE725)

    private fun grey(f: Double, a: Float) = (f * 255).toInt().coerceIn(0, 255).let { Colors.withAlpha(Colors.rgb(it, it, it), a) }

    /** U8 occupancy: 0 free (white), 254 lethal (black), 255 unknown (dark grey), the rest from white to black. */
    fun occupancy(v: Int, a: Float) = when (v) {
        0 -> grey(1.0, a); 254 -> grey(0.0, a); 255 -> grey(0.25, a)
        else -> grey(1 - v / 253.0, a)
    }

    /** Viridis (dark purple low, yellow high): readable on light and dark backgrounds, and by color-blind people. n < 0: a single value. */
    fun viridis(n: Double, a: Float): Int {
        val f = (if (n < 0) 0.5 else n) * (VIRIDIS.size - 1)
        val i = f.toInt().coerceAtMost(VIRIDIS.size - 2); val w = f - i
        fun mix(sh: Int) = (((VIRIDIS[i] shr sh) and 0xFF) * (1 - w) + ((VIRIDIS[i + 1] shr sh) and 0xFF) * w + 0.5).toInt()
        return Colors.withAlpha(Colors.rgb(mix(16), mix(8), mix(0)), a)
    }

    /** Green (low) to red (high), as navmap_rviz_plugin; a single value, green. */
    fun heat(n: Double, a: Float) = if (n < 0) Colors.withAlpha(Colors.rgb(0, 255, 0), a)
        else Colors.withAlpha(Colors.rgb((n * 255).toInt(), ((1 - n) * 255).toInt(), 0), a)

    /** Blue (low) to red (high) through the hue circle, as navmap_rviz_plugin; a single value, green. */
    fun rainbow(n: Double, a: Float): Int {
        if (n < 0) return Colors.withAlpha(Colors.rgb(0, 255, 0), a)
        val h = (1 - n) * 4.0 // sectors of 60 degrees, from red (0) to blue (4)
        val x = 1 - abs(h % 2 - 1)
        val (r, g, b) = when (h.toInt()) { 0 -> Triple(1.0, x, 0.0); 1 -> Triple(x, 1.0, 0.0); 2 -> Triple(0.0, 1.0, x); else -> Triple(0.0, x, 1.0) }
        return Colors.withAlpha(Colors.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt()), a)
    }

    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: 0L
    private fun doubles(v: Any?): DoubleArray = (v as? List<*>)?.let { l -> DoubleArray(l.size) { (l[it] as? Number)?.toDouble() ?: 0.0 } } ?: DoubleArray(0)
    private fun ints(v: Any?): IntArray = (v as? List<*>)?.let { l -> IntArray(l.size) { (l[it] as? Number)?.toInt() ?: 0 } } ?: IntArray(0)
    private fun bytes(v: Any?): ByteArray = when (v) {
        is ByteArray -> v
        is List<*> -> ByteArray(v.size) { ((v[it] as? Number)?.toInt() ?: 0).toByte() }
        else -> ByteArray(0)
    }
}
