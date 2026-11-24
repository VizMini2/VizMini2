// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.juancams.rviz2mini.ui.theme.VizTheme

/**
 * Opens a Compose dialog (or sheet) from a views fragment: a zero-size ComposeView in its view, which
 * is removed on close. `content` receives the function to close it with.
 */
fun Fragment.showComposeDialog(content: @Composable (dismiss: () -> Unit) -> Unit) {
    val root = view as? ViewGroup ?: return
    val cv = ComposeView(requireContext())
    val dismiss: () -> Unit = { root.post { root.removeView(cv) } }
    com.juancams.rviz2mini.rviz.OpenDialogs.dismissCompose = dismiss
    cv.setContent { VizTheme { content(dismiss) } }
    root.addView(cv, ViewGroup.LayoutParams(0, 0))
}
