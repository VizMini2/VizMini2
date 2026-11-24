// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.camera

import com.juancams.rviz2mini.platform.DecodedImage
import com.juancams.rviz2mini.platform.ImageCodecs
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.platform.SystemClock
import com.juancams.rviz2mini.ros.RosConnection
import sensor_msgs.CompressedImage
import sensor_msgs.Image
import us.ihmc.fastddsjava.cdr.idl.IDLByteSequence
import us.ihmc.jros2.ROS2SubscriptionCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Subscription to an image topic for any platform: decodes on the DDS thread and delivers the pixels
 * (ARGB) on the main thread. If the UI has not painted the previous one yet, the new one is dropped (always the most
 * recent, without accumulating lag). Like Android's CameraStream, but without Bitmap.
 */
class FrameStream(
    val topic: ImageTopic,
    private val onFrame: (DecodedImage, CameraFrameInfo) -> Unit,
    private val onError: (String) -> Unit,
    private val qosProfile: us.ihmc.jros2.ROS2QoSProfile? = null,
) {
    private val displayPending = AtomicBoolean(false)
    @Volatile private var active = false
    private var subscription: RosConnection.ManagedSubscription<*>? = null
    private var video: VideoStream? = null

    // DDS thread state.
    private var bytes = ByteArray(0)
    /** Rotates between several buffers so it does not write into the one the UI is painting. */
    private val pool = arrayOfNulls<IntArray>(POOL_SIZE)
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
            // Every packet goes to the decoder (it needs them all, in order); only the newest frame is painted.
            video = VideoStream(topic, qos, { img, p ->
                countFrame()
                if (!displayPending.get()) deliver(img, CameraFrameInfo(img.width, img.height, p.format, fps))
            }, ::reportError).also { it.start() }
            return
        }
        subscription = when (topic.kind) {
            ImageKind.RAW -> {
                val msg = Image()
                RosConnection.subscribe(topic.name, Image::class.java, qos, ROS2SubscriptionCallback { reader ->
                    msg.data.clear()
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
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) { reportError("Invalid image size: ${w}x$h"); return }
        val size = copyBytes(msg.data)
        poolIndex = (poolIndex + 1) % POOL_SIZE
        val pixels = pool[poolIndex]?.takeIf { it.size == w * h } ?: IntArray(w * h).also { pool[poolIndex] = it }
        when (val r = ImageDecoding.decode(encoding, w, h, msg.step, msg.isBigendian.toInt() != 0, bytes, size, pixels)) {
            is ImageDecoding.Result.Unsupported -> { reportError("Unsupported encoding: ${r.encoding}"); return }
            is ImageDecoding.Result.Invalid -> { reportError("Invalid image: ${r.reason}"); return }
            ImageDecoding.Result.Ok -> {}
        }
        deliver(DecodedImage(w, h, pixels), CameraFrameInfo(w, h, encoding, fps))
    }

    private fun onCompressed(msg: CompressedImage) {
        countFrame()
        if (displayPending.get()) return
        val size = copyBytes(msg.data)
        val format = msg.formatAsString
        val offset = ImageTopics.compressedPayloadOffset(format)
        if (size <= offset) { reportError("Empty message"); return }
        val img = ImageCodecs.decode(bytes, offset, size - offset) ?: run { reportError("Could not decode format '$format'"); return }
        deliver(img, CameraFrameInfo(img.width, img.height, format, fps))
    }

    private fun deliver(img: DecodedImage, info: CameraFrameInfo) {
        lastError = null
        displayPending.set(true)
        Platform.main.post {
            displayPending.set(false)
            if (active) onFrame(img, info)
        }
    }

    private fun reportError(message: String) {
        if (message == lastError) return
        lastError = message
        Platform.main.post { if (active) onError(message) }
    }

    private companion object {
        const val POOL_SIZE = 3
        const val MAX_PIXELS = 4096L * 4096L
    }
}

/** Size, encoding and rate of what arrives from a camera. */
data class CameraFrameInfo(val width: Int, val height: Int, val encoding: String, val fps: Float)
