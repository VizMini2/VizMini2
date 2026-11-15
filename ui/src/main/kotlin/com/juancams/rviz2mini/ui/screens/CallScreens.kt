// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.CallException
import com.juancams.rviz2mini.ros.dyn.DynActionClient
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ui.common.MessageFormState
import com.juancams.rviz2mini.ui.common.MessageFormView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Mono(text: String) = SelectionContainer {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.horizontalScroll(rememberScrollState()))
}

/** Call a service (like `ros2 service call`): the request form and the response. */
@Composable
fun ServiceCallScreen(name: String, type: String, modifier: Modifier = Modifier) {
    val registry = remember { Interfaces.get() }
    // Bumped when the user defines the missing type: the form is built then.
    var defined by remember { mutableStateOf(0) }
    if (!remember(type, defined) { registry.has(type) }) { MissingTypeNotice("srv", type, tr("explore_service_type_missing", type), modifier) { defined++ }; return }
    val srv = remember(type) { registry.srv(type) }
    val form = remember(type) { MessageFormState(srv.request, JSONObject()) }
    val worker = remember { Executors.newSingleThreadExecutor { r -> Thread(r, "service-call").apply { isDaemon = true } } }
    val client = remember(name, type) { DynServiceClient(name, srv) }
    DisposableEffect(client) { onDispose { worker.execute { client.close() }; worker.shutdown() } }
    var busy by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf("") }
    var result by remember { mutableStateOf(tr("explore_service_hint")) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(name, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        Text(type, style = MaterialTheme.typography.bodySmall)
        Section(tr("explore_request")) {
            MessageFormView(form)
            Button(enabled = !busy, onClick = {
                val request = try { DynCodec.fromJson(srv.request, form.validate(), strict = true) }
                catch (e: Exception) { result = tr("explore_invalid_value", e.message); return@Button }
                busy = true; meta = tr("explore_calling")
                val start = System.currentTimeMillis()
                worker.execute {
                    val (text, info) = try {
                        val r = client.call(request, 10_000)
                        DynCodec.format(srv.response, r, maxSeq = 64) to tr("explore_answered_in", System.currentTimeMillis() - start)
                    } catch (e: CallException) { (e.message ?: tr("explore_error")) to tr("explore_failed") }
                    catch (e: Exception) { Log.e("VizMini2", "Error llamando a $name", e); (e.message ?: e.javaClass.simpleName) to tr("explore_failed") }
                    Platform.main.post { busy = false; meta = info; result = text }
                }
            }) { Text(tr("explore_call")) }
        }
        Section(tr("explore_response")) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
            Mono(result)
        }
    }
}

/**
 * Send a goal to an action (like `ros2 action send_goal`): the goal, the feedback and the result.
 * `cancelOnLeave`: whether leaving the screen with the goal running cancels it (Android: not when rotating).
 */
@Composable
fun ActionGoalScreen(name: String, type: String, modifier: Modifier = Modifier, cancelOnLeave: () -> Boolean = { true }) {
    val registry = remember { Interfaces.get() }
    // Bumped when the user defines the missing type: the form is built then.
    var defined by remember { mutableStateOf(0) }
    if (!remember(type, defined) { registry.has(type) }) { MissingTypeNotice("action", type, tr("explore_action_type_missing", type), modifier) { defined++ }; return }
    val action = remember(type) { registry.action(type) }
    val form = remember(type) { MessageFormState(action.goal, JSONObject()) }
    val worker = remember { Executors.newSingleThreadExecutor { r -> Thread(r, "action-goal").apply { isDaemon = true } } }
    val client = remember(name, type) { DynActionClient(name, action, registry.srv("action_msgs/srv/CancelGoal")) }
    var running by remember { mutableStateOf(false) }
    var cancelRequested by remember { mutableStateOf(false) }
    var closed by remember { mutableStateOf(false) }
    var cancelOnClose by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf(tr("explore_action_hint")) }
    var feedback by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    val releaseHold = remember { arrayOfNulls<() -> Unit>(1) }
    DisposableEffect(client) {
        onDispose {
            cancelOnClose = cancelOnLeave()
            if (cancelOnClose && running) releaseHold[0] = RosConnection.holdOpen()
            closed = true
            worker.shutdown()
            // Up to 6 s: how long the cancellation may take to respond.
            Thread({ try { worker.awaitTermination(6, TimeUnit.SECONDS) } catch (_: InterruptedException) {}; client.close() }, "action-close").start()
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(name, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        Text(type, style = MaterialTheme.typography.bodySmall)
        Section(tr("explore_goal")) {
            MessageFormView(form)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !running, modifier = Modifier.weight(1f), onClick = {
                    val goal = try { DynCodec.fromJson(action.goal, form.validate(), strict = true) }
                    catch (e: Exception) { status = tr("explore_invalid_value", e.message); return@Button }
                    cancelRequested = false; running = true; status = tr("explore_sending_goal"); feedback = ""; result = ""
                    val start = System.currentTimeMillis()
                    worker.execute {
                        val (st, res) = try {
                            val o = client.run(goal, 10_000,
                                onAccepted = { Platform.main.post { status = tr("explore_accepted_running") } },
                                onFeedback = { fb -> val t = DynCodec.format(action.feedback, fb, maxSeq = 32); Platform.main.post { feedback = t } },
                                cancelRequested = { cancelRequested }, abandoned = { closed }, cancelIfAbandoned = { cancelOnClose })
                            tr("explore_action_outcome", o.statusLabel, ((System.currentTimeMillis() - start) / 1000.0).toString()) to DynCodec.format(action.result, o.result, maxSeq = 64)
                        } catch (e: CallException) { (e.message ?: tr("explore_error")) to "" }
                        catch (e: Exception) { Log.e("VizMini2", "Error en la acción $name", e); (e.message ?: e.javaClass.simpleName) to "" }
                        releaseHold[0]?.invoke()
                        Platform.main.post { running = false; status = st; result = res }
                    }
                }) { Text(tr("explore_send_goal")) }
                OutlinedButton(enabled = running, modifier = Modifier.weight(1f), onClick = { cancelRequested = true; status = tr("explore_canceling") }) { Text(tr("explore_cancel")) }
            }
        }
        Section(tr("explore_feedback")) {
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (feedback.isNotEmpty()) Mono(feedback)
        }
        Section(tr("explore_result")) { if (result.isNotEmpty()) Mono(result) }
    }
}
