// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.diag

/** rcl_interfaces/Log levels (10, 20, 30, 40, 50). */
enum class LogLevel(val value: Int, val label: String) {
    DEBUG(10, "DEBUG"), INFO(20, "INFO"), WARN(30, "WARN"), ERROR(40, "ERROR"), FATAL(50, "FATAL");

    companion object {
        /** Intermediate or unknown levels are rounded down (like rcutils). */
        fun of(v: Int): LogLevel = entries.lastOrNull { v >= it.value } ?: DEBUG
    }
}

class LogEntry(val stampNs: Long, val level: LogLevel, val node: String, val message: String, val file: String = "", val line: Int = 0,
               val function: String = "")

/**
 * Latest `capacity` messages from /rosout with filters by minimum level and text (node or message).
 * `add` may be called from the DDS thread; reads from the UI thread.
 */
class LogBuffer(private val capacity: Int = 2000) {
    private val entries = ArrayDeque<LogEntry>()
    private var version = 0L

    /** Changes every time something is added or cleared (to avoid redrawing without changes). */
    val changeCount: Long get() = synchronized(this) { version }

    fun add(e: LogEntry) = synchronized(this) {
        entries.addLast(e)
        while (entries.size > capacity) entries.removeFirst()
        version++
    }

    fun clear() = synchronized(this) { entries.clear(); version++ }

    val size: Int get() = synchronized(this) { entries.size }

    /** Empty `nodes` = all. */
    fun filtered(minLevel: LogLevel, text: String, nodes: Set<String> = emptySet()): List<LogEntry> {
        val t = text.trim()
        return synchronized(this) {
            entries.filter { it.level.value >= minLevel.value && (nodes.isEmpty() || it.node in nodes) &&
                (t.isEmpty() || it.node.contains(t, true) || it.message.contains(t, true)) }
        }
    }

    /** The nodes that have written something, in alphabetical order. */
    fun nodes(): List<String> = synchronized(this) { entries.map { it.node }.distinct().sorted() }

    companion object {
        /** Text of a log line (to copy or share). */
        fun format(e: LogEntry, time: (Long) -> String): String =
            "${time(e.stampNs)} ${e.level.label} [${e.node}] ${e.message}" + if (e.file.isNotEmpty()) " (${e.file}:${e.line}${if (e.function.isNotEmpty()) " ${e.function}" else ""})" else ""
    }
}

/** diagnostic_msgs/DiagnosticStatus level (plus STALE if it stops arriving). */
enum class DiagLevel(val label: String, val rank: Int) {
    OK("OK", 0), WARN("WARN", 2), ERROR("ERROR", 3), STALE("STALE", 1);

    companion object {
        fun of(b: Int): DiagLevel = when (b) { 0 -> OK; 1 -> WARN; 2 -> ERROR; else -> STALE }
    }
}

class DiagEntry(
    val name: String, val level: DiagLevel, val message: String, val hardwareId: String,
    val values: List<Pair<String, String>>, val receivedMs: Long
)

/**
 * Latest status of each /diagnostics component (by name). Those that have gone more than
 * `staleMs` without updates become STALE, as in rqt_robot_monitor.
 */
class DiagnosticsModel(private val staleMs: Long = 10_000) {
    private val byName = LinkedHashMap<String, DiagEntry>()
    private var version = 0L

    val changeCount: Long get() = synchronized(this) { version }

    fun update(e: DiagEntry) = synchronized(this) { byName[e.name] = e; version++ }

    fun clear() = synchronized(this) { byName.clear(); version++ }

    /** Worst first (ERROR, WARN, STALE, OK) and then by name. */
    fun snapshot(nowMs: Long): List<DiagEntry> = synchronized(this) {
        byName.values.map { if (nowMs - it.receivedMs > staleMs && it.level != DiagLevel.STALE) DiagEntry(it.name, DiagLevel.STALE, it.message, it.hardwareId, it.values, it.receivedMs) else it }
    }.sortedWith(compareByDescending<DiagEntry> { it.level.rank }.thenBy { it.name.lowercase() })

    /** How many there are at each level (for the summary). */
    fun counts(nowMs: Long): Map<DiagLevel, Int> = snapshot(nowMs).groupingBy { it.level }.eachCount()
}

/** Time series for the plot: last `windowMs` of (time, value). Thread-safe. */
class PlotSeries(val path: String, private val windowMs: Long = 30_000, private val maxPoints: Int = 6000) {
    private val t = LongArray(maxPoints)
    private val v = DoubleArray(maxPoints)
    private var start = 0
    private var count = 0

    fun add(timeMs: Long, value: Double) = synchronized(this) {
        if (!value.isFinite()) return@synchronized
        val i = (start + count) % maxPoints
        t[i] = timeMs; v[i] = value
        if (count < maxPoints) count++ else start = (start + 1) % maxPoints
    }

    /** Copy of the points within the window ending at `nowMs`. */
    fun points(nowMs: Long): Pair<LongArray, DoubleArray> = synchronized(this) {
        val from = nowMs - windowMs
        var first = 0
        while (first < count && t[(start + first) % maxPoints] < from) first++
        val n = count - first
        val ts = LongArray(n); val vs = DoubleArray(n)
        for (k in 0 until n) { val i = (start + first + k) % maxPoints; ts[k] = t[i]; vs[k] = v[i] }
        ts to vs
    }

    fun clear() = synchronized(this) { start = 0; count = 0 }
}

/** Messages per second (average over the last `windowMs`). */
class RateMeter(private val windowMs: Long = 3000) {
    private val stamps = ArrayDeque<Long>()
    private var firstMs = -1L
    fun tick(nowMs: Long) = synchronized(this) { if (firstMs < 0) firstMs = nowMs; stamps.addLast(nowMs); trim(nowMs) }
    /** Messages in the window / window duration (at the start, the elapsed time, at least 1 s). */
    fun hz(nowMs: Long): Double = synchronized(this) {
        trim(nowMs)
        if (stamps.isEmpty()) return@synchronized 0.0
        stamps.size * 1000.0 / (nowMs - firstMs).coerceIn(1000L, windowMs)
    }
    private fun trim(nowMs: Long) { while (stamps.isNotEmpty() && nowMs - stamps.first() > windowMs) stamps.removeFirst() }
}
