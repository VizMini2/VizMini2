// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.home

import android.content.Context

/**
 * App changelog (assets/CHANGELOG.md, Keep a Changelog format). Minimal parser: versions
 * "## [x.y.z] - date", "### Added" sections and "- text" items. No Android dependencies
 * except [load], so it can be tested on the JVM.
 */
object Changelog {

    data class Section(val title: String, val items: List<String>)

    data class Release(val version: String, val date: String, val sections: List<Section>) {
        val itemCount: Int get() = sections.sumOf { it.items.size }
    }

    private val RELEASE = Regex("""^##\s+\[?([^\]\s]+)]?\s*(?:-\s*(.+))?$""")

    fun parse(markdown: String): List<Release> {
        val releases = ArrayList<Release>()
        var version: String? = null
        var date = ""
        var sections = ArrayList<Section>()
        var title: String? = null
        var items = ArrayList<String>()

        fun closeSection() {
            title?.let { if (items.isNotEmpty()) sections.add(Section(it, items)) }
            title = null
            items = ArrayList()
        }
        fun closeRelease() {
            closeSection()
            version?.let { releases.add(Release(it, date, sections)) }
            version = null
            sections = ArrayList()
        }

        for (raw in markdown.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> {
                    closeRelease()
                    RELEASE.matchEntire(line)?.let { m ->
                        version = m.groupValues[1]
                        date = m.groupValues[2].trim()
                    }
                }
                line.startsWith("### ") && version != null -> {
                    closeSection()
                    title = line.removePrefix("### ").trim()
                }
                line.startsWith("- ") && title != null -> items.add(line.removePrefix("- ").trim())
                // Line continuing the previous item.
                line.isNotEmpty() && title != null && items.isNotEmpty() && !line.startsWith("#") ->
                    items[items.size - 1] = items.last() + " " + line
            }
        }
        closeRelease()
        return releases
    }

    @Volatile private var cached: Pair<String, List<Release>>? = null

    /** Changelog file for a language: CHANGELOG.<language>.md if a translation exists, otherwise English. */
    fun fileFor(language: String) = if (language == "es") "CHANGELOG.es.md" else "CHANGELOG.md"

    /** Changelog in the language the app is shown in (that of its resources). */
    fun load(context: Context): List<Release> {
        val file = fileFor(context.resources.configuration.locales[0].language)
        cached?.let { (f, list) -> if (f == file) return list }
        val list = try {
            parse(context.assets.open(file).bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            if (file != "CHANGELOG.md") return parse(context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() })
            emptyList()
        }
        cached = file to list
        return list
    }
}
