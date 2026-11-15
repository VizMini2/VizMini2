// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.common

import androidx.compose.ui.graphics.ImageBitmap

/**
 * ARGB pixels -> Compose image. Compose has no common way to do it, so each app sets its own
 * (Android: Bitmap; desktop: Skia) at startup.
 */
object UiImages {
    @Volatile var fromArgb: (pixels: IntArray, width: Int, height: Int) -> ImageBitmap = { _, w, h -> ImageBitmap(w, h) }
}
