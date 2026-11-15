// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.DomainScanner
import com.juancams.rviz2mini.ros.KnownRobots
import com.juancams.rviz2mini.ros.RosConnection

/**
 * Find which domains have robots ([DomainScanner]) and connect to one with a tap. `onPick(domain)`: what
 * the platform does on picking (change the connection's domain).
 */
@Composable
fun FindRobotsDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var result by remember { mutableStateOf<Map<Int, Set<String>>?>(null) }
    var run by remember { mutableStateOf(0) }
    LaunchedEffect(run) {
        result = null
        Thread {
            // While listening, let multicast through (Android filters it otherwise).
            val release = Platform.acquireMulticast()
            val r = try { DomainScanner.scan() } catch (_: Exception) { emptyMap() } finally { release() }
            Platform.main.post { result = r }
        }.start()
    }
    val own = remember { DomainScanner.ownIps() }
    val current = RosConnection.config.domainId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("find_robots_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val r = result
                if (r == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  " + tr("find_robots_searching"), fontSize = 14.sp)
                } else if (r.isEmpty()) Text(tr("find_robots_none"))
                else {
                    Text(tr("find_robots_pick"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for ((d, ips) in r) Column(Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 8.dp)) {
                        Text(tr("find_robots_domain", d) + if (d == current) "  ·  " + tr("find_robots_current") else "", fontWeight = FontWeight.Bold)
                        val names = KnownRobots.list(d).associate { it.ip to it.name }
                        Text(ips.sorted().joinToString { ip -> (names[ip]?.takeIf { it.isNotBlank() }?.let { "$it ($ip)" } ?: ip) + if (ip in own) " · " + tr("find_robots_this_device") else "" },
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("common_close")) } },
        dismissButton = { if (result != null) TextButton(onClick = { run++ }) { Text(tr("find_robots_again")) } },
    )
}
