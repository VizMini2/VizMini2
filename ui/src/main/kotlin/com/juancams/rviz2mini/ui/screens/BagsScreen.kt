// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.juancams.rviz2mini.bag.BagPlayer
import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.bag.BagSession
import com.juancams.rviz2mini.bag.McapReader
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.common.FileDialogs
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

/** Platform-specific parts of the bags tab. */
interface BagsPlatform {
    /** Offers the file (Android: share). Returns a notice to show, if any. */
    fun share(file: File): String? = file.absolutePath
    /** Asks for a file to import: `done(name, content)` (null if cancelled). Null = not possible. */
    val pick: ((done: (String?, InputStream?) -> Unit) -> Unit)? get() = FileDialogs.openStream?.let { o -> { done -> o(listOf("mcap"), done) } }
    /** Whether the demo is running (already isolated: playing "only here" does not change connection). */
    fun isDemo(): Boolean = false
}

@Volatile var bagsPlatform: BagsPlatform = object : BagsPlatform {}

/**
 * Bags tab (like `ros2 bag`): record topics to MCAP and play, share, import or delete
 * recordings. Recording and playback live in [BagSession] and continue while the tab is hidden.
 */
class BagsController(val tabId: String, private val platform: BagsPlatform = bagsPlatform) {
    private val store = TabStore.get()
    private fun setting(key: String): String? = store.get(tabId)?.settings?.get(key)
    private fun saveSetting(key: String, value: String) = store.updateSetting(tabId, key, value)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "bags-io").apply { isDaemon = true } }
    private var active = false
    private var closed = false

    /** Bumped every half second (recording statistics and position). */
    var tick by mutableIntStateOf(0); private set
    var files by mutableStateOf<List<File>>(emptyList()); private set
    val details = mutableStateMapOf<String, String>()
    var message by mutableStateOf<String?>(null)
    var stopping by mutableStateOf(false); private set
    /** Choosing topics to record: the known ones, and which were checked. */
    var choosing by mutableStateOf<List<Pair<String, String>>?>(null)
    val chosen = mutableStateListOf<String>()
    var deleting by mutableStateOf<File?>(null)
    var local by mutableStateOf(setting(KEY_LOCAL) == "true"); private set
    var loop by mutableStateOf(false)
    var rate by mutableStateOf(1.0)
    @Volatile private var playPosition = 0L
    @Volatile private var playDuration = 0L
    val position get() = playPosition
    val duration get() = playDuration
    val canImport get() = platform.pick != null

    fun resume() { if (active || closed) return; active = true; refreshList(); loop() }
    fun pause() { active = false }
    fun close() { closed = true; active = false; io.shutdown() }

    private fun loop() {
        if (!active || closed) return
        updateLive()
        Platform.main.postDelayed({ loop() }, 500)
    }

    fun updateLive() {
        // If writing failed (disk full...) "recording" is no longer shown.
        if (BagSession.recorder?.error != null) { stopRecording(); return }
        tick++
    }

    fun changeLocal(on: Boolean) { local = on; saveSetting(KEY_LOCAL, on.toString()) }
    fun changeLoop(on: Boolean) { loop = on; BagSession.player?.loop = on }
    fun changeRate(r: Double) { rate = r; BagSession.player?.rate = r }

    // ---------------------------------------------------------------- record

    fun recordOrStop() { if (BagSession.recorder != null) stopRecording() else pickTopics() }

    /** Chooses which topics to record among those on the network (with a type the app knows). */
    private fun pickTopics() {
        if (RosConnection.status !is RosConnection.Status.Connected) { message = tr("bags_not_connected"); return }
        RosConnection.discover { g ->
            Platform.main.post {
                if (closed) return@post
                val registry = Interfaces.get()
                val topics = g?.topics(includeHidden = false)?.filter { it.publishers > 0 }.orEmpty()
                val known = topics.filter { registry.has(it.type) }
                if (known.isEmpty()) { message = tr("bags_no_topics"); return@post }
                val previous = setting(KEY_TOPICS)?.lines()?.toSet().orEmpty()
                chosen.clear(); chosen.addAll(known.map { it.name }.filter { it in previous })
                choosing = known.map { it.name to it.type }
                val unknown = topics.size - known.size
                if (unknown > 0) message = tr("bags_unknown_topics", unknown)
            }
        }
    }

    fun startRecording(topics: List<Pair<String, String>>) {
        choosing = null
        if (topics.isEmpty()) return
        saveSetting(KEY_TOPICS, topics.joinToString("\n") { it.first })
        val file = BagSession.freeFile(BagSession.dir(), BagRecorder.defaultName())
        try {
            BagSession.recorder = BagRecorder(file, topics, Interfaces.get())
        } catch (e: Exception) {
            Log.e("VizMini2", "No se pudo empezar a grabar", e)
            message = tr("bags_could_not_start", e.message)
            return
        }
        updateLive()
    }

    private fun stopRecording() {
        val r = BagSession.recorder ?: return
        BagSession.recorder = null
        stopping = true
        io.execute {
            val error = r.stop()
            Platform.main.post {
                stopping = false
                message = if (error != null) tr("bags_record_failed", r.file.name, error) else tr("bags_saved", r.file.name, r.messages.get())
                updateLive()
                refreshList()
            }
        }
    }

    // ---------------------------------------------------------------- play

    fun play(file: File) {
        if (BagSession.player != null) stopPlayback()
        // Only on this device: isolated connection (with the demo it already is).
        val isLocal = local && !platform.isDemo()
        if (isLocal) BagSession.enterLocal()
        io.execute {
            if (isLocal) {
                val until = System.currentTimeMillis() + 10_000
                while ((RosConnection.status !is RosConnection.Status.Connected || RosConnection.config != BagSession.LOCAL_CONFIG) && System.currentTimeMillis() < until) Thread.sleep(100)
            }
            val result = try {
                Result.success(BagPlayer(file, Interfaces.get(), object : BagPlayer.Listener {
                    override fun onProgress(positionNs: Long, durationNs: Long) { playPosition = positionNs; playDuration = durationNs }
                    override fun onFinished(error: String?) {
                        Platform.main.post {
                            BagSession.player?.close()
                            BagSession.player = null
                            BagSession.leaveLocal()
                            updateLive()
                            if (error != null) message = tr("bags_playback_failed", error)
                        }
                    }
                }, direct = isLocal))
            } catch (e: Exception) { Result.failure(e) }
            Platform.main.post {
                result.fold({ p ->
                    if (RosConnection.status !is RosConnection.Status.Connected) {
                        p.close(); BagSession.leaveLocal(); message = tr("bags_not_connected"); return@fold
                    }
                    BagSession.player = p
                    if (p.direct) RosConnection.setBagPlayback(true)
                    playPosition = 0; playDuration = p.reader.durationNs
                    p.loop = loop; p.rate = rate
                    p.start()
                    if (p.skipped.isNotEmpty()) message = tr("bags_skipped", p.skipped.joinToString())
                    updateLive()
                }, { e ->
                    BagSession.leaveLocal()
                    message = e.message ?: tr("bags_could_not_open")
                })
            }
        }
    }

    fun togglePause() { BagSession.player?.let { it.paused = !it.paused; updateLive() } }
    fun seek(fraction: Float) {
        val target = (playDuration * fraction).toLong()
        BagSession.player?.seek(target)
        playPosition = target
        updateLive()
    }
    fun stopPlayback() { BagSession.stopPlayer(); updateLive() }

    // ---------------------------------------------------------------- list

    fun refreshList() {
        val list = BagSession.files()
        files = list
        for (f in list) {
            if (BagSession.recorder?.file == f) continue
            // Duration, messages and topics: reading the summary (fast) in the background.
            io.execute {
                val info = try {
                    McapReader.open(f).use { r -> tr("bags_file_info", fmtTime(r.durationNs), r.messageCount, r.channels.size) }
                } catch (e: Exception) { e.message ?: tr("bags_unreadable") }
                Platform.main.post { details[f.name] = tr("bags_file_details", size(f.length()), info) }
            }
        }
    }

    fun share(f: File) { platform.share(f)?.let { message = it } }

    fun delete(f: File) {
        deleting = null
        if (BagSession.player?.file == f) stopPlayback()
        f.delete()
        refreshList()
    }

    /** Copies a file chosen by the user and, if it is a valid MCAP, saves it under a free name. */
    fun importBag() {
        val pick = platform.pick ?: return
        pick { rawName, input ->
            if (input == null) return@pick
            io.execute {
                val name = rawName?.replace(Regex("[^A-Za-z0-9_.-]"), "_") ?: BagRecorder.defaultName()
                val dir = BagSession.dir()
                // Copied aside (it does not show in the list: it does not end in .mcap): an existing bag is never overwritten or deleted.
                val tmp = File(dir, ".import-${System.nanoTime()}.tmp")
                var dest: File? = null
                val error = try {
                    input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    McapReader.open(tmp).close()
                    synchronized(BagSession) {
                        val f = BagSession.freeFile(dir, name)
                        if (!tmp.renameTo(f)) throw java.io.IOException("No se pudo guardar ${f.name}")
                        dest = f
                    }
                    null
                } catch (e: Exception) { e.message ?: tr("bags_not_mcap") } finally { tmp.delete() }
                Platform.main.post {
                    message = error?.let { tr("bags_import_failed", it) } ?: tr("bags_imported", dest!!.name)
                    refreshList()
                }
            }
        }
    }

    companion object {
        const val KEY_TOPICS = "record_topics"
        const val KEY_LOCAL = "play_local"

        fun fmtTime(ns: Long): String {
            val s = ns / 1_000_000_000
            return "%d:%02d.%d".format(s / 60, s % 60, (ns / 100_000_000) % 10)
        }

        fun size(bytes: Long): String = when {
            bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
            bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

/** The tab with its own controller (desktop, dashboards). */
@Composable
fun BagsScreen(tabId: String, modifier: Modifier = Modifier) {
    val c = remember(tabId) { BagsController(tabId) }
    DisposableEffect(c) { c.resume(); onDispose { c.close() } }
    BagsView(c, modifier)
}

@Composable
fun BagsView(c: BagsController, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(c.message) { c.message?.let { snackbar.showSnackbar(it); c.message = null } }
    c.tick
    val r = BagSession.recorder
    val p = BagSession.player
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.recordOrStop() }, enabled = !c.stopping, modifier = Modifier.weight(1f),
                        colors = if (r != null) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) {
                        Icon(if (r != null) Icons.Default.Stop else Icons.Default.FiberManualRecord, null, Modifier.size(18.dp))
                        Text(" " + tr(if (r != null) "bags_stop" else "bags_record"))
                    }
                    if (c.canImport) OutlinedButton(onClick = { c.importBag() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp)); Text(" " + tr("bags_import"))
                    }
                }
            }
            if (r != null) item {
                // Read here too: the list skips this item otherwise and the statistics stay at their first values.
                c.tick
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(tr("bags_recording"), style = MaterialTheme.typography.titleMedium)
                        val perTopic = r.perTopic.entries.sortedBy { it.key }.joinToString("\n") { (t, n) -> "%6d  %s".format(n.get(), t) }
                        Text(tr("bags_record_stats", BagsController.fmtTime(r.elapsedMs * 1_000_000), r.messages.get(), BagsController.size(r.file.length())) +
                            (if (r.dropped.get() > 0) tr("bags_record_dropped", r.dropped.get()) else "") + "\n\n" + perTopic,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
            if (p != null) item { PlayCard(c, p) }
            else item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { c.changeLocal(!c.local) }) {
                    Checkbox(c.local, { c.changeLocal(it) }); Text(tr("bags_play_local"), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { Text(tr("bags_recordings"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            if (c.files.isEmpty()) item { Text(tr("bags_empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(c.files, key = { it.absolutePath }) { f ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 6.dp)) {
                        Text(f.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.details[f.name] ?: BagsController.size(f.length()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { c.play(f) }) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Text(" " + tr("bags_play")) }
                            TextButton(onClick = { c.share(f) }) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Text(" " + tr("bags_share")) }
                            TextButton(onClick = { c.deleting = f }) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Text(" " + tr("bags_delete")) }
                        }
                    }
                }
            }
            item { Box(Modifier.padding(bottom = 12.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    c.choosing?.let { known ->
        AlertDialog(
            onDismissRequest = { c.choosing = null },
            title = { Text(tr("bags_record_topics_title")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(known, key = { it.first }) { (name, type) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { if (name in c.chosen) c.chosen.remove(name) else c.chosen.add(name) }) {
                            Checkbox(name in c.chosen, { on -> if (on) c.chosen.add(name) else c.chosen.remove(name) })
                            Column { Text(name, fontFamily = FontFamily.Monospace, fontSize = 13.sp); Text(type, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { c.startRecording(known.filter { it.first in c.chosen }) }) { Text(tr("bags_record")) } },
            dismissButton = {
                Row {
                    // "All" checks everything without closing the dialog.
                    TextButton(onClick = { c.chosen.clear(); c.chosen.addAll(known.map { it.first }) }) { Text(tr("bags_all")) }
                    TextButton(onClick = { c.choosing = null }) { Text(tr("bags_cancel")) }
                }
            },
        )
    }
    c.deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { c.deleting = null },
            title = { Text(tr("bags_delete_title", f.name)) },
            confirmButton = { TextButton(onClick = { c.delete(f) }) { Text(tr("bags_delete")) } },
            dismissButton = { TextButton(onClick = { c.deleting = null }) { Text(tr("bags_cancel")) } },
        )
    }
}

@Composable
private fun PlayCard(c: BagsController, p: BagPlayer) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    // The position is not state: the tick (every half second) redraws the card.
    c.tick
    val dur = c.duration
    val frac = if (dur > 0) (c.position.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Slider(if (dragging) dragValue else frac, { dragging = true; dragValue = it },
                onValueChangeFinished = { dragging = false; c.seek(dragValue) }, modifier = Modifier.semantics2(tr("bags_seek")))
            Text(if (dragging) tr("bags_seek_to", BagsController.fmtTime((dur * dragValue).toLong()), BagsController.fmtTime(dur))
                else tr("bags_play_time", BagsController.fmtTime(c.position), BagsController.fmtTime(dur), p.topics.size) + if (p.paused) tr("bags_paused_suffix") else "",
                fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((r, key) in listOf(0.5 to "bags_rate_half", 1.0 to "bags_rate_one", 2.0 to "bags_rate_two")) FilterChip(c.rate == r, { c.changeRate(r) }, label = { Text(tr(key)) })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { c.changeLoop(!c.loop) }) { Checkbox(c.loop, { c.changeLoop(it) }); Text(tr("bags_loop")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.togglePause() }, modifier = Modifier.weight(1f)) { Text(tr(if (p.paused) "bags_resume" else "bags_pause")) }
                OutlinedButton(onClick = { c.stopPlayback() }, modifier = Modifier.weight(1f)) { Text(tr("bags_stop")) }
            }
        }
    }
}

private fun Modifier.semantics2(desc: String) = this.then(Modifier.semantics { contentDescription = desc })
