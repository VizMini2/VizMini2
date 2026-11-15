// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.camera.ImageTopics
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayFactory
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayStatus
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.PropertySpec
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.StatusLevel
import java.util.Locale
import kotlin.math.round

/*
 * The RViz display dialogs in Compose (the same controls as the Android sheets: sliders with their
 * value, option buttons, color swatches, frame with TF picker, add by type or by topic).
 */

/** How these dialogs open: on Android, as a bottom sheet (like its usual sheets); on the PC, as a dialog. */
object RvizDialogStyle { @Volatile var bottomSheet = false }

/** Dialog frame: bottom sheet or dialog, with title, scrollable body and buttons. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DialogFrame(onDismiss: () -> Unit, title: @Composable () -> Unit, buttons: @Composable () -> Unit, body: @Composable () -> Unit) {
    if (RvizDialogStyle.bottomSheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss,
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp).verticalScroll(rememberScrollState())) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                Spacer(Modifier.padding(top = 8.dp))
                body()
                Spacer(Modifier.padding(top = 12.dp))
                buttons()
            }
        }
    } else AlertDialog(
        onDismissRequest = onDismiss, title = title,
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { body() } },
        confirmButton = buttons, modifier = Modifier.width(560.dp),
    )
}

/** Color of a display's status (the same as on Android). */
fun statusColorOf(level: StatusLevel?): Color = when (level) {
    StatusLevel.OK -> Color(0xFF43A047)
    StatusLevel.WARN -> Color(0xFFFB8C00)
    StatusLevel.ERROR -> Color(0xFFE53935)
    else -> Color.Gray
}

private val OPTION_KEYS = mapOf(
    "intensity" to "rviz_option_intensity", "range" to "rviz_option_range", "flat" to "rviz_option_flat",
    "auto" to "rviz_option_auto", "rgb" to "rviz_option_rgb", "z" to "rviz_option_height_z",
    "arrow" to "rviz_option_arrow", "axes" to "rviz_option_axes", "none" to "rviz_option_none",
    "arrows" to "rviz_option_arrows", "map" to "rviz_option_map", "costmap" to "rviz_option_costmap",
    "raw" to "rviz_option_raw", "x" to "rviz_option_x_axis", "y" to "rviz_option_y_axis",
    "lines" to "rviz_option_lines", "billboards" to "rviz_option_billboards",
    "squares" to "rviz_option_squares", "circles" to "rviz_option_circles"
)
private val QOS_LABELS = QosPreset.entries.associate { it.name to it.label }

/** Text of an option of the choice properties (the XY/XZ/YZ planes are shown as is). */
fun optionLabel(o: String): String = OPTION_KEYS[o]?.let { tr(it) } ?: QOS_LABELS[o] ?: o

/** A number with the decimals its step requires. */
fun formatNumber(v: Double, step: Double): String {
    val decimals = when {
        step >= 1.0 -> 0
        step >= 0.1 -> 1
        step >= 0.01 -> 2
        else -> 3
    }
    return String.format(Locale.ROOT, "%.${decimals}f", v)
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

/**
 * Edit a display: name, topic and the properties of its type. Applied when Save is pressed. `topics`: those on the
 * network of its type (to pick from); `tfFrames`: the TF frames (for frame properties).
 */
@Composable
fun DisplayEditorDialog(
    config: DisplayConfig,
    status: DisplayStatus?,
    tfFrames: List<String>,
    topics: List<String> = emptyList(),
    /** Proposed fix for what is wrong (another topic, another QoS): applied and saved when pressed. */
    fix: com.juancams.rviz2mini.rviz.display.Display.Fix? = null,
    onDismiss: () -> Unit,
    onSave: (DisplayConfig) -> Unit,
    onDelete: () -> Unit,
) {
    var edited by remember(config.id) { mutableStateOf(config) }
    var name by remember(config.id) { mutableStateOf(config.name) }
    var topic by remember(config.id) { mutableStateOf(config.topic) }
    var topicError by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    DialogFrame(onDismiss,
        title = {
            Column {
                Text(config.type.label, fontWeight = FontWeight.Bold)
                if (config.type.needsTopic) Text(config.type.rosType, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val msg = status?.message.orEmpty().ifEmpty { if (status?.level == StatusLevel.OK) tr("rviz_status_ok") else "" }
                Text(tr("rviz_status_line", msg), fontSize = 13.sp, color = statusColorOf(status?.level), modifier = Modifier.padding(top = 6.dp))
                if (fix != null && status?.level != StatusLevel.OK) androidx.compose.material3.FilledTonalButton(onClick = { onSave(fix.apply(config)) },
                    modifier = Modifier.padding(top = 6.dp)) { Text(fix.label) }
            }
        },
        buttons = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmDelete = true }) { Text(tr("rviz_action_remove"), color = statusColorOf(StatusLevel.ERROR)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(tr("rviz_action_cancel")) }
                Button(onClick = {
                    var result = edited.copy(name = name.trim().ifEmpty { config.type.label }.take(40))
                    if (config.type.needsTopic) {
                        val t = ImageTopics.normalize(topic)
                        if (!ImageTopics.isValidName(t)) { topicError = true; return@Button }
                        result = result.copy(topic = t)
                    }
                    onSave(result)
                }) { Text(tr("rviz_action_save")) }
            }
        },
    ) {
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("rviz_editor_name_hint")) }, modifier = Modifier.fillMaxWidth())
        if (config.type.needsTopic) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(topic, { topic = it; topicError = false }, singleLine = true, label = { Text(tr("rviz_topic_hint")) },
                    isError = topicError, supportingText = if (topicError) ({ Text(tr("rviz_invalid_topic")) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (topics.isNotEmpty()) ({ TextButton(onClick = { open = true }) { Text("▾") } }) else null)
                DropdownMenu(open, { open = false }) { for (t in topics) DropdownMenuItem({ Text(t) }, { topic = t; topicError = false; open = false }) }
            }
        }
        for (spec in config.type.properties) PropertyEditor(spec, edited, tfFrames) { edited = it }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("rviz_remove_display_title", config.name)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(tr("rviz_action_remove")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("rviz_action_cancel")) } },
    )
}

/** The control for a property (changes go to `onChange` with the new configuration). */
@Composable
fun PropertyEditor(spec: PropertySpec, edited: DisplayConfig, tfFrames: List<String>, onChange: (DisplayConfig) -> Unit) {
    when (spec) {
        is PropertySpec.Bool -> Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onChange(edited.with(spec.key, (!edited.bool(spec.key)).toString())) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, Modifier.weight(1f))
            Switch(edited.bool(spec.key), { onChange(edited.with(spec.key, it.toString())) })
        }

        is PropertySpec.Number -> if (spec.textEntry) NumberField(spec, edited.number(spec.key)) { onChange(edited.with(spec.key, it.toString())) }
        else Column(Modifier.padding(top = 12.dp)) {
            val steps = round((edited.number(spec.key) - spec.min) / spec.step)
            val v = (spec.min + steps * spec.step).coerceIn(spec.min, spec.max)
            Text(tr("rviz_property_value", spec.label, formatNumber(v, spec.step), spec.unit).trimEnd())
            // Continuous and rounded to the step by hand: with thousands of steps, the Slider ticks don't look good.
            Slider(v.toFloat(), { x ->
                val s = (spec.min + round((x - spec.min) / spec.step) * spec.step).coerceIn(spec.min, spec.max)
                onChange(edited.with(spec.key, s.toString()))
            }, valueRange = spec.min.toFloat()..spec.max.toFloat())
        }

        is PropertySpec.Choice -> if (spec.key == "qos") QosEditor(edited, grid = false, onChange = onChange) else Column {
            SectionTitle(spec.label)
            val current = edited.string(spec.key)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (o in spec.options) FilterChip(selected = o == current, onClick = { onChange(edited.with(spec.key, o)) }, label = { Text(optionLabel(o), fontSize = 12.sp) })
            }
        }

        is PropertySpec.Color -> Column {
            SectionTitle(spec.label)
            val selected = edited.color(spec.key) and 0xFFFFFF
            val palette = (Colors.PALETTE + spec.default + edited.color(spec.key)).distinctBy { it and 0xFFFFFF }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in palette) {
                    val sel = (c and 0xFFFFFF) == selected
                    Box(Modifier.size(30.dp).background(Color(c or (0xFF shl 24)), CircleShape)
                        .border(if (sel) 3.dp else 1.dp, if (sel) Color(0xFF2196F3) else Color.Gray, CircleShape)
                        .clickable { onChange(edited.with(spec.key, Colors.toHex(c))) })
                }
            }
        }

        is PropertySpec.Text -> OutlinedTextField(edited.string(spec.key), { onChange(edited.with(spec.key, it.trim())) }, singleLine = true,
            label = { Text(spec.label) }, supportingText = spec.hint.takeIf { it.isNotEmpty() }?.let { h -> { Text(h) } },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

        is PropertySpec.FrameSet -> FrameSetEditor(spec, edited, tfFrames, onChange)

        is PropertySpec.Frame -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            var open by remember { mutableStateOf(false) }
            OutlinedTextField(edited.string(spec.key), { onChange(edited.with(spec.key, it.trim())) }, singleLine = true,
                label = { Text(tr("rviz_frame_property_hint", spec.label)) }, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { open = true }, enabled = tfFrames.isNotEmpty()) { Text(tr("rviz_pick_tf")) }
                DropdownMenu(open, { open = false }) {
                    for (f in tfFrames) DropdownMenuItem({ Text(f) }, { onChange(edited.with(spec.key, f)); open = false })
                }
            }
        }
    }
}

/**
 * The visible frames: a checkbox per incoming frame (unchecking it hides it) and patterns with `*` to hide
 * several at once (e.g. `*wheel*`). The list of hidden ones is saved.
 */
@Composable
private fun FrameSetEditor(spec: PropertySpec.FrameSet, edited: DisplayConfig, tfFrames: List<String>, onChange: (DisplayConfig) -> Unit) {
    val hidden = com.juancams.rviz2mini.rviz.display.TfFrames.patterns(edited.string(spec.key))
    fun save(list: List<String>) = onChange(edited.with(spec.key, com.juancams.rviz2mini.rviz.display.TfFrames.join(list)))
    var search by remember { mutableStateOf("") }
    Column {
        SectionTitle(tr("rviz_frames_title"))
        Text(tr("rviz_frames_hint"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(search, { search = it }, singleLine = true, label = { Text(tr("rviz_frames_search")) }, modifier = Modifier.weight(1f))
            // With a `*`, hide that pattern.
            if ('*' in search) TextButton(onClick = { save(hidden + search.trim().removePrefix("/")); search = "" }) { Text(tr("rviz_frames_hide_pattern")) }
        }
        val patterns = hidden.filter { '*' in it }
        if (patterns.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in patterns) FilterChip(selected = true, onClick = { save(hidden - p) }, label = { Text("$p  ✕", fontSize = 12.sp) })
        }
        val frames = (tfFrames.map { it.removePrefix("/") } + hidden.filter { '*' !in it }).distinct().sorted()
            .filter { search.isBlank() || '*' in search || it.contains(search.trim(), ignoreCase = true) }
        if (frames.isEmpty()) Text(tr("rviz_frames_none"), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        else Row(Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { save(hidden.filter { h -> frames.none { it == h } }) }) { Text(tr("rviz_frames_show_all")) }
            TextButton(onClick = { save((hidden + frames).distinct()) }) { Text(tr("rviz_frames_hide_all")) }
        }
        for (f in frames) {
            val byPattern = '*' !in f && f !in hidden && com.juancams.rviz2mini.rviz.display.TfFrames.hidden(f, patterns)
            val visible = f !in hidden && !byPattern
            Row(Modifier.fillMaxWidth().clickable(enabled = !byPattern) { save(if (visible) hidden + f else hidden - f) }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(visible, { save(if (visible) hidden + f else hidden - f) }, enabled = !byPattern)
                Text(f, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 14.sp,
                    color = if (visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (byPattern) Text("  " + tr("rviz_frames_by_pattern"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NumberField(spec: PropertySpec.Number, value: Double, onChange: (Double) -> Unit) {
    var text by remember(spec.key) { mutableStateOf(formatNumber(value, spec.step)) }
    val v = text.replace(',', '.').toDoubleOrNull()
    val bad = v == null || v < spec.min || v > spec.max
    OutlinedTextField(text, { t ->
        text = t
        t.replace(',', '.').toDoubleOrNull()?.let { onChange(spec.clamp(it)) }
    }, singleLine = true, isError = bad,
        label = { Text(if (spec.unit.isEmpty()) spec.label else tr("rviz_property_with_unit", spec.label, spec.unit)) },
        supportingText = if (bad) ({ Text(tr("rviz_property_range", spec.min.toString(), spec.max.toString())) }) else null,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

// ====================================================================== add display

/** Add a display, as in RViz: by type, or by topic (searching the network or by hand). */
@Composable
fun AddDisplayDialog(onDismiss: () -> Unit, existing: List<DisplayConfig> = emptyList(), onAdd: (DisplayConfig) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    DialogFrame(onDismiss,
        title = { Text(tr("rviz_add_display_title"), fontWeight = FontWeight.Bold) },
        buttons = { Row { Spacer(Modifier.weight(1f)); TextButton(onClick = onDismiss) { Text(tr("rviz_action_cancel")) } } },
    ) {
        SecondaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(tr("rviz_add_by_type")) })
            Tab(tab == 1, { tab = 1 }, text = { Text(tr("rviz_add_by_topic")) })
        }
        if (tab == 0) for (type in DisplayType.entries) AddRow(type.label,
            type.description + if (type.needsTopic) "\n" + tr("rviz_default_topic", type.defaultTopic) else "") { onAdd(DisplayConfig.create(type)) }
        else ByTopicPane(existing, onAdd)
    }
}

@Composable
private fun AddRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ByTopicPane(existing: List<DisplayConfig>, onAdd: (DisplayConfig) -> Unit) {
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(tr("rviz_search_intro")) }
    val hits = remember { mutableStateListOf<Pair<DisplayType, String>>() }
    var manual by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf(false) }
    val types = DisplayType.TOPIC_TYPES
    var typeIndex by remember { mutableStateOf(0) }

    fun search() {
        if (RosConnection.status !is RosConnection.Status.Connected) { status = tr("rviz_ros_not_connected"); return }
        searching = true; status = tr("rviz_searching"); hits.clear()
        fun show(found: List<Pair<DisplayType, String>>, ignored: Int) = Platform.main.post {
            searching = false
            status = when {
                found.isEmpty() && ignored > 0 -> tr("rviz_search_none_displayable", ignored)
                found.isEmpty() -> tr("rviz_search_no_topics")
                else -> tr("rviz_search_found", found.size) + if (ignored > 0) " · " + tr("rviz_search_hidden", ignored) else ""
            }
            hits.clear(); hits.addAll(found)
        }
        RosConnection.discover { graph ->
            if (graph != null) {
                // All topics with a publisher whose type some display can draw.
                val topics = graph.topics(includeHidden = false).filter { it.publishers > 0 }
                val found = topics.flatMap { t -> DisplayFactory.displaysFor(t.name, t.types).map { it to t.name } }
                show(found, topics.count { t -> found.none { it.second == t.name } })
                return@discover
            }
            // Without native discovery: the usual names of each type are probed.
            val requests = LinkedHashMap<RosConnection.ProbeRequest<*>, Pair<DisplayType, String>>()
            for (type in types) for (topic in type.commonTopics) DisplayFactory.probeRequest(type, topic)?.let { requests[it] = type to topic }
            RosConnection.probe(requests.keys.toList(), 3000) { ok -> show(ok.mapNotNull { requests[it] }.sortedBy { it.second }, 0) }
        }
    }

    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { search() }, enabled = !searching) { Text(tr("rviz_search_topics")) }
            if (searching) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(24.dp), strokeWidth = 2.dp)
        }
        Text(status, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        // Build the RViz in one go: one display per topic (the type that draws it best), skipping those already present.
        val firstPerTopic = hits.groupBy { it.second }.map { (t, l) -> l.first().first to t }
            .filter { (type, t) -> existing.none { it.topic == t && it.type == type } }
        if (firstPerTopic.isNotEmpty()) Button(onClick = {
            for ((type, t) in firstPerTopic) onAdd(DisplayConfig.create(type, DisplayList.nameForTopic(type, t), t))
        }, modifier = Modifier.padding(vertical = 6.dp)) { Text(tr("rviz_add_all", firstPerTopic.size)) }
        for ((type, topic) in hits) AddRow(topic, "${type.label} · ${type.rosType}") { onAdd(DisplayConfig.create(type, DisplayList.nameForTopic(type, topic), topic)) }

        SectionTitle(tr("rviz_add_manually"))
        OutlinedTextField(manual, { manual = it; manualError = false }, singleLine = true, label = { Text(tr("rviz_topic_hint")) }, isError = manualError,
            supportingText = if (manualError) ({ Text(tr("rviz_invalid_topic")) }) else null, modifier = Modifier.fillMaxWidth())
        var open by remember { mutableStateOf(false) }
        Box(Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("${types[typeIndex].label}  (${types[typeIndex].rosType})") }
            DropdownMenu(open, { open = false }) {
                types.forEachIndexed { i, t -> DropdownMenuItem({ Text("${t.label}  (${t.rosType})") }, { typeIndex = i; open = false }) }
            }
        }
        Button(onClick = {
            val t = ImageTopics.normalize(manual)
            if (!ImageTopics.isValidName(t)) { manualError = true; return@Button }
            val type = types[typeIndex]
            onAdd(DisplayConfig.create(type, DisplayList.nameForTopic(type, t), t))
        }, modifier = Modifier.padding(top = 8.dp)) { Text(tr("rviz_action_add")) }
    }
}
