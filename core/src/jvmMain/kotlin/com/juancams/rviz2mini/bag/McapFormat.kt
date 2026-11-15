// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bag

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Constants and utilities for the MCAP format (https://mcap.dev/spec): everything little endian,
 * strings are a u32 length + UTF-8 and maps a u32 byte length + entries.
 * No Android dependencies so it can be tested on the JVM.
 */
object Mcap {
    val MAGIC = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), '0'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

    const val OP_HEADER = 0x01
    const val OP_FOOTER = 0x02
    const val OP_SCHEMA = 0x03
    const val OP_CHANNEL = 0x04
    const val OP_MESSAGE = 0x05
    const val OP_CHUNK = 0x06
    const val OP_MESSAGE_INDEX = 0x07
    const val OP_CHUNK_INDEX = 0x08
    const val OP_ATTACHMENT = 0x09
    const val OP_ATTACHMENT_INDEX = 0x0A
    const val OP_STATISTICS = 0x0B
    const val OP_METADATA = 0x0C
    const val OP_METADATA_INDEX = 0x0D
    const val OP_SUMMARY_OFFSET = 0x0E
    const val OP_DATA_END = 0x0F

    /** Opcode (1) + length (8). */
    const val RECORD_HEADER = 9

    /** Complete Footer record: opcode + length + summary_start + summary_offset_start + summary_crc. */
    const val FOOTER_RECORD = RECORD_HEADER + 8 + 8 + 4
}

/** Format error while reading an MCAP (truncated file, malformed record, mismatched CRC...). */
open class McapException(message: String) : IOException(message)

/** The bag uses a chunk compression we cannot decompress. The message is for the user. */
class UnsupportedCompression(val compression: String) : McapException(
    "This bag uses \"$compression\" compression, which is not supported. Re-record it without compression, or with lz4 or zstd."
)

/** Growable buffer records are written into in little endian. */
class LeBuffer(initial: Int = 256) : ByteArrayOutputStream(initial) {

    val size: Int get() = count

    /** Direct access to the internal array (valid up to [size]). */
    val array: ByteArray get() = buf

    fun u8(v: Int) { write(v and 0xFF) }

    fun u16(v: Int) { write(v and 0xFF); write((v ushr 8) and 0xFF) }

    fun u32(v: Long) { for (i in 0 until 4) write(((v ushr (8 * i)) and 0xFF).toInt()) }

    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

    fun u64(v: Long) { for (i in 0 until 8) write(((v ushr (8 * i)) and 0xFF).toInt()) }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size) { write(b, off, len) }

    fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); u32(b.size); write(b, 0, b.size) }

    /** Overwrites an already written u32 (to fill in lengths afterwards). */
    fun patchU32(at: Int, v: Int) { for (i in 0 until 4) buf[at + i] = (v ushr (8 * i)).toByte() }

    fun patchU64(at: Int, v: Long) { for (i in 0 until 8) buf[at + i] = (v ushr (8 * i)).toByte() }

    /** String -> string map (u32 byte length + pairs). */
    fun strMap(m: Map<String, String>) {
        val at = size
        u32(0)
        for ((k, v) in m) { str(k); str(v) }
        patchU32(at, size - at - 4)
    }

    /** Starts a record: opcode + provisional length. Returns where the length goes for [endRecord]. */
    fun beginRecord(op: Int): Int { u8(op); val at = size; u64(0); return at }

    fun endRecord(lenAt: Int) { patchU64(lenAt, (size - lenAt - 8).toLong()) }
}

/** Little-endian reader over a slice of an array. Throws [McapException] if it goes out of bounds. */
class LeReader(val buf: ByteArray, var pos: Int = 0, val end: Int = buf.size) {

    val remaining: Int get() = end - pos

    private fun need(n: Int) { if (n < 0 || pos + n > end) throw McapException("Truncated MCAP record") }

    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xFF }

    fun u16(): Int { need(2); val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }

    fun u32(): Long { need(4); val v = le32(buf, pos); pos += 4; return v }

    fun u64(): Long { need(8); val v = le64(buf, pos); pos += 8; return v }

    /** u32 length that must fit in what remains. */
    fun len32(): Int { val n = u32(); if (n > remaining) throw McapException("Truncated MCAP record"); return n.toInt() }

    fun len64(): Int { val n = u64(); if (n < 0 || n > remaining) throw McapException("Truncated MCAP record"); return n.toInt() }

    fun str(): String { val n = len32(); val s = String(buf, pos, n, Charsets.UTF_8); pos += n; return s }

    fun bytes(n: Int): ByteArray { need(n); val b = buf.copyOfRange(pos, pos + n); pos += n; return b }

    fun skip(n: Int) { need(n); pos += n }

    fun strMap(): Map<String, String> {
        val n = len32()
        val stop = pos + n
        val m = LinkedHashMap<String, String>()
        while (pos < stop) { val k = str(); m[k] = str() }
        return m
    }

    companion object {
        fun le32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

        fun le64(b: ByteArray, at: Int): Long = le32(b, at) or (le32(b, at + 4) shl 32)
    }
}
