// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.nav.Nav2Mission
import com.juancams.rviz2mini.rviz.RvizTool

/** Whether there is something to show in the Nav2 bar (tool selected, route, or a Nav2 goal running). */
fun nav2BarVisible(m: Nav2Mission, tool: RvizTool) = tool == RvizTool.WAYPOINTS || m.waypoints.isNotEmpty() || m.running || m.goalStatus != null

/**
 * The 3D Nav2 bar (the same on the phone and on the PC): the waypoints placed with the tool, how to send them
 * (stopping at each one or passing through them), loops, undo, clear, Go/Cancel and the Nav2 status. `version`
 * is bumped on each route change (to repaint).
 */
@Composable
fun Nav2Bar(m: Nav2Mission, version: Int, modifier: Modifier = Modifier, onChanged: () -> Unit = {}) {
    if (version < 0) return
    val orange = Color(RvizTool.WAYPOINTS.color)
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
        .padding(horizontal = 8.dp, vertical = 2.dp)) {
        if (m.waypoints.isNotEmpty() || m.running) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Nav2", color = orange, fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp))
            Text(tr("nav2_count", m.waypoints.size), fontSize = 13.sp)
            for (mode in Nav2Mission.Mode.entries) FilterChip(m.mode == mode, enabled = !m.running, onClick = { m.mode = mode; onChanged() },
                label = { Text(tr(mode.labelKey), fontSize = 12.sp) })
            if (m.mode == Nav2Mission.Mode.FOLLOW) FilterChip(m.loops > 0, enabled = !m.running, onClick = { m.loops = if (m.loops > 0) 0 else 1; onChanged() },
                label = { Text(tr("nav2_loop"), fontSize = 12.sp) })
            IconButton(onClick = { m.undo() }, enabled = !m.running && m.waypoints.isNotEmpty(), modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, tr("nav2_undo"), Modifier.size(20.dp))
            }
            IconButton(onClick = { m.clear() }, enabled = !m.running && m.waypoints.isNotEmpty(), modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, tr("nav2_clear"), Modifier.size(20.dp))
            }
            if (m.running) Button(onClick = { m.cancel() }, contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935), contentColor = Color.White)) {
                Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(tr("nav2_cancel"), fontSize = 13.sp)
            } else Button(onClick = { m.start() }, enabled = m.waypoints.isNotEmpty(), contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = orange, contentColor = Color.Black)) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(tr("nav2_go"), fontSize = 13.sp)
            }
        }
        val status = m.status ?: m.goalStatus ?: if (m.waypoints.isEmpty()) tr("rviz_tool_waypoints_hint") else null
        status?.let { Text(it, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 3.dp)) }
    }
}
