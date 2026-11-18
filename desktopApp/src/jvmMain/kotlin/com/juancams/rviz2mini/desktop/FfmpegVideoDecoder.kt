// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import com.juancams.rviz2mini.camera.VideoCodec
import com.juancams.rviz2mini.camera.VideoDecoder
import com.juancams.rviz2mini.camera.VideoPacket
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.DecodedImage
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * H.264/H.265 on the PC with the system's ffmpeg (`apt install ffmpeg`; $VIZMINI2_FFMPEG to use another one): the
 * elementary stream goes in through stdin and each frame comes out as a BMP (which carries its size, so a change of
 * resolution needs nothing special). So that it does not hold frames back: the low-delay options (but not
 * `-fflags nobuffer`, which throws away the probed data and with it the first key frame), slice threads instead of
 * frame threads (each one holds a frame), and each frame's access unit delimiter moved from its start to its end
 * (ffmpeg's stream parser only knows that a frame has ended when the next one starts).
 */
class FfmpegVideoDecoder private constructor(
    private val codec: VideoCodec, private val exe: String,
    private val onFrame: (DecodedImage) -> Unit, private val onError: (String) -> Unit,
) : VideoDecoder {
    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE)
    @Volatile private var closed = false
    /** After dropping packets (ffmpeg could not keep up) it waits for a key frame. */
    @Volatile private var needKey = false
    private val stderrTail = ArrayDeque<String>()
    private val aud = if (codec == VideoCodec.H264) byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) else byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50)
    private val proc: Process = ProcessBuilder(command()).redirectErrorStream(false).start()

    init {
        Thread({ writeLoop() }, "ffmpeg-in").apply { isDaemon = true }.start()
        Thread({ readLoop(proc.inputStream) }, "ffmpeg-out").apply { isDaemon = true }.start()
        Thread({
            proc.errorStream.bufferedReader().forEachLine { l -> synchronized(stderrTail) { stderrTail.addLast(l); while (stderrTail.size > 5) stderrTail.removeFirst() } }
        }, "ffmpeg-err").apply { isDaemon = true }.start()
    }

    private fun command(): List<String> = listOf(
        exe, "-hide_banner", "-loglevel", "error",
        "-flags", "low_delay", "-probesize", "32", "-analyzeduration", "0",
        "-thread_type", "slice", "-f", if (codec == VideoCodec.H264) "h264" else "hevc", "-i", "pipe:0",
        "-an", *passthrough(exe), "-f", "image2pipe", "-c:v", "bmp", "-pix_fmt", "bgr24", "pipe:1",
    )

    override fun submit(p: VideoPacket) {
        if (closed) return
        if (needKey) { if (!p.keyframe) return; needKey = false }
        if (!queue.offer(p.data)) { queue.clear(); needKey = !p.keyframe; if (p.keyframe) queue.offer(p.data) }
    }

    private fun writeLoop() {
        val out = proc.outputStream
        try {
            while (!closed) {
                val d = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.write(withoutLeadingAud(d)); out.write(aud); out.flush()
            }
        } catch (_: Exception) {
        } finally { runCatching { out.close() } }
    }

    /** BMP after BMP: the 14-byte file header says the total size and where the pixels start. */
    private fun readLoop(input: InputStream) {
        val din = DataInputStream(input.buffered(1 shl 20))
        val head = ByteArray(14)
        var pixels = IntArray(0)
        try {
            while (!closed) {
                din.readFully(head)
                if (head[0] != 'B'.code.toByte() || head[1] != 'M'.code.toByte()) throw IllegalStateException("not a BMP")
                val size = le32(head, 2)
                val dataOffset = le32(head, 10)
                val rest = ByteArray(size - 14)
                din.readFully(rest)
                val w = le32(rest, 4)
                val hRaw = le32(rest, 8)
                val bpp = (rest[14].toInt() and 0xFF) or ((rest[15].toInt() and 0xFF) shl 8)
                val h = Math.abs(hRaw)
                if (w <= 0 || h <= 0 || (bpp != 24 && bpp != 32)) continue
                val stride = ((bpp * w + 31) / 32) * 4
                val bytesPerPx = bpp / 8
                if (pixels.size != w * h) pixels = IntArray(w * h)
                val px = pixels
                for (row in 0 until h) {
                    // Bottom-up unless the height is negative.
                    val src = dataOffset - 14 + (if (hRaw > 0) h - 1 - row else row) * stride
                    var o = row * w
                    var i = src
                    for (x in 0 until w) {
                        px[o++] = (0xFF shl 24) or ((rest[i + 2].toInt() and 0xFF) shl 16) or ((rest[i + 1].toInt() and 0xFF) shl 8) or (rest[i].toInt() and 0xFF)
                        i += bytesPerPx
                    }
                }
                onFrame(DecodedImage(w, h, px.copyOf()))
            }
        } catch (_: java.io.EOFException) {
            if (!closed) onError(tr("video_decoder_failed", synchronized(stderrTail) { stderrTail.lastOrNull() } ?: "ffmpeg exited"))
        } catch (e: Exception) {
            if (!closed) onError(tr("video_decoder_failed", e.message ?: e.javaClass.simpleName))
        }
    }

    private val audType = if (codec == VideoCodec.H264) 9 else 35

    private fun withoutLeadingAud(d: ByteArray): ByteArray {
        val u = com.juancams.rviz2mini.camera.Nal.units(d)
        if (u.size < 2 || com.juancams.rviz2mini.camera.Nal.type(codec, d[u[0][0]]) != audType) return d
        var s = u[1][0] - 3
        if (s > 0 && d[s - 1].toInt() == 0) s--
        return d.copyOfRange(s, d.size)
    }

    override fun close() {
        closed = true
        runCatching { proc.outputStream.close() }
        proc.destroy()
        if (!proc.waitFor(500, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
    }

    companion object {
        private const val QUEUE = 60

        private fun le32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        /** The ffmpeg to use: $VIZMINI2_FFMPEG or the first one in the PATH (null if there is none). */
        fun find(): String? {
            System.getenv("VIZMINI2_FFMPEG")?.takeIf { File(it).canExecute() }?.let { return it }
            val exe = if (File.separatorChar == '\\') "ffmpeg.exe" else "ffmpeg"
            return System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, exe) }.firstOrNull { it.canExecute() }?.absolutePath
        }

        private val versions = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

        /** One output frame per input frame: -fps_mode since ffmpeg 5.1, -vsync before (Ubuntu 20.04 and 22.04 have 4.x). */
        private fun passthrough(exe: String): Array<String> {
            val v = versions.getOrPut(exe) {
                runCatching {
                    val p = ProcessBuilder(exe, "-version").redirectErrorStream(true).start()
                    val line = p.inputStream.bufferedReader().readLine().orEmpty(); p.waitFor(2, TimeUnit.SECONDS)
                    Regex("""version n?(\d+)\.(\d+)""").find(line)?.destructured?.let { (a, b) -> intArrayOf(a.toInt(), b.toInt()) }
                }.getOrNull() ?: intArrayOf(7, 0)
            }
            return if (v[0] > 5 || (v[0] == 5 && v[1] >= 1)) arrayOf("-fps_mode", "passthrough") else arrayOf("-vsync", "0")
        }

        /** For [com.juancams.rviz2mini.camera.VideoDecoders.create]: null (and the reason through onError) without ffmpeg. */
        fun create(codec: VideoCodec, onFrame: (DecodedImage) -> Unit, onError: (String) -> Unit): VideoDecoder? {
            val exe = find() ?: run { onError(tr("video_needs_ffmpeg")); return null }
            return runCatching { FfmpegVideoDecoder(codec, exe, onFrame, onError) }.getOrElse { onError(tr("video_decoder_failed", it.message ?: "?")); null }
        }
    }
}
