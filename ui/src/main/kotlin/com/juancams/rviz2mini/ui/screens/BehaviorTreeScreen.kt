// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.bt.BtCategory
import com.juancams.rviz2mini.bt.BtNode
import com.juancams.rviz2mini.bt.BtSource
import com.juancams.rviz2mini.bt.BtStatus
import com.juancams.rviz2mini.bt.BtTree
import com.juancams.rviz2mini.bt.Groot2
import com.juancams.rviz2mini.bt.Groot2Source
import com.juancams.rviz2mini.bt.Nav2LogSource
import com.juancams.rviz2mini.graph.TfEdge
import com.juancams.rviz2mini.graph.TfTreeLayout
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.platform.SystemClock
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.common.FileDialogs
import com.juancams.rviz2mini.ui.theme.StatusColors
import java.util.Locale
import kotlin.math.max

object BtKeys {
    const val SOURCE = "source"
    const val HOST = "host"
    const val PORT = "port"
    const val TOPIC = "topic"
    const val LOG = "log"
    const val BOARD = "blackboard"
    const val XML = "treeXml"
    const val HUMBLE = "humble"
    const val SOURCE_GROOT2 = "groot2"
    const val SOURCE_NAV2 = "nav2"
    const val DEFAULT_TOPIC = "/behavior_tree_log"

    /** "host" or "host:port" (the default port is Groot2's). */
    fun parseAddress(text: String): Pair<String, Int>? {
        val t = text.trim().removePrefix("tcp://")
        if (t.isEmpty()) return null
        val h = t.substringBeforeLast(':', t)
        val p = if (':' in t) t.substringAfterLast(':').toIntOrNull() ?: return null else Groot2.DEFAULT_PORT
        if (p !in 1..65535 || !Regex("[A-Za-z0-9.\\-]+").matches(h)) return null
        return h to p
    }
}

/**
 * State of the "Behavior tree" tab (like Groot2 in monitor mode): the tree of a
 * BT::Groot2Publisher with the live status of each node, or the nodes of Nav2's
 * `/behavior_tree_log`. Below, the tapped node (type and ports), the latest status changes or the blackboard.
 * `openXml`: pick the XML of the Nav2 tree (null = not possible).
 */
class BehaviorTreeController(val tabId: String, val openXml: ((done: (String?) -> Unit) -> Unit)? = FileDialogs.openText?.let { o -> { done -> o(listOf("xml"), done) } }) {
    private val store = TabStore.get()
    fun setting(key: String): String? = store.get(tabId)?.settings?.get(key)
    private fun saveSetting(key: String, value: String) = store.updateSetting(tabId, key, value)

    var source: BtSource? = null; private set
    /** What was last painted (changes with the source version). */
    var snap by mutableStateOf<BtSource.Snapshot?>(null); private set
    /** Bumped on every refresh (the log carries relative times). */
    var tick by mutableLongStateOf(0L); private set
    var selected by mutableStateOf<Int?>(null)
    var showLog by mutableStateOf(setting(BtKeys.LOG) == "true"); private set
    var showBoard by mutableStateOf(setting(BtKeys.BOARD) == "true" && setting(BtKeys.LOG) != "true"); private set
    var breakpoints by mutableStateOf<Set<Int>>(emptySet()); private set
    var pausedAt by mutableStateOf<Int?>(null); private set
    var stripVersion by mutableIntStateOf(0); private set
    var fitRequests by mutableIntStateOf(0)
    val collapsed = HashSet<Int>()
    var collapsedVersion by mutableIntStateOf(0); private set
    var editingSource by mutableStateOf(false)
    private var active = false
    private var closed = false

    val useNav2 get() = setting(BtKeys.SOURCE) == BtKeys.SOURCE_NAV2
    val topic get() = setting(BtKeys.TOPIC)?.takeIf { it.isNotBlank() } ?: BtKeys.DEFAULT_TOPIC
    val port get() = setting(BtKeys.PORT)?.toIntOrNull() ?: Groot2.DEFAULT_PORT

    /** The saved address, or the robot's according to the connection (bridge or first peer). */
    val host: String get() = setting(BtKeys.HOST)?.takeIf { it.isNotBlank() } ?: RosConnection.config.let { c ->
        if (c.usesBridge) c.bridgeUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        else c.peerEntries().firstOrNull().orEmpty()
    }

    init { connect() }

    fun resume() { if (active || closed) return; active = true; loop() }
    fun pause() { active = false }
    fun close() { closed = true; active = false; source?.close(); source = null }

    private fun loop() {
        if (!active || closed) return
        refresh()
        Platform.main.postDelayed({ loop() }, REFRESH_MS)
    }

    private fun connect() {
        source?.close()
        selected = null
        snap = null
        source = when {
            useNav2 -> Nav2LogSource(topic, nav2Structure(), setting(BtKeys.HUMBLE) == "true")
            host.isNotBlank() -> Groot2Source(host, port)
            else -> null
        }
        refresh()
    }

    fun refresh() {
        val s = source?.snapshot()
        if (s == null) { snap = null; return }
        if (s.version != snap?.version || snap == null) {
            if (s.tree !== snap?.tree) { collapsed.retainAll(s.tree.nodes.keys); collapsedVersion++ }
            snap = s
        }
        val g = source as? Groot2Source
        breakpoints = g?.breakpoints.orEmpty()
        pausedAt = g?.pausedAt
        tick++
    }

    /** The status line text. */
    val statusText: String get() {
        val s = snap ?: return tr("bt_no_address")
        return when {
            s.error != null && !s.connected -> tr("bt_error", "$host:$port", s.error)
            !s.connected -> if (useNav2) tr("bt_waiting_log", topic) else tr("bt_connecting", "$host:$port")
            s.tree.nodes.isEmpty() -> tr("bt_empty_tree")
            else -> pausedAt?.let { uid -> tr("bt_paused", s.tree.nodes[uid]?.name ?: "#$uid") }
                ?: tr("bt_summary", s.tree.nodes.size, s.states.values.count { it.status == BtStatus.RUNNING })
        }
    }

    val stripText: String get() { stripVersion; return if (useNav2) tr("bt_strip_nav2", topic) else tr("bt_strip_groot2", host.ifBlank { "?" }, port) }

    fun setLog(on: Boolean) {
        showLog = on
        if (on) showBoard = false
        saveSetting(BtKeys.LOG, on.toString()); saveSetting(BtKeys.BOARD, showBoard.toString())
    }

    fun setBoard(on: Boolean) {
        showBoard = on
        if (on) showLog = false
        saveSetting(BtKeys.LOG, showLog.toString()); saveSetting(BtKeys.BOARD, on.toString())
    }

    fun tapNode(uid: Int) {
        selected = uid
        if (snap?.tree?.nodes?.get(uid)?.category == BtCategory.SUBTREE) { if (!collapsed.remove(uid)) collapsed += uid; collapsedVersion++ }
    }

    fun toggleBreakpoint(uid: Int) { (source as? Groot2Source)?.toggleBreakpoint(uid); refresh() }
    fun resumeBreakpoint() { (source as? Groot2Source)?.resume(); refresh() }

    /** The tree from the XML loaded for Nav2 (null if there is none or it cannot be read). */
    fun nav2Structure(xml: String? = setting(BtKeys.XML)): BtTree? = xml?.takeIf { it.isNotBlank() }?.let { x ->
        try { Groot2.parseTree(x).takeIf { it.nodes.isNotEmpty() } } catch (_: Exception) { null }
    }

    fun setSource(kind: String, newHost: String?, newPort: Int?, newTopic: String?) {
        saveSetting(BtKeys.SOURCE, kind)
        if (newHost != null) saveSetting(BtKeys.HOST, newHost)
        if (newPort != null) saveSetting(BtKeys.PORT, newPort.toString())
        if (newTopic != null) saveSetting(BtKeys.TOPIC, newTopic)
        stripVersion++
        connect()
    }

    fun setNav2(t: String, xml: String?, humble: Boolean) {
        saveSetting(BtKeys.XML, xml.orEmpty()); saveSetting(BtKeys.HUMBLE, humble.toString()); setSource(BtKeys.SOURCE_NAV2, null, null, t)
    }

    /** The text below the tree (blackboard, log or tapped node), without colors (for the tests). */
    fun detailsText(): String = detailsAnnotated(Color.Unspecified)?.text.orEmpty()

    /** Blackboard, log or tapped node; null if there is nothing to show. `idle`: IDLE color. */
    fun detailsAnnotated(idle: Color) = run {
        val src = source ?: return@run null
        val s = snap
        fun colorOf(st: BtStatus) = if (st == BtStatus.IDLE) idle else statusColor(st)
        val node = selected?.let { s?.tree?.nodes?.get(it) }
        when {
            showBoard -> buildAnnotatedString {
                val bb = s?.blackboard.orEmpty()
                when {
                    useNav2 -> append(tr("bt_blackboard_nav2"))
                    bb.isEmpty() -> append(tr("bt_blackboard_empty"))
                    else -> for ((board, vars) in bb) {
                        if (length > 0) append('\n')
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(board) }
                        for ((k, v) in vars.toSortedMap()) append("\n  $k = ${boardValue(v)}")
                    }
                }
            }
            showLog -> buildAnnotatedString {
                val list = src.transitions.toList()
                val now = SystemClock.elapsedRealtime()
                if (list.isEmpty()) append(tr("bt_log_empty"))
                for (t in list.takeLast(LOG_LINES).asReversed()) {
                    if (length > 0) append('\n')
                    append(String.format(Locale.ROOT, "%5.1fs  %s  ", (now - t.timeMs) / 1000.0, t.name))
                    withStyle(SpanStyle(color = colorOf(t.to))) { append(t.to.name) }
                    append("  ← ${t.from.name.lowercase()}")
                }
            }
            node != null -> buildAnnotatedString {
                val st = s!!.states[node.uid]
                val state = when {
                    st == null -> BtStatus.IDLE.name
                    st.status == BtStatus.IDLE && st.previous != null -> tr("bt_status_idle_after", st.previous!!.name)
                    else -> st.status.name
                }
                append(tr("bt_node_detail", node.name, node.type, ""))
                withStyle(SpanStyle(color = colorOf(st?.status?.takeIf { it != BtStatus.IDLE } ?: st?.previous ?: BtStatus.IDLE))) { append(state) }
                for ((k, v) in node.attributes) append("\n  $k = $v")
            }
            else -> null
        }
    }

    private fun boardValue(v: Any?): String = when (v) {
        null -> "—"
        is Double -> if (v == Math.rint(v) && Math.abs(v) < 1e15) String.format(Locale.ROOT, "%.1f", v) else String.format(Locale.ROOT, "%.6g", v)
        is String -> "\"$v\""
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${it.key}: ${boardValue(it.value)}" }
        is List<*> -> v.joinToString(", ", "[", "]") { boardValue(it) }
        else -> v.toString()
    }

    companion object {
        private const val REFRESH_MS = 100L
        private const val LOG_LINES = 8

        fun statusColor(s: BtStatus): Color = when (s) {
            BtStatus.RUNNING -> StatusColors.warn
            BtStatus.SUCCESS -> StatusColors.ok
            BtStatus.FAILURE -> StatusColors.error
            BtStatus.SKIPPED -> StatusColors.idle
            BtStatus.IDLE -> Color(0xFF79747E)
        }
    }
}

/** The tab with its own controller (desktop, dashboards). */
@Composable
fun BehaviorTreeScreen(tabId: String, modifier: Modifier = Modifier) {
    val c = remember(tabId) { BehaviorTreeController(tabId) }
    DisposableEffect(c) { c.resume(); onDispose { c.close() } }
    BehaviorTreeView(c, modifier)
}

@Composable
fun BehaviorTreeView(c: BehaviorTreeController, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { c.editingSource = true }, modifier = Modifier.weight(1f)) { Text(c.stripText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val on = MaterialTheme.colorScheme.primary; val off = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(onClick = { c.setLog(!c.showLog) }) { Icon(Icons.AutoMirrored.Filled.MenuBook, tr("bt_log"), tint = if (c.showLog) on else off) }
                IconButton(onClick = { c.setBoard(!c.showBoard) }) { Icon(Icons.Default.DataObject, tr("bt_blackboard"), tint = if (c.showBoard) on else off) }
                IconButton(onClick = { c.fitRequests++ }) { Icon(Icons.Default.CenterFocusStrong, tr("bt_fit"), tint = off) }
            }
        }
        Text(c.statusText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        BtTreeCanvas(c, Modifier.weight(1f))
        c.tick
        c.detailsAnnotated(MaterialTheme.colorScheme.onSurfaceVariant)?.let { text ->
            SelectionContainer(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        // Breakpoints (Groot2 only): set or clear one on the tapped node, and resume when it stops.
        val sel = c.selected
        if (c.source is Groot2Source && (sel != null || c.breakpoints.isNotEmpty())) Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { sel?.let(c::toggleBreakpoint) }, enabled = sel != null, modifier = Modifier.weight(1f)) {
                Text(tr(if (sel != null && sel in c.breakpoints) "bt_breakpoint_remove" else "bt_breakpoint_add"))
            }
            Button(onClick = { c.resumeBreakpoint() }, enabled = c.pausedAt != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, null); Text(tr("bt_resume"))
            }
        }
    }
    if (c.editingSource) BtSourceDialog(c)
}

/**
 * Top-down tree, Groot2 style: each node is a box with a stripe in its category color, the
 * name and the type, and a border in its status color. Those back to IDLE keep their last result
 * dimmed. A tap on a SubTree collapses or expands it; without a tree shape (the Nav2 log) they go in a column.
 */
@Composable
private fun BtTreeCanvas(c: BehaviorTreeController, modifier: Modifier) {
    val tm = rememberTextMeasurer(128)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val nameStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = onSurface)
    val typeStyle = TextStyle(fontSize = 10.sp, color = onSurface.copy(alpha = 0xA8 / 255f))
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val snap = c.snap
    val tree = snap?.tree ?: BtTree.EMPTY
    val states = snap?.states.orEmpty()
    val cv = c.collapsedVersion

    // Layout: only when the tree or the collapsed set changes.
    val placed = remember(tree, cv) {
        val index = HashMap<String, Int>()
        tree.nodes.keys.forEachIndexed { i, uid -> index[uid.toString()] = i }
        val visible = ArrayList<BtNode>()
        val hidden = HashMap<Int, Int>()
        fun countBelow(uid: Int): Int = tree.nodes[uid]?.children?.sumOf { 1 + countBelow(it) } ?: 0
        fun walk(uid: Int) {
            val n = tree.nodes[uid] ?: return
            visible += n
            if (uid in c.collapsed && n.children.isNotEmpty()) { hidden[uid] = countBelow(uid); return }
            for (ch in n.children) walk(ch)
        }
        for (r in tree.roots) walk(r)
        val flat = visible.size > 1 && visible.all { it.parent == null }
        fun sub(n: BtNode) = subtitle(n, flat, hidden, emptyMap())
        val measure = { id: String ->
            val n = tree.nodes[id.toInt()]
            if (n == null) 0f else max(tm.measure(n.name, nameStyle).size.width.toFloat(), tm.measure(if (flat) "IDLE · SUCCESS" else sub(n), typeStyle).size.width.toFloat()) + 24f
        }
        val boxH = 46f
        val result = if (flat) {
            val w = visible.maxOf { max(64f, measure(it.uid.toString())) }
            val gap = 8f
            TfTreeLayout.Result(visible.mapIndexed { i, n -> TfTreeLayout.Box(n.uid.toString(), null, 0f, i * (boxH + gap), w, boxH, 0, false, null) }, w, visible.size * (boxH + gap) - gap)
        } else TfTreeLayout(measure, boxH, 64f, 12f, 30f, 36f, compareBy { index[it] ?: Int.MAX_VALUE })
            .layout(visible.map { TfEdge(it.uid.toString(), it.parent?.toString()) })
        Triple(result, flat, hidden)
    }
    val (result, flat, hidden) = placed
    val selected = c.selected; val bps = c.breakpoints; val paused = c.pausedAt

    PanZoomCanvas(result.width, result.height, modifier.fillMaxWidth(), onTap = { p ->
        val hit = result.boxes.lastOrNull { it.contains(p.x, p.y) } ?: return@PanZoomCanvas
        c.tapNode(hit.id.toInt())
    }, fitRequests = c.fitRequests) { scale ->
        val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 3f), 0f)
        // Edges: from the parent's bottom edge to the child's top edge, in the child's color if it is active.
        for (b in result.boxes) {
            val p = result.byId[b.parent ?: continue] ?: continue
            val s = states[b.id.toInt()]
            val col = if (s != null && s.status != BtStatus.IDLE) BehaviorTreeController.statusColor(s.status) else onSurface.copy(alpha = 0x70 / 255f)
            val my = (p.bottom + b.y) / 2
            drawPath(Path().apply { moveTo(p.cx, p.bottom); cubicTo(p.cx, my, b.cx, my, b.cx, b.y) }, col, style = Stroke(1.5f))
        }
        val corner = CornerRadius(8f); val stripe = 5f
        for (b in result.boxes) {
            val n = tree.nodes[b.id.toInt()] ?: continue
            val s = states[n.uid]
            val active = s != null && s.status != BtStatus.IDLE
            val last = s?.previous
            val tint = when {
                active -> BehaviorTreeController.statusColor(s!!.status).copy(alpha = 0x38 / 255f)
                last != null -> BehaviorTreeController.statusColor(last).copy(alpha = 0x16 / 255f)
                else -> Color.Transparent
            }
            val tl = Offset(b.x, b.y); val sz = Size(b.w, b.h)
            drawRoundRect(tint.compositeOver(surfaceHigh), tl, sz, corner)
            // Category stripe on the left.
            clipRect(b.x, b.y, b.x + stripe, b.bottom) { drawRoundRect(categoryColor(n.category, primary), tl, sz, corner) }
            var bc: Color; var bw: Float
            var pe = if (n.category == BtCategory.SUBTREE && n.uid in c.collapsed) dash else null
            when {
                active -> { bc = BehaviorTreeController.statusColor(s!!.status); bw = 3f }
                last != null -> { bc = BehaviorTreeController.statusColor(last).copy(alpha = 0x90 / 255f); bw = 1.5f }
                else -> { bc = outline; bw = 1f }
            }
            if (n.uid == selected) { bc = primary; bw = 3f }
            if (n.uid == paused) { bc = StatusColors.error; bw = 4f; pe = dash }
            drawRoundRect(bc, tl, sz, corner, style = Stroke(bw, pathEffect = pe))
            if (n.uid in bps) drawCircle(StatusColors.error, 4.5f, Offset(b.right - 6f, b.y + 6f))
            if (13f * scale < 4f) continue
            val cx = b.cx + stripe / 2
            label(tm, n.name, cx, b.y + b.h * 0.36f, nameStyle)
            label(tm, subtitle(n, flat, hidden, states), cx, b.y + b.h * 0.70f, typeStyle)
        }
    }
}

private fun subtitle(n: BtNode, flat: Boolean, hidden: Map<Int, Int>, states: Map<Int, com.juancams.rviz2mini.bt.BtNodeState>): String = when {
    // Without a tree shape the category is not known either: better the status.
    flat -> states[n.uid]?.let { s -> if (s.status == BtStatus.IDLE && s.previous != null) "IDLE · ${s.previous!!.name}" else s.status.name } ?: BtStatus.IDLE.name
    n.category == BtCategory.SUBTREE -> "SubTree" + (hidden[n.uid]?.let { " · +$it" } ?: "")
    n.name != n.type -> n.type
    else -> when (n.category) {
        BtCategory.CONTROL -> "Control"; BtCategory.DECORATOR -> "Decorator"; BtCategory.ACTION -> "Action"
        BtCategory.CONDITION -> "Condition"; BtCategory.SUBTREE -> "SubTree"
    }
}

private fun categoryColor(c: BtCategory, primary: Color): Color = when (c) {
    BtCategory.CONTROL -> primary
    BtCategory.DECORATOR -> Color(0xFF8E5CD9)
    BtCategory.ACTION -> Color(0xFF5C8DD9)
    BtCategory.CONDITION -> Color(0xFFD9A42B)
    BtCategory.SUBTREE -> Color(0xFF7A7A7A)
}

/** Pick the source: Groot2 (address and port) or the Nav2 log topic (with its XML and whether it is Humble). */
@Composable
private fun BtSourceDialog(c: BehaviorTreeController) {
    var nav2 by remember { mutableStateOf(c.useNav2) }
    var address by remember { mutableStateOf(c.host.let { h -> if (h.isBlank()) "" else if (c.port == Groot2.DEFAULT_PORT) h else "$h:${c.port}" }) }
    var topic by remember { mutableStateOf(c.topic) }
    var xml by remember { mutableStateOf(c.setting(BtKeys.XML).orEmpty()) }
    var humble by remember { mutableStateOf(c.setting(BtKeys.HUMBLE) == "true") }
    var addressError by remember { mutableStateOf(false) }
    var topicError by remember { mutableStateOf(false) }
    var xmlError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { c.editingSource = false },
        title = { Text(tr("bt_source_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { nav2 = false }) { RadioButton(!nav2, { nav2 = false }); Text(tr("bt_source_groot2")) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { nav2 = true }) { RadioButton(nav2, { nav2 = true }); Text(tr("bt_source_nav2")) }
                if (!nav2) OutlinedTextField(address, { address = it; addressError = false }, singleLine = true, label = { Text(tr("bt_address")) },
                    isError = addressError, supportingText = if (addressError) { { Text(tr("bt_bad_address")) } } else null, modifier = Modifier.fillMaxWidth())
                else {
                    OutlinedTextField(topic, { topic = it; topicError = false }, singleLine = true, label = { Text(tr("bt_topic")) }, isError = topicError, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.nav2Structure(xml)?.let { tr("bt_xml_loaded", it.nodes.size) } ?: tr(if (xmlError) "bt_xml_bad" else "bt_xml_none"), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        val open = c.openXml
                        if (open != null) TextButton(onClick = {
                            open { text -> Platform.main.post {
                                if (text != null && c.nav2Structure(text) != null) { xml = text; xmlError = false } else if (text != null) xmlError = true
                            } }
                        }) { Text(tr("bt_xml_load")) }
                        TextButton(onClick = { xml = "" }) { Text(tr("bt_xml_clear")) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { humble = !humble }) { Checkbox(humble, { humble = it }); Text(tr("bt_humble")) }
                }
                Text(tr(if (nav2) "bt_source_nav2_hint" else "bt_source_groot2_hint"), fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (nav2) {
                    val name = DynRos.normalizeName(topic)
                    if (!Regex("/[A-Za-z0-9_~{}/]*").matches(name) || name == "/") { topicError = true; return@TextButton }
                    c.setNav2(name, xml, humble)
                } else {
                    val parsed = BtKeys.parseAddress(address) ?: run { addressError = true; return@TextButton }
                    c.setSource(BtKeys.SOURCE_GROOT2, parsed.first, parsed.second, null)
                }
                c.editingSource = false
            }) { Text(tr("buttons_editor_save")) }
        },
        dismissButton = { TextButton(onClick = { c.editingSource = false }) { Text(tr("buttons_cancel")) } },
    )
}
