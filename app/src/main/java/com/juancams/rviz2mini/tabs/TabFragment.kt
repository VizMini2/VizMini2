// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.tabs

import android.os.Bundle
import androidx.fragment.app.Fragment

/**
 * Base for user-created tabs. The tab id travels in the arguments so
 * Android can recreate the fragment by itself.
 *
 * When switching tabs the previous one is hidden and its lifecycle is capped at STARTED, so
 * it receives onPause(): there each tab "freezes" (stops rendering / subscribing) without
 * losing its state.
 */
abstract class TabFragment : Fragment() {

    protected val tabId: String get() = requireArguments().getString(ARG_TAB_ID)!!

    protected val tabStore: TabStore get() = TabStore.get()

    protected fun spec(): TabSpec? = tabStore.get(tabId)

    protected fun setting(key: String): String? = spec()?.settings?.get(key)

    protected fun saveSetting(key: String, value: String?) = tabStore.updateSetting(tabId, key, value)

    companion object {
        const val ARG_TAB_ID = "tab_id"

        fun <T : TabFragment> withTabId(fragment: T, id: String): T {
            fragment.arguments = Bundle().apply { putString(ARG_TAB_ID, id) }
            return fragment
        }
    }
}
