// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.demo

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.juancams.rviz2mini.camera.Yuv
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue

/**
 * The demo camera also as H.264 video (/camera/image_raw/foxglove, foxglove_msgs/CompressedVideo, as
 * foxglove_compressed_video_transport publishes it), encoded with the phone's MediaCodec: to try the video topics
 * without a robot. Only while someone is subscribed; a key frame (with its SPS and PPS) every second.
 */
internal class DemoVideo(registry: InterfaceRegistry, private val publisher: (String, String) -> Pair<com.juancams.rviz2mini.ros.dyn.MsgSpec, DynRos.Publisher>) : DemoVideoSink {
    private val pub = publisher(TOPIC, "foxglove_msgs/msg/CompressedVideo").second
    private var enc: MediaCodec? = null
    private var size = 0 to 0
    private var config: ByteArray? = null
    private var argb = IntArray(0)
    private val info = MediaCodec.BufferInfo()
    @Volatile private var failed = false

    /** One rgb8 frame of the demo camera (from the simulator thread). */
    override fun frame(rgb: ByteArray, w: Int, h: Int, stamp: MsgValue) {
        if (failed) return
        if (pub.matched() == 0) { stopEncoder(); return }
        try {
            val e = enc?.takeIf { size == w to h } ?: start(w, h)
            val i = e.dequeueInputBuffer(5_000)
            if (i >= 0) {
                if (argb.size != w * h) argb = IntArray(w * h)
                for (k in 0 until w * h) argb[k] = (0xFF shl 24) or ((rgb[3 * k].toInt() and 0xFF) shl 16) or ((rgb[3 * k + 1].toInt() and 0xFF) shl 8) or (rgb[3 * k + 2].toInt() and 0xFF)
                val img = e.getInputImage(i)!!
                Yuv.fromArgb(argb, w, h, img)
                e.queueInputBuffer(i, 0, w * h * 3 / 2, System.nanoTime() / 1000, 0)
            }
            while (true) {
                val o = e.dequeueOutputBuffer(info, 10_000)
                if (o < 0) break
                val b = e.getOutputBuffer(o)!!
                val bytes = ByteArray(info.size).also { b.position(info.offset); b.get(it) }
                e.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { config = bytes; continue }
                // Each key frame carries its parameter sets, so a viewer can join at any moment.
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val data = if (key) (config ?: ByteArray(0)) + bytes else bytes
                pub.publish(MsgValue().apply { put("timestamp", stamp); put("frame_id", "camera_optical"); put("data", data); put("format", "h264") })
            }
        } catch (e: Exception) {
            Log.w("VizMini2", "Demo: no H.264 encoder", e)
            failed = true
            stopEncoder()
        }
    }

    private fun start(w: Int, h: Int): MediaCodec {
        stopEncoder()
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 5)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        size = w to h
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply { configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }.also { enc = it }
    }

    private fun stopEncoder() {
        enc?.let { runCatching { it.stop() }; runCatching { it.release() } }
        enc = null; config = null
    }

    override fun close() = stopEncoder()

    companion object {
        const val TOPIC = DemoRobot.VIDEO_TOPIC
    }
}
