// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosGraph
import com.juancams.rviz2mini.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Connection status, updated on changes (RosConnection listeners run on the main thread). */
@Composable
fun rememberRosStatus(): RosConnection.Status {
    var status by remember { mutableStateOf(RosConnection.status) }
    DisposableEffect(Unit) {
        val l: (RosConnection.Status) -> Unit = { status = it }
        RosConnection.addStatusListener(l)
        onDispose { RosConnection.removeStatusListener(l) }
    }
    return status
}

/** ROS graph, re-read every `periodMs` (off the main thread: with DDS it takes a few milliseconds). */
@Composable
fun rememberGraph(periodMs: Long = 1500): RosGraph? {
    var graph by remember { mutableStateOf<RosGraph?>(null) }
    val status = rememberRosStatus()
    LaunchedEffect(status) {
        while (true) {
            graph = withContext(Dispatchers.Default) { runCatching { RosConnection.graph() }.getOrNull() }
            delay(periodMs)
        }
    }
    return graph
}

/** Text and color of a status, like ConnectionUi on Android. */
fun statusLabel(s: RosConnection.Status): Pair<String, Color> = when (s) {
    is RosConnection.Status.Connected -> when {
        s.bridge != null -> tr("main_bridge_badge")
        s.zenoh != null -> tr("main_zenoh_badge", s.domainId)
        else -> tr("main_domain_badge", s.domainId)
    } to StatusColors.ok
    RosConnection.Status.Connecting -> tr("common_status_connecting") to StatusColors.warn
    RosConnection.Status.Disconnected -> tr("common_status_disconnected") to StatusColors.idle
    is RosConnection.Status.Error -> tr("common_status_error") to StatusColors.error
}

/** The status "pill" in the top bar. */
@Composable
fun StatusPill(status: RosConnection.Status, modifier: Modifier = Modifier) {
    val (text, color) = statusLabel(status)
    Text(
        text, color = color, style = MaterialTheme.typography.labelLarge,
        modifier = modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
