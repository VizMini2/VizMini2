// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import androidx.compose.ui.graphics.toComposeImageBitmap

import com.juancams.rviz2mini.rviz.render.GlyphCanvas
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The PC parts of the shared 3D viewer (rviz3d): the text atlas with Java2D. The materials, for desktop OpenGL (the default). */
object DesktopRviz {
    fun install() {
        GlyphCanvas.create = { size, textPx, strokePx -> Java2dGlyphCanvas(size, textPx, strokePx) }
        com.juancams.rviz2mini.rviz.display.RvizImageStreams.paintAnnotations = Java2dAnnotations::paint
        com.juancams.rviz2mini.rviz.display.DisplayFactory.platformDisplays = { host, c ->
            if (c.type == com.juancams.rviz2mini.rviz.display.DisplayType.IMAGE) DesktopImageDisplay(host, c) else null
        }
        installCatalogMedia()
        com.juancams.rviz2mini.ui.PlatformTabs.screens[com.juancams.rviz2mini.tabs.TabType.RVIZ] = { spec, m -> RvizDesktopScreen(spec, m) }
    }
}

private class Java2dGlyphCanvas(private val size: Int, textPx: Float, private val strokePx: Float) : GlyphCanvas {
    private val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE)
    private val g = img.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        font = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(textPx)
    }
    private val metrics = g.fontMetrics
    override val ascent get() = -metrics.ascent.toFloat()
    override val descent get() = metrics.descent.toFloat()
    override fun measure(text: String) = g.font.getStringBounds(text, g.fontRenderContext).width.toFloat()
    override fun draw(text: String, x: Float, y: Float) {
        val shape = TextLayout(text, g.font, g.fontRenderContext).getOutline(java.awt.geom.AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()))
        g.color = Color.BLACK
        g.stroke = BasicStroke(strokePx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
        g.color = Color.WHITE
        g.fill(shape)
    }
    override fun pixels(): ByteBuffer {
        g.dispose()
        val px = IntArray(size * size)
        img.getRGB(0, 0, size, size, px, 0, size) // non-premultiplied ARGB
        val buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        for (p in px) {
            val a = p ushr 24
            // Premultiplied RGBA, like Android.
            buf.put((((p shr 16) and 255) * a / 255).toByte()); buf.put((((p shr 8) and 255) * a / 255).toByte())
            buf.put(((p and 255) * a / 255).toByte()); buf.put(a.toByte())
        }
        buf.flip()
        return buf
    }
}

/** Screenshots and videos of the tab catalog, from the resources (copied from Android, see build.gradle.kts). */
private fun installCatalogMedia() {
    val cache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<androidx.compose.ui.graphics.ImageBitmap>>()
    com.juancams.rviz2mini.ui.screens.CatalogMedia.image = { name ->
        cache.getOrPut(name) {
            java.util.Optional.ofNullable(DesktopRviz::class.java.getResourceAsStream("/catalog/$name.webp")?.use { s ->
                runCatching<androidx.compose.ui.graphics.ImageBitmap> { org.jetbrains.skia.Image.makeFromEncoded(s.readBytes()).toComposeImageBitmap() }.getOrNull()
            })
        }.orElse(null)
    }
    // The video, with the system player (desktop Compose does not play video).
    com.juancams.rviz2mini.ui.screens.CatalogMedia.playVideo = { name ->
        Thread {
            runCatching {
                // In Spanish, the Spanish recording when there is one (res/raw-es).
                val es = DesktopSettings.effectiveLanguage() == "es"
                val res = (if (es) DesktopRviz::class.java.getResourceAsStream("/catalog/es/$name.mp4") else null)
                    ?: DesktopRviz::class.java.getResourceAsStream("/catalog/$name.mp4") ?: return@runCatching
                val f = java.io.File(System.getProperty("java.io.tmpdir"), "vizmini2-$name${if (es) "-es" else ""}.mp4")
                res.use { i -> f.outputStream().use { i.copyTo(it) } }
                if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(f)
                else ProcessBuilder("xdg-open", f.absolutePath).start()
            }
        }.start()
    }
}
