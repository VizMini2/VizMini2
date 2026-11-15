// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.moveit.MoveItClient
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabStore
import java.util.Locale

const val MOVEIT_KEY_GROUP = "group"
const val MOVEIT_KEY_NS = "ns"
const val MOVEIT_KEY_VELOCITY = "velocity"
const val MOVEIT_KEY_PIPELINE = "pipeline"
const val MOVEIT_KEY_PLANNER = "planner"
const val MOVEIT_KEY_TIME = "planningTime"
const val MOVEIT_KEY_LINE = "straightLine"
const val MOVEIT_KEY_MSGS = "msgs"

/** What is visible of each tab's MoveIt client (for the tests). */
object MoveItScreens {
    val clients = java.util.concurrent.ConcurrentHashMap<String, MoveItClient>()
}

/**
 * MoveIt (same on the phone and on the PC): group, named states from the SRDF, one slider per joint for the goal,
 * speed, and Plan / Execute / Plan and execute / Stop. The planned trajectory is shown in 3D with the
 * Trajectory display.
 */
@Composable
fun MoveItScreen(tabId: String, modifier: Modifier = Modifier) {
    val store = remember { TabStore.get() }
    fun setting(k: String) = store.get(tabId)?.settings?.get(k)
    var version by remember { mutableIntStateOf(0) }
    // The move_group namespace: changing it creates another client.
    var ns by remember(tabId) { mutableStateOf(setting(MOVEIT_KEY_NS).orEmpty()) }
    val client = remember(tabId, ns) {
        MoveItClient(Interfaces.get(), ns) { version++ }.apply {
            setting(MOVEIT_KEY_VELOCITY)?.toDoubleOrNull()?.let { velocityScaling = it; accelerationScaling = it }
            pipelineId = setting(MOVEIT_KEY_PIPELINE).orEmpty(); plannerId = setting(MOVEIT_KEY_PLANNER).orEmpty()
            setting(MOVEIT_KEY_TIME)?.toDoubleOrNull()?.let { planningTimeS = it }
            cartesian = setting(MOVEIT_KEY_LINE) == "true"
            setting(MOVEIT_KEY_MSGS)?.let { m -> com.juancams.rviz2mini.moveit.MoveItMsgs.entries.firstOrNull { it.name == m } }?.let { msgVersion = it }
        }
    }
    DisposableEffect(client) {
        client.start(); client.queryPlanners(); MoveItScreens.clients[tabId] = client
        com.juancams.rviz2mini.moveit.MoveItGoal.client = client
        onDispose {
            MoveItScreens.clients.remove(tabId, client)
            if (com.juancams.rviz2mini.moveit.MoveItGoal.client === client) com.juancams.rviz2mini.moveit.MoveItGoal.client = null
            client.close()
        }
    }
    // The saved group, as soon as the SRDF is known.
    val saved = setting(MOVEIT_KEY_GROUP)
    if (version >= 0 && saved != null && client.group != saved && saved in client.groups()) client.group = saved
    val f = Locale.ROOT

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val robot = client.urdf?.name
        if (client.srdf == null || client.urdf == null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Text(tr("moveit_waiting"), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        // Group
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(robot.orEmpty(), style = MaterialTheme.typography.titleMedium)
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) { Text(tr("moveit_group", client.group ?: "—")) }
                DropdownMenu(open, { open = false }) {
                    for (g in client.groups()) DropdownMenuItem({ Text(g) }, { open = false; client.group = g; store.updateSetting(tabId, MOVEIT_KEY_GROUP, g) })
                }
            }
            if (!client.hasJointStates) Text(tr("moveit_no_joint_states"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        // Named states and "current"
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { client.syncTargetToCurrent() }, label = { Text(tr("moveit_current")) })
            for (s in client.namedStates()) AssistChip(onClick = { client.applyNamedState(s) }, label = { Text(s) })
        }
        // One slider per joint
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                for (j in client.groupJoints()) {
                    val (lo, hi) = client.limits(j)
                    val prismatic = client.urdf?.joints?.firstOrNull { it.name == j }?.type == "prismatic"
                    fun fmt(v: Double) = if (prismatic) String.format(f, "%.3f m", v) else String.format(f, "%.1f°", Math.toDegrees(v))
                    val v = client.target[j] ?: 0.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(j, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp))
                        Slider(value = v.toFloat(), onValueChange = { client.setTarget(j, it.toDouble()) }, valueRange = lo.toFloat()..hi.toFloat(),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "slider $j" })
                        Column(Modifier.width(78.dp), horizontalAlignment = Alignment.End) {
                            Text(fmt(v), fontSize = 12.sp)
                            client.current[j]?.let { Text(fmt(it), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
        // Move the gripper (MoveIt computes the joints with IK)
        if (client.tipLink() != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            var big by remember { mutableStateOf(false) }
            val step = if (big) 0.05 else 0.01
            val turn = Math.toRadians(if (big) 15.0 else 5.0)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("moveit_tip", client.tipLink().orEmpty()), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { big = !big }) { Text(if (big) "5 cm · 15°" else "1 cm · 5°", fontSize = 12.sp) }
                }
                client.tipPose()?.let { p -> Text(String.format(f, "x %.3f  y %.3f  z %.3f m", p[12], p[13], p[14]), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                @Composable fun pair(label: String, minus: () -> Unit, plus: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                    OutlinedButton(onClick = minus, enabled = !client.busy, contentPadding = pad, modifier = Modifier.semantics { contentDescription = "$label-" }) { Text("$label−", fontSize = 12.sp) }
                    OutlinedButton(onClick = plus, enabled = !client.busy, contentPadding = pad, modifier = Modifier.semantics { contentDescription = "$label+" }) { Text("$label+", fontSize = 12.sp) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair("X", { client.jog(-step, 0.0, 0.0) }, { client.jog(step, 0.0, 0.0) })
                    pair("Y", { client.jog(0.0, -step, 0.0) }, { client.jog(0.0, step, 0.0) })
                    pair("Z", { client.jog(0.0, 0.0, -step) }, { client.jog(0.0, 0.0, step) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair("Rx", { client.jog(0.0, 0.0, 0.0, droll = -turn) }, { client.jog(0.0, 0.0, 0.0, droll = turn) })
                    pair("Ry", { client.jog(0.0, 0.0, 0.0, dpitch = -turn) }, { client.jog(0.0, 0.0, 0.0, dpitch = turn) })
                    pair("Rz", { client.jog(0.0, 0.0, 0.0, dyaw = -turn) }, { client.jog(0.0, 0.0, 0.0, dyaw = turn) })
                }
                // Obstacles: a box between the gripper and the goal (the plan avoids it) and removing them.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { client.addBox() }, enabled = !client.busy) { Text(tr("moveit_add_box"), fontSize = 12.sp) }
                    if (client.boxes.isNotEmpty()) TextButton(onClick = { client.clearBoxes() }, enabled = !client.busy) { Text(tr("moveit_clear_boxes", client.boxes.size), fontSize = 12.sp) }
                }
            }
        }
        // Speed
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("moveit_speed", (client.velocityScaling * 100).toInt()), fontSize = 13.sp, modifier = Modifier.width(140.dp))
            Slider(value = client.velocityScaling.toFloat(), valueRange = 0.05f..1f, modifier = Modifier.weight(1f), onValueChange = {
                client.velocityScaling = it.toDouble(); client.accelerationScaling = it.toDouble(); version++
                store.updateSetting(tabId, MOVEIT_KEY_VELOCITY, String.format(f, "%.2f", it))
            })
        }
        // Options: straight line, planner, time and namespace
        val toggleLine = { on: Boolean -> client.cartesian = on; store.updateSetting(tabId, MOVEIT_KEY_LINE, on.toString()); version++ }
        Row(Modifier.clickable(enabled = client.tipLink() != null) { toggleLine(!client.cartesian) }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(client.cartesian, { on -> toggleLine(on) }, enabled = client.tipLink() != null)
            Text(tr("moveit_straight_line"), fontSize = 13.sp)
        }
        var options by remember { mutableStateOf(false) }
        TextButton(onClick = { options = !options }) { Text(tr(if (options) "moveit_options_hide" else "moveit_options"), fontSize = 13.sp) }
        if (options) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var open by remember { mutableStateOf(false) }
                val current = if (client.plannerId.isEmpty() && client.pipelineId.isEmpty()) tr("moveit_planner_default")
                    else listOf(client.pipelineId, client.plannerId).filter { it.isNotEmpty() }.joinToString(" · ")
                Box {
                    OutlinedButton(onClick = { open = true }) { Text(tr("moveit_planner", current), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem({ Text(tr("moveit_planner_default")) }, {
                            open = false; client.pipelineId = ""; client.plannerId = ""
                            store.updateSetting(tabId, MOVEIT_KEY_PIPELINE, ""); store.updateSetting(tabId, MOVEIT_KEY_PLANNER, ""); version++
                        })
                        for ((pipe, planner) in client.planners) DropdownMenuItem({ Text("$pipe · $planner", fontSize = 13.sp) }, {
                            open = false; client.pipelineId = pipe; client.plannerId = planner
                            store.updateSetting(tabId, MOVEIT_KEY_PIPELINE, pipe); store.updateSetting(tabId, MOVEIT_KEY_PLANNER, planner); version++
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("moveit_planning_time", client.planningTimeS.toInt()), fontSize = 13.sp, modifier = Modifier.width(140.dp))
                    Slider(value = client.planningTimeS.toFloat(), valueRange = 1f..20f, steps = 18, modifier = Modifier.weight(1f), onValueChange = {
                        client.planningTimeS = Math.round(it).toDouble(); store.updateSetting(tabId, MOVEIT_KEY_TIME, client.planningTimeS.toString()); version++
                    })
                }
                // The MoveIt version of move_group (its messages change between distros).
                var msgsOpen by remember { mutableStateOf(false) }
                Box {
                    val label = tr(client.msgVersion.labelKey) + (client.detected?.takeIf { client.msgVersion == com.juancams.rviz2mini.moveit.MoveItMsgs.AUTO }?.let { " → " + tr(it.labelKey) } ?: "")
                    OutlinedButton(onClick = { msgsOpen = true }) { Text(tr("moveit_msgs", label), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    DropdownMenu(msgsOpen, { msgsOpen = false }) {
                        for (v in com.juancams.rviz2mini.moveit.MoveItMsgs.entries) DropdownMenuItem({ Text(tr(v.labelKey), fontSize = 13.sp) }, {
                            msgsOpen = false; client.msgVersion = v; store.updateSetting(tabId, MOVEIT_KEY_MSGS, v.name); version++
                        })
                    }
                }
                var nsText by remember(ns) { mutableStateOf(ns) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(nsText, { nsText = it.trim() }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(tr("moveit_namespace")) }, placeholder = { Text("/") })
                    TextButton(onClick = { store.updateSetting(tabId, MOVEIT_KEY_NS, nsText); ns = nsText }, enabled = nsText != ns) { Text(tr("rviz_action_save")) }
                }
            }
        }
        // Buttons
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { client.plan() }, enabled = !client.busy) { Text(tr("moveit_plan")) }
            FilledTonalButton(onClick = { client.execute() }, enabled = !client.busy && client.lastPlan != null) { Text(tr("moveit_execute")) }
            Button(onClick = { client.planAndExecute() }, enabled = !client.busy) { Text(tr("moveit_plan_execute")) }
            if (client.busy) Button(onClick = { client.stop() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935), contentColor = Color.White)) { Text(tr("moveit_stop")) }
        }
        client.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        Text(tr("moveit_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (version < 0) TextButton(onClick = {}) {}
    }
}
