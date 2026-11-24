// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.geom

/**
 * Where an image panel (Image or Camera display) sits on top of the 3D view. By default it hugs a corner; once the
 * user drags it, it keeps its own position as fractions of the view (`x`, `y` of its top-left corner, so it survives
 * window resizes). The width is a fraction of the view width and the height follows the image aspect ratio.
 */
object OverlayLayout {
    /** Smallest and largest width, as a fraction of the view. */
    const val MIN_SIZE = 0.1
    const val MAX_SIZE = 1.0

    /** Position not set: use the corner. */
    fun isFree(x: Double, y: Double) = x >= 0 && y >= 0

    /**
     * The panel rectangle in pixels with the origin at the top left: left, top, width, height. It is always kept
     * inside the view.
     */
    fun rect(corner: String, x: Double, y: Double, size: Double, viewW: Int, viewH: Int, imageW: Int, imageH: Int, margin: Int): IntArray {
        var w = (viewW * size.coerceIn(MIN_SIZE, MAX_SIZE)).toInt().coerceAtLeast(1)
        var h = (w.toDouble() * imageH / imageW.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val maxH = (viewH - 2 * margin).coerceAtLeast(1)
        if (h > maxH) { w = (w.toDouble() * maxH / h).toInt().coerceAtLeast(1); h = maxH }
        val left: Int; val top: Int
        if (isFree(x, y)) {
            left = (x * viewW).toInt(); top = (y * viewH).toInt()
        } else {
            left = if (corner.endsWith("left")) margin else viewW - w - margin
            top = if (corner.startsWith("Top")) margin else viewH - h - margin
        }
        return intArrayOf(left.coerceIn(0, (viewW - w).coerceAtLeast(0)), top.coerceIn(0, (viewH - h).coerceAtLeast(0)), w, h)
    }

    /** The free position (fractions) after dragging a panel whose rectangle was `r` by (dx, dy) pixels. */
    fun moved(r: IntArray, dx: Float, dy: Float, viewW: Int, viewH: Int): Pair<Double, Double> {
        val left = (r[0] + dx).coerceIn(0f, (viewW - r[2]).coerceAtLeast(0).toFloat())
        val top = (r[1] + dy).coerceIn(0f, (viewH - r[3]).coerceAtLeast(0).toFloat())
        return left.toDouble() / viewW.coerceAtLeast(1) to top.toDouble() / viewH.coerceAtLeast(1)
    }

    /** The new width fraction after dragging the resize corner by `dx` pixels (or pinching by `scale`). */
    fun resized(size: Double, dx: Float, viewW: Int, scale: Float = 1f): Double =
        ((size * scale) + dx / viewW.coerceAtLeast(1)).coerceIn(MIN_SIZE, MAX_SIZE)
}
