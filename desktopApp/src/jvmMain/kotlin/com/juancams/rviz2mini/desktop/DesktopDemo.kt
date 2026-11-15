// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juancams.rviz2mini.demo.DemoRobot
import com.juancams.rviz2mini.demo.DemoVideoSink
import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.demo.DemoTabs
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Demo mode on the PC, as on the phone: saves the connection and the tabs, connects to a network of this computer only
 * ([DemoRobot.NETWORK]), puts the demo's tabs and runs the simulated robot. Exiting puts everything back. It survives
 * closing the app (the robot starts again on the next run).
 */
object DesktopDemo {
    private const val PREFS = "rviz2mini_demo"
    private const val KEY_BACKUP = "backup"

    /** Whether demo mode is on (the UI follows it). */
    var active by mutableStateOf(Platform.prefs(PREFS).contains(KEY_BACKUP)); private set
    @Volatile private var robot: DemoRobot? = null

    /** Enters demo mode and returns its tabs (already in the [TabStore]). */
    fun enter(): List<TabSpec> {
        if (!active) {
            val backup = JSONObject().put("ros", RosConfig.load().toJson()).put("tabs", TabList.toJson(TabStore.get().all))
            Platform.prefs(PREFS).edit().putString(KEY_BACKUP, backup.toString()).apply()
            active = true
            RosConnection.applyConfig(DemoRobot.NETWORK)
            TabStore.get().replaceAll(DemoTabs.all())
        }
        startRobot()
        return TabStore.get().all
    }

    /** Leaves demo mode: the robot stops and the connection and tabs come back. */
    fun exit(): List<TabSpec> {
        val prefs = Platform.prefs(PREFS)
        val backup = prefs.getString(KEY_BACKUP, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        robot?.stop(); robot = null
        if (backup != null) {
            RosConnection.applyConfig(RosConfig.fromJson(backup.optJSONObject("ros")))
            TabStore.get().replaceAll(TabList.fromJson(backup.optString("tabs")) ?: emptyList())
        }
        prefs.edit().remove(KEY_BACKUP).apply()
        active = false
        return TabStore.get().all
    }

    /** At startup, with demo mode on (the app was closed in it): the robot again. */
    fun resumeIfActive() { if (active) startRobot() }

    /** Stops the robot without leaving demo mode (closing the app). */
    fun stopRobot() { robot?.stop(); robot = null }

    /** The robot publishes on the app's node: it starts once that one is connected to the demo's network. */
    private fun startRobot() {
        if (robot != null) return
        Thread {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && active && robot == null) {
                if (RosConnection.status is RosConnection.Status.Connected && RosConnection.config == DemoRobot.NETWORK) {
                    synchronized(this) {
                        if (robot == null && active) robot = runCatching { DemoRobot { FfmpegDemoVideo.create(it) }.also { r -> r.start() } }
                            .onFailure { Log.e("VizMini2", "Could not start the demo robot", it) }.getOrNull()
                    }
                    return@Thread
                }
                Thread.sleep(200)
            }
        }.apply { isDaemon = true; name = "demo-start" }.start()
    }
}

/**
 * The demo camera as H.264 video with ffmpeg (libx264), like the phone's MediaCodec: only while someone is subscribed,
 * a key frame with its SPS and PPS every second. Without ffmpeg there is just no video topic.
 */
private class FfmpegDemoVideo(private val exe: String, publisher: (String, String) -> Pair<MsgSpec, DynRos.Publisher>) : DemoVideoSink {
    private val pub = publisher(DemoRobot.VIDEO_TOPIC, "foxglove_msgs/msg/CompressedVideo").second
    private var proc: Process? = null
    private var size = 0 to 0
    /** The stamps of the frames sent and not yet encoded (x264 without B-frames returns them in order). */
    private val stamps = java.util.concurrent.ConcurrentLinkedQueue<MsgValue>()
    @Volatile private var failed = false

    override fun frame(rgb: ByteArray, w: Int, h: Int, stamp: MsgValue) {
        if (failed) return
        if (pub.matched() == 0) { stop(); return }
        try {
            val p = proc?.takeIf { it.isAlive && size == w to h } ?: start(w, h)
            stamps.add(stamp)
            p.outputStream.write(rgb, 0, w * h * 3); p.outputStream.flush()
        } catch (e: Exception) {
            Log.w("VizMini2", "Demo: no H.264 encoder (ffmpeg with libx264)", e)
            failed = true; stop()
        }
    }

    private fun start(w: Int, h: Int): Process {
        stop()
        size = w to h
        val p = ProcessBuilder(exe, "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${w}x$h", "-framerate", "5", "-i", "pipe:0",
            "-an", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-g", "5", "-bf", "0", "-pix_fmt", "yuv420p",
            "-x264-params", "aud=1:repeat-headers=1", "-f", "h264", "pipe:1")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        proc = p
        Thread({ readAccessUnits(p) }, "demo-h264").apply { isDaemon = true }.start()
        return p
    }

    /** Splits the Annex B stream at each access unit delimiter (NAL type 9) and publishes each frame. */
    private fun readAccessUnits(p: Process) {
        val input = p.inputStream.buffered()
        val au = ByteArrayOutputStream()
        var zeros = 0
        fun publish() {
            if (au.size() == 0) return
            val data = au.toByteArray(); au.reset()
            val stamp = stamps.poll() ?: com.juancams.rviz2mini.ros.dyn.DynCodec.timeValue(System.currentTimeMillis() * 1_000_000)
            runCatching { pub.publish(MsgValue().apply { put("timestamp", stamp); put("frame_id", "camera_optical"); put("data", data); put("format", "h264") }) }
        }
        try {
            while (true) {
                val b = input.read()
                if (b < 0) break
                // A start code (00 00 01 or 00 00 00 01) followed by an AUD: a new frame starts there.
                if (b == 1 && zeros >= 2) {
                    input.mark(1)
                    val nal = input.read()
                    if (nal >= 0 && (nal and 0x1F) == 9) {
                        val start = au.toByteArray()
                        val keep = minOf(zeros, 3)
                        au.reset(); au.write(start, 0, start.size - keep)
                        publish()
                        repeat(keep) { au.write(0) }
                    }
                    au.write(1)
                    if (nal >= 0) au.write(nal)
                    zeros = 0
                    continue
                }
                zeros = if (b == 0) zeros + 1 else 0
                au.write(b)
            }
            publish()
        } catch (_: Exception) { }
    }

    private fun stop() {
        proc?.let { runCatching { it.outputStream.close() }; it.destroy() }
        proc = null; stamps.clear()
    }

    override fun close() = stop()

    companion object {
        fun create(publisher: (String, String) -> Pair<MsgSpec, DynRos.Publisher>): DemoVideoSink? =
            FfmpegVideoDecoder.find()?.let { exe -> FfmpegDemoVideo(exe, publisher) }
    }
}
