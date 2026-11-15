// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.geom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

class CompressedDepthTest {

    /** 16-bit grayscale PNG with filter `filters[y]` on each row (0 none, 1 Sub, 2 Up, 4 Paeth). */
    private fun png16(w: Int, h: Int, values: IntArray, filters: IntArray): ByteArray {
        val stride = w * 2
        val raw = ByteArrayOutputStream()
        var prev = ByteArray(stride)
        for (y in 0 until h) {
            val cur = ByteArray(stride) { i -> val v = values[y * w + i / 2]; (if (i % 2 == 0) v shr 8 else v).toByte() }
            raw.write(filters[y])
            for (x in 0 until stride) {
                val a = if (x >= 2) cur[x - 2].toInt() and 0xFF else 0
                val b = prev[x].toInt() and 0xFF
                val c = if (x >= 2) prev[x - 2].toInt() and 0xFF else 0
                val pred = when (filters[y]) {
                    1 -> a; 2 -> b; 3 -> (a + b) / 2
                    4 -> { val p = a + b - c; val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c); if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                    else -> 0
                }
                raw.write(((cur[x].toInt() and 0xFF) - pred) and 0xFF)
            }
            prev = cur
        }
        val z = Deflater().run { setInput(raw.toByteArray()); finish(); val o = ByteArray(65536); val n = deflate(o); o.copyOf(n) }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val td = type.toByteArray() + data
            out.write(td)
            out.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(td) }.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(16).put(0).put(0).put(0).put(0).array())
        chunk("IDAT", z)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** Same as RvlCodec::CompressRVL. */
    private fun rvlEncode(input: IntArray): ByteArray {
        val words = ArrayList<Int>(); var word = 0; var nibbles = 0
        fun vle(v0: Int) {
            var value = v0
            do {
                var nibble = value and 0x7; value = value ushr 3
                if (value != 0) nibble = nibble or 0x8
                word = (word shl 4) or nibble
                if (++nibbles == 8) { words += word; nibbles = 0; word = 0 }
            } while (value != 0)
        }
        var i = 0; var previous = 0
        while (i < input.size) {
            var zeros = 0; while (i < input.size && input[i] == 0) { zeros++; i++ }
            vle(zeros)
            val start = i; while (i < input.size && input[i] != 0) i++
            vle(i - start)
            for (k in start until i) { val delta = input[k] - previous; vle((delta shl 1) xor (delta shr 31)); previous = input[k] }
        }
        if (nibbles != 0) words += word shl (4 * (8 - nibbles))
        val bb = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (w in words) bb.putInt(w)
        return bb.array()
    }

    private fun header(a: Float, b: Float) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putFloat(a).putFloat(b).array()

    @Test
    fun pngWithEveryFilter() {
        val w = 5; val h = 5
        val values = IntArray(w * h) { (it * 2731 + 17) % 65536 }
        val png = CompressedDepth.decodePng16(png16(w, h, values, intArrayOf(0, 1, 2, 3, 4)))
        assertEquals(w, png.width); assertEquals(h, png.height)
        assertEquals(values.toList(), png.values.map { it.toInt() and 0xFFFF })
    }

    @Test
    fun depthIn16And32Bits() {
        val mm = intArrayOf(1000, 0, 2500, 65535)
        val d16 = CompressedDepth.decode("16UC1; compressedDepth png", header(0f, 0f) + png16(2, 2, mm, intArrayOf(0, 2)))
        assertEquals(1.0f, d16.meters[0], 0f); assertTrue(d16.meters[1].isNaN()); assertEquals(2.5f, d16.meters[2], 0f)
        // 32FC1: z = a / (v - b).
        val d32 = CompressedDepth.decode("32FC1; compressedDepth png", header(1000f, 10f) + png16(2, 1, intArrayOf(510, 0), intArrayOf(1)))
        assertEquals(2.0f, d32.meters[0], 1e-6f); assertTrue(d32.meters[1].isNaN())
        assertEquals(2 * 4, CompressedDepth.toFloat32Bytes(d32).size)
        assertThrows(IllegalArgumentException::class.java) { CompressedDepth.decode("16UC1; compressedDepth rvl", ByteArray(40)) }
        // RVL (encoded like rvl_codec.cpp from compressed_depth_image_transport).
        val depth = intArrayOf(0, 0, 1000, 1001, 999, 0, 3000, 65535, 0, 0, 0, 1500)
        val rvl = header(0f, 0f) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(4).putInt(3).array() + rvlEncode(depth)
        val dr = CompressedDepth.decode("16UC1; compressedDepth rvl", rvl)
        assertEquals(4, dr.width); assertEquals(3, dr.height)
        assertEquals(depth.map { if (it == 0) Float.NaN else it / 1000f }.toString(), dr.meters.toList().toString())
        assertThrows(IllegalArgumentException::class.java) { CompressedDepth.decode("jpeg", ByteArray(40)) }
    }
}
