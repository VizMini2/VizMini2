// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.camera

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** Draws annotations: the image point (x, y) maps to (dx + x·scale, dy + y·scale) on the canvas. */
object ImageAnnotationPainter {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun draw(canvas: Canvas, shapes: List<ImageShape>, scale: Float, dx: Float, dy: Float) {
        fun x(v: Double) = (dx + v * scale).toFloat()
        fun y(v: Double) = (dy + v * scale).toFloat()
        for (s in shapes) when (s) {
            is ImageShape.Circle -> {
                if (s.fill != 0) { fill.color = s.fill; canvas.drawCircle(x(s.x), y(s.y), (s.radius * scale).toFloat(), fill) }
                if (s.outline != 0) { stroke.color = s.outline; stroke.strokeWidth = (s.thickness * scale).toFloat().coerceAtLeast(1f); canvas.drawCircle(x(s.x), y(s.y), (s.radius * scale).toFloat(), stroke) }
            }
            is ImageShape.Points -> {
                val n = s.xy.size / 2
                if (n == 0) continue
                fun color(i: Int) = if (s.colors.size == n) s.colors[i] else s.outline
                val w = (s.thickness * scale).toFloat().coerceAtLeast(1f)
                when (s.kind) {
                    ImageShape.Points.Kind.POINTS -> for (i in 0 until n) { fill.color = color(i); canvas.drawCircle(x(s.xy[i * 2]), y(s.xy[i * 2 + 1]), (w / 2).coerceAtLeast(1.5f), fill) }
                    ImageShape.Points.Kind.LIST -> for (i in 0 until n - 1 step 2) {
                        stroke.color = color(i); stroke.strokeWidth = w
                        canvas.drawLine(x(s.xy[i * 2]), y(s.xy[i * 2 + 1]), x(s.xy[i * 2 + 2]), y(s.xy[i * 2 + 3]), stroke)
                    }
                    else -> {
                        path.reset()
                        path.moveTo(x(s.xy[0]), y(s.xy[1]))
                        for (i in 1 until n) path.lineTo(x(s.xy[i * 2]), y(s.xy[i * 2 + 1]))
                        if (s.kind == ImageShape.Points.Kind.LOOP) path.close()
                        if (s.fill != 0) { fill.color = s.fill; canvas.drawPath(path, fill) }
                        if (s.colors.size == n) for (i in 0 until n - 1) {
                            stroke.color = s.colors[i]; stroke.strokeWidth = w
                            canvas.drawLine(x(s.xy[i * 2]), y(s.xy[i * 2 + 1]), x(s.xy[i * 2 + 2]), y(s.xy[i * 2 + 3]), stroke)
                        } else if (s.outline != 0) { stroke.color = s.outline; stroke.strokeWidth = w; canvas.drawPath(path, stroke) }
                    }
                }
            }
            is ImageShape.Text -> {
                text.textSize = (s.size * scale).toFloat().coerceAtLeast(6f)
                val tx = x(s.x); val ty = y(s.y)
                if (s.background != 0) {
                    fill.color = s.background
                    canvas.drawRect(tx - 2, ty + text.ascent() - 2, tx + text.measureText(s.text) + 2, ty + text.descent() + 2, fill)
                }
                text.color = if (s.color != 0) s.color else android.graphics.Color.WHITE
                canvas.drawText(s.text, tx, ty, text)
            }
        }
    }
}

/** ImageView that draws the [annotations] on top (with the same scale and position). */
class AnnotatedImageView(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    var annotations: ImageAnnotationState? = null
    private val values = FloatArray(9)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = annotations ?: return
        if (drawable == null) return
        val shapes = a.shapes(ImageAnnotationSource.nowMs())
        if (shapes.isEmpty()) return
        imageMatrix.getValues(values)
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        ImageAnnotationPainter.draw(canvas, shapes, values[android.graphics.Matrix.MSCALE_X], values[android.graphics.Matrix.MTRANS_X], values[android.graphics.Matrix.MTRANS_Y])
        canvas.restore()
    }
}
