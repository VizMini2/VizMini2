// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.camera

/** ROS type of an image topic. */
enum class ImageKind(val rosType: String) {
    RAW("sensor_msgs/msg/Image"),
    COMPRESSED("sensor_msgs/msg/CompressedImage"),
    /** H.264/H.265 video: foxglove's (foxglove_compressed_video_transport, "…/foxglove"). */
    VIDEO("foxglove_msgs/msg/CompressedVideo"),
    /** H.264/H.265 video: ffmpeg_image_transport's ("…/ffmpeg"). */
    FFMPEG("ffmpeg_image_transport_msgs/msg/FFMPEGPacket");

    val isVideo: Boolean get() = this == VIDEO || this == FFMPEG

    companion object {
        /** Every image type, for searching the graph. */
        val rosTypes: Array<String> get() = entries.map { it.rosType }.toTypedArray()

        fun fromName(name: String?): ImageKind? = entries.firstOrNull { it.name == name }
    }
}

data class ImageTopic(val name: String, val kind: ImageKind)

/**
 * jros2 does not expose the ROS graph (there is no equivalent of `ros2 topic list`), so topics
 * are discovered by probing common names: DDS only matches readers and writers of the same
 * type, so a match confirms both that the topic exists and that it is an image.
 */
object ImageTopics {

    private val COMMON_RAW_TOPICS = listOf(
        "/image_raw", "/image", "/image_rect", "/image_color", "/image_rect_color",
        "/camera/image_raw", "/camera/image", "/camera/image_rect", "/camera/image_rect_color",
        "/camera/color/image_raw", "/camera/color/image_rect_raw",
        "/camera/camera/color/image_raw", "/camera/camera/color/image_rect_raw",
        "/camera/rgb/image_raw", "/camera/rgb/image_color", "/camera/rgb/image_rect_color",
        "/camera/depth/image_raw", "/camera/depth/image_rect_raw",
        "/camera/camera/depth/image_rect_raw",
        "/camera/aligned_depth_to_color/image_raw", "/camera/camera/aligned_depth_to_color/image_raw",
        "/camera/infra1/image_rect_raw", "/camera/infra2/image_rect_raw",
        "/usb_cam/image_raw", "/front_camera/image_raw", "/rgb/image_raw", "/depth/image_raw",
        "/oak/rgb/image_raw", "/oak/stereo/image_raw",
        "/zed/zed_node/rgb/image_rect_color", "/zed/zed_node/left/image_rect_color",
        "/zed/zed_node/depth/depth_registered"
    )

    fun normalize(raw: String): String {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return ""
        // With a leading variable (`${ns}/scan`) it is left as is: its value provides the slash.
        return if (t.startsWith("/") || t.startsWith("\${")) t else "/$t"
    }

    /** A topic follows the ROS 2 naming rules (simplified); variables are allowed (`${ns}/scan`, see TopicVariables). */
    fun isValidName(name: String): Boolean {
        val n = name.replace(Regex("""/?\$\{[A-Za-z_][A-Za-z0-9_]*\}"""), "/v")
        return n.matches(Regex("^(/[A-Za-z_~][A-Za-z0-9_]*)+$"))
    }

    /**
     * Candidates to probe: the common ones, their image_transport variants and those the user
     * has added. A name may be tried with both types.
     */
    fun candidates(extra: Collection<String> = emptyList()): List<ImageTopic> {
        val raw = LinkedHashSet<String>()
        raw.addAll(COMMON_RAW_TOPICS)
        val custom = extra.map(::normalize).filter(::isValidName)
        val result = LinkedHashSet<ImageTopic>()
        for (t in raw) {
            result.add(ImageTopic(t, ImageKind.RAW))
            result.add(ImageTopic("$t/compressed", ImageKind.COMPRESSED))
            if ("depth" in t) result.add(ImageTopic("$t/compressedDepth", ImageKind.COMPRESSED))
        }
        for (t in custom) {
            result.add(ImageTopic(t, ImageKind.RAW))
            result.add(ImageTopic(t, ImageKind.COMPRESSED))
            if (!t.endsWith("/compressed") && !t.endsWith("/compressedDepth")) {
                result.add(ImageTopic("$t/compressed", ImageKind.COMPRESSED))
            }
        }
        return result.toList()
    }

    /** Candidates only for a specific name (when added by hand). */
    fun candidatesFor(name: String): List<ImageTopic> {
        val t = normalize(name)
        if (!isValidName(t)) return emptyList()
        val out = mutableListOf(ImageTopic(t, ImageKind.RAW), ImageTopic(t, ImageKind.COMPRESSED))
        if (!t.endsWith("/compressed") && !t.endsWith("/compressedDepth")) {
            out.add(ImageTopic("$t/compressed", ImageKind.COMPRESSED))
        }
        return out
    }

    /** compressedDepth carries a 12-byte header before the PNG. */
    fun compressedPayloadOffset(format: String): Int =
        if (format.contains("compressedDepth", ignoreCase = true)) 12 else 0
}
