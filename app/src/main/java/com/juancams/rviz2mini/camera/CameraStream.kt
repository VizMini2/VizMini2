// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.juancams.rviz2mini.ros.RosConnection
import sensor_msgs.CompressedImage
import sensor_msgs.Image
import us.ihmc.fastddsjava.cdr.idl.IDLByteSequence
import us.ihmc.jros2.ROS2SubscriptionCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Subscription to an image topic. Decodes on the DDS thread and delivers bitmaps on the main
 * thread. If the UI has not painted the previous frame yet, the new one is dropped (the most recent
 * is always shown without accumulating delay).
 */
class CameraStream(
    val topic: ImageTopic,
    private val listener: Listener,
    /** null = the sensor one (best effort: matches any publisher). */
    private val qosProfile: us.ihmc.jros2.ROS2QoSProfile? = null
) {

    interface Listener {
        fun onFrame(bitmap: Bitmap, info: FrameInfo)
        fun onStreamError(message: String)
    }

    data class FrameInfo(val width: Int, val height: Int, val encoding: String, val fps: Float)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayPending = AtomicBoolean(false)
    @Volatile private var active = false
    private var subscription: RosConnection.ManagedSubscription<*>? = null
    private var video: com.juancams.rviz2mini.camera.VideoStream? = null

    // DDS thread state.
    private var bytes = ByteArray(0)
    private var pixels = IntArray(0)
    private val pool = arrayOfNulls<Bitmap>(POOL_SIZE)
    private var poolIndex = 0
    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    private var fps = 0f
    private var lastError: String? = null

    fun start() {
        if (active) return
        active = true
        val qos = qosProfile ?: RosConnection.sensorQos()
        if (topic.kind.isVideo) {
            // Every packet goes to the decoder (MediaCodec); only the newest frame is painted.
            video = com.juancams.rviz2mini.camera.VideoStream(topic, qos, { img, p ->
                countFrame()
                if (!displayPending.get()) deliver(toBitmap(img.pixels, img.width, img.height), FrameInfo(img.width, img.height, p.format, fps))
            }, ::reportError).also { it.start() }
            return
        }
        subscription = when (topic.kind) {
            ImageKind.RAW -> {
                val msg = Image()
                RosConnection.subscribe(topic.name, Image::class.java, qos, ROS2SubscriptionCallback { reader ->
                    msg.data.clear() // jros2 1.2 did not clear sequences; 1.5 already does, but it does no harm
                    if (active && reader.read(msg)) onRaw(msg)
                })
            }
            ImageKind.COMPRESSED -> {
                val msg = CompressedImage()
                RosConnection.subscribe(topic.name, CompressedImage::class.java, qos, ROS2SubscriptionCallback { reader ->
                    msg.data.clear()
                    if (active && reader.read(msg)) onCompressed(msg)
                })
            }
            ImageKind.VIDEO, ImageKind.FFMPEG -> null
        }
    }

    fun stop() {
        active = false
        subscription?.cancel()
        subscription = null
        video?.stop(); video = null
    }

    private fun countFrame() {
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsFrames++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            fps = fpsFrames * 1000f / elapsed
            fpsFrames = 0
            fpsWindowStart = now
        }
    }

    private fun copyBytes(seq: IDLByteSequence): Int {
        val size = seq.size()
        if (bytes.size < size) bytes = ByteArray(size)
        val buf = seq.buffer.duplicate()
        buf.position(0)
        buf.limit(size)
        buf.get(bytes, 0, size)
        return size
    }

    private fun onRaw(msg: Image) {
        countFrame()
        if (displayPending.get()) return

        val w = msg.width
        val h = msg.height
        val encoding = msg.encodingAsString
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) {
            reportError("Invalid image size: ${w}x$h")
            return
        }
        val size = copyBytes(msg.data)
        if (pixels.size < w * h) pixels = IntArray(w * h)

        when (val r = ImageDecoding.decode(encoding, w, h, msg.step, msg.isBigendian.toInt() != 0, bytes, size, pixels)) {
            is ImageDecoding.Result.Unsupported -> { reportError("Unsupported encoding: ${r.encoding}"); return }
            is ImageDecoding.Result.Invalid -> { reportError("Invalid image: ${r.reason}"); return }
            ImageDecoding.Result.Ok -> {}
        }

        deliver(toBitmap(pixels, w, h), FrameInfo(w, h, encoding, fps))
    }

    /** Into one of the pool's bitmaps (several, so as not to write into the one the UI is showing). */
    private fun toBitmap(argb: IntArray, w: Int, h: Int): Bitmap {
        poolIndex = (poolIndex + 1) % POOL_SIZE
        var bmp = pool[poolIndex]
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            pool[poolIndex] = bmp
        }
        bmp!!.setPixels(argb, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun onCompressed(msg: CompressedImage) {
        countFrame()
        if (displayPending.get()) return

        val size = copyBytes(msg.data)
        val format = msg.formatAsString
        val offset = ImageTopics.compressedPayloadOffset(format)
        if (size <= offset) {
            reportError("Empty message")
            return
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, offset, size - offset)
        if (bmp == null) {
            reportError("Could not decode format '$format'")
            return
        }
        deliver(bmp, FrameInfo(bmp.width, bmp.height, format, fps))
    }

    private fun deliver(bmp: Bitmap, info: FrameInfo) {
        lastError = null
        displayPending.set(true)
        mainHandler.post {
            displayPending.set(false)
            if (active) listener.onFrame(bmp, info)
        }
    }

    private fun reportError(message: String) {
        if (message == lastError) return
        lastError = message
        mainHandler.post { if (active) listener.onStreamError(message) }
    }

    private companion object {
        const val POOL_SIZE = 3
        const val MAX_PIXELS = 4096L * 4096L
    }
}
