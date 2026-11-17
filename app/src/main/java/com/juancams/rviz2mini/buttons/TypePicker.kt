// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.buttons

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.juancams.rviz2mini.R
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry

/** List of the app's interfaces ("msg", "srv" or "action") with a search box, to pick a type. */
object TypePicker {

    /** `onDefineCustom` adds "Define your own" at the bottom (null: without that option). */
    fun show(ctx: Context, registry: InterfaceRegistry, kind: String, onPicked: (String) -> Unit, onDefineCustom: (() -> Unit)? = null) {
        val all = registry.names(kind)
        val dp = ctx.resources.displayMetrics.density
        val filter = EditText(ctx).apply { hint = ctx.getString(R.string.buttons_editor_search_types_hint); setSingleLine() }
        val list = ListView(ctx)
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, ArrayList(all))
        list.adapter = adapter
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), 0)
            addView(filter)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (320 * dp).toInt()))
        }
        val custom = onDefineCustom?.let {
            Button(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply { setText(R.string.buttons_editor_define_custom) }.also { box.addView(it) }
        }
        val dialog = MaterialAlertDialogBuilder(ctx).setTitle(R.string.buttons_editor_type_title).setView(box).setNegativeButton(R.string.buttons_cancel, null).show()
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.clear(); adapter.addAll(all.filter { it.contains(s.toString().trim(), true) })
            }
        })
        list.setOnItemClickListener { _, _, pos, _ -> adapter.getItem(pos)?.let { onPicked(it) }; dialog.dismiss() }
        custom?.setOnClickListener { dialog.dismiss(); onDefineCustom() }
    }
}
