// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.explore.MessageTree
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.indicators.IndicatorMath
import com.juancams.rviz2mini.platform.SystemClock
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.profile
import com.juancams.rviz2mini.tabs.TabStore
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Keys of the Message tab (the same as before on Android). */
const val MESSAGE_KEY_TOPIC = "topic"
const val MESSAGE_KEY_TYPE = "type"
const val MESSAGE_KEY_PATH = "path"

/**
 * The latest message of a topic and its tree (see [MessageTree]), outside Compose for the tests. The subscription
 * runs on the ROS thread; [tick] (at 5 Hz, from the screen) passes what is new to the UI.
 */
class MessageController(val topic: String, typeName: String) {
    /** Without a type (a tab imported from a Foxglove layout): the one the graph says. */
    val type: String = typeName.ifBlank { RosConnection.graph()?.topics(includeHidden = true)?.firstOrNull { it.name == topic }?.types?.firstOrNull().orEmpty() }
    var lines by mutableStateOf<List<MessageTree.Line>>(emptyList()); private set
    var info by mutableStateOf(""); private set
    var paused by mutableStateOf(false)
    var path by mutableStateOf("")
    var expanded by mutableStateOf(setOf<String>())
    var collapsed by mutableStateOf(setOf<String>())
    @Volatile var received = 0L; private set
    val spec: MsgSpec? = runCatching { Interfaces.get().msg(type) }.getOrNull()
    private val latest = AtomicReference<Pair<MsgValue, Long>?>(null)
    private val arrivals = ArrayDeque<Long>()
    private var shownAt = -1L
    private var shownKey: Any? = null
    private var error: String? = if (spec == null) tr("explore_topic_type_missing", type) else null
    private val sub = spec?.let { s ->
        runCatching {
            DynRos.subscribe(DynRos.normalizeName(topic), s, QosPreset.SENSOR.profile()) { v ->
                received++
                if (!paused) latest.set(v to SystemClock.uptimeMillis())
            }
        }.onFailure { error = it.message ?: it.javaClass.simpleName }.getOrNull()
    }

    /** The displayed message (or null if it has not arrived yet). */
    fun current(): MsgValue? = latest.get()?.first

    fun tick() {
        val now = SystemClock.uptimeMillis()
        val l = latest.get()
        if (l != null && l.second != shownAt) { arrivals.addLast(l.second); while (arrivals.size > 20) arrivals.removeFirst() }
        info = when {
            error != null -> error!!
            l == null -> tr("message_waiting", topic, type)
            else -> {
                val hz = if (arrivals.size >= 2) (arrivals.size - 1) * 1000.0 / (arrivals.last() - arrivals.first()).coerceAtLeast(1) else 0.0
                tr("message_info", received, String.format(Locale.ROOT, "%.1f", hz), String.format(Locale.ROOT, "%.1f", (now - l.second) / 1000.0))
            }
        }
        val s = spec ?: return
        val key = listOf(path, expanded, collapsed)
        if (l == null || paused || (l.second == shownAt && key == shownKey)) return
        shownAt = l.second; shownKey = key
        lines = MessageTree.build(s, l.first, path, expanded, collapsed)
    }

    fun toggle(line: MessageTree.Line) {
        val p = line.toggle ?: return
        if (line.openByDefault) collapsed = if (line.open) collapsed + p else collapsed - p
        else expanded = if (line.open) expanded - p else expanded + p
        latest.get()?.let { (m, _) -> spec?.let { lines = MessageTree.build(it, m, path, expanded, collapsed) } }
    }

    /** The message (or the part at the path) as JSON, to copy it. */
    fun json(): String? {
        val s = spec ?: return null
        val m = current() ?: return null
        val p = path.trim()
        val v: Any? = if (p.isEmpty()) m else IndicatorMath.valueAt(m, p)
        val nested = if (p.isEmpty()) s else IndicatorMath.resolve(s, p)?.nested
        @Suppress("UNCHECKED_CAST")
        return if (v is Map<*, *> && nested != null) DynCodec.toJson(nested, v as Map<String, Any?>).toString(2) else v.toString()
    }

    fun close() { sub?.close() }
}

/** Message tab: the latest message of a topic as an expandable tree, with path, pause and copy as JSON. */
@Composable
fun MessageScreen(tabId: String, modifier: Modifier = Modifier, onController: (MessageController?) -> Unit = {}, onCopied: () -> Unit = {}) {
    val store = TabStore.get()
    var topic by remember(tabId) { mutableStateOf(store.get(tabId)?.settings?.get(MESSAGE_KEY_TOPIC)?.takeIf { it.isNotBlank() }) }
    var type by remember(tabId) { mutableStateOf(store.get(tabId)?.settings?.get(MESSAGE_KEY_TYPE).orEmpty()) }
    var path by remember(tabId) { mutableStateOf(store.get(tabId)?.settings?.get(MESSAGE_KEY_PATH).orEmpty()) }
    var controller by remember { mutableStateOf<MessageController?>(null) }
    var picking by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // Bumped when the user defines the missing type: the controller subscribes again.
    var defined by remember { mutableStateOf(0) }
    DisposableEffect(topic, type, defined) {
        val c = topic?.let { MessageController(it, type).also { m -> m.path = path } }
        controller = c; onController(c)
        onDispose { onController(null); c?.close() }
    }
    LaunchedEffect(controller) { while (true) { controller?.tick(); delay(200) } }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
                Text(topic?.let { "$it  ·  ${type.substringAfterLast('/')}" } ?: tr("message_pick_topic"), maxLines = 1)
            }
            val c = controller
            IconButton(enabled = c != null, onClick = { c?.paused = !(c?.paused ?: false) }) {
                if (c?.paused == true) Icon(Icons.Default.PlayArrow, tr("message_resume")) else Icon(Icons.Default.Pause, tr("message_pause"))
            }
            IconButton(enabled = c != null, onClick = { c?.json()?.let { clipboard.setText(AnnotatedString(it)); onCopied() } }) {
                Icon(Icons.Default.ContentCopy, tr("message_copy"))
            }
        }
        OutlinedTextField(path, { path = it; controller?.path = it.trim(); store.updateSetting(tabId, MESSAGE_KEY_PATH, it.trim()) },
            singleLine = true, label = { Text(tr("message_path_hint")) }, modifier = Modifier.fillMaxWidth())
        val c = controller
        if (c != null && c.spec == null && c.type.isNotBlank()) MissingTypeNotice("msg", c.type, tr("explore_topic_type_missing", c.type), Modifier) { defined++ }
        else Text(if (topic == null) tr("message_pick_topic") else c?.info.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        val lines = controller?.lines.orEmpty()
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(lines) { _, l ->
                    Text(
                        MessageTree.text(l), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        fontWeight = if (l.toggle != null) FontWeight.Bold else FontWeight.Normal,
                        color = if (l.dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                            .let { m -> if (l.toggle != null) m.clickable { controller?.toggle(l) } else m }
                            .padding(start = (14 * l.depth + if (l.toggle != null) 4 else 18).dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
                    )
                }
            }
        }
    }

    if (picking) TopicPickerDialog(topic.orEmpty(), type, onDismiss = { picking = false }) { name, t ->
        picking = false
        // A type the app does not bring is kept too: the screen then offers to define it.
        topic = name; type = t
        store.updateSetting(tabId, MESSAGE_KEY_TOPIC, name); store.updateSetting(tabId, MESSAGE_KEY_TYPE, t)
    }
}

/** Pick a topic from the network (with its type) or type one by hand (the previous type is kept). */
@Composable
fun TopicPickerDialog(current: String, currentType: String, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val found = remember { RosConnection.graph()?.topics()?.map { it.name to it.type }?.sortedBy { it.first }.orEmpty() }
    var name by remember { mutableStateOf(current) }
    var chosenType by remember { mutableStateOf(currentType) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("message_pick_topic")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("/topic") }, modifier = Modifier.fillMaxWidth())
                for ((n, t) in found) TextButton(onClick = { name = n; chosenType = t }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(n, fontFamily = FontFamily.Monospace)
                        Text(t, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && chosenType.isNotBlank(), onClick = { onPick(name.trim(), chosenType) }) { Text(tr("buttons_editor_save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("buttons_cancel")) } },
    )
}
