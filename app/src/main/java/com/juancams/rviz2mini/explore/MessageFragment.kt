// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.R
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.ui.screens.MESSAGE_KEY_PATH
import com.juancams.rviz2mini.ui.screens.MESSAGE_KEY_TOPIC
import com.juancams.rviz2mini.ui.screens.MESSAGE_KEY_TYPE
import com.juancams.rviz2mini.ui.screens.MessageController
import com.juancams.rviz2mini.ui.screens.MessageScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/** Message tab: the screen shared with the desktop (ui/MessageScreen). */
class MessageFragment : TabFragment() {

    @Volatile private var controller: MessageController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                VizTheme {
                    MessageScreen(tabId, onController = { controller = it },
                        onCopied = { Toast.makeText(context, R.string.message_copied, Toast.LENGTH_SHORT).show() })
                }
            }
        }

    // ---------------------------------------------------------------- for tests (on the main thread)

    internal fun treeTextForTest(): List<String> = controller?.lines.orEmpty().map { com.juancams.rviz2mini.explore.MessageTree.text(it) }
    internal fun setPathForTest(p: String) { controller?.path = p.trim(); controller?.tick() }
    internal fun expandForTest(p: String) { controller?.let { it.expanded = it.expanded + p; it.tick() } }
    internal fun setPausedForTest(p: Boolean) { controller?.paused = p }
    internal fun receivedForTest() = controller?.received ?: 0L

    companion object {
        const val KEY_TOPIC = MESSAGE_KEY_TOPIC
        const val KEY_TYPE = MESSAGE_KEY_TYPE
        const val KEY_PATH = MESSAGE_KEY_PATH
    }
}
