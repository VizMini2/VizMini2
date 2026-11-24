// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.dashboard

import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Tab with its own items (buttons, sliders). Inside a dashboard it does not show its bar
 * with "Add": they are added with the "+" of the dashboard's Edit mode or by tapping the empty notice.
 */
interface WidgetItems {
    fun addItem()

    companion object {
        fun inDashboard(f: Fragment): Boolean = f.parentFragment is DashboardFragment

        /** Hides `bar` and replaces the empty notice with a short one that adds on tap. */
        fun setUp(f: Fragment, bar: View, empty: TextView, emptyText: Int, add: () -> Unit) {
            if (!inDashboard(f)) return
            bar.visibility = View.GONE
            empty.setText(emptyText)
            empty.setOnClickListener { add() }
        }
    }
}
