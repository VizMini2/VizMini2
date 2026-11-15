// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.content.res.ColorStateList
import android.widget.TextView
import com.juancams.rviz2mini.ros.RosConnection

/** Connection status text and color, the same on every screen. */
object ConnectionUi {

    fun label(ctx: Context, s: RosConnection.Status): Pair<String, Int> = when (s) {
        is RosConnection.Status.Connected -> ctx.getString(R.string.common_status_connected) to ctx.getColor(R.color.status_ok)
        RosConnection.Status.Connecting -> ctx.getString(R.string.common_status_connecting) to ctx.getColor(R.color.status_warn)
        RosConnection.Status.Disconnected -> ctx.getString(R.string.common_status_disconnected) to ctx.getColor(R.color.status_idle)
        is RosConnection.Status.Error -> ctx.getString(R.string.common_status_error) to ctx.getColor(R.color.status_error)
    }

    /** Pill with the text in the given color over a soft background of the same color. */
    fun stylePill(view: TextView, text: String, color: Int) {
        view.text = text
        view.setTextColor(color)
        view.backgroundTintList = ColorStateList.valueOf((color and 0x00FFFFFF) or 0x2E000000)
    }
}
