// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.camera

import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** H.264/H.265 bitstreams (the test videos in resources/video, see their README) and the video messages. */
class VideoTest {
    companion object {
        fun bytes(name: String): ByteArray = VideoTest::class.java.getResourceAsStream("/video/$name")!!.use { it.readBytes() }

        fun frames(codec: VideoCodec, d: ByteArray): List<ByteArray> = Nal.accessUnits(codec, d)
    }

    private val h264 = frames(VideoCodec.H264, bytes("blue_100x60.h264"))
    private val h265 = frames(VideoCodec.H265, bytes("blue_100x60.h265"))

    @Test fun codecNames() {
        for (n in listOf("h264", "H264", "avc", "libx264", "h264_nvenc", "h264_vaapi")) assertEquals(n, VideoCodec.H264, VideoCodec.of(n))
        for (n in listOf("h265", "hevc", "libx265", "hevc_nvenc", "hevc_qsv")) assertEquals(n, VideoCodec.H265, VideoCodec.of(n))
        for (n in listOf("vp9", "av1", "libaom-av1", "")) assertNull(n, VideoCodec.of(n))
    }

    @Test fun keyFramesEveryFive() {
        assertEquals(10, h264.size); assertEquals(10, h265.size)
        for ((codec, list) in listOf(VideoCodec.H264 to h264, VideoCodec.H265 to h265))
            assertEquals("$codec", listOf(0, 5), list.indices.filter { Nal.isKeyframe(codec, list[it]) })
    }

    @Test fun h264SizeFromTheSpsWithCropping() {
        // 100x60 is coded as 112x64 macroblocks with cropping.
        assertEquals(100 to 60, Nal.h264Size(h264[0]))
        assertNull(Nal.h264Size(h264[1]))
    }

    @Test fun avccBecomesAnnexB() {
        val au = h264[0]
        val avcc = java.io.ByteArrayOutputStream()
        for (u in Nal.units(au)) {
            val n = u[1]
            avcc.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
            avcc.write(au, u[0], n)
        }
        val a = avcc.toByteArray()
        assertFalse(Nal.isAnnexB(a))
        val back = Nal.toAnnexB(a)
        assertTrue(Nal.isAnnexB(back))
        assertTrue(Nal.isKeyframe(VideoCodec.H264, back))
        assertEquals(Nal.units(au).map { au.copyOfRange(it[0], it[0] + it[1]).toList() }, Nal.units(back).map { back.copyOfRange(it[0], it[0] + it[1]).toList() })
        // Already Annex B: untouched.
        assertArrayEquals(au, Nal.toAnnexB(au))
    }

    @Test fun packetsFromBothMessageTypes() {
        val fox = Nal.packet(ImageKind.VIDEO, MsgValue().apply {
            put("format", "h264"); put("data", h264[0]); put("frame_id", "cam")
            put("timestamp", MsgValue().apply { put("sec", 3L); put("nanosec", 500_000_000L) })
        })!!
        assertEquals(VideoCodec.H264, fox.codec); assertTrue(fox.keyframe); assertEquals(100, fox.width); assertEquals(60, fox.height)
        assertEquals(3_500_000L, fox.ptsUs)
        val ff = Nal.packet(ImageKind.FFMPEG, MsgValue().apply {
            put("encoding", "libx265"); put("data", h265[1]); put("flags", 0L); put("width", 100L); put("height", 60L); put("pts", 7L)
        })!!
        assertEquals(VideoCodec.H265, ff.codec); assertFalse(ff.keyframe); assertEquals(100, ff.width); assertEquals(7L, ff.pts())
        assertNull(Nal.packet(ImageKind.VIDEO, MsgValue().apply { put("format", "vp9"); put("data", h264[0]) }))
        assertNull(Nal.packet(ImageKind.VIDEO, MsgValue().apply { put("format", "h264"); put("data", ByteArray(0)) }))
    }

    private fun VideoPacket.pts() = ptsUs

    @Test fun videoKindsAreImageKinds() {
        assertTrue(ImageKind.VIDEO.isVideo && ImageKind.FFMPEG.isVideo && !ImageKind.RAW.isVideo)
        assertTrue("foxglove_msgs/msg/CompressedVideo" in ImageKind.rosTypes)
    }
}
