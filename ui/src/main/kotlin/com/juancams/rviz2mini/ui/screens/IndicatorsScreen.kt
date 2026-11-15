// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.buttons.ButtonSpec
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.indicators.IndicatorKind
import com.juancams.rviz2mini.indicators.IndicatorList
import com.juancams.rviz2mini.indicators.IndicatorMath
import com.juancams.rviz2mini.indicators.IndicatorRunner
import com.juancams.rviz2mini.indicators.IndicatorSpec
import com.juancams.rviz2mini.indicators.LightRule
import com.juancams.rviz2mini.platform.SystemClock
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.IdlException
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.theme.StatusColors
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Key of the Indicators tab (the same as before on Android). */
const val INDICATORS_KEY = "indicators"

/** The indicators of a tab and their subscriptions, outside Compose (used by the tests). */
class IndicatorsController(val tabId: String) {
    private val store = TabStore.get()
    val registry: InterfaceRegistry = Interfaces.get()
    var items by mutableStateOf(IndicatorList.fromJson(store.get(tabId)?.settings?.get(INDICATORS_KEY))); private set
    val runners = HashMap<String, IndicatorRunner>()
    /** UI clock (refreshes at 5 Hz): what is drawn is read from the executors on each tick. */
    var now by mutableLongStateOf(SystemClock.uptimeMillis())
    var editRequest by mutableStateOf<IndicatorSpec?>(null)
    var editNew by mutableStateOf(false)
    var closeEditorRequests by mutableStateOf(0)

    init { for (s in items) runners[s.id] = IndicatorRunner(s, registry) }

    private fun save(list: List<IndicatorSpec>) { items = list; store.updateSetting(tabId, INDICATORS_KEY, IndicatorList.toJson(list)) }

    fun upsert(s: IndicatorSpec) {
        runners.remove(s.id)?.close()
        Interfaces.importMissing(s.definitions)
        val i = items.indexOfFirst { it.id == s.id }
        save(if (i >= 0) items.toMutableList().also { it[i] = s } else items + s)
        runners[s.id] = IndicatorRunner(s, registry)
    }

    fun delete(id: String) { runners.remove(id)?.close(); save(items.filter { it.id != id }) }
    fun move(id: String, delta: Int) = save(IndicatorList.move(items, id, delta))
    fun requestEdit(id: String?) { editNew = id == null; editRequest = items.firstOrNull { it.id == id } ?: IndicatorSpec() }

    /** The value text of an indicator (as displayed), or null if there is none yet. */
    fun valueText(id: String): String? {
        val s = items.firstOrNull { it.id == id } ?: return null
        val r = runners[id] ?: return null
        val sample = r.latest() ?: return "—"
        return IndicatorMath.format(sample.value, r.decimals) + if (s.unit.isBlank()) "" else " ${s.unit}"
    }

    /** The status text (error, waiting, no data) or "". */
    fun stateText(id: String, now: Long = SystemClock.uptimeMillis()): String {
        val s = items.firstOrNull { it.id == id } ?: return ""
        val r = runners[id]
        val err = r?.setupError
        val sample = r?.latest()
        return when {
            err != null -> "⚠ $err"
            sample == null -> tr("indicators_waiting")
            s.timeoutS > 0 && now - sample.atMs > s.timeoutS * 1000 -> tr("indicators_stale", ((now - sample.atMs) / 1000).toInt())
            else -> ""
        }
    }

    fun close() { for (r in runners.values) r.close(); runners.clear() }
}

/** Indicators tab: a message field as a number, gauge, bar, light or text, or all of them in a table. */
@Composable
fun IndicatorsScreen(tabId: String, modifier: Modifier = Modifier, widget: Boolean = false, addRequests: Int = 0, onController: (IndicatorsController?) -> Unit = {}) {
    val c = remember(tabId) { IndicatorsController(tabId) }
    DisposableEffect(c) { onController(c); onDispose { onController(null); c.close() } }
    LaunchedEffect(c) { while (true) { c.now = SystemClock.uptimeMillis(); delay(200) } }
    var editing by remember { mutableStateOf<IndicatorSpec?>(null) }
    var editingNew by remember { mutableStateOf(false) }
    var lastAdd by remember { mutableStateOf(addRequests) }
    if (addRequests != lastAdd) { lastAdd = addRequests; editing = IndicatorSpec(); editingNew = true }
    c.editRequest?.let { editing = it; editingNew = c.editNew; c.editRequest = null }
    var closedAt by remember { mutableStateOf(c.closeEditorRequests) }
    if (c.closeEditorRequests != closedAt) { closedAt = c.closeEditorRequests; editing = null }
    var deleting by remember { mutableStateOf<IndicatorSpec?>(null) }

    Column(modifier.fillMaxSize().padding(if (widget) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!widget) Button(onClick = { editing = IndicatorSpec(); editingNew = true }) { Text(tr("indicators_add")) }
        if (c.items.isEmpty()) Text(tr(if (widget) "indicators_empty_widget" else "indicators_empty"), modifier = Modifier.padding(16.dp)
            .let { if (widget) it.clickable { editing = IndicatorSpec(); editingNew = true } else it })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = (maxWidth.value / 180f).toInt().coerceIn(1, 6)
            LazyVerticalGrid(GridCells.Fixed(columns), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(c.items, key = { it.id }, span = { s -> GridItemSpan(if (s.kind == IndicatorKind.TABLE || s.kind == IndicatorKind.TEXT) maxLineSpan else 1) }) { s ->
                    IndicatorCell(s, c, onEdit = { editing = s; editingNew = false },
                        onDuplicate = { c.upsert(s.copy(id = UUID.randomUUID().toString(), label = tr("buttons_copy_label", s.displayLabel))) },
                        onMove = { c.move(s.id, it) }, onDelete = { deleting = s })
                }
            }
        }
    }
    editing?.let { s -> IndicatorEditorDialog(c.registry, s, editingNew, onDismiss = { editing = null }) { editing = null; c.upsert(it) } }
    deleting?.let { s ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(tr("buttons_delete_title", s.displayLabel)) },
            confirmButton = { TextButton(onClick = { deleting = null; c.delete(s.id) }) { Text(tr("buttons_delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("buttons_cancel")) } })
    }
}

@Composable
private fun IndicatorCell(s: IndicatorSpec, c: IndicatorsController, onEdit: () -> Unit, onDuplicate: () -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    val now = c.now
    val r = c.runners[s.id]
    val sample = r?.latest()
    val state = c.stateText(s.id, now)
    val stale = state.isNotEmpty() && sample != null
    var menu by remember { mutableStateOf(false) }
    val accent = ButtonSpec.COLORS.getOrElse(s.color) { 0 }.let { if (it != 0) Color(it) else MaterialTheme.colorScheme.primary }
    val value = c.valueText(s.id) ?: "—"
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().alpha(if (stale || r?.setupError != null) 0.6f else 1f)) {
        Column(Modifier.padding(start = 12.dp, end = 2.dp, bottom = 8.dp, top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.displayLabel, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (s.field.isBlank()) s.topic else tr("sliders_cell_target", s.topic, s.field), fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, tr("buttons_options_for", s.displayLabel)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("buttons_menu_edit")) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_duplicate")) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_move_earlier")) }, onClick = { menu = false; onMove(-1) })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_move_later")) }, onClick = { menu = false; onMove(+1) })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_delete")) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Column(Modifier.padding(end = 10.dp, top = 4.dp)) {
                val d = r?.decimals ?: 2
                val fraction = sample?.let { IndicatorMath.toDouble(it.value) }?.let { IndicatorMath.fraction(it, s.min, s.max) }
                val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                when (s.kind) {
                    IndicatorKind.NUMBER -> Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = accent,
                        textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth())
                    IndicatorKind.GAUGE -> {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                                val thick = 10.dp.toPx()
                                val rr = min(size.width / 2f, size.height * 0.8f) - thick
                                val cx = size.width / 2f; val cy = size.height * 0.78f
                                val tl = Offset(cx - rr, cy - rr); val sz = Size(rr * 2, rr * 2)
                                drawArc(track, 150f, 240f, false, tl, sz, style = Stroke(thick, cap = StrokeCap.Round))
                                if (fraction != null) {
                                    drawArc(accent, 150f, (240 * fraction).toFloat(), false, tl, sz, style = Stroke(thick, cap = StrokeCap.Round))
                                    val a = Math.toRadians(150 + 240 * fraction)
                                    drawLine(accent, Offset(cx, cy), Offset(cx + (rr - thick) * cos(a).toFloat(), cy + (rr - thick) * sin(a).toFloat()), 3.dp.toPx())
                                    drawCircle(accent, 5.dp.toPx(), Offset(cx, cy))
                                }
                            }
                            Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = accent, maxLines = 1)
                        }
                        Limits(s, d)
                    }
                    IndicatorKind.BAR -> {
                        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = accent, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(), maxLines = 1)
                        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(top = 2.dp)) {
                            val rr = size.height / 2
                            drawRoundRect(track, cornerRadius = CornerRadius(rr))
                            if (fraction != null) drawRoundRect(accent, size = Size(maxOf(size.height, (size.width * fraction).toFloat()), size.height), cornerRadius = CornerRadius(rr))
                        }
                        Limits(s, d)
                    }
                    IndicatorKind.LIGHT -> Row(verticalAlignment = Alignment.CenterVertically) {
                        val on = sample != null && IndicatorMath.lightOn(s.rule, s.threshold, sample.value)
                        val onCol = ButtonSpec.COLORS.getOrElse(s.onColor) { 0 }.let { if (it != 0) Color(it) else StatusColors.ok }
                        val offCol = ButtonSpec.COLORS.getOrElse(s.offColor) { 0 }.let { if (it != 0) Color(it) else Color.Gray }
                        Box(Modifier.size(44.dp).clip(CircleShape).background(if (sample == null) track else if (on) onCol else offCol.copy(alpha = 0.5f)))
                        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 10.dp), maxLines = 1)
                    }
                    IndicatorKind.TEXT -> Text(value, fontSize = 16.sp, color = accent, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    IndicatorKind.TABLE -> Column {
                        for ((k, v) in sample?.rows.orEmpty()) Row(Modifier.padding(vertical = 1.dp)) {
                            Text(k, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                            Text(v, fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.3f))
                        }
                    }
                }
            }
            if (state.isNotEmpty()) Text(state, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                color = when { r?.setupError != null -> StatusColors.error; sample != null -> StatusColors.warn; else -> MaterialTheme.colorScheme.onSurfaceVariant })
        }
    }
}

@Composable
private fun Limits(s: IndicatorSpec, decimals: Int) = Row {
    Text(IndicatorMath.format(s.min, decimals), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    Text(IndicatorMath.format(s.max, decimals), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun kindLabel(k: IndicatorKind) = tr(when (k) {
    IndicatorKind.NUMBER -> "indicators_kind_number"; IndicatorKind.GAUGE -> "indicators_kind_gauge"; IndicatorKind.BAR -> "indicators_kind_bar"
    IndicatorKind.LIGHT -> "indicators_kind_light"; IndicatorKind.TEXT -> "indicators_kind_text"; IndicatorKind.TABLE -> "indicators_kind_table"
})

private fun ruleLabel(r: LightRule) = tr(when (r) {
    LightRule.NONZERO -> "indicators_rule_nonzero"; LightRule.ABOVE -> "indicators_rule_above"; LightRule.BELOW -> "indicators_rule_below"; LightRule.EQUALS -> "indicators_rule_equals"
})

/** Create or edit an indicator: topic, type, field, appearance, units, colors, reception and expiry. */
@Composable
fun IndicatorEditorDialog(registry: InterfaceRegistry, initial: IndicatorSpec, isNew: Boolean, onDismiss: () -> Unit, onSave: (IndicatorSpec) -> Unit) {
    var label by remember { mutableStateOf(initial.label) }
    var topic by remember { mutableStateOf(initial.topic) }
    var type by remember { mutableStateOf(initial.type) }
    var field by remember { mutableStateOf(initial.field) }
    var kind by remember { mutableStateOf(initial.kind) }
    var min by remember { mutableStateOf(numText(initial.min)) }
    var max by remember { mutableStateOf(numText(initial.max)) }
    var decimals by remember { mutableStateOf(if (initial.decimals >= 0) initial.decimals.toString() else "") }
    var unit by remember { mutableStateOf(initial.unit) }
    var rule by remember { mutableStateOf(initial.rule) }
    var threshold by remember { mutableStateOf(initial.threshold) }
    var onColor by remember { mutableStateOf(initial.onColor) }
    var offColor by remember { mutableStateOf(initial.offColor) }
    var color by remember { mutableStateOf(initial.color) }
    var qos by remember { mutableStateOf(initial.qos) }
    var timeout by remember { mutableStateOf(numText(initial.timeoutS)) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var pickingField by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val spec: MsgSpec? = remember(type) { if (type.isBlank()) null else try { registry.msg(type) } catch (e: IdlException) { error = e.message; null } }

    @Composable
    fun colors(selected: Int, onPick: (Int) -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((i, col) in ButtonSpec.COLORS.withIndex()) Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (col == 0) Color(0xFFBDBDBD) else Color(col))
                .let { if (i == selected) it.border(3.dp, Color(0xFF212121), CircleShape) else it }
                .semantics { contentDescription = tr("buttons_editor_color_n", i + 1) }.clickable { onPick(i) },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (isNew) "indicators_editor_new_title" else "indicators_editor_edit_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, singleLine = true, label = { Text(tr("indicators_editor_label")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(topic, { topic = it }, singleLine = true, label = { Text(tr("sliders_editor_topic")) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    if (RosConnection.status !is RosConnection.Status.Connected) { error = tr("buttons_editor_not_connected"); return@OutlinedButton }
                    RosConnection.discover { g ->
                        val items = g?.topics()?.map { it.name to it.type }.orEmpty()
                        if (items.isEmpty()) error = tr("buttons_editor_no_topics_found", RosConnection.config.domainId) else browsing = items
                    }
                }) { Text(tr("sliders_editor_browse")) }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text(type.ifBlank { tr("buttons_editor_choose_message_type") }) }
                Text(tr("indicators_editor_kind"), style = MaterialTheme.typography.titleSmall)
                Column {
                    for (row in IndicatorKind.entries.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (k in row) FilterChip(kind == k, { kind = k }, label = { Text(kindLabel(k), fontSize = 12.sp) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(field, { field = it }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(tr(if (kind == IndicatorKind.TABLE) "indicators_editor_table_field" else "indicators_editor_field")) })
                    if (spec != null) TextButton(onClick = { pickingField = true }) { Text("…") }
                }
                Text(tr("indicators_editor_field_hint"), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (kind == IndicatorKind.GAUGE || kind == IndicatorKind.BAR) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(min, { min = it }, singleLine = true, label = { Text(tr("sliders_editor_min")) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(max, { max = it }, singleLine = true, label = { Text(tr("sliders_editor_max")) }, modifier = Modifier.weight(1f))
                }
                if (kind != IndicatorKind.TABLE) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(unit, { unit = it }, singleLine = true, label = { Text(tr("indicators_editor_unit")) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(decimals, { decimals = it }, singleLine = true, label = { Text(tr("indicators_editor_decimals")) }, modifier = Modifier.weight(1f))
                }
                if (kind == IndicatorKind.LIGHT) {
                    Text(tr("indicators_editor_light"), style = MaterialTheme.typography.titleSmall)
                    for (r in LightRule.entries) Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(rule == r, { rule = r }); Text(ruleLabel(r)) }
                    if (rule != LightRule.NONZERO) OutlinedTextField(threshold, { threshold = it }, singleLine = true, label = { Text(tr("indicators_editor_threshold")) })
                    Text(tr("indicators_editor_on_color")); colors(onColor) { onColor = it }
                    Text(tr("indicators_editor_off_color")); colors(offColor) { offColor = it }
                } else { Text(tr("sliders_editor_color")); colors(color) { color = it } }
                Text(tr("indicators_editor_reception"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((q, key) in listOf(QosPreset.SENSOR to "buttons_editor_best_effort", QosPreset.RELIABLE to "buttons_editor_reliable", QosPreset.LATCHED to "buttons_editor_transient_local"))
                        FilterChip(qos == q, { qos = q }, label = { Text(tr(key), fontSize = 12.sp) })
                }
                Text(tr("indicators_editor_qos_hint"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(timeout, { timeout = it }, singleLine = true, label = { Text(tr("indicators_editor_timeout")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = null
                val t = topic.trim()
                if (t.isEmpty()) { error = tr("buttons_editor_name_required"); return@TextButton }
                if (!Regex("/?[A-Za-z0-9_~{}/]+").matches(t)) { error = tr("buttons_editor_invalid_ros_name"); return@TextButton }
                if (type.isBlank()) { error = tr("buttons_editor_choose_a_type"); return@TextButton }
                val s = spec ?: run { error = tr("buttons_editor_type_not_valid", type); return@TextButton }
                val f = field.trim()
                if (kind == IndicatorKind.TABLE) {
                    if (IndicatorRunner.tableSpec(s, f) == null) { error = tr("indicators_editor_error_table", f, type); return@TextButton }
                } else {
                    val fs = IndicatorMath.resolve(s, f)
                    if (fs == null || fs.nested?.isTime == false) { error = tr("indicators_editor_error_field", f, type); return@TextButton }
                    if (kind.numeric && fs.prim?.isText == true) { error = tr("indicators_editor_error_numeric", f); return@TextButton }
                }
                fun n(x: String) = x.replace(',', '.').trim().toDoubleOrNull()
                var mn = initial.min; var mx = initial.max
                if (kind == IndicatorKind.GAUGE || kind == IndicatorKind.BAR) {
                    mn = n(min) ?: run { error = tr("sliders_editor_error_number"); return@TextButton }
                    mx = n(max) ?: run { error = tr("sliders_editor_error_number"); return@TextButton }
                    if (mn >= mx) { error = tr("sliders_editor_error_min_max"); return@TextButton }
                }
                val dec = if (decimals.isBlank()) -1 else decimals.trim().toIntOrNull()?.takeIf { it in 0..9 } ?: run { error = tr("indicators_editor_error_decimals"); return@TextButton }
                if (kind == IndicatorKind.LIGHT && (rule == LightRule.ABOVE || rule == LightRule.BELOW) && n(threshold) == null) { error = tr("sliders_editor_error_number"); return@TextButton }
                val to = n(timeout)?.takeIf { it in 0.0..3600.0 } ?: run { error = tr("indicators_editor_error_timeout"); return@TextButton }
                onSave(initial.copy(label = label.trim(), topic = if (t.startsWith("/")) t else "/$t", type = type, field = f, kind = kind, min = mn, max = mx,
                    decimals = dec, unit = unit.trim(), rule = rule, threshold = threshold.trim(), onColor = onColor, offColor = offColor, color = color,
                    qos = qos, timeoutS = to, definitions = Interfaces.customDependencies(type)))
            }) { Text(tr("buttons_editor_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("buttons_cancel")) } },
    )
    if (picking) TypePickerDialog(registry, "msg", onDismiss = { picking = false }) { picking = false; type = it; error = null }
    if (pickingField && spec != null) {
        val leaves = remember(spec, kind) { IndicatorMath.leaves(spec).filter { !kind.numeric || it.prim?.isText != true }.map { it.path } }
        AlertDialog(onDismissRequest = { pickingField = false }, title = { Text(tr("indicators_editor_field")) },
            text = { LazyColumn { items(leaves) { p -> Text(p, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().clickable { pickingField = false; field = p }.padding(vertical = 8.dp)) } } },
            confirmButton = { TextButton(onClick = { pickingField = false }) { Text(tr("buttons_cancel")) } })
    }
    browsing?.let { items ->
        AlertDialog(onDismissRequest = { browsing = null }, title = { Text(tr("buttons_editor_topics_on_network")) },
            text = { LazyColumn { items(items) { (n, t) -> Column(Modifier.fillMaxWidth().clickable { browsing = null; topic = n; if (t != type && registry.has(t)) type = t }.padding(vertical = 6.dp)) { Text(n, fontFamily = FontFamily.Monospace); Text(t, style = MaterialTheme.typography.bodySmall) } } } },
            confirmButton = { TextButton(onClick = { browsing = null }) { Text(tr("buttons_cancel")) } })
    }
}

private fun numText(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else d.toString()
