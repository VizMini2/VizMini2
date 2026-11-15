// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.dashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import com.juancams.rviz2mini.tabs.AddTabActivity
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.tabs.TabFragments
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.DASHBOARD_KEY_LAYOUT
import com.juancams.rviz2mini.ui.screens.DashboardController
import com.juancams.rviz2mini.ui.screens.DashboardView
import com.juancams.rviz2mini.ui.theme.VizTheme

/**
 * Free-form dashboard: the screen shared with the PC (ui/DashboardView). Each widget is a normal tab (RViz,
 * camera, teleop, buttons...) stored separately with [TabSpec.KEY_DASHBOARD] pointing here, and shown as a child
 * fragment inside its card. The layout is stored in the [KEY_LAYOUT] setting.
 */
class DashboardFragment : TabFragment() {

    private lateinit var ctl: DashboardController

    private val addWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val spec = TabList.fromJson(r.data?.getStringExtra(AddTabActivity.RESULT_TAB))?.firstOrNull() ?: return@registerForActivityResult
        addWidget(spec)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // When the activity is recreated, the restored widgets would not have their card yet (it is composed later):
        // they are removed and each card creates its own again.
        removeChildren()
        ctl = DashboardController(tabId)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                VizTheme {
                    DashboardView(ctl,
                        onAddWidget = { addWidget.launch(Intent(requireContext(), AddTabActivity::class.java).putExtra(AddTabActivity.EXTRA_WIDGET, true)) },
                        onAddItem = { spec -> (childFragmentManager.findFragmentByTag(spec.id) as? WidgetItems)?.addItem() },
                        hasItems = { it.type in ITEM_TYPES },
                    ) { spec, m -> WidgetFragment(spec, m) }
                }
            }
        }

    override fun onDestroyView() {
        super.onDestroyView()
        removeChildren()
    }

    private fun removeChildren() {
        val fm = childFragmentManager
        if (fm.fragments.isEmpty()) return
        fm.beginTransaction().apply { for (f in fm.fragments) remove(f) }.commitNowAllowingStateLoss()
    }

    /** The widget's tab as a child fragment, in a container inside the card. */
    @Composable
    private fun WidgetFragment(spec: TabSpec, modifier: Modifier) {
        val containerId = remember(spec.id) { View.generateViewId() }
        AndroidView({ c ->
            FrameLayout(c).apply {
                id = containerId
                doOnAttach {
                    val fm = childFragmentManager
                    if (view == null || fm.isDestroyed) return@doOnAttach
                    val old = fm.findFragmentByTag(spec.id)
                    // If Compose rebuilt the card, the fragment stayed in the old container: it is created again here.
                    if (old != null && (old.view?.parent as? View)?.id == containerId) return@doOnAttach
                    fm.beginTransaction().apply { old?.let { remove(it) } }.add(containerId, TabFragments.create(spec), spec.id).commitNowAllowingStateLoss()
                }
            }
        }, modifier)
        DisposableEffect(spec.id) {
            onDispose {
                // The card is gone (the widget was removed or Compose is rebuilding it): so is its fragment, if it is still in it.
                // When the view is closed, onDestroyView removes them all.
                val fm = childFragmentManager
                val f = fm.findFragmentByTag(spec.id)
                if (view != null && !fm.isDestroyed && f != null && (f.view?.parent as? View)?.id == containerId) {
                    fm.beginTransaction().remove(f).commitNowAllowingStateLoss()
                }
            }
        }
    }

    private fun addWidget(spec: TabSpec) = ctl.add(spec)

    // ---------------------------------------------------------------- for the tests

    internal fun layoutForTest(): List<DashboardGrid.Item> = ctl.items
    internal fun addWidgetForTest(spec: TabSpec) = addWidget(spec)
    internal fun moveForTest(id: String, x: Int, y: Int, w: Int, h: Int) = ctl.move(id, x, y, w, h)
    internal fun moveForTest(id: String, x: Double, y: Double, w: Double, h: Double) = ctl.move(id, x, y, w, h)
    internal fun removeForTest(id: String) = ctl.remove(id)
    internal fun setEditingForTest(on: Boolean) { ctl.editing = on }

    companion object {
        const val KEY_LAYOUT = DASHBOARD_KEY_LAYOUT
        /** Widgets with their own elements (the edit mode "+" adds one to them). */
        private val ITEM_TYPES = setOf(TabType.BUTTONS, TabType.SLIDERS, TabType.INDICATORS)

        fun defaultSize(type: TabType): Pair<Int, Int> = DashboardGrid.defaultSize(type)
    }
}
