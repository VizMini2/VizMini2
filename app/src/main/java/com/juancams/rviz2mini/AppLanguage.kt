// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * App language (independent of the system's): English, Spanish or the system one. AppCompat
 * stores it by itself and recreates the activities when it changes; on Android 13+ it also appears in the settings.
 */
object AppLanguage {

    /** Supported BCP 47 tags ("" = the system one). Must match res/xml/locales_config.xml. */
    val TAGS = listOf("", "en", "es")

    fun current(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').substringBefore(',')

    fun label(context: Context, tag: String): String = when (tag) {
        "en" -> "English"
        "es" -> "Español"
        else -> context.getString(R.string.settings_language_system)
    }

    fun currentLabel(context: Context) = label(context, current())

    fun set(tag: String) = AppCompatDelegate.setApplicationLocales(
        if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))

    /** Dialog to choose the language. */
    fun pick(context: Context) {
        val selected = TAGS.indexOf(current()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(TAGS.map { label(context, it) }.toTypedArray(), selected) { d, which ->
                d.dismiss()
                if (which != selected) set(TAGS[which])
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
