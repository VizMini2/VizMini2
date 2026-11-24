// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plot

import java.util.Locale

/**
 * Samples (time in ns, value) of a series, in arrival order, in a ring of `capacity`.
 * Written from the DDS thread (or the bag loading thread) and read from the UI thread:
 * everything is synchronized.
 */
class SeriesBuffer(private val capacity: Int = DEFAULT_CAPACITY) {
    private val t = LongArray(capacity)
    private val v = DoubleArray(capacity)
    private var start = 0
    private var count = 0

    @Synchronized fun add(timeNs: Long, value: Double) {
        // Out of order: slightly (header.stamp of messages arriving out of order) the sample is
        // dropped; a lot (clocks jumping back) it starts over so that time only moves forward.
        if (count > 0) {
            val last = t[(start + count - 1) % capacity]
            if (timeNs < last) { if (last - timeNs < REORDER_NS) return else clear() }
        }
        val i = (start + count) % capacity
        t[i] = timeNs; v[i] = value
        if (count < capacity) count++ else start = (start + 1) % capacity
    }

    @Synchronized fun clear() { start = 0; count = 0 }

    @Synchronized fun size() = count

    @Synchronized fun firstTime(): Long? = if (count == 0) null else t[start]
    @Synchronized fun lastTime(): Long? = if (count == 0) null else t[(start + count - 1) % capacity]

    /** Logical index (0 = the oldest) of the last sample with time <= `timeNs`, or -1. */
    private fun indexAtOrBefore(timeNs: Long): Int {
        var lo = 0; var hi = count - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (t[(start + mid) % capacity] <= timeNs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** Value of the last sample at or before `timeNs` (what the cursor shows), or null. */
    @Synchronized fun valueAt(timeNs: Long): Double? {
        val i = indexAtOrBefore(timeNs)
        return if (i < 0) null else v[(start + i) % capacity]
    }

    /** Samples between `fromNs` and `toNs`, plus the previous and next ones so the line reaches the edges. */
    @Synchronized fun range(fromNs: Long, toNs: Long): Pair<LongArray, DoubleArray> {
        if (count == 0) return LongArray(0) to DoubleArray(0)
        val a = maxOf(0, indexAtOrBefore(fromNs))
        val b = minOf(count - 1, indexAtOrBefore(toNs) + 1)
        if (b < a) return LongArray(0) to DoubleArray(0)
        val n = b - a + 1
        val ot = LongArray(n); val ov = DoubleArray(n)
        for (k in 0 until n) { val i = (start + a + k) % capacity; ot[k] = t[i]; ov[k] = v[i] }
        return ot to ov
    }

    /** Copy of everything (for the CSV). */
    fun all(): Pair<LongArray, DoubleArray> = range(Long.MIN_VALUE, Long.MAX_VALUE)

    companion object {
        const val DEFAULT_CAPACITY = 60_000
        /** How far back counts as out-of-order rather than a clock jump. */
        const val REORDER_NS = 1_000_000_000L
    }
}

/**
 * Reduces a long series to `buckets` equal spans of [start, end]: from each one the minimum
 * and maximum are kept (with their timestamps), which is what is seen when drawing many samples per pixel.
 */
class Decimator(private val start: Long, end: Long, private val buckets: Int) {
    private val span = (end - start + 1).coerceAtLeast(1).toDouble()
    private val tMin = LongArray(buckets); private val vMin = DoubleArray(buckets)
    private val tMax = LongArray(buckets); private val vMax = DoubleArray(buckets)
    private val has = BooleanArray(buckets)

    fun add(t: Long, v: Double) {
        if (!v.isFinite()) return
        val i = ((t - start) / span * buckets).toInt().coerceIn(0, buckets - 1)
        if (!has[i]) { has[i] = true; tMin[i] = t; vMin[i] = v; tMax[i] = t; vMax[i] = v; return }
        if (v < vMin[i]) { vMin[i] = v; tMin[i] = t }
        if (v > vMax[i]) { vMax[i] = v; tMax[i] = t }
    }

    /** The remaining samples, in time order. */
    fun writeTo(b: SeriesBuffer) {
        for (i in 0 until buckets) {
            if (!has[i]) continue
            when {
                tMin[i] == tMax[i] -> b.add(tMin[i], vMin[i])
                tMin[i] < tMax[i] -> { b.add(tMin[i], vMin[i]); b.add(tMax[i], vMax[i]) }
                else -> { b.add(tMax[i], vMax[i]); b.add(tMin[i], vMin[i]) }
            }
        }
    }
}

/** Transformation of a series with its state (the previous sample, the averaging window). */
class SeriesFilter(private val spec: SeriesSpec) {
    private var lastT = Long.MIN_VALUE
    private var lastV = 0.0
    private val window = ArrayDeque<Double>()
    private var sum = 0.0

    /** Value to store for sample (t, raw), or null if there is none yet (the first one of a derivative). */
    fun apply(timeNs: Long, raw: Double): Double? {
        val out: Double? = when (spec.transform) {
            SeriesTransform.NONE -> raw
            SeriesTransform.DERIVATIVE -> {
                val prev = lastT
                val d = if (prev == Long.MIN_VALUE || timeNs <= prev) null else (raw - lastV) / ((timeNs - prev) / 1e9)
                lastT = timeNs; lastV = raw
                d
            }
            SeriesTransform.INTEGRAL -> {
                val prev = lastT
                if (prev != Long.MIN_VALUE && timeNs > prev) sum += (raw + lastV) / 2 * ((timeNs - prev) / 1e9)
                lastT = timeNs; lastV = raw
                sum
            }
            SeriesTransform.MOVING_AVERAGE -> {
                window.addLast(raw); sum += raw
                while (window.size > spec.window) sum -= window.removeFirst()
                sum / window.size
            }
        }
        return out?.let { it * spec.scale + spec.offset }
    }

    fun reset() { lastT = Long.MIN_VALUE; window.clear(); sum = 0.0 }
}

object PlotMath {

    /** "Nice" step (1, 2 or 5 × 10^n) for about 5 divisions. */
    fun niceStep(raw: Double): Double {
        if (!raw.isFinite() || raw <= 0) return 1.0
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val n = raw / mag
        return mag * when { n < 1.5 -> 1.0; n < 3.5 -> 2.0; n < 7.5 -> 5.0; else -> 10.0 }
    }

    /** Minimum and maximum of the finite values, with a margin; if equal, a range around them. */
    fun bounds(values: List<DoubleArray>): Pair<Double, Double>? {
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        for (a in values) for (x in a) if (x.isFinite()) { if (x < lo) lo = x; if (x > hi) hi = x }
        if (lo > hi) return null
        if (hi - lo < 1e-12) { val pad = maxOf(Math.abs(lo) * 0.1, 0.5); return (lo - pad) to (hi + pad) }
        val pad = (hi - lo) * 0.06
        return (lo - pad) to (hi + pad)
    }

    /**
     * (x, y) pairs for an XY panel: for each x sample in the interval, the value of y at that
     * instant (normally they come from the same message and have the same time).
     */
    fun xyPairs(x: SeriesBuffer, y: SeriesBuffer, fromNs: Long, toNs: Long): Pair<DoubleArray, DoubleArray> {
        val (tx, vx) = x.range(fromNs, toNs)
        val ox = ArrayList<Double>(tx.size); val oy = ArrayList<Double>(tx.size)
        for (i in tx.indices) {
            if (tx[i] < fromNs || tx[i] > toNs) continue
            val yv = y.valueAt(tx[i]) ?: continue
            ox += vx[i]; oy += yv
        }
        return ox.toDoubleArray() to oy.toDoubleArray()
    }

    /**
     * CSV with a time column (s since the first sample; also the absolute time in s) and one per
     * series. One row for each instant with any sample; the other series carry their last value
     * (empty if they had none yet).
     */
    fun csv(series: List<Pair<String, SeriesBuffer>>): String {
        val data = series.map { it.first to it.second.all() }
        val times = java.util.TreeSet<Long>()
        for ((_, d) in data) for (t in d.first) times += t
        val sb = StringBuilder()
        sb.append("time_s,stamp_s")
        for ((name, _) in data) sb.append(',').append(csvField(name))
        sb.append('\n')
        if (times.isEmpty()) return sb.toString()
        val t0 = times.first()
        val idx = IntArray(data.size)
        for (t in times) {
            sb.append(String.format(Locale.ROOT, "%.6f,%.6f", (t - t0) / 1e9, t / 1e9))
            for ((k, d) in data.withIndex()) {
                val (ts, vs) = d.second
                while (idx[k] < ts.size && ts[idx[k]] <= t) idx[k]++
                sb.append(',')
                if (idx[k] > 0) sb.append(vs[idx[k] - 1].toString())
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun csvField(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Time axis label: seconds relative to the end ("-10 s") or absolute from the start. */
    fun timeLabel(seconds0: Double, step: Double): String {
        val seconds = if (Math.abs(seconds0) < step * 1e-6) 0.0 else seconds0
        val decimals = if (step >= 1) 0 else maxOf(0, -Math.floor(Math.log10(step)).toInt())
        return String.format(Locale.ROOT, "%.${decimals}f s", seconds)
    }

    fun valueLabel(v0: Double, step: Double): String {
        if (!v0.isFinite()) return v0.toString()
        val v = if (Math.abs(v0) < step * 1e-6) 0.0 else v0 // no "-0"
        val decimals = if (step >= 1) 0 else minOf(6, maxOf(0, -Math.floor(Math.log10(step)).toInt()))
        return String.format(Locale.ROOT, "%.${decimals}f", v)
    }

    /** Header of the measurement between the two cursors. */
    fun deltaTime(ns: Long): String = String.format(Locale.ROOT, "Δt %+.4g s", ns / 1e9)

    /** Measurement of a series: its value at the second cursor and the difference from the first. */
    fun measure(a: Double?, b: Double?): String = when {
        b == null -> "—"
        a == null || !a.isFinite() || !b.isFinite() -> legendValue(b)
        else -> legendValue(b) + String.format(Locale.ROOT, "  Δ %+.4g", b - a)
    }

    /**
     * Amplitude spectrum of the samples (`t`, `v`) between `from` and `to`: they are resampled to `n`
     * equally spaced points (n a power of 2), the mean is removed and a Hann window is applied. Returns
     * (frequencies in Hz, amplitudes) from 0 to Nyquist, or null if there are too few samples.
     */
    fun spectrum(t: LongArray, v: DoubleArray, from: Long, to: Long, n: Int = 512): Pair<DoubleArray, DoubleArray>? {
        if (t.size < 8 || to <= from) return null
        val re = DoubleArray(n); val im = DoubleArray(n)
        var k = 0
        for (i in 0 until n) {
            val ti = from + (to - from) * i / (n - 1)
            while (k + 1 < t.size && t[k + 1] <= ti) k++
            // Linear interpolation between the surrounding samples.
            re[i] = if (k + 1 < t.size && t[k + 1] > t[k] && ti > t[k]) v[k] + (v[k + 1] - v[k]) * (ti - t[k]).toDouble() / (t[k + 1] - t[k]) else v[k]
        }
        if (re.any { !it.isFinite() }) return null
        val mean = re.average()
        for (i in 0 until n) re[i] = (re[i] - mean) * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)))
        fft(re, im)
        val fs = (n - 1) / ((to - from) / 1e9)
        val half = n / 2
        val freqs = DoubleArray(half) { it * fs / n }
        // Amplitude of a sinusoid (the Hann window halves it: ×4/n).
        val amps = DoubleArray(half) { Math.hypot(re[it], im[it]) * 4 / n }
        return freqs to amps
    }

    /** In-place complex FFT (radix 2; the size must be a power of 2). */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of 2" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var x = re[i]; re[i] = re[j]; re[j] = x; x = im[i]; im[i] = im[j]; im[j] = x }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            for (i in 0 until n step len) for (m in 0 until len / 2) {
                val wr = Math.cos(ang * m); val wi = Math.sin(ang * m)
                val ur = re[i + m]; val ui = im[i + m]
                val vr = re[i + m + len / 2] * wr - im[i + m + len / 2] * wi
                val vi = re[i + m + len / 2] * wi + im[i + m + len / 2] * wr
                re[i + m] = ur + vr; im[i + m] = ui + vi
                re[i + m + len / 2] = ur - vr; im[i + m + len / 2] = ui - vi
            }
            len = len shl 1
        }
    }

    /** How many values fall into each of the `bins` equal spans of [lo, hi]. */
    fun histogram(values: DoubleArray, bins: Int, lo: Double, hi: Double): IntArray {
        val out = IntArray(bins)
        if (hi <= lo) { out[0] = values.count { it.isFinite() }; return out }
        for (x in values) if (x.isFinite()) out[((x - lo) / (hi - lo) * bins).toInt().coerceIn(0, bins - 1)]++
        return out
    }

    /** Legend value (4 significant digits). */
    fun legendValue(v: Double?): String = if (v == null) "—" else if (!v.isFinite()) v.toString() else String.format(Locale.ROOT, "%.4g", v)
}
