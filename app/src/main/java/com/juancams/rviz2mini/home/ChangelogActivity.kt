// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.home

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.juancams.rviz2mini.AppInfo
import com.juancams.rviz2mini.R

/** Full changelog: its own screen, exited with the arrow or with "back". */
class ChangelogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.changelog_title)
            subtitle = getString(R.string.changelog_subtitle, AppInfo.versionLabel(this@ChangelogActivity))
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.common_back)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        root.addView(toolbar)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { clipToPadding = false; addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = bars.top)
            scroll.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        val releases = Changelog.load(this)
        if (releases.isEmpty()) {
            list.addView(TextView(this).apply { setText(R.string.changelog_unavailable); setPadding(0, dp(24), 0, 0) })
        }
        for ((i, r) in releases.withIndex()) list.addView(releaseCard(r, latest = i == 0))
    }

    private fun releaseCard(r: Changelog.Release, latest: Boolean): View {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)) }
        card.addView(body)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = r.version
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (latest) header.addView(pill(getString(R.string.changelog_latest), MaterialColors.getColor(header, androidx.appcompat.R.attr.colorPrimary)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(10)
        })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(TextView(this).apply {
            text = r.date
            textSize = 13f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        body.addView(header)

        for (s in r.sections) {
            body.addView(pill(s.title, sectionColor(s.title)).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(14)
            })
            for (item in s.items) {
                body.addView(TextView(this).apply {
                    text = SpannableStringBuilder("•  ").append(inlineCode(item))
                    textSize = 14.5f
                    setLineSpacing(0f, 1.15f)
                    setPadding(dp(2), dp(6), 0, 0)
                })
            }
        }
        return card
    }

    private fun pill(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color)
        setBackgroundResource(R.drawable.bg_pill)
        backgroundTintList = android.content.res.ColorStateList.valueOf((color and 0x00FFFFFF) or 0x26000000)
        setPadding(dp(10), dp(3), dp(10), dp(3))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun sectionColor(title: String): Int = when (title.lowercase()) {
        "added" -> getColor(R.color.status_ok)
        "fixed" -> getColor(R.color.status_warn)
        "removed", "security" -> getColor(R.color.status_error)
        else -> MaterialColors.getColor(window.decorView, androidx.appcompat.R.attr.colorPrimary)
    }

    /** `code` in monospace with a soft background, as in Markdown. */
    private fun inlineCode(text: String): CharSequence {
        val out = SpannableStringBuilder()
        val parts = text.split('`')
        val codeBg = (MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorOnSurface) and 0x00FFFFFF) or 0x1A000000
        for ((i, p) in parts.withIndex()) {
            val start = out.length
            if (i % 2 == 1) {
                out.append(p)
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(codeBg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                // **bold** outside the code.
                for ((j, b) in p.split("**").withIndex()) {
                    val s = out.length
                    out.append(b)
                    if (j % 2 == 1) out.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return out
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
