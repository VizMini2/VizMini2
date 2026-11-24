// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plot

/**
 * What all the panels of a tab see at once: the time window (ends at `endNs` and
 * lasts `windowNs`), whether it follows the latest data (`follow`), the cursor (null = no cursor) and the second
 * cursor for measuring (`cursorBNs`): time difference and difference of each series between the two.
 */
class PlotViewport(var windowNs: Long, var endNs: Long = 0, var follow: Boolean = true, var cursorNs: Long? = null,
                   var cursorBNs: Long? = null) {
    /** Reference time for the labels: 0 when live (relative to the end) or the start of the bag. */
    var originNs: Long? = null
    val startNs: Long get() = endNs - windowNs
}
