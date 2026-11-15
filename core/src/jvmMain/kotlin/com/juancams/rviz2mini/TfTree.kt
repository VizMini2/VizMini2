// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import java.util.concurrent.ConcurrentHashMap

/**
 * TF tree. `update` is called from the DDS thread; `compute`/`poseInFixed` from the render thread.
 * Each frame stores its local transform relative to its parent; every `compute` resolves the
 * global ones (relative to the root of its tree), detecting cycles and disconnected trees.
 */
class TfTree {

    class Frame constructor(val id: String) {
        @Volatile var parentId: String = ""
            set
        // The whole array is replaced on every update: the reader never sees a half-written matrix.
        @Volatile var local: DoubleArray = TfMath.identity()
        @Volatile var isStatic: Boolean = false
            set
        /** Phone clock (ns) of the last time this frame arrived (for "stale"). */
        @Volatile var lastReceivedNs: Long = 0L
            set

        /** History sorted by stamp (only dynamic frames with a stamp): for interpolation. */
        val history = ArrayDeque<Sample>()

        // Resolution state: only the render thread touches it.
        val global = TfMath.identity()
        var rootId: String = id
        var valid = false
        var pass = 0
        var visiting = false
    }

    /** Sample of the local transform at an instant (message stamp, ns). */
    class Sample(val stampNs: Long, val t: DoubleArray /* tx, ty, tz, qx, qy, qz, qw */)

    private val frames = ConcurrentHashMap<String, Frame>()
    private val jumps = java.util.concurrent.atomic.AtomicInteger()
    private var pass = 0
    private val chain = ArrayList<Frame>()
    private val fixedInverse = TfMath.identity()
    private var fixedRootId: String? = null

    val size: Int get() = frames.size

    /**
     * Backward jumps of the TF clock seen so far (looping bag, simulation restarted with
     * /clock...). Each jump clears the history: displays that accumulate data compare this number to
     * clear themselves too, as RViz does when it detects the jump.
     */
    val timeJumps: Int get() = jumps.get()

    fun contains(id: String) = frames.containsKey(id)

    fun frameIds(): List<String> = frames.keys.sorted()

    fun parentOf(id: String): String? = frames[id]?.parentId

    /** The root of the tree of `id` (after [compute]), or null if it is not resolved. */
    fun rootOf(id: String): String? = frames[normalizeId(id)]?.takeIf { it.valid }?.rootId

    /** (frame, parent or null, static, seconds since it arrived) of every frame, to draw the tree. */
    fun snapshot(nowNs: Long = System.nanoTime()): List<FrameInfo> = frames.values.map { f ->
        FrameInfo(f.id, f.parentId.ifEmpty { null }, f.isStatic, if (f.lastReceivedNs > 0) (nowNs - f.lastReceivedNs) / 1e9 else null)
    }

    data class FrameInfo(val id: String, val parent: String?, val isStatic: Boolean, val ageSec: Double?)

    fun clear() = frames.clear()

    /**
     * Registers a parent -> child transform. Returns true if new frames appeared
     * (so the UI creates their geometry / updates the selector).
     */
    fun update(
        parentRaw: String, childRaw: String,
        tx: Double, ty: Double, tz: Double,
        qx: Double, qy: Double, qz: Double, qw: Double,
        isStatic: Boolean = false,
        stampNs: Long = 0L,
        receivedNs: Long = System.nanoTime()
    ): Boolean {
        val parent = normalizeId(parentRaw)
        val child = normalizeId(childRaw)
        // Same rules as tf2: non-empty ids, no self-references and no NaN/Inf.
        if (parent.isEmpty() || child.isEmpty() || parent == child) return false
        if (!(tx.isFinite() && ty.isFinite() && tz.isFinite() &&
                    qx.isFinite() && qy.isFinite() && qz.isFinite() && qw.isFinite())) return false

        val local = TfMath.fromTranslationQuaternion(tx, ty, tz, qx, qy, qz, qw)
        var created = false
        val childFrame = frames.computeIfAbsent(child) { created = true; Frame(it) }
        frames.computeIfAbsent(parent) { created = true; Frame(it) }
        childFrame.local = local
        childFrame.parentId = parent
        childFrame.isStatic = isStatic
        childFrame.lastReceivedNs = receivedNs
        if (!isStatic && stampNs > 0) {
            if (jumpedBack(childFrame, stampNs)) clearHistory()
            addSample(childFrame, Sample(stampNs, doubleArrayOf(tx, ty, tz, qx, qy, qz, qw)))
        }
        return created
    }

    /**
     * This frame's clock went backwards: its stamp is more than [TIME_JUMP_NS] older than the newest
     * one it has. Compared frame by frame (each is published by one node, with a single clock) so that the
     * offset between the clocks of two machines is not mistaken for a jump; network reordering is milliseconds.
     */
    private fun jumpedBack(f: Frame, stampNs: Long): Boolean = synchronized(f) {
        f.history.isNotEmpty() && stampNs < f.history.last().stampNs - TIME_JUMP_NS
    }

    /**
     * Like tf2/RViz2 when detecting a backward jump: the history of every frame is forgotten (the samples from
     * the previous loop would sit "in the future" and hide the new ones). The tree, the latest transform
     * of each frame and the static ones are kept, so nothing disappears from the screen.
     */
    private fun clearHistory() {
        for (f in frames.values) synchronized(f) { f.history.clear() }
        jumps.incrementAndGet()
    }

    /**
     * Resolves all global transforms and prepares the inverse of the fixed frame.
     * If the fixed frame does not exist, the root of each tree is used as the "world".
     */
    fun compute(fixedFrame: String) {
        pass++
        for (f in frames.values) resolve(f)

        val fixed = frames[normalizeId(fixedFrame)]
        if (fixed != null && fixed.valid) {
            TfMath.invertRigid(fixed.global, fixedInverse)
            fixedRootId = fixed.rootId
        } else {
            TfMath.setIdentity(fixedInverse)
            fixedRootId = null
        }
    }

    /**
     * Pose of `id` expressed in the fixed frame (after `compute`). Returns false if the frame does not exist,
     * is part of a cycle or is not connected to the fixed frame (RViz does not draw those either).
     */
    fun poseInFixed(id: String, out: DoubleArray): Boolean {
        val f = frames[id] ?: return false
        if (!f.valid) return false
        val root = fixedRootId
        if (root != null && f.rootId != root) return false
        TfMath.multiply(fixedInverse, f.global, out)
        return true
    }

    // ------------------------------------------------------------------ time

    private fun addSample(f: Frame, s: Sample) = synchronized(f) {
        val h = f.history
        if (h.isEmpty() || s.stampNs >= h.last().stampNs) h.addLast(s)
        else {
            // Arrives out of order (network): inserted in its place.
            var i = h.size - 1
            while (i >= 0 && h[i].stampNs > s.stampNs) i--
            if (i >= 0 && h[i].stampNs == s.stampNs) h[i] = s else h.add(i + 1, s)
        }
        val newest = h.last().stampNs
        while (h.size > MAX_SAMPLES || (h.size > 2 && newest - h.first().stampNs > CACHE_NS)) h.removeFirst()
    }

    /**
     * Local transform of `f` at `stampNs`: interpolated between the two samples around it
     * (linear in translation, slerp in rotation). Outside the history the closest one is used.
     * Static frames or stamp 0: the latest received.
     */
    private fun localAt(f: Frame, stampNs: Long, out: DoubleArray): DoubleArray {
        if (f.isStatic || stampNs <= 0L) return f.local.copyInto(out)
        synchronized(f) {
            val h = f.history
            if (h.isEmpty()) return f.local.copyInto(out)
            if (stampNs >= h.last().stampNs) return fromSample(h.last().t, out)
            if (stampNs <= h.first().stampNs) return fromSample(h.first().t, out)
            var lo = 0; var hi = h.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (h[mid].stampNs <= stampNs) lo = mid else hi = mid }
            val a = h[lo]; val b = h[hi]
            val r = (stampNs - a.stampNs).toDouble() / (b.stampNs - a.stampNs).toDouble()
            val q = TfMath.slerp(a.t[3], a.t[4], a.t[5], a.t[6], b.t[3], b.t[4], b.t[5], b.t[6], r)
            return TfMath.fromTranslationQuaternion(
                a.t[0] + (b.t[0] - a.t[0]) * r, a.t[1] + (b.t[1] - a.t[1]) * r, a.t[2] + (b.t[2] - a.t[2]) * r,
                q[0], q[1], q[2], q[3], out
            )
        }
    }

    private fun fromSample(t: DoubleArray, out: DoubleArray) = TfMath.fromTranslationQuaternion(t[0], t[1], t[2], t[3], t[4], t[5], t[6], out)

    /** Global transform of `id` at instant `stampNs` and its root; null if it does not exist or there is a cycle. */
    private fun globalAt(id: String, stampNs: Long, out: DoubleArray): String? {
        val chain = ArrayList<Frame>()
        var f: Frame? = frames[id] ?: return null
        val seen = HashSet<String>()
        while (f != null) {
            if (!seen.add(f.id)) return null // cycle
            chain.add(f)
            val pid = f.parentId
            f = if (pid.isEmpty()) null else frames[pid]
        }
        TfMath.setIdentity(out)
        val tmp = DoubleArray(16)
        for (i in chain.indices.reversed()) {
            val node = chain[i]
            if (i == chain.size - 1 && node.parentId.isEmpty()) continue // root: identity
            TfMath.multiply(out, localAt(node, stampNs, tmp), out)
        }
        return chain.last().let { if (it.parentId.isEmpty()) it.id else it.parentId }
    }

    /**
     * Pose of `id` in `fixedFrame` at instant `stampNs` (message stamp). With stamp 0 it uses the
     * latest TF. Like RViz, the fixed frame is assumed to be still: it is evaluated at the same instant.
     * Render thread (or any: it only reads).
     */
    fun lookupInFixed(id: String, fixedFrame: String, stampNs: Long, out: DoubleArray): Boolean {
        val fid = normalizeId(id)
        val fixed = normalizeId(fixedFrame)
        val g = DoubleArray(16)
        val root = globalAt(fid, stampNs, g) ?: return fixed == fid && TfMath.setIdentity(out).let { true }
        if (fixed.isEmpty() || frames[fixed] == null) { g.copyInto(out); return true }
        val gf = DoubleArray(16)
        val fixedRoot = globalAt(fixed, stampNs, gf) ?: return false
        if (fixedRoot != root) return false
        TfMath.multiply(TfMath.invertRigid(gf, DoubleArray(16)), g, out)
        return true
    }

    /** A dynamic frame that has not arrived for more than `timeoutNs` is stale. */
    fun isStale(id: String, nowNs: Long, timeoutNs: Long): Boolean {
        val f = frames[id] ?: return false
        if (f.isStatic || f.lastReceivedNs == 0L) return false
        return nowNs - f.lastReceivedNs > timeoutNs
    }

    /** Picks a reasonable fixed frame: REP-105 names first, otherwise the first root. */
    fun chooseDefaultFixedFrame(): String? {
        for (name in PREFERRED_FIXED_FRAMES) if (frames.containsKey(name)) return name
        val ids = frameIds()
        return ids.firstOrNull { frames[it]?.parentId?.isEmpty() == true } ?: ids.firstOrNull()
    }

    private fun resolve(start: Frame) {
        if (start.pass == pass) return

        // Walk up to an already resolved frame, a root, or a repeated frame (cycle).
        chain.clear()
        var f: Frame? = start
        while (f != null && f.pass != pass && !f.visiting) {
            f.visiting = true
            chain.add(f)
            val pid = f.parentId
            f = if (pid.isEmpty()) null else frames[pid]
        }

        val cycle = f != null && f.pass != pass // we stopped because `f` was already in the chain
        var parentGlobal: DoubleArray? = null
        var rootId = chain.last().id
        var parentValid = !cycle
        if (f != null && !cycle) {
            parentGlobal = f.global
            rootId = f.rootId
            parentValid = f.valid
        }

        for (i in chain.indices.reversed()) {
            val node = chain[i]
            node.visiting = false
            node.pass = pass
            node.valid = parentValid
            if (!parentValid) continue
            val local = node.local
            if (parentGlobal == null) local.copyInto(node.global)
            else TfMath.multiply(parentGlobal, local, node.global)
            node.rootId = rootId
            parentGlobal = node.global
        }
    }

    companion object {
        val PREFERRED_FIXED_FRAMES = listOf("map", "world", "odom", "base_link", "base_footprint")
        /** Like the tf2 buffer: 10 s of history per frame. */
        const val CACHE_NS = 10_000_000_000L
        const val MAX_SAMPLES = 2000
        /** A stamp that goes back more than this relative to the latest of the same frame is a clock jump. */
        const val TIME_JUMP_NS = 1_000_000_000L

        /** tf2 in ROS 2 does not allow a leading '/', but many ROS 1 publishers send it. */
        fun normalizeId(raw: String): String = raw.replace("\u0000", "").trim().trimStart('/')
    }
}
