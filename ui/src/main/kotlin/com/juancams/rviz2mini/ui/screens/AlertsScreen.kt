// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.alerts.AlertLog
import com.juancams.rviz2mini.alerts.AlertRules
import com.juancams.rviz2mini.alerts.TopicAlertMonitors
import com.juancams.rviz2mini.alerts.TopicRule
import com.juancams.rviz2mini.alerts.TopicRules
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.sliders.SliderMath
import com.juancams.rviz2mini.ui.theme.StatusColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Platform extras at the top of the Alerts tab (the PC's general switches); null: none. */
object AlertsScreens {
    @Volatile var extra: (@Composable () -> Unit)? = null
}

/**
 * Alerts (same on the phone and on the PC): the user's topic rules (a field crossing a threshold, or a topic falling
 * silent), whether each one is firing now, and the latest notices of every kind. The rules are global (they keep
 * watching while this tab is closed).
 */
@Composable
fun AlertsScreen(tabId: String, modifier: Modifier = Modifier) {
    var rules by remember { mutableStateOf(TopicRules.load()) }
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<TopicRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    DisposableEffect(tabId) {
        val l: () -> Unit = { tick++ }
        AlertLog.addListener(l)
        onDispose { AlertLog.removeListener(l) }
    }
    // The state of each rule (firing, last value) changes without notices: repainted every second.
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    fun save(list: List<TopicRule>) { rules = list; TopicRules.save(list) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AlertsScreens.extra?.invoke()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("alerts_rules"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { creating = true }) { Text(tr("alerts_add_rule")) }
        }
        if (rules.isEmpty()) Text(tr("alerts_no_rules"), style = MaterialTheme.typography.bodyMedium)
        val state = if (tick >= 0) TopicAlertMonitors.current?.state().orEmpty() else emptyMap()
        for (r in rules) {
            val watched = r.id in state
            val (firing, value) = state[r.id] ?: (false to null)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(when { !r.enabled || !watched -> StatusColors.idle; firing -> severityColor(r.severity); else -> StatusColors.ok }, CircleShape))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(r.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.topic + " · " + r.condition() + (value?.let { " · " + tr("alerts_now", TopicRule.fmt(it)) } ?: ""),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(r.enabled, { on -> save(rules.map { if (it.id == r.id) it.copy(enabled = on) else it }) },
                        modifier = Modifier.semantics { contentDescription = "enable ${r.title()}" })
                    TextButton(onClick = { editing = r }) { Text(tr("alerts_edit"), fontSize = 12.sp) }
                }
            }
        }
        Text(tr(TopicAlertMonitors.whereKey), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("alerts_history"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { AlertLog.clear() }) { Text(tr("alerts_clear_history")) }
        }
        val log = if (tick >= 0) AlertLog.all() else emptyList()
        if (log.isEmpty()) Text(tr("alerts_no_history"), style = MaterialTheme.typography.bodyMedium)
        val time = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }
        for (e in log.take(100)) Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp).size(8.dp).background(severityColor(e.alert.severity), CircleShape))
            Text(time.format(Date(e.timeMs)), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(e.alert.title, fontSize = 13.sp)
                if (e.alert.text.isNotBlank()) Text(e.alert.text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (creating || editing != null) RuleDialog(editing, onDismiss = { creating = false; editing = null },
        onDelete = editing?.let { r -> { save(rules.filter { it.id != r.id }); editing = null } },
        onSave = { r ->
            save(if (rules.any { it.id == r.id }) rules.map { if (it.id == r.id) r else it } else rules + r)
            creating = false; editing = null
        })
}

@Composable
private fun severityColor(s: AlertRules.Severity) = when (s) {
    AlertRules.Severity.ERROR -> StatusColors.error
    AlertRules.Severity.WARNING -> StatusColors.warn
    AlertRules.Severity.RECOVERED -> StatusColors.ok
}

/** Creating or editing a rule: topic (from the graph or typed), kind, field (the numeric ones of its type), condition. */
@Composable
private fun RuleDialog(initial: TopicRule?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (TopicRule) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var topic by remember { mutableStateOf(initial?.topic.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: TopicRule.Kind.VALUE) }
    var field by remember { mutableStateOf(initial?.field.orEmpty()) }
    var op by remember { mutableStateOf(initial?.op ?: TopicRule.Op.LT) }
    var threshold by remember { mutableStateOf(initial?.threshold?.let { TopicRule.fmt(it) } ?: "0") }
    var silence by remember { mutableStateOf(initial?.silenceS?.let { TopicRule.fmt(it) } ?: "5") }
    var severity by remember { mutableStateOf(initial?.severity ?: AlertRules.Severity.WARNING) }
    val topics = remember { RosConnection.graph()?.topics().orEmpty().sortedBy { it.name } }
    val type = topics.firstOrNull { it.name == topic }?.types?.firstOrNull()
    val fields = remember(type) { type?.let { t -> runCatching { SliderMath.numericFields(Interfaces.get().msg(t)).map { it.first } }.getOrNull() }.orEmpty() }
    val valid = topic.isNotBlank() && (kind == TopicRule.Kind.SILENCE && silence.toDoubleOrNull()?.let { it > 0 } == true ||
        kind == TopicRule.Kind.VALUE && field.isNotBlank() && threshold.toDoubleOrNull() != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (initial == null) "alerts_new_rule" else "alerts_edit_rule")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var topicsOpen by remember { mutableStateOf(false) }
                Box {
                    OutlinedTextField(topic, { topic = it.trim() }, singleLine = true, label = { Text(tr("alerts_topic")) }, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { TextButton(onClick = { topicsOpen = true }) { Text("▾") } })
                    DropdownMenu(topicsOpen, { topicsOpen = false }) {
                        for (t in topics.take(200)) DropdownMenuItem({ Text("${t.name}  ${t.types.firstOrNull().orEmpty().substringAfterLast('/')}", fontSize = 13.sp) }, { topic = t.name; topicsOpen = false })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(kind == TopicRule.Kind.VALUE, { kind = TopicRule.Kind.VALUE }, label = { Text(tr("alerts_kind_value")) })
                    FilterChip(kind == TopicRule.Kind.SILENCE, { kind = TopicRule.Kind.SILENCE }, label = { Text(tr("alerts_kind_silence")) })
                }
                if (kind == TopicRule.Kind.VALUE) {
                    var fieldsOpen by remember { mutableStateOf(false) }
                    Box {
                        OutlinedTextField(field, { field = it.trim() }, singleLine = true, label = { Text(tr("alerts_field")) }, modifier = Modifier.fillMaxWidth(),
                            trailingIcon = { if (fields.isNotEmpty()) TextButton(onClick = { fieldsOpen = true }) { Text("▾") } })
                        DropdownMenu(fieldsOpen, { fieldsOpen = false }) {
                            for (f in fields) DropdownMenuItem({ Text(f, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }, { field = f; fieldsOpen = false })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (o in TopicRule.Op.entries) FilterChip(op == o, { op = o }, label = { Text(o.symbol) })
                    }
                    OutlinedTextField(threshold, { threshold = it.trim() }, singleLine = true, label = { Text(tr("alerts_threshold")) }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(silence, { silence = it.trim() }, singleLine = true, label = { Text(tr("alerts_silence_s")) }, modifier = Modifier.fillMaxWidth())
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(severity == AlertRules.Severity.WARNING, { severity = AlertRules.Severity.WARNING }, label = { Text(tr("alerts_warning")) })
                    FilterChip(severity == AlertRules.Severity.ERROR, { severity = AlertRules.Severity.ERROR }, label = { Text(tr("alerts_error")) })
                }
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("alerts_name")) }, modifier = Modifier.fillMaxWidth())
                if (onDelete != null) OutlinedButton(onClick = onDelete) { Text(tr("alerts_delete_rule"), color = StatusColors.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave((initial ?: TopicRule(topic = topic)).copy(name = name.trim(), topic = topic, kind = kind, field = field, op = op,
                    threshold = threshold.toDoubleOrNull() ?: 0.0, silenceS = silence.toDoubleOrNull() ?: 5.0, severity = severity))
            }) { Text(tr("rviz_action_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("controllers_cancel")) } },
    )
}
