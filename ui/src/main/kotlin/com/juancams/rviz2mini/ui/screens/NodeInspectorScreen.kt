// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.params.ParamClient
import com.juancams.rviz2mini.ui.common.rememberGraph

/**
 * Node inspector (like `ros2 node info` + `ros2 param`): its parameters, which can be edited, and what it
 * publishes, what it subscribes to and the services it offers. `onOpenTopic(name, type)`: tapping a topic.
 */
@Composable
fun NodeInspectorScreen(node: String, modifier: Modifier = Modifier, onOpenTopic: ((String, String) -> Unit)? = null) {
    val c = remember(node) { ParamsController(node) }
    DisposableEffect(c) { onDispose { c.close() } }
    LaunchedEffect(c) { c.load() }
    var editing by remember { mutableStateOf<ParamClient.Entry?>(null) }
    val g = rememberGraph(3000)
    val n = g?.nodes()?.firstOrNull { it.name == node }
    val topics = g?.topics(includeHidden = true).orEmpty().associateBy { it.name }
    val services = g?.services(includeHidden = true).orEmpty().associateBy { it.name }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(Modifier.padding(bottom = 6.dp)) {
                Text(node.substringAfterLast('/'), style = MaterialTheme.typography.titleLarge)
                // The name, whether it is lifecycle and the node's DDS (Fast DDS, Cyclone DDS...).
                val base = if (n?.isLifecycle == true) tr("params_lifecycle_subtitle", node) else node
                Text(n?.dds?.let { "$base · $it" } ?: base, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val list = c.entries
        item {
            Text(when {
                c.error != null -> c.error!!
                list == null -> tr("params_loading")
                list.isEmpty() -> tr("params_none")
                else -> tr("params_count", list.size)
            }, style = MaterialTheme.typography.labelLarge, color = if (c.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        items(list.orEmpty(), key = { "p:" + it.descriptor.name }) { e -> ParamRow(e) { editing = e } }
        if (g != null && n == null) item { Text(tr("params_no_info"), style = MaterialTheme.typography.bodySmall) }
        if (n != null) for ((title, items, empty) in listOf(
            Triple(tr("params_publishers"), n.publishers, tr("params_publishes_nothing")),
            Triple(tr("params_subscribers"), n.subscribers, tr("params_subscribes_nothing")),
            Triple(tr("params_services"), n.services, tr("params_no_services")),
        )) item(key = "s:$title") {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(tr("params_section_title", title, items.size), style = MaterialTheme.typography.titleSmall)
                    if (items.isEmpty()) Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (t in items) {
                        val topicType = topics[t]?.type
                        val type = topicType ?: services[t]?.type
                        Text(if (type != null) "$t\n$type" else t, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().let { m -> if (onOpenTopic != null && topicType != null) m.clickable { onOpenTopic(t, topicType) } else m }
                                .padding(vertical = 6.dp))
                    }
                }
            }
        }
        item { Column(Modifier.padding(bottom = 16.dp)) {} }
    }
    editing?.let { e -> ParamEditDialog(e, c) { editing = null } }
}
