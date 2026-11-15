// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.lifecycle.LifecycleModel
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType

/**
 * The tab catalog (the same as Android's add screen): groups, summary, "what it does" points,
 * screenshot and video of each type. The texts are Android's (tab_catalog_<type>_summary/detailN); the images and
 * videos (shot_<type>.webp, clip_<type>.mp4) are provided by each platform with [CatalogMedia].
 */
object TabCatalogInfo {
    enum class Group(val labelKey: String) {
        VISUALIZE("tab_catalog_group_visualize"), CONTROL("tab_catalog_group_control"),
        INSPECT("tab_catalog_group_inspect"), RECORD("tab_catalog_group_record")
    }

    private val groups = mapOf(
        TabType.DASHBOARD to Group.VISUALIZE, TabType.RVIZ to Group.VISUALIZE, TabType.CAMERA to Group.VISUALIZE,
        TabType.TELEOP to Group.CONTROL, TabType.TELEOP_DUAL to Group.CONTROL, TabType.BUTTONS to Group.CONTROL,
        TabType.SLIDERS to Group.CONTROL, TabType.INDICATORS to Group.VISUALIZE, TabType.PLOT to Group.VISUALIZE,
        TabType.GPS to Group.VISUALIZE, TabType.BEHAVIOR_TREE to Group.VISUALIZE, TabType.MESSAGE to Group.INSPECT,
        TabType.PARAMETERS to Group.CONTROL, TabType.DIAGNOSTICS to Group.INSPECT, TabType.LIFECYCLE to Group.CONTROL,
        TabType.NODE_GRAPH to Group.INSPECT, TabType.TF_TREE to Group.INSPECT, TabType.NODES to Group.INSPECT,
        TabType.TOPICS to Group.INSPECT, TabType.SERVICES to Group.INSPECT, TabType.ACTIONS to Group.INSPECT,
        TabType.MOVEIT to Group.CONTROL, TabType.CONTROLLERS to Group.CONTROL, TabType.MAPS to Group.CONTROL,
        TabType.ALERTS to Group.INSPECT, TabType.BAGS to Group.RECORD,
    )

    /** In Android's order. */
    val order: List<TabType> = groups.keys.toList()

    fun group(t: TabType) = groups[t] ?: Group.INSPECT
    /** "rviz", "teleop_dual", "behavior_tree"...: the name of its texts, screenshot and video. */
    fun key(t: TabType) = t.name.lowercase()
    fun summary(t: TabType) = tr("tab_catalog_${key(t)}_summary")
    fun details(t: TabType): List<String> = (1..8).mapNotNull { i -> Platform.strings.get("tab_catalog_${key(t)}_detail$i") }
}

/** The catalog screenshots and videos on each platform. */
object CatalogMedia {
    /** The shot_<type> screenshot (null: no screenshot). */
    @Volatile var image: (String) -> ImageBitmap? = { null }
    /** Opens the clip_<type> video (null: the platform cannot; on Android the screen itself plays it). */
    @Volatile var playVideo: ((String) -> Unit)? = null
}

/**
 * Add a tab (or a dashboard widget) as on Android: the list by groups and, on picking one, its card (what it
 * does, video and the initial settings). `allowed`: the types offered on this platform.
 */
@Composable
fun AddTabDialog(allowed: Collection<TabType>, forWidget: Boolean = false, onDismiss: () -> Unit,
                 /** A template: the dashboard and its widgets (null = no templates, e.g. when adding a widget). */
                 onAddAll: ((List<TabSpec>) -> Unit)? = null, onAdd: (TabSpec) -> Unit) {
    var chosen by remember { mutableStateOf<TabType?>(null) }
    var imported by remember { mutableStateOf<com.juancams.rviz2mini.ui.interop.ImportResult?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val types = TabCatalogInfo.order.filter { it in allowed && !(forWidget && it == TabType.DASHBOARD) }
    imported?.let { res ->
        // Fresh ids (a file can be imported twice); then, if anything had no equivalent, say what.
        val tabs = com.juancams.rviz2mini.tabs.TabSharing.copies(res.tabs, com.juancams.rviz2mini.tabs.TabStore.get().all)
        if (res.notes.isEmpty()) { imported = null; onAddAll?.invoke(tabs); return }
        AlertDialog(
            onDismissRequest = { imported = null },
            title = { Text(tr("interop_imported_title", res.format.label)) },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(tr("interop_notes"), fontSize = 13.sp)
                for (n in res.notes.distinct()) Text("• $n", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            } },
            confirmButton = { TextButton(onClick = { imported = null; onAddAll?.invoke(tabs) }) { Text(tr("interop_add")) } },
            dismissButton = { TextButton(onClick = { imported = null }) { Text(tr("common_cancel")) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chosen != null) IconButton(onClick = { chosen = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("common_back")) }
                Column {
                    Text(chosen?.label() ?: tr(if (forWidget) "add_tab_widget_title" else "common_new_tab"), fontWeight = FontWeight.Bold)
                    if (chosen == null) Text(tr("add_tab_subtitle"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                val c = chosen
                // A file from another tool (RViz, Foxglove, PlotJuggler) or a shared VizMini2 tab.
                val open = com.juancams.rviz2mini.ui.common.FileDialogs.openText
                if (c == null && onAddAll != null && !forWidget && open != null) {
                    OutlinedButton(onClick = {
                        open(listOf("json", "xml", "rviz", "yaml")) { text ->
                            if (text == null) return@open
                            val r = runCatching { com.juancams.rviz2mini.ui.interop.ConfigInterop.import(text) }
                            r.onSuccess { res -> imported = res }.onFailure { importError = it.message }
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(tr("interop_import")) }
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
                if (c == null && onAddAll != null && !forWidget && TabType.DASHBOARD in allowed) {
                    Text(tr("template_group"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                    Text(tr("template_uses_topics"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (t in DashboardTemplates.Template.entries) Card(onClick = { onAddAll(DashboardTemplates.build(t)) }, shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(tabIcon(TabType.DASHBOARD), null, Modifier.size(20.dp))
                                Text(tr(t.titleKey), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                            }
                            Text(tr(t.summaryKey), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                if (c == null) for (g in TabCatalogInfo.Group.entries) {
                    val items = types.filter { TabCatalogInfo.group(it) == g }
                    if (items.isEmpty()) continue
                    Text(tr(g.labelKey), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                    for (t in items) CatalogRow(t) { chosen = t }
                } else CatalogDetail(c, onAdd)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("common_cancel")) } },
        modifier = Modifier.width(620.dp),
    )
}

@Composable
private fun CatalogRow(t: TabType, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(tabIcon(t), null, Modifier.size(20.dp))
                    Text(t.label(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
                Text(TabCatalogInfo.summary(t), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            CatalogMedia.image("shot_${TabCatalogInfo.key(t)}")?.let {
                Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.padding(10.dp).size(76.dp, 92.dp).clip(RoundedCornerShape(10.dp)))
            }
        }
    }
}

/** A type's card: screenshot, video, what it does and the initial settings (the same as on Android). */
@Composable
private fun CatalogDetail(type: TabType, onAdd: (TabSpec) -> Unit) {
    var name by remember { mutableStateOf("") }
    val defaultName = TabList.resolveName("", type, TabStore.get().all, type.label())
    // Initial settings of each type.
    var fixedFrame by remember { mutableStateOf("") }
    var commonDisplays by remember { mutableStateOf(true) }
    var topic by remember { mutableStateOf(if (type == TabType.TELEOP || type == TabType.TELEOP_DUAL) "/cmd_vel" else "") }
    var mode by remember { mutableStateOf("LOG") }
    val lifecycleFound = remember { if (type == TabType.LIFECYCLE) RosConnection.graph()?.lifecycleNodes().orEmpty() else emptyList() }
    var addLifecycle by remember { mutableStateOf(lifecycleFound.isNotEmpty()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            CatalogMedia.image("shot_${TabCatalogInfo.key(type)}")?.let {
                Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp)))
            }
            CatalogMedia.playVideo?.let { play ->
                Button(onClick = { play("clip_${TabCatalogInfo.key(type)}") }, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                    Icon(Icons.Default.PlayCircle, null); Spacer(Modifier.width(6.dp)); Text(tr("add_tab_watch_video"))
                }
            }
        }
        Text(tr("add_tab_what_it_does"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Text(TabCatalogInfo.summary(type))
        for (d in TabCatalogInfo.details(type)) Row { Text("•  "); Text(d, fontSize = 14.sp) }

        Text(tr("add_tab_set_up"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("common_name")) },
            supportingText = { Text(tr("add_tab_name_helper", defaultName)) }, modifier = Modifier.fillMaxWidth())
        when (type) {
            TabType.RVIZ -> {
                OutlinedTextField(fixedFrame, { fixedFrame = it }, singleLine = true, label = { Text(tr("add_tab_fixed_frame")) },
                    supportingText = { Text(tr("add_tab_fixed_frame_helper")) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.clickable { commonDisplays = !commonDisplays }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(commonDisplays, { commonDisplays = it }); Text(tr("add_tab_common_displays"))
                }
            }
            TabType.CAMERA -> OutlinedTextField(topic, { topic = it }, singleLine = true, label = { Text(tr("add_tab_image_topic")) },
                supportingText = { Text(tr("add_tab_image_topic_helper")) }, modifier = Modifier.fillMaxWidth())
            TabType.TELEOP, TabType.TELEOP_DUAL -> OutlinedTextField(topic, { topic = it }, singleLine = true, label = { Text(tr("add_tab_velocity_topic")) },
                supportingText = { Text(tr("add_tab_velocity_topic_helper")) }, modifier = Modifier.fillMaxWidth())
            TabType.DIAGNOSTICS -> {
                Text(tr("add_tab_start_with"), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, label) in listOf("LOG" to "add_tab_mode_log", "DIAG" to "add_tab_mode_diagnostics", "ECHO" to "add_tab_mode_echo", "PLOT" to "add_tab_mode_plot"))
                        FilterChip(selected = mode == k, onClick = { mode = k }, label = { Text(tr(label)) })
                }
            }
            TabType.LIFECYCLE -> Row(Modifier.clickable(enabled = lifecycleFound.isNotEmpty()) { addLifecycle = !addLifecycle }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(addLifecycle, { addLifecycle = it }, enabled = lifecycleFound.isNotEmpty())
                Text(if (lifecycleFound.isEmpty()) tr("add_tab_lifecycle_none") else tr("add_tab_lifecycle_found", lifecycleFound.size))
            }
            else -> Text(tr("add_tab_no_settings"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                var s = TabSpec.create(type, TabList.resolveName(name, type, TabStore.get().all, type.label()))
                fun slash(t: String) = if (t.startsWith("/")) t else "/$t"
                when (type) {
                    TabType.RVIZ -> {
                        s = s.withSetting("fixedFrame", fixedFrame.trim().ifEmpty { null })
                        if (commonDisplays) s = s.withSetting("displays", DisplayList.toJson(DisplayList.defaults() + listOf(
                            DisplayConfig.create(DisplayType.MAP), DisplayConfig.create(DisplayType.ROBOT_MODEL),
                            DisplayConfig.create(DisplayType.LASER_SCAN), DisplayConfig.create(DisplayType.PATH, "Plan", "/plan"))))
                    }
                    TabType.CAMERA -> topic.trim().takeIf { it.isNotEmpty() }?.let { t ->
                        val compressed = RosConnection.graph()?.topics(true)?.firstOrNull { it.name == slash(t) }?.type == "sensor_msgs/msg/CompressedImage" || t.endsWith("/compressed")
                        s = s.withSetting(CAMERA_KEY_TOPIC, slash(t)).withSetting(CAMERA_KEY_KIND, if (compressed) "COMPRESSED" else "RAW")
                    }
                    TabType.TELEOP, TabType.TELEOP_DUAL -> s = s.withSetting("topic", slash(topic.trim().ifEmpty { "/cmd_vel" }))
                    TabType.DIAGNOSTICS -> s = s.withSetting("mode", mode)
                    TabType.LIFECYCLE -> if (addLifecycle) s = s.withSetting(LIFECYCLE_KEY_NODES, LifecycleModel.formatNodes(lifecycleFound))
                    else -> {}
                }
                onAdd(s)
            }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(tr("common_create")) }
        }
    }
}
