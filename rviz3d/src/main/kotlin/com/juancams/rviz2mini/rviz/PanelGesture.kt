// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.rviz.display.CameraDisplay
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.geom.OverlayLayout

/**
 * Moving and resizing the Camera display panels drawn inside the 3D view (the same on the phone and on the PC):
 * press on a panel and drag to move it; drag its bottom-right corner (or pinch, on the phone) to resize it. The
 * position and width are saved in the display configuration when the gesture ends. Pixels of the 3D view, main
 * thread.
 */
class PanelGesture(private val scene: RvizScene, private val pxPerDp: Float = 1f) {
    private class Grab(val id: String, val start: IntArray, val config: DisplayConfig, val resize: Boolean, val sx: Float, val sy: Float)
    private var grab: Grab? = null
    private var last: DisplayConfig? = null

    val active: Boolean get() = grab != null

    /** The Camera panel under (x, y), topmost first, with its rectangle. */
    fun panelAt(x: Float, y: Float): Pair<String, IntArray>? = scene.configs.asReversed().firstNotNullOfOrNull { c ->
        val r = (scene.displays[c.id] as? CameraDisplay)?.panelRect ?: return@firstNotNullOfOrNull null
        if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) c.id to r else null
    }

    /** Starts a gesture if (x, y) is on a panel; returns whether it did (if not, the touch belongs to the camera). */
    fun down(x: Float, y: Float): Boolean {
        val (id, r) = panelAt(x, y) ?: return false
        val c = scene.configs.firstOrNull { it.id == id } ?: return false
        val corner = CORNER_DP * pxPerDp
        val resize = x > r[0] + r[2] - corner && y > r[1] + r[3] - corner
        grab = Grab(id, r, c, resize, x, y)
        return true
    }

    fun move(x: Float, y: Float) {
        val g = grab ?: return
        val w = scene.width; val h = scene.height
        val c = if (g.resize) g.config.with("size", OverlayLayout.resized(g.config.number("size"), x - g.sx, w).toString()).withFree(g.start, w, h)
            else OverlayLayout.moved(g.start, x - g.sx, y - g.sy, w, h).let { (fx, fy) -> g.config.with("x", fx.toString()).with("y", fy.toString()) }
        last = c
        scene.updateDisplay(c, persist = false)
    }

    /** Two-finger pinch on the panel (phone): `scale` relative to when the gesture started. */
    fun pinch(scale: Float) {
        val g = grab ?: return
        val c = g.config.with("size", OverlayLayout.resized(g.config.number("size"), 0f, scene.width, scale).toString()).withFree(g.start, scene.width, scene.height)
        last = c
        scene.updateDisplay(c, persist = false)
    }

    fun up() {
        val c = last
        grab = null; last = null
        if (c != null) scene.updateDisplay(c)
    }

    fun cancel() {
        val g = grab ?: return
        grab = null; last = null
        scene.updateDisplay(g.config)
    }

    /** Resizing keeps the top-left corner where it is (a panel still in its corner gets a free position). */
    private fun DisplayConfig.withFree(r: IntArray, w: Int, h: Int): DisplayConfig =
        if (OverlayLayout.isFree(number("x"), number("y"))) this
        else with("x", (r[0].toDouble() / w.coerceAtLeast(1)).toString()).with("y", (r[1].toDouble() / h.coerceAtLeast(1)).toString())

    companion object {
        /** Size of the resize corner of a panel. */
        const val CORNER_DP = 28f
    }
}
