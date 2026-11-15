// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.FieldSpec
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.Prim
import org.json.JSONArray
import org.json.JSONObject

/**
 * Form generated from the structure of a message (buttons, sliders, calling a service, sending a
 * goal): one row per field, with submessages as indented sections. Arrays of numbers or text are
 * typed comma-separated and arrays of messages as JSON. builtin_interfaces/Time fields have "current time" (it is
 * saved as "now" and filled in when sending). The state lives here; [MessageFormView] draws it.
 */
class MessageFormState(val spec: MsgSpec?, values: JSONObject = JSONObject()) {

    sealed class Row(val depth: Int) {
        class Header(val name: String, val type: String, depth: Int) : Row(depth)
        class Note(val text: String, depth: Int) : Row(depth)
        class Scalar(val path: List<String>, val field: FieldSpec, val text: MutableState<String>, depth: Int) : Row(depth)
        class Bool(val path: List<String>, val name: String, val checked: MutableState<Boolean>, depth: Int) : Row(depth)
        class Array(val path: List<String>, val field: FieldSpec, val text: MutableState<String>, depth: Int) : Row(depth)
        class Time(val path: List<String>, val name: String, val now: MutableState<Boolean>, val sec: MutableState<String>, val nanosec: MutableState<String>, depth: Int) : Row(depth)
    }

    val rows = ArrayList<Row>()

    init {
        if (spec != null) {
            if (spec.fields.isEmpty()) rows += Row.Note(tr("buttons_form_no_fields"), 0)
            else { constants(spec, 0); add(spec, values, emptyList(), 0) }
        }
    }

    private fun constants(s: MsgSpec, depth: Int) {
        if (s.constants.isEmpty()) return
        val text = s.constants.take(12).joinToString(", ") { "${it.name}=${it.value}" } + if (s.constants.size > 12) ", …" else ""
        rows += Row.Note(tr("buttons_form_constants", text), depth)
    }

    private fun add(s: MsgSpec, values: JSONObject?, path: List<String>, depth: Int) {
        for (f in s.fields) {
            val p = path + f.name
            val raw = values?.opt(f.name)?.takeIf { it != JSONObject.NULL }
            val nested = f.nested
            when {
                !f.isArray && nested != null && nested.isTime -> {
                    val o = raw as? JSONObject
                    rows += Row.Time(p, f.name, mutableStateOf(raw is String && raw.equals("now", true)),
                        mutableStateOf(o?.opt("sec")?.toString() ?: "0"), mutableStateOf(o?.opt("nanosec")?.toString() ?: "0"), depth)
                }
                !f.isArray && nested != null -> {
                    rows += Row.Header(f.name, nested.name.substringAfterLast('/'), depth)
                    constants(nested, depth + 1)
                    add(nested, raw as? JSONObject, p, depth + 1)
                }
                f.isArray -> {
                    val text = when {
                        raw is String -> raw
                        f.prim != null -> {
                            val list: List<Any?> = (raw as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it) } }
                                ?: (DynCodec.defaultField(f) as? List<*>).orEmpty()
                            list.joinToString(", ") { if (f.prim!!.isText) "'${it.toString().replace("'", "''")}'" else it.toString() }
                        }
                        raw is JSONArray -> raw.toString()
                        else -> ""
                    }
                    rows += Row.Array(p, f, mutableStateOf(text), depth)
                }
                f.prim == Prim.BOOL -> rows += Row.Bool(p, f.name,
                    mutableStateOf((raw ?: DynCodec.defaultField(f)).let { it == true || it.toString() == "true" }), depth)
                else -> {
                    val prim = f.prim!!
                    val def = DynCodec.defaultField(f)
                    rows += Row.Scalar(p, f, mutableStateOf(raw?.toString() ?: if (prim.isText) def.toString() else DynCodec.scalarText(prim, def)), depth)
                }
            }
        }
    }

    /** JSON with what was typed (not fully validated: [validate] does that). */
    fun collect(): JSONObject {
        val root = JSONObject()
        for (r in rows) {
            val (path, v) = when (r) {
                is Row.Bool -> r.path to r.checked.value
                is Row.Array -> r.path to r.text.value.trim()
                is Row.Scalar -> {
                    val p = r.field.prim!!
                    val t = r.text.value
                    val c = DynCodec.coerceScalar(p, if (p.isText) t else t.trim(), r.path.joinToString("."), r.field.maxLength)
                    r.path to (if (p == Prim.UINT64) (c as Long).toULong().toString() else c)
                }
                is Row.Time -> r.path to (if (r.now.value) "now" else JSONObject()
                    .put("sec", DynCodec.coerceScalar(Prim.INT32, r.sec.value, r.path.joinToString(".") + ".sec"))
                    .put("nanosec", DynCodec.coerceScalar(Prim.UINT32, r.nanosec.value, r.path.joinToString(".") + ".nanosec")))
                else -> continue
            }
            putPath(root, path, v)
        }
        return root
    }

    /** Throws ValueException with the path of the first field that does not fit. */
    fun validate(): JSONObject {
        val json = collect()
        spec?.let { DynCodec.fromJson(it, json, strict = true) }
        return json
    }

    companion object {
        fun putPath(root: JSONObject, path: List<String>, value: Any) {
            var o = root
            for (k in path.dropLast(1)) o = o.optJSONObject(k) ?: JSONObject().also { o.put(k, it) }
            o.put(path.last(), value)
        }
    }
}

/** Draws a [MessageFormState]. */
@Composable
fun MessageFormView(state: MessageFormState, modifier: Modifier = Modifier) {
    val muted = Color(0xFF888888)
    @Composable
    fun label(name: String, type: String) = Text(buildAnnotatedString {
        append(name)
        withStyle(SpanStyle(color = muted, fontSize = 11.sp)) { append("  $type") }
    })
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in state.rows) {
            val pad = Modifier.padding(start = (12 * r.depth).dp)
            when (r) {
                is MessageFormState.Row.Header -> Text("${r.name}  (${r.type})", fontWeight = FontWeight.Bold, modifier = pad.padding(top = 6.dp))
                is MessageFormState.Row.Note -> Text(r.text, style = MaterialTheme.typography.bodySmall, color = muted, modifier = pad)
                is MessageFormState.Row.Bool -> Row(pad, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(r.checked.value, { r.checked.value = it }); Text("${r.name}  (bool)")
                }
                is MessageFormState.Row.Scalar -> Row(pad, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(0.45f)) { label(r.field.name, r.field.typeLabel) }
                    val p = r.field.prim!!
                    OutlinedTextField(r.text.value, { r.text.value = it }, singleLine = true, modifier = Modifier.weight(0.55f),
                        keyboardOptions = KeyboardOptions(keyboardType = when { p.isFloat -> KeyboardType.Decimal; p.isInteger -> KeyboardType.Number; else -> KeyboardType.Text }))
                }
                is MessageFormState.Row.Array -> Column(pad) {
                    label(r.field.name, r.field.typeLabel)
                    OutlinedTextField(r.text.value, { r.text.value = it }, modifier = Modifier.fillMaxWidth(),
                        textStyle = if (r.field.prim == null) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                        placeholder = { Text(if (r.field.prim != null) tr("buttons_form_csv_hint") else tr("buttons_form_json_hint", r.field.nested?.fields?.firstOrNull()?.name ?: "x")) })
                }
                is MessageFormState.Row.Time -> Column(pad) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(r.now.value, { r.now.value = it }); Text(tr("buttons_form_current_time", r.name)) }
                    if (!r.now.value) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(0.45f)) { label("sec", "int32") }
                            OutlinedTextField(r.sec.value, { r.sec.value = it }, singleLine = true, modifier = Modifier.weight(0.55f))
                        }
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(0.45f)) { label("nanosec", "uint32") }
                            OutlinedTextField(r.nanosec.value, { r.nanosec.value = it }, singleLine = true, modifier = Modifier.weight(0.55f))
                        }
                    }
                }
            }
        }
    }
}
