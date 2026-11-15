// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.plot.PanelKind
import com.juancams.rviz2mini.plot.PanelSpec
import com.juancams.rviz2mini.plot.PlotMath
import com.juancams.rviz2mini.plot.PlotViewport
import com.juancams.rviz2mini.plot.SeriesBuffer
import com.juancams.rviz2mini.plot.SeriesSpec
import kotlin.math.max
import kotlin.math.min

/** Theme colors used to paint a panel. */
data class PlotColors(val onSurface: Color, val surface: Color, val primary: Color)

/**
 * Paints a plot panel. Time mode: grid, axes, one line per series and a legend with each one's value
 * at the cursor (or the latest). XY mode: the trajectory of series 0 (x) against series 1 (y).
 * States, spectrum (FFT) and histogram, as in the original app. Stores [plotLeft]/[plotRight] for gestures.
 */
class PlotPainter(private val measurer: TextMeasurer) {
    var plotLeft = 0f; private set
    var plotRight = 0f; private set

    private lateinit var ds: DrawScope
    private lateinit var panel: PanelSpec
    private lateinit var viewport: PlotViewport
    private var buffers: Map<String, SeriesBuffer> = emptyMap()
    private var errors: Map<String, String> = emptyMap()
    private var textLabels: Map<String, List<String>> = emptyMap()
    private lateinit var colors: PlotColors
    private var d = 1f
    private var width = 0f
    private var height = 0f

    private val textStyle get() = TextStyle(fontSize = 10.sp, color = colors.onSurface.copy(alpha = 190 / 255f))
    private val legendStyle get() = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
    private val stateStyle = TextStyle(fontSize = 10.sp, color = Color.White)
    private val gridColor get() = colors.onSurface.copy(alpha = 35 / 255f)
    private val axisColor get() = colors.onSurface.copy(alpha = 110 / 255f)

    fun draw(scope: DrawScope, panel: PanelSpec, viewport: PlotViewport, buffers: Map<String, SeriesBuffer>, errors: Map<String, String>,
             textLabels: Map<String, List<String>>, colors: PlotColors) {
        ds = scope; this.panel = panel; this.viewport = viewport; this.buffers = buffers; this.errors = errors
        this.textLabels = textLabels; this.colors = colors
        d = scope.density; width = scope.size.width; height = scope.size.height
        when (panel.kind) {
            PanelKind.XY -> drawXY()
            PanelKind.STATES -> drawStates()
            PanelKind.TIME -> drawTime()
            PanelKind.FFT -> drawSpectrum()
            PanelKind.HISTOGRAM -> drawHistogram()
        }
    }

    // ---------------------------------------------------------------- utilities

    private fun measure(s: String, style: TextStyle): TextLayoutResult = measurer.measure(s, style)
    private fun textWidth(s: String, style: TextStyle = textStyle) = measure(s, style).size.width.toFloat()
    /** Text with its baseline at `y` (like Android's Canvas.drawText). */
    private fun text(s: String, x: Float, y: Float, style: TextStyle = textStyle) {
        val l = measure(s, style)
        ds.drawText(l, topLeft = Offset(x, y - l.firstBaseline))
    }
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, c: Color, dashed: Boolean = false) =
        ds.drawLine(c, Offset(x0, y0), Offset(x1, y1), strokeWidth = d,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) else null)
    private fun rect(l: Float, t: Float, r: Float, b: Float, c: Color) { if (r > l && b > t) ds.drawRect(c, Offset(l, t), Size(r - l, b - t)) }
    private fun circle(x: Float, y: Float, r: Float, c: Color) = ds.drawCircle(c, r, Offset(x, y))
    private fun stroke(p: Path, c: Color) = ds.drawPath(p, c, style = Stroke(width = 1.8f * d, join = StrokeJoin.Round))
    private val cursorColor get() = colors.onSurface.copy(alpha = 170 / 255f)

    /** Text of a series value: its text if it is a text series, otherwise the number. */
    fun valueText(s: SeriesSpec, v: Double?): String {
        if (v == null) return "—"
        val names = textLabels[s.id]
        if (names != null && v >= 0 && v < names.size && v == Math.rint(v)) return names[v.toInt()]
        return PlotMath.legendValue(v)
    }

    // ---------------------------------------------------------------- spectrum and histogram

    /** Axes with grid for x in [xlo, xhi] and y in [ylo, yhi]; returns (sx, sy) to map to screen. */
    private fun axes(xlo: Double, xhi: Double, ylo: Double, yhi: Double, xUnit: String): Pair<(Double) -> Float, (Double) -> Float>? {
        plotLeft = 46 * d; plotRight = width - 6 * d
        val top = 6 * d; val bottom = height - 16 * d
        if (plotRight <= plotLeft || bottom <= top) return null
        val w = (plotRight - plotLeft).toDouble(); val h = (bottom - top).toDouble()
        val sx = { v: Double -> (plotLeft + (v - xlo) / (xhi - xlo) * w).toFloat() }
        val sy = { v: Double -> (bottom - (v - ylo) / (yhi - ylo) * h).toFloat() }
        val xStep = PlotMath.niceStep((xhi - xlo) / 5); val yStep = PlotMath.niceStep((yhi - ylo) / 4)
        var g = Math.ceil(xlo / xStep) * xStep; var n = 0
        while (g <= xhi && n++ < 50) { line(sx(g), top, sx(g), bottom, gridColor); text(PlotMath.valueLabel(g, xStep) + xUnit, sx(g) + 2 * d, height - 3 * d); g += xStep }
        g = Math.ceil(ylo / yStep) * yStep; n = 0
        while (g <= yhi && n++ < 50) { line(plotLeft, sy(g), plotRight, sy(g), gridColor); text(PlotMath.valueLabel(g, yStep), 2 * d, sy(g) + 4 * d); g += yStep }
        line(plotLeft, bottom, plotRight, bottom, axisColor)
        line(plotLeft, top, plotLeft, bottom, axisColor)
        return sx to sy
    }

    /** Amplitude spectrum of each series over what is in the window (frequency in Hz). */
    private fun drawSpectrum() {
        val from = viewport.startNs; val to = viewport.endNs
        val spectra = panel.series.map { s -> buffers[s.id]?.range(from, to)?.let { (t, v) -> PlotMath.spectrum(t, v, from, to) } }
        val fMax = spectra.filterNotNull().maxOfOrNull { it.first.last() } ?: 1.0
        val aMax = spectra.filterNotNull().maxOfOrNull { s -> s.second.drop(1).maxOrNull() ?: 0.0 }?.takeIf { it > 0 } ?: 1.0
        val (sx, sy) = axes(0.0, fMax, 0.0, aMax * 1.08, " Hz") ?: return
        for ((i, s) in panel.series.withIndex()) {
            val (f, a) = spectra[i] ?: continue
            val path = Path()
            for (k in 1 until f.size) if (k == 1) path.moveTo(sx(f[k]), sy(a[k])) else path.lineTo(sx(f[k]), sy(a[k]))
            stroke(path, Color(panel.colorOf(s)))
        }
        // Legend: the peak frequency of each series.
        drawLegend(plotLeft + 4 * d, 8 * d) { s ->
            val sp = spectra[panel.series.indexOf(s)] ?: return@drawLegend "—"
            val k = (1 until sp.second.size).maxByOrNull { sp.second[it] } ?: return@drawLegend "—"
            String.format(java.util.Locale.ROOT, "%.3g Hz", sp.first[k])
        }
    }

    /** Histogram of each series' values in the window (30 shared bins). */
    private fun drawHistogram() {
        val from = viewport.startNs; val to = viewport.endNs
        val values = panel.series.map { s -> buffers[s.id]?.range(from, to)?.let { (t, v) -> DoubleArray(t.size) { v[it] }.filterIndexed { i, _ -> t[i] in from..to }.toDoubleArray() } ?: DoubleArray(0) }
        val (lo, hi) = PlotMath.bounds(values) ?: (0.0 to 1.0)
        val bins = 30
        val counts = values.map { PlotMath.histogram(it, bins, lo, hi) }
        val cMax = counts.maxOfOrNull { it.maxOrNull() ?: 0 }?.coerceAtLeast(1) ?: 1
        val (sx, sy) = axes(lo, hi, 0.0, cMax * 1.08, "") ?: return
        for ((i, s) in panel.series.withIndex()) {
            val c = Color(panel.colorOf(s)).copy(alpha = (if (panel.series.size > 1) 110 else 170) / 255f)
            for (b in 0 until bins) {
                if (counts[i][b] == 0) continue
                val x0 = lo + (hi - lo) * b / bins; val x1 = lo + (hi - lo) * (b + 1) / bins
                rect(sx(x0) + d / 2, sy(counts[i][b].toDouble()), sx(x1) - d / 2, sy(0.0), c)
            }
        }
        drawLegend(plotLeft + 4 * d, 8 * d) { s ->
            val v = values[panel.series.indexOf(s)]
            if (v.isEmpty()) "—" else String.format(java.util.Locale.ROOT, "n=%d  μ=%.4g  σ=%.3g", v.size, v.average(), Math.sqrt(v.map { (it - v.average()) * (it - v.average()) }.average()))
        }
    }

    // ---------------------------------------------------------------- states

    /** One band per series: each segment in its value's color, with the value written if it fits. */
    private fun drawStates() {
        val series = panel.series
        val from = viewport.startNs; val to = viewport.endNs
        plotLeft = 46 * d; plotRight = width - 6 * d
        val top = 6 * d; val bottom = height - 16 * d
        if (plotRight <= plotLeft || bottom <= top || series.isEmpty()) return
        fun x(t: Long) = (plotLeft + (t - from).toDouble() / (to - from) * (plotRight - plotLeft)).toFloat()
        drawTimeAxis(from, to, top, bottom)
        val gap = 4 * d
        val laneH = ((bottom - top - gap * (series.size - 1)) / series.size).coerceAtMost(36 * d)
        val cursor = viewport.cursorNs
        ds.clipRect(plotLeft, top, plotRight, bottom) {
            for ((i, s) in series.withIndex()) {
                val y0 = top + i * (laneH + gap); val y1 = y0 + laneH
                val (t, v) = buffers[s.id]?.range(from, to) ?: (LongArray(0) to DoubleArray(0))
                var k = 0
                while (k < t.size) {
                    // Segment: while the value does not change.
                    var e = k + 1
                    while (e < t.size && v[e] == v[k]) e++
                    val xa = x(t[k]).coerceAtLeast(plotLeft)
                    val xb = if (e < t.size) x(t[e]) else x(minOf(to, buffers[s.id]?.lastTime() ?: to)).coerceAtLeast(xa + d)
                    rect(xa, y0, xb.coerceAtMost(plotRight), y1, Color(stateColor(v[k])))
                    val label = valueText(s, v[k])
                    if (xb - xa > textWidth(label, stateStyle) + 6 * d) text(label, xa + 3 * d, (y0 + y1) / 2 + 4 * d, stateStyle)
                    k = e
                }
            }
            if (cursor != null && cursor in from..to) line(x(cursor), top, x(cursor), bottom, cursorColor, dashed = true)
        }
        // Name of each band on the left and the value at the cursor (or the latest) in the legend.
        for ((i, s) in series.withIndex()) {
            val y0 = top + i * (laneH + gap)
            circle(4 * d, y0 + laneH / 2, 3 * d, Color(panel.colorOf(s)))
            val name = s.displayLabel.let { if (textWidth(it) > plotLeft - 10 * d) it.takeLast(6) else it }
            text(name, 9 * d, y0 + laneH / 2 + 4 * d)
        }
        drawLegend(plotLeft + 4 * d, top + 2 * d) { s -> valueText(s, buffers[s.id]?.valueAt(cursor ?: to)) }
    }

    /** Color of a state value: always the same for the same value. */
    private fun stateColor(v: Double): Int {
        val h = java.lang.Double.hashCode(v)
        return STATE_COLORS[Math.floorMod(h xor (h ushr 16), STATE_COLORS.size)]
    }

    /** Grid and labels of the time axis. */
    private fun drawTimeAxis(from: Long, to: Long, top: Float, bottom: Float) {
        fun x(t: Long) = (plotLeft + (t - from).toDouble() / (to - from) * (plotRight - plotLeft)).toFloat()
        val origin = viewport.originNs ?: to
        val tStep = PlotMath.niceStep(viewport.windowNs / 1e9 / 4)
        var ts = Math.ceil((from - origin) / 1e9 / tStep) * tStep
        var n = 0
        while (ts * 1e9 + origin <= to && n++ < 50) {
            val xx = x(origin + (ts * 1e9).toLong())
            line(xx, top, xx, bottom, gridColor)
            val label = PlotMath.timeLabel(ts, tStep)
            val lw = textWidth(label)
            text(label, (xx - lw / 2).coerceIn(plotLeft, max(plotLeft, plotRight - lw)), height - 3 * d)
            ts += tStep
        }
        line(plotLeft, bottom, plotRight, bottom, axisColor)
    }

    // ---------------------------------------------------------------- time

    private fun drawTime() {
        val series = panel.series
        val from = viewport.startNs; val to = viewport.endNs
        val data = series.map { s -> buffers[s.id]?.range(from, to) ?: (LongArray(0) to DoubleArray(0)) }
        val (lo, hi) = PlotMath.bounds(data.map { visible(it, from, to) }) ?: (-1.0 to 1.0)

        plotLeft = 46 * d; plotRight = width - 6 * d
        val top = 6 * d; val bottom = height - 16 * d
        if (plotRight <= plotLeft || bottom <= top) return
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun x(t: Long) = (plotLeft + (t - from).toDouble() / (to - from) * (plotRight - plotLeft)).toFloat()

        // Grid and labels.
        val vStep = PlotMath.niceStep((hi - lo) / 4)
        var g = Math.ceil(lo / vStep) * vStep
        var n = 0
        while (g <= hi && n++ < 50) {
            val yy = y(g)
            line(plotLeft, yy, plotRight, yy, gridColor)
            text(PlotMath.valueLabel(g, vStep), 2 * d, yy + 4 * d)
            g += vStep
        }
        drawTimeAxis(from, to, top, bottom)
        line(plotLeft, top, plotLeft, bottom, axisColor)

        val cursor = viewport.cursorNs
        val cursorB = viewport.cursorBNs
        // Lines: with many samples, min and max per pixel column.
        ds.clipRect(plotLeft, top, plotRight, bottom) {
            for ((i, s) in series.withIndex()) {
                val (t, v) = data[i]
                val path = Path()
                var pen = false
                var col = Int.MIN_VALUE; var cMin = 0.0; var cMax = 0.0; var cLast = 0.0
                val dense = t.size > (plotRight - plotLeft) * 2
                fun flush() {
                    if (col == Int.MIN_VALUE) return
                    val xx = col.toFloat()
                    if (!pen) { path.moveTo(xx, y(cMin)); pen = true } else path.lineTo(xx, y(cMin))
                    path.lineTo(xx, y(cMax)); path.lineTo(xx, y(cLast))
                }
                for (k in t.indices) {
                    val value = v[k]
                    if (!value.isFinite()) { flush(); col = Int.MIN_VALUE; pen = false; continue }
                    if (!dense) {
                        if (!pen) { path.moveTo(x(t[k]), y(value)); pen = true } else path.lineTo(x(t[k]), y(value))
                        continue
                    }
                    val c = x(t[k]).toInt()
                    if (c != col) { flush(); col = c; cMin = value; cMax = value } else { cMin = min(cMin, value); cMax = max(cMax, value) }
                    cLast = value
                }
                if (dense) flush()
                stroke(path, Color(panel.colorOf(s)))
            }
            if (cursor != null && cursorB != null) {
                val xa = x(minOf(cursor, cursorB)).coerceIn(plotLeft, plotRight); val xb = x(maxOf(cursor, cursorB)).coerceIn(plotLeft, plotRight)
                rect(xa, top, xb, bottom, colors.primary.copy(alpha = 40 / 255f))
            }
            if (cursor != null && cursor in from..to) line(x(cursor), top, x(cursor), bottom, cursorColor, dashed = true)
            if (cursorB != null && cursorB in from..to) line(x(cursorB), top, x(cursorB), bottom, cursorColor, dashed = true)
        }

        if (cursor != null && cursorB != null) {
            // Measurement: at B, plus the difference from A.
            drawLegend(plotLeft + 4 * d, top + 2 * d, PlotMath.deltaTime(cursorB - cursor)) { s ->
                val b = buffers[s.id] ?: return@drawLegend "—"
                PlotMath.measure(b.valueAt(cursor), b.valueAt(cursorB))
            }
        } else {
            drawLegend(plotLeft + 4 * d, top + 2 * d) { s ->
                valueText(s, buffers[s.id]?.let { b -> if (cursor != null) b.valueAt(cursor) else b.valueAt(to) })
            }
        }
    }

    /** Only the values inside the window (the range brings one extra on each side). */
    private fun visible(dt: Pair<LongArray, DoubleArray>, from: Long, to: Long): DoubleArray {
        val (t, v) = dt
        val out = ArrayList<Double>(v.size)
        for (k in t.indices) if (t[k] in from..to) out += v[k]
        if (out.isEmpty() && v.isNotEmpty()) out += v.last()
        return out.toDoubleArray()
    }

    // ---------------------------------------------------------------- XY

    private fun drawXY() {
        val xs = panel.series.getOrNull(0); val ys = panel.series.getOrNull(1)
        plotLeft = 46 * d; plotRight = width - 6 * d
        val top = 6 * d; val bottom = height - 16 * d
        if (plotRight <= plotLeft || bottom <= top) return
        val xb = xs?.let { buffers[it.id] }; val yb = ys?.let { buffers[it.id] }
        val (px, py) = if (xb != null && yb != null) PlotMath.xyPairs(xb, yb, viewport.startNs, viewport.endNs) else DoubleArray(0) to DoubleArray(0)
        var (xlo, xhi) = PlotMath.bounds(listOf(px)) ?: (-1.0 to 1.0)
        var (ylo, yhi) = PlotMath.bounds(listOf(py)) ?: (-1.0 to 1.0)
        val w = (plotRight - plotLeft).toDouble(); val h = (bottom - top).toDouble()
        // With sameScale, the same scale on both axes (a trajectory is not distorted).
        if (panel.sameScale) {
            val k = max((xhi - xlo) / w, (yhi - ylo) / h)
            val cx = (xlo + xhi) / 2; val cy = (ylo + yhi) / 2
            xlo = cx - k * w / 2; xhi = cx + k * w / 2; ylo = cy - k * h / 2; yhi = cy + k * h / 2
        }
        fun sx(v: Double) = (plotLeft + (v - xlo) / (xhi - xlo) * w).toFloat()
        fun sy(v: Double) = (bottom - (v - ylo) / (yhi - ylo) * h).toFloat()

        val xStep = PlotMath.niceStep((xhi - xlo) / 5)
        val yStep = PlotMath.niceStep((yhi - ylo) / 4)
        var g = Math.ceil(xlo / xStep) * xStep; var n = 0
        while (g <= xhi && n++ < 50) { line(sx(g), top, sx(g), bottom, gridColor); text(PlotMath.valueLabel(g, xStep), sx(g) + 2 * d, height - 3 * d); g += xStep }
        g = Math.ceil(ylo / yStep) * yStep; n = 0
        while (g <= yhi && n++ < 50) { line(plotLeft, sy(g), plotRight, sy(g), gridColor); text(PlotMath.valueLabel(g, yStep), 2 * d, sy(g) + 4 * d); g += yStep }
        line(plotLeft, bottom, plotRight, bottom, axisColor)
        line(plotLeft, top, plotLeft, bottom, axisColor)

        if (ys != null && px.isNotEmpty()) {
            val c = Color(panel.colorOf(ys))
            if (panel.scatter) {
                for (i in px.indices) circle(sx(px[i]), sy(py[i]), 2 * d, c)
            } else {
                val path = Path()
                for (i in px.indices) if (i == 0) path.moveTo(sx(px[i]), sy(py[i])) else path.lineTo(sx(px[i]), sy(py[i]))
                stroke(path, c)
            }
            circle(sx(px.last()), sy(py.last()), 4 * d, c)
        }
        val cursor = viewport.cursorNs
        drawLegend(plotLeft + 4 * d, top + 2 * d) { s -> PlotMath.legendValue(buffers[s.id]?.valueAt(cursor ?: viewport.endNs)) }
    }

    // ---------------------------------------------------------------- legend

    /** Legend: one line per series with its value (`value`), preceded by `header` if present (the measurement). */
    private fun drawLegend(x0: Float, y0: Float, header: String? = null, value: (SeriesSpec) -> String) {
        if (panel.series.isEmpty()) return
        val lines = panel.series.mapIndexed { i, s ->
            val axis = if (panel.kind == PanelKind.XY) (if (i == 0) "x: " else "y: ") else ""
            val err = errors[s.id]
            Triple<SeriesSpec?, String, String>(s, axis + s.displayLabel, err?.let { "⚠ $it" } ?: value(s))
        }.let { if (header != null) listOf(Triple<SeriesSpec?, String, String>(null, header, "")) + it else it }
        val lineH = 14 * d
        val texts = lines.map { if (it.third.isEmpty()) it.second else "${it.second}  ${it.third}" }
        val wMax = texts.maxOf { textWidth(it, legendStyle) } + 14 * d
        rect(x0, y0, x0 + wMax, y0 + lineH * lines.size + 4 * d, colors.surface.copy(alpha = 190 / 255f))
        for ((i, l) in lines.withIndex()) {
            val yy = y0 + lineH * (i + 1)
            val s = l.first
            if (s != null) circle(x0 + 6 * d, yy - 4 * d, 3.5f * d, Color(panel.colorOf(s)))
            text(texts[i], x0 + 12 * d, yy, legendStyle)
        }
    }

    private companion object {
        /** State colors (dark, so the white text is readable). */
        val STATE_COLORS = intArrayOf(
            0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFF8E24AA.toInt(), 0xFFF4511E.toInt(),
            0xFF00897B.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt(), 0xFFC0CA33.toInt(), 0xFFD81B60.toInt()
        )
    }
}
