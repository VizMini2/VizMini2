// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTopicsTest {

    @Test
    fun normalizeAddsLeadingSlashAndTrims() {
        assertEquals("/camera/image_raw", ImageTopics.normalize("  camera/image_raw/ "))
        assertEquals("/a", ImageTopics.normalize("/a"))
        assertEquals("", ImageTopics.normalize("  "))
    }

    @Test
    fun validNames() {
        assertTrue(ImageTopics.isValidName("/camera/image_raw"))
        assertTrue(ImageTopics.isValidName("/_private/x1"))
        assertFalse(ImageTopics.isValidName("/1camera"))
        assertFalse(ImageTopics.isValidName("/camera//image"))
        assertFalse(ImageTopics.isValidName("/camera image"))
        assertFalse(ImageTopics.isValidName(""))
    }

    @Test
    fun candidatesIncludeRawAndCompressedVariants() {
        val c = ImageTopics.candidates()
        assertTrue(ImageTopic("/camera/image_raw", ImageKind.RAW) in c)
        assertTrue(ImageTopic("/camera/image_raw/compressed", ImageKind.COMPRESSED) in c)
        assertTrue(ImageTopic("/camera/depth/image_rect_raw/compressedDepth", ImageKind.COMPRESSED) in c)
        assertFalse(ImageTopic("/camera/image_raw/compressedDepth", ImageKind.COMPRESSED) in c)
        assertEquals("Sin duplicados", c.size, c.toSet().size)
    }

    @Test
    fun customTopicsAreProbedWithBothTypes() {
        val c = ImageTopics.candidates(listOf("robot/eye", "bad name", "/robot/eye"))
        assertTrue(ImageTopic("/robot/eye", ImageKind.RAW) in c)
        assertTrue(ImageTopic("/robot/eye", ImageKind.COMPRESSED) in c)
        assertTrue(ImageTopic("/robot/eye/compressed", ImageKind.COMPRESSED) in c)
        assertFalse(c.any { it.name.contains(" ") })
        assertEquals(c.size, c.toSet().size)
    }

    @Test
    fun candidatesForSingleName() {
        assertEquals(
            listOf(
                ImageTopic("/cam", ImageKind.RAW),
                ImageTopic("/cam", ImageKind.COMPRESSED),
                ImageTopic("/cam/compressed", ImageKind.COMPRESSED)
            ),
            ImageTopics.candidatesFor("cam")
        )
        assertEquals(2, ImageTopics.candidatesFor("/cam/compressed").size)
        assertTrue(ImageTopics.candidatesFor("no valid").isEmpty())
    }

    @Test
    fun compressedDepthHeaderOffset() {
        assertEquals(12, ImageTopics.compressedPayloadOffset("16UC1; compressedDepth png"))
        assertEquals(0, ImageTopics.compressedPayloadOffset("rgb8; jpeg compressed bgr8"))
        assertEquals(0, ImageTopics.compressedPayloadOffset(""))
    }
}
