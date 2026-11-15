// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.tabs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.juancams.rviz2mini.ui.Screens

/**
 * Tab catalog (the "+" button): each type with its short description and a screenshot. Tapping
 * one opens its detail page ([AddTabDetailActivity]); the created tab is returned to MainActivity.
 */
class AddTabActivity : AppCompatActivity() {

    private val detail = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            setResult(Activity.RESULT_OK, r.data)
            finish()
        }
    }

    /** Import a shared tab (a .json from "Share") or another tool's file (RViz, Foxglove layout, PlotJuggler layout). */
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = try { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { null }
        val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val r = text?.let { runCatching { com.juancams.rviz2mini.ui.interop.ConfigInterop.import(it, name) }.getOrNull() }
        if (r == null) {
            android.widget.Toast.makeText(this, com.juancams.rviz2mini.R.string.add_tab_import_bad, android.widget.Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val done = {
            val copies = TabSharing.copies(r.tabs, TabStore.get().all)
            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_TAB, TabList.toJson(copies)))
            finish()
        }
        if (r.notes.isEmpty()) done()
        else com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(com.juancams.rviz2mini.i18n.tr("interop_imported_title", r.format.label))
            .setMessage(com.juancams.rviz2mini.i18n.tr("interop_notes") + "\n\n" + r.notes.distinct().joinToString("\n") { "• $it" })
            .setPositiveButton(com.juancams.rviz2mini.i18n.tr("interop_add")) { _, _ -> done() }
            .setNegativeButton(com.juancams.rviz2mini.R.string.common_cancel, null).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = Screens.setup(this, getString(com.juancams.rviz2mini.R.string.common_new_tab), getString(com.juancams.rviz2mini.R.string.add_tab_subtitle)).content
        // For a dashboard widget, another dashboard is not offered.
        val forWidget = intent.getBooleanExtra(EXTRA_WIDGET, false)
        if (forWidget) supportActionBarSubtitle()
        else c.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(com.juancams.rviz2mini.R.string.add_tab_import)
            setIconResource(com.juancams.rviz2mini.R.drawable.ic_share)
            setOnClickListener { importFile.launch(arrayOf("application/json", "text/plain", "text/xml", "application/xml", "application/x-yaml", "application/octet-stream")) }
        })
        val allowed = TabCatalog.entries.filter { !(forWidget && it.type == TabType.DASHBOARD) }
        // Ready-made dashboards (the same templates as on PC): the dashboard with its widgets in one go.
        if (!forWidget && allowed.any { it.type == TabType.DASHBOARD }) {
            c.addView(TextView(this).apply {
                setText(com.juancams.rviz2mini.R.string.template_group)
                setTextAppearance(com.juancams.rviz2mini.R.style.TextAppearance_VizMini2_CardTitle)
                setPadding(Screens.dp(this@AddTabActivity, 4), Screens.dp(this@AddTabActivity, 18), 0, 0)
            })
            c.addView(Screens.hint(this, getString(com.juancams.rviz2mini.R.string.template_uses_topics)))
            for (t in com.juancams.rviz2mini.ui.screens.DashboardTemplates.Template.entries) c.addView(templateRow(t))
        }
        for (group in TabCatalog.Group.entries) {
            val items = allowed.filter { it.group == group }
            if (items.isEmpty()) continue
            c.addView(TextView(this).apply {
                setText(group.label)
                setTextAppearance(com.juancams.rviz2mini.R.style.TextAppearance_VizMini2_CardTitle)
                setPadding(Screens.dp(this@AddTabActivity, 4), Screens.dp(this@AddTabActivity, 18), 0, 0)
            })
            for (e in items) c.addView(row(e))
        }
        if (allowed.isEmpty()) c.addView(Screens.hint(this, getString(com.juancams.rviz2mini.R.string.add_tab_not_allowed)))
    }

    private fun supportActionBarSubtitle() {
        (window.decorView.findViewWithTag<android.view.View>("screen_toolbar") as? com.google.android.material.appbar.MaterialToolbar)
            ?.setTitle(com.juancams.rviz2mini.R.string.add_tab_widget_title)
    }

    private fun templateRow(t: com.juancams.rviz2mini.ui.screens.DashboardTemplates.Template): MaterialCardView {
        val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewFilledStyle).apply {
            radius = Screens.dp(this@AddTabActivity, 18).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Screens.dp(this@AddTabActivity, 10) }
            isClickable = true
            contentDescription = com.juancams.rviz2mini.i18n.tr(t.titleKey)
            setOnClickListener {
                val tabs = com.juancams.rviz2mini.ui.screens.DashboardTemplates.build(t)
                setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_TAB, TabList.toJson(tabs)))
                finish()
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Screens.dp(this@AddTabActivity, 16), Screens.dp(this@AddTabActivity, 14), Screens.dp(this@AddTabActivity, 16), Screens.dp(this@AddTabActivity, 14))
        }
        texts.addView(Screens.title(this, com.juancams.rviz2mini.i18n.tr(t.titleKey), com.juancams.rviz2mini.R.drawable.ic_dashboard))
        texts.addView(TextView(this).apply {
            text = com.juancams.rviz2mini.i18n.tr(t.summaryKey)
            textSize = 13f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, Screens.dp(this@AddTabActivity, 6), 0, 0)
        })
        card.addView(texts)
        return card
    }

    private fun row(e: TabCatalog.Entry): MaterialCardView {
        val card = MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewFilledStyle).apply {
            radius = Screens.dp(this@AddTabActivity, 18).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Screens.dp(this@AddTabActivity, 10) }
            isClickable = true
            contentDescription = e.type.label()
            setOnClickListener { detail.launch(Intent(this@AddTabActivity, AddTabDetailActivity::class.java).putExtra(AddTabDetailActivity.EXTRA_TYPE, e.type.name)) }
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Screens.dp(this@AddTabActivity, 16), Screens.dp(this@AddTabActivity, 14), Screens.dp(this@AddTabActivity, 10), Screens.dp(this@AddTabActivity, 14))
        }
        texts.addView(Screens.title(this, e.type.label(), e.icon))
        texts.addView(TextView(this).apply {
            setText(e.summary)
            textSize = 13f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, Screens.dp(this@AddTabActivity, 6), 0, 0)
        })
        body.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(ImageView(this).apply {
            setImageResource(e.image)
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            setBackgroundResource(com.juancams.rviz2mini.R.drawable.bg_thumb)
        }, LinearLayout.LayoutParams(Screens.dp(this, 92), Screens.dp(this, 112)).apply {
            setMargins(0, Screens.dp(this@AddTabActivity, 10), Screens.dp(this@AddTabActivity, 10), Screens.dp(this@AddTabActivity, 10))
        })
        card.addView(body)
        return card
    }

    companion object {
        /** JSON of the created tab (TabList.toJson of a single tab). */
        const val RESULT_TAB = "tab"
        /** true: a widget is being chosen for a dashboard. */
        const val EXTRA_WIDGET = "widget"
    }
}
