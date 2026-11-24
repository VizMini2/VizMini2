// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.dashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Dashboard grid (no Android): 12 columns, square rows, infinite downwards. When moving
 * or resizing a widget, the ones it collides with move down and then everything moves up as far as it can (like
 * web dashboards), so there are never overlaps or gaps at the top.
 *
 * Floating widgets ([Item.floating]) are separate: they go wherever they are dropped, on top of the others, and neither push
 * nor get pushed (a joystick over the RViz, a small camera in a corner...).
 */
object DashboardGrid {

    const val COLUMNS = 12

    /**
     * A widget: position and size in cells, with decimals (it stays exactly where it is dropped). Old layouts,
     * in integers, are read the same way; and earlier versions of the app read these by rounding.
     */
    data class Item(val id: String, val x: Double, val y: Double, val w: Double, val h: Double, val floating: Boolean = false) {
        constructor(id: String, x: Int, y: Int, w: Int, h: Int, floating: Boolean = false) : this(id, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), floating)
        fun overlaps(o: Item) = id != o.id && x < o.x + o.w - EPS && o.x < x + w - EPS && y < o.y + o.h - EPS && o.y < y + h - EPS
    }

    private const val EPS = 1e-6

    /** Free placement: size between the minimum and the maximum and position ≥ 0, no limit to the right or bottom (unbounded board). */
    fun clampFree(i: Item): Item = i.copy(w = i.w.coerceIn(MIN_W.toDouble(), MAX_W.toDouble()), h = i.h.coerceIn(MIN_H.toDouble(), MAX_H.toDouble()),
        x = i.x.coerceAtLeast(0.0), y = i.y.coerceAtLeast(0.0))

    /** Columns the board takes up (at least the usual 12). */
    fun columns(items: List<Item>): Double = maxOf(COLUMNS.toDouble(), items.maxOfOrNull { it.x + it.w } ?: 0.0)

    /** Fits size and position to the grid. */
    fun clamp(i: Item): Item {
        val w = i.w.coerceIn(MIN_W.toDouble(), COLUMNS.toDouble())
        val h = i.h.coerceIn(MIN_H.toDouble(), MAX_H.toDouble())
        return i.copy(w = w, h = h, x = i.x.coerceIn(0.0, COLUMNS - w), y = i.y.coerceAtLeast(0.0))
    }

    /**
     * Free placement (the dashboard's): `moved` stays where it is dropped, on top of everything (at the end of the list,
     * which is the drawing order), and the others do not move.
     */
    fun moveFree(items: List<Item>, moved: Item): List<Item> = items.filter { it.id != moved.id } + clampFree(moved)

    /** Does `item` cover any one below it (earlier in the list)? Then it is drawn translucent. */
    fun coversAnother(items: List<Item>, item: Item): Boolean {
        val i = items.indexOfFirst { it.id == item.id }
        return i > 0 && items.subList(0, i).any { it.overlaps(item) }
    }

    /**
     * Places `moved` where requested (it takes precedence over the others); those it collides with move down and then everything
     * is compacted upwards. Returns the full list.
     */
    fun place(items: List<Item>, moved: Item): List<Item> {
        val fixed = clamp(moved)
        val floats = items.filter { it.floating && it.id != fixed.id }
        // A floating one stays where it is dropped; the grid ones are only compacted (it may have left a gap).
        if (fixed.floating) return compact(items.filter { it.id != fixed.id }) + fixed
        val others = items.filter { it.id != fixed.id && !it.floating }.sortedWith(compareBy({ it.y }, { it.x }))
        val placed = arrayListOf(fixed)
        for (o in others) {
            var c = o
            while (placed.any { it.overlaps(c) }) c = c.copy(y = c.y + 1)
            placed += c
        }
        return compact(placed, keep = fixed.id) + floats
    }

    /** Takes a widget out of the grid so it floats on top (in the same place) or puts it back. */
    fun setFloating(items: List<Item>, id: String, floating: Boolean): List<Item> {
        val item = items.firstOrNull { it.id == id } ?: return items
        return place(items, item.copy(floating = floating))
    }

    /** Moves each widget up as far as it can without overlapping (`keep` stays where it is if it already fits). Floating ones too. */
    fun compact(all: List<Item>, keep: String? = null): List<Item> {
        val floats = all.filter { it.floating }
        val items = all.filter { !it.floating }
        val out = ArrayList<Item>()
        val keepItem = items.firstOrNull { it.id == keep }
        if (keepItem != null) out += keepItem
        for (i in items.filter { it.id != keep }.sortedWith(compareBy({ it.y }, { it.x }))) {
            var c = i
            while (c.y > 0 && out.none { it.overlaps(c.copy(y = maxOf(0.0, c.y - 1))) }) c = c.copy(y = maxOf(0.0, c.y - 1))
            while (out.any { it.overlaps(c) }) c = c.copy(y = c.y + 1)
            out += c
        }
        // The one that moved also moves up if it was left floating.
        if (keepItem != null) {
            val rest = out.filter { it.id != keep }
            var c: Item = keepItem
            while (c.y > 0 && rest.none { it.overlaps(c.copy(y = maxOf(0.0, c.y - 1))) }) c = c.copy(y = maxOf(0.0, c.y - 1))
            return (rest + c).sortedWith(compareBy({ it.y }, { it.x })) + floats
        }
        return out.sortedWith(compareBy({ it.y }, { it.x })) + floats
    }

    /** First gap (top to bottom, left to right) where a w × h widget fits. */
    fun firstFree(all: List<Item>, w: Int, h: Int, id: String): Item {
        val items = all.filter { !it.floating }
        val ww = w.coerceIn(MIN_W, COLUMNS)
        var y = 0
        while (true) {
            for (x in 0..COLUMNS - ww) {
                val c = Item(id, x, y, ww, h)
                if (items.none { it.overlaps(c) }) return c
            }
            y++
        }
    }

    /** Initial size of each widget type (columns × rows of the 12-column grid). */
    fun defaultSize(type: com.juancams.rviz2mini.tabs.TabType): Pair<Int, Int> = when (type) {
        com.juancams.rviz2mini.tabs.TabType.RVIZ -> 12 to 9
        com.juancams.rviz2mini.tabs.TabType.CAMERA -> 6 to 5
        com.juancams.rviz2mini.tabs.TabType.TELEOP, com.juancams.rviz2mini.tabs.TabType.TELEOP_DUAL -> 6 to 7
        com.juancams.rviz2mini.tabs.TabType.BUTTONS -> 12 to 5
        com.juancams.rviz2mini.tabs.TabType.SLIDERS -> 12 to 7
        com.juancams.rviz2mini.tabs.TabType.INDICATORS -> 12 to 6
        com.juancams.rviz2mini.tabs.TabType.PLOT -> 12 to 8
        com.juancams.rviz2mini.tabs.TabType.GPS -> 12 to 8
        com.juancams.rviz2mini.tabs.TabType.BEHAVIOR_TREE -> 12 to 10
        com.juancams.rviz2mini.tabs.TabType.MESSAGE, com.juancams.rviz2mini.tabs.TabType.PARAMETERS -> 12 to 8
        com.juancams.rviz2mini.tabs.TabType.DIAGNOSTICS, com.juancams.rviz2mini.tabs.TabType.BAGS -> 12 to 7
        com.juancams.rviz2mini.tabs.TabType.LIFECYCLE -> 12 to 5
        com.juancams.rviz2mini.tabs.TabType.MOVEIT, com.juancams.rviz2mini.tabs.TabType.CONTROLLERS -> 6 to 10
        com.juancams.rviz2mini.tabs.TabType.MAPS -> 12 to 10
        com.juancams.rviz2mini.tabs.TabType.ALERTS -> 6 to 8
        com.juancams.rviz2mini.tabs.TabType.NODE_GRAPH, com.juancams.rviz2mini.tabs.TabType.TF_TREE -> 12 to 8
        else -> 6 to 7
    }

    /** Rows the board takes up. */
    fun rows(items: List<Item>): Double = items.maxOfOrNull { it.y + it.h } ?: 0.0

    fun toJson(items: List<Item>): String = JSONArray().apply {
        for (i in items) put(JSONObject().put("id", i.id).put("x", i.x).put("y", i.y).put("w", i.w).put("h", i.h).apply { if (i.floating) put("floating", true) })
    }.toString()

    fun fromJson(json: String?): List<Item> = try {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).map { k -> arr.getJSONObject(k).let { Item(it.getString("id"), it.optDouble("x", 0.0), it.optDouble("y", 0.0), it.optDouble("w", 6.0), it.optDouble("h", 6.0), it.optBoolean("floating")) } }
    } catch (_: Exception) {
        emptyList()
    }

    const val MIN_W = 3
    const val MIN_H = 2
    const val MAX_H = 30
    const val MAX_W = 48
}
