// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import com.juancams.rviz2mini.camera.ImageShape
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/** Image annotations (ImageMarker / foxglove) painted with Java2D onto an ARGB image: the same as Android's Canvas painter. */
object Java2dAnnotations {
    fun paint(argb: IntArray, w: Int, h: Int, shapes: List<ImageShape>) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, argb, 0, w)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        fun color(c: Int) = Color(c, true)
        fun stroke(w: Double) = BasicStroke(w.toFloat().coerceAtLeast(1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (s in shapes) when (s) {
            is ImageShape.Circle -> {
                val e = Ellipse2D.Double(s.x - s.radius, s.y - s.radius, s.radius * 2, s.radius * 2)
                if (s.fill != 0) { g.color = color(s.fill); g.fill(e) }
                if (s.outline != 0) { g.color = color(s.outline); g.stroke = stroke(s.thickness); g.draw(e) }
            }
            is ImageShape.Points -> {
                val n = s.xy.size / 2
                if (n == 0) continue
                fun c(i: Int) = if (s.colors.size == n) s.colors[i] else s.outline
                val sw = s.thickness.coerceAtLeast(1.0)
                g.stroke = stroke(sw)
                when (s.kind) {
                    ImageShape.Points.Kind.POINTS -> for (i in 0 until n) {
                        val r = (sw / 2).coerceAtLeast(1.5)
                        g.color = color(c(i)); g.fill(Ellipse2D.Double(s.xy[i * 2] - r, s.xy[i * 2 + 1] - r, r * 2, r * 2))
                    }
                    ImageShape.Points.Kind.LIST -> for (i in 0 until n - 1 step 2) {
                        g.color = color(c(i)); g.draw(Line2D.Double(s.xy[i * 2], s.xy[i * 2 + 1], s.xy[i * 2 + 2], s.xy[i * 2 + 3]))
                    }
                    else -> {
                        val path = Path2D.Double()
                        path.moveTo(s.xy[0], s.xy[1])
                        for (i in 1 until n) path.lineTo(s.xy[i * 2], s.xy[i * 2 + 1])
                        if (s.kind == ImageShape.Points.Kind.LOOP) path.closePath()
                        if (s.fill != 0) { g.color = color(s.fill); g.fill(path) }
                        if (s.colors.size == n) for (i in 0 until n - 1) {
                            g.color = color(s.colors[i]); g.draw(Line2D.Double(s.xy[i * 2], s.xy[i * 2 + 1], s.xy[i * 2 + 2], s.xy[i * 2 + 3]))
                        } else if (s.outline != 0) { g.color = color(s.outline); g.draw(path) }
                    }
                }
            }
            is ImageShape.Text -> {
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(s.size.toFloat().coerceAtLeast(6f))
                val m = g.fontMetrics
                val tx = s.x.toFloat(); val ty = s.y.toFloat()
                if (s.background != 0) {
                    g.color = color(s.background)
                    g.fill(java.awt.geom.Rectangle2D.Float(tx - 2, ty - m.ascent - 2, m.stringWidth(s.text) + 4f, (m.ascent + m.descent) + 4f))
                }
                g.color = if (s.color != 0) color(s.color) else Color.WHITE
                g.drawString(s.text, tx, ty)
            }
        }
        g.dispose()
        img.getRGB(0, 0, w, h, argb, 0, w)
    }
}
