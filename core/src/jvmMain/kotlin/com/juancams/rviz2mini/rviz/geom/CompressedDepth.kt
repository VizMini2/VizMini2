// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.geom

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Depth images from compressed_depth_image_transport ("…/compressedDepth"): a 12-byte header
 * (format and two quantization parameters) and a 16-bit grayscale PNG, or with RVL the
 * width, height and the RVL-compressed values (Wilson, 2017). In 16UC1 the PNG
 * carries the millimeters as is; in 32FC1, the quantized inverse depth:
 * `z = a / (v - b)` (v = 0: no data). No Android: tested on the JVM.
 */
object CompressedDepth {

    class Depth(val width: Int, val height: Int, val meters: FloatArray)

    /** `format` is the CompressedImage's ("16UC1; compressedDepth png"); `data`, its bytes. */
    fun decode(format: String, data: ByteArray): Depth {
        val f = format.lowercase()
        require("compresseddepth" in f) { "Not a compressedDepth image: '$format'" }
        require(data.size > HEADER) { "Empty compressedDepth image" }
        val head = ByteBuffer.wrap(data, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
        head.int
        val a = head.float; val b = head.float
        val png = if ("rvl" in f) decodeRvl(data, HEADER) else decodePng16(data, HEADER)
        val n = png.width * png.height
        val out = FloatArray(n)
        val float32 = f.startsWith("32fc1")
        for (i in 0 until n) {
            val v = png.values[i].toInt() and 0xFFFF
            out[i] = when {
                v == 0 -> Float.NaN
                float32 -> a / (v - b)
                else -> v / 1000f
            }
        }
        return Depth(png.width, png.height, out)
    }

    /** The depth as a 32FC1 image (little endian bytes) for [CameraMath.depthPoints]. */
    fun toFloat32Bytes(d: Depth): ByteArray {
        val bb = ByteBuffer.allocate(d.meters.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (m in d.meters) bb.putFloat(m)
        return bb.array()
    }

    class Png16(val width: Int, val height: Int, val values: ShortArray)

    /** 16-bit grayscale PNG (color type 0, no interlacing), from `offset`. */
    fun decodePng16(bytes: ByteArray, offset: Int = 0): Png16 {
        val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        require(bytes.size >= offset + 8 && (0 until 8).all { bytes[offset + it] == sig[it] }) { "Not a PNG" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var p = offset + 8
        var w = 0; var h = 0; var depth = 0; var color = -1
        val idat = ByteArrayOutputStream()
        while (p + 8 <= bytes.size) {
            val len = bb.getInt(p); val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
            val data = p + 8
            require(len >= 0 && data + len <= bytes.size) { "Truncated PNG" }
            when (type) {
                "IHDR" -> { w = bb.getInt(data); h = bb.getInt(data + 4); depth = bytes[data + 8].toInt(); color = bytes[data + 9].toInt()
                    require(bytes[data + 12].toInt() == 0) { "Interlaced PNG is not supported" } }
                "IDAT" -> idat.write(bytes, data, len)
                "IEND" -> break
            }
            p = data + len + 4
        }
        require(depth == 16 && color == 0) { "Expected a 16-bit grayscale PNG (got $depth-bit, color type $color)" }
        val stride = w * 2
        val raw = ByteArray((stride + 1) * h)
        Inflater().apply {
            setInput(idat.toByteArray())
            var n = 0
            while (n < raw.size && !finished()) { val k = inflate(raw, n, raw.size - n); if (k == 0 && (needsInput() || needsDictionary())) break; n += k }
            end()
        }
        val out = ShortArray(w * h)
        val prev = ByteArray(stride); val cur = ByteArray(stride)
        for (y in 0 until h) {
            val base = y * (stride + 1)
            val filter = raw[base].toInt()
            for (x in 0 until stride) {
                val v = raw[base + 1 + x].toInt() and 0xFF
                val left = if (x >= 2) cur[x - 2].toInt() and 0xFF else 0
                val up = prev[x].toInt() and 0xFF
                val upLeft = if (x >= 2) prev[x - 2].toInt() and 0xFF else 0
                cur[x] = (when (filter) {
                    0 -> v
                    1 -> v + left
                    2 -> v + up
                    3 -> v + (left + up) / 2
                    4 -> v + paeth(left, up, upLeft)
                    else -> throw IllegalArgumentException("Bad PNG filter $filter")
                } and 0xFF).toByte()
            }
            for (x in 0 until w) out[y * w + x] = (((cur[2 * x].toInt() and 0xFF) shl 8) or (cur[2 * x + 1].toInt() and 0xFF)).toShort()
            cur.copyInto(prev)
        }
        return Png16(w, h, out)
    }

    /** RVL: width and height (int32 LE) and then 32-bit words with variable-length nibbles. */
    fun decodeRvl(bytes: ByteArray, offset: Int = 0): Png16 {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= offset + 8) { "Truncated RVL image" }
        val w = bb.getInt(offset); val h = bb.getInt(offset + 4)
        require(w > 0 && h > 0 && w.toLong() * h <= 64L shl 20) { "Bad RVL size ${w}x$h" }
        val out = ShortArray(w * h)
        var pos = offset + 8
        var word = 0; var nibbles = 0
        fun vle(): Int {
            var value = 0; var bits = 29
            var nibble: Int
            do {
                if (nibbles == 0) {
                    require(pos + 4 <= bytes.size) { "Truncated RVL data" }
                    word = bb.getInt(pos); pos += 4; nibbles = 8
                }
                nibble = word and 0xF0000000.toInt()
                value = value or ((nibble shl 1) ushr bits)
                word = word shl 4; nibbles--; bits -= 3
            } while (nibble and 0x80000000.toInt() != 0)
            return value
        }
        var i = 0
        var previous = 0
        while (i < out.size) {
            val zeros = vle()
            require(i + zeros <= out.size) { "Bad RVL data" }
            i += zeros
            val nonzeros = vle()
            require(i + nonzeros <= out.size) { "Bad RVL data" }
            repeat(nonzeros) {
                val positive = vle()
                val delta = (positive ushr 1) xor -(positive and 1)
                previous = (previous + delta) and 0xFFFF
                out[i++] = previous.toShort()
            }
        }
        return Png16(w, h, out)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private const val HEADER = 12
}
