// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.plansys2.ExecutionEvent
import com.juancams.rviz2mini.plansys2.ExecutionInfo
import com.juancams.rviz2mini.plansys2.HubMessage
import com.juancams.rviz2mini.plansys2.Knowledge
import com.juancams.rviz2mini.plansys2.Performer
import com.juancams.rviz2mini.plansys2.PlanSys2
import com.juancams.rviz2mini.plansys2.PlanSys2Monitor
import com.juancams.rviz2mini.plansys2.PlanSys2State
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.theme.StatusColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The views of the PlanSys2 tab: the four boxes together, as in the terminal monitor, or each one on its own. */
enum class PlanSys2View(val labelKey: String) {
    OVERVIEW("ps2_overview"), PLAN("ps2_plan"), ACTIONS("ps2_actions"), PERFORMERS("ps2_performers"),
    KNOWLEDGE("ps2_knowledge"), LOG("ps2_log"),
}

/** The tab's settings (view and namespace) and its [PlanSys2Monitor]. */
class PlanSys2Controller(val tabId: String) {
    private val store = TabStore.get()
    private fun setting(k: String) = store.get(tabId)?.settings?.get(k)

    var view by mutableStateOf(PlanSys2View.entries.firstOrNull { it.name == setting(KEY_VIEW) } ?: PlanSys2View.OVERVIEW); private set
    var namespace by mutableStateOf(setting(KEY_NAMESPACE).orEmpty()); private set
    var follow by mutableStateOf(true)
    var state by mutableStateOf(PlanSys2State()); private set
    private var monitor: PlanSys2Monitor? = null
    private var seen = -1L

    fun start() { if (monitor == null) monitor = PlanSys2Monitor(namespace).also { it.start() }; seen = -1 }
    fun stop() { monitor?.close(); monitor = null }

    /** Takes the monitor's state if it changed (the screen calls it a few times a second). */
    fun refresh() {
        val m = monitor ?: return
        if (m.version != seen) { seen = m.version; state = m.state }
    }

    fun show(v: PlanSys2View) { view = v; store.updateSetting(tabId, KEY_VIEW, v.name) }

    fun updateNamespace(ns: String) {
        val clean = ns.trim()
        if (clean == namespace) return
        namespace = clean
        store.updateSetting(tabId, KEY_NAMESPACE, clean.ifEmpty { null })
        val running = monitor != null
        stop(); state = PlanSys2State()
        if (running) start()
    }

    fun clearLog() { monitor?.clearTrace(); refresh() }

    companion object {
        const val KEY_VIEW = "view"
        const val KEY_NAMESPACE = "namespace"
    }
}

/**
 * PlanSys2 (same on the phone and on the PC): like its terminal monitor, the plan being executed with how each action
 * goes, the actions under way, the performers, what the problem expert knows (instances, predicates, functions, goal)
 * and the plan log, what the executor does and why. Together in four boxes or each one on its own.
 */
@Composable
fun PlanSys2Screen(tabId: String, modifier: Modifier = Modifier) {
    val c = remember(tabId) { PlanSys2Controller(tabId) }
    DisposableEffect(c) { c.start(); onDispose { c.stop() } }
    LaunchedEffect(c) { while (true) { c.refresh(); delay(200) } }
    val s = c.state

    Column(modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            for (v in PlanSys2View.entries) FilterChip(c.view == v, { c.show(v) }, label = { Text(tr(v.labelKey)) })
            Spacer(Modifier.width(6.dp))
            NamespaceField(c)
        }
        if (!s.anything) Text(tr("ps2_nothing", PlanSys2.topic(c.namespace, "")), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (c.view) {
                PlanSys2View.OVERVIEW -> Overview(c, s)
                PlanSys2View.PLAN -> Panel(PlanSys2View.PLAN, plansTopics(c), null) { PlanList(s, full = true) }
                PlanSys2View.ACTIONS -> Panel(PlanSys2View.ACTIONS, topic(c, PlanSys2.HUB_TOPIC), null) { ActionsList(s) }
                PlanSys2View.PERFORMERS -> Panel(PlanSys2View.PERFORMERS, topic(c, PlanSys2.PERFORMERS_TOPIC), null) { PerformersList(s) }
                PlanSys2View.KNOWLEDGE -> Panel(PlanSys2View.KNOWLEDGE, topic(c, PlanSys2.KNOWLEDGE_TOPIC), null) { KnowledgeList(s.knowledge, filterable = true) }
                PlanSys2View.LOG -> Panel(PlanSys2View.LOG, topic(c, PlanSys2.TRACE_TOPIC), null) { LogList(c, s, full = true) }
            }
        }
    }
}

private fun topic(c: PlanSys2Controller, name: String) = PlanSys2.topic(c.namespace, name)
private fun plansTopics(c: PlanSys2Controller) = topic(c, PlanSys2.PLAN_TOPIC) + " + " + PlanSys2.INFO_TOPIC

@Composable
private fun NamespaceField(c: PlanSys2Controller) {
    var text by remember(c.namespace) { mutableStateOf(c.namespace) }
    OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.width(190.dp).heightIn(min = 48.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        label = { Text(tr("ps2_namespace"), fontSize = 11.sp) }, placeholder = { Text("/", fontSize = 12.sp) },
        trailingIcon = { if (text.trim() != c.namespace) TextButton(onClick = { c.updateNamespace(text) }) { Text("OK", fontSize = 12.sp) } })
}

/** The four boxes of the terminal monitor: two columns if there is room, one under the other if not. */
@Composable
private fun Overview(c: PlanSys2Controller, s: PlanSys2State) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxes: List<Pair<PlanSys2View, @Composable () -> Unit>> = listOf(
            PlanSys2View.ACTIONS to { Panel(PlanSys2View.ACTIONS, topic(c, PlanSys2.HUB_TOPIC), { c.show(PlanSys2View.ACTIONS) }) { ActionsList(s) } },
            PlanSys2View.PLAN to { Panel(PlanSys2View.PLAN, plansTopics(c), { c.show(PlanSys2View.PLAN) }) { PlanList(s, full = false) } },
            PlanSys2View.PERFORMERS to { Panel(PlanSys2View.PERFORMERS, topic(c, PlanSys2.PERFORMERS_TOPIC), { c.show(PlanSys2View.PERFORMERS) }) { PerformersList(s) } },
            PlanSys2View.KNOWLEDGE to { Panel(PlanSys2View.KNOWLEDGE, topic(c, PlanSys2.KNOWLEDGE_TOPIC), { c.show(PlanSys2View.KNOWLEDGE) }) { KnowledgeList(s.knowledge, filterable = false) } },
        )
        if (maxWidth >= 640.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in listOf(listOf(boxes[0], boxes[2]), listOf(boxes[1], boxes[3]))) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((_, box) in col) Box(Modifier.weight(1f).fillMaxWidth()) { box() }
                    }
                }
            }
        } else {
            // On a phone: the plan first, each box with the height it can be read at.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((_, box) in listOf(boxes[1], boxes[0], boxes[2], boxes[3])) Box(Modifier.weight(1f).fillMaxWidth()) { box() }
            }
        }
    }
}

/** A box with its title and topic; `open`: the title opens it on its own. */
@Composable
private fun Panel(view: PlanSys2View, topic: String, open: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable(onClick = open) else Modifier).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr(view.labelKey), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("  [$topic]", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (open != null) Icon(Icons.AutoMirrored.Filled.OpenInNew, tr("ps2_open", tr(view.labelKey)), Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, bottom = 8.dp), content = content)
    }
}

@Composable
private fun Waiting(key: String) = Text(tr(key), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ---------------------------------------------------------------- plan

private fun statusColor(status: Int?): Color = when (status) {
    ExecutionInfo.EXECUTING -> StatusColors.warn
    ExecutionInfo.SUCCEEDED -> StatusColors.ok
    ExecutionInfo.FAILED, ExecutionInfo.CANCELLED -> StatusColors.error
    else -> StatusColors.idle
}

private fun statusLabel(status: Int?): String = tr(when (status) {
    ExecutionInfo.NOT_EXECUTED -> "ps2_st_not_executed"
    ExecutionInfo.EXECUTING -> "ps2_st_executing"
    ExecutionInfo.FAILED -> "ps2_st_failed"
    ExecutionInfo.SUCCEEDED -> "ps2_st_succeeded"
    ExecutionInfo.CANCELLED -> "ps2_st_cancelled"
    else -> "ps2_st_pending"
})

private fun fmt(d: Double) = "%.3f".format(Locale.ROOT, d)

/** The plan in execution order, each action with its state and progress. `full`: with its times and what the executor says. */
@Composable
private fun PlanList(s: PlanSys2State, full: Boolean) {
    val plan = s.plan ?: return Waiting("ps2_waiting_plan")
    if (plan.items.isEmpty()) return Waiting("ps2_plan_empty")
    val rows = s.rows
    val done = rows.count { it.info?.status == ExecutionInfo.SUCCEEDED }
    Text(tr("ps2_plan_summary", done, rows.size) + (plan.cost?.let { " · " + tr("ps2_cost", fmt(it)) } ?: ""),
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Narrow (a phone, a widget): the action under its state, so that it can be read whole.
    val stacked = full && maxWidth < 480.dp
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (full) 6.dp else 3.dp)) {
        items(rows) { r ->
            val st = r.info?.status
            val color = statusColor(st)
            val progress = (r.info?.completion ?: 0.0).toFloat().coerceIn(0f, 1f)
            @Composable fun action(m: Modifier) = Text(r.item.action, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (st == ExecutionInfo.EXECUTING) FontWeight.Bold else FontWeight.Normal,
                color = if (st == null || st == ExecutionInfo.NOT_EXECUTED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = if (full) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = m)
            Column(Modifier.fillMaxWidth().semantics { contentDescription = "plan ${r.item.action} ${statusLabel(st)}" }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(statusLabel(st), color)
                    Spacer(Modifier.width(6.dp))
                    if (full) Text(fmt(r.item.time) + "  ", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (stacked) Spacer(Modifier.weight(1f)) else action(Modifier.weight(1f))
                    Text("${(progress * 100).toInt()}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                if (stacked) action(Modifier.padding(top = 2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { progress }, color = color, trackColor = color.copy(alpha = 0.18f),
                        modifier = Modifier.weight(1f).padding(top = 2.dp).height(if (full) 5.dp else 3.dp))
                    if (full) Text("  [${fmt(r.item.duration)} s]", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val msg = r.info?.message.orEmpty()
                if (full && msg.isNotBlank()) Text(msg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

// ---------------------------------------------------------------- actions and performers

private fun performerColor(state: Int) = when (state) {
    Performer.READY -> StatusColors.ok
    Performer.RUNNING -> StatusColors.warn
    Performer.FAILURE -> StatusColors.error
    else -> StatusColors.idle
}

private fun performerLabel(state: Int) = tr(when (state) {
    Performer.READY -> "ps2_perf_ready"
    Performer.RUNNING -> "ps2_perf_running"
    Performer.FAILURE -> "ps2_perf_failure"
    else -> "ps2_perf_not_ready"
})

private fun hubColor(type: Int) = when (type) {
    HubMessage.REJECT, HubMessage.CANCEL -> StatusColors.error
    HubMessage.FEEDBACK -> StatusColors.warn
    HubMessage.FINISH, HubMessage.CONFIRM -> StatusColors.ok
    else -> Color(0xFF1E88E5)
}

/** The actions under way: who does them, the last message on actions_hub and, with feedback, how far they go. */
@Composable
private fun ActionsList(s: PlanSys2State) {
    // As the terminal monitor: by performer; the ones running with their action.
    val running = s.active.values.filter { it.nodeId.isNotEmpty() }.sortedBy { it.nodeId }
    if (running.isEmpty()) return Waiting(if (s.performers.isEmpty()) "ps2_waiting_performers" else "ps2_no_actions")
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(running, key = { it.nodeId }) { m ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.nodeId, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusPill(HubMessage.LABELS[m.type] ?: m.type.toString(), hubColor(m.type))
                }
                Text("(${(listOf(m.action) + m.arguments).joinToString(" ")})", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                if (m.type == HubMessage.FEEDBACK || m.completion > 0) {
                    val p = m.completion.toFloat().coerceIn(0f, 1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { p }, color = StatusColors.warn, trackColor = StatusColors.warn.copy(alpha = 0.18f),
                            modifier = Modifier.weight(1f).height(4.dp))
                        Text("  ${(p * 100).toInt()}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (m.type == HubMessage.RESPONSE) Text(tr(if (m.success) "ps2_result_ok" else "ps2_result_fail"), fontSize = 12.sp,
                    color = if (m.success) StatusColors.ok else StatusColors.error)
                if (m.status.isNotBlank()) Text(m.status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PerformersList(s: PlanSys2State) {
    if (s.performers.isEmpty()) return Waiting("ps2_waiting_performers")
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(s.performers.values.sortedBy { it.node }, key = { it.node }) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(performerColor(p.state), CircleShape))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(p.node, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val action = if (p.action.isEmpty()) "—" else (listOf(p.action) + p.arguments).joinToString(" ")
                    Text(action, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(performerLabel(p.state), performerColor(p.state))
            }
        }
    }
}

// ---------------------------------------------------------------- knowledge

/** "(and (cleaned kitchen) (not (busy r1)))" -> the predicates it names: "(cleaned kitchen)", "(busy r1)". */
internal fun goalAtoms(goal: String): List<String> =
    Regex("""\(([^()\s]+)((?:\s+[^()\s]+)*)\s*\)""").findAll(goal).map { it.value.replace(Regex("""\s+"""), " ") }
        .filter { a -> a.removePrefix("(").substringBefore(' ').substringBefore(')') !in setOf("and", "or", "not", "imply", "total-time") }.toList()

/** Instances, predicates, functions, goal (each of its predicates ticked if it holds now), metric and constraints. */
@Composable
private fun KnowledgeList(k: Knowledge?, filterable: Boolean) {
    if (k == null) return Waiting("ps2_waiting_knowledge")
    var filter by remember { mutableStateOf("") }
    if (filterable) OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text(tr("ps2_filter")) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
    val f = filter.trim()
    fun List<String>.matching() = if (f.isEmpty()) this else filter { it.contains(f, ignoreCase = true) }
    val holds = k.predicates.map { it.replace(Regex("""\s+"""), " ") }.toSet()
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize()) {
            section("ps2_instances", k.instances.matching(), Color(0xFF00ACC1))
            section("ps2_predicates", k.predicates.matching(), StatusColors.ok)
            section("ps2_functions", k.functions.matching(), Color(0xFFF9A825))
            item { Heading("ps2_goal") }
            if (k.goal.isBlank()) item { Waiting("ps2_none") } else {
                item { Text(k.goal, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFAB47BC), modifier = Modifier.padding(start = 8.dp)) }
                val atoms = goalAtoms(k.goal).distinct()
                items(atoms) { a ->
                    val ok = a in holds
                    Text((if (ok) "✓ " else "○ ") + a, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = if (ok) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
                }
            }
            if (k.metric.isNotBlank()) { item { Heading("ps2_metric") }; item { Mono(k.metric, Color(0xFF1E88E5)) } }
            if (k.constraints.isNotBlank()) { item { Heading("ps2_constraints") }; item { Mono(k.constraints, StatusColors.error) } }
        }
    }
}

private fun LazyListScope.section(key: String, lines: List<String>, color: Color) {
    item { Heading(key, lines.size) }
    if (lines.isEmpty()) item { Waiting("ps2_none") } else items(lines) { Mono(it, color) }
}

@Composable
private fun Heading(key: String, count: Int? = null) =
    Text(tr(key) + (count?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))

@Composable
private fun Mono(text: String, color: Color) = Text(text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = color, modifier = Modifier.padding(start = 8.dp))

// ---------------------------------------------------------------- plan log

/** What the executor did and why, one sentence per event, as plansys2_tui's plan log (the newest at the bottom). */
@Composable
private fun LogList(c: PlanSys2Controller, s: PlanSys2State, full: Boolean) {
    if (full) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("ps2_follow"), fontSize = 12.sp)
        Switch(c.follow, { c.follow = it }, Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { c.clearLog() }) { Text(tr("ps2_clear")) }
    }
    if (s.trace.isEmpty()) return Waiting("ps2_waiting_log")
    val list = rememberLazyListState()
    LaunchedEffect(s.trace.size, c.follow) { if (c.follow && s.trace.isNotEmpty()) list.scrollToItem(s.trace.size - 1) }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(s.trace) { e -> Text(sentence(e, clock), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp)) }
        }
    }
}

private val CYAN = Color(0xFF00ACC1)
private val BLUE = Color(0xFF1E88E5)
private val MAGENTA = Color(0xFFAB47BC)
private val DIM = Color(0xFF9E9E9E)

/** The event as plansys2_tui writes it (in English, like the details the executor gives). */
internal fun sentence(e: ExecutionEvent, clock: SimpleDateFormat): AnnotatedString = buildAnnotatedString {
    val action = PlanSys2.actionName(e.action)
    val detail = PlanSys2.withoutIds(e.detail)
    fun stamp() = withStyle(SpanStyle(color = DIM)) { append("%9.3f  ".format(Locale.ROOT, e.planTime)) }
    fun act() = withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(action) }
    fun styled(color: Color, bold: Boolean = false, text: String) = withStyle(SpanStyle(color = color, fontWeight = if (bold) FontWeight.Bold else null)) { append(text) }
    fun timing() {
        val late = PlanSys2.lateness(e)
        when {
            late == 0.0 -> styled(StatusColors.ok, text = "on time")
            late > 0 -> styled(StatusColors.warn, text = "${fmt(late)} s late")
            else -> styled(CYAN, text = "${fmt(-late)} s early")
        }
        styled(DIM, text = " (planned at ${fmt(e.plannedTime)}" + (if (e.shift > 1e-3) ", the plan ${fmt(e.shift)} s later" else "") + ")")
    }
    when (e.type) {
        ExecutionEvent.PLAN_START -> styled(CYAN, true, "━━ ${clock.format(Date(e.stampMs))} Plan starts: $detail")
        ExecutionEvent.PLAN_END -> { stamp(); styled(if (e.detail == "succeeded") StatusColors.ok else StatusColors.error, true, "━━ The plan ends: $detail") }
        ExecutionEvent.WAIT -> { stamp(); act(); styled(DIM, text = if (e.detail.startsWith("its ")) ": $detail" else " waits $detail") }
        ExecutionEvent.PERFORM -> { stamp(); act(); append(" is performed $detail") }
        ExecutionEvent.START -> { stamp(); act(); styled(StatusColors.ok, text = " starts "); timing() }
        ExecutionEvent.END -> { stamp(); act(); styled(BLUE, text = " ends "); timing() }
        ExecutionEvent.SHIFT -> { stamp(); act(); styled(MAGENTA, text = ": the rest of the plan goes $detail") }
        ExecutionEvent.FAILURE -> { stamp(); if (e.action.isNotEmpty()) { act(); append(" ") } else append("The plan "); styled(StatusColors.error, true, "fails: $detail") }
        ExecutionEvent.VIOLATION -> { stamp(); styled(StatusColors.error, true, "A constraint is violated: $detail") }
        ExecutionEvent.REPLAN -> { stamp(); styled(StatusColors.warn, true, "A new plan replaces the running one: $detail") }
        else -> { stamp(); act(); append(" event ${e.type}: $detail") }
    }
}
