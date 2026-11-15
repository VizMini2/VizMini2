// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.buttons.ButtonSpec
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.IdlException
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.sliders.SliderList
import com.juancams.rviz2mini.sliders.SliderMath
import com.juancams.rviz2mini.sliders.SliderMode
import com.juancams.rviz2mini.sliders.SliderRunner
import com.juancams.rviz2mini.sliders.SliderSpec
import com.juancams.rviz2mini.sliders.SliderState
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.common.MessageFormState
import com.juancams.rviz2mini.ui.common.MessageFormView
import com.juancams.rviz2mini.ui.theme.StatusColors
import org.json.JSONObject
import java.util.UUID

/** Key of the Sliders tab (the same as before on Android). */
const val SLIDERS_KEY = "sliders"

/** The sliders of a tab and their executors, outside Compose (the tests use it). */
class SlidersController(val tabId: String) {
    private val store = TabStore.get()
    val registry: InterfaceRegistry = Interfaces.get()
    var sliders by mutableStateOf(SliderList.fromJson(store.get(tabId)?.settings?.get(SLIDERS_KEY))); private set
    val states = mutableStateMapOf<String, SliderState>()
    /** The displayed value of each slider (moved by the finger or by "exact value"). */
    val values = mutableStateMapOf<String, Double>()
    val runners = HashMap<String, SliderRunner>()
    var editRequest by mutableStateOf<SliderSpec?>(null)
    var editNew by mutableStateOf(false)
    var closeEditorRequests by mutableStateOf(0)

    init { for (s in sliders) add(s) }

    private fun add(s: SliderSpec) {
        val r = SliderRunner(s, registry) { st -> states[s.id] = st }
        runners[s.id] = r
        values[s.id] = SliderMath.quantize(r.value, s.min, s.max, s.step)
    }

    private fun save(list: List<SliderSpec>) { sliders = list; store.updateSetting(tabId, SLIDERS_KEY, SliderList.toJson(list)) }

    fun upsert(s: SliderSpec) {
        runners.remove(s.id)?.close(); states.remove(s.id)
        Interfaces.importMissing(s.definitions)
        val i = sliders.indexOfFirst { it.id == s.id }
        save(if (i >= 0) sliders.toMutableList().also { it[i] = s } else sliders + s)
        add(s)
    }

    fun delete(id: String) { runners.remove(id)?.close(); states.remove(id); values.remove(id); save(sliders.filter { it.id != id }) }
    fun move(id: String, delta: Int) = save(SliderList.move(sliders, id, delta))

    fun drag(id: String, v: Double) {
        val r = runners[id] ?: return
        val q = SliderMath.quantize(v, r.spec.min, r.spec.max, r.spec.step)
        r.moveTo(q); values[id] = q
    }

    fun release(id: String): Double? = runners[id]?.touchEnd()?.also { values[id] = it }

    fun setExact(id: String, v: Double) {
        val r = runners[id] ?: return
        val q = SliderMath.quantize(v, r.spec.min, r.spec.max, r.spec.step)
        r.setExact(q); values[id] = q
    }

    fun requestEdit(id: String?) { editNew = id == null; editRequest = sliders.firstOrNull { it.id == id } ?: SliderSpec() }

    /** Tab hidden: whatever is being dragged is released. */
    fun pauseAll() { for ((id, r) in runners) values[id] = r.pause() }

    fun close() { for (r in runners.values) r.close(); runners.clear() }
}

/** Sliders tab: sliders that publish a number into a message field, while dragging or on release. */
@Composable
fun SlidersScreen(tabId: String, modifier: Modifier = Modifier, widget: Boolean = false, addRequests: Int = 0, onController: (SlidersController?) -> Unit = {}) {
    val c = remember(tabId) { SlidersController(tabId) }
    DisposableEffect(c) { onController(c); onDispose { onController(null); c.close() } }
    var editing by remember { mutableStateOf<SliderSpec?>(null) }
    var editingNew by remember { mutableStateOf(false) }
    var lastAdd by remember { mutableStateOf(addRequests) }
    if (addRequests != lastAdd) { lastAdd = addRequests; editing = SliderSpec(); editingNew = true }
    c.editRequest?.let { editing = it; editingNew = c.editNew; c.editRequest = null }
    var closedAt by remember { mutableStateOf(c.closeEditorRequests) }
    if (c.closeEditorRequests != closedAt) { closedAt = c.closeEditorRequests; editing = null }
    var exact by remember { mutableStateOf<SliderSpec?>(null) }
    var deleting by remember { mutableStateOf<SliderSpec?>(null) }

    Column(modifier.fillMaxSize().padding(if (widget) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!widget) Button(onClick = { editing = SliderSpec(); editingNew = true }) { Text(tr("sliders_add")) }
        if (c.sliders.isEmpty()) Text(tr(if (widget) "sliders_empty_widget" else "sliders_empty"), modifier = Modifier.padding(16.dp)
            .let { if (widget) it.clickable { editing = SliderSpec(); editingNew = true } else it })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = (maxWidth.value / 300f).toInt().coerceIn(1, 4)
            LazyVerticalGrid(GridCells.Fixed(columns), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(c.sliders, key = { it.id }) { s -> SliderCell(s, c, onExact = { exact = s }, onEdit = { editing = s; editingNew = false },
                    onDuplicate = { c.upsert(s.copy(id = UUID.randomUUID().toString(), label = tr("buttons_copy_label", s.displayLabel))) },
                    onMove = { c.move(s.id, it) }, onDelete = { deleting = s }) }
            }
        }
    }

    editing?.let { s -> SliderEditorDialog(c.registry, s, editingNew, onDismiss = { editing = null }) { editing = null; c.upsert(it) } }
    exact?.let { s ->
        val r = c.runners[s.id]
        var text by remember(s.id) { mutableStateOf(r?.format(c.values[s.id] ?: s.initial).orEmpty()) }
        AlertDialog(onDismissRequest = { exact = null }, title = { Text(s.displayLabel) },
            text = { Column { Text(tr("sliders_set_value_range", r?.format(s.min) ?: s.min.toString(), r?.format(s.max) ?: s.max.toString())); OutlinedTextField(text, { text = it }, singleLine = true) } },
            confirmButton = { TextButton(onClick = { exact = null; text.replace(',', '.').trim().toDoubleOrNull()?.let { c.setExact(s.id, it) } }) { Text(tr("sliders_send")) } },
            dismissButton = { TextButton(onClick = { exact = null }) { Text(tr("buttons_cancel")) } })
    }
    deleting?.let { s ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(tr("buttons_delete_title", s.displayLabel)) },
            confirmButton = { TextButton(onClick = { deleting = null; c.delete(s.id) }) { Text(tr("buttons_delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("buttons_cancel")) } })
    }
}

@Composable
private fun SliderCell(s: SliderSpec, c: SlidersController, onExact: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    val r = c.runners[s.id]
    val v = c.values[s.id] ?: s.initial
    var menu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp, top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.displayLabel, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr("sliders_cell_target", s.target, s.field), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(r?.format(v).orEmpty(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                    modifier = Modifier.padding(horizontal = 8.dp).semantics { contentDescription = tr("sliders_set_value") }.clickable { if (r?.setupError == null) onExact() })
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, tr("buttons_options_for", s.displayLabel)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("buttons_menu_edit")) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(tr("sliders_set_value")) }, onClick = { menu = false; onExact() })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_duplicate")) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_move_earlier")) }, onClick = { menu = false; onMove(-1) })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_move_later")) }, onClick = { menu = false; onMove(+1) })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_delete")) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            val color = ButtonSpec.COLORS.getOrElse(s.color) { 0 }
            val colors = if (color != 0) SliderDefaults.colors(thumbColor = Color(color), activeTrackColor = Color(color)) else SliderDefaults.colors()
            val ticks = if (s.step > 0) (s.max - s.min) / s.step else 0.0
            val steps = if (s.step > 0 && Math.abs(ticks - Math.rint(ticks)) < 1e-6 && ticks <= 200) (Math.rint(ticks).toInt() - 1).coerceAtLeast(0) else 0
            Slider(
                value = v.toFloat(), valueRange = s.min.toFloat()..s.max.toFloat(), steps = steps, colors = colors, enabled = r?.setupError == null,
                modifier = Modifier.semantics { contentDescription = s.displayLabel },
                onValueChange = { nv -> if (!dragging) { dragging = true; r?.touchStart() }; c.drag(s.id, nv.toDouble()) },
                onValueChangeFinished = { dragging = false; c.release(s.id) },
            )
            Row(Modifier.padding(end = 12.dp)) {
                Text(r?.format(s.min) ?: s.min.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(r?.format(s.max) ?: s.max.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val err = r?.setupError
            val (line, col) = when (val st = c.states[s.id] ?: SliderState.Idle) {
                is SliderState.Idle -> (if (err != null) "⚠ $err" else "") to StatusColors.error
                is SliderState.Publishing -> "● " + st.text to MaterialTheme.colorScheme.onSurfaceVariant
                is SliderState.Done -> (if (st.ok) "✓ " else "✗ ") + st.text to (if (st.ok) StatusColors.ok else StatusColors.error)
            }
            if (line.isNotEmpty()) Text(line, color = col, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Create or edit a slider: topic, type, field it moves, range, when it publishes, the other fields and the color. */
@Composable
fun SliderEditorDialog(registry: InterfaceRegistry, initial: SliderSpec, isNew: Boolean, onDismiss: () -> Unit, onSave: (SliderSpec) -> Unit) {
    var label by remember { mutableStateOf(initial.label) }
    var target by remember { mutableStateOf(initial.target) }
    var type by remember { mutableStateOf(initial.type) }
    var field by remember { mutableStateOf(initial.field) }
    var min by remember { mutableStateOf(num(initial.min)) }
    var max by remember { mutableStateOf(num(initial.max)) }
    var step by remember { mutableStateOf(num(initial.step)) }
    var init by remember { mutableStateOf(num(initial.initial)) }
    var drag by remember { mutableStateOf(initial.mode == SliderMode.WHILE_DRAGGING) }
    var rate by remember { mutableStateOf(num(initial.rateHz)) }
    var spring by remember { mutableStateOf(initial.springBack) }
    var color by remember { mutableStateOf(initial.color) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var pickingField by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val valuesByType = remember { HashMap<String, JSONObject>().apply { if (initial.type.isNotBlank()) put(initial.type, runCatching { JSONObject(initial.values) }.getOrDefault(JSONObject())) } }
    val payload: MsgSpec? = remember(type) { if (type.isBlank()) null else try { registry.msg(type) } catch (e: IdlException) { error = e.message; null } }
    val form = remember(payload) { MessageFormState(payload, valuesByType[type] ?: JSONObject()) }
    val fields = remember(payload) { payload?.let { SliderMath.numericFields(it) }.orEmpty() }

    fun suggestRange(f: String) {
        if (!(min == "0" && max == "1")) return
        val prim = payload?.let { SliderMath.fieldPrim(it, f) } ?: return
        when {
            prim.isInteger -> { max = "100"; step = "1" }
            f.startsWith("linear.") || f.startsWith("angular.") -> {
                min = if (f.startsWith("linear.")) "-0.5" else "-1"; max = if (f.startsWith("linear.")) "0.5" else "1"
                step = "0.05"; init = "0"; spring = drag
            }
        }
    }
    fun setType(t: String) {
        if (type.isNotBlank() && payload != null) runCatching { valuesByType[type] = form.collect() }
        type = t; error = null
        val fs = runCatching { SliderMath.numericFields(registry.msg(t)) }.getOrDefault(emptyList())
        if (fs.none { it.first == field }) { field = fs.firstOrNull { it.first == "linear.x" }?.first ?: fs.firstOrNull()?.first.orEmpty() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (isNew) "sliders_editor_new_title" else "sliders_editor_edit_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, singleLine = true, label = { Text(tr("sliders_editor_label")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(target, { target = it }, singleLine = true, label = { Text(tr("sliders_editor_topic")) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    if (RosConnection.status !is RosConnection.Status.Connected) { error = tr("buttons_editor_not_connected"); return@OutlinedButton }
                    RosConnection.discover { g ->
                        val items = g?.topics()?.map { it.name to it.type }?.filter { (_, t) -> registry.has(t) && runCatching { SliderMath.numericFields(registry.msg(t)).isNotEmpty() }.getOrDefault(false) }.orEmpty()
                        if (items.isEmpty()) error = tr("buttons_editor_no_topics_found", RosConnection.config.domainId) else browsing = items
                    }
                }) { Text(tr("sliders_editor_browse")) }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text(type.ifBlank { tr("buttons_editor_choose_message_type") }) }
                if (payload != null) OutlinedButton(onClick = { pickingField = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (field.isBlank()) tr("sliders_editor_choose_field") else tr("sliders_editor_field_value", field, SliderMath.fieldPrim(payload, field)?.rosName ?: "?"))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(tr("sliders_editor_range"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(min, { min = it }, singleLine = true, label = { Text(tr("sliders_editor_min")) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(max, { max = it }, singleLine = true, label = { Text(tr("sliders_editor_max")) }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(step, { step = it }, singleLine = true, label = { Text(tr("sliders_editor_step")) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(init, { init = it }, singleLine = true, label = { Text(tr("sliders_editor_initial")) }, modifier = Modifier.weight(1f))
                }
                Text(tr("sliders_editor_step_hint"), style = MaterialTheme.typography.bodySmall)
                Text(tr("sliders_editor_when"), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(drag, { drag = true }); Text(tr("sliders_editor_mode_drag")) }
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!drag, { drag = false }); Text(tr("sliders_editor_mode_release")) }
                if (drag) {
                    OutlinedTextField(rate, { rate = it }, singleLine = true, label = { Text(tr("sliders_editor_rate")) }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(spring, { spring = it }); Text(tr("sliders_editor_spring")) }
                }
                if (payload != null) {
                    Text(tr("sliders_editor_other_fields"), style = MaterialTheme.typography.titleSmall)
                    Text(tr("sliders_editor_other_fields_hint"), style = MaterialTheme.typography.bodySmall)
                    MessageFormView(form)
                }
                Text(tr("sliders_editor_color"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((i, col) in ButtonSpec.COLORS.withIndex()) Box(
                        Modifier.size(32.dp).clip(CircleShape).background(if (col == 0) Color(0xFFBDBDBD) else Color(col))
                            .let { if (i == color) it.border(3.dp, Color(0xFF212121), CircleShape) else it }
                            .semantics { contentDescription = tr("buttons_editor_color_n", i + 1) }.clickable { color = i },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = null
                val t = target.trim()
                if (t.isEmpty()) { error = tr("buttons_editor_name_required"); return@TextButton }
                if (!Regex("/?[A-Za-z0-9_~{}/]+").matches(t)) { error = tr("buttons_editor_invalid_ros_name"); return@TextButton }
                if (type.isBlank()) { error = tr("buttons_editor_choose_a_type"); return@TextButton }
                val spec = payload ?: run { error = tr("buttons_editor_type_not_valid", type); return@TextButton }
                val prim = SliderMath.fieldPrim(spec, field) ?: run { error = tr("sliders_editor_choose_field"); return@TextButton }
                fun n(s: String) = s.replace(',', '.').trim().toDoubleOrNull()
                val mn = n(min); val mx = n(max); val st = n(step); val iv = n(init)
                SliderMath.validate(mn, mx, st, iv)?.let { p ->
                    error = tr(when (p) {
                        SliderMath.Problem.NOT_A_NUMBER -> "sliders_editor_error_number"
                        SliderMath.Problem.MIN_NOT_BELOW_MAX -> "sliders_editor_error_min_max"
                        SliderMath.Problem.BAD_STEP -> "sliders_editor_error_step"
                        SliderMath.Problem.INITIAL_OUT_OF_RANGE -> "sliders_editor_error_initial"
                    }); return@TextButton
                }
                if (prim.isInteger) try {
                    DynCodec.coerceScalar(prim, Math.round(mn!!), field); DynCodec.coerceScalar(prim, Math.round(mx!!), field)
                } catch (e: Exception) { error = tr("sliders_editor_error_range_type", prim.rosName, e.message); return@TextButton }
                val hz = n(rate)
                if (drag && (hz == null || hz < 1 || hz > SliderSpec.MAX_RATE_HZ)) { error = tr("sliders_editor_error_rate", SliderSpec.MAX_RATE_HZ.toInt()); return@TextButton }
                val values = try { form.validate() } catch (e: Exception) { error = tr("buttons_editor_field_error", e.message); return@TextButton }
                onSave(initial.copy(
                    label = label.trim(), target = if (t.startsWith("/")) t else "/$t", type = type, field = field,
                    min = mn!!, max = mx!!, step = st!!, initial = SliderMath.quantize(iv!!, mn, mx, st),
                    mode = if (drag) SliderMode.WHILE_DRAGGING else SliderMode.ON_RELEASE, rateHz = if (drag) hz!! else initial.rateHz,
                    springBack = drag && spring, values = values.toString(), color = color, definitions = Interfaces.customDependencies(type),
                ))
            }) { Text(tr("buttons_editor_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("buttons_cancel")) } },
    )
    if (picking) TypePickerDialog(registry, "msg", onDismiss = { picking = false }) { picking = false; setType(it) }
    if (pickingField) AlertDialog(onDismissRequest = { pickingField = false }, title = { Text(tr("sliders_editor_field")) },
        text = {
            if (fields.isEmpty()) Text(tr("sliders_editor_no_numeric_fields", type))
            else LazyColumn { items(fields) { (f, p) -> Text("$f  (${p.rosName})", modifier = Modifier.fillMaxWidth().clickable { pickingField = false; field = f; suggestRange(f) }.padding(vertical = 8.dp)) } }
        },
        confirmButton = { TextButton(onClick = { pickingField = false }) { Text(tr("buttons_cancel")) } })
    browsing?.let { items ->
        AlertDialog(onDismissRequest = { browsing = null }, title = { Text(tr("buttons_editor_topics_on_network")) },
            text = { LazyColumn { items(items) { (n, t) -> Column(Modifier.fillMaxWidth().clickable { browsing = null; target = n; if (t != type) setType(t) }.padding(vertical = 6.dp)) { Text(n, fontFamily = FontFamily.Monospace); Text(t, style = MaterialTheme.typography.bodySmall) } } } },
            confirmButton = { TextButton(onClick = { browsing = null }) { Text(tr("buttons_cancel")) } })
    }
}

private fun num(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else d.toString()
