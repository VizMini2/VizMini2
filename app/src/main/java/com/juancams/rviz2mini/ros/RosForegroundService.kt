// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.juancams.rviz2mini.MainActivity
import com.juancams.rviz2mini.R

/**
 * Optional foreground service (Config → "Keep the connection in the background"): with the
 * app in the background Android may kill the process or put the Wi-Fi to sleep and the DDS node disappears
 * from the graph. With the service the node stays alive (TF, discovery) and everything is ready on return.
 * Tabs still pause when hidden: teleop never publishes in the background.
 */
class RosForegroundService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private val statusListener: (RosConnection.Status) -> Unit = { notifyStatus(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel(this)
        val n = buildNotification(RosConnection.status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID, n)
        // Without this many phones save power by turning off the radio and DDS loses discovery.
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VizMini2:dds").apply { setReferenceCounted(false); acquire() }
        RosConnection.start() // in case the system recreated only the service
        RosConnection.addStatusListener(statusListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            setEnabled(this, false)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        RosConnection.removeStatusListener(statusListener)
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        super.onDestroy()
    }

    private fun notifyStatus(s: RosConnection.Status) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try { nm.notify(NOTIFICATION_ID, buildNotification(s)) } catch (_: SecurityException) {}
    }

    private fun buildNotification(s: RosConnection.Status): Notification {
        val text = when (s) {
            is RosConnection.Status.Connected -> getString(R.string.notif_connected, s.domainId, s.nodeName)
            RosConnection.Status.Connecting -> getString(R.string.notif_connecting)
            RosConnection.Status.Disconnected -> getString(R.string.notif_disconnected)
            is RosConnection.Status.Error -> getString(R.string.notif_error, s.message)
        }
        // Same as the launcher icon: if the app is already open it is brought to the front, without duplicating it.
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(
            this, 0, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, RosForegroundService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_frame)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), disconnect)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ros_connection"
        private const val NOTIFICATION_ID = 7701
        private const val ACTION_DISCONNECT = "com.juancams.rviz2mini.action.STOP_BACKGROUND"
        private const val PREFS = "rviz2mini_app"
        private const val KEY_ENABLED = "keepConnection"

        /** For the Config tab and the tests. */
        @Volatile var running = false
            private set

        /** Notifies Config when it is disabled from the notification. */
        @Volatile var onDisabled: (() -> Unit)? = null

        fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(ctx: Context, enabled: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (!enabled) onDisabled?.invoke()
        }

        /** Starts if enabled (from a visible activity: Android does not allow it in the background). */
        fun startIfEnabled(ctx: Context) {
            if (isEnabled(ctx) && !running) ContextCompat.startForegroundService(ctx, Intent(ctx, RosForegroundService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RosForegroundService::class.java))
        }

        private fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ctx.getString(R.string.notif_channel_description)
                    setShowBadge(false)
                }
            )
        }
    }
}
