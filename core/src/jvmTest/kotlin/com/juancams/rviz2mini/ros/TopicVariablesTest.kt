// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros

import org.junit.Assert.assertEquals
import org.junit.Test

class TopicVariablesTest {
    @Test
    fun resolvesAndCleansSlashes() {
        val v = mapOf("ns" to "/robot1", "cam" to "front")
        assertEquals("/robot1/scan", TopicVariables.resolve("\${ns}/scan", v))
        assertEquals("/robot1/front/image_raw", TopicVariables.resolve("\${ns}/\${cam}/image_raw", v))
        assertEquals("/scan", TopicVariables.resolve("\${missing}/scan", v))
        assertEquals("/plain", TopicVariables.resolve("/plain", v))
    }

    @Test
    fun variablesAreValidTopicNames() {
        org.junit.Assert.assertTrue(com.juancams.rviz2mini.camera.ImageTopics.isValidName("\${ns}/scan"))
        org.junit.Assert.assertTrue(com.juancams.rviz2mini.camera.ImageTopics.isValidName("/a/\${ns}/scan"))
        org.junit.Assert.assertEquals("\${ns}/scan", com.juancams.rviz2mini.camera.ImageTopics.normalize("\${ns}/scan"))
        org.junit.Assert.assertFalse(com.juancams.rviz2mini.camera.ImageTopics.isValidName("/bad topic"))
    }
}
