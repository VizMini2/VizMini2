// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ui.common.topicDragSource
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.DragIndicator
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgFormat
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.profile
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference

/** Echo of a topic, like `ros2 topic echo` + `hz`: the latest message, the rate and the message count. */
@Composable
fun TopicEchoScreen(topic: String, type: String, modifier: Modifier = Modifier, onOpenNode: ((String) -> Unit)? = null) {
    var endpoints by remember { mutableStateOf<Triple<String, List<String>, List<String>>?>(null) }
    var paused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf(0) }
    var hz by remember { mutableStateOf(0.0) }
    val latest = remember { AtomicReference<Any?>(null) }
    val stamps = remember { ArrayDeque<Long>() }
    var received by remember { mutableStateOf(0) }

    // Bumped when the user defines the missing type: subscribes again.
    var defined by remember { mutableStateOf(0) }
    var missing by remember { mutableStateOf(false) }
    DisposableEffect(topic, type, defined) {
        error = null
        val spec = try { Interfaces.get().msg(type) } catch (e: Exception) { error = tr("explore_topic_type_missing", type); null }
        missing = spec == null
        val sub = spec?.let {
            try {
                // "Latched" ones (/tf_static, /map...), reliable and transient local; the rest, BEST_EFFORT and volatile (matches everything).
                val latched = com.juancams.rviz2mini.bag.BagRecorder.latched(topic)
                DynRos.subscribe(topic, it, if (latched) com.juancams.rviz2mini.bag.BagRecorder.qos(true) else QosPreset.SENSOR.profile()) { v ->
                    latest.set(v)
                    synchronized(stamps) { stamps.addLast(System.nanoTime()); while (stamps.size > 200) stamps.removeFirst() }
                    synchronized(latest) { received++ }
                }
            } catch (e: Exception) { error = tr("explore_could_not_subscribe", e.message ?: e.toString()); null }
        }
        onDispose { sub?.close() }
    }
    // The UI refreshes at 5 Hz, even if hundreds of messages arrive per second.
    LaunchedEffect(topic, type) {
        var n = 0
        while (true) {
            // Who publishes it and who listens (from what is advertised on the network), every 3 s.
            if (n++ % 15 == 0) com.juancams.rviz2mini.ros.RosConnection.graph()?.let { g ->
                val t = g.topics(includeHidden = true).firstOrNull { it.name == topic }
                val nodes = g.nodes()
                endpoints = Triple(tr("explore_endpoints_count", t?.publishers ?: 0, t?.subscribers ?: 0),
                    nodes.filter { topic in it.publishers }.map { it.name }, nodes.filter { topic in it.subscribers }.map { it.name })
            }
            delay(200)
            count = synchronized(latest) { received }
            hz = synchronized(stamps) {
                val now = System.nanoTime()
                while (stamps.isNotEmpty() && now - stamps.first() > 2_000_000_000L) stamps.removeFirst()
                if (stamps.size < 2) 0.0 else (stamps.size - 1) / ((stamps.last() - stamps.first()) / 1e9)
            }
            if (!paused) latest.get()?.let { text = MsgFormat.yaml(it) }
        }
    }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(topic, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.topicDragSource(topic, type))
        Text(type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("explore_topic_rate", hz, count), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { paused = !paused }) { Text(tr(if (paused) "explore_resume" else "explore_pause")) }
        }
        error?.let {
            if (missing) MissingTypeNotice("msg", type, it, Modifier) { defined++ }
            else Text(it, color = MaterialTheme.colorScheme.error)
        }
        endpoints?.let { (count, pubs, subs) ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(tr("explore_endpoints_title"), style = MaterialTheme.typography.titleSmall)
                    Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for ((label, names) in listOf(tr("explore_endpoint_pub") to pubs, tr("explore_endpoint_sub") to subs)) for (name in names) Text(
                        "$label  $name", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().let { m -> if (onOpenNode != null) m.clickable { onOpenNode(name) } else m }.padding(vertical = 5.dp),
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            val t = text
            if (t == null) Text(tr("explore_waiting_messages"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            else {
                // Each numeric field, with a handle to drag it onto a dashboard plot (or into a slot).
                val lines = remember(t) { t.lines() }
                val paths = remember(t) { com.juancams.rviz2mini.explore.YamlPaths.numericPaths(t) }
                val handle = MaterialTheme.colorScheme.primary
                SelectionContainer {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        for ((i, line) in lines.withIndex()) Row(verticalAlignment = Alignment.CenterVertically) {
                            val path = paths.getOrNull(i)
                            if (path != null) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.DragIndicator, tr("explore_drag_field", path), tint = handle,
                                modifier = Modifier.size(16.dp).topicDragSource(topic, type, path))
                            else androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
                            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
