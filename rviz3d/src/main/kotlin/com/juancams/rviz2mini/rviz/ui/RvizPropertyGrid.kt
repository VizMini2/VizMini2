// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.camera.ImageTopics
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.rviz.display.Colors
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayStatus
import com.juancams.rviz2mini.rviz.display.PropertySpec
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.QosSettings
import com.juancams.rviz2mini.rviz.display.StatusLevel
import kotlin.math.round

/*
 * A display's properties as in the RViz panel: a two-column table (name | value) edited in
 * place and applied immediately. Used by the PC displays panel; the phone sheet uses the
 * DisplayEditors controls (larger, for fingers) and shares the QoS block with this one.
 */

/** A table row: name (indented) and value. */
@Composable
fun PropRow(label: String, indent: Int = 0, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 30.dp).padding(start = (6 + 14 * indent).dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.46f).padding(end = 6.dp))
        Box(Modifier.weight(0.54f)) { value() }
    }
}

/** Compact text field: applied on Enter or when leaving the field. */
@Composable
fun CompactField(value: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier, error: Boolean = false, numeric: Boolean = false,
                 mono: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    var text by remember(value) { mutableStateOf(value) }
    val color = MaterialTheme.colorScheme.onSurface
    val border = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
    Row(modifier.fillMaxWidth().height(28.dp).border(1.dp, border, RoundedCornerShape(4.dp)).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(text, { text = it }, singleLine = true,
            textStyle = TextStyle(color = color, fontSize = 13.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text != value) onCommit(text) }),
            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused && text != value) onCommit(text) })
        trailing?.invoke()
    }
}

/** Compact dropdown list. */
@Composable
fun CompactChoice(current: String, options: List<String>, label: (String) -> String = { it }, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().height(28.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .clickable { open = true }.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label(current), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
        }
        DropdownMenu(open, { open = false }) {
            for (o in options) DropdownMenuItem({ Text(label(o), fontSize = 13.sp) }, { open = false; if (o != current) onPick(o) })
        }
    }
}

/** Number with arrows (like RViz's), within [min, max] and with the property's step. */
@Composable
fun NumberSpinner(spec: PropertySpec.Number, value: Double, onChange: (Double) -> Unit) {
    fun snap(v: Double) = if (spec.textEntry) spec.clamp(v) else (spec.min + round((v - spec.min) / spec.step) * spec.step).coerceIn(spec.min, spec.max)
    var bad by remember(value) { mutableStateOf(false) }
    CompactField(formatNumber(value, spec.step), { t ->
        val v = t.replace(',', '.').toDoubleOrNull()
        if (v == null) bad = true else { bad = false; onChange(snap(v)) }
    }, numeric = true, error = bad, trailing = {
        Column {
            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(width = 22.dp, height = 13.dp).clickable { onChange(snap(value + spec.step)) })
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(width = 22.dp, height = 13.dp).clickable { onChange(snap(value - spec.step)) })
        }
    })
}

/** Color as in RViz: the swatch (opens the palette) and an editable "R; G; B". */
@Composable
fun ColorField(value: Int, palette: List<Int>, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun text(c: Int) = "${(c shr 16) and 255}; ${(c shr 8) and 255}; ${c and 255}"
    Box {
        CompactField(text(value), { t ->
            val p = t.split(';', ',', ' ').mapNotNull { it.trim().toIntOrNull() }
            if (p.size == 3) onChange(Colors.rgb(p[0], p[1], p[2]))
        }, mono = true, trailing = {
            Box(Modifier.padding(end = 4.dp).size(18.dp).background(Color(value or (0xFF shl 24)), RoundedCornerShape(3.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(3.dp)).clickable { open = true })
        })
        DropdownMenu(open, { open = false }) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in palette) Box(Modifier.size(22.dp).background(Color(c or (0xFF shl 24)), CircleShape).border(1.dp, Color.Gray, CircleShape)
                    .clickable { open = false; onChange(c) })
            }
        }
    }
}

/** The name of a QoS value in the list (the presets, with their text). */
private fun qosLabel(o: String) = QosPreset.entries.firstOrNull { it.name == o }?.label ?: o

/**
 * QoS as in RViz 2: the preset and below it Depth, History, Reliability and Durability, which can be changed individually.
 * `grid`: table rows (PC); otherwise, large chips (phone sheet).
 */
@Composable
fun QosEditor(config: DisplayConfig, grid: Boolean, onChange: (DisplayConfig) -> Unit) {
    val q = config.qosSettings()
    fun set(key: String, v: String) = onChange(config.with(key, v))
    if (grid) {
        PropRow("QoS") { CompactChoice(config.qos().name, QosPreset.entries.map { it.name }, ::qosLabel) { onChange(config.withQosPreset(QosPreset.valueOf(it))) } }
        PropRow("Depth", 1) {
            NumberSpinner(PropertySpec.Number(QosSettings.DEPTH, "Depth", q.depth.toDouble(), 1.0, 1000.0, 1.0), q.depth.toDouble()) { set(QosSettings.DEPTH, it.toInt().toString()) }
        }
        PropRow("History Policy", 1) { CompactChoice(q.history, QosSettings.HISTORIES) { set(QosSettings.HISTORY, it) } }
        PropRow("Reliability Policy", 1) { CompactChoice(q.reliability, QosSettings.RELIABILITIES) { set(QosSettings.RELIABILITY, it) } }
        PropRow("Durability Policy", 1) { CompactChoice(q.durability, QosSettings.DURABILITIES) { set(QosSettings.DURABILITY, it) } }
        return
    }
    var advanced by remember { mutableStateOf(false) }
    Column {
        Text("QoS", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in QosPreset.entries) FilterChip(selected = config.qos() == p, onClick = { onChange(config.withQosPreset(p)) }, label = { Text(p.label, fontSize = 12.sp) })
        }
        Row(Modifier.clickable { advanced = !advanced }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (advanced) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, Modifier.size(20.dp))
            Text("Depth ${q.depth} · ${q.history} · ${q.reliability} · ${q.durability}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (advanced) Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PropRow("Depth") {
                NumberSpinner(PropertySpec.Number(QosSettings.DEPTH, "Depth", q.depth.toDouble(), 1.0, 1000.0, 1.0), q.depth.toDouble()) { set(QosSettings.DEPTH, it.toInt().toString()) }
            }
            for ((label, key, options, cur) in listOf(
                Quad("History", QosSettings.HISTORY, QosSettings.HISTORIES, q.history),
                Quad("Reliability", QosSettings.RELIABILITY, QosSettings.RELIABILITIES, q.reliability),
                Quad("Durability", QosSettings.DURABILITY, QosSettings.DURABILITIES, q.durability))) {
                Text(label, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in options) FilterChip(selected = o == cur, onClick = { set(key, o) }, label = { Text(o, fontSize = 12.sp) })
                }
            }
        }
    }
}

private data class Quad(val a: String, val b: String, val c: List<String>, val d: String)

/** Text and color of a display's status, like RViz's "Status". */
fun statusText(level: StatusLevel?) = when (level) { StatusLevel.OK -> "Ok"; StatusLevel.WARN -> "Warn"; StatusLevel.ERROR -> "Error"; else -> "Disabled" }

/**
 * A display's properties in the table (RViz): Status (expandable with the message), Topic (with the network's topics
 * of its type), QoS and the rest. Each change goes to `onChange` immediately.
 */
@Composable
fun DisplayPropertyGrid(config: DisplayConfig, status: DisplayStatus?, tfFrames: List<String>, topics: List<String>,
                        fix: com.juancams.rviz2mini.rviz.display.Display.Fix? = null, onChange: (DisplayConfig) -> Unit) {
    // If no data arrives and there is a fix, the status is shown expanded, with the reason and the button.
    var statusOpen by remember { mutableStateOf(false) }
    Column {
        PropRow("Status", modifier = Modifier.clickable { statusOpen = !statusOpen }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(statusColorOf(status?.level), CircleShape))
                Text("  " + statusText(status?.level), fontSize = 13.sp, color = statusColorOf(status?.level), fontWeight = FontWeight.Medium)
                Icon(if (statusOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
        }
        val showFix = fix != null && status?.level != StatusLevel.OK
        if ((statusOpen || showFix) && !status?.message.isNullOrEmpty()) Text(status!!.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 6.dp, bottom = 4.dp))
        if (showFix) TextButton(onClick = { onChange(fix!!.apply(config)) }, modifier = Modifier.padding(start = 12.dp)) {
            Text("🔧 " + fix!!.label, fontSize = 13.sp)
        }
        if (config.type.needsTopic) PropRow("Topic") {
            var open by remember { mutableStateOf(false) }
            var bad by remember(config.topic) { mutableStateOf(false) }
            Box {
                CompactField(config.topic, { t ->
                    val n = ImageTopics.normalize(t)
                    if (!ImageTopics.isValidName(n)) bad = true else { bad = false; onChange(config.copy(topic = n)) }
                }, mono = true, error = bad, trailing = if (topics.isNotEmpty()) ({
                    Icon(Icons.Default.ArrowDropDown, tr("rviz_topic_hint"), Modifier.size(22.dp).clickable { open = true })
                }) else null)
                DropdownMenu(open, { open = false }) {
                    for (t in topics) DropdownMenuItem({ Text(t, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }, { open = false; onChange(config.copy(topic = t)) })
                }
            }
        }
        for (spec in config.type.properties) PropertyGridRow(spec, config, tfFrames, onChange)
    }
}

@Composable
private fun PropertyGridRow(spec: PropertySpec, c: DisplayConfig, tfFrames: List<String>, onChange: (DisplayConfig) -> Unit) {
    when (spec) {
        is PropertySpec.Choice -> if (spec.key == "qos") QosEditor(c, grid = true, onChange = onChange)
            else PropRow(spec.label) { CompactChoice(c.string(spec.key), spec.options, ::optionLabel) { onChange(c.with(spec.key, it)) } }
        is PropertySpec.Bool -> PropRow(spec.label) {
            Checkbox(c.bool(spec.key), { onChange(c.with(spec.key, it.toString())) }, Modifier.size(28.dp),
                colors = CheckboxDefaults.colors())
        }
        is PropertySpec.Number -> PropRow(if (spec.unit.isEmpty() || "(" in spec.label) spec.label else "${spec.label} (${spec.unit})") {
            NumberSpinner(spec, c.number(spec.key)) { onChange(c.with(spec.key, it.toString())) }
        }
        is PropertySpec.Color -> PropRow(spec.label) {
            ColorField(c.color(spec.key) and 0xFFFFFF, (Colors.PALETTE + spec.default).distinctBy { it and 0xFFFFFF }) { onChange(c.with(spec.key, Colors.toHex(it))) }
        }
        is PropertySpec.Text -> PropRow(spec.label) { CompactField(c.string(spec.key), { onChange(c.with(spec.key, it.trim())) }) }
        is PropertySpec.Frame -> PropRow(spec.label) {
            var open by remember { mutableStateOf(false) }
            Box {
                CompactField(c.string(spec.key), { onChange(c.with(spec.key, it.trim())) }, mono = true, trailing = if (tfFrames.isNotEmpty()) ({
                    Icon(Icons.Default.ArrowDropDown, tr("rviz_pick_tf"), Modifier.size(22.dp).clickable { open = true })
                }) else null)
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text(tr("rviz_frame_auto"), fontSize = 13.sp) }, { open = false; onChange(c.with(spec.key, "")) })
                    for (f in tfFrames) DropdownMenuItem({ Text(f, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }, { open = false; onChange(c.with(spec.key, f)) })
                }
            }
        }
        is PropertySpec.FrameSet -> {
            var open by remember { mutableStateOf(false) }
            val hidden = com.juancams.rviz2mini.rviz.display.TfFrames.patterns(c.string(spec.key)).size
            PropRow(spec.label, modifier = Modifier.clickable { open = !open }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (hidden == 0) tr("rviz_frames_all_shown") else tr("rviz_frames_some_hidden", hidden), fontSize = 13.sp)
                    Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp))
                }
            }
            if (open) Column(Modifier.padding(start = 20.dp, end = 4.dp)) { PropertyEditor(spec, c, tfFrames, onChange) }
        }
    }
}
