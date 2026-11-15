// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.camera

/** A shape on the image, in image pixels. ARGB colors (0 = not drawn). */
sealed class ImageShape {
    class Circle(val x: Double, val y: Double, val radius: Double, val outline: Int, val fill: Int, val thickness: Double) : ImageShape()
    /** `kind`: loose points, line, closed line or segments in pairs. */
    class Points(val kind: Kind, val xy: DoubleArray, val colors: IntArray, val outline: Int, val fill: Int, val thickness: Double) : ImageShape() {
        enum class Kind { POINTS, STRIP, LOOP, LIST }
    }
    class Text(val x: Double, val y: Double, val text: String, val size: Double, val color: Int, val background: Int) : ImageShape()
}

/**
 * Annotations of an image: visualization_msgs/ImageMarker (accumulated by ns/id, with expiry,
 * as in image_view) or foxglove_msgs/ImageAnnotations (each message replaces the previous one). No
 * Android except for drawing: the conversions are tested on the JVM.
 */
class ImageAnnotationState {
    private class Entry(val shape: ImageShape, val expiresAt: Long)

    private val markers = LinkedHashMap<Pair<String, Int>, Entry>()
    private var foxglove: List<ImageShape> = emptyList()

    @Synchronized fun shapes(nowMs: Long): List<ImageShape> {
        markers.values.removeAll { it.expiresAt <= nowMs }
        return markers.values.map { it.shape } + foxglove
    }

    @Synchronized fun clear() { markers.clear(); foxglove = emptyList() }

    /** A visualization_msgs/ImageMarker. */
    @Synchronized fun applyImageMarker(v: Map<*, *>, nowMs: Long) {
        val key = (v["ns"]?.toString() ?: "") to ((v["id"] as? Number)?.toInt() ?: 0)
        if ((v["action"] as? Number)?.toInt() == REMOVE) { markers.remove(key); return }
        val lifetime = (v["lifetime"] as? Map<*, *>)?.let { (it["sec"] as Number).toLong() * 1000 + (it["nanosec"] as Number).toLong() / 1_000_000 } ?: 0
        markers[key] = Entry(imageMarkerShape(v), if (lifetime > 0) nowMs + lifetime else Long.MAX_VALUE)
    }

    /** A foxglove_msgs/ImageAnnotations: replaces the previous ones of this type. */
    @Synchronized fun applyFoxglove(v: Map<*, *>) { foxglove = foxgloveShapes(v) }

    companion object {
        const val CIRCLE = 0; const val LINE_STRIP = 1; const val LINE_LIST = 2; const val POLYGON = 3; const val POINTS = 4
        const val REMOVE = 1

        fun rgba(c: Any?): Int {
            val m = c as? Map<*, *> ?: return 0
            fun ch(k: String) = (((m[k] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
            return (ch("a") shl 24) or (ch("r") shl 16) or (ch("g") shl 8) or ch("b")
        }

        private fun xy(points: Any?): DoubleArray {
            val l = (points as? List<*>).orEmpty()
            val out = DoubleArray(l.size * 2)
            l.forEachIndexed { i, p -> val m = p as? Map<*, *>; out[i * 2] = (m?.get("x") as? Number)?.toDouble() ?: 0.0; out[i * 2 + 1] = (m?.get("y") as? Number)?.toDouble() ?: 0.0 }
            return out
        }

        fun imageMarkerShape(v: Map<*, *>): ImageShape {
            val outline = rgba(v["outline_color"])
            val filled = (v["filled"] as? Number)?.toInt() == 1 || v["filled"] == true
            val fill = if (filled) rgba(v["fill_color"]) else 0
            val scale = (v["scale"] as? Number)?.toDouble() ?: 1.0
            val colors = (v["outline_colors"] as? List<*>).orEmpty().map { rgba(it) }.toIntArray()
            return when ((v["type"] as? Number)?.toInt()) {
                CIRCLE -> {
                    val p = v["position"] as? Map<*, *>
                    ImageShape.Circle((p?.get("x") as? Number)?.toDouble() ?: 0.0, (p?.get("y") as? Number)?.toDouble() ?: 0.0, scale, outline, fill, 1.0)
                }
                LINE_LIST -> ImageShape.Points(ImageShape.Points.Kind.LIST, xy(v["points"]), colors, outline, 0, scale)
                POLYGON -> ImageShape.Points(ImageShape.Points.Kind.LOOP, xy(v["points"]), colors, outline, fill, scale)
                POINTS -> ImageShape.Points(ImageShape.Points.Kind.POINTS, xy(v["points"]), colors, outline, 0, scale)
                else -> ImageShape.Points(ImageShape.Points.Kind.STRIP, xy(v["points"]), colors, outline, 0, scale)
            }
        }

        fun foxgloveShapes(v: Map<*, *>): List<ImageShape> {
            val out = ArrayList<ImageShape>()
            for (c in (v["circles"] as? List<*>).orEmpty()) {
                val m = c as? Map<*, *> ?: continue
                val p = m["position"] as? Map<*, *>
                out += ImageShape.Circle((p?.get("x") as? Number)?.toDouble() ?: 0.0, (p?.get("y") as? Number)?.toDouble() ?: 0.0,
                    ((m["diameter"] as? Number)?.toDouble() ?: 0.0) / 2, rgba(m["outline_color"]), rgba(m["fill_color"]),
                    (m["thickness"] as? Number)?.toDouble() ?: 1.0)
            }
            for (pa in (v["points"] as? List<*>).orEmpty()) {
                val m = pa as? Map<*, *> ?: continue
                val kind = when ((m["type"] as? Number)?.toInt()) {
                    2 -> ImageShape.Points.Kind.LOOP; 3 -> ImageShape.Points.Kind.STRIP; 4 -> ImageShape.Points.Kind.LIST
                    else -> ImageShape.Points.Kind.POINTS
                }
                out += ImageShape.Points(kind, xy(m["points"]), (m["outline_colors"] as? List<*>).orEmpty().map { rgba(it) }.toIntArray(),
                    rgba(m["outline_color"]), rgba(m["fill_color"]), (m["thickness"] as? Number)?.toDouble() ?: 1.0)
            }
            for (t in (v["texts"] as? List<*>).orEmpty()) {
                val m = t as? Map<*, *> ?: continue
                val p = m["position"] as? Map<*, *>
                out += ImageShape.Text((p?.get("x") as? Number)?.toDouble() ?: 0.0, (p?.get("y") as? Number)?.toDouble() ?: 0.0,
                    m["text"]?.toString().orEmpty(), (m["font_size"] as? Number)?.toDouble() ?: 12.0, rgba(m["text_color"]), rgba(m["background_color"]))
            }
            return out
        }
    }
}


/** Finding the annotations that belong to a camera image. */
object ImageAnnotationTopics {
    val TYPES = setOf("visualization_msgs/msg/ImageMarker", "foxglove_msgs/msg/ImageAnnotations")

    /**
     * The annotations topic for `imageTopic` among `topics` (name → types): one of [TYPES] in the same namespace as
     * the image (e.g. /camera/image_markers for /camera/image_raw), the closest one first; null if there is none.
     */
    fun find(imageTopic: String, topics: Map<String, List<String>>): String? {
        val image = imageTopic.removeSuffix("/compressed").removeSuffix("/compressedDepth")
        val ns = image.substringBeforeLast('/', "")
        return topics.filter { (_, types) -> types.any { it in TYPES } }.keys
            .filter { ns.isEmpty() || it.startsWith("$ns/") || it.startsWith("$image/") }
            .minWithOrNull(compareBy({ !it.startsWith("$image/") }, { it.count { c -> c == '/' } }, { it.length }))
    }
}
