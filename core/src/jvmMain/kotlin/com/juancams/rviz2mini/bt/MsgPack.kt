// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.bt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal MessagePack reader (what Groot2Publisher sends with the blackboard): nil, bool, integers,
 * floats, strings, binaries, lists and maps. Extensions are skipped (null).
 */
object MsgPack {

    fun decode(bytes: ByteArray): Any? = Reader(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)).read()

    private class Reader(val b: ByteBuffer) {
        fun read(): Any? {
            val t = b.get().toInt() and 0xFF
            return when {
                t <= 0x7F -> t.toLong()
                t in 0x80..0x8F -> map(t and 0x0F)
                t in 0x90..0x9F -> list(t and 0x0F)
                t in 0xA0..0xBF -> str(t and 0x1F)
                t >= 0xE0 -> (t - 256).toLong()
                else -> when (t) {
                    0xC0 -> null
                    0xC2 -> false
                    0xC3 -> true
                    0xC4 -> bin(u8()); 0xC5 -> bin(u16()); 0xC6 -> bin(u32())
                    0xC7 -> { val n = u8(); b.get(); skip(n); null }
                    0xC8 -> { val n = u16(); b.get(); skip(n); null }
                    0xC9 -> { val n = u32(); b.get(); skip(n); null }
                    0xCA -> b.float.toDouble()
                    0xCB -> b.double
                    0xCC -> u8().toLong(); 0xCD -> u16().toLong(); 0xCE -> (b.int.toLong() and 0xFFFFFFFFL); 0xCF -> b.long
                    0xD0 -> b.get().toLong(); 0xD1 -> b.short.toLong(); 0xD2 -> b.int.toLong(); 0xD3 -> b.long
                    0xD4 -> { b.get(); skip(1); null }; 0xD5 -> { b.get(); skip(2); null }; 0xD6 -> { b.get(); skip(4); null }
                    0xD7 -> { b.get(); skip(8); null }; 0xD8 -> { b.get(); skip(16); null }
                    0xD9 -> str(u8()); 0xDA -> str(u16()); 0xDB -> str(u32())
                    0xDC -> list(u16()); 0xDD -> list(u32())
                    0xDE -> map(u16()); 0xDF -> map(u32())
                    else -> throw IllegalArgumentException("msgpack: unknown type 0x%02x".format(t))
                }
            }
        }

        fun u8() = b.get().toInt() and 0xFF
        fun u16() = b.short.toInt() and 0xFFFF
        fun u32(): Int { val n = b.int; require(n >= 0 && n <= b.remaining()) { "msgpack: bad length" }; return n }
        fun skip(n: Int) { b.position(b.position() + n) }
        fun str(n: Int): String { val a = ByteArray(n); b.get(a); return String(a, Charsets.UTF_8) }
        fun bin(n: Int): ByteArray { val a = ByteArray(n); b.get(a); return a }
        fun list(n: Int): List<Any?> = List(n) { read() }
        fun map(n: Int): Map<String, Any?> { val m = LinkedHashMap<String, Any?>(); repeat(n) { m[read().toString()] = read() }; return m }
    }
}
