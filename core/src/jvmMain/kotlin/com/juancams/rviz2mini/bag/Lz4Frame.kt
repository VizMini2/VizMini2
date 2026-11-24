// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.bag

/**
 * LZ4 decompressor in "frame" format (https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md),
 * the one MCAP uses for "lz4" chunks. Pure Kotlin, no native code. Does not check the xxHash
 * checksums (the MCAP chunk already carries its own CRC32).
 */
object Lz4Frame {

    private const val MAGIC = 0x184D2204L
    private const val SKIPPABLE_MASK = 0xFFFFFFF0L
    private const val SKIPPABLE_MAGIC = 0x184D2A50L

    /**
     * Decompresses one or more consecutive frames. `expectedSize` (if known, e.g. the
     * chunk's uncompressed_size) avoids reallocating the output buffer.
     */
    fun decompress(src: ByteArray, offset: Int = 0, length: Int = src.size - offset, expectedSize: Int = -1): ByteArray {
        val end = offset + length
        var out = ByteArray(if (expectedSize >= 0) expectedSize else maxOf(64, length * 3))
        var op = 0
        var ip = offset

        // Ensures the output has at least `total` bytes.
        fun ensure(total: Int) {
            if (total <= out.size) return
            var n = maxOf(out.size.toLong() * 2, 64L)
            while (n < total) n *= 2
            if (n > Int.MAX_VALUE - 16) throw McapException("LZ4 data too large")
            out = out.copyOf(n.toInt())
        }

        fun u32(at: Int): Long {
            if (at + 4 > end) throw McapException("Truncated LZ4 frame")
            return LeReader.le32(src, at)
        }

        while (ip < end) {
            val magic = u32(ip)
            ip += 4
            if (magic and SKIPPABLE_MASK == SKIPPABLE_MAGIC) {
                // The size is an unsigned 32-bit value: as an Int it could come out negative and send `ip` back, to the
                // same frame, forever.
                val skip = u32(ip)
                if (skip > end - ip - 4) throw McapException("Truncated LZ4 frame")
                ip += 4 + skip.toInt()
                continue
            }
            if (magic != MAGIC) throw McapException("Bad LZ4 frame magic")
            if (ip + 2 > end) throw McapException("Truncated LZ4 frame")
            val flg = src[ip].toInt() and 0xFF
            if ((flg ushr 6) != 1) throw McapException("Unsupported LZ4 frame version")
            val blockChecksum = flg and 0x10 != 0
            val contentSize = flg and 0x08 != 0
            val contentChecksum = flg and 0x04 != 0
            val dictId = flg and 0x01 != 0
            // FLG, BD, [size 8], [dict 4], HC
            ip += 2 + (if (contentSize) 8 else 0) + (if (dictId) 4 else 0) + 1
            if (dictId) throw McapException("LZ4 frames with a dictionary are not supported")

            while (true) {
                val raw = u32(ip)
                ip += 4
                if (raw == 0L) break // EndMark
                val size = (raw and 0x7FFFFFFFL).toInt()
                if (size > end - ip) throw McapException("Truncated LZ4 block")
                if (raw and 0x80000000L != 0L) {
                    // Uncompressed block.
                    ensure(op + size)
                    System.arraycopy(src, ip, out, op, size)
                    op += size
                } else {
                    op = decodeBlock(src, ip, ip + size, out, op) { total -> ensure(total); out }
                }
                ip += size
                if (blockChecksum) ip += 4
            }
            if (contentChecksum) ip += 4
        }
        return if (op == out.size) out else out.copyOf(op)
    }

    private fun checkedSize(n: Long): Int = if (n > Int.MAX_VALUE - 16) throw McapException("LZ4 data too large") else n.toInt()

    /**
     * Decompresses an LZ4 block into `dst` starting at `start`; returns the new position.
     * References may point to previous blocks (linked blocks) because all the output
     * is in the same buffer. `grow(n)` ensures the buffer has n bytes and returns it.
     */
    private inline fun decodeBlock(src: ByteArray, from: Int, to: Int, dst0: ByteArray, start: Int, grow: (Int) -> ByteArray): Int {
        var dst = dst0
        var ip = from
        var op = start
        while (ip < to) {
            val token = src[ip++].toInt() and 0xFF
            // Literals.
            var lit = token ushr 4
            if (lit == 15) {
                while (true) {
                    if (ip >= to) throw McapException("Corrupt LZ4 block")
                    val b = src[ip++].toInt() and 0xFF
                    lit += b
                    if (b != 255) break
                }
            }
            if (lit > to - ip) throw McapException("Corrupt LZ4 block")
            if (lit > dst.size - op) dst = grow(checkedSize(op.toLong() + lit))
            System.arraycopy(src, ip, dst, op, lit)
            ip += lit
            op += lit
            if (ip >= to) break // the last sequence only has literals

            // Match.
            if (ip + 2 > to) throw McapException("Corrupt LZ4 block")
            val distance = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
            ip += 2
            if (distance == 0 || distance > op) throw McapException("Corrupt LZ4 block")
            var len = token and 0x0F
            if (len == 15) {
                while (true) {
                    if (ip >= to) throw McapException("Corrupt LZ4 block")
                    val b = src[ip++].toInt() and 0xFF
                    len += b
                    if (b != 255) break
                }
            }
            len += 4
            if (len > dst.size - op) dst = grow(checkedSize(op.toLong() + len))
            var m = op - distance
            if (distance >= len) {
                System.arraycopy(dst, m, dst, op, len)
                op += len
            } else {
                // Overlapping: copied byte by byte (repeats the pattern).
                repeat(len) { dst[op++] = dst[m++] }
            }
        }
        return op
    }
}
