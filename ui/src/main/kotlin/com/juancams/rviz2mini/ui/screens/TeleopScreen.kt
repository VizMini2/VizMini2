// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.teleop.AxisAssignment
import com.juancams.rviz2mini.teleop.AxisMapping
import com.juancams.rviz2mini.teleop.AxisTarget
import com.juancams.rviz2mini.teleop.Joystick
import com.juancams.rviz2mini.teleop.Stick
import com.juancams.rviz2mini.teleop.StickAxis
import com.juancams.rviz2mini.teleop.TeleopPreset
import com.juancams.rviz2mini.teleop.TeleopPublisher
import com.juancams.rviz2mini.teleop.TeleopSettings
import com.juancams.rviz2mini.teleop.Velocity
import com.juancams.rviz2mini.ui.theme.StatusColors
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** A physical gamepad: its two sticks (dead zone already applied) and its name. Filled in by each platform. */
data class GamepadState(val name: String, val left: Stick = Stick.CENTER, val right: Stick = Stick.CENTER) {
    val active: Boolean get() = !left.isCenter || !right.isCenter
}

/**
 * Teleop: one or two joysticks publishing Twist/TwistStamped, with STOP, safety stop notice and settings
 * (topic, velocities, preset or axis mapping). On the PC you can also drive with the keyboard: W/S or ↑/↓ forward and
 * backward, A/D or ←/→ turn, space = STOP. Settings are saved in the tab (`tabId`), same as on Android.
 *
 * `gamepad`: the physical gamepad, if any; `stopRequests`: each increment is a STOP (gamepad B button...);
 * `onPublisher`: the live publisher, so the platform can stop on its own (network lost, gamepad disconnected).
 * `compact`: inside a dashboard, smaller margins and single-line texts.
 */
@Composable
fun TeleopScreen(
    tabId: String,
    modifier: Modifier = Modifier,
    dual: Boolean = false,
    compact: Boolean = false,
    gamepad: GamepadState? = null,
    stopRequests: Int = 0,
    onPublisher: (TeleopPublisher?) -> Unit = {},
) {
    val store = TabStore.get()
    var settings by remember(tabId) { mutableStateOf(TeleopSettings.fromMap(store.get(tabId)?.settings ?: emptyMap(), dual)) }
    var state by remember { mutableStateOf<TeleopPublisher.State?>(null) }
    var publisher by remember { mutableStateOf<TeleopPublisher?>(null) }
    var left by remember { mutableStateOf(Stick.CENTER) }
    var right by remember { mutableStateOf(Stick.CENTER) }
    var touchingLeft by remember { mutableStateOf(false) }
    var touchingRight by remember { mutableStateOf(false) }
    var stopLatched by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf(setOf<Key>()) }
    var showSettings by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    DisposableEffect(settings) {
        val p = TeleopPublisher(settings) { state = it }
        publisher = p; onPublisher(p)
        onDispose { onPublisher(null); p.close() }
    }
    LaunchedEffect(stopRequests) {
        if (stopRequests > 0) { stopLatched = true; publisher?.stop(latch = true) }
    }

    fun keyStick(): Stick {
        var x = 0f; var y = 0f
        if (Key.W in keys || Key.DirectionUp in keys) y += 1f
        if (Key.S in keys || Key.DirectionDown in keys) y -= 1f
        if (Key.A in keys || Key.DirectionLeft in keys) x -= 1f
        if (Key.D in keys || Key.DirectionRight in keys) x += 1f
        return Stick(x, y)
    }
    val keyboard = keys.isNotEmpty()
    val pad = gamepad?.takeIf { it.active }
    val touching = touchingLeft || touchingRight
    val active = touching || keyboard || pad != null
    val shownLeft = when { pad != null -> pad.left; keyboard -> keyStick(); else -> left }
    val shownRight = if (pad != null) pad.right else right

    // Computes the velocity with the mapping and sends it; heartbeat while driving (if the interface
    // freezes, the publisher's watchdog stops the robot).
    LaunchedEffect(active, shownLeft, shownRight, settings, publisher) {
        val p = publisher ?: return@LaunchedEffect
        if (!active) { stopLatched = false; p.onRelease(); return@LaunchedEffect }
        p.onStick(settings.mapping.toVelocity(shownLeft, shownRight, settings.maxLinear, settings.maxAngular))
        while (true) { p.heartbeat(); delay(50) }
    }

    val gap = if (compact) 6.dp else 10.dp
    Column(
        modifier.fillMaxSize().padding(if (compact) 8.dp else 16.dp).focusRequester(focus).focusable()
            // Any click in the tab (the joystick too) gives it the keyboard back: after clicking the sidebar or a
            // dialog the focus is elsewhere and WASD and the arrows would not reach it. Nothing is consumed.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) runCatching { focus.requestFocus() }
                    }
                }
            }
            .onKeyEvent { e ->
                val k = e.key
                if (k == Key.Spacebar) {
                    if (e.type == KeyEventType.KeyDown) { stopLatched = true; keys = emptySet(); publisher?.stop(latch = true) }
                    return@onKeyEvent true
                }
                if (k !in DRIVE_KEYS) return@onKeyEvent false
                keys = if (e.type == KeyEventType.KeyDown) keys + k else keys - k
                true
            },
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val s = state
        val type = if (settings.stamped) "TwistStamped" else "Twist"
        val (statusText, statusColor) = when {
            s == null || !s.connected -> tr("teleop_ros_not_connected") to StatusColors.error
            s.subscribers == 0 -> tr("teleop_status_no_subscribers", settings.topic, type) to StatusColors.warn
            else -> tr("teleop_status_subscribers", settings.topic, type, s.subscribers) to StatusColors.ok
        }
        val textStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
        Text(statusText, color = statusColor, style = textStyle, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
        Text(valuesLine(s?.lastSent ?: Velocity.ZERO, settings, when {
            s?.autoStop != null || stopLatched -> tr("teleop_mode_stop")
            s?.publishing == true -> tr("teleop_mode_publishing")
            else -> tr("teleop_mode_idle")
        }), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        gamepad?.let { Text(tr("teleop_gamepad_info", it.name.take(30)), style = MaterialTheme.typography.bodySmall) }
        s?.autoStop?.let { reason ->
            Text(
                tr("teleop_safety_stop", reason), color = Color.White, fontWeight = FontWeight.Bold,
                // Acknowledge the notice: driving is possible again (lifting the finger first).
                modifier = Modifier.fillMaxWidth().background(StatusColors.error).clickable { publisher?.acknowledge() }.padding(10.dp),
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            JoystickPad(
                shown = shownLeft, labels = labelsFor(settings.mapping, StickAxis.LX, StickAxis.LY),
                description = tr("teleop_left_joystick"), modifier = Modifier.weight(1f).fillMaxSize(),
                onMove = { left = it; touchingLeft = true }, onRelease = { left = Stick.CENTER; touchingLeft = false },
            )
            if (settings.dual || settings.mapping.usesRightStick) JoystickPad(
                shown = shownRight, labels = labelsFor(settings.mapping, StickAxis.RX, StickAxis.RY),
                description = tr("teleop_right_joystick"), modifier = Modifier.weight(1f).fillMaxSize(),
                onMove = { right = it; touchingRight = true }, onRelease = { right = Stick.CENTER; touchingRight = false },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            val h = if (compact) 40.dp else 52.dp
            Button(
                onClick = { stopLatched = true; publisher?.stop(latch = true) },
                colors = ButtonDefaults.buttonColors(containerColor = StatusColors.error, contentColor = Color.White),
                modifier = Modifier.weight(1f).height(h),
            ) { Text(tr("teleop_stop"), fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Bold) }
            // Limits and mapping cannot be changed while driving.
            val settingsLabel = tr("teleop_settings")
            OutlinedIconButton(enabled = !active, onClick = { showSettings = true }, modifier = Modifier.size(h).semantics { contentDescription = settingsLabel }) {
                Icon(Icons.Default.Settings, null)
            }
        }
    }

    if (showSettings) TeleopSettingsDialog(settings, onDismiss = { showSettings = false }) { next ->
        showSettings = false
        settings = next
        for ((k, v) in next.toMap()) store.updateSetting(tabId, k, v)
    }
}

private val TOPIC_REGEX = Regex("^/?[A-Za-z_~][A-Za-z0-9_/~]*$")

/** A control preset as shown in the settings. */
private fun presetLabel(p: TeleopPreset) = tr(when (p) {
    TeleopPreset.DIFFERENTIAL -> "teleop_preset_differential"
    TeleopPreset.HOLONOMIC -> "teleop_preset_holonomic"
    TeleopPreset.DUAL_DIFFERENTIAL -> "teleop_preset_dual_differential"
    TeleopPreset.DUAL_HOLONOMIC -> "teleop_preset_dual_holonomic"
    TeleopPreset.DRONE_MODE2 -> "teleop_preset_drone_mode2"
    TeleopPreset.CUSTOM -> "teleop_preset_custom"
})

/** An axis' target as shown: "None" translated, the Twist fields (linear.x...) as they are. */
private fun targetLabel(t: AxisTarget) = if (t == AxisTarget.NONE) tr("teleop_axis_none") else t.label

private val DRIVE_KEYS = setOf(Key.W, Key.A, Key.S, Key.D, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/** "x +0.40   yaw -0.20   [publishing]": only the components used by the mapping. */
private fun valuesLine(v: Velocity, settings: TeleopSettings, mode: String): String {
    fun fmt(key: String, vararg args: Double) = String.format(Locale.ROOT, tr(key), *args.toTypedArray())
    val used = settings.mapping.axes.values.map { it.target }.toSet()
    val parts = ArrayList<String>()
    if (v.lx != 0.0 || AxisTarget.LX in used) parts += fmt("teleop_value_x", v.lx)
    if (v.ly != 0.0 || AxisTarget.LY in used) parts += fmt("teleop_value_y", v.ly)
    if (v.lz != 0.0 || AxisTarget.LZ in used) parts += fmt("teleop_value_z", v.lz)
    if (v.az != 0.0 || AxisTarget.AZ in used) parts += fmt("teleop_value_yaw", v.az)
    if (abs(v.ax) > 0 || abs(v.ay) > 0) parts += fmt("teleop_value_roll_pitch", v.ax, v.ay)
    return parts.joinToString("   ") + "   [$mode]"
}

/** Top, bottom, left and right labels of a joystick according to the mapping ("forward", "turn left"...). */
private fun labelsFor(mapping: AxisMapping, xAxis: StickAxis, yAxis: StickAxis): List<String> {
    fun label(a: AxisAssignment, positive: Boolean): String {
        val sign = positive != a.inverted
        return when (a.target) {
            AxisTarget.LX -> tr(if (sign) "teleop_dir_forward" else "teleop_dir_backward")
            AxisTarget.LY -> tr(if (sign) "teleop_dir_left" else "teleop_dir_right")
            AxisTarget.LZ -> tr(if (sign) "teleop_dir_up" else "teleop_dir_down")
            AxisTarget.AZ -> tr(if (sign) "teleop_dir_turn_left" else "teleop_dir_turn_right")
            AxisTarget.AX -> tr(if (sign) "teleop_dir_roll_plus" else "teleop_dir_roll_minus")
            AxisTarget.AY -> tr(if (sign) "teleop_dir_pitch_plus" else "teleop_dir_pitch_minus")
            AxisTarget.NONE -> ""
        }
    }
    val x = mapping.of(xAxis); val y = mapping.of(yAxis)
    return listOf(label(y, true), label(y, false), label(x, false), label(x, true))
}

/** A joystick: base circle, cross, knob following the finger (or mouse) and the four directions. */
@Composable
private fun JoystickPad(shown: Stick, labels: List<String>, description: String, modifier: Modifier, onMove: (Stick) -> Unit, onRelease: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.fillMaxSize().padding(18.dp).semantics { contentDescription = description }
                    .pointerInput(Unit) {
                        fun stick(p: Offset): Stick {
                            val r = min(size.width, size.height) / 2f
                            return Joystick.fromTouch(p.x - size.width / 2f, p.y - size.height / 2f, r)
                        }
                        detectDragGestures(onDragStart = { onMove(stick(it)) }, onDragEnd = onRelease, onDragCancel = onRelease) { change, _ ->
                            onMove(stick(change.position))
                        }
                    },
            ) {
                val r = min(size.width, size.height) / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(primary.copy(alpha = 0.18f), r, c)
                drawCircle(primary, r, c, style = Stroke(width = 3.dp.toPx()))
                drawLine(primary.copy(alpha = 0.35f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5.dp.toPx())
                drawLine(primary.copy(alpha = 0.35f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5.dp.toPx())
                drawCircle(primary, r * 0.28f, Offset(c.x + shown.x * r, c.y - shown.y * r))
            }
            // The labels need room: a small pad (a dashboard widget) keeps only the top and bottom ones, and a tiny one none,
            // so they never pile up on the knob or on each other.
            if (side >= 96.dp) {
                Text(labels[0], style = labelStyle, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.TopCenter))
                Text(labels[1], style = labelStyle, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomCenter))
            }
            if (side >= 180.dp) {
                val sideLabel = Modifier.widthIn(max = side * 0.3f)
                Text(labels[2], style = labelStyle, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = sideLabel.align(Alignment.CenterStart))
                Text(labels[3], style = labelStyle, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = sideLabel.align(Alignment.CenterEnd))
            }
        }
    }
}

@Composable
private fun TeleopSettingsDialog(current: TeleopSettings, onDismiss: () -> Unit, onSave: (TeleopSettings) -> Unit) {
    var topic by remember { mutableStateOf(current.topic) }
    var stamped by remember { mutableStateOf(current.stamped) }
    var maxLinear by remember { mutableStateOf(current.maxLinear.toFloat()) }
    var maxAngular by remember { mutableStateOf(current.maxAngular.toFloat()) }
    var stopOnLoss by remember { mutableStateOf(current.stopOnConnectionLoss) }
    var preset by remember { mutableStateOf(current.preset) }
    val custom = remember { mutableStateMapOf<StickAxis, AxisAssignment>().apply { for (a in StickAxis.entries) put(a, current.mapping.of(a)) } }
    val topicOk = TOPIC_REGEX.matches(topic.trim()) && "//" !in topic
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (current.dual) "teleop_settings_title_dual" else "teleop_settings_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(topic, { topic = it }, label = { Text(tr("teleop_topic_hint")) }, singleLine = true,
                    isError = !topicOk, supportingText = if (topicOk) null else { { Text(tr("teleop_invalid_topic")) } })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(stamped, { stamped = it }); Text(tr("teleop_use_twist_stamped")) }
                Text(String.format(Locale.ROOT, tr("teleop_max_linear"), maxLinear))
                Slider(maxLinear, { maxLinear = it }, valueRange = 0f..TeleopSettings.MAX_LINEAR_LIMIT.toFloat())
                Text(String.format(Locale.ROOT, tr("teleop_max_angular"), maxAngular))
                Slider(maxAngular, { maxAngular = it }, valueRange = 0f..TeleopSettings.MAX_ANGULAR_LIMIT.toFloat())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(stopOnLoss, { stopOnLoss = it }); Text(tr("teleop_stop_on_loss")) }
                Text(tr("teleop_control"), fontWeight = FontWeight.Bold)
                for (p in TeleopPreset.forMode(current.dual)) Row(
                    Modifier.fillMaxWidth().selectable(selected = preset == p, onClick = { preset = p }),
                    verticalAlignment = Alignment.CenterVertically,
                ) { RadioButton(preset == p, { preset = p }); Text(presetLabel(p)) }
                if (preset == TeleopPreset.CUSTOM) {
                    val axes = if (current.dual) StickAxis.entries else listOf(StickAxis.LX, StickAxis.LY)
                    for (axis in axes) AxisRow(axis, current.dual, custom.getValue(axis)) { custom[axis] = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = topicOk, onClick = {
                val mapping = preset.mapping ?: AxisMapping(custom.toMap())
                onSave(current.copy(topic = topic.trim(), stamped = stamped, maxLinear = maxLinear.toDouble(), maxAngular = maxAngular.toDouble(),
                    stopOnConnectionLoss = stopOnLoss, preset = preset, mapping = mapping))
            }) { Text(tr("teleop_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("teleop_cancel")) } },
    )
}

/** An axis of the custom mapping: which Twist component it goes to and whether it is inverted. */
@Composable
private fun AxisRow(axis: StickAxis, dual: Boolean, value: AxisAssignment, onChange: (AxisAssignment) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = tr(when (axis) {
        StickAxis.LX -> if (dual) "teleop_axis_left_horizontal" else "teleop_axis_joystick_horizontal"
        StickAxis.LY -> if (dual) "teleop_axis_left_vertical" else "teleop_axis_joystick_vertical"
        StickAxis.RX -> "teleop_axis_right_horizontal"
        StickAxis.RY -> "teleop_axis_right_vertical"
    })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(targetLabel(value.target)) }
            DropdownMenu(open, { open = false }) {
                for (t in AxisTarget.entries) DropdownMenuItem(text = { Text(targetLabel(t)) }, onClick = { open = false; onChange(value.copy(target = t)) })
            }
        }
        Checkbox(value.inverted, { onChange(value.copy(inverted = it)) }); Text(tr("teleop_invert"))
    }
}
