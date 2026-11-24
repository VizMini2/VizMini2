// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.e2e

import com.juancams.rviz2mini.camera.CameraFrameInfo
import com.juancams.rviz2mini.camera.FrameStream
import com.juancams.rviz2mini.camera.ImageKind
import com.juancams.rviz2mini.camera.ImageTopic
import com.juancams.rviz2mini.platform.DecodedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Against tools/video_publisher.py (live H.264 and H.265, 320x240 at 15 fps): the camera streams decode
 * foxglove's CompressedVideo and ffmpeg_image_transport's FFMPEGPacket with the PC's decoder (ffmpeg).
 */
class VideoE2ETest {
    private fun frames(topic: ImageTopic): Pair<List<Pair<DecodedImage, CameraFrameInfo>>, List<String>> {
        E2E.connect(topic.name, 30_000)
        assumeTrue("no ffmpeg", com.juancams.rviz2mini.desktop.FfmpegVideoDecoder.find() != null)
        val got = CopyOnWriteArrayList<Pair<DecodedImage, CameraFrameInfo>>()
        val errors = CopyOnWriteArrayList<String>()
        val s = FrameStream(topic, { img, info -> got += DecodedImage(img.width, img.height, img.pixels.copyOf()) to info }, { errors += it })
        s.start()
        try {
            E2E.waitFor({ "only ${got.size} frames from ${topic.name}, errors $errors" }, 20_000) { got.size >= 20 }
        } finally { s.stop() }
        return got to errors
    }

    private fun check(topic: ImageTopic, format: String) {
        val (got, _) = frames(topic)
        val (img, info) = got.last()
        assertEquals(320, img.width); assertEquals(240, img.height)
        assertEquals(format, info.encoding)
        // testsrc2 is colourful: not a black or grey frame.
        val colours = img.pixels.map { it and 0xF0F0F0 }.toSet().size
        assertTrue("only $colours colours", colours > 20)
        // About the publisher's rate once it is running (frames are not held back).
        assertTrue("fps ${info.fps}", info.fps > 8)
    }

    @Test fun foxgloveH264() = check(ImageTopic("/video/image_raw/foxglove", ImageKind.VIDEO), "h264")
    @Test fun ffmpegPacketH264() = check(ImageTopic("/video/image_raw/ffmpeg", ImageKind.FFMPEG), "libx264")
    @Test fun foxgloveH265() = check(ImageTopic("/video_hevc/image_raw/foxglove", ImageKind.VIDEO), "h265")
}
