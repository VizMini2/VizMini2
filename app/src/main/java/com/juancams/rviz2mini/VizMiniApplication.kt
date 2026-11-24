// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.asImageBitmap
import com.juancams.rviz2mini.platform.KeyValueStore
import com.juancams.rviz2mini.platform.LogSink
import com.juancams.rviz2mini.platform.MainThread
import com.juancams.rviz2mini.platform.Platform

/** Installs the Android parts into the platform layer of the shared code (core) before anything else. */
class VizMiniApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installPlatform(this)
        AppTheme.apply(this)
        // The shared UI texts, in the language of the visible activity (the one the app picks).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) { current = java.lang.ref.WeakReference(a) }
            override fun onActivityResumed(a: android.app.Activity) { current = java.lang.ref.WeakReference(a) }
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    companion object {
        @Volatile private var installed = false
        @Volatile private var current: java.lang.ref.WeakReference<android.app.Activity>? = null

        /** Also from the tests, in case the process starts without going through onCreate. Only the first call does anything. */
        @Synchronized
        fun installPlatform(context: Context) {
            if (installed) return
            installed = true
            val app = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            Platform.name = "android"
            com.juancams.rviz2mini.platform.SystemClock.uptime = { android.os.SystemClock.uptimeMillis() }
            com.juancams.rviz2mini.platform.SystemClock.elapsed = { android.os.SystemClock.elapsedRealtime() }
            Platform.main = object : MainThread {
                override fun post(r: () -> Unit) { handler.post(r) }
                override fun postDelayed(r: () -> Unit, delayMs: Long) { handler.postDelayed(r, delayMs) }
                override fun postDelayed(r: Runnable, delayMs: Long) { handler.postDelayed(r, delayMs) }
            }
            Platform.prefs = { name -> SharedPrefsStore(app.getSharedPreferences(name, Context.MODE_PRIVATE)) }
            Platform.openAsset = { name -> app.assets.open(name) }
            com.juancams.rviz2mini.rviz.AndroidRviz.install(app)
            // Bags (and plot CSVs): the same folder as always.
            com.juancams.rviz2mini.bag.BagSession.baseDir = { app.getExternalFilesDir(null) ?: app.filesDir }
            // Map tiles (GPS): the app's cache and its own User-Agent, as the OpenStreetMap policy requires.
            com.juancams.rviz2mini.gps.OsmTiles.cacheDir = java.io.File(app.cacheDir, "osm-tiles")
            com.juancams.rviz2mini.gps.OsmTiles.userAgent = app.packageName
            com.juancams.rviz2mini.ui.common.UiImages.fromArgb = { px, w, h ->
                android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
            }
            // H.264/H.265 video topics: the phone's MediaCodec.
            com.juancams.rviz2mini.camera.VideoDecoders.create = com.juancams.rviz2mini.camera.MediaCodecVideoDecoder::create
            com.juancams.rviz2mini.platform.ImageCodecs.decode = { bytes, offset, length ->
                android.graphics.BitmapFactory.decodeByteArray(bytes, offset, length)?.let { b ->
                    val px = IntArray(b.width * b.height)
                    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                    com.juancams.rviz2mini.platform.DecodedImage(b.width, b.height, px).also { b.recycle() }
                }
            }
            Platform.strings = object : com.juancams.rviz2mini.i18n.StringSource {
                private fun res() = (current?.get() ?: app).resources
                override fun get(key: String): String? {
                    val id = app.resources.getIdentifier(key, "string", app.packageName)
                    return if (id == 0) null else res().getString(id)
                }
                override fun plural(key: String, count: Int): String? {
                    val id = app.resources.getIdentifier(key, "plurals", app.packageName)
                    return if (id == 0) null else res().getQuantityText(id, count).toString()
                }
            }
            Platform.log = object : LogSink {
                override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
                    when (level) {
                        'E' -> android.util.Log.e(tag, msg, t)
                        'W' -> android.util.Log.w(tag, msg, t)
                        'I' -> android.util.Log.i(tag, msg, t)
                        else -> android.util.Log.d(tag, msg, t)
                    }
                }
            }
            // Without this lock Android drops Wi-Fi multicast and DDS discovers nobody.
            Platform.acquireMulticast = {
                val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lock = wifi.createMulticastLock("ROS2_DDS_LOCK").apply { setReferenceCounted(false); acquire() }
                ({ if (lock.isHeld) lock.release() })
            }
        }
    }
}

/** SharedPreferences as [KeyValueStore] (same file names and keys as before: nothing is lost). */
class SharedPrefsStore(private val p: android.content.SharedPreferences) : KeyValueStore {
    override fun getString(key: String, def: String?) = p.getString(key, def)
    override fun getInt(key: String, def: Int) = p.getInt(key, def)
    override fun getLong(key: String, def: Long) = p.getLong(key, def)
    override fun getBoolean(key: String, def: Boolean) = p.getBoolean(key, def)
    override fun getFloat(key: String, def: Float) = p.getFloat(key, def)
    override fun contains(key: String) = p.contains(key)
    override val all: Map<String, *> get() = p.all
    override fun edit(): KeyValueStore.Editor = object : KeyValueStore.Editor {
        private val e = p.edit()
        override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
        override fun putFloat(key: String, value: Float) = apply { e.putFloat(key, value) }
        override fun remove(key: String) = apply { e.remove(key) }
        override fun clear() = apply { e.clear() }
        override fun apply() = e.apply()
        override fun commit() = e.commit()
    }
}
