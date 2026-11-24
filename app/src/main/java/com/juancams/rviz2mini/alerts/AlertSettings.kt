// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.alerts

import android.content.Context

/** Which alerts the user wants (SharedPreferences). */
data class AlertSettings(
    val enabled: Boolean = false,
    val connection: Boolean = true,
    val diagnostics: Boolean = true,
    val diagnosticWarnings: Boolean = false,
    val lifecycle: Boolean = true
) {
    companion object {
        private const val PREFS = "rviz2mini_alerts"

        fun load(context: Context): AlertSettings {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AlertSettings(
                p.getBoolean("enabled", false), p.getBoolean("connection", true), p.getBoolean("diagnostics", true),
                p.getBoolean("diagnosticWarnings", false), p.getBoolean("lifecycle", true)
            )
        }

        fun save(context: Context, s: AlertSettings) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", s.enabled).putBoolean("connection", s.connection).putBoolean("diagnostics", s.diagnostics)
                .putBoolean("diagnosticWarnings", s.diagnosticWarnings).putBoolean("lifecycle", s.lifecycle)
                .apply()
        }
    }
}
