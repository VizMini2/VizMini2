// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageAnnotationTopicsTest {
    private val marker = listOf("visualization_msgs/msg/ImageMarker")
    private val foxglove = listOf("foxglove_msgs/msg/ImageAnnotations")

    @Test fun sameNamespace() {
        val topics = mapOf("/camera/image_raw" to listOf("sensor_msgs/msg/Image"), "/camera/image_markers" to marker,
            "/front/detections" to foxglove, "/scan" to listOf("sensor_msgs/msg/LaserScan"))
        assertEquals("/camera/image_markers", ImageAnnotationTopics.find("/camera/image_raw", topics))
        assertEquals("/camera/image_markers", ImageAnnotationTopics.find("/camera/image_raw/compressed", topics))
        assertEquals("/front/detections", ImageAnnotationTopics.find("/front/image", topics))
        assertNull(ImageAnnotationTopics.find("/rear/image", topics))
    }

    @Test fun underTheImageTopicFirst() {
        val topics = mapOf("/cam/annotations" to foxglove, "/cam/image/annotations" to marker)
        assertEquals("/cam/image/annotations", ImageAnnotationTopics.find("/cam/image", topics))
    }
}
