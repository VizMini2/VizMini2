// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.camera

import com.juancams.rviz2mini.platform.DecodedImage
import com.juancams.rviz2mini.ros.dyn.MsgValue

/** The video codecs the app decodes (H.264 and H.265, the ones ROS cameras and encoders use). */
enum class VideoCodec(val label: String) {
    H264("H.264"), H265("H.265");

    companion object {
        /**
         * From foxglove's `format` ("h264", "h265") or ffmpeg_image_transport's `encoding` (the encoder:
         * "libx264", "h264_nvenc", "hevc_vaapi"...). Null for the others (vp9, av1...).
         */
        fun of(name: String): VideoCodec? {
            val n = name.trim().lowercase()
            return when {
                n == "h264" || n == "avc" || n.startsWith("libx264") || n.startsWith("h264_") || n.startsWith("h264;") -> H264
                n == "h265" || n == "hevc" || n.startsWith("libx265") || n.startsWith("hevc_") || n.startsWith("h265_") || n.startsWith("hevc;") -> H265
                else -> null
            }
        }
    }
}

/** One encoded frame (an access unit, Annex B with start codes) from a video topic. */
class VideoPacket(
    val codec: VideoCodec, val data: ByteArray, val keyframe: Boolean, val ptsUs: Long,
    /** From the message (FFMPEGPacket) or the H.264 SPS; 0 if unknown. */
    val width: Int = 0, val height: Int = 0, val format: String = codec.label,
)

/** A decoder of one stream (each platform's: MediaCodec on Android, ffmpeg on the PC). Frames come on its own thread. */
interface VideoDecoder {
    fun submit(p: VideoPacket)
    fun close()
}

/** The platform's video decoders. `create(codec, onFrame, onError)`; null: the platform cannot decode that codec. */
object VideoDecoders {
    @Volatile var create: (VideoCodec, (DecodedImage) -> Unit, (String) -> Unit) -> VideoDecoder? = { _, _, _ -> null }
}

/** H.264/H.265 bitstreams: NAL units, key frames and the H.264 frame size. */
object Nal {
    /** (offset of the NAL header, length) of each NAL unit of an Annex B buffer. */
    fun units(d: ByteArray, len: Int = d.size): List<IntArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < len) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) { starts += i + 3; i += 3 } else i++
        }
        return starts.mapIndexed { k, s ->
            var end = if (k + 1 < starts.size) starts[k + 1] - 3 else len
            // The zero of a 4-byte start code belongs to the next one.
            while (end > s && d[end - 1].toInt() == 0 && k + 1 < starts.size) end--
            intArrayOf(s, end - s)
        }
    }

    /**
     * An elementary stream cut into access units (frames): a new one at each access unit delimiter (H.264 9,
     * H.265 35) that comes after other NAL units. For streams with delimiters (x264/x265 `aud=1`).
     */
    fun accessUnits(codec: VideoCodec, d: ByteArray): List<ByteArray> {
        val aud = if (codec == VideoCodec.H264) 9 else 35
        val out = ArrayList<ByteArray>()
        var start = -1
        var sawOther = false
        for (u in units(d)) {
            val isAud = type(codec, d[u[0]]) == aud
            if (isAud && sawOther) { out += d.copyOfRange(start, u[0] - 3); start = u[0] - 3; sawOther = false }
            if (start < 0) start = u[0] - 3
            if (!isAud) sawOther = true
        }
        if (start >= 0) out += d.copyOfRange(start, d.size)
        return out
    }

    fun type(codec: VideoCodec, header: Byte): Int = if (codec == VideoCodec.H264) header.toInt() and 0x1F else (header.toInt() shr 1) and 0x3F

    /** Whether the buffer starts with a start code (Annex B) rather than a length (AVCC/HVCC, as MP4 stores it). */
    fun isAnnexB(d: ByteArray): Boolean =
        d.size >= 4 && d[0].toInt() == 0 && d[1].toInt() == 0 && (d[2].toInt() == 1 || (d[2].toInt() == 0 && d[3].toInt() == 1))

    /** AVCC (4-byte big-endian lengths) to Annex B; the buffer as is if it is already Annex B or does not parse. */
    fun toAnnexB(d: ByteArray): ByteArray {
        if (isAnnexB(d)) return d
        val out = java.io.ByteArrayOutputStream(d.size + 16)
        var i = 0
        while (i + 4 <= d.size) {
            val n = ((d[i].toInt() and 0xFF) shl 24) or ((d[i + 1].toInt() and 0xFF) shl 16) or ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
            if (n <= 0 || i + 4 + n > d.size) return d
            out.write(START); out.write(d, i + 4, n); i += 4 + n
        }
        return if (i == d.size) out.toByteArray() else d
    }

    private val START = byteArrayOf(0, 0, 0, 1)

    /** A frame a decoder can start from: an IDR (H.264 5; H.265 16..21, IRAP) with its parameter sets. */
    fun isKeyframe(codec: VideoCodec, d: ByteArray): Boolean {
        val types = units(d).map { type(codec, d[it[0]]) }
        return if (codec == VideoCodec.H264) 5 in types && 7 in types else types.any { it in 16..21 } && 33 in types
    }

    /** Width and height from the H.264 SPS of the buffer (with its cropping); null if there is none or it does not parse. */
    fun h264Size(d: ByteArray): Pair<Int, Int>? {
        val sps = units(d).firstOrNull { (d[it[0]].toInt() and 0x1F) == 7 } ?: return null
        return runCatching { parseSps(unescape(d, sps[0] + 1, sps[1] - 1)) }.getOrNull()
    }

    /** Removes the emulation prevention bytes (00 00 03 -> 00 00). */
    private fun unescape(d: ByteArray, off: Int, len: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(len)
        var zeros = 0
        for (i in off until off + len) {
            val b = d[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class Bits(val d: ByteArray) {
        var pos = 0
        fun bit(): Int { val b = (d[pos shr 3].toInt() shr (7 - (pos and 7))) and 1; pos++; return b }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int { var z = 0; while (bit() == 0) { z++; if (z > 31) throw IllegalStateException() }; return ((1 shl z) - 1) + bits(z) }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    private fun parseSps(s: ByteArray): Pair<Int, Int> {
        val b = Bits(s)
        val profile = b.bits(8); b.bits(8); b.bits(8); b.ue()
        var chroma = 1
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = b.ue()
            if (chroma == 3) b.bit()
            b.ue(); b.ue(); b.bit()
            if (b.bit() == 1) for (i in 0 until if (chroma != 3) 8 else 12) if (b.bit() == 1) {
                var last = 8; var next = 8
                for (j in 0 until if (i < 6) 16 else 64) { if (next != 0) next = (last + b.se() + 256) % 256; if (next != 0) last = next }
            }
        }
        b.ue()
        when (b.ue()) {
            0 -> b.ue()
            1 -> { b.bit(); b.se(); b.se(); repeat(b.ue()) { b.se() } }
        }
        b.ue(); b.bit()
        val wMbs = b.ue() + 1
        val hMapUnits = b.ue() + 1
        val frameMbsOnly = b.bit()
        if (frameMbsOnly == 0) b.bit()
        b.bit()
        var w = wMbs * 16
        var h = (2 - frameMbsOnly) * hMapUnits * 16
        if (b.bit() == 1) {
            val l = b.ue(); val r = b.ue(); val t = b.ue(); val bo = b.ue()
            val cx = if (chroma == 0 || chroma == 3) 1 else 2
            val cy = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
            w -= (l + r) * cx; h -= (t + bo) * cy
        }
        return w to h
    }

    /**
     * The packet of a video message: foxglove_msgs/CompressedVideo (`format`, `data`, `timestamp`) or
     * ffmpeg_image_transport_msgs/FFMPEGPacket (`encoding`, `data`, `flags` bit 0 = key frame, `width`, `height`, `pts`).
     * Null if the codec is not supported or there are no data.
     */
    fun packet(kind: ImageKind, v: MsgValue): VideoPacket? {
        val raw = v["data"] as? ByteArray ?: return null
        if (raw.isEmpty()) return null
        val name = (if (kind == ImageKind.FFMPEG) v["encoding"] else v["format"])?.toString().orEmpty()
        val codec = VideoCodec.of(name) ?: return null
        val data = toAnnexB(raw)
        val key = isKeyframe(codec, data) || (kind == ImageKind.FFMPEG && ((v["flags"] as? Number)?.toInt() ?: 0) and 1 == 1 && hasParameterSets(codec, data))
        val (w, h) = if (kind == ImageKind.FFMPEG) ((v["width"] as? Number)?.toInt() ?: 0) to ((v["height"] as? Number)?.toInt() ?: 0)
            else if (codec == VideoCodec.H264 && key) h264Size(data) ?: (0 to 0) else 0 to 0
        val pts = if (kind == ImageKind.FFMPEG) (v["pts"] as? Number)?.toLong() ?: 0L
            else ((v["timestamp"] as? Map<*, *>)?.let { t -> ((t["sec"] as? Number)?.toLong() ?: 0L) * 1_000_000L + ((t["nanosec"] as? Number)?.toLong() ?: 0L) / 1000 } ?: 0L)
        return VideoPacket(codec, data, key, pts, w, h, name.ifEmpty { codec.label })
    }

    private fun hasParameterSets(codec: VideoCodec, d: ByteArray): Boolean {
        val types = units(d).map { type(codec, d[it[0]]) }
        return if (codec == VideoCodec.H264) 7 in types else 33 in types
    }
}

/**
 * Subscription to a video topic: the packets go to the platform's decoder from the first key frame on (before it
 * there is nothing to decode against); the decoded frames come through `onFrame` on the decoder's thread.
 */
class VideoStream(
    private val topic: ImageTopic,
    private val qos: us.ihmc.jros2.ROS2QoSProfile,
    private val onFrame: (DecodedImage, VideoPacket) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var sub: com.juancams.rviz2mini.ros.dyn.DynRos.Subscription? = null
    private var decoder: VideoDecoder? = null
    private var codec: VideoCodec? = null
    @Volatile private var last: VideoPacket? = null
    @Volatile private var active = false
    private var waitingKey = true

    fun start() {
        if (active) return
        active = true
        val spec = runCatching { com.juancams.rviz2mini.ros.dyn.Interfaces.get().msg(topic.kind.rosType) }.getOrElse { onError(it.message ?: "?"); return }
        sub = com.juancams.rviz2mini.ros.dyn.DynRos.subscribe(topic.name, spec, qos) { v -> if (active) onMessage(v) }
    }

    @Synchronized private fun onMessage(v: MsgValue) {
        val p = Nal.packet(topic.kind, v)
        if (p == null) {
            val name = (v["format"] ?: v["encoding"])?.toString().orEmpty()
            onError(com.juancams.rviz2mini.i18n.tr("video_unsupported", name)); return
        }
        if (codec != p.codec) {
            decoder?.close(); decoder = null; codec = p.codec; waitingKey = true
        }
        if (waitingKey) { if (!p.keyframe) return; waitingKey = false }
        var reported = false
        val d = decoder ?: VideoDecoders.create(p.codec, { img -> if (active) last?.let { onFrame(img, it) } }, { e -> reported = true; if (active) onError(e) })
            ?.also { decoder = it }
            ?: run { if (!reported) onError(com.juancams.rviz2mini.i18n.tr("video_no_decoder", p.codec.label)); waitingKey = true; return }
        last = p
        d.submit(p)
    }

    fun stop() {
        active = false
        sub?.close(); sub = null
        synchronized(this) { decoder?.close(); decoder = null; codec = null; waitingKey = true }
    }
}
