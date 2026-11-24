// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.diag.DiagEntry
import com.juancams.rviz2mini.diag.DiagLevel
import com.juancams.rviz2mini.diag.DiagnosticsModel
import com.juancams.rviz2mini.diag.LogBuffer
import com.juancams.rviz2mini.diag.LogEntry
import com.juancams.rviz2mini.diag.LogLevel
import com.juancams.rviz2mini.diag.MessageCatalog
import com.juancams.rviz2mini.diag.MessageIntrospection
import com.juancams.rviz2mini.diag.PlotSeries
import com.juancams.rviz2mini.diag.RateMeter
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosStrings
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.profile
import com.juancams.rviz2mini.tabs.TabStore
import kotlinx.coroutines.delay
import us.ihmc.jros2.ROS2Message
import us.ihmc.jros2.ROS2QoSProfile
import us.ihmc.jros2.ROS2SubscriptionCallback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Modes of the Diagnostics tab. */
enum class DiagMode { LOG, DIAG, ECHO, PLOT }

/**
 * Diagnostics: /rosout, /diagnostics, the echo of a topic and a simple plot of its numeric fields. The
 * data is received on the DDS thread and read on refresh; kept outside Compose for the tests. Settings are
 * stored in the tab with the same keys as before on Android.
 */
class DiagnosticsController(val tabId: String) {
    private val store = TabStore.get()
    private fun setting(k: String) = store.get(tabId)?.settings?.get(k)
    private fun save(k: String, v: String?) = store.updateSetting(tabId, k, v)

    var mode by mutableStateOf(DiagMode.entries.firstOrNull { it.name == setting("mode") } ?: DiagMode.LOG); private set
    var paused by mutableStateOf(false)
    var logLevel by mutableStateOf(LogLevel.entries.firstOrNull { it.name == setting("logLevel") } ?: LogLevel.INFO); private set
    var logFilter by mutableStateOf(setting("logFilter").orEmpty()); private set
    var logNodes by mutableStateOf(setting("logNodes")?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()); private set
    var topic by mutableStateOf(setting("topic").orEmpty()); private set
    var type by mutableStateOf(setting("type").orEmpty()); private set
    var qos by mutableStateOf(QosPreset.entries.firstOrNull { it.name == setting("qos") } ?: QosPreset.SENSOR); private set
    var plotPaths by mutableStateOf(setting("plotPaths")?.split(',')?.filter { it.isNotBlank() }.orEmpty()); private set

    val logBuffer = LogBuffer()
    val diagModel = DiagnosticsModel()
    val latest = AtomicReference<Any?>()
    val count = AtomicLong()
    val rate = RateMeter()
    @Volatile var series: List<PlotSeries> = plotPaths.map { PlotSeries(it) }; private set
    @Volatile private var parsedPaths = plotPaths.map { MessageIntrospection.NumericPath(it) }
    private var subscription: RosConnection.ManagedSubscription<*>? = null
    var active = false; private set

    fun start() { active = true; resubscribe() }
    fun stop() { active = false; subscription?.cancel(); subscription = null }

    fun updateMode(m: DiagMode) {
        if (m == mode) return
        val sameSource = (m == DiagMode.ECHO || m == DiagMode.PLOT) && (mode == DiagMode.ECHO || mode == DiagMode.PLOT) &&
            (m != DiagMode.PLOT || MessageCatalog.isPlottable(type))
        mode = m; save("mode", m.name); paused = false
        if (!sameSource) resubscribe()
    }

    fun updateLogLevel(l: LogLevel) { logLevel = l; save("logLevel", l.name) }
    fun updateLogFilter(t: String) { logFilter = t; save("logFilter", t) }
    fun updateLogNodes(n: Set<String>) { logNodes = n; save("logNodes", n.joinToString("\n")) }
    fun updateQos(q: QosPreset) { qos = q; save("qos", q.name); sourceChanged() }

    fun updateTopic(text: String) {
        val t = text.trim().let { if (it.isNotEmpty() && !it.startsWith("/")) "/$it" else it }
        if (t == topic) return
        topic = t; save("topic", t)
        MessageCatalog.COMMON_TOPICS.firstOrNull { it.first == t }?.let { if (it.second != type) { type = it.second; save("type", it.second) } }
        sourceChanged()
    }

    fun updateType(t: String) { if (t == type) return; type = t; save("type", t); sourceChanged() }

    fun updatePlotPaths(p: List<String>) {
        plotPaths = p; series = p.map { PlotSeries(it) }; parsedPaths = p.map { MessageIntrospection.NumericPath(it) }
        save("plotPaths", p.joinToString(","))
    }

    private fun sourceChanged() { latest.set(null); count.set(0); updatePlotPaths(emptyList()); resubscribe() }

    fun clear() {
        when (mode) {
            DiagMode.LOG -> logBuffer.clear()
            DiagMode.DIAG -> diagModel.clear()
            DiagMode.ECHO -> { latest.set(null); count.set(0) }
            DiagMode.PLOT -> for (s in series) s.clear()
        }
    }

    val error: String? get() = subscription?.error

    private fun resubscribe() {
        subscription?.cancel(); subscription = null
        if (!active) return
        subscription = when (mode) {
            // /rosout: volatile RELIABLE is compatible with every publisher.
            DiagMode.LOG -> RosConnection.subscribe("/rosout", rcl_interfaces.Log::class.java, QosPreset.RELIABLE.profile(),
                ROS2SubscriptionCallback { reader -> reader.read()?.let { onLog(it) } })
            DiagMode.DIAG -> RosConnection.subscribe("/diagnostics", diagnostic_msgs.DiagnosticArray::class.java, QosPreset.RELIABLE.profile(),
                ROS2SubscriptionCallback { reader -> reader.read()?.let { onDiagnostics(it) } })
            DiagMode.ECHO, DiagMode.PLOT -> {
                val cls = MessageCatalog.classFor(type)
                if (topic.isBlank() || cls == null || (mode == DiagMode.PLOT && !MessageCatalog.isPlottable(type))) null
                else subscribeAny(topic, cls, qos.profile())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun subscribeAny(topic: String, cls: Class<*>, profile: ROS2QoSProfile): RosConnection.ManagedSubscription<*> =
        subscribeTyped(topic, cls as Class<std_msgs.Empty>, profile)

    private fun <T : ROS2Message<T>> subscribeTyped(topic: String, cls: Class<T>, profile: ROS2QoSProfile) =
        RosConnection.subscribe(topic, cls, profile, ROS2SubscriptionCallback { reader ->
            val msg = reader.read() ?: return@ROS2SubscriptionCallback
            val now = System.currentTimeMillis()
            count.incrementAndGet(); rate.tick(now); latest.set(msg)
            val paths = parsedPaths; val ss = series
            for (i in paths.indices) paths[i].eval(msg)?.let { v -> ss.getOrNull(i)?.add(now, v) }
        })

    private fun onLog(m: rcl_interfaces.Log) {
        val stamp = m.stamp.sec * 1_000_000_000L + (m.stamp.nanosec.toLong() and 0xFFFFFFFFL)
        logBuffer.add(LogEntry(stamp, LogLevel.of(m.level.toInt() and 0xFF), RosStrings.fix(m.name), RosStrings.fix(m.msg), RosStrings.fix(m.file), m.line, RosStrings.fix(m.function)))
        count.incrementAndGet(); rate.tick(System.currentTimeMillis())
    }

    private fun onDiagnostics(a: diagnostic_msgs.DiagnosticArray) {
        val now = System.currentTimeMillis()
        val st = a.status
        for (i in 0 until st.size()) {
            val s = st.get(i)
            val values = (0 until s.values.size()).map { k -> s.values.get(k).let { RosStrings.fix(it.key) to RosStrings.fix(it.value) } }
            diagModel.update(DiagEntry(RosStrings.fix(s.name), DiagLevel.of(s.level.toInt()), RosStrings.fix(s.message), RosStrings.fix(s.hardwareId), values, now))
        }
        count.incrementAndGet(); rate.tick(now)
    }

    /** The status line at the top (with the "not connected" notice in front if needed). */
    fun statusLine(now: Long = System.currentTimeMillis()): String {
        error?.let { return it }
        val conn = if (RosConnection.status !is RosConnection.Status.Connected) tr("diag_no_connection_prefix") else ""
        val hz = fmtHz(rate.hz(now))
        return when (mode) {
            DiagMode.LOG -> tr("diag_status_rosout", conn, logBuffer.size, hz)
            DiagMode.DIAG -> {
                val counts = diagModel.counts(now)
                tr("diag_status_diagnostics", conn, DiagLevel.entries.filter { (counts[it] ?: 0) > 0 }
                    .joinToString(" · ") { tr("diag_level_count", counts[it] ?: 0, it.label) }.ifEmpty { tr("diag_no_data") })
            }
            else -> if (mode == DiagMode.PLOT && !plottable()) tr("diag_cannot_plot", conn, topic)
                else if (topic.isBlank() || type.isBlank()) tr("diag_choose_topic_and_type", conn)
                else tr("diag_topic_status", conn, topic, count.get(), hz)
        }
    }

    fun plottable() = type.isBlank() || MessageCatalog.classFor(type) == null || MessageCatalog.isPlottable(type)

    fun sourceHint(): String = when {
        topic.isBlank() -> tr("diag_type_or_choose_topic")
        MessageCatalog.classFor(type) == null -> tr("diag_choose_message_type_of", topic)
        else -> tr("diag_waiting_topic", topic, type, qos.label)
    }

    fun echoText(): String = latest.get()?.let { MessageIntrospection.format(it) }.orEmpty()

    private fun fmtHz(hz: Double) = if (hz > 0) String.format(Locale.US, "%.1f Hz", hz) else "0 Hz"
}

private val LOG_TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
private fun logTime(ns: Long): String = synchronized(LOG_TIME) { LOG_TIME.format(Date(ns / 1_000_000)) }

private fun levelColor(l: LogLevel) = when (l) {
    LogLevel.DEBUG -> Color(144, 164, 174); LogLevel.INFO -> Color(102, 187, 106)
    LogLevel.WARN -> Color(255, 167, 38); LogLevel.ERROR -> Color(239, 83, 80); LogLevel.FATAL -> Color(213, 0, 249)
}

private fun diagColor(l: DiagLevel) = when (l) {
    DiagLevel.OK -> Color(102, 187, 106); DiagLevel.WARN -> Color(255, 167, 38)
    DiagLevel.ERROR -> Color(239, 83, 80); DiagLevel.STALE -> Color(144, 164, 174)
}

private val SERIES_COLORS = listOf(Color(0xFF42A5F5), Color(0xFFEF5350), Color(0xFF66BB6A), Color(0xFFFFA726))

/** Diagnostics tab. `onShare`: share the log text (each platform in its own way). */
@Composable
fun DiagnosticsScreen(tabId: String, modifier: Modifier = Modifier, onController: (DiagnosticsController?) -> Unit = {}, onShare: ((String) -> Unit)? = null) {
    val c = remember(tabId) { DiagnosticsController(tabId) }
    DisposableEffect(c) { c.start(); onController(c); onDispose { onController(null); c.stop() } }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(c) { while (true) { delay(250); tick++ } }
    @Suppress("UNUSED_VARIABLE") val t = tick
    var picker by remember { mutableStateOf<String?>(null) }
    var logEntry by remember { mutableStateOf<LogEntry?>(null) }
    val expanded = remember { mutableStateListOf<String>() }
    val clipboard = LocalClipboardManager.current
    // What is shown freezes while paused.
    var shownLog by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var shownDiag by remember { mutableStateOf<List<DiagEntry>>(emptyList()) }
    var shownEcho by remember { mutableStateOf("") }
    if (!c.paused && !com.juancams.rviz2mini.LiveView.frozen) {
        shownLog = c.logBuffer.filtered(c.logLevel, c.logFilter, c.logNodes)
        shownDiag = c.diagModel.snapshot(System.currentTimeMillis())
        shownEcho = c.echoText()
    }

    Column(modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Header that adapts to the width: when narrow (dashboard widget, phone) the modes go in scrolling chips
        // and Pause/Clear become icons; when wide, the usual buttons (without breaking words).
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 460.dp
            val modes = listOf(DiagMode.LOG to "diag_mode_log", DiagMode.DIAG to "diag_mode_diagnostics", DiagMode.ECHO to "diag_mode_echo", DiagMode.PLOT to "diag_mode_plot")
            val icons = mapOf(DiagMode.LOG to androidx.compose.material.icons.Icons.Default.Notes, DiagMode.DIAG to androidx.compose.material.icons.Icons.Default.MonitorHeart,
                DiagMode.ECHO to androidx.compose.material.icons.Icons.Default.DataObject, DiagMode.PLOT to androidx.compose.material.icons.Icons.Default.Timeline)
            val extra: @Composable () -> Unit = {
                when (c.mode) {
                    DiagMode.LOG -> OutlinedButton(onClick = { picker = "nodes" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                        Text(if (c.logNodes.isEmpty()) tr("diag_log_nodes_all") else tr("diag_log_nodes_n", c.logNodes.size), fontSize = 12.sp, maxLines = 1)
                    }
                    DiagMode.ECHO -> OutlinedButton(onClick = { picker = "qos" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                        Text(tr("diag_qos_button", qosShort(c.qos)), fontSize = 12.sp, maxLines = 1)
                    }
                    DiagMode.PLOT -> if (c.plottable()) OutlinedButton(onClick = { picker = "fields" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                        Text(tr("diag_fields"), fontSize = 12.sp, maxLines = 1)
                    }
                    else -> {}
                }
            }
            if (narrow) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in modes) androidx.compose.material3.FilterChip(selected = c.mode == m.first, onClick = { c.updateMode(m.first) },
                        label = { Text(tr(m.second), maxLines = 1) },
                        leadingIcon = { androidx.compose.material3.Icon(icons.getValue(m.first), null, Modifier.size(18.dp)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.statusLine(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), maxLines = 2)
                    extra()
                    androidx.compose.material3.IconButton(onClick = { c.paused = !c.paused }) {
                        androidx.compose.material3.Icon(if (c.paused) androidx.compose.material.icons.Icons.Default.PlayArrow else androidx.compose.material.icons.Icons.Default.Pause,
                            tr(if (c.paused) "diag_resume" else "diag_pause"))
                    }
                    androidx.compose.material3.IconButton(onClick = { c.clear() }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.ClearAll, tr("diag_clear"))
                    }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    for ((i, m) in modes.withIndex()) SegmentedButton(c.mode == m.first, { c.updateMode(m.first) }, SegmentedButtonDefaults.itemShape(i, modes.size), icon = {}) {
                        Text(tr(m.second), maxLines = 1)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.statusLine(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f), maxLines = 2)
                    extra()
                    FilledTonalButton(onClick = { c.paused = !c.paused }) { Text(tr(if (c.paused) "diag_resume" else "diag_pause"), fontSize = 12.sp) }
                    OutlinedButton(onClick = { c.clear() }) { Text(tr("diag_clear"), fontSize = 12.sp) }
                }
            }
        }
        if (c.mode == DiagMode.LOG) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { picker = "level" }) { Text(tr("diag_log_level_button", c.logLevel.label)) }
            OutlinedTextField(c.logFilter, { c.updateLogFilter(it) }, singleLine = true, label = { Text(tr("diag_filter_hint")) }, modifier = Modifier.weight(1f))
        }
        if (c.mode == DiagMode.ECHO || c.mode == DiagMode.PLOT) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            var topicText by remember(c.topic) { mutableStateOf(c.topic) }
            OutlinedTextField(topicText, { topicText = it }, singleLine = true, label = { Text(tr("diag_topic_hint")) }, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { c.updateTopic(topicText) }) { Text("✓") }
            OutlinedButton(onClick = { picker = "type" }) { Text(c.type.ifEmpty { tr("diag_type_button") }, maxLines = 1) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (c.mode) {
                DiagMode.LOG -> if (shownLog.isEmpty()) Hint(tr("diag_waiting_rosout", c.logLevel.label)) else LazyColumn {
                    items(shownLog) { e ->
                        Text(buildAnnotatedString {
                            append(logTime(e.stampNs)); append(' ')
                            withStyle(SpanStyle(color = levelColor(e.level), fontWeight = FontWeight.Bold)) { append(e.level.label.padEnd(5)) }
                            append(" [${e.node}] ${e.message}")
                        }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { logEntry = e }.padding(horizontal = 4.dp, vertical = 3.dp))
                    }
                }
                DiagMode.DIAG -> if (shownDiag.isEmpty()) Hint(tr("diag_waiting_diagnostics")) else LazyColumn {
                    items(shownDiag) { e ->
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = diagColor(e.level))) { append("● ") }
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(e.name) }
                            append("  ${e.level.label}")
                            if (e.message.isNotEmpty()) append("\n${e.message}")
                            if (e.name in expanded) {
                                if (e.hardwareId.isNotEmpty()) append("\nhardware_id: ${e.hardwareId}")
                                for ((k, v) in e.values) append("\n  $k: $v")
                                if (e.values.isEmpty()) append("\n  " + tr("diag_no_values"))
                            } else if (e.values.isNotEmpty()) append("\n  " + tr("diag_values_count", e.values.size))
                        }, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable { if (e.name in expanded) expanded.remove(e.name) else expanded.add(e.name) }.padding(6.dp))
                    }
                }
                DiagMode.ECHO -> if (shownEcho.isEmpty()) Hint(c.sourceHint()) else SelectionContainer {
                    Text(shownEcho, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                }
                DiagMode.PLOT -> when {
                    !c.plottable() -> Hint(tr("diag_not_plottable", c.type))
                    c.latest.get() == null || c.plotPaths.isEmpty() -> Hint(if (c.latest.get() != null) tr("diag_tap_fields") else c.sourceHint())
                    else -> SimplePlot(c, frozen = c.paused)
                }
            }
        }
    }

    when (picker) {
        "level" -> ChoiceDialog(tr("diag_min_level_title"), LogLevel.entries.map { it.label }, LogLevel.entries.indexOf(c.logLevel), { picker = null }) { c.updateLogLevel(LogLevel.entries[it]) }
        "qos" -> ChoiceDialog(tr("diag_qos_title"), QosPreset.entries.map {
            when (it) { QosPreset.SENSOR -> tr("diag_qos_sensor_option", it.label); QosPreset.RELIABLE -> it.label; QosPreset.LATCHED -> tr("diag_qos_latched_option", it.label) }
        }, QosPreset.entries.indexOf(c.qos), { picker = null }) { c.updateQos(QosPreset.entries[it]) }
        "type" -> {
            val all = if (c.mode == DiagMode.PLOT) MessageCatalog.plottable else MessageCatalog.all
            var filter by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { picker = null }, title = { Text(tr(if (c.mode == DiagMode.PLOT) "diag_message_type_plottable" else "diag_message_type")) },
                text = {
                    Column {
                        OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text(tr("diag_search_types_hint")) })
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(all.filter { it.contains(filter.trim(), true) }) { t -> Text(t, modifier = Modifier.fillMaxWidth().clickable { picker = null; c.updateType(t) }.padding(vertical = 8.dp)) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { picker = null }) { Text(tr("diag_cancel")) } })
        }
        "fields" -> {
            val msg = c.latest.get()
            val paths = msg?.let { MessageIntrospection.numericPaths(it) }.orEmpty()
            val checked = remember { mutableStateListOf<String>().apply { addAll(c.plotPaths) } }
            AlertDialog(onDismissRequest = { picker = null }, title = { Text(tr("diag_fields_title")) },
                text = {
                    if (msg == null) Text(tr("diag_wait_for_message_fields")) else if (paths.isEmpty()) Text(tr("diag_no_numeric_fields"))
                    else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(paths) { p -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(p in checked, { on -> if (on && checked.size < 4) checked.add(p) else if (!on) checked.remove(p) }); Text(p, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        } }
                    }
                },
                confirmButton = { TextButton(onClick = { picker = null; c.updatePlotPaths(paths.filter { it in checked }.take(4)) }) { Text(tr("diag_plot")) } },
                dismissButton = { TextButton(onClick = { picker = null }) { Text(tr("diag_cancel")) } })
        }
        "nodes" -> {
            val all = (c.logBuffer.nodes() + c.logNodes).distinct().sorted()
            val checked = remember { mutableStateListOf<String>().apply { addAll(c.logNodes) } }
            AlertDialog(onDismissRequest = { picker = null }, title = { Text(tr("diag_log_nodes_title")) },
                text = {
                    if (all.isEmpty()) Text(tr("diag_log_no_nodes")) else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(all) { n -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(n in checked, { on -> if (on) checked.add(n) else checked.remove(n) }); Text(n) } }
                    }
                },
                confirmButton = { TextButton(onClick = { picker = null; c.updateLogNodes(checked.toSet()) }) { Text(tr("buttons_editor_save")) } },
                dismissButton = { Row { TextButton(onClick = { picker = null; c.updateLogNodes(emptySet()) }) { Text(tr("diag_log_nodes_all")) }; TextButton(onClick = { picker = null }) { Text(tr("buttons_cancel")) } } })
        }
    }
    logEntry?.let { e ->
        val text = buildString {
            append(e.message).append("\n\n").append(tr("diag_log_detail", e.level.label, e.node, logTime(e.stampNs)))
            if (e.file.isNotEmpty()) append("\n").append(e.file).append(':').append(e.line)
            if (e.function.isNotEmpty()) append("\n").append(e.function).append("()")
        }
        AlertDialog(onDismissRequest = { logEntry = null }, title = { Text(e.node) }, text = { SelectionContainer { Text(text) } },
            confirmButton = { TextButton(onClick = { logEntry = null }) { Text(tr("params_ok")) } },
            dismissButton = {
                Row {
                    if (onShare != null) TextButton(onClick = { logEntry = null; onShare(shownLog.joinToString("\n") { LogBuffer.format(it, ::logTime) }) }) { Text(tr("diag_log_share")) }
                    TextButton(onClick = { logEntry = null; clipboard.setText(AnnotatedString(LogBuffer.format(e, ::logTime))) }) { Text(tr("diag_log_copy")) }
                }
            })
    }
}

private fun qosShort(q: QosPreset) = when (q) { QosPreset.SENSOR -> "BE"; QosPreset.RELIABLE -> "R"; QosPreset.LATCHED -> "TL" }

@Composable
private fun Hint(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column { for ((i, o) in options.withIndex()) Row(Modifier.fillMaxWidth().clickable { onDismiss(); onPick(i) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(i == selected, { onDismiss(); onPick(i) }); Text(o) } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("diag_cancel")) } })
}

/** The last few tens of seconds of each chosen field, with its legend. */
@Composable
private fun SimplePlot(c: DiagnosticsController, frozen: Boolean) {
    var frozenAt by remember { mutableStateOf<Long?>(null) }
    if (frozen && frozenAt == null) frozenAt = System.currentTimeMillis() else if (!frozen) frozenAt = null
    val now = frozenAt ?: System.currentTimeMillis()
    val data = c.series.map { it.path to it.points(now) }
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((i, d) in data.withIndex()) Text("● " + d.first + (d.second.second.lastOrNull()?.let { "  %.3f".format(Locale.ROOT, it) } ?: ""),
                color = SERIES_COLORS[i % SERIES_COLORS.size], fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Canvas(Modifier.fillMaxSize().padding(top = 6.dp)) {
            val all = data.flatMap { it.second.second.toList() }
            if (all.isEmpty()) return@Canvas
            var lo = all.min(); var hi = all.max()
            if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
            val t0 = now - 30_000
            for (k in 0..4) { val y = size.height * k / 4f; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
            for ((i, d) in data.withIndex()) {
                val (ts, vs) = d.second
                if (ts.isEmpty()) continue
                val p = Path()
                for (j in ts.indices) {
                    val x = ((ts[j] - t0) / 30_000f) * size.width
                    val y = size.height - ((vs[j] - lo) / (hi - lo)).toFloat() * size.height
                    if (j == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, SERIES_COLORS[i % SERIES_COLORS.size], style = Stroke(2f))
            }
        }
    }
}
