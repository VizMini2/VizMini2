// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogTest {

    @Test
    fun parsesReleasesSectionsAndItems() {
        val md = """
            # Changelog
            Intro text.

            ## [0.2.0] - 2026-06-18
            ### Added
            - Tabs.
            - Config tab with a long
              wrapped line.
            ### Fixed
            - Reconnect.

            ## [0.1.0] - 2026-06-08
            ### Added
            - First build.
        """.trimIndent()
        val r = Changelog.parse(md)
        assertEquals(listOf("0.2.0", "0.1.0"), r.map { it.version })
        assertEquals("2026-06-18", r[0].date)
        assertEquals(listOf("Added", "Fixed"), r[0].sections.map { it.title })
        assertEquals("Config tab with a long wrapped line.", r[0].sections[0].items[1])
        assertEquals(3, r[0].itemCount)
    }

    /** The app's changelog: the first version is Gradle's, and all are x.y.z. */
    @Test
    fun bundledChangelogMatchesAppVersion() {
        val file = listOf("src/main/assets/CHANGELOG.md", "app/src/main/assets/CHANGELOG.md").map(::File).first { it.exists() }
        val releases = Changelog.parse(file.readText())
        assertTrue(releases.size >= 5)
        assertTrue(releases.all { it.version.matches(Regex("""\d+\.\d+\.\d+""")) && it.itemCount > 0 })
        val gradle = listOf("build.gradle.kts", "app/build.gradle.kts").map(::File).first { it.exists() && it.readText().contains("versionName") }
        val versionName = Regex("""versionName = "([^"]+)"""").find(gradle.readText())!!.groupValues[1]
        assertEquals(versionName, releases.first().version)
    }

    /** The Spanish translation has the same versions, dates and number of changes. */
    @Test
    fun spanishChangelogMatchesTheEnglishOne() {
        fun read(name: String) = Changelog.parse(listOf("src/main/assets/$name", "app/src/main/assets/$name").map(::File).first { it.exists() }.readText())
        val en = read("CHANGELOG.md")
        val es = read("CHANGELOG.es.md")
        assertEquals(en.map { it.version to it.date }, es.map { it.version to it.date })
        assertEquals(en.map { it.itemCount }, es.map { it.itemCount })
        assertEquals("CHANGELOG.es.md", Changelog.fileFor("es"))
        assertEquals("CHANGELOG.md", Changelog.fileFor("en"))
    }
}
