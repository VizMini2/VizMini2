// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.camera

/**
 * Converts a sensor_msgs/Image buffer to ARGB_8888 pixels (those of Bitmap.setPixels).
 * No Android dependencies so it can be tested on the JVM.
 */
object ImageDecoding {

    sealed class Result {
        data object Ok : Result()
        data class Unsupported(val encoding: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    private val BYTES_PER_PIXEL = mapOf(
        "rgb8" to 3, "bgr8" to 3, "8uc3" to 3,
        "rgba8" to 4, "bgra8" to 4, "8uc4" to 4,
        "mono8" to 1, "8uc1" to 1,
        "mono16" to 2, "16uc1" to 2,
        "32fc1" to 4,
        "yuv422" to 2, "uyvy" to 2, "yuyv" to 2, "yuv422_yuy2" to 2
    )

    fun isSupported(encoding: String) = encoding.lowercase() in BYTES_PER_PIXEL

    /**
     * @param data   message bytes (only the first `size` are read)
     * @param out    destination, at least width * height
     */
    fun decode(
        encoding: String, width: Int, height: Int, step: Int, bigEndian: Boolean,
        data: ByteArray, size: Int, out: IntArray
    ): Result {
        val enc = encoding.lowercase()
        val bpp = BYTES_PER_PIXEL[enc] ?: return Result.Unsupported(encoding)
        if (width <= 0 || height <= 0) return Result.Invalid("Size ${width}x$height")
        if (step < width * bpp) return Result.Invalid("step ($step) < width * $bpp")
        // The last row does not need the full `step` padding.
        val needed = step.toLong() * (height - 1) + width.toLong() * bpp
        if (size.toLong() < needed || data.size < size) return Result.Invalid("Not enough data ($size < $needed)")
        if (out.size < width * height) return Result.Invalid("Output buffer too small")

        when (enc) {
            "rgb8" -> rgb(data, width, height, step, out, 0, 1, 2, 3)
            "bgr8", "8uc3" -> rgb(data, width, height, step, out, 2, 1, 0, 3)
            "rgba8" -> rgb(data, width, height, step, out, 0, 1, 2, 4)
            "bgra8", "8uc4" -> rgb(data, width, height, step, out, 2, 1, 0, 4)
            "mono8", "8uc1" -> mono8(data, width, height, step, out)
            "mono16", "16uc1" -> mono16(data, width, height, step, bigEndian, out)
            "32fc1" -> float32(data, width, height, step, bigEndian, out)
            "yuv422", "uyvy" -> yuv422(data, width, height, step, out, yIndex0 = 1, uIndex = 0, yIndex1 = 3, vIndex = 2)
            "yuyv", "yuv422_yuy2" -> yuv422(data, width, height, step, out, yIndex0 = 0, uIndex = 1, yIndex1 = 2, vIndex = 3)
        }
        return Result.Ok
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun rgb(d: ByteArray, w: Int, h: Int, step: Int, out: IntArray, ri: Int, gi: Int, bi: Int, bpp: Int) {
        for (y in 0 until h) {
            var src = y * step
            var dst = y * w
            for (x in 0 until w) {
                out[dst++] = argb(d[src + ri].toInt() and 0xFF, d[src + gi].toInt() and 0xFF, d[src + bi].toInt() and 0xFF)
                src += bpp
            }
        }
    }

    private fun mono8(d: ByteArray, w: Int, h: Int, step: Int, out: IntArray) {
        for (y in 0 until h) {
            var src = y * step
            var dst = y * w
            for (x in 0 until w) {
                val v = d[src++].toInt() and 0xFF
                out[dst++] = argb(v, v, v)
            }
        }
    }

    private fun read16(d: ByteArray, i: Int, bigEndian: Boolean): Int {
        val a = d[i].toInt() and 0xFF
        val b = d[i + 1].toInt() and 0xFF
        return if (bigEndian) (a shl 8) or b else (b shl 8) or a
    }

    private fun readFloat(d: ByteArray, i: Int, bigEndian: Boolean): Float {
        val b0 = d[i].toInt() and 0xFF; val b1 = d[i + 1].toInt() and 0xFF
        val b2 = d[i + 2].toInt() and 0xFF; val b3 = d[i + 3].toInt() and 0xFF
        val bits = if (bigEndian) (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        else (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        return java.lang.Float.intBitsToFloat(bits)
    }

    /**
     * 16 bits (typically depth in mm): normalized to the [min, max] range of the non-zero
     * values so something is visible. 0 (no measurement) is drawn black.
     */
    private fun mono16(d: ByteArray, w: Int, h: Int, step: Int, bigEndian: Boolean, out: IntArray) {
        var min = Int.MAX_VALUE; var max = 0
        for (y in 0 until h) {
            var src = y * step
            for (x in 0 until w) {
                val v = read16(d, src, bigEndian); src += 2
                if (v != 0) { if (v < min) min = v; if (v > max) max = v }
            }
        }
        val range = (max - min).coerceAtLeast(1)
        for (y in 0 until h) {
            var src = y * step
            var dst = y * w
            for (x in 0 until w) {
                val v = read16(d, src, bigEndian); src += 2
                val g = if (v == 0) 0 else 1 + (v - min) * 254 / range
                out[dst++] = argb(g, g, g)
            }
        }
    }

    /** 32-bit float (depth in meters). NaN/Inf/<=0 are drawn black. */
    private fun float32(d: ByteArray, w: Int, h: Int, step: Int, bigEndian: Boolean, out: IntArray) {
        var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
        for (y in 0 until h) {
            var src = y * step
            for (x in 0 until w) {
                val v = readFloat(d, src, bigEndian); src += 4
                if (v.isFinite() && v > 0f) { if (v < min) min = v; if (v > max) max = v }
            }
        }
        val range = if (max > min) max - min else 1f
        for (y in 0 until h) {
            var src = y * step
            var dst = y * w
            for (x in 0 until w) {
                val v = readFloat(d, src, bigEndian); src += 4
                val g = if (!v.isFinite() || v <= 0f) 0 else 1 + ((v - min) / range * 254f).toInt().coerceIn(0, 254)
                out[dst++] = argb(g, g, g)
            }
        }
    }

    private fun yuv422(
        d: ByteArray, w: Int, h: Int, step: Int, out: IntArray,
        yIndex0: Int, uIndex: Int, yIndex1: Int, vIndex: Int
    ) {
        for (y in 0 until h) {
            var src = y * step
            var dst = y * w
            var x = 0
            while (x < w) {
                val u = (d[src + uIndex].toInt() and 0xFF) - 128
                val v = (d[src + vIndex].toInt() and 0xFF) - 128
                out[dst++] = yuvToArgb(d[src + yIndex0].toInt() and 0xFF, u, v)
                if (x + 1 < w) out[dst++] = yuvToArgb(d[src + yIndex1].toInt() and 0xFF, u, v)
                src += 4
                x += 2
            }
        }
    }

    /** BT.601, full range. */
    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
        val g = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
        val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
        return argb(r, g, b)
    }
}
