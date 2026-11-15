// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyPolicyTest {

    // JVM tests run in the app/ module directory.
    private val inApp = File("src/main/assets/privacy.html")
    private val published = File("../tools/firebase/hosting/privacy.html")

    @Test
    fun theAppShowsTheSamePolicyThatIsPublished() {
        assertTrue("Falta $published", published.exists())
        assertEquals("Copia app/src/main/assets/privacy.html de tools/firebase/hosting/privacy.html", published.readText(), inApp.readText())
    }

    @Test
    fun bothLanguagesAndTheDeletionSectionArePresent() {
        val html = inApp.readText()
        for (id in listOf("id=\"es\"", "id=\"en\"", "id=\"es-delete\"", "id=\"en-delete\"")) assertTrue(id, id in html)
        assertTrue(PrivacyActivity.PUBLIC_URL.startsWith("https://"))
    }
}
