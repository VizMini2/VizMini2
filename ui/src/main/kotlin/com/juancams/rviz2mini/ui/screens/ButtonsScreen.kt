// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.buttons.ButtonKind
import com.juancams.rviz2mini.buttons.ButtonList
import com.juancams.rviz2mini.buttons.ButtonRunner
import com.juancams.rviz2mini.buttons.ButtonSpec
import com.juancams.rviz2mini.buttons.ButtonState
import com.juancams.rviz2mini.diag.MessageCatalog
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosGraph
import com.juancams.rviz2mini.ros.dyn.IdlException
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.common.MessageFormState
import com.juancams.rviz2mini.ui.common.MessageFormView
import com.juancams.rviz2mini.ui.theme.StatusColors
import org.json.JSONObject
import java.util.UUID

/** Key of the Buttons tab (the same as before on Android). */
const val BUTTONS_KEY = "buttons"

/**
 * The buttons of a tab and their executors, outside Compose (the tests use it): add, edit, delete,
 * press and release, and the state of each one.
 */
class ButtonsController(val tabId: String) {
    private val store = TabStore.get()
    val registry: InterfaceRegistry = Interfaces.get()
    var buttons by mutableStateOf(ButtonList.fromJson(store.get(tabId)?.settings?.get(BUTTONS_KEY))); private set
    val states = mutableStateMapOf<String, ButtonState>()
    /** Incremented when an executor changes something that is not its state (action running, setup error). */
    var tick by mutableStateOf(0); private set
    val runners = HashMap<String, ButtonRunner>()
    /** Open the editor from outside (the tests): the button, or a new one with `editNew`. */
    var editRequest by mutableStateOf<ButtonSpec?>(null)
    var editNew by mutableStateOf(false)
    fun requestEdit(id: String?) { editNew = id == null; editRequest = buttons.firstOrNull { it.id == id } ?: ButtonSpec() }
    /** Each increment closes the open editor. */
    var closeEditorRequests by mutableStateOf(0)

    init { for (b in buttons) runners[b.id] = newRunner(b) }

    private fun newRunner(b: ButtonSpec) = ButtonRunner(b, registry) { s -> states[b.id] = s; tick++ }

    private fun save(list: List<ButtonSpec>) { buttons = list; store.updateSetting(tabId, BUTTONS_KEY, ButtonList.toJson(list)) }

    fun upsert(b: ButtonSpec) {
        runners.remove(b.id)?.close()
        states.remove(b.id)
        Interfaces.importMissing(b.definitions)
        val i = buttons.indexOfFirst { it.id == b.id }
        save(if (i >= 0) buttons.toMutableList().also { it[i] = b } else buttons + b)
        runners[b.id] = newRunner(b)
    }

    fun delete(id: String) {
        runners.remove(id)?.close(); states.remove(id)
        save(buttons.filter { it.id != id })
    }

    fun move(id: String, delta: Int) = save(ButtonList.move(buttons, id, delta))

    fun close() { for (r in runners.values) r.close(); runners.clear() }
}

/** Buttons tab: a grid of buttons that publish, call a service or send a goal. */
@Composable
fun ButtonsScreen(tabId: String, modifier: Modifier = Modifier, widget: Boolean = false, addRequests: Int = 0, onController: (ButtonsController?) -> Unit = {}) {
    val controller = remember(tabId) { ButtonsController(tabId) }
    DisposableEffect(controller) { onController(controller); onDispose { onController(null); controller.close() } }
    var editing by remember { mutableStateOf<ButtonSpec?>(null) }
    var editingNew by remember { mutableStateOf(false) }
    controller.editRequest?.let { req -> editing = req; editingNew = controller.editNew; controller.editRequest = null }
    var closedAt by remember { mutableStateOf(controller.closeEditorRequests) }
    if (controller.closeEditorRequests != closedAt) { closedAt = controller.closeEditorRequests; editing = null }
    var lastAdd by remember { mutableStateOf(addRequests) }
    if (addRequests != lastAdd) { lastAdd = addRequests; editing = ButtonSpec(); editingNew = true }
    var confirm by remember { mutableStateOf<ButtonSpec?>(null) }
    var cancelGoal by remember { mutableStateOf<ButtonSpec?>(null) }
    var detail by remember { mutableStateOf<Pair<ButtonSpec, String>?>(null) }
    var deleting by remember { mutableStateOf<ButtonSpec?>(null) }
    @Suppress("UNUSED_VARIABLE") val tick = controller.tick

    fun press(b: ButtonSpec) {
        val r = controller.runners[b.id] ?: return
        when {
            r.actionRunning -> cancelGoal = b
            b.confirm -> confirm = b
            else -> r.press()
        }
    }

    Column(modifier.fillMaxSize().padding(if (widget) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!widget) Button(onClick = { editing = ButtonSpec(); editingNew = true }) { Text(tr("buttons_add")) }
        if (controller.buttons.isEmpty()) Text(tr(if (widget) "buttons_empty_widget" else "buttons_empty"), modifier = Modifier.padding(16.dp)
            .let { if (widget) it.clickable { editing = ButtonSpec(); editingNew = true } else it })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = (maxWidth.value / 170f).toInt().coerceIn(1, 6)
            LazyVerticalGrid(GridCells.Fixed(columns), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(controller.buttons, key = { it.id }) { b ->
                    ButtonCell(b, controller, onPress = { press(b) },
                        onDetail = { text -> detail = b to text },
                        onEdit = { editing = b; editingNew = false },
                        onDuplicate = { controller.upsert(b.copy(id = UUID.randomUUID().toString(), label = tr("buttons_copy_label", b.label.ifBlank { b.displayLabel }))) },
                        onMove = { controller.move(b.id, it) }, onDelete = { deleting = b })
                }
            }
        }
    }

    editing?.let { b -> ButtonEditorDialog(controller.registry, b, editingNew, onDismiss = { editing = null }) { editing = null; controller.upsert(it) } }
    confirm?.let { b ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(b.displayLabel) },
            text = { Text(tr(when (b.kind) { ButtonKind.PUBLISH -> "buttons_confirm_publish"; ButtonKind.SERVICE -> "buttons_confirm_service"; ButtonKind.ACTION -> "buttons_confirm_action" }, b.target)) },
            confirmButton = { TextButton(onClick = { confirm = null; controller.runners[b.id]?.press() }) { Text(tr("buttons_send")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("buttons_cancel")) } })
    }
    cancelGoal?.let { b ->
        AlertDialog(onDismissRequest = { cancelGoal = null }, title = { Text(tr("buttons_cancel_goal_title")) },
            text = { Text(tr("buttons_cancel_goal_message", b.target)) },
            confirmButton = { TextButton(onClick = { cancelGoal = null; controller.runners[b.id]?.cancelAction() }) { Text(tr("buttons_cancel_goal")) } },
            dismissButton = { TextButton(onClick = { cancelGoal = null }) { Text(tr("buttons_no")) } })
    }
    detail?.let { (b, text) ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(b.displayLabel) },
            text = { SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(tr("buttons_close")) } })
    }
    deleting?.let { b ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(tr("buttons_delete_title", b.displayLabel)) },
            confirmButton = { TextButton(onClick = { deleting = null; controller.delete(b.id) }) { Text(tr("buttons_delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("buttons_cancel")) } })
    }
}

@Composable
private fun ButtonCell(
    b: ButtonSpec, controller: ButtonsController, onPress: () -> Unit, onDetail: (String) -> Unit,
    onEdit: () -> Unit, onDuplicate: () -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit,
) {
    val runner = controller.runners[b.id]
    val state = controller.states[b.id] ?: ButtonState.Idle
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 8.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("buttons_cell_target", kindLabel(b.kind), b.target), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, tr("buttons_options_for", b.displayLabel)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("buttons_menu_edit")) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_duplicate")) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_move_earlier")) }, onClick = { menu = false; onMove(-1) })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_move_later")) }, onClick = { menu = false; onMove(+1) })
                        DropdownMenuItem(text = { Text(tr("buttons_menu_delete")) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            val color = ButtonSpec.COLORS[b.color]
            val bg = if (color != 0) Color(color) else MaterialTheme.colorScheme.primary
            val fg = if (color != 0) Color.White else MaterialTheme.colorScheme.onPrimary
            val running = runner?.actionRunning == true
            val text = if (running) tr("buttons_cancel_running", b.displayLabel) else b.displayLabel
            val hold = b.kind == ButtonKind.PUBLISH && b.repeatHz > 0
            Box(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(50)).background(bg)
                    .semantics { contentDescription = b.displayLabel; onClick { onPress(); true } }
                    .pointerInput(b.id, hold) {
                        if (hold) awaitEachGesture {
                            // While held down; the options are behind the ⋮ button.
                            awaitFirstDown(requireUnconsumed = false); runner?.press()
                            waitForUpOrCancellation(); runner?.release()
                        } else detectTapGestures(onTap = { onPress() }, onLongPress = { menu = true })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    fontStyle = if (running) FontStyle.Italic else FontStyle.Normal, modifier = Modifier.padding(8.dp))
            }
            val err = runner?.setupError
            val (line, col) = when {
                err != null && state is ButtonState.Idle -> "⚠ $err" to StatusColors.error
                state is ButtonState.Busy -> "⏳ " + state.text to MaterialTheme.colorScheme.onSurfaceVariant
                state is ButtonState.Done -> (if (state.ok) "✓ " else "✗ ") + state.text to (if (state.ok) StatusColors.ok else StatusColors.error)
                else -> "" to Color.Unspecified
            }
            if (line.isNotEmpty()) Text(line, color = col, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp).clickable {
                    onDetail(when (state) { is ButtonState.Done -> state.detail ?: state.text; is ButtonState.Busy -> state.text; else -> err ?: "" })
                })
        }
    }
}

private fun kindLabel(k: ButtonKind) = tr(when (k) { ButtonKind.PUBLISH -> "buttons_kind_publish"; ButtonKind.SERVICE -> "buttons_kind_service"; ButtonKind.ACTION -> "buttons_kind_action" })

/** Common destinations with their type, to suggest and fill in. */
val KNOWN_TARGETS: List<Pair<String, String>> = listOf(
    "/cmd_vel" to "geometry_msgs/msg/Twist", "/goal_pose" to "geometry_msgs/msg/PoseStamped",
    "/initialpose" to "geometry_msgs/msg/PoseWithCovarianceStamped", "/chatter" to "std_msgs/msg/String",
    "/add_two_ints" to "example_interfaces/srv/AddTwoInts", "/spawn" to "turtlesim/srv/Spawn", "/kill" to "turtlesim/srv/Kill",
    "/reset" to "std_srvs/srv/Empty", "/clear" to "std_srvs/srv/Empty", "/turtle1/teleport_absolute" to "turtlesim/srv/TeleportAbsolute",
    "/turtle1/set_pen" to "turtlesim/srv/SetPen", "/global_costmap/clear_entirely_global_costmap" to "nav2_msgs/srv/ClearEntireCostmap",
    "/local_costmap/clear_entirely_local_costmap" to "nav2_msgs/srv/ClearEntireCostmap", "/navigate_to_pose" to "nav2_msgs/action/NavigateToPose",
    "/follow_waypoints" to "nav2_msgs/action/FollowWaypoints", "/spin" to "nav2_msgs/action/Spin", "/backup" to "nav2_msgs/action/BackUp",
    "/fibonacci" to "example_interfaces/action/Fibonacci", "/turtle1/rotate_absolute" to "turtlesim/action/RotateAbsolute",
)

fun knownType(target: String): String? = KNOWN_TARGETS.firstOrNull { it.first == target.trim() }?.second
    ?: MessageCatalog.COMMON_TOPICS.firstOrNull { it.first == target.trim() }?.second

/** Create or edit a button: what it does, where, with which type, which values and its options. */
@Composable
fun ButtonEditorDialog(registry: InterfaceRegistry, initial: ButtonSpec, isNew: Boolean, onDismiss: () -> Unit, onSave: (ButtonSpec) -> Unit) {
    var label by remember { mutableStateOf(initial.label) }
    var kind by remember { mutableStateOf(initial.kind) }
    var target by remember { mutableStateOf(initial.target) }
    var type by remember { mutableStateOf(initial.type) }
    var color by remember { mutableStateOf(initial.color) }
    var qos by remember { mutableStateOf(initial.qos) }
    var repeat by remember { mutableStateOf(fmt(initial.repeatHz)) }
    var stop by remember { mutableStateOf(initial.stopOnRelease) }
    var timeout by remember { mutableStateOf(fmt(initial.timeoutS)) }
    var confirm by remember { mutableStateOf(initial.confirm) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    /** Values per type, so what was typed is not lost when switching type and back. */
    val valuesByType = remember { HashMap<String, JSONObject>().apply { if (initial.type.isNotBlank()) put(initial.type, runCatching { JSONObject(initial.values) }.getOrDefault(JSONObject())) } }
    val payload: MsgSpec? = remember(kind, type) {
        if (type.isBlank()) null else try {
            when (kind) { ButtonKind.PUBLISH -> registry.msg(type); ButtonKind.SERVICE -> registry.srv(type).request; ButtonKind.ACTION -> registry.action(type).goal }
        } catch (e: IdlException) { error = e.message; null }
    }
    val form = remember(payload) { MessageFormState(payload, valuesByType[type] ?: JSONObject()) }
    fun remember() { if (type.isNotBlank() && payload != null) runCatching { valuesByType[type] = form.collect() } }
    fun setType(t: String) {
        remember(); type = t; error = null
        if (target.isBlank()) KNOWN_TARGETS.firstOrNull { it.second == t }?.let { target = it.first }
    }
    var defining by remember { mutableStateOf<String?>(null) }
    /** A type seen on the network: if the app does not bring it, straight to defining it. */
    fun setTypeIfKnown(t: String) { if (registry.has(t)) setType(t) else { error = tr("buttons_editor_not_bundled", t); defining = t } }
    fun discovered(g: RosGraph?): List<Pair<String, String>> = when (kind) {
        ButtonKind.PUBLISH -> g?.topics()?.map { it.name to it.type }
        ButtonKind.SERVICE -> g?.services()?.filter { it.servers > 0 }?.map { it.name to it.type }
        ButtonKind.ACTION -> g?.actions()?.map { it.name to it.type }
    }.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (isNew) "buttons_editor_new_title" else "buttons_editor_edit_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, singleLine = true, label = { Text(tr("buttons_editor_label_hint")) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in ButtonKind.entries) FilterChip(kind == k, {
                        if (k != kind) { remember(); kind = k; if (type.split('/').getOrNull(1) != k.kind) type = "" }
                    }, label = { Text(kindLabel(k)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(target, { target = it }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(tr(when (kind) { ButtonKind.PUBLISH -> "buttons_editor_target_topic_hint"; ButtonKind.SERVICE -> "buttons_editor_target_service_hint"; ButtonKind.ACTION -> "buttons_editor_target_action_hint" })) })
                    OutlinedButton(onClick = {
                        if (RosConnection.status !is RosConnection.Status.Connected) { error = tr("buttons_editor_not_connected"); return@OutlinedButton }
                        RosConnection.discover { g ->
                            val items = discovered(g)
                            if (g == null) error = tr("buttons_editor_discovery_unavailable")
                            else if (items.isEmpty()) error = tr(when (kind) { ButtonKind.PUBLISH -> "buttons_editor_no_topics_found"; ButtonKind.SERVICE -> "buttons_editor_no_services_found"; ButtonKind.ACTION -> "buttons_editor_no_actions_found" }, RosConnection.config.domainId)
                            else browsing = items
                        }
                    }) { Text(tr("buttons_editor_browse")) }
                }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(type.ifBlank { tr(when (kind) { ButtonKind.PUBLISH -> "buttons_editor_choose_message_type"; ButtonKind.SERVICE -> "buttons_editor_choose_service_type"; ButtonKind.ACTION -> "buttons_editor_choose_action_type" }) })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (payload != null) {
                    Text(tr(when (kind) { ButtonKind.PUBLISH -> "buttons_editor_fields_message"; ButtonKind.SERVICE -> "buttons_editor_fields_request"; ButtonKind.ACTION -> "buttons_editor_fields_goal" }), style = MaterialTheme.typography.titleSmall)
                    MessageFormView(form)
                }
                Text(tr("buttons_editor_options"), style = MaterialTheme.typography.titleSmall)
                if (kind == ButtonKind.PUBLISH) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((q, key) in listOf(QosPreset.RELIABLE to "buttons_editor_reliable", QosPreset.SENSOR to "buttons_editor_best_effort", QosPreset.LATCHED to "buttons_editor_transient_local"))
                            FilterChip(qos == q, { qos = q }, label = { Text(tr(key)) })
                    }
                    OutlinedTextField(repeat, { repeat = it }, singleLine = true, label = { Text(tr("buttons_editor_repeat")) }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(stop, { stop = it }); Text(tr("buttons_editor_stop_on_release")) }
                } else OutlinedTextField(timeout, { timeout = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr(if (kind == ButtonKind.ACTION) "buttons_editor_goal_timeout" else "buttons_editor_response_timeout")) })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(confirm, { confirm = it }); Text(tr("buttons_editor_confirm")) }
                Text(tr("buttons_editor_color"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((i, c) in ButtonSpec.COLORS.withIndex()) Box(
                        Modifier.size(32.dp).clip(CircleShape).background(if (c == 0) Color(0xFFBDBDBD) else Color(c))
                            .let { if (i == color) it.border(3.dp, Color(0xFF212121), CircleShape) else it }
                            .semantics { contentDescription = tr("buttons_editor_color_n", i + 1) }.clickable { color = i },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = null
                val t = target.trim()
                if (t.isEmpty()) { error = tr("buttons_editor_name_required"); return@TextButton }
                if (!Regex("/?[A-Za-z0-9_~{}/]+").matches(t)) { error = tr("buttons_editor_invalid_ros_name"); return@TextButton }
                if (type.isBlank()) { error = tr("buttons_editor_choose_a_type"); return@TextButton }
                if (payload == null) { error = tr("buttons_editor_type_not_valid", type); return@TextButton }
                val values = try { form.validate() } catch (e: Exception) { error = tr("buttons_editor_field_error", e.message); return@TextButton }
                val hz = repeat.replace(',', '.').toDoubleOrNull()
                if (kind == ButtonKind.PUBLISH && (hz == null || hz < 0 || hz > 100)) { error = tr("buttons_editor_between_0_100"); return@TextButton }
                val to = timeout.replace(',', '.').toDoubleOrNull()
                if (kind != ButtonKind.PUBLISH && (to == null || to <= 0 || to > 3600)) { error = tr("buttons_editor_between_0_3600"); return@TextButton }
                onSave(initial.copy(
                    label = label.trim(), kind = kind, target = if (t.startsWith("/")) t else "/$t", type = type, values = values.toString(),
                    color = color, qos = qos, repeatHz = if (kind == ButtonKind.PUBLISH) hz!! else initial.repeatHz, stopOnRelease = stop,
                    timeoutS = if (kind != ButtonKind.PUBLISH) to!! else initial.timeoutS, confirm = confirm,
                    definitions = Interfaces.customDependencies(type),
                ))
            }) { Text(tr("buttons_editor_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("buttons_cancel")) } },
    )

    if (picking) TypePickerDialog(registry, kind.kind, onDismiss = { picking = false }) { picking = false; setType(it) }
    defining?.let { t -> CustomTypeDialog(kind.kind, t, onDismiss = { defining = null }) { defining = null; setType(it) } }
    browsing?.let { items ->
        AlertDialog(onDismissRequest = { browsing = null },
            title = { Text(tr(when (kind) { ButtonKind.PUBLISH -> "buttons_editor_topics_on_network"; ButtonKind.SERVICE -> "buttons_editor_services_on_network"; ButtonKind.ACTION -> "buttons_editor_actions_on_network" })) },
            text = {
                LazyColumn {
                    items(items) { (name, t) ->
                        Column(Modifier.fillMaxWidth().clickable { browsing = null; target = name; if (t != type) setTypeIfKnown(t) }.padding(vertical = 6.dp)) {
                            Text(name, fontFamily = FontFamily.Monospace)
                            Text(t + if (registry.has(t)) "" else tr("buttons_editor_type_not_bundled"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { browsing = null }) { Text(tr("buttons_cancel")) } })
    }
}

/**
 * List of the app's interfaces ("msg", "srv" or "action") with a search box, to pick a type, and "Define a custom
 * type…" at the bottom for one the app does not bring (the search text is its starting name).
 */
@Composable
fun TypePickerDialog(registry: InterfaceRegistry, kind: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val all = remember(kind) { registry.names(kind) }
    var filter by remember { mutableStateOf("") }
    var defining by remember { mutableStateOf(false) }
    if (defining) { CustomTypeDialog(kind, filter.trim().takeIf { '/' in it }.orEmpty(), onDismiss = { defining = false }, onSaved = onPicked); return }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("buttons_editor_type_title")) },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text(tr("buttons_editor_search_types_hint")) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(all.filter { it.contains(filter.trim(), true) }) { t ->
                        Text(t, modifier = Modifier.fillMaxWidth().clickable { onPicked(t) }.padding(vertical = 8.dp))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { defining = true }) { Text(tr("buttons_editor_define_custom")) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("buttons_cancel")) } })
}

private fun fmt(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else d.toString()
