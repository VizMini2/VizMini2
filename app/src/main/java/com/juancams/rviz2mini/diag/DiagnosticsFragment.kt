// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.diag

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.ui.screens.DiagMode
import com.juancams.rviz2mini.ui.screens.DiagnosticsController
import com.juancams.rviz2mini.ui.screens.DiagnosticsScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/** Diagnostics tab: the screen shared with the desktop (ui/DiagnosticsScreen); log sharing, with Android. */
class DiagnosticsFragment : TabFragment() {

    enum class Mode { LOG, DIAG, ECHO, PLOT }

    @Volatile private var controller: DiagnosticsController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                VizTheme {
                    DiagnosticsScreen(tabId, onController = { controller = it }, onShare = { text ->
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "rosout"))
                    })
                }
            }
        }

    // Tab hidden: nothing is received (as before).
    override fun onResume() { super.onResume(); controller?.start() }
    override fun onPause() { super.onPause(); controller?.stop() }

    // ---------------------------------------------------------------- for the tests (on the main thread)

    internal fun setModeForTest(m: Mode) { controller?.updateMode(DiagMode.valueOf(m.name)) }
    internal fun setSourceForTest(t: String, ty: String, q: QosPreset) { controller?.let { it.updateQos(q); it.updateTopic(t); it.updateType(ty) } }
    internal fun setPlotPathsForTest(p: List<String>) { controller?.updatePlotPaths(p) }
    internal fun logCountForTest() = controller?.logBuffer?.size ?: 0
    internal fun diagSnapshotForTest() = controller?.diagModel?.snapshot(System.currentTimeMillis()).orEmpty()
    internal fun echoTextForTest() = controller?.echoText().orEmpty()
    internal fun plotPointsForTest() = controller?.series.orEmpty().map { it.points(System.currentTimeMillis()).second.size }
    internal fun statusForTest() = controller?.statusLine().orEmpty()
}
