// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.tabs

import android.view.KeyEvent
import android.view.MotionEvent

/** Tabs that accept a physical gamepad (Bluetooth/USB). MainActivity forwards the events to the visible one. */
interface GamepadInput {
    fun onGamepadMotion(event: MotionEvent): Boolean
    fun onGamepadKey(event: KeyEvent): Boolean
}
