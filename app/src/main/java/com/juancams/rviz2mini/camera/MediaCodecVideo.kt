// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.camera

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.DecodedImage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * H.264/H.265 with the phone's decoder (MediaCodec, in hardware where there is one). Its own thread feeds the packets
 * and takes the frames out as YUV 4:2:0 images, which become ARGB.
 */
class MediaCodecVideoDecoder private constructor(
    private val codec: VideoCodec, private val onFrame: (DecodedImage) -> Unit, private val onError: (String) -> Unit,
) : VideoDecoder {
    private val queue = LinkedBlockingQueue<VideoPacket>(QUEUE)
    @Volatile private var closed = false
    @Volatile private var needKey = false
    private val thread = Thread({ loop() }, "video-decoder").apply { isDaemon = true; start() }
    private val mime = if (codec == VideoCodec.H264) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC

    override fun submit(p: VideoPacket) {
        if (closed) return
        if (needKey) { if (!p.keyframe) return; needKey = false }
        if (!queue.offer(p)) { queue.clear(); needKey = !p.keyframe; if (p.keyframe) queue.offer(p) }
    }

    private fun loop() {
        var mc: MediaCodec? = null
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        var pixels = IntArray(0)
        try {
            while (!closed) {
                val p = queue.poll(10, TimeUnit.MILLISECONDS)
                if (p != null) {
                    if (mc == null) {
                        // The size is only a hint (the decoder takes the real one from the SPS).
                        val f = MediaFormat.createVideoFormat(mime, p.width.takeIf { it > 0 } ?: 1280, p.height.takeIf { it > 0 } ?: 720)
                        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        mc = MediaCodec.createDecoderByType(mime).apply { configure(f, null, null, 0); start() }
                    }
                    val i = mc.dequeueInputBuffer(20_000)
                    if (i >= 0) {
                        val buf = mc.getInputBuffer(i)!!
                        buf.clear()
                        if (p.data.size > buf.remaining()) { mc.queueInputBuffer(i, 0, 0, pts, 0); continue }
                        buf.put(p.data)
                        // Increasing timestamps (some decoders reorder by them).
                        pts = maxOf(pts + 1, p.ptsUs)
                        mc.queueInputBuffer(i, 0, p.data.size, pts, 0)
                    }
                }
                val m = mc ?: continue
                while (!closed) {
                    val o = m.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val img = runCatching { m.getOutputImage(o) }.getOrNull()
                    if (img != null && info.size > 0) {
                        val w = img.cropRect.width(); val h = img.cropRect.height()
                        if (pixels.size != w * h) pixels = IntArray(w * h)
                        Yuv.toArgb(img, pixels)
                        img.close()
                        onFrame(DecodedImage(w, h, pixels.copyOf()))
                    } else img?.close()
                    m.releaseOutputBuffer(o, false)
                }
            }
        } catch (e: Exception) {
            Log.w("VizMini2", "Video decoder ($mime)", e)
            if (!closed) onError(tr("video_decoder_failed", e.message ?: e.javaClass.simpleName))
        } finally {
            runCatching { mc?.stop() }; runCatching { mc?.release() }
        }
    }

    override fun close() {
        closed = true
        thread.interrupt()
    }

    companion object {
        private const val QUEUE = 60

        /** For [VideoDecoders.create]; null if the phone has no decoder for that codec. */
        fun create(codec: VideoCodec, onFrame: (DecodedImage) -> Unit, onError: (String) -> Unit): VideoDecoder? {
            val mime = if (codec == VideoCodec.H264) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
            val has = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && mime in it.supportedTypes }
            return if (has) MediaCodecVideoDecoder(codec, onFrame, onError) else null
        }
    }
}

/** YUV 4:2:0 (android.media.Image, any plane layout) <-> ARGB, BT.601 limited range, with integers. */
object Yuv {
    private var y = ByteArray(0); private var u = ByteArray(0); private var v = ByteArray(0)

    @Synchronized fun toArgb(img: Image, out: IntArray) {
        val crop = img.cropRect
        val w = crop.width(); val h = crop.height()
        val (py, pu, pv) = img.planes
        y = copy(py.buffer, y); u = copy(pu.buffer, u); v = copy(pv.buffer, v)
        val yRow = py.rowStride; val yPix = py.pixelStride
        val cRow = pu.rowStride; val cPix = pu.pixelStride
        val vRow = pv.rowStride; val vPix = pv.pixelStride
        var o = 0
        for (r in 0 until h) {
            val yr = (r + crop.top) * yRow
            val cr = ((r + crop.top) shr 1) * cRow
            val vr = ((r + crop.top) shr 1) * vRow
            for (c in 0 until w) {
                val x = c + crop.left
                val yy = ((y[yr + x * yPix].toInt() and 0xFF) - 16).coerceAtLeast(0) * 298
                val uu = (u[cr + (x shr 1) * cPix].toInt() and 0xFF) - 128
                val vv = (v[vr + (x shr 1) * vPix].toInt() and 0xFF) - 128
                val rr = (yy + 409 * vv + 128) shr 8
                val gg = (yy - 100 * uu - 208 * vv + 128) shr 8
                val bb = (yy + 516 * uu + 128) shr 8
                out[o++] = (0xFF shl 24) or (clamp(rr) shl 16) or (clamp(gg) shl 8) or clamp(bb)
            }
        }
    }

    /** ARGB into an encoder's input image (YUV 4:2:0, any plane layout). */
    fun fromArgb(argb: IntArray, w: Int, h: Int, img: Image) {
        val (py, pu, pv) = img.planes
        val yb = py.buffer; val ub = pu.buffer; val vb = pv.buffer
        for (r in 0 until h) for (c in 0 until w) {
            val p = argb[r * w + c]
            val R = (p shr 16) and 0xFF; val G = (p shr 8) and 0xFF; val B = p and 0xFF
            yb.put(r * py.rowStride + c * py.pixelStride, (((66 * R + 129 * G + 25 * B + 128) shr 8) + 16).toByte())
            if (r and 1 == 0 && c and 1 == 0) {
                ub.put((r shr 1) * pu.rowStride + (c shr 1) * pu.pixelStride, (((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128).toByte())
                vb.put((r shr 1) * pv.rowStride + (c shr 1) * pv.pixelStride, (((112 * R - 94 * G - 18 * B + 128) shr 8) + 128).toByte())
            }
        }
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    private fun copy(b: java.nio.ByteBuffer, into: ByteArray): ByteArray {
        val d = b.duplicate(); d.rewind()
        val n = d.remaining()
        val a = if (into.size >= n) into else ByteArray(n)
        d.get(a, 0, n)
        return a
    }
}
