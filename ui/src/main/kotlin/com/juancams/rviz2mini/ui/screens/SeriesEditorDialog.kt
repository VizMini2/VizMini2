// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.juancams.rviz2mini.diag.MessageCatalog
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.indicators.IndicatorMath
import com.juancams.rviz2mini.plot.BagPlotSource
import com.juancams.rviz2mini.plot.Formula
import com.juancams.rviz2mini.plot.FormulaException
import com.juancams.rviz2mini.plot.JsonStream
import com.juancams.rviz2mini.plot.LuaScript
import com.juancams.rviz2mini.plot.PlotSpecs
import com.juancams.rviz2mini.plot.ScriptException
import com.juancams.rviz2mini.plot.SeriesSpec
import com.juancams.rviz2mini.plot.SeriesTransform
import com.juancams.rviz2mini.ros.dyn.IdlException
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec

private val STREAM_EXAMPLES = listOf("udp://0.0.0.0:9870", "mqtt://192.168.1.10:1883/robot/#", "zmq://192.168.1.10:9872")

/** A text field with suggestions that drop down when typing or with the arrow. */
@Composable
private fun SuggestField(value: String, onChange: (String) -> Unit, label: String, options: List<String>, error: String?, onPicked: (String) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value, { onChange(it); open = true }, singleLine = true, label = { Text(label) }, isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = { IconButton(onClick = { open = !open }) { Icon(Icons.Default.ArrowDropDown, null) } },
            modifier = Modifier.fillMaxWidth(),
        )
        val shown = options.filter { value.isBlank() || it.contains(value.trim(), ignoreCase = true) }.take(40)
        DropdownMenu(open && shown.isNotEmpty() && !(shown.size == 1 && shown[0] == value), { open = false }, properties = PopupProperties(focusable = false)) {
            for (o in shown) DropdownMenuItem({ Text(o) }, { onChange(o); onPicked(o); open = false })
        }
    }
}

private enum class SeriesMode { TOPIC, FORMULA, SCRIPT }

/**
 * Series dialog. `topics` are the suggested (topic, type) pairs: the network's or, with a bag
 * open, the bag's. `others` are the tab's other series (for formulas). `allowText`:
 * state panel (text fields are also valid). `fieldHints`: fields when there is no ROS type (CSV columns).
 */
@Composable
fun SeriesEditorDialog(
    registry: InterfaceRegistry, initial: SeriesSpec, isNew: Boolean, topics: List<Pair<String, String>>, others: List<SeriesSpec>,
    allowText: Boolean, fieldHints: List<String>, onDismiss: () -> Unit, onSave: (SeriesSpec) -> Unit,
) {
    fun fmt(d: Double) = if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    var mode by remember { mutableStateOf(when { initial.isScript -> SeriesMode.SCRIPT; initial.isFormula -> SeriesMode.FORMULA; else -> SeriesMode.TOPIC }) }
    var topic by remember { mutableStateOf(initial.topic) }
    var type by remember { mutableStateOf(initial.type) }
    var field by remember { mutableStateOf(initial.field) }
    var label by remember { mutableStateOf(initial.label) }
    var color by remember { mutableStateOf(initial.color) }
    var transform by remember { mutableStateOf(initial.transform) }
    var window by remember { mutableStateOf(initial.window.toString()) }
    var scale by remember { mutableStateOf(fmt(initial.scale)) }
    var offset by remember { mutableStateOf(fmt(initial.offset)) }
    var formula by remember { mutableStateOf(initial.formula) }
    var inputs by remember { mutableStateOf(initial.inputList.joinToString(", ")) }
    var globals by remember { mutableStateOf(initial.scriptGlobals) }
    var body by remember { mutableStateOf(initial.script.ifBlank { "return value" }) }
    var picking by remember { mutableStateOf(false) }
    var defining by remember { mutableStateOf<String?>(null) }
    // Per-field errors and the general one.
    var error by remember { mutableStateOf<String?>(null) }
    var topicError by remember { mutableStateOf<String?>(null) }
    var fieldError by remember { mutableStateOf<String?>(null) }
    var formulaError by remember { mutableStateOf<String?>(null) }
    var inputsError by remember { mutableStateOf<String?>(null) }
    var bodyError by remember { mutableStateOf<String?>(null) }
    var windowError by remember { mutableStateOf<String?>(null) }
    var scaleError by remember { mutableStateOf<String?>(null) }
    var offsetError by remember { mutableStateOf<String?>(null) }

    val spec: MsgSpec? = remember(type) { if (type.isBlank()) null else try { registry.msg(type) } catch (e: IdlException) { error = e.message ?: tr("buttons_editor_invalid_type"); null } }
    val untyped = topic.trim().let { JsonStream.isStream(it) || it == BagPlotSource.CSV_TOPIC }
    val fieldOptions = if (untyped) fieldHints else spec?.let { s -> IndicatorMath.leaves(s).filter { allowText || it.prim?.isText != true }.map { it.path } }.orEmpty()
    val usable = others.filter { it.displayLabel.isNotBlank() }

    fun setType(t: String) {
        type = t; error = null
        val s = try { registry.msg(t) } catch (e: IdlException) { null } ?: return
        if (IndicatorMath.resolve(s, field.trim()) == null) {
            val leaves = IndicatorMath.leaves(s).filter { it.prim.let { p -> p != null && (p.isFloat || p.isInteger) } }
            field = (leaves.firstOrNull { it.path == "data" } ?: leaves.firstOrNull { !it.path.startsWith("header") } ?: leaves.firstOrNull())?.path.orEmpty()
        }
    }

    /** Transformation, window, scale and offset (common to all modes); null if something is invalid. */
    fun common(): Triple<Int, Double, Double>? {
        windowError = null; scaleError = null; offsetError = null
        fun num(s: String) = s.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        val w = window.trim().toIntOrNull()?.takeIf { it in 2..1000 }
        if (transform == SeriesTransform.MOVING_AVERAGE && w == null) { windowError = tr("plot_editor_error_window"); return null }
        val sc = num(scale) ?: run { scaleError = tr("sliders_editor_error_number"); return null }
        val of = num(offset) ?: run { offsetError = tr("sliders_editor_error_number"); return null }
        return Triple(w ?: initial.window, sc, of)
    }

    fun build(): SeriesSpec? {
        error = null; topicError = null; fieldError = null; formulaError = null; inputsError = null; bodyError = null
        val lbl = label.trim()
        when (mode) {
            SeriesMode.FORMULA -> {
                val text = formula.trim()
                val f = try { Formula.parse(text) } catch (e: FormulaException) { formulaError = e.message; return null }
                val names = others.map { it.displayLabel }.toSet()
                f.refs.firstOrNull { it !in names || it == lbl }?.let { formulaError = tr("plot_editor_error_formula_ref", it); return null }
                val (w, sc, of) = common() ?: return null
                return initial.copy(topic = "", type = "", field = "", formula = text, script = "", scriptGlobals = "", inputs = "", label = lbl, color = color,
                    transform = transform, window = w, scale = sc, offset = of)
            }
            SeriesMode.SCRIPT -> {
                val names = inputs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val known = others.map { it.displayLabel }.toSet()
                if (names.isEmpty()) { inputsError = tr("plot_editor_script_no_inputs"); return null }
                names.firstOrNull { it !in known || it == lbl }?.let { inputsError = tr("plot_editor_error_formula_ref", it); return null }
                try { LuaScript(globals, body, names.size) } catch (e: ScriptException) { bodyError = e.message; return null }
                val (w, sc, of) = common() ?: return null
                return initial.copy(topic = "", type = "", field = "", formula = "", script = body, scriptGlobals = globals, inputs = names.joinToString("\n"),
                    label = lbl, color = color, transform = transform, window = w, scale = sc, offset = of)
            }
            SeriesMode.TOPIC -> {
                val t = topic.trim(); val fld = field.trim()
                if (untyped) {
                    // JSON or CSV stream: the field is a path (or a column) not checked against a type.
                    if (JsonStream.isStream(t) && JsonStream.create(t) {} == null) { topicError = tr("plot_editor_bad_stream"); return null }
                    if (fld.isEmpty()) { fieldError = tr("plot_editor_error_field", fld, t); return null }
                    val (w, sc, of) = common() ?: return null
                    return initial.copy(topic = t, type = if (t == BagPlotSource.CSV_TOPIC) "csv" else "json", field = fld, formula = "", script = "", inputs = "",
                        label = lbl, color = color, transform = transform, window = w, scale = sc, offset = of)
                }
                if (t.isEmpty() || !Regex("/?[A-Za-z0-9_~{}/]+").matches(t)) { topicError = tr("buttons_editor_invalid_ros_name"); return null }
                if (type.isBlank()) { error = tr("buttons_editor_choose_a_type"); return null }
                val s = spec ?: run { error = tr("buttons_editor_type_not_valid", type); return null }
                val f = IndicatorMath.resolve(s, fld)
                if (f == null || (f.nested?.isTime == false) || (f.prim?.isText == true && !allowText)) { fieldError = tr("plot_editor_error_field", fld, type); return null }
                val (w, sc, of) = common() ?: return null
                return initial.copy(topic = if (t.startsWith("/")) t else "/$t", type = type, field = fld, label = lbl, color = color,
                    transform = transform, window = w, scale = sc, offset = of, formula = "", script = "", inputs = "")
            }
        }
    }

    @Composable
    fun hint(key: String) = Text(tr(key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    @Composable
    fun radio(selected: Boolean, text: String, onClick: () -> Unit) =
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onClick)) { RadioButton(selected, onClick); Text(text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (isNew) "plot_editor_new_title" else "plot_editor_edit_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    radio(mode == SeriesMode.TOPIC, tr("plot_editor_source_topic")) { mode = SeriesMode.TOPIC }
                    radio(mode == SeriesMode.FORMULA, tr("plot_editor_source_formula")) { mode = SeriesMode.FORMULA }
                    radio(mode == SeriesMode.SCRIPT, tr("plot_editor_source_script")) { mode = SeriesMode.SCRIPT }
                }
                when (mode) {
                    SeriesMode.TOPIC -> {
                        SuggestField(topic, { topic = it }, tr("sliders_editor_topic"),
                            (topics.map { it.first } + MessageCatalog.COMMON_TOPICS.map { it.first } + STREAM_EXAMPLES).distinct(), topicError,
                            onPicked = { picked ->
                                topics.firstOrNull { it.first == picked }?.second?.takeIf { it != type }?.let { t ->
                                    if (registry.has(t)) setType(t) else { error = tr("buttons_editor_not_bundled", t); defining = t }
                                }
                            })
                        if (!untyped) OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text(type.ifBlank { tr("buttons_editor_choose_message_type") }) }
                        SuggestField(field, { field = it }, tr("indicators_editor_field"), fieldOptions, fieldError)
                        hint("plot_editor_field_hint")
                    }
                    SeriesMode.FORMULA -> {
                        OutlinedTextField(formula, { formula = it }, singleLine = true, label = { Text(tr("plot_editor_formula")) }, isError = formulaError != null,
                            supportingText = formulaError?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
                        hint(if (usable.isEmpty()) "plot_editor_formula_no_series" else "plot_editor_insert_series")
                        for (s in usable) TextButton(onClick = { formula += "{${s.displayLabel}}" }) { Text("{${s.displayLabel}}") }
                        hint("plot_editor_formula_hint")
                    }
                    SeriesMode.SCRIPT -> {
                        OutlinedTextField(inputs, { inputs = it }, singleLine = true, label = { Text(tr("plot_editor_script_inputs")) }, isError = inputsError != null,
                            supportingText = inputsError?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
                        if (usable.isEmpty()) hint("plot_editor_formula_no_series")
                        for (s in usable) TextButton(onClick = { val cur = inputs.trim(); inputs = if (cur.isEmpty()) s.displayLabel else "$cur, ${s.displayLabel}" }) { Text(s.displayLabel) }
                        OutlinedTextField(globals, { globals = it }, minLines = 3, label = { Text(tr("plot_editor_script_globals")) },
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(body, { body = it }, minLines = 5, label = { Text(tr("plot_editor_script_body")) }, isError = bodyError != null,
                            supportingText = bodyError?.let { { Text(it) } }, textStyle = TextStyle(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                        hint("plot_editor_script_hint")
                    }
                }
                OutlinedTextField(label, { label = it }, singleLine = true, label = { Text(tr("plot_editor_label")) }, modifier = Modifier.fillMaxWidth())
                Text(tr("sliders_editor_color"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in -1 until PlotSpecs.PALETTE.size) Box(
                        Modifier.size(28.dp).clip(CircleShape).background(if (i < 0) Color(0xFFBDBDBD) else Color(PlotSpecs.PALETTE[i]))
                            .let { if (i == color) it.border(3.dp, Color(0xFF212121), CircleShape) else it }
                            .semantics { contentDescription = if (i < 0) tr("plot_editor_color_auto") else tr("buttons_editor_color_n", i + 1) }
                            .clickable { color = i },
                    )
                }
                Text(tr("plot_editor_transform"), style = MaterialTheme.typography.titleSmall)
                for (t in SeriesTransform.entries) radio(transform == t, tr(when (t) {
                    SeriesTransform.NONE -> "plot_transform_none"; SeriesTransform.DERIVATIVE -> "plot_transform_derivative"
                    SeriesTransform.MOVING_AVERAGE -> "plot_transform_average"; SeriesTransform.INTEGRAL -> "plot_transform_integral"
                })) { transform = t }
                if (transform == SeriesTransform.MOVING_AVERAGE) OutlinedTextField(window, { window = it }, singleLine = true, label = { Text(tr("plot_editor_window")) },
                    isError = windowError != null, supportingText = windowError?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(scale, { scale = it }, singleLine = true, label = { Text(tr("plot_editor_scale")) }, isError = scaleError != null,
                        supportingText = scaleError?.let { { Text(it) } }, modifier = Modifier.weight(1f))
                    OutlinedTextField(offset, { offset = it }, singleLine = true, label = { Text(tr("plot_editor_offset")) }, isError = offsetError != null,
                        supportingText = offsetError?.let { { Text(it) } }, modifier = Modifier.weight(1f))
                }
                hint("plot_editor_scale_hint")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { build()?.let(onSave) }) { Text(tr("buttons_editor_save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("buttons_cancel")) } },
    )
    if (picking) TypePickerDialog(registry, "msg", onDismiss = { picking = false }) { t -> picking = false; setType(t) }
    defining?.let { t -> CustomTypeDialog("msg", t, onDismiss = { defining = null }) { defining = null; error = null; setType(it) } }
}
