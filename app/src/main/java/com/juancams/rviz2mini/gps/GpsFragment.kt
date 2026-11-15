// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.gps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.ui.screens.GPS_DEFAULT_TOPIC
import com.juancams.rviz2mini.ui.screens.GPS_KEY_FOLLOW
import com.juancams.rviz2mini.ui.screens.GPS_KEY_TOPIC
import com.juancams.rviz2mini.ui.screens.GpsController
import com.juancams.rviz2mini.ui.screens.GpsView
import com.juancams.rviz2mini.ui.theme.VizTheme

/**
 * GPS tab: the screen shared with the desktop (ui/GpsScreen), with the OpenStreetMap tile map
 * in Compose. The fragment owns the controller (it refreshes while visible).
 */
class GpsFragment : TabFragment() {

    private var controller: GpsController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val c = GpsController(tabId)
        controller = c
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { VizTheme { GpsView(c) } }
        }
    }

    override fun onResume() { super.onResume(); controller?.resume() }
    override fun onPause() { super.onPause(); controller?.pause() }

    override fun onDestroyView() {
        super.onDestroyView()
        controller?.close(); controller = null
    }

    // ---------------------------------------------------------------- tests (on the main thread)

    private val c: GpsController get() = controller!!
    internal fun lastFixForTest(): GpsMath.Fix? { c.refresh(); return c.fix }
    internal fun trackSizeForTest(): Int = c.track.size
    internal fun infoForTest(): String { c.refresh(); return c.info }
    internal fun centerForTest(): LatLon = c.center
    internal fun setTopicForTest(t: String) = c.setTopic(t)
    internal fun followForTest() = c.follow

    companion object {
        const val KEY_TOPIC = GPS_KEY_TOPIC
        const val KEY_FOLLOW = GPS_KEY_FOLLOW
        const val DEFAULT_TOPIC = GPS_DEFAULT_TOPIC
    }
}
