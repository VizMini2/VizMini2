// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.i18n

import com.juancams.rviz2mini.platform.Platform
import org.w3c.dom.Element
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Shared UI texts. They are the same `strings.xml` as the Android app (app/src/main/res): on
 * Android they come from its resources (in the language the app picks) and on the other platforms those
 * XML files are read from the classpath (res/values-<language>/strings*.xml).
 */
interface StringSource {
    fun get(key: String): String?
    fun plural(key: String, count: Int): String?
}

/** `tr("config_domain")`, `tr("main_domain_badge", 7)`: like getString(R.string.x, args). If missing, the key. */
fun tr(key: String, vararg args: Any?): String {
    val s = Platform.strings.get(key) ?: return key
    return if (args.isEmpty()) s else try { String.format(s, *args) } catch (_: Exception) { s }
}

/** Like getQuantityString(R.plurals.x, count, args). */
fun trPlural(key: String, count: Int, vararg args: Any?): String {
    val s = Platform.strings.plural(key, count) ?: return key
    return try { String.format(s, *(if (args.isEmpty()) arrayOf(count) else args)) } catch (_: Exception) { s }
}

/** The strings.xml files from the classpath, in the system language (or `vizmini2.lang`) with English as the default. */
class XmlStrings(lang: String = System.getProperty("vizmini2.lang") ?: Locale.getDefault().language) : StringSource {
    private val strings = ConcurrentHashMap<String, String>()
    private val plurals = ConcurrentHashMap<String, Map<String, String>>()

    init {
        load("values")
        if (lang != "en") load("values-$lang")
    }

    private fun load(dir: String) {
        for (file in FILES) {
            val stream = XmlStrings::class.java.getResourceAsStream("/res/$dir/$file") ?: continue
            val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val name = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> strings[name] = unescape(e.textContent)
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        plurals[name] = (0 until items.length).associate { k ->
                            val it = items.item(k) as Element
                            it.getAttribute("quantity") to unescape(it.textContent)
                        }
                    }
                }
            }
        }
    }

    override fun get(key: String): String? = strings[key]

    override fun plural(key: String, count: Int): String? {
        val p = plurals[key] ?: return null
        return (if (count == 1) p["one"] else null) ?: p["other"] ?: p.values.firstOrNull()
    }

    companion object {
        /** The app's strings files (app/src/main/res/values*): a new one must be added here too (StringResourcesTest checks it). */
        val FILES = listOf("strings.xml", "strings_settings.xml", "strings_tools.xml", "strings_visual.xml", "strings_features.xml")

        /** Android resource escapes: \' \" \n \t \@ \? and quotes that preserve spaces. */
        fun unescape(raw: String): String {
            var s = raw
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
            else s = s.replace(Regex("\\s+"), " ").trim()
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> if (i + 5 < s.length) { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 } else out.append(n)
                        else -> out.append(n)
                    }
                    i += 2
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }
    }
}
