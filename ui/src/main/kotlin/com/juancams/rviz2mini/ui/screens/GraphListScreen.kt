// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ui.common.rememberGraph
import com.juancams.rviz2mini.ui.common.topicDragSource
import com.juancams.rviz2mini.ui.common.rememberRosStatus

/** Which list [GraphListScreen] shows: like `ros2 topic|service|action|node list`. */
enum class GraphKind(val filterKey: String, val statusKey: String, val emptyKey: String) {
    TOPICS("explore_filter_topics", "explore_status_topics", "explore_empty_topics"),
    SERVICES("explore_filter_services", "explore_status_services", "explore_empty_services"),
    ACTIONS("explore_filter_actions", "explore_status_actions", "explore_empty_actions"),
    NODES("explore_filter_nodes", "explore_status_nodes", "explore_empty_nodes"),
}

/** A row of the list: name, type and tags (publishers, subscribers, lifecycle...). */
data class GraphRow(val name: String, val detail: String, val badges: List<String>, val type: String = "")

/** ROS graph lists with a filter. `onOpen` receives the tapped row (e.g. to open a topic's echo). */
@Composable
fun GraphListScreen(kind: GraphKind, modifier: Modifier = Modifier, onOpen: (GraphRow) -> Unit = {}) {
    val status = rememberRosStatus()
    val graph = rememberGraph()
    var filter by remember { mutableStateOf("") }
    val rows: List<GraphRow> = graph?.let { g ->
        when (kind) {
            GraphKind.TOPICS -> g.topics().map { GraphRow(it.name, it.types.joinToString(), listOf(tr("explore_badge_pub", it.publishers), tr("explore_badge_sub", it.subscribers)), it.type) }
            GraphKind.SERVICES -> g.services().map { GraphRow(it.name, it.type, if (it.servers == 0) listOf(tr("explore_badge_no_server")) else emptyList(), it.type) }
            GraphKind.ACTIONS -> g.actions().map { GraphRow(it.name, it.type, if (it.servers == 0) listOf(tr("explore_badge_no_server")) else emptyList(), it.type) }
            GraphKind.NODES -> g.nodes().map { n ->
                GraphRow(n.name, tr("explore_node_summary", n.publishers.size, n.subscribers.size, n.services.size),
                    listOfNotNull(if (n.isLifecycle) tr("explore_badge_lifecycle") else null, n.dds))
            }
        }.sortedBy { it.name }
    }.orEmpty()
    val shown = if (filter.isBlank()) rows else rows.filter { it.name.contains(filter.trim(), ignoreCase = true) || it.detail.contains(filter.trim(), ignoreCase = true) }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            label = { Text(tr(kind.filterKey)) }, modifier = Modifier.fillMaxWidth(),
        )
        val connected = status is RosConnection.Status.Connected
        Text(
            when {
                !connected -> tr("explore_not_connected")
                else -> tr(kind.statusKey, rows.size, (status as RosConnection.Status.Connected).domainId)
            },
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        )
        if (kind == GraphKind.TOPICS && connected && rows.isNotEmpty() && DashboardController.visible != null)
            Text(tr("explore_drag_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            connected && rows.isEmpty() -> Text(tr(kind.emptyKey), modifier = Modifier.padding(16.dp))
            connected && shown.isEmpty() -> Text(tr("explore_no_match", filter), modifier = Modifier.padding(16.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.name }) { row ->
                Card(
                    onClick = { onOpen(row) },
                    // Topics can be dragged onto a dashboard widget (or into an empty slot).
                    modifier = Modifier.fillMaxWidth().let { if (kind == GraphKind.TOPICS && row.type.isNotEmpty()) it.topicDragSource(row.name, row.type) else it },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    // Narrow (e.g. a dashboard widget): the tags below, so the name can be read.
                    androidx.compose.foundation.layout.BoxWithConstraints {
                        val narrow = maxWidth < 340.dp
                        val badges = @Composable {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (b in row.badges) AssistChip(onClick = { onOpen(row) }, label = { Text(b, style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                        val texts = @Composable {
                            Text(row.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, maxLines = if (narrow) 2 else 1, overflow = TextOverflow.Ellipsis)
                            Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (narrow) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { texts(); if (row.badges.isNotEmpty()) badges() }
                        else Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { texts() }
                            Spacer(Modifier.padding(4.dp))
                            badges()
                        }
                    }
                }
            }
        }
    }
}
