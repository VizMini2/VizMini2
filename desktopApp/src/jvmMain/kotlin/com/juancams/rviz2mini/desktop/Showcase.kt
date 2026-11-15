// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.screens.DashboardTemplates

/** `vizmini2 --showcase`: adds the "Showcase" dashboard (the template with almost everything, meant for tools/fake_robot.py). */
object Showcase {
    fun create(): TabSpec {
        val tabs = DashboardTemplates.build(DashboardTemplates.Template.SHOWCASE)
        for (t in tabs) TabStore.get().add(t)
        return tabs.first()
    }
}
