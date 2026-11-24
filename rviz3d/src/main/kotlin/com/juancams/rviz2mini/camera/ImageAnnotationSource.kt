// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.camera

import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.TopicDisplay

/**
 * Subscription to an annotations topic: finds out its type in the graph (ImageMarker or
 * Foxglove ImageAnnotations) and keeps applying it to [state]. Retries until the topic appears.
 */
class ImageAnnotationSource(val topic: String) {
    val state = ImageAnnotationState()
    @Volatile var type: String? = null; private set
    @Volatile var error: String? = null; private set
    private var sub: DynRos.Subscription? = null
    @Volatile private var closed = false
    private val retry = Runnable { connect() }

    init { connect() }

    private fun connect() {
        if (closed) return
        val name = DynRos.normalizeName(topic)
        val t = RosConnection.graph()?.topics()?.firstOrNull { it.name == name }?.type
        when (t) {
            IMAGE_MARKER, FOXGLOVE -> {
                type = t
                error = null
                val foxglove = t == FOXGLOVE
                sub = DynRos.subscribe(name, Interfaces.get().msg(t), TopicDisplay.qosProfile(QosPreset.SENSOR).apply { depth(100) }) { v: MsgValue ->
                    if (foxglove) state.applyFoxglove(v) else state.applyImageMarker(v, nowMs())
                }
            }
            null -> Platform.main.postDelayed(retry, RETRY_MS)
            else -> error = "$name is $t, not an image annotation"
        }
    }

    fun close() { closed = true; sub?.close(); sub = null }

    companion object {
        /** Clock for expiries (monotonic, in ms): the same when applying and when reading [ImageAnnotationState.shapes]. */
        fun nowMs(): Long = System.nanoTime() / 1_000_000
        private const val IMAGE_MARKER = "visualization_msgs/msg/ImageMarker"
        private const val FOXGLOVE = "foxglove_msgs/msg/ImageAnnotations"
        private const val RETRY_MS = 1500L
    }
}

