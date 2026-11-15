// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.plot

import kotlin.math.sqrt

/** Statistics of a series over a range: samples, minimum, maximum, mean, standard deviation and rate. */
data class SeriesStats(val count: Int, val min: Double, val max: Double, val mean: Double, val std: Double, val hz: Double) {
    companion object {
        /** From the samples with time in [fromNs, toNs] (NaNs do not count). Null if there are none. */
        fun of(times: LongArray, values: DoubleArray, fromNs: Long, toNs: Long): SeriesStats? {
            var n = 0; var min = Double.MAX_VALUE; var max = -Double.MAX_VALUE; var sum = 0.0; var sum2 = 0.0
            var first = Long.MAX_VALUE; var last = Long.MIN_VALUE
            for (i in times.indices) {
                val t = times[i]; val v = values[i]
                if (t < fromNs || t > toNs || v.isNaN()) continue
                n++; sum += v; sum2 += v * v
                if (v < min) min = v; if (v > max) max = v
                if (t < first) first = t; if (t > last) last = t
            }
            if (n == 0) return null
            val mean = sum / n
            val std = sqrt(maxOf(0.0, sum2 / n - mean * mean))
            val hz = if (n > 1 && last > first) (n - 1) / ((last - first) / 1e9) else 0.0
            return SeriesStats(n, min, max, mean, std, hz)
        }
    }
}
