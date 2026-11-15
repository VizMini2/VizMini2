// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.home.OnboardingActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Welcome tour: goes through all pages, checks titles and buttons and that "Get started"
 * marks it as seen. With `-e shots true` it also takes the Config screenshots used by the tour.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "onboarding").apply { mkdirs() }
    private val appPrefs = context.getSharedPreferences("rviz2mini_app", Context.MODE_PRIVATE)
    private val savedSeen = appPrefs.getBoolean("onboarding_seen", false)

    @After
    fun tearDown() {
        appPrefs.edit().putBoolean("onboarding_seen", savedSeen).commit()
    }

    @Test
    fun walkThroughTheTour() {
        appPrefs.edit().putBoolean("onboarding_seen", false).commit()
        val ids = listOf(
            R.string.onboarding_welcome_title, R.string.onboarding_connect_title, R.string.onboarding_workspace_title,
            R.string.onboarding_drive_title, R.string.onboarding_inspect_title, R.string.onboarding_record_title,
            R.string.onboarding_profile_title, R.string.onboarding_demo_title,
        )
        val scenario = ActivityScenario.launchActivityForResult(OnboardingActivity::class.java)
        // Texts in the activity's language (may differ from the system's).
        var titles: List<String> = emptyList()
        scenario.onActivity { a -> titles = ids.map { a.getString(it) } }
        titles.forEachIndexed { i, title ->
            Ui.waitFor(title, exact = true, scroll = false)
            SystemClock.sleep(600)
            Ui.shot(dir, "page_$i")
            if (i < titles.size - 1) {
                assertTrue("Falta Skip en la página $i", Ui.exists("Skip", exact = true))
                assertFalse("Start demo mode solo en la última", Ui.exists("Start demo mode", exact = true))
                Ui.click("Next")
            }
        }
        Ui.waitFor("Start demo mode", exact = true, scroll = false) // appears when the page is selected: may take longer than the title
        Ui.click("Get started")
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        assertTrue(OnboardingActivity.seen(context))
    }

    @Test
    fun skipMarksItAsSeen() {
        appPrefs.edit().putBoolean("onboarding_seen", false).commit()
        val scenario = ActivityScenario.launchActivityForResult(OnboardingActivity::class.java)
        Ui.click("Skip")
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        assertTrue(OnboardingActivity.seen(context))
    }

    /** Config screenshots for the tour (not part of the suite). */
    @Test
    fun configShots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shots") == "true")
        ActivityScenario.launch(MainActivity::class.java).use {
            Ui.click("Config")
            SystemClock.sleep(1500)
            Ui.shot(dir, "config")
            Ui.waitFor("Robot profiles", exact = true)
            SystemClock.sleep(800)
            Ui.shot(dir, "profiles")
        }
    }
}
