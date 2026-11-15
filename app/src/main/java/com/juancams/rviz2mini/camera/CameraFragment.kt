// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.camera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.ui.screens.CAMERA_KEY_CUSTOM
import com.juancams.rviz2mini.ui.screens.CAMERA_KEY_KIND
import com.juancams.rviz2mini.ui.screens.CAMERA_KEY_TOPIC
import com.juancams.rviz2mini.ui.screens.CAMERA_KEY_TOPICS
import com.juancams.rviz2mini.ui.screens.CAMERA_MAX
import com.juancams.rviz2mini.ui.screens.CameraScreen
import com.juancams.rviz2mini.ui.screens.CameraTileState
import com.juancams.rviz2mini.ui.theme.VizTheme

/** Camera tab: the screen shared with the desktop (ui/CameraScreen). */
class CameraFragment : TabFragment() {

    @Volatile private var tiles: (() -> List<CameraTileState>)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val compact = spec()?.isWidget == true
            setContent { VizTheme { CameraScreen(tabId, compact = compact, onTiles = { tiles = it }) } }
        }

    /** Each visible camera: its topic, whether it already has an image and where it is in the window. */
    internal fun tilesForTest(): List<Triple<String, Boolean, android.graphics.Rect>> = tiles?.invoke().orEmpty().map { t ->
        val b = t.bounds
        Triple(t.topic, t.hasImage, if (b == null) android.graphics.Rect() else android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
    }

    companion object {
        const val KEY_TOPIC = CAMERA_KEY_TOPIC
        const val KEY_KIND = CAMERA_KEY_KIND
        const val KEY_CUSTOM = CAMERA_KEY_CUSTOM
        /** The visible cameras ("KIND\tname" per line). */
        const val KEY_TOPICS = CAMERA_KEY_TOPICS
        const val MAX_CAMERAS = CAMERA_MAX
    }
}
