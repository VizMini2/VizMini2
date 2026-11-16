// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.ui.screens.tabIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.SHARED_TAB_TYPES
import com.juancams.rviz2mini.ui.TabScreen
import com.juancams.rviz2mini.ui.common.StatusPill
import com.juancams.rviz2mini.ui.common.rememberRosStatus
import com.juancams.rviz2mini.ui.screens.ConnectionScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/**
 * The desktop window: saved tabs (the same as on Android: TabStore) in a sidebar,
 * the network configuration and the screen of the selected tab.
 */
@Composable
fun DesktopApp(startTab: String? = null) {
    val dark = when (DesktopSettings.theme) { "light" -> false; "dark" -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    // Changing the language rebuilds everything (texts are read when composing).
    // Interface size (Settings → Appearance): the whole window scaled.
    val base = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
        androidx.compose.ui.unit.Density(base.density * DesktopSettings.uiScale, base.fontScale)) {
    key(DesktopSettings.language) { VizTheme(dark) {
        val store = TabStore.get()
        val tabs = remember { mutableStateListOf<TabSpec>().apply { addAll(store.all.filter { !it.isWidget }) } }
        // null = the network configuration.
        // `startTab`: the tab to start with: its id or name, or a tab type ("TOPICS"...; "CONFIG" = the network). If no tab
        // of that type exists, it is created.
        var selected by remember {
            mutableStateOf(when (startTab) {
                null -> tabs.firstOrNull { it.type in SHARED_TAB_TYPES || it.type in com.juancams.rviz2mini.ui.PlatformTabs.screens.keys }?.id
                "CONFIG" -> null
                else -> tabs.firstOrNull { it.id == startTab || it.name == startTab }?.id ?: TabType.fromName(startTab)?.let { type ->
                    (tabs.firstOrNull { it.type == type } ?: TabSpec.create(type, type.label()).also { store.add(it); tabs.add(it) }).id
                }
            })
        }
        var adding by remember { mutableStateOf(false) }
        var renaming by remember { mutableStateOf<TabSpec?>(null) }
        var deleting by remember { mutableStateOf<TabSpec?>(null) }
        var exportMessage by remember { mutableStateOf<String?>(null) }
        var configVersion by remember { mutableStateOf(0) }
        val status = rememberRosStatus()
        val collapsed = DesktopSettings.sidebarCollapsed
        /** After the demo or a profile changes all the tabs: the sidebar shows them and opens the first one. */
        fun reload(all: List<TabSpec>) {
            tabs.clear(); tabs.addAll(all.filter { !it.isWidget })
            selected = tabs.firstOrNull()?.id
            configVersion++
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxSize()) {
                // Sidebar: narrow, and collapsible to icons only.
                Column(Modifier.width(if (collapsed) 60.dp else 196.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { DesktopSettings.changeSidebar(!collapsed) }) {
                            Icon(Icons.Default.Menu, tr(if (collapsed) "desktop_sidebar_expand" else "desktop_sidebar_collapse"))
                        }
                        if (!collapsed) Text("VizMini2", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    val statusColor = com.juancams.rviz2mini.ui.common.statusLabel(status).second
                    if (collapsed) Box(Modifier.padding(start = 18.dp, top = 2.dp, bottom = 2.dp).size(12.dp).background(statusColor, CircleShape))
                    else StatusPill(status, Modifier.padding(horizontal = 6.dp))
                    // In demo mode, always in sight how to leave it.
                    if (DesktopDemo.active) SideItem(Icons.Default.SmartToy, tr("home_demo_exit"), false, collapsed, onClick = { reload(DesktopDemo.exit()) })
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (t in tabs) androidx.compose.foundation.ContextMenuArea(items = {
                            // Right click, as the phone's long press: rename, duplicate, delete, and export the tab for
                            // VizMini2 or for another tool (RViz, Foxglove, PlotJuggler).
                            listOf(
                                androidx.compose.foundation.ContextMenuItem(tr("common_rename")) { renaming = t },
                                androidx.compose.foundation.ContextMenuItem(tr("main_duplicate_tab")) {
                                    val all = store.all
                                    val copies = com.juancams.rviz2mini.tabs.TabSharing.copies(listOf(t) + all.filter { it.dashboardId == t.id }, all)
                                    copies.forEach { store.add(it) }
                                    copies.firstOrNull { !it.isWidget }?.let { c -> tabs.add(tabs.indexOf(t) + 1, c); selected = c.id }
                                },
                                androidx.compose.foundation.ContextMenuItem(tr("common_delete")) { deleting = t },
                            ) + com.juancams.rviz2mini.ui.interop.ConfigInterop.exportFormats(t, store.all).map { f ->
                                androidx.compose.foundation.ContextMenuItem(tr("interop_export_as", f.label)) { exportTab(t, f) { exportMessage = it } }
                            }
                        }) {
                            SideItem(tabIcon(t.type), t.name, selected == t.id, collapsed, onClick = { selected = t.id },
                                onClose = { deleting = t })
                        }
                    }
                    SideItem(Icons.Default.Add, tr("lifecycle_add"), false, collapsed, onClick = { adding = true })
                    RecordItem(collapsed)
                    SideItem(Icons.Default.Settings, tr("main_title_config"), selected == null, collapsed, onClick = { selected = null })
                }
                // The tab, and under it the timeline of a bag being played, whatever the tab (as on the phone).
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val spec = tabs.firstOrNull { it.id == selected }
                        if (spec == null) key(configVersion) {
                            ConnectionScreen(Modifier.fillMaxWidth()) {
                                DemoCard(onEnter = { reload(DesktopDemo.enter()) }, onExit = { reload(DesktopDemo.exit()) })
                                AppearanceCard()
                                AccountCard()
                                com.juancams.rviz2mini.ui.common.VariablesCard()
                                // Rebuilt when pulling changes from the cloud.
                                key(DesktopAccount.changes) { com.juancams.rviz2mini.ui.screens.ProfilesCard(onChanged = { name, _ -> DesktopAccount.push(name) }, onLoad = { p ->
                                    // As on Android: the profile's connection and its tabs instead of the current ones.
                                    if (p.ros != com.juancams.rviz2mini.ros.RosConnection.config) com.juancams.rviz2mini.ros.RosConnection.applyConfig(p.ros)
                                    store.replaceAll(p.tabsWithFreshIds())
                                    tabs.clear(); tabs.addAll(store.all.filter { !it.isWidget })
                                    configVersion++
                                }) }
                            }
                        }
                        else key(spec.id) { TabScreen(spec) }
                    }
                    com.juancams.rviz2mini.ui.common.PlaybackBar(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), inWindow = true)
                }
            }
        }

        exportMessage?.let { m ->
            androidx.compose.material3.AlertDialog(onDismissRequest = { exportMessage = null }, text = { Text(m) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { exportMessage = null }) { Text("OK") } })
        }
        renaming?.let { t ->
            var name by remember(t.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(t.name, androidx.compose.ui.text.TextRange(0, t.name.length))) }
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            fun save() {
                val others = store.all.filter { it.id != t.id }
                val updated = t.copy(name = TabList.resolveName(name.text, t.type, others, t.type.label()))
                store.update(updated)
                val i = tabs.indexOfFirst { it.id == t.id }
                if (i >= 0) tabs[i] = updated
                renaming = null
            }
            AlertDialog(onDismissRequest = { renaming = null }, title = { Text(tr("main_rename_tab")) },
                text = {
                    androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true,
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                },
                confirmButton = { TextButton(onClick = { save() }) { Text(tr("common_save")) } },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text(tr("common_cancel")) } })
        }
        deleting?.let { t ->
            AlertDialog(onDismissRequest = { deleting = null }, title = { Text(tr("common_delete_named", t.name)) },
                text = { Text(tr("main_delete_tab_message")) },
                confirmButton = {
                    TextButton(onClick = {
                        // A dashboard takes its widgets with it (as on the phone).
                        for (w in store.all.filter { it.dashboardId == t.id }) store.remove(w.id)
                        store.remove(t.id)
                        val i = tabs.indexOfFirst { it.id == t.id }
                        tabs.removeAll { it.id == t.id }
                        if (selected == t.id) selected = tabs.getOrNull((i - 1).coerceAtLeast(0))?.id
                        deleting = null
                    }) { Text(tr("common_delete"), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("common_cancel")) } })
        }
        if (adding) com.juancams.rviz2mini.ui.screens.AddTabDialog(
            allowed = SHARED_TAB_TYPES + com.juancams.rviz2mini.ui.PlatformTabs.screens.keys,
            onDismiss = { adding = false },
            onAddAll = { list -> list.forEach { store.add(it) }; tabs.addAll(list.filter { !it.isWidget }); selected = list.first().id; adding = false },
        ) { spec -> store.add(spec); tabs.add(spec); selected = spec.id; adding = false }
    } }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
/** A sidebar row: icon and name (only the icon when collapsed, with the name as tooltip). */
@Composable
private fun SideItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, collapsed: Boolean,
                     onClick: () -> Unit, onClose: (() -> Unit)? = null) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val row = @Composable {
        Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).background(bg).clickable(onClick = onClick).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, if (collapsed) label else null, Modifier.size(20.dp))
            if (!collapsed) {
                Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, tr("main_delete_tab_message"), Modifier.size(16.dp))
                }
            }
        }
    }
    if (collapsed) TooltipArea(tooltip = {
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.inverseSurface) {
            Text(label, color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }) { row() } else row()
}


/** Record the window (or stop, with the time so far): [DesktopRecorder]. */
@Composable
private fun RecordItem(collapsed: Boolean) {
    val rec = DesktopRecorder.recording
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(rec) { while (rec) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) } }
    val secs = ((now - DesktopRecorder.startedMs) / 1000).coerceAtLeast(0)
    val label = if (rec) tr("record_stop", String.format(java.util.Locale.ROOT, "%d:%02d", secs / 60, secs % 60)) else tr("record_start")
    SideItem(if (rec) Icons.Default.Stop else Icons.Default.FiberManualRecord, label, rec, collapsed, onClick = { Thread { DesktopRecorder.toggle() }.start() })
}

/** Saves the tab in that format with the system dialog; `done` gets what to tell the user. */
private fun exportTab(t: com.juancams.rviz2mini.tabs.TabSpec, f: com.juancams.rviz2mini.ui.interop.ConfigFormat, done: (String) -> Unit) {
    val save = com.juancams.rviz2mini.ui.common.FileDialogs.saveText ?: return
    val r = runCatching { com.juancams.rviz2mini.ui.interop.ConfigInterop.export(t, com.juancams.rviz2mini.tabs.TabStore.get().all, f) }
        .getOrElse { done(it.message ?: "?"); return }
    save(r.fileName, r.text) { ok ->
        if (!ok) return@save
        done(if (r.notes.isEmpty()) tr("interop_exported", f.label) else tr("interop_exported", f.label) + "\n\n" + tr("interop_notes") + "\n" + r.notes.distinct().joinToString("\n") { "• $it" })
    }
}

/** Settings card: try the app with the simulated robot ([DesktopDemo]), or leave it. */
@Composable
private fun DemoCard(onEnter: () -> Unit, onExit: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    androidx.compose.material3.Card(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.SmartToy, null)
                Text(tr(if (DesktopDemo.active) "home_demo_title" else "onboarding_demo_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(tr(if (DesktopDemo.active) "home_demo_text" else "onboarding_demo_text"))
            if (DesktopDemo.active) androidx.compose.material3.OutlinedButton(onClick = onExit) { Text(tr("home_demo_exit")) }
            else androidx.compose.material3.Button(onClick = { confirming = true }) { Text(tr("home_start_demo")) }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(tr("home_demo_confirm_title")) }, text = { Text(tr("home_demo_confirm_message")) },
        confirmButton = { TextButton(onClick = { confirming = false; onEnter() }) { Text(tr("home_start_demo")) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(tr("common_cancel")) } })
}
