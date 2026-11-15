// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bt

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.ui.screens.BehaviorTreeController
import com.juancams.rviz2mini.ui.screens.BehaviorTreeView
import com.juancams.rviz2mini.ui.screens.BtKeys
import com.juancams.rviz2mini.ui.theme.VizTheme

/**
 * "Behavior tree" tab: the screen shared with the desktop (ui/BehaviorTreeScreen). The fragment
 * owns the controller (it refreshes while visible) and provides the Android file picker for the Nav2 XML.
 */
class BehaviorTreeFragment : TabFragment() {

    private var controller: BehaviorTreeController? = null
    private var xmlDone: ((String?) -> Unit)? = null

    private val openXml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = xmlDone ?: return@registerForActivityResult
        xmlDone = null
        done(uri?.let { u -> try { requireContext().contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { null } })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val c = BehaviorTreeController(tabId) { done ->
            xmlDone = done
            openXml.launch(arrayOf("text/xml", "application/xml", "text/plain", "application/octet-stream"))
        }
        controller = c
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { VizTheme { BehaviorTreeView(c) } }
        }
    }

    override fun onResume() { super.onResume(); controller?.resume() }
    override fun onPause() { super.onPause(); controller?.pause() }

    override fun onDestroyView() {
        super.onDestroyView()
        controller?.close(); controller = null
    }

    // ---------------------------------------------------------------- tests (on the main thread)

    private val c: BehaviorTreeController get() = controller!!
    internal fun snapshotForTest(): BtSource.Snapshot? = c.source?.snapshot()
    internal fun transitionsForTest(): List<BtTransition> = c.source?.transitions?.toList().orEmpty()
    internal fun statusForTest(): String { c.refresh(); return c.statusText }
    internal fun detailsForTest(): String = c.detailsText()
    internal fun setBoardForTest(on: Boolean) = c.setBoard(on)
    internal fun toggleBreakpointForTest(uid: Int) = c.toggleBreakpoint(uid)
    internal fun resumeForTest() = c.resumeBreakpoint()
    internal fun pausedAtForTest(): Int? = (c.source as? Groot2Source)?.pausedAt
    internal fun setNav2ForTest(t: String, xml: String?, humble: Boolean) = c.setNav2(t, xml, humble)
    internal fun tapNodeForTest(uid: Int) { c.selected = uid }
    internal fun setSourceForTest(kind: String, h: String?, p: Int?, t: String?) = c.setSource(kind, h, p, t)

    companion object {
        const val KEY_SOURCE = BtKeys.SOURCE
        const val KEY_HOST = BtKeys.HOST
        const val KEY_PORT = BtKeys.PORT
        const val KEY_TOPIC = BtKeys.TOPIC
        const val KEY_LOG = BtKeys.LOG
        const val KEY_BOARD = BtKeys.BOARD
        const val KEY_XML = BtKeys.XML
        const val KEY_HUMBLE = BtKeys.HUMBLE
        const val SOURCE_GROOT2 = BtKeys.SOURCE_GROOT2
        const val SOURCE_NAV2 = BtKeys.SOURCE_NAV2
        const val DEFAULT_TOPIC = BtKeys.DEFAULT_TOPIC
        fun parseAddress(text: String): Pair<String, Int>? = BtKeys.parseAddress(text)
    }
}
