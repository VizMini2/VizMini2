// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

/**
 * Freeze everything shown live at once (the dashboard's pause button): the 3D view stops applying new
 * messages, and the plots, camera and diagnostics stay still. Messages keep arriving; on resume the
 * latest is shown. With a bag playing, the pause is the bag's (see the playback bar).
 */
object LiveView {
    @Volatile var frozen = false
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun setFrozen(f: Boolean) { if (f != frozen) { frozen = f; listeners.forEach { it(f) } } }
    fun addListener(l: (Boolean) -> Unit) { listeners += l }
    fun removeListener(l: (Boolean) -> Unit) { listeners -= l }
}
