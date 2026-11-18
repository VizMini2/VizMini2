// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import java.io.File

/**
 * The desktop entry when the app is not installed from the .deb (for example `ros2 run vizmini2 vizmini2`): without
 * one, GNOME and KDE show the Java class name and a generic icon in the top bar and the dock. The first time, it
 * writes ~/.local/share/applications/vizmini2.desktop (Name VizMini2, the logo, StartupWMClass=vizmini2, and the
 * launcher of this installation) and the logo next to it; it also adds VizMini2 to the applications menu.
 * The .deb brings its own entry (see fixDebDesktopEntry in desktopApp/build.gradle.kts), so then nothing is written.
 */
object LinuxDesktopEntry {
    private const val DEB_ENTRY = "/usr/share/applications/vizmini2-vizmini2.desktop"

    /** The entry text for the launcher `exec` and the icon `icon`. */
    fun entry(exec: String, icon: String): String = """
        [Desktop Entry]
        Type=Application
        Name=VizMini2
        Comment=ROS 2 visualization and control
        Exec="$exec"
        Icon=$icon
        Terminal=false
        Categories=Development;
        Keywords=ROS;ROS2;robot;RViz;
        StartupWMClass=$WM_CLASS
    """.trimIndent() + "\n"

    fun install() {
        if (!System.getProperty("os.name", "").lowercase().contains("linux")) return
        runCatching {
            // Only from an installed app image (jpackage sets this): not from `gradlew run`.
            val launcher = System.getProperty("jpackage.app-path")?.takeIf { File(it).canExecute() } ?: return
            if (launcher.startsWith("/opt/vizmini2/") || File(DEB_ENTRY).exists()) return
            val home = System.getProperty("user.home") ?: return
            val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
            val icon = File(data, "vizmini2/vizmini2.png")
            if (!icon.exists()) {
                val png = javaClass.getResourceAsStream("/vizmini2.png") ?: return
                icon.parentFile.mkdirs()
                png.use { i -> icon.outputStream().use { i.copyTo(it) } }
            }
            val file = File(data, "applications/vizmini2.desktop")
            val text = entry(launcher, icon.absolutePath)
            if (file.exists() && file.readText() == text) return
            file.parentFile.mkdirs()
            file.writeText(text)
        }
    }
}
