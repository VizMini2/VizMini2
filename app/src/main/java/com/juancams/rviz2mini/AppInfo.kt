// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.view.View
import android.widget.TextView

/** App data shown in the UI (version...). */
object AppInfo {

    /** "0.9.0" (Gradle versionName). */
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** "v0.9.0" for the Home corner. */
    fun versionLabel(context: Context): String = context.getString(R.string.common_version_label, versionName(context))

    /**
     * "`prefix` · by Juancams" in `view`, with "Juancams" as a link to his LinkedIn (config.xml). The
     * rest of the text is not clickable.
     */
    fun showCredit(view: TextView, prefix: String) {
        val ctx = view.context
        val author = ctx.getString(R.string.author_name)
        val text = android.text.SpannableStringBuilder(prefix).append("  ·  by ")
        val start = text.length
        text.append(author)
        text.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ctx.getString(R.string.author_url))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            override fun updateDrawState(ds: android.text.TextPaint) {
                ds.color = com.google.android.material.color.MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
                ds.isFakeBoldText = true
                ds.isUnderlineText = false
            }
        }, start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = text
        view.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        view.highlightColor = android.graphics.Color.TRANSPARENT
    }
}
