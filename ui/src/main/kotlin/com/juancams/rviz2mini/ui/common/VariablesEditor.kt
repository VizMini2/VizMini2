// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.TopicVariables

/** Topic variables (`${ns}/scan`): list, change, remove and add. Changing one reconnects. */
@Composable
fun VariablesEditor() {
    var version by remember { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val v = version
    val vars = TopicVariables.all()
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("variables_hint"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((k, cur) in vars) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("\${$k}", fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp))
            var edit by remember(k, cur) { mutableStateOf(cur) }
            OutlinedTextField(edit, { edit = it }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = edit != cur, onClick = { TopicVariables.set(k, edit.trim()); version++ }) { Text(tr("common_save")) }
            IconButton(onClick = { TopicVariables.set(k, null); version++ }) { Icon(Icons.Default.Delete, tr("common_delete_named", k)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it.trim() }, singleLine = true, label = { Text(tr("variables_name")) }, isError = name.isNotEmpty() && !TopicVariables.validName(name),
                modifier = Modifier.weight(0.4f))
            OutlinedTextField(value, { value = it }, singleLine = true, label = { Text(tr("variables_value")) }, modifier = Modifier.weight(0.6f))
            OutlinedButton(enabled = TopicVariables.validName(name), onClick = { TopicVariables.set(name, value.trim()); name = ""; value = ""; version++ }) { Text(tr("rviz_action_add")) }
        }
    }
}

/** Card for the PC configuration. */
@Composable
fun VariablesCard(modifier: Modifier = Modifier) {
    Card(modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("variables_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            VariablesEditor()
        }
    }
}

/** Dialog (phone). */
@Composable
fun VariablesDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("variables_title")) }, text = { VariablesEditor() },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("common_close")) } })
}
