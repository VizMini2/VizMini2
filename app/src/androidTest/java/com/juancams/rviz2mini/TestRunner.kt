// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runner for the instrumented tests: sets the app to English while the tests run, because the UI tests
 * look for the English texts and on a phone in Spanish they all failed. At the end it restores the app's previous language.
 *
 * `-e lang es` (or `en`) picks another language and `-e lang system` leaves the current one. Tests that set the language
 * themselves (CatalogClips) change it afterwards in their @Before.
 */
class TestRunner : AndroidJUnitRunner() {

    private var previous: LocaleListCompat? = null
    private var lang = "en"

    override fun onCreate(arguments: Bundle?) {
        lang = arguments?.getString("lang")?.trim().orEmpty().ifEmpty { "en" }
        super.onCreate(arguments)
    }

    override fun onStart() {
        if (lang != "system") runOnMainSync {
            previous = AppCompatDelegate.getApplicationLocales()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
            // Before Android 13 AppCompat only changes the activities' language: tests that read texts with
            // targetContext.getString (e.g. to find a view by its description) would get them in the
            // system language. The application's resources are changed too.
            val res = targetContext.applicationContext.resources
            val conf = android.content.res.Configuration(res.configuration)
            conf.setLocales(android.os.LocaleList.forLanguageTags(lang))
            @Suppress("DEPRECATION") res.updateConfiguration(conf, res.displayMetrics)
        }
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        previous?.let { runOnMainSync { AppCompatDelegate.setApplicationLocales(it) } }
        super.finish(resultCode, results)
    }
}
