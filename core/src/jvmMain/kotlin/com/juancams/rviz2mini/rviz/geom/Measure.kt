// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.geom

import java.util.Locale

/** Text of the Measure tool (no Android, tested on the JVM). */
object Measure {
    fun distance(a: DoubleArray, b: DoubleArray): Double = Math.sqrt((b[0] - a[0]).let { it * it } + (b[1] - a[1]).let { it * it } + (b[2] - a[2]).let { it * it })

    /**
     * "2.35 m (dx 1.20, dy -2.02)": on the ground z does not change and is not shown. No "Δ": the text of the
     * 3D view only has the characters in its atlas.
     */
    fun text(a: DoubleArray, b: DoubleArray): String {
        val d = distance(a, b)
        val dz = b[2] - a[2]
        val parts = mutableListOf(String.format(Locale.ROOT, "dx %.2f", b[0] - a[0]), String.format(Locale.ROOT, "dy %.2f", b[1] - a[1]))
        if (Math.abs(dz) > 1e-6) parts += String.format(Locale.ROOT, "dz %.2f", dz)
        return String.format(Locale.ROOT, "%.2f m", d) + " (" + parts.joinToString(", ") + ")"
    }
}
