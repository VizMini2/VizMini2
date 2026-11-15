// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.android.filament.filamat.MaterialBuilder
import com.juancams.rviz2mini.rviz.display.DisplayFactory
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.ImageDisplay
import com.juancams.rviz2mini.rviz.render.GlyphCanvas
import com.juancams.rviz2mini.rviz.display.RvizImageStream
import com.juancams.rviz2mini.rviz.display.RvizImageStreams
import com.juancams.rviz2mini.camera.CameraStream
import com.juancams.rviz2mini.camera.CameraFrameInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android side of the shared 3D viewer (rviz3d): mobile materials, Canvas text and the camera displays. */
object AndroidRviz {
    fun install(app: Context) {
        FilamentHub.materialsDir = { File(app.filesDir, "filamat") }
        FilamentHub.fingerprint = { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString() }
        FilamentHub.materialPlatform = MaterialBuilder.Platform.MOBILE
        FilamentHub.materialApi = MaterialBuilder.TargetApi.ALL
        FilamentHub.backend = com.google.android.filament.Engine.Backend.DEFAULT
        com.juancams.rviz2mini.rviz.ui.RvizDialogStyle.bottomSheet = true
        GlyphCanvas.create = { size, textPx, strokePx -> AndroidGlyphCanvas(size, textPx, strokePx) }
        DisplayFactory.platformDisplays = { host, c ->
            when (c.type) {
                DisplayType.IMAGE -> ImageDisplay(host, c)
                else -> null
            }
        }
        // Camera and DepthCloud images, with the usual CameraStream and the annotations painted on top.
        RvizImageStreams.open = { host, topic, qos, annotationsTopic, onFrame, onError ->
            val ann = annotationsTopic?.let { com.juancams.rviz2mini.camera.ImageAnnotationSource(it) }
            var px = IntArray(0)
            val stream = CameraStream(topic, object : CameraStream.Listener {
                override fun onFrame(bitmap: Bitmap, info: CameraStream.FrameInfo) {
                    val shapes = ann?.state?.shapes(com.juancams.rviz2mini.camera.ImageAnnotationSource.nowMs()).orEmpty()
                    val bmp = if (shapes.isNotEmpty()) bitmap.copy(Bitmap.Config.ARGB_8888, true).also {
                        com.juancams.rviz2mini.camera.ImageAnnotationPainter.draw(Canvas(it), shapes, 1f, 0f, 0f)
                    } else bitmap
                    val n = bmp.width * bmp.height
                    if (px.size != n) px = IntArray(n)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    onFrame(px, bmp.width, bmp.height, CameraFrameInfo(info.width, info.height, info.encoding, info.fps))
                }
                override fun onStreamError(message: String) = onError(message)
            }, qos)
            stream.start()
            object : RvizImageStream {
                override fun stop() { stream.stop(); ann?.close() }
                override val annotations get() = ann
            }
        }
    }
}

private class AndroidGlyphCanvas(private val size: Int, textPx: Float, strokePx: Float) : GlyphCanvas {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textPx; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.WHITE }
    private val stroke = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = strokePx; color = Color.BLACK; strokeJoin = Paint.Join.ROUND }
    private val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bmp)
    override val ascent get() = paint.ascent()
    override val descent get() = paint.descent()
    override fun measure(text: String) = paint.measureText(text)
    override fun draw(text: String, x: Float, y: Float) { canvas.drawText(text, x, y, stroke); canvas.drawText(text, x, y, paint) }
    override fun pixels(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf) // premultiplied RGBA
        buf.flip()
        bmp.recycle()
        return buf
    }
}
