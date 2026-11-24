// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.dyn.IdlException
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.Interfaces

/**
 * Define a type the app does not bring: its name and the text of its .msg, .srv or .action file (what
 * `ros2 interface show` prints). It can use the types the app already knows. Saved with [Interfaces.saveCustom], so it
 * stays for the next time and travels with the buttons, sliders and indicators that use it.
 *
 * `kind`: "msg", "srv" or "action". `initialName`: the type to define (the one the robot uses), or one of the user's
 * own to edit it. `onSaved` gets the full name ("pkg/msg/Name").
 */
@Composable
fun CustomTypeDialog(kind: String, initialName: String = "", onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val own = remember(initialName) { InterfaceRegistry.normalize(initialName, kind)?.let { Interfaces.custom()[it] } }
    var name by remember { mutableStateOf(initialName) }
    var text by remember { mutableStateOf(own?.trimEnd().orEmpty()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var textError by remember { mutableStateOf<String?>(null) }
    val example = when (kind) {
        "srv" -> "my_robot/srv/SetLight" to "uint8 color\nfloat32 brightness\n---\nbool ok\nstring message"
        "action" -> "my_robot/action/Turn" to "float32 angle\n---\nbool ok\n---\nfloat32 remaining"
        else -> "my_robot/msg/Light" to "uint8 RED=1\nuint8 color\nfloat32 brightness"
    }
    fun save() {
        nameError = null; textError = null
        val full = InterfaceRegistry.normalize(name.trim(), kind)
        if (full == null || full.split('/')[1] != kind) { nameError = tr("buttons_editor_custom_name_error", kind); return }
        if (text.isBlank()) { textError = tr("custom_type_empty"); return }
        try { onSaved(Interfaces.saveCustom(full, text)) } catch (e: IdlException) { textError = e.message ?: e.toString() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("buttons_editor_custom_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("buttons_editor_custom_help", kind), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("custom_type_name")) },
                    placeholder = { Text(example.first) }, isError = nameError != null, supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, minLines = 6, label = { Text(tr("custom_type_definition", kind)) },
                    placeholder = { Text(example.second, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    isError = textError != null, supportingText = textError?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { save() }) { Text(tr("buttons_editor_save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("buttons_cancel")) } },
    )
}

/**
 * What a screen shows instead of its form when the type is not in the app: the message and a button to define it
 * there and then. `onDefined` runs once it is saved (the screen then builds its form).
 */
@Composable
fun MissingTypeNotice(kind: String, type: String, message: String, modifier: Modifier = Modifier, onDefined: () -> Unit) {
    var defining by remember { mutableStateOf(false) }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message)
        OutlinedButton(onClick = { defining = true }) { Text(tr("custom_type_define")) }
    }
    if (defining) CustomTypeDialog(kind, type, onDismiss = { defining = false }) { defining = false; onDefined() }
}
