// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import com.juancams.rviz2mini.camera.Nal
import com.juancams.rviz2mini.camera.VideoCodec
import com.juancams.rviz2mini.camera.VideoPacket
import com.juancams.rviz2mini.platform.DecodedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The PC's video decoder with the system's ffmpeg (skipped without it) on core's test videos (#3366CC, 100x60). */
class FfmpegVideoDecoderTest {
    private fun video(name: String) = listOf(File("../core/src/jvmTest/resources/video/$name"), File("core/src/jvmTest/resources/video/$name")).first { it.exists() }.readBytes()

    private fun decode(codec: VideoCodec, file: String) {
        assumeTrue("no ffmpeg", FfmpegVideoDecoder.find() != null)
        val frames = CopyOnWriteArrayList<DecodedImage>()
        val errors = CopyOnWriteArrayList<String>()
        val d = FfmpegVideoDecoder.create(codec, { frames += it }, { errors += it })!!
        try {
            for ((i, au) in Nal.accessUnits(codec, video(file)).withIndex()) d.submit(VideoPacket(codec, au, Nal.isKeyframe(codec, au), i * 100_000L))
            val end = System.currentTimeMillis() + 10_000
            // Low delay: every frame comes out without waiting for the end of the stream.
            while (frames.size < 10 && System.currentTimeMillis() < end) Thread.sleep(50)
        } finally { d.close() }
        assertTrue("errors: $errors", errors.isEmpty())
        assertEquals(10, frames.size)
        val f = frames.last()
        assertEquals(100, f.width); assertEquals(60, f.height)
        val c = f.pixels[30 * 100 + 50]
        // #3366CC after YUV 4:2:0 and back.
        assertEquals(0x33.toDouble(), ((c shr 16) and 0xFF).toDouble(), 10.0)
        assertEquals(0x66.toDouble(), ((c shr 8) and 0xFF).toDouble(), 10.0)
        assertEquals(0xCC.toDouble(), (c and 0xFF).toDouble(), 10.0)
    }

    @Test fun h264() = decode(VideoCodec.H264, "blue_100x60.h264")
    @Test fun h265() = decode(VideoCodec.H265, "blue_100x60.h265")
}
