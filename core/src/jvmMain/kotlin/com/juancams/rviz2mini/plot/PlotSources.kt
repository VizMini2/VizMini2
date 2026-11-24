// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plot

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.bag.BagPlayer
import com.juancams.rviz2mini.bag.McapReader
import com.juancams.rviz2mini.indicators.IndicatorMath
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.profile
import java.io.File

/** Numeric value of the series in a message, or null if the field is missing or not a number. */
fun SeriesSpec.extract(msg: MsgValue): Double? = IndicatorMath.toDouble(IndicatorMath.valueAt(msg, this.field))

/**
 * Texts of the text series (for the state panels): each distinct text gets a number
 * (0, 1, 2…), which is what is stored in the buffer.
 */
class TextCodes {
    private val codes = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, Int>>()
    private val names = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    @Synchronized fun code(seriesId: String, text: String): Double {
        val m = codes.getOrPut(seriesId) { HashMap() }
        return m.getOrPut(text) { names.getOrPut(seriesId) { java.util.concurrent.CopyOnWriteArrayList() }.add(text); m.size }.toDouble()
    }

    /** Texts of each text series, by their number. */
    fun labels(): Map<String, List<String>> = names

    fun clear() { synchronized(this) { codes.clear(); names.clear() } }
}

/** Value of the series: the number, or the code of its text if it is a text series. */
fun SeriesSpec.extractAny(msg: Map<String, Any?>, texts: TextCodes): Double? {
    val v = IndicatorMath.valueAt(msg, field) ?: return null
    return if (v is String) texts.code(id, v) else IndicatorMath.toDouble(v)
}

/** The message's `header.stamp` in ns, or null if it has none (or it is zero, not filled in). */
fun stampNs(msg: Map<String, Any?>): Long? {
    val stamp = (msg["header"] as? Map<*, *>)?.get("stamp") as? Map<*, *> ?: return null
    val ns = (stamp["sec"] as? Number ?: return null).toLong() * 1_000_000_000L + (stamp["nanosec"] as? Number ?: return null).toLong()
    return ns.takeIf { it > 0 }
}

/**
 * Formula series: every time the first series they use has new samples, the value is computed
 * at those instants with the latest value of the others (like the cursor). Those that cannot be
 * computed (badly written formula, a series that does not exist) leave their reason in `errors`.
 */
class FormulaRunner(series: List<SeriesSpec>, private val buffers: Map<String, SeriesBuffer>, errors: MutableMap<String, String>) {

    /** `formula` or `script` (one of the two); `names`: the inputs in order (the first one drives). */
    private class Item(val spec: SeriesSpec, val formula: Formula?, val script: LuaScript?, val names: List<String>, val refs: Map<String, String>,
                       val filter: SeriesFilter) {
        var lastT = Long.MIN_VALUE
        var failed = false
    }

    private val errors = errors

    private val items = ArrayList<Item>()

    init {
        val byLabel = series.filter { it.displayLabel.isNotBlank() }.associateBy { it.displayLabel }
        for (s in series.filter { it.isFormula }) {
            try {
                val f = if (s.isScript) null else Formula.parse(s.formula)
                val names = f?.refs ?: s.inputList.ifEmpty { throw FormulaException("Choose at least one input series") }
                val refs = names.associateWith { name ->
                    val target = byLabel[name] ?: throw FormulaException("No series named {$name}")
                    if (target.id == s.id) throw FormulaException("A formula can't use itself")
                    target.id
                }
                val script = if (s.isScript) LuaScript(s.scriptGlobals, s.script, names.size) else null
                items += Item(s, f, script, names, refs, SeriesFilter(s))
            } catch (e: FormulaException) {
                errors[s.id] = e.message ?: "Bad formula"
            } catch (e: ScriptException) {
                errors[s.id] = "Lua: ${e.message}"
            }
        }
    }

    val isEmpty: Boolean get() = items.isEmpty()

    /** Computes what is new (live, every frame; with a bag, once when loading it). */
    fun update() {
        for (it in items) {
            if (it.failed) continue
            val out = buffers[it.spec.id] ?: continue
            val driver = buffers[it.refs.getValue(it.names.first())] ?: continue
            // The first series' buffer has started over (it was cleared or the clock jumped).
            val last = driver.lastTime() ?: continue
            if (last < it.lastT) { it.lastT = Long.MIN_VALUE; it.filter.reset(); out.clear() }
            val from = if (it.lastT == Long.MIN_VALUE) Long.MIN_VALUE else it.lastT + 1
            val (ts, _) = driver.range(from, Long.MAX_VALUE)
            for (t in ts) {
                if (t < from) continue
                val v = if (it.script != null) {
                    val inputs = DoubleArray(it.names.size) { k -> buffers[it.refs.getValue(it.names[k])]?.valueAt(t) ?: Double.NaN }
                    if (inputs.any { x -> x.isNaN() }) continue
                    try { it.script.call(t / 1e9, inputs) } catch (e: ScriptException) {
                        it.failed = true; errors[it.spec.id] = "Lua: ${e.message}"; break
                    } ?: continue
                } else it.formula!!.eval { name -> buffers[it.refs.getValue(name)]?.valueAt(t) } ?: continue
                it.filter.apply(t, v)?.let { y -> out.add(t, y) }
            }
            if (ts.isNotEmpty()) it.lastT = maxOf(it.lastT, ts.last())
        }
    }
}

/**
 * Live data: one subscription per topic (even if several series use it) and one buffer per series. The
 * time is the arrival time (wall clock) by default, or with `headerStamp`
 * each message's `header.stamp` (the arrival time if it has none).
 */
class LivePlotSource(series: List<SeriesSpec>, registry: InterfaceRegistry, headerStamp: Boolean = false) {
    val buffers: Map<String, SeriesBuffer> = series.associate { it.id to SeriesBuffer() }
    /** Why each series cannot be subscribed to, by id. */
    val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val subs = ArrayList<DynRos.Subscription>()
    private val formulas = FormulaRunner(series, buffers, errors)
    val texts = TextCodes()
    @Volatile var paused = false

    /** Non-ROS sources (UDP, MQTT, ZeroMQ) and the series that read from each one. */
    private val streams = ArrayList<Pair<JsonStream, List<SeriesSpec>>>()

    init {
        for ((url, group) in series.filter { !it.isFormula && JsonStream.isStream(it.topic) }.groupBy { it.topic.trim() }) {
            val filters = group.associate { it.id to SeriesFilter(it) }
            val stream = JsonStream.create(url) { msg ->
                if (paused) return@create
                val now = (if (headerStamp) stampNs(msg) else null) ?: (System.currentTimeMillis() * 1_000_000L)
                for (s in group) {
                    val raw = s.extractAny(msg, texts) ?: continue
                    filters.getValue(s.id).apply(now, raw)?.let { buffers.getValue(s.id).add(now, it) }
                }
            }
            if (stream == null) { for (s in group) errors[s.id] = "Not a valid address: $url"; continue }
            streams += stream to group
            stream.start()
        }
        for ((topic, group) in series.filter { it.topic.isNotBlank() && !it.isFormula && !JsonStream.isStream(it.topic) }.groupBy { DynRos.normalizeName(it.topic) to it.type }) {
            try {
                // Without a type (a series imported from PlotJuggler): the one the graph says.
                val type = topic.second.ifBlank {
                    com.juancams.rviz2mini.ros.RosConnection.graph()?.topics(includeHidden = true)?.firstOrNull { it.name == topic.first }?.types?.firstOrNull()
                        ?: throw IllegalStateException("${topic.first} is not on the network yet (its type is unknown)")
                }
                val spec = registry.msg(type)
                val filters = group.associate { it.id to SeriesFilter(it) }
                subs += DynRos.subscribe(topic.first, spec, QosPreset.SENSOR.profile()) { msg ->
                    if (paused) return@subscribe
                    // With a live bag, the bag time (unless the header time is requested).
                    val now = (if (headerStamp) stampNs(msg) else null) ?: com.juancams.rviz2mini.ros.RosConnection.bagMessageTimeNs()
                        ?: (System.currentTimeMillis() * 1_000_000L)
                    for (s in group) {
                        val raw = s.extractAny(msg, texts) ?: continue
                        filters.getValue(s.id).apply(now, raw)?.let { buffers.getValue(s.id).add(now, it) }
                    }
                }
            } catch (e: Exception) {
                for (s in group) errors[s.id] = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Computes the formula series and updates the source errors (UI thread, every frame). */
    fun updateFormulas() {
        if (!formulas.isEmpty) formulas.update()
        for ((stream, group) in streams) {
            val e = stream.error
            for (s in group) if (e != null) errors[s.id] = e else if (stream.received > 0) errors.remove(s.id)
        }
    }

    /** The last instant with data (to follow with header.stamp, which may be on another clock). */
    fun latestNs(): Long? = buffers.values.mapNotNull { it.lastTime() }.maxOrNull()

    fun clear() { for (b in buffers.values) b.clear(); texts.clear() }

    fun close() { for (s in subs) s.close(); subs.clear(); for ((st, _) in streams) st.close(); streams.clear() }
}

/**
 * Data from an MCAP bag: it is read (only the series' topics) without publishing anything. The time is each
 * message's log time (or header.stamp if requested).
 *
 * Large bags: series with more than [fullLimit] messages are stored downsampled (minimum and maximum of
 * each of [OVERVIEW_BUCKETS] spans, which is what is seen with the window zoomed out) and, when zooming in,
 * [loadDetail] reads only the visible span at full resolution.
 */
class BagPlotSource private constructor(
    val file: File, val buffers: Map<String, SeriesBuffer>, val errors: Map<String, String>,
    val startNs: Long, val endNs: Long, val topics: List<Pair<String, String>>,
    val texts: TextCodes,
    /** Series stored downsampled. */
    val decimated: Set<String>,
    /** Bag messages per second of each downsampled series (to know how much a span takes). */
    private val ratePerNs: Map<String, Double>,
    private val series: List<SeriesSpec>, private val registry: InterfaceRegistry, private val headerStamp: Boolean
) {
    /**
     * CSV of the whole bag at full resolution without loading it into memory: it is walked in order and each
     * message gives a row with the latest value of each series (formulas and scripts computed on
     * each row). The columns, like [PlotMath.csv]. In the background.
     */
    fun exportCsv(out: java.io.Writer, cancelled: () -> Boolean = { false }) {
        val order = series
        out.write("time_s,stamp_s")
        for (s in order) out.write("," + PlotMath.csvField(s.displayLabel))
        out.write("\n")
        val last = HashMap<String, Double>()
        val formulas = order.filter { it.isFormula }.mapNotNull { s ->
            try {
                val byLabel = order.associateBy { it.displayLabel }
                if (s.isScript) Triple(s, null as Formula?, LuaScript(s.scriptGlobals, s.script, s.inputList.size) to s.inputList.map { byLabel[it]?.id })
                else Triple(s, Formula.parse(s.formula), null)
            } catch (_: Exception) { null }
        }
        val byLabelId = order.associate { it.displayLabel to it.id }
        McapReader.open(file).use { r ->
            val byChannel = channelsFor(r, order, registry, HashMap())
            var t0 = Long.MIN_VALUE
            for (m in r.messages(channelIds = byChannel.keys)) {
                if (cancelled()) break
                val (spec, readers) = byChannel[m.channelId] ?: continue
                val msg = try { BagPlayer.deserialize(spec, m.data) } catch (_: Exception) { continue }
                val t = (if (headerStamp) stampNs(msg) else null) ?: m.logTimeNs
                if (t0 == Long.MIN_VALUE) t0 = t
                for ((s, f) in readers) { val raw = s.extractAny(msg, texts) ?: continue; f.apply(t, raw)?.let { last[s.id] = it } }
                for ((s, f, lua) in formulas) {
                    val v = if (f != null) f.eval { name -> byLabelId[name]?.let { last[it] } }
                    else {
                        val ids = lua!!.second
                        val inputs = DoubleArray(ids.size) { k -> ids[k]?.let { last[it] } ?: Double.NaN }
                        if (inputs.any { it.isNaN() }) null else try { lua.first.call(t / 1e9, inputs) } catch (_: Exception) { null }
                    }
                    if (v != null) last[s.id] = v * s.scale + s.offset
                }
                out.write(String.format(java.util.Locale.ROOT, "%.6f,%.6f", (t - t0) / 1e9, t / 1e9))
                for (s in order) { out.write(","); last[s.id]?.let { out.write(it.toString()) } }
                out.write("\n")
            }
        }
    }

    /** Data columns if the source is a CSV (to suggest them as fields). */
    var columns: List<String> = emptyList(); private set

    /** Whether the span [fromNs, toNs] fits at full resolution. */
    fun detailFits(fromNs: Long, toNs: Long): Boolean = decimated.all { ratePerNs.getValue(it) * (toNs - fromNs) <= DETAIL_LIMIT }

    /**
     * The series with [fromNs, toNs] at full resolution and the rest of the bag downsampled (those that were
     * not downsampled, as they are). Formulas are recomputed. In the background.
     */
    fun loadDetail(fromNs: Long, toNs: Long, cancelled: () -> Boolean = { false }): Map<String, SeriesBuffer> {
        val detail = HashMap<String, SeriesBuffer>()
        for (s in series) {
            when {
                s.isFormula -> detail[s.id] = SeriesBuffer(SeriesBuffer.DEFAULT_CAPACITY * 4)
                s.id in decimated -> detail[s.id] = SeriesBuffer(SeriesBuffer.DEFAULT_CAPACITY * 4)
                else -> buffers[s.id]?.let { detail[s.id] = it }
            }
        }
        // Before the span: the downsampled data.
        for (id in decimated) { val (t, v) = buffers.getValue(id).range(Long.MIN_VALUE, fromNs - 1); for (k in t.indices) if (t[k] < fromNs) detail.getValue(id).add(t[k], v[k]) }
        McapReader.open(file).use { r ->
            val wanted = series.filter { it.id in decimated }
            val byChannel = channelsFor(r, wanted, registry, HashMap())
            // A bit of margin: with header.stamp the time is not exactly the log time.
            for (m in r.messages(startNs = fromNs - MARGIN_NS, endNs = toNs + MARGIN_NS, channelIds = byChannel.keys)) {
                if (cancelled()) break
                val (spec, readers) = byChannel[m.channelId] ?: continue
                val msg = try { BagPlayer.deserialize(spec, m.data) } catch (_: Exception) { continue }
                val t = (if (headerStamp) stampNs(msg) else null) ?: m.logTimeNs
                if (t < fromNs || t > toNs) continue
                for ((s, f) in readers) {
                    val raw = s.extractAny(msg, texts) ?: continue
                    f.apply(t, raw)?.let { detail.getValue(s.id).add(t, it) }
                }
            }
        }
        // After the span: the downsampled data.
        for (id in decimated) { val (t, v) = buffers.getValue(id).range(toNs + 1, Long.MAX_VALUE); for (k in t.indices) if (t[k] > toNs) detail.getValue(id).add(t[k], v[k]) }
        FormulaRunner(series, detail, HashMap()).update()
        return detail
    }

    companion object {
        /** From how many messages a series is stored downsampled (variable for the tests). */
        @Volatile var fullLimit = 150_000L
        /** The most read at full resolution at once (messages per series). */
        const val DETAIL_LIMIT = 200_000.0
        const val OVERVIEW_BUCKETS = 3000
        private const val MARGIN_NS = 2_000_000_000L

        /** Topics (name, type) in the bag. */
        fun topicsOf(file: File): List<Pair<String, String>> = McapReader.open(file).use { r ->
            r.channels.values.map { it.topic to it.schemaName }.distinct().sortedBy { it.first }
        }

        /** Per channel: its type and the series that read it. */
        private fun channelsFor(r: McapReader, series: List<SeriesSpec>, registry: InterfaceRegistry, errors: MutableMap<String, String>):
                Map<Int, Pair<com.juancams.rviz2mini.ros.dyn.MsgSpec, List<Pair<SeriesSpec, SeriesFilter>>>> {
            val byChannel = HashMap<Int, Pair<com.juancams.rviz2mini.ros.dyn.MsgSpec, List<Pair<SeriesSpec, SeriesFilter>>>>()
            for (s in series.filter { !it.isFormula }) {
                val chans = r.channels.values.filter { it.topic == DynRos.normalizeName(s.topic) }
                if (chans.isEmpty()) { errors[s.id] = "Not in this bag"; continue }
                for (c in chans) {
                    if (c.messageEncoding != "cdr" || !registry.has(c.schemaName)) { errors[s.id] = "Unknown type ${c.schemaName}"; continue }
                    val spec = registry.msg(c.schemaName)
                    val prev = byChannel[c.id]?.second.orEmpty()
                    byChannel[c.id] = spec to (prev + (s to SeriesFilter(s)))
                }
            }
            return byChannel
        }

        /** "Topic" name of the series of a CSV (the field is the column name). */
        const val CSV_TOPIC = "csv"

        /**
         * A CSV as if it were a bag: the first column named time/stamp/t (or the first one) is the
         * time in seconds; the others, series (topic [CSV_TOPIC], field = column name).
         */
        fun fromCsv(file: File, series: List<SeriesSpec>, registry: InterfaceRegistry): BagPlotSource {
            val lines = file.readLines().filter { it.isNotBlank() }
            require(lines.isNotEmpty()) { "Empty CSV" }
            val sep = if (lines[0].count { it == ';' } > lines[0].count { it == ',' }) ';' else ','
            val header = splitCsv(lines[0], sep)
            val timeCol = header.indexOfFirst { it.trim().lowercase() in setOf("time", "time_s", "timestamp", "stamp", "stamp_s", "t", "__time") }.let { if (it < 0) 0 else it }
            val cols = header.mapIndexed { i, h -> h.trim().ifEmpty { "col$i" } }
            val buffers = series.associate { it.id to SeriesBuffer(SeriesBuffer.DEFAULT_CAPACITY * 4) }
            val errors = HashMap<String, String>()
            val wanted = series.filter { !it.isFormula }.mapNotNull { s ->
                val c = cols.indexOf(s.field.trim())
                if (s.topic != CSV_TOPIC || c < 0) { errors[s.id] = "No column '${s.field}' in this CSV"; null } else Triple(s, c, SeriesFilter(s))
            }
            var first = Long.MAX_VALUE; var last = Long.MIN_VALUE
            for ((row, line) in lines.drop(1).withIndex()) {
                val f = splitCsv(line, sep)
                val tS = f.getOrNull(timeCol)?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: row.toDouble()
                val t = (tS * 1e9).toLong()
                for ((s, c, filter) in wanted) {
                    val v = f.getOrNull(c)?.trim()?.toDoubleOrNull() ?: continue
                    filter.apply(t, v)?.let { buffers.getValue(s.id).add(t, it) }
                }
                if (t < first) first = t
                if (t > last) last = t
            }
            FormulaRunner(series, buffers, errors).update()
            if (first > last) { first = 0; last = 1_000_000_000L }
            val topics = listOf(CSV_TOPIC to "csv")
            return BagPlotSource(file, buffers, errors, first, last, topics, TextCodes(), emptySet(), emptyMap(), series, registry, false)
                .also { it.columns = cols.filterIndexed { i, _ -> i != timeCol } }
        }

        private fun splitCsv(line: String, sep: Char): List<String> {
            val out = ArrayList<String>(); val cur = StringBuilder(); var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == sep && !quoted -> { out += cur.toString(); cur.clear() }
                    else -> cur.append(c)
                }
                i++
            }
            out += cur.toString()
            return out
        }

        /** Reads the bag (in the background: it can take a while with large bags). `cancelled` stops it. */
        fun load(file: File, series: List<SeriesSpec>, registry: InterfaceRegistry, cancelled: () -> Boolean = { false },
                 headerStamp: Boolean = false): BagPlotSource {
            val buffers = series.associate { it.id to SeriesBuffer(SeriesBuffer.DEFAULT_CAPACITY * 4) }
            val errors = HashMap<String, String>()
            val texts = TextCodes()
            McapReader.open(file).use { r ->
                val topics = r.channels.values.map { it.topic to it.schemaName }.distinct().sortedBy { it.first }
                val byChannel = channelsFor(r, series, registry, errors)
                // Series of channels with many messages are downsampled.
                val counts = HashMap<String, Long>()
                for ((ch, pair) in byChannel) for ((s, _) in pair.second) counts[s.id] = (counts[s.id] ?: 0) + (r.channelMessageCounts[ch] ?: 0)
                val span = (r.endTimeNs - r.startTimeNs).coerceAtLeast(1)
                val reducers = counts.filterValues { it > fullLimit }.keys.associateWith { Decimator(r.startTimeNs, r.endTimeNs, OVERVIEW_BUCKETS) }
                var first = Long.MAX_VALUE; var last = Long.MIN_VALUE
                if (byChannel.isNotEmpty()) for (m in r.messages(channelIds = byChannel.keys)) {
                    if (cancelled()) break
                    val (spec, readers) = byChannel[m.channelId] ?: continue
                    val msg = try { BagPlayer.deserialize(spec, m.data) } catch (e: Exception) {
                        Log.w("VizMini2", "Mensaje ilegible en ${file.name}", e); continue
                    }
                    val t = (if (headerStamp) stampNs(msg) else null) ?: m.logTimeNs
                    for ((s, f) in readers) {
                        val raw = s.extractAny(msg, texts) ?: continue
                        val v = f.apply(t, raw) ?: continue
                        reducers[s.id]?.add(t, v) ?: buffers.getValue(s.id).add(t, v)
                    }
                    if (t < first) first = t
                    if (t > last) last = t
                }
                for ((id, d) in reducers) d.writeTo(buffers.getValue(id))
                FormulaRunner(series, buffers, errors).update()
                if (first > last) { first = 0; last = 1_000_000_000L }
                val rates = reducers.keys.associateWith { (counts.getValue(it).toDouble() / span) }
                return BagPlotSource(file, buffers, errors, first, last, topics, texts, reducers.keys, rates, series, registry, headerStamp)
            }
        }
    }
}
