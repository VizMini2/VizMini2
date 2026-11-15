// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.juancams.rviz2mini.MainActivity
import com.juancams.rviz2mini.R
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosStrings
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.TopicDisplay
import us.ihmc.jros2.ROS2SubscriptionCallback

/**
 * Watches the network while the app is alive (also in the background with the connection service) and
 * notifies: loss of contact, diagnostics turning ERROR/STALE, lifecycle nodes
 * that disappear, get finalized or fail, and the user's topic rules (the Alerts tab, [TopicAlertMonitor]).
 * The decisions are in [AlertRules]; every notice also goes to [AlertLog].
 */
object AlertMonitor {

    private const val TAG = "VizMini2"
    private const val CHANNEL_ID = "alerts"
    private const val TICK_MS = 3000L

    private var appContext: Context? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var settings = AlertSettings()

    // Only from the monitor thread.
    private var diagRules = AlertRules.Diagnostics()
    private val lifecycleRules = AlertRules.Presence("Lifecycle node")
    private val contactRules = AlertRules.Contact()
    private val topicRules by lazy { TopicAlertMonitor(Interfaces.get()) { notify(it) } }
    private var diagSubscription: RosConnection.ManagedSubscription<*>? = null
    private val transitionSubs = HashMap<String, DynRos.Subscription>()
    private var lastDomain = -1

    private val statusListener: (RosConnection.Status) -> Unit = { s ->
        // When the network changes (or on reconnect) what was seen before no longer applies.
        if (s is RosConnection.Status.Connecting) handler?.post { resetTrackers() }
    }

    private val tick = object : Runnable {
        override fun run() {
            try { check() } catch (t: Throwable) { Log.e(TAG, "Error en el monitor de alertas", t) }
            handler?.postDelayed(this, TICK_MS)
        }
    }

    /** Starts (once) with the saved configuration. */
    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        settings = AlertSettings.load(context)
        if (thread != null) return
        createChannel(context)
        TopicAlertMonitors.current = topicRules
        TopicAlertMonitors.whereKey = "alerts_where_android"
        com.juancams.rviz2mini.ui.screens.AlertsScreens.extra = { AlertsTabSwitch() }
        thread = HandlerThread("alerts").also { it.start() }
        handler = Handler(thread!!.looper)
        RosConnection.addStatusListener(statusListener)
        handler!!.post(tick)
    }

    /** Applies new settings (from Config). */
    fun apply(context: Context, s: AlertSettings) {
        AlertSettings.save(context, s)
        settings = s
        handler?.post {
            diagRules = AlertRules.Diagnostics(includeWarnings = s.diagnosticWarnings)
            syncSubscriptions()
        }
    }

    private fun resetTrackers() {
        diagRules = AlertRules.Diagnostics(includeWarnings = settings.diagnosticWarnings)
        lifecycleRules.reset()
        contactRules.reset()
    }

    private fun check() {
        val s = settings
        syncSubscriptions()
        if (!s.enabled || RosConnection.status !is RosConnection.Status.Connected) return
        val domain = RosConnection.config.domainId
        if (domain != lastDomain) { lastDomain = domain; resetTrackers() }
        val g = RosConnection.graph() ?: return
        if (s.connection) contactRules.update(g.hasOthers())?.let { notify(it) }
        topicRules.sync(TopicRules.load())
        topicRules.tick()
        if (s.lifecycle) {
            val nodes = g.lifecycleNodes().toSet()
            for (a in lifecycleRules.update(nodes)) notify(a)
            syncTransitionSubscriptions(nodes)
        }
    }

    /** Subscription to /diagnostics only while needed. */
    private fun syncSubscriptions() {
        val s = settings
        val wantDiag = s.enabled && s.diagnostics
        if (wantDiag && diagSubscription == null) {
            diagSubscription = RosConnection.subscribe("/diagnostics", diagnostic_msgs.DiagnosticArray::class.java,
                TopicDisplay.qosProfile(QosPreset.RELIABLE), ROS2SubscriptionCallback { reader ->
                    val a = reader.read() ?: return@ROS2SubscriptionCallback
                    val now = System.currentTimeMillis()
                    val st = a.status
                    val alerts = (0 until st.size()).mapNotNull { i ->
                        val d = st.get(i)
                        synchronized(this) { diagRules.update(RosStrings.fix(d.name), d.level.toInt(), RosStrings.fix(d.message), now) }
                    }
                    for (al in alerts) notify(al)
                })
        } else if (!wantDiag && diagSubscription != null) {
            diagSubscription?.cancel()
            diagSubscription = null
        }
        if (!(s.enabled && s.lifecycle)) syncTransitionSubscriptions(emptySet())
    }

    /** transition_event of each visible lifecycle node: notifies if it goes to finalized or errorprocessing. */
    private fun syncTransitionSubscriptions(nodes: Set<String>) {
        val ctx = appContext ?: return
        for (gone in transitionSubs.keys - nodes) transitionSubs.remove(gone)?.close()
        for (n in nodes - transitionSubs.keys) {
            try {
                val spec = Interfaces.get().msg("lifecycle_msgs/msg/TransitionEvent")
                transitionSubs[n] = DynRos.subscribe("$n/transition_event", spec, RosConnection.defaultQos()) { v ->
                    val goal = v["goal_state"] as? Map<*, *> ?: return@subscribe
                    val id = (goal["id"] as? Number)?.toInt() ?: return@subscribe
                    AlertRules.lifecycleAlert(n, id, goal["label"] as? String ?: "")?.let { notify(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo vigilar $n/transition_event", e)
            }
        }
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.alerts_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.alerts_channel_description)
        })
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Also used from Config for the test notification. */
    fun notify(alert: AlertRules.Alert) {
        // The topic rules log their own notices.
        if (!alert.key.startsWith("rule:")) AlertLog.add(alert)
        val ctx = appContext ?: return
        if (!settings.enabled || !canNotify(ctx)) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val color = ctx.getColor(when (alert.severity) {
            AlertRules.Severity.ERROR -> R.color.status_error
            AlertRules.Severity.WARNING -> R.color.status_warn
            AlertRules.Severity.RECOVERED -> R.color.status_ok
        })
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setColor(color)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (alert.severity == AlertRules.Severity.RECOVERED) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        try {
            // Same id per key: "recovered" replaces the loss notice.
            NotificationManagerCompat.from(ctx).notify(alert.key.hashCode(), n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso de notificaciones", e)
        }
    }
}
