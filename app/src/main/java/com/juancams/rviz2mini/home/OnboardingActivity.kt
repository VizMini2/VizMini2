// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.juancams.rviz2mini.R
import com.juancams.rviz2mini.ui.Screens

/**
 * Quick tour of the app: a few pages with the basics. Shown the first time and can be repeated
 * from Home. The last page offers to start demo mode (result [RESULT_START_DEMO]).
 */
class OnboardingActivity : AppCompatActivity() {

    private class Page(@androidx.annotation.StringRes val title: Int, @androidx.annotation.StringRes val text: Int, val image: Int, val isPhoto: Boolean = true)

    private val pages = listOf(
        Page(R.string.onboarding_welcome_title, R.string.onboarding_welcome_text, R.drawable.logo_vizmini2, isPhoto = false),
        Page(R.string.onboarding_connect_title, R.string.onboarding_connect_text, R.drawable.shot_config),
        Page(R.string.onboarding_workspace_title, R.string.onboarding_workspace_text, R.drawable.shot_dashboard),
        Page(R.string.onboarding_drive_title, R.string.onboarding_drive_text, R.drawable.shot_teleop),
        Page(R.string.onboarding_inspect_title, R.string.onboarding_inspect_text, R.drawable.shot_node_graph),
        Page(R.string.onboarding_record_title, R.string.onboarding_record_text, R.drawable.shot_bags),
        Page(R.string.onboarding_profile_title, R.string.onboarding_profile_text, R.drawable.shot_profiles),
        Page(R.string.onboarding_demo_title, R.string.onboarding_demo_text, R.drawable.shot_rviz),
    )

    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var next: MaterialButton
    private lateinit var skip: MaterialButton
    private lateinit var demo: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_VizMini2_Auth)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_auth)
        }
        skip = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(R.string.onboarding_skip)
            setTextColor(getColor(R.color.auth_text_secondary))
            setOnClickListener { finishTour(false) }
        }
        root.addView(FrameLayout(this).apply { addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.END)) })
        pager = ViewPager2(this).apply { adapter = Adapter() }
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(dots)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), dp(20)) }
        demo = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.onboarding_start_demo)
            setTextColor(getColor(android.R.color.white))
            visibility = View.GONE
            setOnClickListener { finishTour(true) }
        }
        next = MaterialButton(this).apply {
            setText(R.string.onboarding_next)
            setOnClickListener { if (pager.currentItem < pages.size - 1) pager.currentItem++ else finishTour(false) }
        }
        buttons.addView(demo, LinearLayout.LayoutParams(-1, dp(52)))
        buttons.addView(next, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        root.addView(buttons)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = update(position)
        })
        update(0)
    }

    private fun update(position: Int) {
        val last = position == pages.size - 1
        next.setText(if (last) R.string.onboarding_get_started else R.string.onboarding_next)
        demo.visibility = if (last) View.VISIBLE else View.GONE
        skip.visibility = if (last) View.INVISIBLE else View.VISIBLE
        dots.removeAllViews()
        for (i in pages.indices) dots.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_pill)
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (i == position) MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary) else 0x55FFFFFF)
        }, LinearLayout.LayoutParams(if (i == position) dp(22) else dp(8), dp(8)).apply { setMargins(dp(3), 0, dp(3), 0) })
    }

    private fun finishTour(startDemo: Boolean) {
        markSeen(this)
        setResult(if (startDemo) RESULT_START_DEMO else Activity.RESULT_OK)
        finish()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pages.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(8), dp(28), 0)
                layoutParams = ViewGroup.LayoutParams(-1, -1)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val p = pages[position]
            val v = holder.itemView as LinearLayout
            v.removeAllViews()
            // Screenshot in a rounded "phone", or the logo (its own rounded tile) on the first page.
            val frame = FrameLayout(this@OnboardingActivity).apply {
                if (p.isPhoto) {
                    setBackgroundResource(R.drawable.bg_logo_tile)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }
            }
            frame.addView(ImageView(this@OnboardingActivity).apply {
                setImageResource(p.image)
                scaleType = if (p.isPhoto) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                if (p.isPhoto) {
                    clipToOutline = true
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    setBackgroundResource(R.drawable.bg_thumb)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            v.addView(frame, LinearLayout.LayoutParams(dp(230), dp(if (p.isPhoto) 300 else 230)).apply { topMargin = dp(12) })
            v.addView(TextView(this@OnboardingActivity).apply {
                setText(p.title)
                textSize = 24f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(android.R.color.white))
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            })
            v.addView(TextView(this@OnboardingActivity).apply {
                setText(p.text)
                textSize = 15f
                setLineSpacing(0f, 1.2f)
                setTextColor(getColor(R.color.auth_text_secondary))
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
        }
    }

    private fun dp(v: Int) = Screens.dp(this, v)

    companion object {
        const val RESULT_START_DEMO = Activity.RESULT_FIRST_USER + 1
        private const val PREFS = "rviz2mini_app"
        private const val KEY_SEEN = "onboarding_seen"

        fun seen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)
        fun markSeen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
    }
}
