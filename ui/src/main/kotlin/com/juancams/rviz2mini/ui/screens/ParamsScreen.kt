// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.params.ParamClient
import com.juancams.rviz2mini.params.ParamModel
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.rviz.display.profile
import com.juancams.rviz2mini.ui.common.rememberGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Parameters tab keys (the same as Android's ParamsFragment). */
const val PARAMS_KEY_NODE = "node"
const val PARAMS_KEY_FILTER = "filter"

/**
 * A node's parameters and its client: read, change and listen to /parameter_events (changes by others arrive
 * on their own). Outside Compose so it can be used from tests.
 */
class ParamsController(val node: String) {
    var entries by mutableStateOf<List<ParamClient.Entry>?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    private val client: ParamClient? = runCatching { ParamClient(node, Interfaces.get()) }.getOrNull()
    private val events = runCatching {
        com.juancams.rviz2mini.ros.dyn.DynRos.subscribe("/parameter_events", Interfaces.get().msg("rcl_interfaces/msg/ParameterEvent"),
            com.juancams.rviz2mini.rviz.display.QosPreset.RELIABLE.profile()) { v ->
            if (v["node"]?.toString() != node) return@subscribe
            val changed = ParamModel.eventChanges(v)
            val structural = (v["new_parameters"] as? List<*>).orEmpty().isNotEmpty() || (v["deleted_parameters"] as? List<*>).orEmpty().isNotEmpty()
            com.juancams.rviz2mini.platform.Platform.main.post {
                val cur = entries
                if (structural || cur == null) kotlinx.coroutines.MainScope().launch { load() }
                else if (changed.isNotEmpty()) entries = cur.map { e -> changed[e.descriptor.name]?.let { ParamClient.Entry(e.descriptor, it) } ?: e }
            }
        }
    }.getOrNull()

    suspend fun load() {
        val c = client ?: return
        error = null
        try { entries = withContext(Dispatchers.IO) { c.all() } }
        catch (e: Exception) { entries = null; error = tr("params_read_failed", e.message ?: e.toString()) }
    }

    /** Changes a parameter; returns the error or null. */
    suspend fun apply(name: String, value: ParamModel.Value): String? {
        val c = client ?: return "no client"
        val err = withContext(Dispatchers.IO) { runCatching { c.set(name, value) }.getOrElse { it.message ?: it.toString() } }
        if (err == null) entries = entries?.map { if (it.descriptor.name == name) ParamClient.Entry(it.descriptor, value) else it }
        return err
    }

    fun close() { events?.close(); client?.close() }
}

/** A node's parameters: list with filter and editing, like `ros2 param list/get/set`. */
@Composable
fun ParamsScreen(tabId: String, modifier: Modifier = Modifier, onController: (ParamsController?) -> Unit = {}) {
    val store = TabStore.get()
    var node by remember(tabId) { mutableStateOf(store.get(tabId)?.settings?.get(PARAMS_KEY_NODE)?.takeIf { it.isNotBlank() }) }
    var filter by remember(tabId) { mutableStateOf(store.get(tabId)?.settings?.get(PARAMS_KEY_FILTER).orEmpty()) }
    var editing by remember { mutableStateOf<ParamClient.Entry?>(null) }
    var controller by remember { mutableStateOf<ParamsController?>(null) }
    var pickNode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val graph = rememberGraph(3000)

    DisposableEffect(node) {
        val c = node?.let { ParamsController(it) }
        controller = c; onController(c)
        onDispose { onController(null); c?.close() }
    }
    LaunchedEffect(controller) { controller?.load() }
    val entries = controller?.entries
    val error = controller?.error

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                OutlinedButton(onClick = { pickNode = true }) { Text(node ?: tr("params_choose_node"), fontFamily = if (node != null) FontFamily.Monospace else null) }
                DropdownMenu(expanded = pickNode, onDismissRequest = { pickNode = false }) {
                    for (n in graph?.nodes().orEmpty().map { it.name }.sorted()) DropdownMenuItem(text = { Text(n) }, onClick = {
                        pickNode = false
                        if (n != node) { filter = ""; store.updateSetting(tabId, PARAMS_KEY_FILTER, "") }
                        node = n; store.updateSetting(tabId, PARAMS_KEY_NODE, n)
                    })
                }
            }
            FilledTonalButton(enabled = controller != null, onClick = { scope.launch { controller?.load() } }) { Text(tr("params_refresh")) }
        }
        if (node != null) OutlinedTextField(filter, { filter = it; store.updateSetting(tabId, PARAMS_KEY_FILTER, it) }, singleLine = true,
            label = { Text(tr("params_filter_hint")) }, modifier = Modifier.fillMaxWidth())
        val list = entries
        when {
            node == null -> Text(tr("params_choose_hint"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            error != null -> Text(error, color = MaterialTheme.colorScheme.error)
            list == null -> Text(tr("params_loading"))
            list.isEmpty() -> Text(tr("params_none"))
            else -> Text(tr("params_count", list.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        val shown = list.orEmpty().filter { filter.isBlank() || it.descriptor.name.contains(filter.trim(), ignoreCase = true) }
        if (list != null && list.isNotEmpty() && shown.isEmpty()) Text(tr("params_no_match", filter))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.descriptor.name }) { e -> ParamRow(e) { editing = e } }
        }
    }

    editing?.let { e -> controller?.let { c -> ParamEditDialog(e, c) { editing = null } } }
}

/** A parameter: name, value and type; tapping it edits it. */
@Composable
fun ParamRow(e: ParamClient.Entry, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.descriptor.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Text(e.value.display(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(e.descriptor.type.label + if (e.descriptor.readOnly) " · " + tr("params_read_only_pill") else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Edit a parameter (or view it, if read-only). `onDone`: when closed. */
@Composable
fun ParamEditDialog(e: ParamClient.Entry, controller: ParamsController, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
        var text by remember(e) { mutableStateOf(e.value.display()) }
        var problem by remember(e) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { onDone() },
            title = { Text(e.descriptor.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (e.descriptor.description.isNotBlank()) Text(e.descriptor.description, style = MaterialTheme.typography.bodySmall)
                    if (e.descriptor.readOnly) Text(tr("params_read_only_message", e.value.display()))
                    else {
                        OutlinedTextField(text, { text = it; problem = null }, isError = problem != null, supportingText = problem?.let { p -> { Text(p) } })
                        if (e.descriptor.type.isArray) Text(tr("params_arrays_hint"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (!e.descriptor.readOnly) TextButton(onClick = {
                    val v = try { ParamModel.parse(e.descriptor.type, text) } catch (ex: Exception) { problem = ex.message; return@TextButton }
                    val c = controller
                    scope.launch {
                        val err = c.apply(e.descriptor.name, v)
                        if (err == null) onDone() else problem = tr("params_set_failed", e.descriptor.name) + ": " + err
                    }
                }) { Text(tr("params_set")) }
                else TextButton(onClick = { onDone() }) { Text(tr("params_ok")) }
            },
            dismissButton = { if (!e.descriptor.readOnly) TextButton(onClick = { onDone() }) { Text(tr("params_cancel")) } },
        )
    }
