// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

/**
 * jros2 1.5.1 reads ROS strings (UTF-8) by turning each byte into a signed char: "í"
 * (C3 AD) arrives as U+FFC3 U+FFAD. Here the bytes are recovered and decoded properly.
 * Only for messages of jros2's classes: dynamic ones (DynCodec) already arrive properly decoded
 * and passing them through here breaks single-byte characters like "ñ" (U+00F1).
 */
object RosStrings {

    fun fix(s: CharSequence): String {
        var needs = false
        for (i in 0 until s.length) {
            val c = s[i].code
            if (c >= 0x80) {
                // Only if everything fits "one byte per char" (with or without sign extension).
                if (c in 0x100 until 0xFF80) return s.toString()
                needs = true
            }
        }
        if (!needs) return s.toString()
        val bytes = ByteArray(s.length) { (s[it].code and 0xFF).toByte() }
        return String(bytes, Charsets.UTF_8)
    }
}
