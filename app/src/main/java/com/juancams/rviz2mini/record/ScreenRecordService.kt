// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.juancams.rviz2mini.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Screen recording (the 3D view, a dashboard... whatever is on screen) with MediaProjection: the system asks for
 * permission each time, the recording runs in a foreground service with a Stop action, and the video (H.264 MP4,
 * the screen's aspect at up to 1280 px) goes to Movies/VizMini2.
 */
class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var file: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopRecording(); stopSelf(); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        // Android 14: the foreground service (of type mediaProjection) before asking for the projection.
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, n)
        try { start(code, data) } catch (e: Exception) {
            Log.w("VizMini2", "Screen recording", e)
            stopRecording(failed = e.message ?: e.javaClass.simpleName); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("no projection")
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Handler(Looper.getMainLooper()).post { stopRecording(); stopSelf() } }
        }, Handler(Looper.getMainLooper()))
        val m = resources.displayMetrics
        // Up to 1280 on the long side, even sizes (H.264).
        val scale = minOf(1.0, 1280.0 / maxOf(m.widthPixels, m.heightPixels))
        val w = (m.widthPixels * scale).toInt() / 2 * 2
        val h = (m.heightPixels * scale).toInt() / 2 * 2
        val name = "vizmini2-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".mp4"
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(w, h)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(6_000_000)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VizMini2")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("no MediaStore")
            uri = u
            pfd = contentResolver.openFileDescriptor(u, "w")
            r.setOutputFile(pfd!!.fileDescriptor)
        } else {
            // Before Android 10, the app's own Movies folder (no storage permission needed).
            val f = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), name)
            file = f
            r.setOutputFile(f.absolutePath)
        }
        r.prepare()
        display = p.createVirtualDisplay("VizMini2", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        r.start()
        recorder = r
        setRecording(true)
    }

    private fun stopRecording(failed: String? = null) {
        val r = recorder
        recorder = null
        val ok = failed == null && r != null && runCatching { r.stop() }.isSuccess
        runCatching { r?.release() }
        runCatching { display?.release() }; display = null
        runCatching { projection?.stop() }; projection = null
        runCatching { pfd?.close() }; pfd = null
        val u = uri
        if (u != null) {
            if (ok) contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            else runCatching { contentResolver.delete(u, null, null) }
        }
        // Before Android 10 the file is the app's own: shared through its FileProvider.
        val shown = u ?: file?.let { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", it) }
        if (r != null || failed != null) savedNotification(ok, if (ok) shown else null, failed)
        uri = null; file = null
        setRecording(false)
    }

    override fun onDestroy() { if (recorder != null) stopRecording(); super.onDestroy() }

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.record_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification {
        channel()
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.record_notification))
            .setOngoing(true)
            .setUsesChronometer(true)
            .addAction(0, getString(R.string.record_notification_stop), stop)
            .build()
    }

    private fun savedNotification(ok: Boolean, video: Uri?, failed: String?) {
        channel()
        val b = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome).setAutoCancel(true)
        if (ok && video != null) {
            val open = PendingIntent.getActivity(this, 2, Intent(Intent.ACTION_VIEW).setDataAndType(video, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE)
            b.setContentTitle(getString(R.string.record_saved_title)).setContentText(getString(R.string.record_saved_in, "Movies/VizMini2")).setContentIntent(open)
        } else b.setContentTitle(getString(R.string.record_failed, failed ?: "?"))
        // Without the notifications permission (Android 13+) there is simply no "saved" notice.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID + 1, b.build()) } catch (_: SecurityException) { }
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.juancams.rviz2mini.record.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile var recording = false; private set
        private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
        fun addListener(l: (Boolean) -> Unit) { listeners += l }
        fun removeListener(l: (Boolean) -> Unit) { listeners -= l }
        private fun setRecording(v: Boolean) { recording = v; Handler(Looper.getMainLooper()).post { listeners.forEach { it(v) } } }

        /** With the result of MediaProjectionManager.createScreenCaptureIntent(). */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenRecordService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) { context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP)) }

        /** The screen capture request (the system's dialog). */
        fun captureIntent(context: Context): Intent = context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
    }
}
