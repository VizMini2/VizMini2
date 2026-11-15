// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed

/**
 * Right click (pressing and releasing the secondary button without dragging): for the PC context menus. It consumes
 * nothing, so a right-button drag (e.g. panning the 3D view) keeps working. On the phone there is no
 * secondary button and it does nothing.
 */
fun Modifier.onContextClick(key: Any? = Unit, onClick: (Offset) -> Unit): Modifier = pointerInput(key) {
    awaitPointerEventScope {
        var down: Offset? = null
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Final)
            val ch = e.changes.firstOrNull() ?: continue
            when (e.type) {
                PointerEventType.Press -> down = if (e.buttons.isSecondaryPressed) ch.position else null
                PointerEventType.Move -> if (down != null && (ch.position - down!!).getDistance() > 8f) down = null
                PointerEventType.Release -> { down?.let { onClick(it) }; down = null }
                else -> {}
            }
        }
    }
}
