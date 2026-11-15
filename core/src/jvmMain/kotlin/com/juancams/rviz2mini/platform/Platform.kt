// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.platform

import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The little that the shared logic needs from each platform: the main thread, saved preferences,
 * assets, logging and network multicast. The Android app installs its own at startup
 * (VizMiniApplication); the desktop uses the defaults (its own thread, files in ~/.config/vizmini2).
 */
object Platform {
    /** UI thread: state, TF and result listeners are called here. */
    @Volatile var main: MainThread = SingleThreadMain()

    /** Preferences by name (on Android, the usual SharedPreferences with that name). */
    @Volatile var prefs: (String) -> KeyValueStore = { name -> FilePrefs.get(name) }

    /** Opens an assets file (on Android, those in app/src/main/assets). */
    @Volatile var openAsset: (String) -> InputStream = { name ->
        Platform::class.java.getResourceAsStream("/assets/$name") ?: throw java.io.FileNotFoundException("asset $name")
    }

    @Volatile var log: LogSink = StdLog

    /** UI strings (see [com.juancams.rviz2mini.i18n.tr]). */
    @Volatile var strings: com.juancams.rviz2mini.i18n.StringSource = LazyXmlStrings

    /**
     * Keeps Wi-Fi multicast open while there is a DDS node (Android filters it otherwise). Returns
     * how to release it. Not needed on desktop.
     */
    @Volatile var acquireMulticast: () -> (() -> Unit) = { {} }

    /** Platform, for the help text and reports ("android", "linux", "windows", "macos"). */
    @Volatile var name: String = System.getProperty("os.name", "jvm").lowercase().let {
        when {
            it.contains("linux") -> "linux"
            it.contains("win") -> "windows"
            it.contains("mac") -> "macos"
            else -> it
        }
    }
}

/** Like an Android Handler on the main thread. */
interface MainThread {
    fun post(r: () -> Unit)
    fun postDelayed(r: () -> Unit, delayMs: Long)
    fun postDelayed(r: Runnable, delayMs: Long) = postDelayed({ r.run() }, delayMs)
}

/** Default main thread (headless desktop, tests): its own thread that runs tasks in order. */
class SingleThreadMain : MainThread {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "vizmini2-main").apply { isDaemon = true }
    }
    override fun post(r: () -> Unit) { exec.execute(r) }
    override fun postDelayed(r: () -> Unit, delayMs: Long) { exec.schedule(r, delayMs, TimeUnit.MILLISECONDS) }
}

/** What is used from SharedPreferences. */
interface KeyValueStore {
    fun getString(key: String, def: String?): String?
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getFloat(key: String, def: Float): Float
    fun contains(key: String): Boolean
    val all: Map<String, *>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putFloat(key: String, value: Float): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/**
 * Preferences in .properties files (desktop): ~/.config/vizmini2/<name>.properties, or wherever the
 * `vizmini2.config` property says. Types are stored with a prefix ("i:3", "b:true"...).
 */
class FilePrefs private constructor(private val file: File) : KeyValueStore {
    private val map = ConcurrentHashMap<String, Any>()

    init {
        if (file.exists()) {
            val p = Properties()
            file.inputStream().use { p.load(it) }
            for (k in p.stringPropertyNames()) decode(p.getProperty(k))?.let { map[k] = it }
        }
    }

    override fun getString(key: String, def: String?) = map[key] as? String ?: def
    override fun getInt(key: String, def: Int) = (map[key] as? Number)?.toInt() ?: def
    override fun getLong(key: String, def: Long) = (map[key] as? Number)?.toLong() ?: def
    override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    override fun getFloat(key: String, def: Float) = (map[key] as? Number)?.toFloat() ?: def
    override fun contains(key: String) = map.containsKey(key)
    override val all: Map<String, *> get() = HashMap(map)

    override fun edit(): KeyValueStore.Editor = object : KeyValueStore.Editor {
        private val puts = HashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun remove(key: String) = apply { puts[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean = synchronized(this@FilePrefs) {
            if (clear) map.clear()
            for ((k, v) in puts) if (v == null) map.remove(k) else map[k] = v
            save()
        }
    }

    private fun save(): Boolean = try {
        file.parentFile?.mkdirs()
        val p = Properties()
        for ((k, v) in map) p.setProperty(k, encode(v))
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { p.store(it, null) }
        tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
    } catch (e: Exception) {
        Log.w("VizMini2", "No se pudieron guardar las preferencias en $file", e)
        false
    }

    private fun encode(v: Any): String = when (v) {
        is Boolean -> "b:$v"
        is Int -> "i:$v"
        is Long -> "l:$v"
        is Float -> "f:$v"
        else -> "s:$v"
    }

    private fun decode(s: String): Any? = when {
        s.startsWith("b:") -> s.substring(2).toBoolean()
        s.startsWith("i:") -> s.substring(2).toIntOrNull()
        s.startsWith("l:") -> s.substring(2).toLongOrNull()
        s.startsWith("f:") -> s.substring(2).toFloatOrNull()
        s.startsWith("s:") -> s.substring(2)
        else -> s
    }

    companion object {
        private val open = ConcurrentHashMap<String, FilePrefs>()

        /** Desktop configuration folder. */
        val dir: File get() = File(System.getProperty("vizmini2.config") ?: (System.getProperty("user.home") + "/.config/vizmini2"))

        fun get(name: String): FilePrefs = open.getOrPut(name) { FilePrefs(File(dir, "$name.properties")) }
    }
}

interface LogSink {
    fun log(level: Char, tag: String, msg: String, t: Throwable?)
}

object StdLog : LogSink {
    override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
        val line = "$level/$tag: $msg"
        if (level == 'E' || level == 'W') System.err.println(line) else println(line)
        t?.printStackTrace()
    }
}

/** Like android.util.Log, so shared code writes to logcat on Android and to the console on PC. */
object Log {
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    @JvmStatic fun println(priority: Int, tag: String, msg: String): Int {
        Platform.log.log(when (priority) { ERROR -> 'E'; WARN -> 'W'; INFO -> 'I'; else -> 'D' }, tag, msg, null); return 0
    }
    @JvmStatic fun getStackTraceString(t: Throwable?): String =
        t?.let { java.io.StringWriter().also { w -> it.printStackTrace(java.io.PrintWriter(w)) }.toString() } ?: ""
    @JvmStatic fun d(tag: String, msg: String): Int { Platform.log.log('D', tag, msg, null); return 0 }
    @JvmStatic fun i(tag: String, msg: String): Int { Platform.log.log('I', tag, msg, null); return 0 }
    @JvmStatic fun w(tag: String, msg: String): Int { Platform.log.log('W', tag, msg, null); return 0 }
    @JvmStatic fun w(tag: String, msg: String, t: Throwable?): Int { Platform.log.log('W', tag, msg, t); return 0 }
    @JvmStatic fun e(tag: String, msg: String): Int { Platform.log.log('E', tag, msg, null); return 0 }
    @JvmStatic fun e(tag: String, msg: String, t: Throwable?): Int { Platform.log.log('E', tag, msg, t); return 0 }
}

/** The strings.xml files on the classpath, loaded the first time they are requested. */
private object LazyXmlStrings : com.juancams.rviz2mini.i18n.StringSource {
    private val real by lazy { com.juancams.rviz2mini.i18n.XmlStrings() }
    override fun get(key: String) = real.get(key)
    override fun plural(key: String, count: Int) = real.plural(key, count)
}

/**
 * Like android.os.SystemClock: on Android it uses its own (VizMiniApplication), so shared code and the
 * Android UI measure with the same clock; elsewhere, the JVM monotonic clock.
 */
object SystemClock {
    @Volatile var uptime: () -> Long = { System.nanoTime() / 1_000_000 }
    @Volatile var elapsed: () -> Long = { System.nanoTime() / 1_000_000 }
    @JvmStatic fun uptimeMillis(): Long = uptime()
    @JvmStatic fun elapsedRealtime(): Long = elapsed()
    @JvmStatic fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() } }
}

/** A decoded image: ARGB pixels (row by row). */
class DecodedImage(val width: Int, val height: Int, val pixels: IntArray)

/**
 * Decoding JPEG/PNG (sensor_msgs/CompressedImage): provided by each platform (Android, BitmapFactory; PC,
 * ImageIO). Without a decoder, null.
 */
object ImageCodecs {
    @Volatile var decode: (ByteArray, Int, Int) -> DecodedImage? = { _, _, _ -> null }
}
