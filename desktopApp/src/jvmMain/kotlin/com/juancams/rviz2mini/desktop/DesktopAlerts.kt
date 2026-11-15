// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.alerts.AlertLog
import com.juancams.rviz2mini.alerts.AlertRules
import com.juancams.rviz2mini.alerts.TopicAlertMonitor
import com.juancams.rviz2mini.alerts.TopicAlertMonitors
import com.juancams.rviz2mini.alerts.TopicRules
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import java.io.File

/**
 * Alerts on the PC while the app is open: the user's topic rules (the Alerts tab), losing contact with the robot and
 * diagnostics turning ERROR. As desktop notifications (`notify-send`, from libnotify, which GNOME, KDE and the others
 * show; otherwise the system tray) and in the Alerts tab's history.
 */
object DesktopAlerts {
    private const val PREFS = "rviz2mini_alerts"
    private const val TICK_MS = 3000L
    private val prefs get() = Platform.prefs(PREFS)
    var enabled: Boolean
        get() = prefs.getBoolean("desktop_enabled", true)
        set(v) { prefs.edit().putBoolean("desktop_enabled", v).apply() }
    var contact: Boolean
        get() = prefs.getBoolean("desktop_contact", true)
        set(v) { prefs.edit().putBoolean("desktop_contact", v).apply() }
    var diagnostics: Boolean
        get() = prefs.getBoolean("desktop_diagnostics", true)
        set(v) { prefs.edit().putBoolean("desktop_diagnostics", v).apply() }

    private val monitor by lazy { TopicAlertMonitor(Interfaces.get()) { notify(it) } }
    private val contactRule = AlertRules.Contact()
    private val diagRule = AlertRules.Diagnostics()
    private var diagSub: DynRos.Subscription? = null
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        TopicAlertMonitors.current = monitor
        TopicAlertMonitors.whereKey = "alerts_where_desktop"
        com.juancams.rviz2mini.ui.screens.AlertsScreens.extra = { Switches() }
        Thread({
            while (true) {
                runCatching { check() }
                Thread.sleep(TICK_MS)
            }
        }, "alerts").apply { isDaemon = true }.start()
    }

    private fun check() {
        val on = enabled && RosConnection.status is RosConnection.Status.Connected
        if (!on) { monitor.sync(emptyList()); diagSub?.close(); diagSub = null; return }
        monitor.sync(TopicRules.load())
        monitor.tick()
        if (contact) RosConnection.graph()?.let { g -> contactRule.update(g.hasOthers())?.let { emit(it) } }
        if (diagnostics && diagSub == null) diagSub = runCatching {
            DynRos.subscribe("/diagnostics", Interfaces.get().msg("diagnostic_msgs/msg/DiagnosticArray"), RosConnection.defaultQos()) { v ->
                val now = System.currentTimeMillis()
                for (s in (v["status"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }) {
                    val level = (s["level"] as? Number)?.toInt() ?: continue
                    diagRule.update(s["name"]?.toString().orEmpty(), level, s["message"]?.toString().orEmpty(), now)?.let { emit(it) }
                }
            }
        }.getOrNull()
        if (!diagnostics) { diagSub?.close(); diagSub = null }
    }

    private fun emit(a: AlertRules.Alert) { AlertLog.add(a); notify(a) }

    private val notifySend: String? by lazy {
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "notify-send") }.firstOrNull { it.canExecute() }?.absolutePath
    }
    private var tray: java.awt.TrayIcon? = null

    /** A desktop notification (the history already has it). */
    fun notify(a: AlertRules.Alert) {
        if (!enabled) return
        val urgency = when (a.severity) { AlertRules.Severity.ERROR -> "critical"; AlertRules.Severity.WARNING -> "normal"; AlertRules.Severity.RECOVERED -> "low" }
        val ns = notifySend
        if (ns != null) {
            val icon = File(System.getProperty("user.home"), ".local/share/vizmini2/vizmini2.png").takeIf { it.exists() }?.absolutePath ?: "dialog-warning"
            runCatching { ProcessBuilder(ns, "-a", "VizMini2", "-u", urgency, "-i", icon, a.title, a.text).redirectErrorStream(true).start() }
            return
        }
        runCatching {
            if (!java.awt.SystemTray.isSupported()) return
            val t = tray ?: java.awt.TrayIcon(java.awt.Toolkit.getDefaultToolkit().getImage(javaClass.getResource("/vizmini2.png")), "VizMini2")
                .apply { isImageAutoSize = true; java.awt.SystemTray.getSystemTray().add(this) }.also { tray = it }
            t.displayMessage(a.title, a.text, when (a.severity) { AlertRules.Severity.ERROR -> java.awt.TrayIcon.MessageType.ERROR; AlertRules.Severity.WARNING -> java.awt.TrayIcon.MessageType.WARNING; else -> java.awt.TrayIcon.MessageType.INFO })
        }
    }

    /** "Video saved" (with the file), even with alerts turned off. */
    fun notifySaved(f: File) {
        val ns = notifySend ?: return
        runCatching { ProcessBuilder(ns, "-a", "VizMini2", "-u", "low", "-i", "video-x-generic", tr("record_saved_title"), f.absolutePath).start() }
    }

    @Composable
    private fun Switches() {
        Column {
            @Composable fun row(label: String, value: Boolean, set: (Boolean) -> Unit) {
                var v by remember { mutableStateOf(value) }
                Row(Modifier.clickable { v = !v; set(v) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(v, { v = it; set(it) }); Text(label, fontSize = 13.sp)
                }
            }
            row(tr("alerts_desktop_enabled"), enabled) { enabled = it }
            row(tr("alerts_desktop_contact"), contact) { contact = it }
            row(tr("alerts_desktop_diagnostics"), diagnostics) { diagnostics = it }
        }
    }
}
