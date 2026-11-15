// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.display

import com.juancams.rviz2mini.camera.CameraFrameInfo
import com.juancams.rviz2mini.camera.FrameStream
import com.juancams.rviz2mini.camera.ImageAnnotationSource
import com.juancams.rviz2mini.camera.ImageShape
import com.juancams.rviz2mini.camera.ImageTopic
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An image topic opened by a display (Camera, DepthCloud). */
interface RvizImageStream {
    fun stop()
    /** The annotations painted on top (null: no annotations topic). */
    val annotations: ImageAnnotationSource? get() = null
}

/**
 * How camera displays open images on each platform. `onFrame(argb, width, height, info)` on the main
 * thread (the pixels may come from a reused buffer: they must be copied right away). `annotations`: annotations
 * topic to paint on top. By default, [FrameStream] and annotations with [paintAnnotations] (the PC sets a
 * Java2D painter); Android sets its CameraStream, with the annotations painted with Canvas.
 */
object RvizImageStreams {
    @Volatile var open: (host: DisplayHost, topic: ImageTopic, qos: us.ihmc.jros2.ROS2QoSProfile?, annotations: String?,
                         onFrame: (IntArray, Int, Int, CameraFrameInfo) -> Unit, onError: (String) -> Unit) -> RvizImageStream =
        { _, topic, qos, annotationsTopic, onFrame, onError ->
            val ann = annotationsTopic?.let { ImageAnnotationSource(it) }
            val s = FrameStream(topic, { img, info ->
                val shapes = ann?.state?.shapes(ImageAnnotationSource.nowMs()).orEmpty()
                val paint = paintAnnotations
                if (shapes.isNotEmpty() && paint != null) {
                    val px = img.pixels.copyOf(img.width * img.height)
                    paint(px, img.width, img.height, shapes)
                    onFrame(px, img.width, img.height, info)
                } else onFrame(img.pixels, img.width, img.height, info)
            }, onError, qos)
            s.start()
            object : RvizImageStream {
                override fun stop() { s.stop(); ann?.close() }
                override val annotations get() = ann
            }
        }

    /** Paints annotations (in image pixels) over an ARGB image, in place. Null: the platform cannot. */
    @Volatile var paintAnnotations: ((argb: IntArray, w: Int, h: Int, shapes: List<ImageShape>) -> Unit)? = null

    /** Annotations error (topic of another type), for the display status. */
    fun annotationsError(a: ImageAnnotationSource?): String? = a?.error

    /** What is appended to the display's OK status: " · N annotations" or " · waiting for topic". */
    fun annotationsSuffix(a: ImageAnnotationSource?): String =
        a?.let { if (it.type == null) " · waiting for ${it.topic}" else " · ${it.state.shapes(ImageAnnotationSource.nowMs()).size} annotations" } ?: ""

    /** ARGB (ints) -> RGBA (bytes), in a direct buffer ready for a Filament texture. */
    fun toRgba(argb: IntArray, w: Int, h: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until w * h) {
            val p = argb[i]
            buf.put((p shr 16).toByte()); buf.put((p shr 8).toByte()); buf.put(p.toByte()); buf.put((p ushr 24).toByte())
        }
        buf.flip()
        return buf
    }
}
