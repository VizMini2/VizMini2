// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.control.ControlMsgs
import com.juancams.rviz2mini.control.ControllerCommands
import com.juancams.rviz2mini.control.ControllerManagerClient
import com.juancams.rviz2mini.control.ControllerManagerClient.Controller
import com.juancams.rviz2mini.control.ControllerManagerClient.HardwareComponent
import com.juancams.rviz2mini.control.RobotJoints
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Tab keys: the controller manager and its controller_manager_msgs version. */
const val CONTROLLERS_KEY_MANAGER = "manager"
const val CONTROLLERS_KEY_MSGS = "msgs"

/** What is visible of each tab's client (for the tests). */
object ControllersScreens {
    val clients = java.util.concurrent.ConcurrentHashMap<String, ControllerManagerClient>()
}

/**
 * ros2_control (same on the phone and on the PC): the controllers of a controller manager with their state,
 * activate / deactivate / configure / unload / load, moving the joints of trajectory and forward command
 * controllers, and the hardware components with their interfaces.
 */
@Composable
fun ControllersScreen(tabId: String, modifier: Modifier = Modifier) {
    val store = remember { TabStore.get() }
    fun setting(k: String) = store.get(tabId)?.settings?.get(k)
    val scope = rememberCoroutineScope()
    var manager by remember(tabId) { mutableStateOf(ControllerManagerClient.normalize(setting(CONTROLLERS_KEY_MANAGER).orEmpty())) }
    var tick by remember { mutableIntStateOf(0) }
    val client = remember(tabId, manager) {
        ControllerManagerClient(Interfaces.get(), manager).apply {
            setting(CONTROLLERS_KEY_MSGS)?.let { m -> ControlMsgs.entries.firstOrNull { it.name == m } }?.let { msgVersion = it }
        }
    }
    val joints = remember(tabId, manager) { RobotJoints(Interfaces.get(), manager) { tick++ } }
    val publishers = remember(tabId, manager) { HashMap<String, DynRos.Publisher>() }
    DisposableEffect(client) {
        ControllersScreens.clients[tabId] = client; joints.start()
        onDispose {
            ControllersScreens.clients.remove(tabId, client)
            client.close(); joints.close(); publishers.values.forEach { runCatching { it.close() } }
        }
    }
    var controllers by remember(client) { mutableStateOf<List<Controller>?>(null) }
    var hardware by remember(client) { mutableStateOf<List<HardwareComponent>>(emptyList()) }
    var notLoaded by remember(client) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var error by remember(client) { mutableStateOf<String?>(null) }
    var message by remember(client) { mutableStateOf<String?>(null) }
    var busy by remember(client) { mutableStateOf(false) }
    var refreshNow by remember { mutableIntStateOf(0) }

    suspend fun refresh(full: Boolean) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val list = client.listControllers()
                val hw = runCatching { client.listHardwareComponents() }.getOrDefault(hardware)
                val extra = if (full) client.configuredButNotLoaded(list.map { it.name }) else notLoaded
                Triple(list, hw, extra)
            }
        }
        r.onSuccess { (l, h, n) -> controllers = l; hardware = h; notLoaded = n; error = null }
            .onFailure { error = it.message; controllers = null }
    }
    LaunchedEffect(client, refreshNow) {
        var n = 0
        while (true) { refresh(full = n % 5 == 0); n++; delay(2000) }
    }

    /** Runs one manager call off the main thread, then shows the result and refreshes. */
    fun run(what: String, block: () -> Pair<Boolean, String>) {
        busy = true; message = tr("controllers_working", what)
        scope.launch {
            val (ok, detail) = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { false to (it.message ?: "") } }
            busy = false
            message = if (ok) tr("controllers_done", what) else tr("controllers_failed", what) + (if (detail.isNotBlank()) ": $detail" else "")
            refresh(full = true)
        }
    }
    var switchTo by remember { mutableStateOf<Pair<Controller, List<Controller>>?>(null) }
    var unloading by remember { mutableStateOf<Controller?>(null) }

    fun activate(c: Controller) {
        val conflicts = controllers.orEmpty().filter { o -> o.active && o.name != c.name && o.claimed.any { it in c.commandInterfaces } }
        if (conflicts.isNotEmpty()) { switchTo = c to conflicts; return }
        run(tr("controllers_activate_what", c.name)) { client.switchControllers(listOf(c.name), emptyList()).let { it.ok to it.message } }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ManagerRow(manager, onChange = { m -> manager = ControllerManagerClient.normalize(m); store.updateSetting(tabId, CONTROLLERS_KEY_MANAGER, manager) },
            onRefresh = { refreshNow++ })
        if (error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Text(tr("controllers_no_manager", manager), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (controllers == null) {
            Text(tr("controllers_waiting", manager), style = MaterialTheme.typography.bodyMedium)
        }
        val list = controllers.orEmpty()
        if (controllers != null) {
            Text(tr("controllers_title", list.size), style = MaterialTheme.typography.titleMedium)
            if (list.isEmpty()) Text(tr("controllers_none"), style = MaterialTheme.typography.bodyMedium)
        }
        for (c in list) ControllerCard(c, busy, joints, tick,
            onActivate = { activate(c) },
            onDeactivate = { run(tr("controllers_deactivate_what", c.name)) { client.switchControllers(emptyList(), listOf(c.name)).let { it.ok to it.message } } },
            onConfigure = { run(tr("controllers_configure_what", c.name)) { client.configure(c.name) to "" } },
            onUnload = { unloading = c },
            onSend = { names, values, duration ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { ControllerCommands.send(Interfaces.get(), manager, c, names, values, duration, publishers) }.getOrDefault(false) }
                    message = if (ok) tr("controllers_sent", c.name) else tr("controllers_no_connection")
                }
            })
        if (notLoaded.isNotEmpty()) {
            Text(tr("controllers_not_loaded"), style = MaterialTheme.typography.titleSmall)
            for ((name, type) in notLoaded) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(type, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(enabled = !busy, onClick = { run(tr("controllers_load_what", name)) { client.load(name) to "" } }) { Text(tr("controllers_load")) }
            }
        }
        if (hardware.isNotEmpty()) {
            Text(tr("controllers_hardware"), style = MaterialTheme.typography.titleMedium)
            for (h in hardware) HardwareCard(h, busy,
                onActivate = { run(tr("controllers_activate_what", h.name)) { client.setHardwareActive(h.name, true) to "" } },
                onDeactivate = { run(tr("controllers_deactivate_what", h.name)) { client.setHardwareActive(h.name, false) to "" } })
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        // The controller_manager_msgs version (they change between distros).
        var msgsOpen by remember { mutableStateOf(false) }
        Box {
            val label = tr(client.msgVersion.labelKey) + (client.detected?.takeIf { client.msgVersion == ControlMsgs.AUTO }?.let { " → " + tr(it.labelKey) } ?: "")
            TextButton(onClick = { msgsOpen = true }) { Text(tr("controllers_msgs", label), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(msgsOpen, { msgsOpen = false }) {
                for (v in ControlMsgs.entries) DropdownMenuItem({ Text(tr(v.labelKey), fontSize = 13.sp) }, {
                    msgsOpen = false; client.msgVersion = v; store.updateSetting(tabId, CONTROLLERS_KEY_MSGS, v.name); refreshNow++
                })
            }
        }
        if (tick < 0) Text("")
    }

    switchTo?.let { (c, conflicts) ->
        AlertDialog(
            onDismissRequest = { switchTo = null },
            title = { Text(tr("controllers_conflict_title", c.name)) },
            text = { Text(tr("controllers_conflict_text", conflicts.joinToString(", ") { it.name })) },
            confirmButton = { TextButton(onClick = {
                switchTo = null
                run(tr("controllers_activate_what", c.name)) { client.switchControllers(listOf(c.name), conflicts.map { it.name }).let { it.ok to it.message } }
            }) { Text(tr("controllers_switch")) } },
            dismissButton = { TextButton(onClick = { switchTo = null }) { Text(tr("controllers_cancel")) } },
        )
    }
    unloading?.let { c ->
        AlertDialog(
            onDismissRequest = { unloading = null },
            title = { Text(tr("controllers_unload_title", c.name)) },
            text = { Text(tr("controllers_unload_text")) },
            confirmButton = { TextButton(onClick = {
                unloading = null; run(tr("controllers_unload_what", c.name)) { client.unload(c.name) to "" }
            }) { Text(tr("controllers_unload"), color = Color(0xFFE53935)) } },
            dismissButton = { TextButton(onClick = { unloading = null }) { Text(tr("controllers_cancel")) } },
        )
    }
}

/** The manager: the ones on the network (nodes with list_controllers) or one typed by hand. */
@Composable
private fun ManagerRow(manager: String, onChange: (String) -> Unit, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }) { Text(tr("controllers_manager", manager), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(open, { open = false }) {
                val found = RosConnection.graph()?.services(includeHidden = true).orEmpty()
                    .filter { it.type == "controller_manager_msgs/srv/ListControllers" && it.name.endsWith("/list_controllers") }
                    .map { it.name.removeSuffix("/list_controllers") }.distinct().sorted()
                for (m in found) DropdownMenuItem({ Text(m, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }, { open = false; onChange(m) })
                DropdownMenuItem({ Text(tr("controllers_manager_other")) }, { open = false; typing = true })
            }
        }
        TextButton(onClick = onRefresh) { Text(tr("controllers_refresh")) }
    }
    if (typing) {
        var text by remember { mutableStateOf(manager) }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(tr("controllers_manager_other")) },
            text = { OutlinedTextField(text, { text = it.trim() }, singleLine = true, label = { Text(tr("controllers_manager_hint")) }) },
            confirmButton = { TextButton(onClick = { typing = false; onChange(text) }) { Text(tr("rviz_action_save")) } },
            dismissButton = { TextButton(onClick = { typing = false }) { Text(tr("controllers_cancel")) } },
        )
    }
}

@Composable
private fun stateColor(state: String) = when (state) {
    "active" -> StatusColors.ok
    "inactive" -> StatusColors.warn
    "unconfigured" -> MaterialTheme.colorScheme.primary
    "finalized" -> StatusColors.error
    else -> StatusColors.idle
}

@Composable
private fun ControllerCard(
    c: Controller, busy: Boolean, joints: RobotJoints, tick: Int,
    onActivate: () -> Unit, onDeactivate: () -> Unit, onConfigure: () -> Unit, onUnload: () -> Unit,
    onSend: (List<String>, List<Double>, Double) -> Unit,
) {
    var showInterfaces by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                    Text(c.type, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(c.state, color = stateColor(c.state), style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { contentDescription = "state ${c.name} ${c.state}" })
                    val extra = listOfNotNull(c.updateRate?.takeIf { it > 0 }?.let { tr("controllers_rate", it) }, if (c.isChained) tr("controllers_chained") else null)
                    if (extra.isNotEmpty()) Text(extra.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (c.state) {
                    "active" -> FilledTonalButton(enabled = !busy, onClick = onDeactivate, modifier = Modifier.semantics { contentDescription = "deactivate ${c.name}" }) { Text(tr("controllers_deactivate")) }
                    "inactive" -> {
                        FilledTonalButton(enabled = !busy, onClick = onActivate, modifier = Modifier.semantics { contentDescription = "activate ${c.name}" }) { Text(tr("controllers_activate")) }
                        OutlinedButton(enabled = !busy, onClick = onUnload) { Text(tr("controllers_unload"), color = StatusColors.error) }
                    }
                    "unconfigured" -> {
                        FilledTonalButton(enabled = !busy, onClick = onConfigure) { Text(tr("controllers_configure")) }
                        OutlinedButton(enabled = !busy, onClick = onUnload) { Text(tr("controllers_unload"), color = StatusColors.error) }
                    }
                }
                if (c.commandInterfaces.isNotEmpty() || c.requiredState.isNotEmpty())
                    TextButton(onClick = { showInterfaces = !showInterfaces }) { Text(tr(if (showInterfaces) "controllers_interfaces_hide" else "controllers_interfaces"), fontSize = 12.sp) }
                if (c.active && c.kind != ControllerCommands.Kind.NONE && c.joints.isNotEmpty())
                    TextButton(onClick = { moving = !moving }, modifier = Modifier.semantics { contentDescription = "move ${c.name}" }) {
                        Text(tr(if (moving) "controllers_move_hide" else "controllers_move"), fontSize = 12.sp)
                    }
            }
            if (showInterfaces) {
                val mono = Modifier.padding(start = 4.dp)
                if (c.claimed.isNotEmpty()) Text(tr("controllers_claimed", c.claimed.joinToString(", ")), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = mono)
                if (c.requiredCommand.isNotEmpty() && c.requiredCommand != c.claimed) Text(tr("controllers_required", c.requiredCommand.joinToString(", ")), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = mono)
                if (c.requiredState.isNotEmpty()) Text(tr("controllers_state_interfaces", c.requiredState.joinToString(", ")), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = mono)
            }
            if (moving && c.active) JointCommands(c, joints, tick, busy, onSend)
        }
    }
}

/** One slider per joint, Current (copy the robot's), Send and, for trajectories, the duration. */
@Composable
private fun JointCommands(c: Controller, joints: RobotJoints, tick: Int, busy: Boolean, onSend: (List<String>, List<Double>, Double) -> Unit) {
    val names = c.joints
    val kind = c.commandKind
    val target = remember(c.name) { mutableStateMapOf<String, Double>() }
    fun syncToCurrent() { for (j in names) target[j] = if (kind == null || kind == "position") joints.current[j] ?: 0.0 else 0.0 }
    LaunchedEffect(c.name) { syncToCurrent() }
    var duration by remember { mutableStateOf(2.0) }
    var live by remember { mutableStateOf(false) }
    var lastLive by remember { mutableStateOf(0L) }
    val f = Locale.ROOT
    fun send() = onSend(names, names.map { target[it] ?: 0.0 }, duration)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (j in names) {
            val range = joints.range(j, kind)
            val prismatic = joints.urdf?.joints?.firstOrNull { it.name == j }?.type == "prismatic"
            fun fmt(v: Double) = when {
                kind != null && kind != "position" -> String.format(f, "%.2f", v)
                prismatic -> String.format(f, "%.3f m", v)
                else -> String.format(f, "%.1f°", Math.toDegrees(v))
            }
            val v = (target[j] ?: 0.0).coerceIn(range)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(j, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(130.dp))
                Slider(value = v.toFloat(), valueRange = range.start.toFloat()..range.endInclusive.toFloat(), modifier = Modifier.weight(1f).semantics { contentDescription = "slider $j" },
                    onValueChange = {
                        target[j] = it.toDouble()
                        val now = System.currentTimeMillis()
                        if (live && now - lastLive > 100) { lastLive = now; send() }
                    },
                    onValueChangeFinished = { if (live) send() })
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
                    Text(fmt(v), fontSize = 12.sp)
                    if (tick >= 0) joints.current[j]?.let { Text(fmt(it), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (c.kind == ControllerCommands.Kind.TRAJECTORY) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("controllers_duration", String.format(f, "%.1f", duration)), fontSize = 12.sp, modifier = Modifier.width(130.dp))
            Slider(value = duration.toFloat(), valueRange = 0.2f..10f, modifier = Modifier.weight(1f), onValueChange = { duration = Math.round(it * 10) / 10.0 })
        }
        Row(Modifier.clickable { live = !live }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(live, { live = it })
            Text(tr("controllers_live"), fontSize = 12.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = !busy, onClick = { send() }, modifier = Modifier.semantics { contentDescription = "send ${c.name}" }) { Text(tr("controllers_send")) }
            OutlinedButton(onClick = { syncToCurrent() }) { Text(tr(if (kind == null || kind == "position") "controllers_current" else "controllers_zero")) }
        }
    }
}

@Composable
private fun HardwareCard(h: HardwareComponent, busy: Boolean, onActivate: () -> Unit, onDeactivate: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                    Text(listOf(h.type, h.plugin).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(h.stateLabel, color = stateColor(h.stateLabel), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { contentDescription = "state ${h.name} ${h.stateLabel}" })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (h.active) OutlinedButton(enabled = !busy, onClick = onDeactivate, modifier = Modifier.semantics { contentDescription = "deactivate ${h.name}" }) { Text(tr("controllers_deactivate")) }
                else if (h.stateId == ControllerManagerClient.STATE_INACTIVE) FilledTonalButton(enabled = !busy, onClick = onActivate,
                    modifier = Modifier.semantics { contentDescription = "activate ${h.name}" }) { Text(tr("controllers_activate")) }
                TextButton(onClick = { open = !open }) { Text(tr(if (open) "controllers_interfaces_hide" else "controllers_interfaces"), fontSize = 12.sp) }
            }
            if (open) {
                for ((title, list) in listOf(tr("controllers_command_interfaces") to h.command, tr("controllers_state_interfaces_title") to h.state)) {
                    if (list.isEmpty()) continue
                    Text(title, fontSize = 12.sp, style = MaterialTheme.typography.labelMedium)
                    for (i in list) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(i.name, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        if (i.claimed) Text(tr("controllers_claimed_short"), fontSize = 11.sp, color = StatusColors.ok)
                        if (i.available == false) Text(tr("controllers_unavailable"), fontSize = 11.sp, color = StatusColors.error)
                    }
                }
            }
        }
    }
}
