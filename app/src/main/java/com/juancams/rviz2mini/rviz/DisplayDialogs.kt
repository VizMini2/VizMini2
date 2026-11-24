// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz

import android.content.Context
import android.graphics.Color
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.juancams.rviz2mini.R
import com.juancams.rviz2mini.rviz.display.StatusLevel

fun statusColor(level: StatusLevel): Int = when (level) {
    StatusLevel.OK -> Color.rgb(0x43, 0xA0, 0x47)
    StatusLevel.WARN -> Color.rgb(0xFB, 0x8C, 0x00)
    StatusLevel.ERROR -> Color.rgb(0xE5, 0x39, 0x35)
    StatusLevel.DISABLED -> Color.GRAY
}

/** Last opened dialog (instrumented tests use it to close it without injecting BACK). */
internal object OpenDialogs {
    var last: android.app.Dialog? = null
    /** For Compose dialogs (they are not an android.app.Dialog): how to close the last one. */
    var dismissCompose: (() -> Unit)? = null
    fun dismissLast() { last?.dismiss(); last = null; dismissCompose?.invoke(); dismissCompose = null }
}

// ====================================================================== fixed frame

object FixedFrameDialog {
    /**
     * `null` = automatic (map, odom... or the tree root). Besides the frames in TF, one can be written by hand (to leave
     * a configuration ready: it is used as soon as it appears), so the current one is listed even if it is not in TF.
     */
    fun show(ctx: Context, frames: List<String>, current: String?, autoLabel: String, onPick: (String?) -> Unit) {
        val all = if (current != null && current !in frames) listOf(current) + frames else frames
        val items = listOf(ctx.getString(R.string.rviz_fixed_frame_automatic, autoLabel)) + all
        val checked = if (current == null) 0 else (all.indexOf(current) + 1).coerceAtLeast(0)
        OpenDialogs.last = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.rviz_fixed_frame)
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, i ->
                onPick(if (i == 0) null else all[i - 1])
                d.dismiss()
            }
            .setNeutralButton(R.string.rviz_fixed_frame_write) { _, _ -> write(ctx, current, onPick) }
            .setNegativeButton(R.string.rviz_action_cancel, null)
            .show()
    }

    private fun write(ctx: Context, current: String?, onPick: (String?) -> Unit) {
        val input = android.widget.EditText(ctx).apply {
            setText(current.orEmpty()); setSelection(text.length)
            hint = ctx.getString(R.string.rviz_fixed_frame_write_hint)
            isSingleLine = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(ctx).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        OpenDialogs.last = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.rviz_fixed_frame)
            .setMessage(R.string.rviz_fixed_frame_write_hint)
            .setView(box)
            .setPositiveButton(R.string.rviz_action_ok) { _, _ ->
                onPick(input.text.toString().trim().trimStart('/').ifEmpty { null })
            }
            .setNegativeButton(R.string.rviz_action_cancel, null)
            .show()
    }
}
