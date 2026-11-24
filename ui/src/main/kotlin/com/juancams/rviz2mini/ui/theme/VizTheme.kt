// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** The Android app colors (res/values{,-night}/themes.xml), so everything looks the same everywhere. */
private val Light = lightColorScheme(
    primary = Color(0xFF0E7C86), onPrimary = Color.White,
    primaryContainer = Color(0xFFA6EEF5), onPrimaryContainer = Color(0xFF002023),
    secondary = Color(0xFF4A6366), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8EB), onSecondaryContainer = Color(0xFF051F22),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5CD3DD), onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF004F56), onPrimaryContainer = Color(0xFFA6EEF5),
    secondary = Color(0xFFB1CBCE), onSecondary = Color(0xFF1C3437),
    secondaryContainer = Color(0xFF334B4E), onSecondaryContainer = Color(0xFFCCE8EB),
)

/** Status colors (res/values/colors.xml). */
object StatusColors {
    val ok = Color(0xFF43A047)
    val warn = Color(0xFFFB8C00)
    val error = Color(0xFFE53935)
    val idle = Color(0xFF9E9E9E)
}

@Composable
fun VizTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}
