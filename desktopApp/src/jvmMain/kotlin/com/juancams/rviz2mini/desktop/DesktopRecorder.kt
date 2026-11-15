// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juancams.rviz2mini.i18n.tr
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Records the app's window to an MP4 (dashboards and the 3D view, as they look): Skia's own picture of the window
 * (Compose paints in software, so it does not depend on the desktop allowing screen capture, which Wayland does not
 * for X11 apps) [FPS] times per second, encoded by the system's ffmpeg (H.264). Saved in ~/Videos/VizMini2.
 */
object DesktopRecorder {
    const val FPS = 15
    @Volatile var window: java.awt.Window? = null
    var recording by mutableStateOf(false); private set
    var startedMs by mutableLongStateOf(0L); private set
    /** The last file saved (or the reason it could not be). */
    var last by mutableStateOf<String?>(null); private set

    private var proc: Process? = null
    private var thread: Thread? = null
    @Volatile private var stopRequested = false
    private var file: File? = null

    private fun skiaLayer(c: Component): SkiaLayer? {
        if (c is SkiaLayer) return c
        if (c is Container) for (k in c.components) skiaLayer(k)?.let { return it }
        return null
    }

    /** ~/Videos/VizMini2 (the XDG videos folder if the desktop says another one). */
    fun folder(): File {
        val home = System.getProperty("user.home")
        val xdg = runCatching {
            File(home, ".config/user-dirs.dirs").readLines().firstOrNull { it.startsWith("XDG_VIDEOS_DIR=") }
                ?.substringAfter('=')?.trim('"')?.replace("\$HOME", home)
        }.getOrNull()
        return File(xdg ?: "$home/Videos", "VizMini2")
    }

    fun toggle() = if (recording) stop() else start()

    /** Starts recording to `to` (default: a new file in [folder]); false and the reason in [last] if it cannot. */
    @Synchronized fun start(to: File? = null): Boolean {
        if (recording) return true
        val w = window ?: return false
        val layer = skiaLayer(w) ?: run { last = tr("record_failed", "no Skia layer"); return false }
        val ffmpeg = FfmpegVideoDecoder.find() ?: run { last = tr("video_needs_ffmpeg"); return false }
        // Even sizes (H.264 4:2:0); fixed for the whole video (a resized window is cropped or padded).
        val width = layer.width / 2 * 2; val height = layer.height / 2 * 2
        if (width < 16 || height < 16) return false
        val out = to ?: File(folder().apply { mkdirs() }, "vizmini2-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".mp4")
        val cmd = listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${width}x$height", "-r", "$FPS", "-i", "pipe:0",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out.absolutePath)
        val p = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
            .getOrElse { last = tr("record_failed", it.message ?: "ffmpeg"); return false }
        proc = p; file = out; stopRequested = false
        recording = true; startedMs = System.currentTimeMillis(); last = null
        thread = Thread({ loop(p, layer, width, height) }, "recorder").apply { isDaemon = true; start() }
        return true
    }

    private fun loop(p: Process, layer: SkiaLayer, width: Int, height: Int) {
        val frame = ByteArray(width * height * 4)
        val period = 1_000_000_000L / FPS
        var next = System.nanoTime()
        val out = p.outputStream.buffered(1 shl 20)
        try {
            while (!stopRequested) {
                // Skia's picture is taken on the Swing thread, where the window paints.
                var bytes: ByteArray? = null; var bw = 0; var bh = 0
                SwingUtilities.invokeAndWait {
                    val bmp = runCatching { layer.screenshot() }.getOrNull() ?: return@invokeAndWait
                    try { bw = bmp.width; bh = bmp.height; bytes = bmp.readPixels(bmp.imageInfo.withColorType(org.jetbrains.skia.ColorType.BGRA_8888), bw * 4, 0, 0) } finally { bmp.close() }
                }
                val b = bytes
                if (b != null) {
                    if (bw == width && bh == height) out.write(b)
                    else {
                        java.util.Arrays.fill(frame, 0)
                        for (r in 0 until minOf(height, bh)) System.arraycopy(b, r * bw * 4, frame, r * width * 4, minOf(width, bw) * 4)
                        out.write(frame)
                    }
                }
                next += period
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else next = System.nanoTime()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { out.close() }
        }
    }

    /** Stops and finishes the file (ffmpeg writes the MP4 index when its input ends). */
    @Synchronized fun stop(): File? {
        if (!recording) return null
        stopRequested = true
        thread?.join(3000)
        val p = proc
        val ok = p != null && p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
        if (p != null && p.isAlive) p.destroyForcibly()
        recording = false; proc = null; thread = null
        val f = file
        last = if (ok && f != null && f.length() > 0) f.absolutePath else tr("record_failed", "ffmpeg")
        if (ok && f != null) DesktopAlerts.notifySaved(f)
        return if (ok) f else null
    }
}
