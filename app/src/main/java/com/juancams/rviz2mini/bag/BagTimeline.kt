// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.bag

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import com.juancams.rviz2mini.R
import java.util.Locale

/**
 * Common timeline (like Foxglove's): while a bag is played directly into the app, a
 * bar over the tabs with pause, position and stop. It moves everything visible at once: the 3D
 * view, the cameras, the plots... It sits below the content of [MainActivity].
 */
class BagTimeline(private val activity: AppCompatActivity) {

    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY))
        setPadding(dp(4), 0, dp(8), 0)
        visibility = View.GONE
        tag = "bag-timeline"
    }
    private val pause = button(R.drawable.ic_pause, R.string.bags_pause) { BagSession.player?.let { it.paused = !it.paused; refresh() } }
    private val seek = SeekBar(activity).apply { max = 1000 }
    private val time = TextView(activity).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
    private var seeking = false
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 250) } }

    init {
        view.addView(pause, LinearLayout.LayoutParams(dp(40), dp(40)))
        view.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(time)
        view.addView(button(R.drawable.ic_stop, R.string.bags_stop) { BagSession.stopPlayer(); refresh() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val pl = BagSession.player ?: return
                pl.seek(pl.reader.durationNs * p / 1000)
            }
            override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar) { seeking = false }
        })
    }

    fun start() = main.post(tick)
    fun stop() = main.removeCallbacks(tick)

    private fun refresh() {
        val p = BagSession.player?.takeIf { it.direct }
        view.visibility = if (p != null) View.VISIBLE else View.GONE
        if (p == null) return
        val d = p.reader.durationNs.coerceAtLeast(1)
        if (!seeking) seek.progress = (p.positionNs * 1000 / d).toInt().coerceIn(0, 1000)
        time.text = String.format(Locale.ROOT, "%s / %s", fmt(p.positionNs), fmt(d))
        pause.setImageResource(if (p.paused) R.drawable.ic_play else R.drawable.ic_pause)
        pause.contentDescription = activity.getString(if (p.paused) R.string.bags_resume else R.string.bags_pause)
    }

    private fun fmt(ns: Long): String { val s = ns / 1_000_000_000L; return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) }

    private fun button(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(icon)
        setBackgroundResource(android.R.color.transparent)
        imageTintList = ColorStateList.valueOf(MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, Color.CYAN))
        contentDescription = activity.getString(desc)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
