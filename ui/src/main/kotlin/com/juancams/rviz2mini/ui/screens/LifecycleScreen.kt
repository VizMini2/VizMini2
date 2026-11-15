// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.lifecycle.LifecycleModel
import com.juancams.rviz2mini.lifecycle.LifecycleNodeClient
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tab key with the chosen nodes (the same as Android's LifecycleFragment). */
const val LIFECYCLE_KEY_NODES = "lifecycle_nodes"

/** Lifecycle: state of each managed node and its transitions (configure, activate, deactivate...). */
@Composable
fun LifecycleScreen(tabId: String, modifier: Modifier = Modifier, onOpenNode: ((String) -> Unit)? = null) {
    val store = TabStore.get()
    val nodes = remember(tabId) { mutableStateListOf<String>().apply { addAll(LifecycleModel.parseNodes(store.get(tabId)?.settings?.get(LIFECYCLE_KEY_NODES))) } }
    var adding by remember { mutableStateOf(false) }
    // Incremented by "Refresh all": each card queries the state again.
    var refreshAll by remember { mutableStateOf(0) }
    fun save() = store.updateSetting(tabId, LIFECYCLE_KEY_NODES, LifecycleModel.formatNodes(nodes.toList()))

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }) { Text(tr("lifecycle_add_node")) }
            if (nodes.isNotEmpty()) OutlinedButton(onClick = { refreshAll++ }) { Text(tr("lifecycle_refresh_all")) }
        }
        if (nodes.isEmpty()) Text(tr("lifecycle_empty"), modifier = Modifier.padding(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(nodes.toList(), key = { it }) { name ->
                LifecycleNodeCard(name, refreshAll, onOpenNode, onRemove = { nodes.remove(name); save() })
            }
        }
    }
    if (adding) AddLifecycleNodesDialog(nodes.toSet(), onDismiss = { adding = false }) { picked ->
        adding = false
        for (n in picked) if (n !in nodes) nodes.add(n)
        save()
    }
}

@Composable
private fun LifecycleNodeCard(name: String, refreshAll: Int, onOpenNode: ((String) -> Unit)?, onRemove: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LifecycleModel.State?>(null) }
    var transitions by remember { mutableStateOf<List<LifecycleModel.Transition>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var client by remember { mutableStateOf<LifecycleNodeClient?>(null) }
    var confirm by remember { mutableStateOf<LifecycleModel.Transition?>(null) }

    suspend fun refresh() {
        val c = client ?: return
        // The calls run off the main thread; the state is changed on return (on the UI thread).
        val r = withContext(Dispatchers.IO) { runCatching { c.state() to LifecycleModel.sortTransitions(c.availableTransitions()) } }
        r.onSuccess { (s, t) -> state = s; transitions = t; message = null }
            .onFailure { com.juancams.rviz2mini.platform.Log.w("VizMini2", "Lifecycle de $name sin respuesta", it); state = null; transitions = emptyList(); message = tr("lifecycle_unreachable") }
    }
    DisposableEffect(name) {
        val c = try { LifecycleNodeClient(name, Interfaces.get()) { s -> scope.launch { state = s; refresh() } } }
        catch (e: Throwable) { com.juancams.rviz2mini.platform.Log.w("VizMini2", "No se pudo crear el cliente lifecycle de $name", e); message = e.message; null }
        client = c
        onDispose { c?.close() }
    }
    // As on Android: query again every 5 s (in case someone changes the node from outside).
    LaunchedEffect(client, refreshAll) { while (true) { refresh(); kotlinx.coroutines.delay(5000) } }

    fun run(t: LifecycleModel.Transition) {
        val c = client ?: return
        busy = true; message = tr("lifecycle_busy", t.title)
        scope.launch {
            val start = System.currentTimeMillis()
            val ok = withContext(Dispatchers.IO) { runCatching { c.change(t) }.getOrDefault(false) }
            busy = false
            message = if (ok) tr("lifecycle_transition_ok", t.title, System.currentTimeMillis() - start) else tr("lifecycle_transition_rejected", t.title)
            refresh()
        }
    }

    val kind = state?.kind
    val color = when (kind) {
        LifecycleModel.Kind.ACTIVE -> StatusColors.ok
        LifecycleModel.Kind.INACTIVE -> StatusColors.warn
        LifecycleModel.Kind.UNCONFIGURED -> MaterialTheme.colorScheme.primary
        LifecycleModel.Kind.FINALIZED, LifecycleModel.Kind.ERROR -> StatusColors.error
        else -> StatusColors.idle
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                    Text(state?.title ?: (message ?: "…"), color = color, style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, tr("lifecycle_menu_remove")) }
            }
            if (kind == LifecycleModel.Kind.FINALIZED) Text(tr("lifecycle_finalized_hint"), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in transitions) {
                    if (t.isDestructive) OutlinedButton(enabled = !busy, onClick = { confirm = t }) { Text(t.title, color = StatusColors.error) }
                    else FilledTonalButton(enabled = !busy, onClick = { run(t) }) { Text(t.title) }
                }
                OutlinedButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text(tr("lifecycle_menu_refresh")) }
                if (onOpenNode != null) OutlinedButton(onClick = { onOpenNode(name) }) { Text(tr("lifecycle_menu_params")) }
            }
            if (state != null) message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tr("lifecycle_confirm_title", t.title, name)) },
            text = { Text(tr("lifecycle_finalize_warning")) },
            confirmButton = { TextButton(onClick = { confirm = null; run(t) }) { Text(t.title, color = Color(0xFFE53935)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("lifecycle_cancel")) } },
        )
    }
}

/** Choose lifecycle nodes from the network (those with the get_state service) or type one by hand. */
@Composable
private fun AddLifecycleNodesDialog(existing: Set<String>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val found = remember {
        RosConnection.graph()?.nodes().orEmpty().filter { it.isLifecycle && it.name !in existing }.map { it.name }.sorted()
    }
    val picked = remember { mutableStateListOf<String>() }
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("lifecycle_add_nodes_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (found.isEmpty()) Text(tr("lifecycle_all_added"))
                for (n in found) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(n in picked, { if (it) picked.add(n) else picked.remove(n) })
                    Text(n, fontFamily = FontFamily.Monospace)
                }
                OutlinedTextField(typed, { typed = it }, label = { Text(tr("lifecycle_node_name_hint")) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAdd(picked.toList() + listOfNotNull(typed.trim().takeIf { it.isNotEmpty() }?.let { LifecycleModel.normalizeNode(it) }))
            }) { Text(tr("lifecycle_add")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("lifecycle_cancel")) } },
    )
}
