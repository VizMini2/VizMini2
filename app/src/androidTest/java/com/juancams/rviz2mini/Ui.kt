// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Tap the interface like a user, with accessibility actions: HyperOS blocks the injection of
 * touches (Espresso/UiAutomator) but not these. Searches all windows (dialogs and sheets included).
 */
object Ui {

    private const val TAG = "UiTest"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).also { a ->
            a.serviceInfo = a.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
        }
    }

    /** Roots of the app's windows (top to bottom: the dialog first). */
    private fun roots(): List<AccessibilityNodeInfo> {
        val pkg = instrumentation.targetContext.packageName
        val ws = automation.windows.sortedByDescending { it.layer }.mapNotNull { it.root }
        return ws.filter { it.packageName == pkg }.ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
    }

    private fun all(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo> = ArrayList()): List<AccessibilityNodeInfo> {
        if (n == null) return out
        // Nodes are cached: Android 8 and 9 do not notice that a view goes from GONE to VISIBLE (the tour's
        // "Start demo mode" button, a sheet's "Create"), and in every version a Compose text that
        // changes without the structure changing (the state of a lifecycle node) keeps the old value. Each node is
        // requested again before reading it.
        n.refresh()
        out += n
        for (i in 0 until n.childCount) all(n.getChild(i), out)
        return out
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription ?: n.hintText)?.toString().orEmpty()

    fun find(text: String, exact: Boolean = true): AccessibilityNodeInfo? {
        for (r in roots()) {
            val hit = all(r).firstOrNull { n -> n.isVisibleToUser && label(n).let { if (exact) it == text else it.contains(text, ignoreCase = true) } }
            if (hit != null) return hit
        }
        return null
    }

    fun exists(text: String, exact: Boolean = false) = find(text, exact) != null

    /** All visible nodes with that text or description. */
    fun findAll(text: String, exact: Boolean = true): List<AccessibilityNodeInfo> =
        roots().flatMap { r -> all(r).filter { n -> n.isVisibleToUser && label(n).let { if (exact) it == text else it.contains(text, ignoreCase = true) } } }

    /** Waits for it to appear; if it is not in view, tries scrolling whatever can scroll. */
    fun waitFor(text: String, timeoutMs: Long = 10_000, exact: Boolean = false, scroll: Boolean = true): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        // If it is not in view: first up/left and then down/right.
        var scrolls = 0
        var backward = true
        while (SystemClock.uptimeMillis() < deadline) {
            find(text, exact)?.let { return it }
            if (scroll && scrolls < 30) {
                val moved = scrollStep(backward)
                scrolls++
                if (!moved && backward) backward = false
                if (!moved && !backward) SystemClock.sleep(250)
            } else SystemClock.sleep(250)
        }
        dump()
        throw AssertionError("No aparece \"$text\"")
    }

    /** Scrolls one step everything that scrolls (lists and horizontal strips). True if anything moved. */
    private fun scrollStep(backward: Boolean): Boolean {
        val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var moved = false
        for (r in roots()) for (sc in all(r).filter { it.isScrollable && it.isVisibleToUser }) {
            if (sc.performAction(action)) moved = true
        }
        if (moved) SystemClock.sleep(300)
        return moved
    }

    fun scrollToTop() {
        for (r in roots()) for (s in all(r).filter { it.isScrollable }) repeat(15) { s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
        SystemClock.sleep(300)
    }

    /** Taps the element with that text (or its nearest clickable container). */
    fun click(text: String, exact: Boolean = true, timeoutMs: Long = 10_000) {
        waitFor(text, timeoutMs, exact)
        // Among several matches (e.g. the "Home" title and the "Home" tab) the clickable one wins.
        val n = clickableFor(text, exact) { it.isClickable }
        // Some views (TabLayout tabs) do not announce themselves as clickable but do accept
        // CLICK or SELECT: try walking up the parents.
        val ok = n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true || anyAncestorAction(text, exact)
        check(ok) { dump(); "No se pudo pulsar \"$text\"" }
        Log.i(TAG, "click $text")
        SystemClock.sleep(600)
    }

    fun longClick(text: String, exact: Boolean = true) {
        waitFor(text, exact = exact)
        val n = clickableFor(text, exact) { it.isLongClickable }
        check(n != null && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) { "No se pudo mantener pulsado \"$text\"" }
        SystemClock.sleep(600)
    }

    private fun anyAncestorAction(text: String, exact: Boolean): Boolean {
        for (r in roots()) for (n in all(r)) {
            if (!n.isVisibleToUser) continue
            val l = label(n)
            if (if (exact) l != text else !l.contains(text, ignoreCase = true)) continue
            var p: AccessibilityNodeInfo? = n
            while (p != null) {
                if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK) || p.performAction(AccessibilityNodeInfo.ACTION_SELECT)) return true
                p = p.parent
            }
        }
        return false
    }

    /** The first element with that text that (itself or a parent) satisfies `ok`, already walked up to that parent. */
    private fun clickableFor(text: String, exact: Boolean, ok: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (r in roots()) for (n in all(r)) {
            if (!n.isVisibleToUser) continue
            val l = label(n)
            if (if (exact) l != text else !l.contains(text, ignoreCase = true)) continue
            var p: AccessibilityNodeInfo? = n
            while (p != null && !ok(p)) p = p.parent
            if (p != null) return p
        }
        return null
    }

    /** Types into the field whose text, hint or label matches. */
    /** Types into editable field number `index` (in screen order) of the top window: for forms without labels. */
    fun typeInto(index: Int, value: String, timeoutMs: Long = 10_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var edits: List<AccessibilityNodeInfo> = emptyList()
        while (SystemClock.uptimeMillis() < deadline) {
            edits = roots().firstOrNull()?.let { all(it).filter { n -> n.isEditable && n.isVisibleToUser } }.orEmpty()
            if (edits.size > index) break
            SystemClock.sleep(200)
        }
        val edit = edits.getOrNull(index) ?: throw AssertionError("No hay campo editable $index (hay ${edits.size})")
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { "No se pudo escribir en el campo $index" }
        SystemClock.sleep(200)
    }

    fun type(field: String, value: String) {
        val f = waitFor(field, exact = false)
        val edit = (listOf(f) + all(f)).firstOrNull { it.isEditable } ?: generateSequence(f) { it.parent }.flatMap { all(it).asSequence() }.first { it.isEditable }
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { "No se pudo escribir en \"$field\"" }
        SystemClock.sleep(300)
    }

    fun back() {
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        SystemClock.sleep(800)
    }

    fun shot(dir: File, name: String) {
        instrumentation.waitForIdleSync()
        val bmp = automation.takeScreenshot() ?: return
        File(dir.apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    /** What is on screen, to debug a failure. */
    fun dump() {
        for (r in roots()) Log.i(TAG, "VENTANA: " + all(r).map { label(it) }.filter { it.isNotBlank() }.joinToString(" | ").take(3000))
    }
}
