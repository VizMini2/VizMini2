// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.graph

import com.juancams.rviz2mini.ros.RosGraph
import kotlin.math.max
import kotlin.math.min

/** ROS node for the graph: what it publishes and what it subscribes to (topic names). */
data class GraphNode(
    val name: String,
    val isLifecycle: Boolean = false,
    val publishers: List<String> = emptyList(),
    val subscribers: List<String> = emptyList()
)

/** Adapter from the DDS-discovered graph to the input of [NodeGraphLayout]. */
fun RosGraph.toGraphNodes(): List<GraphNode> =
    nodes().map { GraphNode(it.name, it.isLifecycle, it.publishers, it.subscribers) }

/**
 * Layout of the bipartite nodes/topics graph (like rqt_graph "Nodes/Topics (all)"), in layers
 * from left to right. Pure, deterministic Kotlin (same inputs -> same output).
 *
 * Simplified Sugiyama:
 *  1. Cycle breaking with a deterministic DFS (back edges are reversed only to
 *     compute layers; they are drawn with their real direction).
 *  2. Layers by longest path from the sources; sources are moved closer to their successors.
 *  3. Ordering within each layer with several barycenter passes (down and up).
 *  4. Coordinates: X by layer; Y towards the mean of the already placed neighbors, without overlaps.
 */
class NodeGraphLayout(
    /** Width of a label (in drawing units). */
    private val measure: (String) -> Float = { it.length * 7f },
    val nodeHeight: Float = 34f,
    val topicHeight: Float = 28f,
    val padX: Float = 12f,
    /** Horizontal gap between layers. */
    val layerGap: Float = 70f,
    /** Vertical gap between boxes in the same layer. */
    val vGap: Float = 14f
) {

    /** Filters for what is shown. */
    data class Options(
        /** Hides /rosout, /parameter_events and hidden topics ("/_..."). */
        val hideDebug: Boolean = true,
        /** Hides topics without publishers or without subscribers. */
        val hideLeafTopics: Boolean = false,
        /**
         * Text (case-insensitive) that names must contain. The matching nodes and
         * topics are shown along with their direct neighbors (so context is not lost).
         */
        val filter: String = ""
    )

    enum class Kind { NODE, TOPIC }

    /** Positioned vertex: (x, y) is the top-left corner. */
    data class Vertex(
        val kind: Kind,
        val name: String,
        val isLifecycle: Boolean,
        val layer: Int,
        val order: Int,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float
    ) {
        val cx: Float get() = x + w / 2
        val cy: Float get() = y + h / 2
        val right: Float get() = x + w
        val bottom: Float get() = y + h
        fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
    }

    /** Edge with its real direction: node -> topic (publishes) or topic -> node (subscribes). Indices into `vertices`. */
    data class Edge(val from: Int, val to: Int)

    class Result(val vertices: List<Vertex>, val edges: List<Edge>, val width: Float, val height: Float) {
        fun indexOf(kind: Kind, name: String) = vertices.indexOfFirst { it.kind == kind && it.name == name }
        companion object { val EMPTY = Result(emptyList(), emptyList(), 0f, 0f) }
    }

    fun layout(nodes: List<GraphNode>, options: Options = Options()): Result {
        // --- 1. Bipartite graph with the filters applied ---
        val nodeList = nodes.distinctBy { it.name }.sortedBy { it.name }
        val pubCount = HashMap<String, Int>()
        val subCount = HashMap<String, Int>()
        for (n in nodeList) {
            for (t in n.publishers.distinct()) pubCount[t] = (pubCount[t] ?: 0) + 1
            for (t in n.subscribers.distinct()) subCount[t] = (subCount[t] ?: 0) + 1
        }
        fun topicVisible(t: String): Boolean {
            if (options.hideDebug && isDebugTopic(t)) return false
            if (options.hideLeafTopics && ((pubCount[t] ?: 0) == 0 || (subCount[t] ?: 0) == 0)) return false
            return true
        }
        val topics = (pubCount.keys + subCount.keys).filter(::topicVisible).sorted()

        // Vertices: nodes first, then topics (alphabetical order within each kind).
        val kinds = ArrayList<Kind>(); val names = ArrayList<String>(); val lc = ArrayList<Boolean>()
        val nodeIdx = HashMap<String, Int>(); val topicIdx = HashMap<String, Int>()
        for (n in nodeList) { nodeIdx[n.name] = kinds.size; kinds += Kind.NODE; names += n.name; lc += n.isLifecycle }
        for (t in topics) { topicIdx[t] = kinds.size; kinds += Kind.TOPIC; names += t; lc += false }
        val edgeSet = LinkedHashSet<Long>()
        for (n in nodeList) {
            val ni = nodeIdx.getValue(n.name)
            for (t in n.publishers) topicIdx[t]?.let { edgeSet += key(ni, it) }
            for (t in n.subscribers) topicIdx[t]?.let { edgeSet += key(it, ni) }
        }

        // Text filter: matches + direct neighbors.
        var keep = BooleanArray(kinds.size) { true }
        val needle = options.filter.trim().lowercase()
        if (needle.isNotEmpty()) {
            keep = BooleanArray(kinds.size) { names[it].lowercase().contains(needle) }
            val match = keep.copyOf()
            for (k in edgeSet) {
                val a = (k shr 32).toInt(); val b = k.toInt()
                if (match[a]) keep[b] = true
                if (match[b]) keep[a] = true
            }
        }
        // Reindexing to the remaining vertices.
        val remap = IntArray(kinds.size) { -1 }
        var count = 0
        for (i in kinds.indices) if (keep[i]) remap[i] = count++
        if (count == 0) return Result.EMPTY
        val vKind = arrayOfNulls<Kind>(count); val vName = arrayOfNulls<String>(count); val vLc = BooleanArray(count)
        for (i in kinds.indices) if (remap[i] >= 0) { vKind[remap[i]] = kinds[i]; vName[remap[i]] = names[i]; vLc[remap[i]] = lc[i] }
        val edges = ArrayList<Edge>()
        for (k in edgeSet) {
            val a = remap[(k shr 32).toInt()]; val b = remap[k.toInt()]
            if (a >= 0 && b >= 0) edges += Edge(a, b)
        }

        val n = count
        val out = Array(n) { ArrayList<Int>() }
        val inc = Array(n) { ArrayList<Int>() }
        for (e in edges) { out[e.from] += e.to; inc[e.to] += e.from }

        // --- 2. Cycle breaking: iterative DFS starting from the sources ---
        val state = IntArray(n) // 0 = new, 1 = on stack, 2 = done
        val reversed = HashSet<Long>()
        val discovery = IntArray(n) { -1 }
        var disc = 0
        val starts = (0 until n).sortedWith(compareBy({ if (inc[it].isEmpty()) 0 else 1 }, { it }))
        for (s in starts) {
            if (state[s] != 0) continue
            val stack = ArrayDeque<IntArray>() // (vertex, next successor to look at)
            stack.addLast(intArrayOf(s, 0)); state[s] = 1; discovery[s] = disc++
            while (stack.isNotEmpty()) {
                val top = stack.last()
                val v = top[0]
                if (top[1] < out[v].size) {
                    val w = out[v][top[1]++]
                    when (state[w]) {
                        0 -> { state[w] = 1; discovery[w] = disc++; stack.addLast(intArrayOf(w, 0)) }
                        1 -> reversed += key(v, w) // back edge: closes a cycle
                    }
                } else { state[v] = 2; stack.removeLast() }
            }
        }
        // DAG for the layers.
        val dOut = Array(n) { ArrayList<Int>() }
        val dIn = Array(n) { ArrayList<Int>() }
        for (e in edges) {
            val (a, b) = if (key(e.from, e.to) in reversed) e.to to e.from else e.from to e.to
            dOut[a] += b; dIn[b] += a
        }

        // --- 3. Layers by longest path (Kahn in deterministic order) ---
        val layer = IntArray(n)
        val indeg = IntArray(n) { dIn[it].size }
        val topo = ArrayList<Int>(n)
        val ready = java.util.PriorityQueue<Int>(compareBy { discovery[it] })
        for (v in 0 until n) if (indeg[v] == 0) ready += v
        while (ready.isNotEmpty()) {
            val v = ready.poll()
            topo += v
            for (w in dOut[v]) {
                layer[w] = max(layer[w], layer[v] + 1)
                if (--indeg[w] == 0) ready += w
            }
        }
        // Sources (e.g. topics that only have subscribers) are attached to their first successor.
        for (v in topo.asReversed()) {
            if (dIn[v].isEmpty() && dOut[v].isNotEmpty()) layer[v] = dOut[v].minOf { layer[it] } - 1
        }
        val minLayer = layer.minOrNull() ?: 0
        for (v in 0 until n) layer[v] -= minLayer
        val numLayers = (layer.maxOrNull() ?: 0) + 1

        // --- 4. Ordering within layers: barycenter ---
        val layers = Array(numLayers) { ArrayList<Int>() }
        for (v in (0 until n).sortedBy { discovery[it] }) layers[layer[v]] += v
        val pos = DoubleArray(n)
        fun updatePos() { for (l in layers) { val s = max(1, l.size - 1).toDouble(); l.forEachIndexed { i, v -> pos[v] = i / s } } }
        updatePos()
        fun sweep(range: IntProgression, neighbours: Array<ArrayList<Int>>) {
            for (li in range) {
                val l = layers[li]
                val bary = HashMap<Int, Double>(l.size * 2)
                for ((i, v) in l.withIndex()) {
                    val nb = neighbours[v]
                    bary[v] = if (nb.isEmpty()) i / max(1, l.size - 1).toDouble() else nb.sumOf { pos[it] } / nb.size
                }
                val sorted = l.withIndex().sortedWith(compareBy({ bary.getValue(it.value) }, { it.index })).map { it.value }
                l.clear(); l.addAll(sorted)
                val s = max(1, l.size - 1).toDouble()
                l.forEachIndexed { i, v -> pos[v] = i / s }
            }
        }
        repeat(SWEEPS) {
            sweep(1 until numLayers, dIn)
            sweep(numLayers - 2 downTo 0, dOut)
        }

        // --- 5. Coordinates ---
        val w = FloatArray(n) { measure(vName[it]!!) + 2 * padX }
        val h = FloatArray(n) { if (vKind[it] == Kind.NODE) nodeHeight else topicHeight }
        val layerX = FloatArray(numLayers)
        var x = 0f
        for (li in 0 until numLayers) {
            layerX[li] = x
            x += (layers[li].maxOfOrNull { w[it] } ?: 0f) + layerGap
        }
        val cy = FloatArray(n)
        val undirected = Array(n) { ArrayList<Int>().apply { addAll(dIn[it]); addAll(dOut[it]) } }
        // First pass: simple stacking and centering; then two passes towards the mean of the neighbors.
        val tallest = layers.maxOf { l -> l.sumOf { h[it].toDouble() } + vGap * max(0, l.size - 1) }.toFloat()
        for (l in layers) {
            val total = l.sumOf { h[it].toDouble() }.toFloat() + vGap * max(0, l.size - 1)
            var y = (tallest - total) / 2
            for (v in l) { cy[v] = y + h[v] / 2; y += h[v] + vGap }
        }
        repeat(2) { pass ->
            val order = if (pass % 2 == 0) (0 until numLayers) else (numLayers - 1 downTo 0)
            for (li in order) {
                val l = layers[li]
                if (l.isEmpty()) continue
                val desired = FloatArray(l.size) { i ->
                    val v = l[i]
                    val nb = undirected[v]
                    if (nb.isEmpty()) cy[v] else nb.sumOf { cy[it].toDouble() }.toFloat() / nb.size
                }
                // Keeps the order and the minimum spacing: pushes down and then up.
                val y = desired.copyOf()
                for (i in 1 until l.size) {
                    val minY = y[i - 1] + (h[l[i - 1]] + h[l[i]]) / 2 + vGap
                    if (y[i] < minY) y[i] = minY
                }
                // Recenters the block so that the mean offset from the desired position is zero.
                val shift = (0 until l.size).sumOf { (desired[it] - y[it]).toDouble() }.toFloat() / l.size
                for (i in l.indices) cy[l[i]] = y[i] + shift
            }
        }
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (v in 0 until n) { minY = min(minY, cy[v] - h[v] / 2); maxY = max(maxY, cy[v] + h[v] / 2) }

        val orderInLayer = IntArray(n)
        for (l in layers) l.forEachIndexed { i, v -> orderInLayer[v] = i }
        val vertices = (0 until n).map { v ->
            val lw = layers[layer[v]].maxOf { w[it] }
            Vertex(vKind[v]!!, vName[v]!!, vLc[v], layer[v], orderInLayer[v],
                layerX[layer[v]] + (lw - w[v]) / 2, cy[v] - h[v] / 2 - minY, w[v], h[v])
        }
        return Result(vertices, edges, max(0f, x - layerGap), maxY - minY)
    }

    companion object {
        private const val SWEEPS = 4
        private val DEBUG_TOPICS = setOf("/rosout", "/parameter_events")

        /** "Debug" topics that rqt_graph hides by default. */
        fun isDebugTopic(t: String) = t in DEBUG_TOPICS || RosGraph.isHidden(t)

        private fun key(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
    }
}
