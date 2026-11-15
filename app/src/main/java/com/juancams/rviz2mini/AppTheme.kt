// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * App theme: light, dark or the system one (like on the PC). AppCompat recreates the activities when it changes; it is
 * stored in the "appearance" preferences and applied at startup.
 */
object AppTheme {
    val MODES = listOf("system", "light", "dark")

    private fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun current(context: Context): String = prefs(context).getString("theme", null) ?: "system"

    fun label(context: Context, mode: String) = context.getString(when (mode) {
        "light" -> R.string.settings_theme_light
        "dark" -> R.string.settings_theme_dark
        else -> R.string.settings_theme_system
    })

    /** The saved one, at app startup. */
    fun apply(context: Context) = AppCompatDelegate.setDefaultNightMode(nightMode(current(context)))

    private fun nightMode(mode: String) = when (mode) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun pick(context: Context) {
        val selected = MODES.indexOf(current(context)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(MODES.map { label(context, it) }.toTypedArray(), selected) { d, which ->
                d.dismiss()
                if (which != selected) {
                    prefs(context).edit().putString("theme", MODES[which]).apply()
                    AppCompatDelegate.setDefaultNightMode(nightMode(MODES[which]))
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
