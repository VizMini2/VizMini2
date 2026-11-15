// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.interop

import com.juancams.rviz2mini.dashboard.DashboardGrid
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.indicators.IndicatorKind
import com.juancams.rviz2mini.indicators.IndicatorList
import com.juancams.rviz2mini.indicators.IndicatorSpec
import com.juancams.rviz2mini.plot.PanelKind
import com.juancams.rviz2mini.plot.PanelSpec
import com.juancams.rviz2mini.plot.PlotLayout
import com.juancams.rviz2mini.plot.PlotSpecs
import com.juancams.rviz2mini.plot.SeriesSpec
import com.juancams.rviz2mini.plot.SeriesTransform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.rvizfile.RvizExporter
import com.juancams.rviz2mini.rviz.rvizfile.RvizImporter
import com.juancams.rviz2mini.tabs.TabSharing
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.CAMERA_KEY_KIND
import com.juancams.rviz2mini.ui.screens.CAMERA_KEY_TOPIC
import com.juancams.rviz2mini.ui.screens.DASHBOARD_KEY_LAYOUT
import com.juancams.rviz2mini.ui.screens.GPS_KEY_TOPIC
import com.juancams.rviz2mini.ui.screens.INDICATORS_KEY
import com.juancams.rviz2mini.ui.screens.MESSAGE_KEY_TOPIC
import com.juancams.rviz2mini.ui.screens.MESSAGE_KEY_TYPE
import com.juancams.rviz2mini.ui.screens.PLOT_KEY_LAYOUT
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The configuration files VizMini2 reads and writes: its own, RViz 2's, and the panel layouts (JSON) and plot layouts
 * (XML) of other ROS visualization tools. [label] is what the menus show.
 */
enum class ConfigFormat(private val fixedLabel: String?, val extension: String) {
    VIZMINI2("VizMini2", "vizmini2.json"), RVIZ(null, "rviz"), FOXGLOVE(null, "json"), PLOTJUGGLER(null, "xml");

    val label: String get() = fixedLabel ?: tr(when (this) { RVIZ -> "interop_format_rviz"; FOXGLOVE -> "interop_format_panels"; else -> "interop_format_plots" })
}

/** What an import produced: the tabs (a tab, or a dashboard followed by its widgets) and what had no equivalent. */
class ImportResult(val format: ConfigFormat, val tabs: List<TabSpec>, val notes: List<String>)

/** An export: the file's text and what could not be carried over. */
class ExportResult(val format: ConfigFormat, val fileName: String, val text: String, val notes: List<String>)

/** The type of each topic, from the ROS graph (empty if the topic is not on the network). */
object TopicTypes {
    @Volatile var lookup: (String) -> String? = { t -> RosConnection.graph()?.topics(includeHidden = true)?.firstOrNull { it.name == t }?.types?.firstOrNull() }
    fun of(topic: String): String = runCatching { lookup(topic) }.getOrNull().orEmpty()
    /** The graph's topics (for resolving PlotJuggler names). */
    @Volatile var all: () -> List<String> = { RosConnection.graph()?.topics(includeHidden = true)?.map { it.name }.orEmpty() }
}

/**
 * Reading and writing other tools' files: RViz 2 (.rviz), Foxglove layouts (.json) and PlotJuggler layouts (.xml),
 * besides VizMini2's own exported tabs. Formats are recognized by their content, not their name.
 */
object ConfigInterop {
    fun detect(text: String): ConfigFormat? {
        val t = text.trimStart()
        return when {
            TabSharing.parse(text) != null -> ConfigFormat.VIZMINI2
            t.startsWith("{") && ("\"configById\"" in t || ("\"content\"" in t && "\"panelType\"" in t)) -> ConfigFormat.FOXGLOVE
            t.startsWith("<") && ("<tabbed_widget" in t || "<plot " in t || "<plot>" in t) -> ConfigFormat.PLOTJUGGLER
            "Visualization Manager" in t -> ConfigFormat.RVIZ
            else -> null
        }
    }

    /** Throws IllegalArgumentException (with a message for the user) if it is not one of the formats. */
    fun import(text: String, name: String = ""): ImportResult = when (detect(text)) {
        ConfigFormat.VIZMINI2 -> ImportResult(ConfigFormat.VIZMINI2, TabSharing.parse(text)!!, emptyList())
        ConfigFormat.FOXGLOVE -> FoxgloveLayout.import(text, name)
        ConfigFormat.PLOTJUGGLER -> PlotJugglerLayout.import(text, name)
        ConfigFormat.RVIZ -> rviz(text, name)
        null -> throw IllegalArgumentException("Not a file VizMini2 can import")
    }

    private fun rviz(text: String, name: String): ImportResult {
        val r = RvizImporter.import(text)
        var tab = TabSpec.create(TabType.RVIZ, name.substringBeforeLast('.').ifBlank { "RViz" })
            .withSetting("displays", DisplayList.toJson(r.displays))
        r.fixedFrame?.let { tab = tab.withSetting("fixedFrame", it) }
        r.background?.let { tab = tab.withSetting("background", Colors.toHex(it)) }
        r.view?.let { tab = tab.withSetting("view", JSONObject(it).toString()) }
        r.goalTopic?.let { tab = tab.withSetting("goalTopic", it) }
        r.poseTopic?.let { tab = tab.withSetting("poseTopic", it) }
        r.pointTopic?.let { tab = tab.withSetting("pointTopic", it) }
        return ImportResult(ConfigFormat.RVIZ, listOf(tab), r.skipped.map { "RViz display without an equivalent: $it" })
    }

    /** The formats a tab (with its widgets, for a dashboard) can be exported to. */
    fun exportFormats(tab: TabSpec, all: List<TabSpec>): List<ConfigFormat> {
        val parts = if (tab.type == TabType.DASHBOARD) all.filter { it.dashboardId == tab.id } else listOf(tab)
        return listOfNotNull(
            ConfigFormat.VIZMINI2,
            ConfigFormat.RVIZ.takeIf { tab.type == TabType.RVIZ },
            ConfigFormat.FOXGLOVE.takeIf { parts.any { it.type in FoxgloveLayout.EXPORTABLE } },
            ConfigFormat.PLOTJUGGLER.takeIf { parts.any { it.type == TabType.PLOT } },
        )
    }

    fun export(tab: TabSpec, all: List<TabSpec>, format: ConfigFormat): ExportResult {
        val base = tab.name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "vizmini2" }
        return when (format) {
            ConfigFormat.VIZMINI2 -> ExportResult(format, "$base.vizmini2.json", TabSharing.export(tab, all), emptyList())
            ConfigFormat.RVIZ -> {
                val s = tab.settings
                val text = RvizExporter.export(DisplayList.fromJson(s["displays"]) ?: DisplayList.defaults(), s["fixedFrame"],
                    Colors.parseHex(s["background"]) ?: 0xFF303030.toInt(),
                    s["view"]?.let { v -> runCatching { JSONObject(v).let { o -> o.keys().asSequence().associateWith { k -> o.get(k).toString() } } }.getOrNull() } ?: emptyMap(),
                    s["goalTopic"], s["poseTopic"], s["pointTopic"], 1000)
                ExportResult(format, "$base.rviz", text, emptyList())
            }
            ConfigFormat.FOXGLOVE -> FoxgloveLayout.export(tab, all).let { (text, notes) -> ExportResult(format, "$base.foxglove.json", text, notes) }
            ConfigFormat.PLOTJUGGLER -> PlotJugglerLayout.export(tab, all).let { (text, notes) -> ExportResult(format, "$base.plotjuggler.xml", text, notes) }
        }
    }

    // ---------------------------------------------------------------- shared helpers

    /** The palette color closest to `rgb` ("#1f77b4"); -1 if it does not parse. */
    fun paletteIndex(rgb: String?): Int {
        val c = Colors.parseHex(rgb ?: return -1) ?: return -1
        fun d(a: Int, b: Int) = listOf(16, 8, 0).sumOf { s -> val x = ((a shr s) and 0xFF) - ((b shr s) and 0xFF); x * x }
        return PlotSpecs.PALETTE.indices.minByOrNull { d(PlotSpecs.PALETTE[it], c) } ?: -1
    }

    fun paletteHex(i: Int, n: Int): String = Colors.toHex(PlotSpecs.PALETTE[(if (i >= 0) i else n) % PlotSpecs.PALETTE.size]).lowercase()

    /**
     * Widgets without overlaps, each as close as possible to where it was: one that covers an already placed one goes to
     * the nearest free place, first sideways in the same rows and, only if there is none, further down.
     */
    internal fun settle(items: List<DashboardGrid.Item>): List<DashboardGrid.Item> {
        val placed = ArrayList<DashboardGrid.Item>()
        for (i in items.sortedWith(compareBy({ it.y }, { it.x }))) {
            if (placed.none { it.overlaps(i) }) { placed += i; continue }
            var best: DashboardGrid.Item? = null
            var dy = 0.0
            while (best == null) {
                val y = i.y + dy
                best = (0..((DashboardGrid.COLUMNS - i.w).toInt().coerceAtLeast(0) * 2)).map { i.copy(x = it / 2.0, y = y) }
                    .filter { c -> placed.none { it.overlaps(c) } }.minByOrNull { Math.abs(it.x - i.x) }
                dy += 1.0
            }
            placed += best
        }
        return placed
    }

    /** A dashboard with these widgets at these grid places (x, y, w, h in cells), or the only tab if there is one. */
    fun dashboard(name: String, parts: List<Pair<TabSpec, IntArray>>): List<TabSpec> {
        if (parts.size == 1) return listOf(parts[0].first.let { TabSpec(it.id, it.type, name.ifBlank { it.name }, it.settings) })
        val dash = TabSpec.create(TabType.DASHBOARD, name.ifBlank { "Layout" })
        // Panels smaller than a widget's minimum size grow and would cover their neighbors: those move to the nearest gap.
        val items = settle(parts.map { (t, r) -> DashboardGrid.Item(t.id, r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[3].toDouble()) })
        return listOf(dash.withSetting(DASHBOARD_KEY_LAYOUT, DashboardGrid.toJson(items))) + parts.map { it.first.withSetting(TabSpec.KEY_DASHBOARD, dash.id) }
    }

    /** A display for a topic, from its type (or, if unknown, its name). */
    fun displayFor(topic: String, type: String): DisplayConfig? {
        val byType = DisplayType.TOPIC_TYPES.firstOrNull { it.rosType == type && (it != DisplayType.ROBOT_MODEL || topic.endsWith("robot_description")) }
        val t = byType ?: guessDisplay(topic) ?: return null
        return DisplayConfig.create(t, topic.substringAfterLast('/').ifBlank { t.label }, topic)
    }

    private fun guessDisplay(topic: String): DisplayType? {
        val n = topic.lowercase()
        return when {
            n.endsWith("robot_description") -> DisplayType.ROBOT_MODEL
            n.endsWith("/tf") || n.endsWith("/tf_static") -> null
            "scan" in n -> DisplayType.LASER_SCAN
            "costmap" in n && !n.endsWith("footprint") || n.endsWith("/map") -> DisplayType.MAP
            "point" in n || "cloud" in n -> DisplayType.POINT_CLOUD2
            "marker_array" in n || n.endsWith("markers") -> DisplayType.MARKER_ARRAY
            "marker" in n -> DisplayType.MARKER
            "path" in n || "plan" in n -> DisplayType.PATH
            "odom" in n -> DisplayType.ODOMETRY
            "footprint" in n || "polygon" in n -> DisplayType.POLYGON
            "image" in n || "camera" in n -> DisplayType.IMAGE
            "pose" in n -> DisplayType.POSE
            else -> null
        }
    }

    fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(7)
}

// ==================================================================== Foxglove

/**
 * Foxglove layouts, in both forms: the JSON of "Export layout" (also Lichtblick's), with `configById` holding each
 * panel's settings ("Plot!abc123": {...}) and `layout`, a tree of splits ({first, second, direction: row|column,
 * splitPercentage}); and the newer programmatic one of the Foxglove SDK (`version`, `content`: panels, splits with
 * proportions, tabs and stacks). A layout becomes a dashboard with a widget per panel, placed where the splits put
 * it; exporting writes the first form, which the Foxglove app imports.
 */
object FoxgloveLayout {
    private const val ROWS = 12
    val EXPORTABLE = setOf(TabType.BUTTONS, TabType.RVIZ, TabType.PLOT, TabType.CAMERA, TabType.MESSAGE, TabType.TELEOP, TabType.INDICATORS,
        TabType.DIAGNOSTICS, TabType.PARAMETERS, TabType.NODE_GRAPH, TabType.GPS)

    // ------------------------------------------------ message paths

    /** "/odom.pose.pose.position.x" -> ("/odom", "pose.pose.position.x"); with a modifier, its transform and scale. */
    class Path(val topic: String, val field: String, val transform: SeriesTransform = SeriesTransform.NONE, val scale: Double = 1.0)

    fun parsePath(p: String, notes: MutableList<String>): Path? {
        var s = p.trim().removePrefix("\"").removeSuffix("\"")
        if (!s.startsWith("/")) return null
        var transform = SeriesTransform.NONE; var scale = 1.0
        val mod = Regex("""\.@(\w+)$""").find(s)
        if (mod != null) {
            s = s.substring(0, mod.range.first)
            when (mod.groupValues[1]) {
                "derivative" -> transform = SeriesTransform.DERIVATIVE
                "negative" -> scale = -1.0
                "rad2deg" -> scale = 180 / Math.PI
                "deg2rad" -> scale = Math.PI / 180
                else -> notes += "Path modifier @${mod.groupValues[1]} not supported in $p"
            }
        }
        val end = s.indexOfFirst { it == '.' || it == '[' || it == '{' }.let { if (it < 0) s.length else it }
        val topic = s.substring(0, end)
        var field = s.substring(end).removePrefix(".")
        if ("[:]" in field || Regex("""\[\d*:\d*]""").containsMatchIn(field)) {
            notes += "Array slices are not supported ($p): the first element is used"
            field = field.replace(Regex("""\[\d*:\d*]"""), "[0]")
        }
        // {name=="motor"} -> {name==motor}: both forms work here; quotes are kept.
        return Path(topic, field, transform, scale)
    }

    fun formatPath(topic: String, field: String): String {
        // Foxglove quotes string filter values: {name==motor} -> {name=="motor"}.
        val f = field.replace(Regex("""\{(\w+)==([^"'}\d-][^}]*)}""")) { m -> "{${m.groupValues[1]}==\"${m.groupValues[2]}\"}" }
        return if (f.isBlank()) topic else "$topic.$f"
    }

    // ------------------------------------------------ import

    fun import(text: String, name: String = ""): ImportResult {
        val root = JSONObject(text)
        val configs = root.optJSONObject("configById") ?: JSONObject()
        val notes = ArrayList<String>()
        // (panel kind, its settings, rectangle in fractions of the layout).
        var leaves = ArrayList<Triple<String, JSONObject, DoubleArray>>()
        // The Foxglove tabs that are not the open one: each becomes a dashboard of its own (title, how to walk it).
        val otherTabs = ArrayList<Pair<String, () -> Unit>>()
        fun walkV1(node: JSONObject?, x: Double, y: Double, w: Double, h: Double) {
            when (node?.optString("type")) {
                "panel" -> leaves += Triple(node.optString("panelType"), (node.optJSONObject("config") ?: JSONObject()).apply {
                    node.optString("title").takeIf { it.isNotBlank() && it != "null" }?.let { put("foxglovePanelTitle", it) } }, doubleArrayOf(x, y, w, h))
                "split", "stack" -> {
                    val items = node.optJSONArray(if (node.optString("type") == "split") "items" else "panels") ?: JSONArray()
                    val list = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    val sizes = list.map { it.optDouble(if (node.optString("type") == "split") "proportion" else "size", 1.0).coerceAtLeast(0.01) }
                    val total = sizes.sum()
                    var at = 0.0
                    val row = node.optString("type") == "split" && node.optString("direction", "row") == "row"
                    for ((i, item) in list.withIndex()) {
                        val f = sizes[i] / total
                        val child = item.optJSONObject("content") ?: item.optJSONObject("panel")
                        if (row) walkV1(child, x + w * at, y, w * f, h) else walkV1(child, x, y + h * at, w, h * f)
                        at += f
                    }
                }
                "tabs" -> {
                    val tabs = node.optJSONArray("tabs") ?: JSONArray()
                    val open = node.optInt("selectedTabIndex", 0)
                    for (k in 0 until tabs.length()) if (k != open) tabs.optJSONObject(k)?.let { t ->
                        otherTabs += t.optString("title").ifBlank { "Tab ${k + 1}" } to { walkV1(t.optJSONObject("content"), 0.0, 0.0, 1.0, 1.0) }
                    }
                    walkV1(tabs.optJSONObject(open)?.optJSONObject("content"), x, y, w, h)
                }
            }
        }
        fun walk(node: Any?, x: Double, y: Double, w: Double, h: Double) {
            when (node) {
                is String -> {
                    if (node.substringBefore('!') == "Tab") {
                        val c = configs.optJSONObject(node)
                        val tabs = c?.optJSONArray("tabs") ?: JSONArray()
                        val open = c?.optInt("activeTabIdx", 0) ?: 0
                        for (k in 0 until tabs.length()) if (k != open) tabs.optJSONObject(k)?.let { t ->
                            otherTabs += t.optString("title").ifBlank { "Tab ${k + 1}" } to { walk(t.opt("layout"), 0.0, 0.0, 1.0, 1.0) }
                        }
                        walk(tabs.optJSONObject(open)?.opt("layout"), x, y, w, h)
                    } else leaves += Triple(node.substringBefore('!'), configs.optJSONObject(node) ?: JSONObject(), doubleArrayOf(x, y, w, h))
                }
                is JSONObject -> {
                    val pct = node.optDouble("splitPercentage", 50.0).coerceIn(5.0, 95.0) / 100
                    if (node.optString("direction") == "column") {
                        walk(node.opt("first"), x, y, w, h * pct); walk(node.opt("second"), x, y + h * pct, w, h * (1 - pct))
                    } else {
                        walk(node.opt("first"), x, y, w * pct, h); walk(node.opt("second"), x + w * pct, y, w * (1 - pct), h)
                    }
                }
            }
        }
        fun parts(): List<Pair<TabSpec, IntArray>> = leaves.mapNotNull { (kind, config, r) ->
            val tab = panel(kind, config, notes) ?: return@mapNotNull null
            val gx = Math.round(r[0] * DashboardGrid.COLUMNS).toInt(); val gy = Math.round(r[1] * ROWS).toInt()
            val gw = (Math.round((r[0] + r[2]) * DashboardGrid.COLUMNS).toInt() - gx).coerceAtLeast(DashboardGrid.MIN_W)
            val gh = (Math.round((r[1] + r[3]) * ROWS).toInt() - gy).coerceAtLeast(DashboardGrid.MIN_H)
            tab to intArrayOf(gx.coerceAtMost(DashboardGrid.COLUMNS - DashboardGrid.MIN_W), gy, gw, gh)
        }
        if (root.has("content") && !root.has("configById")) walkV1(root.optJSONObject("content"), 0.0, 0.0, 1.0, 1.0)
        else walk(root.opt("layout") ?: configs.keys().asSequence().firstOrNull(), 0.0, 0.0, 1.0, 1.0)
        val base = name.substringBeforeLast('.').substringBeforeLast(".foxglove").ifBlank { "Layout" }
        val main = parts()
        val tabs = ArrayList<TabSpec>()
        if (main.isNotEmpty()) tabs += ConfigInterop.dashboard(base, main)
        // The other Foxglove tabs (and the tabs inside those), one dashboard each: "Layout · Tab title".
        var k = 0
        while (k < otherTabs.size) {
            val (title, walkIt) = otherTabs[k++]
            leaves = ArrayList(); walkIt()
            val p = parts()
            if (p.isNotEmpty()) tabs += ConfigInterop.dashboard("$base · $title", p)
        }
        if (tabs.isEmpty()) throw IllegalArgumentException("No panel with an equivalent here")
        return ImportResult(ConfigFormat.FOXGLOVE, tabs, notes)
    }

    /** The button color closest to a Foxglove one ("#00A871"); the default one if there is none. */
    private fun buttonColor(hex: String): Int {
        val c = Colors.parseHex(hex.ifBlank { return 0 }) ?: return 0
        fun d(a: Int) = listOf(16, 8, 0).sumOf { s -> val x = ((a shr s) and 0xFF) - ((c shr s) and 0xFF); x * x }
        val colors = com.juancams.rviz2mini.buttons.ButtonSpec.COLORS
        return (1 until colors.size).minByOrNull { d(colors[it]) } ?: 0
    }

    private fun panel(kind: String, c: JSONObject, notes: MutableList<String>): TabSpec? {
        val title = c.optString("foxglovePanelTitle").ifBlank { kind }
        fun series(p: JSONObject, i: Int): SeriesSpec? {
            if (!p.optBoolean("enabled", true)) return null
            val path = parsePath(p.optString("value"), notes) ?: return null
            return SeriesSpec(topic = path.topic, type = TopicTypes.of(path.topic), field = path.field, transform = path.transform, scale = path.scale,
                label = p.optString("label"), color = ConfigInterop.paletteIndex(p.optString("color").ifBlank { null }).takeIf { p.has("color") } ?: -1)
        }
        return when (kind) {
            "3D", "ThreeDee", "ThreeDeeRender", "3D Panel" -> {
                val displays = ArrayList<DisplayConfig>()
                val layers = c.optJSONObject("layers")
                if (layers == null || layers.keys().asSequence().any { layers.optJSONObject(it)?.optString("layerId") == "foxglove.Grid" })
                    displays += DisplayConfig.create(DisplayType.GRID, "Grid")
                displays += DisplayConfig.create(DisplayType.TF, "TF")
                val topics = c.optJSONObject("topics")
                topics?.keys()?.forEach { t ->
                    if (topics.optJSONObject(t)?.optBoolean("visible", false) != true) return@forEach
                    ConfigInterop.displayFor(t, TopicTypes.of(t))?.let { displays += it } ?: run { notes += "3D topic $t: unknown type, not added" }
                }
                var tab = TabSpec.create(TabType.RVIZ, title.takeIf { it != kind } ?: "3D").withSetting("displays", DisplayList.toJson(displays))
                c.optString("followTf").takeIf { it.isNotBlank() }?.let { tab = tab.withSetting("fixedFrame", it) }
                c.optJSONObject("scene")?.optString("backgroundColor")?.takeIf { it.isNotBlank() }?.let { b -> Colors.parseHex(b)?.let { tab = tab.withSetting("background", Colors.toHex(it)) } }
                c.optJSONObject("publish")?.let { p ->
                    p.optString("poseTopic").takeIf { it.isNotBlank() }?.let { tab = tab.withSetting("goalTopic", it) }
                    p.optString("poseEstimateTopic").takeIf { it.isNotBlank() }?.let { tab = tab.withSetting("poseTopic", it) }
                    p.optString("pointTopic").takeIf { it.isNotBlank() }?.let { tab = tab.withSetting("pointTopic", it) }
                }
                tab
            }
            "Plot", "StateTransitions" -> {
                val paths = c.optJSONArray("paths") ?: JSONArray()
                val list = (0 until paths.length()).mapNotNull { paths.optJSONObject(it)?.let { p -> series(p, it) } }
                val xy = kind == "Plot" && c.optString("xAxisVal") in setOf("custom", "currentCustom")
                val xPath = if (xy) c.optJSONObject("xAxisPath")?.let { series(it, -1) } else null
                val panels = when {
                    kind == "StateTransitions" -> list.chunked(PlotSpecs.MAX_SERIES).map { PanelSpec(kind = PanelKind.STATES, series = it) }
                    xPath != null -> list.take(PlotSpecs.MAX_PANELS).map { PanelSpec(kind = PanelKind.XY, series = listOf(xPath.copy(id = UUID.randomUUID().toString()), it)) }
                    else -> list.chunked(PlotSpecs.MAX_SERIES).map { PanelSpec(series = it) }
                }.take(PlotSpecs.MAX_PANELS)
                if (list.size > panels.sumOf { it.series.size }) notes += "Plot $title: only the first ${panels.sumOf { it.series.size }} series fit"
                if (kind == "Plot" && c.optString("xAxisVal") == "index") notes += "Plot $title: the x axis by array index is shown over time"
                TabSpec.create(TabType.PLOT, title.takeIf { it != kind } ?: "Plot").withSetting(PLOT_KEY_LAYOUT, PlotLayout(panels.ifEmpty { listOf(PanelSpec()) }).toJson())
            }
            "Image", "ImageView" -> {
                val topic = c.optJSONObject("imageMode")?.optString("imageTopic")?.ifBlank { null } ?: c.optString("cameraTopic").ifBlank { "/camera/image_raw" }
                val type = TopicTypes.of(topic)
                val k = com.juancams.rviz2mini.camera.ImageKind.entries.firstOrNull { it.rosType == type }
                    ?: com.juancams.rviz2mini.rviz.display.kindOf(topic)
                TabSpec.create(TabType.CAMERA, title.takeIf { it != kind } ?: "Camera").withSetting(CAMERA_KEY_TOPIC, topic).withSetting(CAMERA_KEY_KIND, k.name)
            }
            "RawMessages", "RawMessagesVirtual", "Table" -> {
                val p = parsePath(c.optString("topicPath"), notes)
                if (p == null) { notes += "$kind panel without a topic: skipped"; null }
                else TabSpec.create(TabType.MESSAGE, p.topic.substringAfterLast('/')).withSetting(MESSAGE_KEY_TOPIC, p.topic).withSetting(MESSAGE_KEY_TYPE, TopicTypes.of(p.topic))
                    .let { if (p.field.isNotBlank()) it.withSetting(com.juancams.rviz2mini.ui.screens.MESSAGE_KEY_PATH, p.field) else it }
            }
            "Teleop" -> TabSpec.create(TabType.TELEOP, "Teleop").withSetting("topic", c.optString("topic").ifBlank { "/cmd_vel" })
            "Publish" -> {
                // A button that publishes that message (Foxglove's "value" is the message as JSON).
                val topic = c.optString("topicName")
                if (topic.isBlank()) { notes += "Publish panel without a topic: skipped"; return null }
                val type = c.optString("datatype").ifBlank { TopicTypes.of(topic) }
                    .let { t -> if (t.count { it == '/' } == 1) t.replace("/", "/msg/") else t }
                val values = runCatching { JSONObject(c.optString("value").ifBlank { "{}" }).toString() }
                    .getOrElse { notes += "Publish panel $title: its message is not valid JSON, the button starts empty"; "{}" }
                val label = c.optString("buttonText").takeIf { it.isNotBlank() && it != "Publish" } ?: title.takeIf { it != kind } ?: topic
                val button = com.juancams.rviz2mini.buttons.ButtonSpec(label = label, kind = com.juancams.rviz2mini.buttons.ButtonKind.PUBLISH,
                    target = topic, type = type, values = values, color = buttonColor(c.optString("buttonColor")))
                TabSpec.create(TabType.BUTTONS, title.takeIf { it != kind } ?: label)
                    .withSetting(com.juancams.rviz2mini.ui.screens.BUTTONS_KEY, com.juancams.rviz2mini.buttons.ButtonList.toJson(listOf(button)))
            }
            "Gauge", "Indicator" -> {
                val p = parsePath(c.optString("path"), notes)
                if (p == null) { notes += "$kind panel without a path: skipped"; null }
                else {
                    val spec = IndicatorSpec(label = title.takeIf { it != kind } ?: p.field.substringAfterLast('.'), topic = p.topic, type = TopicTypes.of(p.topic), field = p.field,
                        kind = if (kind == "Gauge") IndicatorKind.GAUGE else IndicatorKind.LIGHT,
                        min = c.optDouble("minValue", 0.0), max = c.optDouble("maxValue", 1.0))
                    TabSpec.create(TabType.INDICATORS, title.takeIf { it != kind } ?: kind).withSetting(INDICATORS_KEY, IndicatorList.toJson(listOf(spec)))
                }
            }
            "Map" -> TabSpec.create(TabType.GPS, "Map").withSetting(GPS_KEY_TOPIC, "/gps/fix")
            "DiagnosticSummary", "DiagnosticStatusPanel", "DiagnosticsSummary", "DiagnosticsDetail" -> TabSpec.create(TabType.DIAGNOSTICS, "Diagnostics").withSetting("mode", "DIAG")
            "TransformTree" -> TabSpec.create(TabType.TF_TREE, "TF tree")
            "RosOut", "Log" -> TabSpec.create(TabType.DIAGNOSTICS, "Log").withSetting("mode", "LOG")
            "Parameters" -> TabSpec.create(TabType.PARAMETERS, "Parameters")
            "TopicGraph" -> TabSpec.create(TabType.NODE_GRAPH, "Node graph")
            else -> { notes += "Panel $kind has no equivalent: skipped"; null }
        }
    }

    // ------------------------------------------------ export

    /** The tab (a dashboard with its widgets, or any single tab) as a Foxglove layout. */
    fun export(tab: TabSpec, all: List<TabSpec>): Pair<String, List<String>> {
        val notes = ArrayList<String>()
        val configs = JSONObject()
        val placed: List<Pair<TabSpec, DoubleArray>> = if (tab.type == TabType.DASHBOARD) {
            val widgets = all.filter { it.dashboardId == tab.id }.associateBy { it.id }
            DashboardGrid.fromJson(tab.settings[DASHBOARD_KEY_LAYOUT]).mapNotNull { i -> widgets[i.id]?.let { it to doubleArrayOf(i.x, i.y, i.w, i.h) } }
        } else listOf(tab to doubleArrayOf(0.0, 0.0, 12.0, 12.0))
        // Each widget becomes one panel, or several stacked ones (a plot with several panels, indicators).
        val leaves = ArrayList<Pair<Any, DoubleArray>>()
        for ((t, r) in placed) {
            val ids = panels(t, configs, notes)
            if (ids.isEmpty()) continue
            leaves += stack(ids) to r
        }
        if (leaves.isEmpty()) throw IllegalArgumentException("Nothing in this tab has an equivalent panel")
        val layout = JSONObject().put("configById", configs).put("globalVariables", JSONObject()).put("userNodes", JSONObject())
            .put("playbackConfig", JSONObject().put("speed", 1.0)).put("layout", split(leaves))
        return layout.toString(2) to notes
    }

    /** Several panels one above the other with equal heights. */
    private fun stack(ids: List<String>): Any = chain(ids)

    /** Guillotine cuts of the grid rectangles into Foxglove's split tree (falling back to rows if they overlap). */
    private fun split(items: List<Pair<Any, DoubleArray>>): Any {
        if (items.size == 1) return items[0].first
        for (vertical in listOf(true, false)) {
            val lo = items.minOf { if (vertical) it.second[0] else it.second[1] }
            val hi = items.maxOf { if (vertical) it.second[0] + it.second[2] else it.second[1] + it.second[3] }
            val cuts = items.map { if (vertical) it.second[0] + it.second[2] else it.second[1] + it.second[3] }.distinct().sorted()
            for (c in cuts) {
                if (c >= hi) continue
                val first = items.filter { (if (vertical) it.second[0] + it.second[2] else it.second[1] + it.second[3]) <= c + 1e-6 }
                val second = items.filter { (if (vertical) it.second[0] else it.second[1]) >= c - 1e-6 }
                if (first.isEmpty() || second.isEmpty() || first.size + second.size != items.size) continue
                return JSONObject().put("first", split(first)).put("second", split(second)).put("direction", if (vertical) "row" else "column")
                    .put("splitPercentage", 100.0 * (c - lo) / (hi - lo))
            }
        }
        // Overlapping widgets (free placement): one above the other, top to bottom.
        return chain(items.sortedWith(compareBy({ it.second[1] }, { it.second[0] })).map { it.first })
    }

    private fun chain(nodes: List<Any>): Any = if (nodes.size == 1) nodes[0] else
        JSONObject().put("first", nodes[0]).put("second", chain(nodes.drop(1))).put("direction", "column").put("splitPercentage", 100.0 / nodes.size)

    private fun panels(t: TabSpec, configs: JSONObject, notes: MutableList<String>): List<String> {
        fun add(kind: String, c: JSONObject): String { val id = "$kind!${ConfigInterop.shortId()}"; configs.put(id, c.put("foxglovePanelTitle", t.name)); return id }
        val s = t.settings
        return when (t.type) {
            TabType.RVIZ -> {
                val displays = DisplayList.fromJson(s["displays"]) ?: DisplayList.defaults()
                val topics = JSONObject()
                for (d in displays) if (d.topic.isNotBlank() && d.type.needsTopic) topics.put(d.topic, JSONObject().put("visible", d.enabled))
                val layers = JSONObject()
                if (displays.any { it.type == DisplayType.GRID }) {
                    val gid = UUID.randomUUID().toString()
                    layers.put(gid, JSONObject().put("visible", true).put("frameLocked", true).put("label", "Grid").put("instanceId", gid)
                        .put("layerId", "foxglove.Grid").put("size", 10).put("divisions", 10).put("lineWidth", 1).put("color", "#248eff")
                        .put("position", JSONArray(listOf(0, 0, 0))).put("rotation", JSONArray(listOf(0, 0, 0))).put("order", 1))
                }
                val c = JSONObject().put("topics", topics).put("layers", layers)
                s["fixedFrame"]?.takeIf { it.isNotBlank() }?.let { c.put("followTf", it) }
                s["background"]?.let { c.put("scene", JSONObject().put("backgroundColor", it)) }
                val publish = JSONObject()
                s["goalTopic"]?.let { publish.put("poseTopic", it) }; s["poseTopic"]?.let { publish.put("poseEstimateTopic", it) }; s["pointTopic"]?.let { publish.put("pointTopic", it) }
                if (publish.length() > 0) c.put("publish", publish.put("type", "pose"))
                val skipped = displays.filter { !it.type.needsTopic && it.type !in setOf(DisplayType.GRID, DisplayType.TF, DisplayType.AXES) }
                if (skipped.isNotEmpty()) notes += "${t.name}: ${skipped.joinToString { it.name }} have no equivalent panel"
                listOf(add("3D", c))
            }
            TabType.PLOT -> PlotLayout.fromJson(s[PLOT_KEY_LAYOUT]).panels.mapNotNull { p ->
                fun path(x: SeriesSpec, i: Int): JSONObject? {
                    if (x.isFormula) { notes += "${t.name}: formula series \"${x.displayLabel}\" not exported"; return null }
                    var v = formatPath(x.topic, x.field)
                    when {
                        x.transform == SeriesTransform.DERIVATIVE -> v += ".@derivative"
                        x.transform != SeriesTransform.NONE -> notes += "${t.name}: ${x.transform.name.lowercase()} of \"${x.displayLabel}\" not exported"
                    }
                    if (x.scale == -1.0) v += ".@negative" else if (x.scale != 1.0 || x.offset != 0.0) notes += "${t.name}: scale/offset of \"${x.displayLabel}\" not exported"
                    return JSONObject().put("value", v).put("enabled", true).put("timestampMethod", "receiveTime")
                        .put("color", ConfigInterop.paletteHex(x.color, i)).apply { if (x.label.isNotBlank()) put("label", x.label) }
                }
                when (p.kind) {
                    PanelKind.STATES -> add("StateTransitions", JSONObject().put("paths", JSONArray(p.series.mapIndexedNotNull { i, x -> path(x, i) })))
                    PanelKind.XY -> {
                        val x = p.series.getOrNull(0)?.let { path(it, 0) } ?: return@mapNotNull null
                        add("Plot", JSONObject().put("paths", JSONArray(p.series.drop(1).mapIndexedNotNull { i, y -> path(y, i + 1) }))
                            .put("xAxisVal", "custom").put("xAxisPath", JSONObject().put("value", x.getString("value")).put("enabled", true)).put("showLegend", true))
                    }
                    else -> {
                        if (p.kind != PanelKind.TIME) notes += "${t.name}: ${p.kind.name.lowercase()} panel exported as a time plot"
                        add("Plot", JSONObject().put("paths", JSONArray(p.series.mapIndexedNotNull { i, x -> path(x, i) })).put("xAxisVal", "timestamp").put("showLegend", true))
                    }
                }
            }
            TabType.CAMERA -> listOf(add("Image", JSONObject().put("imageMode", JSONObject().put("imageTopic", s[CAMERA_KEY_TOPIC] ?: "/camera/image_raw"))))
            TabType.MESSAGE -> listOf(add("RawMessages", JSONObject().put("topicPath", formatPath(s[MESSAGE_KEY_TOPIC] ?: "", s[com.juancams.rviz2mini.ui.screens.MESSAGE_KEY_PATH].orEmpty()))))
            TabType.TELEOP -> listOf(add("Teleop", JSONObject().put("topic", s["topic"] ?: "/cmd_vel").put("publishRate", 5)))
            TabType.INDICATORS -> IndicatorList.fromJson(s[INDICATORS_KEY]).map { i ->
                val path = formatPath(i.topic, i.field)
                if (i.kind == IndicatorKind.GAUGE || i.kind == IndicatorKind.BAR)
                    add("Gauge", JSONObject().put("path", path).put("minValue", i.min).put("maxValue", i.max).put("colorMode", "colormap").put("colorMap", "red-yellow-green"))
                else add("RawMessages", JSONObject().put("topicPath", path))
            }
            TabType.BUTTONS -> com.juancams.rviz2mini.buttons.ButtonList.fromJson(s[com.juancams.rviz2mini.ui.screens.BUTTONS_KEY]).mapNotNull { b ->
                if (b.kind != com.juancams.rviz2mini.buttons.ButtonKind.PUBLISH) { notes += "${t.name}: button \"${b.label}\" calls a ${b.kind.kind}, which a Publish panel cannot"; return@mapNotNull null }
                val color = com.juancams.rviz2mini.buttons.ButtonSpec.COLORS[b.color].takeIf { it != 0 }?.let { Colors.toHex(it) } ?: "#4caf50"
                val id = "Publish!${ConfigInterop.shortId()}"
                configs.put(id, JSONObject().put("topicName", b.target).put("datatype", b.type).put("buttonText", b.label.ifBlank { b.target })
                    .put("buttonTooltip", "").put("buttonColor", color).put("advancedView", false)
                    .put("value", runCatching { JSONObject(b.values).toString(2) }.getOrDefault("{}")).put("foxglovePanelTitle", b.label.ifBlank { t.name }))
                id
            }
            TabType.DIAGNOSTICS -> listOf(if (s["mode"] == "LOG") add("RosOut", JSONObject()) else add("DiagnosticSummary", JSONObject().put("topicToRender", "/diagnostics")))
            TabType.PARAMETERS -> listOf(add("Parameters", JSONObject()))
            TabType.NODE_GRAPH -> listOf(add("TopicGraph", JSONObject()))
            TabType.GPS -> listOf(add("Map", JSONObject()))
            else -> { notes += "${t.name} (${t.type.label()}) has no equivalent panel"; emptyList() }
        }
    }
}

// ==================================================================== PlotJuggler

/**
 * PlotJuggler layouts (.xml, "Save layout"): tabs of plots split in docks, each plot a list of curves named after the
 * ROS 2 topic and field ("/odom/pose/pose/position/x"; array elements as ".3"), plus Lua "custom series"
 * (`customMathEquations`), the same `function(time, value, v1...)` as the plots here. A layout becomes a Plot tab
 * (or a dashboard of them if it does not fit in one); plot tabs are written back as one PlotJuggler tab each.
 */
object PlotJugglerLayout {

    // ------------------------------------------------ names

    /** "/odom/pose/pose/position/x" -> ("/odom", "pose.pose.position.x"), with the topic the longest known prefix. */
    fun splitName(name: String, topics: Collection<String>, notes: MutableList<String>): Pair<String, String>? {
        if (!name.startsWith("/")) return null
        val topic = topics.filter { name == it || name.startsWith("$it/") }.maxByOrNull { it.length } ?: run {
            // Unknown: the first level as the topic (the usual "/odom", "/scan"...).
            val guess = "/" + name.trimStart('/').substringBefore('/')
            notes += "Topic of \"$name\" not known: $guess assumed (open it with the robot on the network to check)"
            guess
        }
        val rest = name.removePrefix(topic).trimStart('/')
        val field = rest.split('/').joinToString(".") { seg -> seg.replace(Regex("""\.(\d+)$"""), "[$1]").replace(Regex("""\.(\d+)\."""), "[$1].") }
        return topic to field
    }

    fun joinName(topic: String, field: String): String =
        if (field.isBlank()) topic else topic + "/" + field.replace(Regex("""\[(-?\d+)]"""), ".$1").replace('.', '/').replace(Regex("""/(-?\d+)(?=/|$)"""), ".$1")

    // ------------------------------------------------ import

    fun import(xml: String, name: String = ""): ImportResult {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false; isExpandEntityReferences = false }
            .newDocumentBuilder().parse(xml.byteInputStream())
        val root = doc.documentElement
        val notes = ArrayList<String>()
        fun children(e: Element, tag: String) = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }.filter { it.tagName == tag }
        fun all(e: Element, tag: String): List<Element> { val l = e.getElementsByTagName(tag); return (0 until l.length).map { l.item(it) as Element } }
        // Topics the layout subscribes to (streaming plugin or bag loader) and those on the network.
        val selected = all(root, "selected_topics").flatMap { it.getAttribute("value").split(';') }.map { it.trim() }.filter { it.startsWith("/") }
        val topics = (selected + TopicTypes.all()).toSet()
        // Custom series (Lua).
        class Snippet(val name: String, val global: String, val function: String, val sources: List<String>)
        val snippets = all(root, "snippet").map { s ->
            fun text(tag: String) = children(s, tag).firstOrNull()?.textContent.orEmpty()
            val extra = children(s, "additional_sources").firstOrNull()?.let { a -> (0 until a.childNodes.length).mapNotNull { a.childNodes.item(it) as? Element }.sortedBy { it.tagName.removePrefix("v").toIntOrNull() ?: 0 }.map { it.textContent.trim() } }.orEmpty()
            Snippet(s.getAttribute("name"), text("global"), text("function"), listOf(text("linked_source").trim()) + extra)
        }.associateBy { it.name }

        fun seriesFor(curve: String, color: String?): List<SeriesSpec> {
            snippets[curve]?.let { sn ->
                val inputs = sn.sources.filter { it.isNotBlank() }.mapNotNull { src -> splitName(src, topics, notes)?.let { (t, f) -> SeriesSpec(topic = t, type = TopicTypes.of(t), field = f, label = src) } }
                return inputs + SeriesSpec(label = sn.name, script = sn.function.ifBlank { "return value" }, scriptGlobals = sn.global,
                    inputs = inputs.joinToString("\n") { it.label }, color = ConfigInterop.paletteIndex(color))
            }
            val (t, f) = splitName(curve, topics, notes) ?: run { notes += "Curve \"$curve\" is not a ROS topic: skipped"; return emptyList() }
            return listOf(SeriesSpec(topic = t, type = TopicTypes.of(t), field = f, label = curve, color = ConfigInterop.paletteIndex(color)))
        }

        fun panelsOf(plot: Element): List<PanelSpec> {
            val curves = children(plot, "curve")
            if (plot.getAttribute("mode") == "XYPlot") {
                return curves.mapNotNull { c ->
                    val x = seriesFor(c.getAttribute("curve_x"), c.getAttribute("color")).lastOrNull()
                    val y = seriesFor(c.getAttribute("curve_y"), c.getAttribute("color")).lastOrNull()
                    if (x == null || y == null) null else PanelSpec(kind = PanelKind.XY, series = listOf(x, y))
                }
            }
            // Inputs of custom series come first, without duplicates (by label).
            val list = curves.flatMap { seriesFor(it.getAttribute("name"), it.getAttribute("color").ifBlank { null }) }.distinctBy { it.label }
            return list.chunked(PlotSpecs.MAX_SERIES).map { PanelSpec(series = it) }.ifEmpty { listOf(PanelSpec()) }
        }

        val tabs = all(root, "Tab").ifEmpty { listOf(root) }
        val plotTabs = ArrayList<TabSpec>()
        for (tab in tabs) {
            val tabName = tab.getAttribute("tab_name").ifBlank { name.substringBeforeLast('.').ifBlank { "Plots" } }
            val panels = all(tab, "plot").flatMap { panelsOf(it) }
            // Up to MAX_PANELS per Plot tab.
            panels.chunked(PlotSpecs.MAX_PANELS).forEachIndexed { i, chunk ->
                val n = if (panels.size > PlotSpecs.MAX_PANELS) "$tabName ${i + 1}" else tabName
                plotTabs += TabSpec.create(TabType.PLOT, n).withSetting(PLOT_KEY_LAYOUT, PlotLayout(chunk, windowS = 30.0).toJson())
            }
        }
        if (plotTabs.isEmpty()) throw IllegalArgumentException("No plots in this file")
        val h = 6
        val parts = plotTabs.mapIndexed { i, t -> t to intArrayOf(0, i * h, DashboardGrid.COLUMNS, h) }
        return ImportResult(ConfigFormat.PLOTJUGGLER, ConfigInterop.dashboard(name.substringBeforeLast('.').ifBlank { "Plots" }, parts), notes)
    }

    // ------------------------------------------------ export

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "&#xa;")

    /** The plot tabs (the tab, or a dashboard's plot widgets) as a PlotJuggler layout: one PlotJuggler tab each. */
    fun export(tab: TabSpec, all: List<TabSpec>): Pair<String, List<String>> {
        val notes = ArrayList<String>()
        val plots = (if (tab.type == TabType.DASHBOARD) all.filter { it.dashboardId == tab.id } else listOf(tab)).filter { it.type == TabType.PLOT }
        if (plots.isEmpty()) throw IllegalArgumentException("No plots in this tab")
        val topics = LinkedHashSet<String>()
        val snippets = StringBuilder()
        val sb = StringBuilder("<?xml version='1.0' encoding='UTF-8'?>\n<root>\n <tabbed_widget name=\"Main Window\" parent=\"main_window\">\n")
        for (p in plots) {
            val layout = PlotLayout.fromJson(p.settings[PLOT_KEY_LAYOUT])
            val byLabel = layout.allSeries.associateBy { it.displayLabel }
            fun curveName(s: SeriesSpec): String? = when {
                s.isScript -> s.displayLabel.also {
                    if (snippets.indexOf("name=\"${esc(it)}\"") < 0) {
                        val src = s.inputList.mapNotNull { l -> byLabel[l]?.takeIf { !it.isFormula }?.let { joinName(it.topic, it.field) } }
                        snippets.append("  <snippet name=\"${esc(it)}\">\n   <global>${esc(s.scriptGlobals)}</global>\n   <function>${esc(s.script)}</function>\n")
                            .append("   <linked_source>${esc(src.firstOrNull().orEmpty())}</linked_source>\n   <additional_sources>\n")
                        src.drop(1).forEachIndexed { i, v -> snippets.append("    <v${i + 1}>${esc(v)}</v${i + 1}>\n") }
                        snippets.append("   </additional_sources>\n  </snippet>\n")
                    }
                }
                s.isFormula -> { notes += "${p.name}: formula \"${s.formula}\" not exported (the layout only takes Lua)"; null }
                else -> {
                    if ('{' in s.field) { notes += "${p.name}: filter in \"${s.field}\" not exported"; null }
                    else {
                        if (s.transform != SeriesTransform.NONE || s.scale != 1.0 || s.offset != 0.0) notes += "${p.name}: transform/scale of \"${s.displayLabel}\" not exported"
                        topics += s.topic; joinName(s.topic, s.field)
                    }
                }
            }
            val n = layout.panels.size
            sb.append("  <Tab tab_name=\"${esc(p.name)}\" containers=\"1\">\n   <Container>\n")
            sb.append("    <DockSplitter orientation=\"-\" count=\"$n\" sizes=\"${List(n) { String.format(Locale.ROOT, "%.6f", 1.0 / n) }.joinToString(";")}\">\n")
            for (panel in layout.panels) {
                val xy = panel.kind == PanelKind.XY
                if (panel.kind != PanelKind.TIME && !xy) notes += "${p.name}: ${panel.kind.name.lowercase()} panel exported as a time plot"
                sb.append("     <DockArea name=\"...\">\n      <plot style=\"Lines\" mode=\"${if (xy) "XYPlot" else "TimeSeries"}\" flip_x=\"false\" flip_y=\"false\">\n       <range left=\"0\" right=\"1\" bottom=\"0\" top=\"1\"/>\n       <limitY/>\n")
                if (xy) {
                    val names = panel.series.mapNotNull { curveName(it) }
                    if (names.size >= 2) for (y in names.drop(1)) sb.append("       <curve name=\"${esc(names[0])};${esc(y)}\" curve_x=\"${esc(names[0])}\" curve_y=\"${esc(y)}\" color=\"${ConfigInterop.paletteHex(-1, 0)}\"/>\n")
                } else panel.series.forEachIndexed { i, s ->
                    curveName(s)?.let { sb.append("       <curve name=\"${esc(it)}\" color=\"${ConfigInterop.paletteHex(s.color, i)}\"/>\n") }
                }
                sb.append("      </plot>\n     </DockArea>\n")
            }
            sb.append("    </DockSplitter>\n   </Container>\n  </Tab>\n")
        }
        sb.append("  <currentTabIndex index=\"0\"/>\n </tabbed_widget>\n <use_relative_time_offset enabled=\"1\"/>\n <Plugins>\n")
        sb.append("  <plugin ID=\"ROS2 Topic Subscriber\">\n   <use_header_stamp value=\"false\"/>\n   <discard_large_arrays value=\"true\"/>\n   <max_array_size value=\"100\"/>\n")
        sb.append("   <boolean_strings_to_number value=\"true\"/>\n   <remove_suffix_from_strings value=\"true\"/>\n   <selected_topics value=\"${esc(topics.joinToString(";"))}\"/>\n  </plugin>\n </Plugins>\n")
        sb.append(" <previouslyLoaded_Datafiles/>\n <previouslyLoaded_Streamer name=\"ROS2 Topic Subscriber\"/>\n <customMathEquations>\n").append(snippets).append(" </customMathEquations>\n <snippets/>\n</root>\n")
        return sb.toString() to notes
    }
}
