// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.LiveView
import com.juancams.rviz2mini.bag.BagSession
import com.juancams.rviz2mini.i18n.tr
import java.util.Locale

/**
 * The common timeline: with a bag playing (from the Bags tab), its bar at the top of the dashboard and the 3D view (on the
 * PC, under every tab). All
 * widgets receive what is playing at the same time, so pausing, changing the speed or jumping to an instant (also
 * backwards) moves the 3D view, the plots, the camera and the rest together. Hidden without a bag.
 */
/** Where the timeline goes: the PC shows a single one under every tab ([inWindow]), so the dashboard and 3D view leave out theirs. */
object PlaybackBars {
    @Volatile var shownByWindow = false
}

@Composable
fun PlaybackBar(modifier: Modifier = Modifier, inWindow: Boolean = false) {
    if (PlaybackBars.shownByWindow && !inWindow) return
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(200); tick++ } }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val p = BagSession.player ?: return
    val dur = p.reader.durationNs.coerceAtLeast(1)
    var dragging by remember { mutableStateOf<Float?>(null) }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { p.paused = !p.paused }) {
                Icon(if (p.paused) Icons.Default.PlayArrow else Icons.Default.Pause, tr(if (p.paused) "playback_play" else "playback_pause"))
            }
            Text(time(((dragging ?: (p.positionNs.toFloat() / dur)) * dur).toLong()) + " / " + time(dur), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Slider(dragging ?: (p.positionNs.toFloat() / dur).coerceIn(0f, 1f), { dragging = it },
                onValueChangeFinished = { dragging?.let { p.seek((it * dur).toLong()) }; dragging = null },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            // Speed: tap = the next one.
            TextButton(onClick = { p.rate = RATES[(RATES.indexOf(p.rate).takeIf { it >= 0 }?.plus(1) ?: 1) % RATES.size] }) {
                Text(String.format(Locale.ROOT, "%sx", if (p.rate % 1.0 == 0.0) p.rate.toInt().toString() else p.rate.toString()), fontSize = 13.sp)
            }
            Text(p.file.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp))
            IconButton(onClick = { BagSession.stopPlayer() }) { Icon(Icons.Default.Close, tr("playback_stop"), Modifier.size(20.dp)) }
        }
    }
}

/** Live pause of everything at once ([LiveView]); with a bag playing, the bag's pause. */
@Composable
fun LivePauseButton() {
    var frozen by remember { mutableStateOf(LiveView.frozen) }
    val p = BagSession.player
    IconButton(onClick = {
        if (p != null) p.paused = !p.paused else { LiveView.setFrozen(!LiveView.frozen); frozen = LiveView.frozen }
    }) {
        val paused = p?.paused ?: frozen
        Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, tr(if (paused) "playback_resume_all" else "playback_pause_all"),
            tint = if (paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

private val RATES = listOf(0.25, 0.5, 1.0, 2.0, 4.0)

private fun time(ns: Long): String { val s = ns / 1_000_000_000; return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) }
