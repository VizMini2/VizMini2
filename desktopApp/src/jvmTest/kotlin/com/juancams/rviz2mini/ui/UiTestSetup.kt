// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui

/** The shared screens without ROS: settings in a temporary folder (never the user's) and the desktop platform hooks. */
object UiTestSetup {
    @Volatile private var done = false

    @Synchronized fun install() {
        if (done) return
        val dir = java.nio.file.Files.createTempDirectory("vizmini2-ui-test").toFile().apply { deleteOnExit() }
        System.setProperty("vizmini2.config", dir.absolutePath)
        com.juancams.rviz2mini.desktop.DesktopPlatform.install()
        done = true
    }
}
