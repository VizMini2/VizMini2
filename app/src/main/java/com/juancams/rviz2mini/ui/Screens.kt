// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.juancams.rviz2mini.ConnectionUi
import com.juancams.rviz2mini.R

/**
 * Common pieces of the secondary screens (node, topic, service, action inspector...): bar
 * with a back arrow, scrolling content and cards styled like Home.
 */
object Screens {

    class Screen(val toolbar: MaterialToolbar, val content: LinearLayout, val scroll: ScrollView)

    /** Builds the screen: bar on top and scrolling content that respects the system bars. */
    fun setup(activity: AppCompatActivity, title: String, subtitle: String? = null): Screen {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
        }
        val toolbar = MaterialToolbar(activity).apply {
            this.title = title
            this.subtitle = subtitle
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.common_back)
            setNavigationOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
            tag = "screen_toolbar"
        }
        root.addView(toolbar)
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 24)) }
        val scroll = ScrollView(activity).apply { clipToPadding = false; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        activity.setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            toolbar.updatePadding(top = bars.top)
            scroll.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        return Screen(toolbar, content, scroll)
    }

    /** Section card; returns the body to add views to. */
    fun card(parent: LinearLayout, topMargin: Int = 12): LinearLayout {
        val ctx = parent.context
        val card = MaterialCardView(ctx, null, com.google.android.material.R.attr.materialCardViewFilledStyle).apply {
            radius = dp(ctx, 20).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { this.topMargin = dp(ctx, topMargin) }
        }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14)) }
        card.addView(body)
        parent.addView(card)
        return body
    }

    fun title(ctx: Context, text: String, icon: Int) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_VizMini2_CardTitle)
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = dp(ctx, 10)
        gravity = Gravity.CENTER_VERTICAL
        compoundDrawableTintList = ColorStateList.valueOf(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
    }

    fun hint(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    fun mono(ctx: Context, text: String, size: Float = 13f) = TextView(ctx).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        textSize = size
        setTextIsSelectable(true)
    }

    fun pill(ctx: Context, text: String, color: Int) = TextView(ctx).apply {
        setBackgroundResource(R.drawable.bg_pill)
        setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        ConnectionUi.stylePill(this, text, color)
    }

    /** Rounded search field with a magnifier icon. */
    fun searchField(ctx: Context, hint: String, onChange: (String) -> Unit): TextInputLayout {
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedDenseStyle).apply {
            this.hint = hint
            setStartIconDrawable(R.drawable.ic_search)
            val r = dp(ctx, 14).toFloat()
            setBoxCornerRadii(r, r, r, r)
        }
        val edit = TextInputEditText(layout.context).apply { isSingleLine = true }
        layout.addView(edit)
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = onChange(s.toString().trim())
        })
        return layout
    }

    fun selectable(ctx: Context): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId)
    }

    fun visible(v: View, show: Boolean) { v.visibility = if (show) View.VISIBLE else View.GONE }

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
