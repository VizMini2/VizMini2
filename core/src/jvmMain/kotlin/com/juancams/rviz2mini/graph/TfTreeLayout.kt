// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.graph

import kotlin.math.max

/**
 * An edge of the TF tree: `frame` hangs from `parent` (null = root). `ageSec` is the time since
 * the last update (null = unknown).
 */
data class TfEdge(
    val frame: String,
    val parent: String?,
    val isStatic: Boolean = false,
    val ageSec: Double? = null
)

/**
 * Top-down layout of the TF tree (like rqt_tf_tree / view_frames). Pure Kotlin, no
 * Android, so it can be tested on the JVM.
 *
 * Algorithm: post-order width allocation. Each subtree takes
 * max(width of its box, sum of the child subtrees + gaps); the parent is centered over its children.
 * This way siblings never overlap. Multiple roots (a forest) are placed side by side. Cycles
 * (corrupt data) are cut: an already visited frame is not expanded again.
 */
class TfTreeLayout(
    /** Box width as a function of the frame id (in drawing units, e.g. pixels). */
    private val measure: (String) -> Float = { it.length * 8f + 16f },
    val boxHeight: Float = 36f,
    val minBoxWidth: Float = 48f,
    /** Horizontal gap between siblings. */
    val hGap: Float = 16f,
    /** Vertical gap between levels. */
    val vGap: Float = 40f,
    /** Extra horizontal gap between separate trees of the forest. */
    val treeGap: Float = 40f,
    /** Order of siblings and roots (alphabetical; a behavior tree uses its own). */
    private val siblingOrder: Comparator<String> = naturalOrder()
) {

    /** Positioned box: (x, y) is the top-left corner. */
    data class Box(
        val id: String,
        val parent: String?,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val depth: Int,
        val isStatic: Boolean,
        val ageSec: Double?
    ) {
        val cx: Float get() = x + w / 2
        val bottom: Float get() = y + h
        val right: Float get() = x + w
        fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
    }

    class Result(val boxes: List<Box>, val width: Float, val height: Float) {
        val byId: Map<String, Box> = boxes.associateBy { it.id }
        /** Children of each frame according to the layout (cycles already cut). */
        val children: Map<String, List<String>> = boxes.filter { it.parent != null }.groupBy({ it.parent!! }, { it.id })
        val roots: List<String> get() = boxes.filter { it.parent == null }.map { it.id }

        companion object { val EMPTY = Result(emptyList(), 0f, 0f) }
    }

    fun layout(edges: List<TfEdge>): Result {
        if (edges.isEmpty()) return Result.EMPTY
        // Per-frame info (the last edge of each frame wins) and set of frames.
        val info = LinkedHashMap<String, TfEdge>()
        val parentOf = HashMap<String, String>()
        val all = sortedSetOf(siblingOrder)
        for (e in edges) {
            if (e.frame.isEmpty()) continue
            info[e.frame] = e
            all += e.frame
            val p = e.parent
            if (!p.isNullOrEmpty() && p != e.frame) { parentOf[e.frame] = p; all += p } else parentOf.remove(e.frame)
        }
        val children = HashMap<String, MutableList<String>>()
        for ((c, p) in parentOf) children.getOrPut(p) { ArrayList() }.add(c)
        for (l in children.values) l.sortWith(siblingOrder)

        // Roots: no parent. Frames left unvisited are in cycles: the smallest one is treated as a root.
        val visited = HashSet<String>()
        val trees = ArrayList<Pair<String, MutableList<String>>>() // root -> frames in pre-order
        val effectiveParent = HashMap<String, String?>()
        val depth = HashMap<String, Int>()

        fun collect(root: String) {
            val order = ArrayList<String>()
            val stack = ArrayDeque<String>()
            stack.addLast(root); visited += root; effectiveParent[root] = null; depth[root] = 0
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                order += n
                val ch = children[n] ?: continue
                // Reversed so the pre-order comes out in sibling order.
                for (c in ch.asReversed()) {
                    if (!visited.add(c)) continue
                    effectiveParent[c] = n; depth[c] = depth.getValue(n) + 1
                    stack.addLast(c)
                }
            }
            trees += root to order
        }
        for (f in all) if (parentOf[f] == null) collect(f)
        for (f in all) if (f !in visited) collect(f)

        // Effective children (those of the traversal, cycles removed).
        val kids = HashMap<String, MutableList<String>>()
        for ((c, p) in effectiveParent) if (p != null) kids.getOrPut(p) { ArrayList() }.add(c)
        for (l in kids.values) l.sortWith(siblingOrder)

        val boxW = HashMap<String, Float>()
        for (f in all) boxW[f] = max(minBoxWidth, measure(f))

        // Width of each subtree, in post-order (walking the pre-order backwards).
        val subW = HashMap<String, Float>()
        for ((_, order) in trees) for (n in order.asReversed()) {
            val ch = kids[n]
            val sum = if (ch.isNullOrEmpty()) 0f else ch.sumOf { subW.getValue(it).toDouble() }.toFloat() + hGap * (ch.size - 1)
            subW[n] = max(boxW.getValue(n), sum)
        }

        // Placement: each node gets a span [left, left + subW]; children centered inside it.
        val left = HashMap<String, Float>()
        val boxes = ArrayList<Box>(all.size)
        var cursor = 0f
        var maxDepth = 0
        for ((root, order) in trees) {
            left[root] = cursor
            for (n in order) {
                val l = left.getValue(n)
                val w = subW.getValue(n)
                val bw = boxW.getValue(n)
                val d = depth.getValue(n)
                maxDepth = max(maxDepth, d)
                val e = info[n]
                boxes += Box(n, effectiveParent[n], l + (w - bw) / 2, d * (boxHeight + vGap), bw, boxHeight, d,
                    e?.isStatic ?: false, e?.ageSec)
                val ch = kids[n] ?: continue
                val span = ch.sumOf { subW.getValue(it).toDouble() }.toFloat() + hGap * (ch.size - 1)
                var x = l + (w - span) / 2
                for (c in ch) { left[c] = x; x += subW.getValue(c) + hGap }
            }
            cursor += subW.getValue(root) + treeGap
        }
        val width = cursor - treeGap
        val height = (maxDepth + 1) * boxHeight + maxDepth * vGap
        return Result(boxes, width, height)
    }
}
