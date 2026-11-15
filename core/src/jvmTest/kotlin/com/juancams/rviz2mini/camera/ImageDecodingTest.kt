// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.camera

import com.juancams.rviz2mini.camera.ImageDecoding.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImageDecodingTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun decode(enc: String, w: Int, h: Int, step: Int, data: ByteArray, bigEndian: Boolean = false): Pair<Result, IntArray> {
        val out = IntArray(w * h)
        return ImageDecoding.decode(enc, w, h, step, bigEndian, data, data.size, out) to out
    }

    @Test
    fun rgb8() {
        val (r, px) = decode("rgb8", 2, 1, 6, bytes(255, 0, 0, 0, 0, 255))
        assertEquals(Result.Ok, r)
        assertEquals(argb(255, 0, 0), px[0])
        assertEquals(argb(0, 0, 255), px[1])
    }

    @Test
    fun bgr8SwapsChannels() {
        val (_, px) = decode("bgr8", 1, 1, 3, bytes(10, 20, 30))
        assertEquals(argb(30, 20, 10), px[0])
    }

    @Test
    fun rgba8AndBgra8IgnoreAlpha() {
        assertEquals(argb(1, 2, 3), decode("rgba8", 1, 1, 4, bytes(1, 2, 3, 0)).second[0])
        assertEquals(argb(3, 2, 1), decode("bgra8", 1, 1, 4, bytes(1, 2, 3, 128)).second[0])
    }

    @Test
    fun encodingIsCaseInsensitive() {
        assertEquals(Result.Ok, decode("RGB8", 1, 1, 3, bytes(1, 2, 3)).first)
        assertEquals(Result.Ok, decode("8UC1", 1, 1, 1, bytes(7)).first)
    }

    @Test
    fun mono8() {
        val (_, px) = decode("mono8", 2, 1, 2, bytes(0, 200))
        assertEquals(argb(0, 0, 0), px[0])
        assertEquals(argb(200, 200, 200), px[1])
    }

    @Test
    fun rowPaddingFromStepIsSkipped() {
        // 1x2 mono8 with step 4: each row has 3 padding bytes.
        val (r, px) = decode("mono8", 1, 2, 4, bytes(10, 99, 99, 99, 20))
        assertEquals(Result.Ok, r)
        assertEquals(argb(10, 10, 10), px[0])
        assertEquals(argb(20, 20, 20), px[1])
    }

    @Test
    fun mono16IsNormalizedAndZeroIsBlack() {
        val le = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(1000).putShort(3000).array()
        val (_, px) = decode("16UC1", 3, 1, 6, le)
        assertEquals(argb(0, 0, 0), px[0])
        assertEquals(argb(1, 1, 1), px[1])       // minimum
        assertEquals(argb(255, 255, 255), px[2]) // maximum
    }

    @Test
    fun mono16RespectsBigEndian() {
        val be = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putShort(500).putShort(1500).array()
        val (_, px) = decode("mono16", 2, 1, 4, be, bigEndian = true)
        assertEquals(argb(1, 1, 1), px[0])
        assertEquals(argb(255, 255, 255), px[1])
    }

    @Test
    fun float32DepthIgnoresNanAndNonPositive() {
        val data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(Float.NaN).putFloat(0.5f).putFloat(2.5f).putFloat(-1f).array()
        val (_, px) = decode("32FC1", 4, 1, 16, data)
        assertEquals(argb(0, 0, 0), px[0])
        assertEquals(argb(1, 1, 1), px[1])
        assertEquals(argb(255, 255, 255), px[2])
        assertEquals(argb(0, 0, 0), px[3])
    }

    @Test
    fun uyvyGrey() {
        // U = V = 128 => no color: R = G = B = Y
        val (_, px) = decode("yuv422", 2, 1, 4, bytes(128, 50, 128, 200))
        assertEquals(argb(50, 50, 50), px[0])
        assertEquals(argb(200, 200, 200), px[1])
    }

    @Test
    fun yuyvOrderAndOddWidth() {
        val (r, px) = decode("yuyv", 3, 1, 8, bytes(60, 128, 70, 128, 80, 128, 90, 128))
        assertEquals(Result.Ok, r)
        assertEquals(argb(60, 60, 60), px[0])
        assertEquals(argb(70, 70, 70), px[1])
        assertEquals(argb(80, 80, 80), px[2])
    }

    @Test
    fun yuvRedIsMostlyRed() {
        // Red in BT.601: Y≈76, U≈85, V≈255
        val (_, px) = decode("yuyv", 2, 1, 4, bytes(76, 85, 76, 255))
        val r = (px[0] shr 16) and 0xFF; val g = (px[0] shr 8) and 0xFF; val b = px[0] and 0xFF
        assertTrue("r=$r g=$g b=$b", r > 240 && g < 20 && b < 20)
    }

    @Test
    fun unsupportedEncoding() {
        val (r, _) = decode("bayer_rggb8", 1, 1, 1, bytes(0))
        assertEquals(Result.Unsupported("bayer_rggb8"), r)
    }

    @Test
    fun invalidInputsAreRejectedWithoutThrowing() {
        assertTrue(decode("rgb8", 2, 2, 5, ByteArray(100)).first is Result.Invalid)   // step < w*3
        assertTrue(decode("rgb8", 2, 2, 6, ByteArray(11)).first is Result.Invalid)    // missing data
        assertTrue(decode("rgb8", 0, 2, 6, ByteArray(12)).first is Result.Invalid)    // size 0
        val out = IntArray(1)
        assertTrue(ImageDecoding.decode("mono8", 2, 2, 2, false, ByteArray(4), 4, out) is Result.Invalid)
    }

    @Test
    fun lastRowDoesNotNeedFullStepPadding() {
        // Some drivers do not send the last row's padding.
        val (r, _) = decode("mono8", 2, 2, 4, bytes(1, 2, 0, 0, 3, 4))
        assertEquals(Result.Ok, r)
    }
}
