// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.plot.BagPlotSource
import com.juancams.rviz2mini.plot.LivePlotSource
import com.juancams.rviz2mini.plot.PanelKind
import com.juancams.rviz2mini.plot.PanelSpec
import com.juancams.rviz2mini.plot.PlotLayout
import com.juancams.rviz2mini.plot.PlotMath
import com.juancams.rviz2mini.plot.PlotSpecs
import com.juancams.rviz2mini.plot.PlotViewport
import com.juancams.rviz2mini.plot.SeriesBuffer
import com.juancams.rviz2mini.plot.SeriesSpec
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabStore
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

const val PLOT_KEY_LAYOUT = "plotLayout"
const val PLOT_KEY_BAG = "plotBag"

/** What the plots tab needs from each platform: where the bags are, CSV export and import. */
interface PlotPlatform {
    /** Bags folder (and of imported CSVs). */
    val bagDir: File
    /** The MCAP bags offered as a source, most recent first. */
    fun bagFiles(): List<File> = bagDir.listFiles { f -> f.isFile && f.name.endsWith(".mcap") }.orEmpty().sortedByDescending { it.lastModified() }
    /** Folder where the exported CSV is written. */
    val exportDir: File
    /** Offers the just-exported CSV (Android: share). Returns a notice to show, if any. */
    fun share(file: File): String?
    /** Whether a CSV can be picked for import. */
    val canImportCsv: Boolean get() = false
    /** Asks the user for a CSV and copies it to `bagDir`; `done` receives the copy (null if cancelled or failed, with the reason). */
    fun importCsv(done: (File?, String?) -> Unit) { done(null, null) }
}

/** The PC one: the bags of [com.juancams.rviz2mini.bag.BagSession] and the CSV stays in ~/Documents (or in ~). */
open class JvmPlotPlatform : PlotPlatform {
    private val home = File(System.getProperty("user.home") ?: ".")
    override val bagDir: File get() = com.juancams.rviz2mini.bag.BagSession.dir()
    override val exportDir: File get() = File(home, "Documents").takeIf { it.isDirectory } ?: home
    override fun share(file: File): String? = file.absolutePath

    /** Copies `src` to `bagDir` without overwriting another CSV with the same name. */
    fun copyCsv(src: File): File {
        val name = src.name.let { if (it.endsWith(".csv", true)) it else "$it.csv" }
        val base = name.substring(0, name.length - 4)
        var target = File(bagDir, "$base.csv")
        var n = 2
        while (target.exists()) target = File(bagDir, "${base}_${n++}.csv")
        src.copyTo(target)
        return target
    }
}

/** The one the tab uses if nobody sets another (on Android each tab passes its own). */
@Volatile var plotPlatform: PlotPlatform = JvmPlotPlatform()

/** Request to open a series' editor (new if `series` is null). */
data class SeriesEditRequest(
    val panelId: String, val series: SeriesSpec?, val topics: List<Pair<String, String>>, val others: List<SeriesSpec>,
    val allowText: Boolean, val fieldHints: List<String>,
)

/**
 * State and logic of the plots tab: up to [PlotSpecs.MAX_PANELS] panels with the same time
 * axis and the same cursor, series from any topic with transformations, XY panels, CSV
 * export and, instead of live, the data of an MCAP bag (or a CSV) with a timeline.
 *
 * Everything on the main thread ([Platform.main]); bag reading on a separate thread. [frame] changes
 * with every frame that has to be drawn. Between [resume] and [pause] the screen refreshes.
 */
class PlotController(val tabId: String, private val platform: PlotPlatform = plotPlatform) {

    val registry: InterfaceRegistry = Interfaces.get()
    private val store = TabStore.get()
    private fun setting(key: String): String? = store.get(tabId)?.settings?.get(key)
    private fun saveSetting(key: String, value: String) = store.updateSetting(tabId, key, value)

    var layout by mutableStateOf(PlotLayout.fromJson(setting(PLOT_KEY_LAYOUT))); private set
    val viewport = PlotViewport((layout.windowS * 1e9).toLong(), System.currentTimeMillis() * 1_000_000L)
    /** Increments with every change that has to be drawn (data, window, cursor). */
    var frame by mutableLongStateOf(0L); private set
    /** Show below each panel the statistics of its series over what is visible (or between the two cursors). */
    var showStats by mutableStateOf(setting("plotStats") == "true"); private set
    fun toggleStats() { showStats = !showStats; saveSetting("plotStats", showStats.toString()) }
    /** Increments when what the bar shows changes (follow, window, source). */
    var stripVersion by mutableIntStateOf(0); private set

    private var live: LivePlotSource? = null
    var bag by mutableStateOf<BagPlotSource?>(null); private set
    /** Chosen bag (even if it is still being read). */
    var bagFile by mutableStateOf<File?>(null); private set
    private var loading: Future<*>? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "plot-bag").apply { isDaemon = true } }
    private val main = Platform.main
    private var active = false
    private var closed = false

    /** Notice for the user (shown briefly). */
    var message by mutableStateOf<String?>(null)
    var editRequest by mutableStateOf<SeriesEditRequest?>(null)
    var closeEditorRequests by mutableIntStateOf(0)

    private fun redraw() { frame++ }
    private fun strip() { stripVersion++ }

    private val tick = object : Runnable {
        override fun run() {
            if (!active || closed) return
            live?.updateFormulas()
            // With everything paused (LiveView) the axis stays still: data keeps arriving and is shown when following.
            if (bag == null && viewport.follow && !com.juancams.rviz2mini.LiveView.frozen) {
                val now = System.currentTimeMillis() * 1_000_000L
                // With header.stamp or a live bag the clock is a different one: follow the latest data.
                viewport.endNs = if (layout.headerStamp || RosConnection.bagPlaybackActive) live?.latestNs() ?: now else now
            }
            redraw()
            main.postDelayed(this, FRAME_MS)
        }
    }

    fun resume() { if (active || closed) return; active = true; main.post { tick.run() } }
    fun pause() { active = false }

    /**
     * A topic dropped on it (dashboard): with its field, one more series in the first panel; without it, the series editor
     * with the topic and the most likely field already filled in.
     */
    private val dropHandler = com.juancams.rviz2mini.dashboard.TopicDrop.Handler { p ->
        val panel = layout.panels.firstOrNull() ?: PanelSpec().also { saveLayout(layout.copy(panels = listOf(it))) }
        val field = p.field ?: com.juancams.rviz2mini.dashboard.TopicDrop.guessField(registry, p.type)
            ?: return@Handler tr("drop_plot_no", p.topic)
        val short = p.topic.substringAfterLast('/')
        val spec = SeriesSpec(topic = p.topic, type = p.type, field = field, label = "$short.$field")
        if (p.field != null) { saveSeries(SeriesEditRequest(panel.id, null, emptyList(), emptyList(), false, emptyList()), spec); tr("drop_plot_added", spec.label) }
        else { editSeries(panel, spec); tr("drop_plot_choose", p.topic) }
    }

    fun close() {
        com.juancams.rviz2mini.dashboard.TopicDrop.unregister(tabId, dropHandler)
        closed = true; active = false
        playing = null
        loading?.cancel(true)
        resetDetail()
        live?.close(); live = null
        io.shutdownNow()
    }

    // ---------------------------------------------------------------- sources

    private fun startLive() {
        resetDetail()
        live?.close()
        bag = null; bagFile = null
        live = LivePlotSource(layout.allSeries, registry, layout.headerStamp)
        viewport.originNs = null
        viewport.follow = true
        viewport.cursorNs = null
        viewport.cursorBNs = null
        redraw(); strip()
    }

    private var loadingName by mutableStateOf<String?>(null)

    private fun openBag(f: File) {
        resetDetail()
        live?.close(); live = null
        bagFile = f
        bag = null
        loading?.cancel(true)
        val series = layout.allSeries
        val headerStamp = layout.headerStamp
        loadingName = f.name
        loading = io.submit {
            val result = try {
                if (f.name.endsWith(".csv", ignoreCase = true)) BagPlotSource.fromCsv(f, series, registry)
                else BagPlotSource.load(f, series, registry, { Thread.currentThread().isInterrupted }, headerStamp)
            } catch (e: Exception) {
                main.post { if (!closed && bagFile == f) { message = tr("plot_bag_failed", e.message); loadingName = null; goLive() } }
                return@submit
            }
            main.post {
                if (closed || bagFile != f) return@post
                loadingName = null
                bag = result
                viewport.originNs = result.startNs
                viewport.follow = false
                viewport.windowNs = minOf(viewport.windowNs, maxOf(1_000_000_000L, result.endNs - result.startNs))
                viewport.endNs = result.startNs + viewport.windowNs
                viewport.cursorNs = result.startNs
                viewport.cursorBNs = null
                redraw(); strip()
                scheduleDetail()
            }
        }
        strip()
    }

    fun goLive() {
        saveSetting(PLOT_KEY_BAG, "")
        startLive()
    }

    fun buffers(): Map<String, SeriesBuffer> = detail ?: bag?.buffers ?: live?.buffers.orEmpty()
    fun errors(): Map<String, String> = bag?.errors ?: live?.errors.orEmpty()
    fun textLabels(): Map<String, List<String>> = (bag?.texts ?: live?.texts)?.labels().orEmpty()

    /** Bags and CSVs in the folder, to choose the source. */
    fun sources(): List<File> {
        val csvs = platform.bagDir.listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }.orEmpty().sortedByDescending { it.lastModified() }
        return platform.bagFiles() + csvs
    }
    fun pickSource(f: File) { saveSetting(PLOT_KEY_BAG, f.name); openBag(f) }
    val canImportCsv: Boolean get() = platform.canImportCsv
    fun importCsv() = platform.importCsv { f, err ->
        main.post {
            if (closed) return@post
            if (f != null) pickSource(f) else if (err != null) message = tr("plot_bag_failed", err)
        }
    }

    // ---------------------------------------------------------------- large bags

    /** Bag series with the range [detailFrom, detailTo] at full resolution (null = the reduced view). */
    private var detail by mutableStateOf<Map<String, SeriesBuffer>?>(null)
    private var detailFrom = 0L
    private var detailTo = -1L
    private var detailLoading: Future<*>? = null
    private var detailToken = 0

    private fun scheduleDetail() {
        if (bag?.decimated.isNullOrEmpty()) return
        val token = ++detailToken
        main.postDelayed({ if (token == detailToken) checkDetail() }, DETAIL_DELAY_MS)
    }

    /** If what is visible fits at full resolution and has not been read yet, read it (with half a window of margin on each side). */
    private fun checkDetail() {
        val b = bag ?: return
        if (closed || b.decimated.isEmpty()) return
        val from = viewport.startNs; val to = viewport.endNs
        if (from >= detailFrom && to <= detailTo) return
        val w = to - from
        val wantFrom = maxOf(b.startNs, from - w / 2); val wantTo = minOf(b.endNs, to + w / 2)
        if (!b.detailFits(wantFrom, wantTo)) {
            if (detail != null) { detail = null; detailTo = -1; redraw(); strip() }
            return
        }
        detailLoading?.cancel(true)
        detailLoading = io.submit {
            val d = try { b.loadDetail(wantFrom, wantTo) { Thread.currentThread().isInterrupted } } catch (e: Exception) { null }
            main.post {
                if (d == null || closed || bag !== b) return@post
                detail = d; detailFrom = wantFrom; detailTo = wantTo
                redraw(); strip()
            }
        }
    }

    private fun resetDetail() {
        detailLoading?.cancel(true); detailLoading = null
        detail = null; detailFrom = 0; detailTo = -1
        detailToken++
    }

    /** Re-reads the series (when they change): live it resubscribes; with a bag it re-reads. */
    private fun reloadData() {
        val f = bagFile
        if (f != null) openBag(f) else startLive()
    }

    // ---------------------------------------------------------------- bar

    /** Whether it is running: live, following the latest data; with a bag, playing it. */
    val running: Boolean get() { stripVersion; return if (bag != null) playing != null else viewport.follow }
    val windowText: String get() { stripVersion; return windowLabel(viewport.windowNs / 1e9) }
    val sourceText: String get() {
        stripVersion
        val f = bagFile ?: return tr("plot_source_live")
        val b = bag ?: return tr("plot_loading_bag", loadingName ?: f.name)
        return if (b.decimated.isNotEmpty() && detail == null) tr("plot_bag_overview", f.name) else f.name
    }

    /** Live: follow the latest data or stay still. With a bag: play or stop it. */
    fun toggleFollow() {
        val b = bag
        if (b != null) { if (playing != null) playing = null else playBag(b) }
        else setFollow(!viewport.follow)
        strip()
    }

    fun setFollow(on: Boolean) {
        viewport.follow = on
        if (on) viewport.cursorNs = null
        strip(); redraw()
    }

    private var playing: Any? = null

    /** Plays the bag in real time from the cursor. */
    private fun playBag(b: BagPlotSource) {
        val startWall = System.nanoTime()
        val from = (viewport.cursorNs ?: b.startNs).coerceIn(b.startNs, b.endNs).let { if (it >= b.endNs) b.startNs else it }
        val token = Any()
        playing = token
        fun step() {
            if (closed || bag !== b || playing !== token) return
            val t = from + (System.nanoTime() - startWall)
            setCursor(minOf(t, b.endNs))
            if (t < b.endNs) main.postDelayed({ step() }, FRAME_MS) else { playing = null; strip() }
        }
        main.post { step() }
    }

    private fun setCursor(t: Long) {
        viewport.cursorNs = t
        // The window follows the cursor when it goes outside it.
        if (t > viewport.endNs || t < viewport.startNs) viewport.endNs = t + viewport.windowNs / 2
        redraw(); strip()
        scheduleDetail()
    }

    fun seekTo(f: Double) {
        val b = bag ?: return
        playing = null
        setCursor(b.startNs + ((b.endNs - b.startNs) * f).toLong())
    }

    /** Cursor position in the bag (0..1) and its text. */
    val timelineFraction: Float get() {
        frame
        val b = bag ?: return 0f
        val c = viewport.cursorNs ?: b.startNs
        return ((c - b.startNs).toDouble() / (b.endNs - b.startNs).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
    }
    val timelineText: String get() {
        frame
        val b = bag ?: return ""
        val c = viewport.cursorNs ?: b.startNs
        return String.format(Locale.ROOT, "%.2f / %.2f s", (c - b.startNs) / 1e9, (b.endNs - b.startNs).coerceAtLeast(1) / 1e9)
    }

    /** The window or cursor changed (gestures on a panel or a test). */
    fun onViewportChanged() {
        if (closed) return
        scheduleDetail()
        strip(); redraw()
        val w = viewport.windowNs / 1e9
        if (Math.abs(w - layout.windowS) > 1e-6) saveLayout(layout.copy(windowS = w))
    }

    fun setWindow(seconds: Double) { viewport.windowNs = (seconds * 1e9).toLong(); onViewportChanged() }

    fun addPanel() { if (layout.panels.size < PlotSpecs.MAX_PANELS) saveLayout(layout.copy(panels = layout.panels + PanelSpec())) }
    fun clear() { live?.clear(); redraw() }

    fun setHeaderStamp(on: Boolean) {
        saveLayout(layout.copy(headerStamp = on))
        reloadData()
    }

    fun exportCsv() {
        val all = layout.allSeries.mapNotNull { s -> buffers()[s.id]?.let { s.displayLabel to it } }
        if (all.all { it.second.size() == 0 }) { message = tr("plot_nothing_to_export"); return }
        val b = bag
        io.execute {
            try {
                val dir = platform.exportDir.apply { mkdirs() }
                val name = "plot_" + java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(java.util.Date()) + ".csv"
                val f = File(dir, name)
                // A reduced bag is exported in full at full resolution, reading it again.
                if (b != null && b.decimated.isNotEmpty()) f.bufferedWriter().use { b.exportCsv(it) }
                else f.writeText(PlotMath.csv(all))
                main.post { if (!closed) platform.share(f)?.let { message = it } }
            } catch (e: Exception) {
                main.post { message = tr("plot_bag_failed", e.message) }
            }
        }
    }

    // ---------------------------------------------------------------- panels and series

    private fun saveLayout(l: PlotLayout) {
        layout = l
        saveSetting(PLOT_KEY_LAYOUT, l.toJson())
        redraw()
    }

    fun setPanelKind(p: PanelSpec, kind: PanelKind) {
        // XY needs exactly x and y: the first two series are kept.
        saveLayout(layout.replacePanel(p.copy(kind = kind, series = if (kind == PanelKind.XY) p.series.take(2) else p.series)))
        reloadData()
    }
    fun removePanel(p: PanelSpec) { saveLayout(layout.copy(panels = layout.panels.filter { it.id != p.id })); reloadData() }
    fun toggleSameScale(p: PanelSpec) = saveLayout(layout.replacePanel(p.copy(sameScale = !p.sameScale)))
    fun toggleScatter(p: PanelSpec) = saveLayout(layout.replacePanel(p.copy(scatter = !p.scatter)))
    fun removeSeries(p: PanelSpec, s: SeriesSpec) { saveLayout(layout.replacePanel(p.copy(series = p.series.filter { it.id != s.id }))); reloadData() }

    /** Opens a series' editor: suggests the bag's topics or, live, the network's. */
    fun editSeries(p: PanelSpec, s: SeriesSpec?) {
        fun open(topics: List<Pair<String, String>>) {
            if (closed) return
            editRequest = SeriesEditRequest(p.id, s, topics, layout.allSeries.filter { it.id != s?.id }, p.kind == PanelKind.STATES, bag?.columns.orEmpty())
        }
        val b = bag
        if (b != null) { open(b.topics); return }
        RosConnection.discover(waitMs = 300) { g -> main.post { open(g?.topics()?.map { it.name to it.type }.orEmpty()) } }
    }

    fun saveSeries(req: SeriesEditRequest, edited: SeriesSpec) {
        editRequest = null
        val current = layout.panels.firstOrNull { it.id == req.panelId } ?: return
        val list = current.series.toMutableList()
        val i = list.indexOfFirst { it.id == edited.id }
        if (i >= 0) list[i] = edited else list.add(edited)
        val old = req.series
        // Formulas that used this series by name keep working if it is renamed.
        saveLayout(layout.replacePanel(current.copy(series = list)).let { l -> if (old == null) l else l.renameReferences(old.displayLabel, edited.displayLabel) })
        reloadData()
    }

    // ---------------------------------------------------------------- tests

    fun latestNs(): Long? = live?.latestNs()
    fun saveLayoutAndReload(l: PlotLayout) { saveLayout(l); reloadData() }
    fun openBagFile(f: File) = pickSource(f)
    val isPlaying: Boolean get() = playing != null
    val detailRange: LongRange? get() = if (detail != null) detailFrom..detailTo else null
    fun csv(): String = PlotMath.csv(layout.allSeries.mapNotNull { s -> buffers()[s.id]?.let { s.displayLabel to it } })

    // At the end: needs all properties already initialized.
    init {
        val savedBag = setting(PLOT_KEY_BAG)?.takeIf { it.isNotBlank() }?.let { File(platform.bagDir, it) }?.takeIf { it.isFile }
        if (savedBag != null) openBag(savedBag) else startLive()
        com.juancams.rviz2mini.dashboard.TopicDrop.register(tabId, dropHandler)
    }

    companion object {
        private const val DETAIL_DELAY_MS = 300L
        const val FRAME_MS = 33L

        fun windowLabel(s: Double): String = when {
            s >= 60 && s % 60 < 1e-6 -> tr("plot_window_min", (s / 60).toInt())
            s >= 10 -> tr("plot_window_s", String.format(Locale.ROOT, "%.0f", s))
            else -> tr("plot_window_s", String.format(Locale.ROOT, "%.1f", s))
        }
    }
}
