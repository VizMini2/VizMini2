// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.desktop

import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState

fun main(args: Array<String>) {
    // Where Filament's NativeLoader extracts its libraries (tools/filament-desktop/NativeLoader.java).
    if (System.getProperty("vizmini2.cache.dir") == null) System.setProperty("vizmini2.cache.dir", com.juancams.rviz2mini.platform.Platform.cacheDir.path)
    // jros2 takes its settings from the app's jros2.properties (so there is no ~/.ihmc). That file says no intraprocess
    // delivery, as jros2 does by default, except on GitHub Actions, where jros2 turns it on: the same there.
    if (System.getenv("GITHUB_ACTIONS") != null && System.getProperty("fastdds.intraprocess.delivery") == null)
        System.setProperty("fastdds.intraprocess.delivery", "true")
    DesktopPlatform.install()
    com.juancams.rviz2mini.ui.common.PlaybackBars.shownByWindow = true
    DesktopRviz.install()
    seedFromRosEnvironment()
    linuxX11Setup()
    DesktopSettings.applyLanguage()
    if ("--check" in args) return check(args)
    if ("--scan-domains" in args) {
        val own = com.juancams.rviz2mini.ros.DomainScanner.ownIps()
        val r = com.juancams.rviz2mini.ros.DomainScanner.scan()
        if (r.isEmpty()) println(tr("cli_no_domains"))
        for ((d, ips) in r) println(tr("cli_domain", d, ips.joinToString { if (it in own) tr("cli_this_machine", it) else it }))
        return
    }
    if ("--showcase" in args) {
        val d = Showcase.create()
        println(tr("cli_showcase_added", d.name))
        return
    }
    // vizmini2 --shot output.png [--tab TYPE] [--domain N]: windowless capture (see Shot.kt).
    args.indexOf("--shot").takeIf { it >= 0 }?.let { i ->
        RosConnection.start()
        args.indexOf("--domain").takeIf { it >= 0 }?.let { d -> RosConnection.applyConfig(RosConnection.config.copy(domainId = args[d + 1].toInt())) }
        // A capture with --demo leaves demo mode as it was (otherwise the next normal start would be in the demo).
        val demoBefore = DesktopDemo.active
        startDemo(args)
        importAtStart(args)
        val tab = args.indexOf("--tab").takeIf { it >= 0 }?.let { args[it + 1] } ?: importedTab
        applySettings(args, tab)
        playAtStart(args)
        Thread.sleep(3000)
        val events = args.indexOf("--events").takeIf { it >= 0 }?.let { args[it + 1] }
        val millis = args.indexOf("--millis").takeIf { it >= 0 }?.let { args[it + 1].toLong() } ?: 4000
        // --size 800x600: capture size (default 1200x800).
        val (w, h) = args.indexOf("--size").takeIf { it >= 0 }?.let { args[it + 1].split('x').map(String::toInt) } ?: listOf(1200, 800)
        shot(java.io.File(args[i + 1]), width = w, height = h, millis = millis, events = events) { DesktopApp(startTab = tab) }
        if (!demoBefore && DesktopDemo.active) DesktopDemo.exit() else DesktopDemo.stopRobot()
        RosConnection.stop()
        kotlin.system.exitProcess(0)
    }
    RosConnection.start()
    args.indexOf("--domain").takeIf { it >= 0 }?.let { d -> RosConnection.applyConfig(RosConnection.config.copy(domainId = args[d + 1].toInt())) }
    startDemo(args)
    importAtStart(args)
    applySettings(args, args.indexOf("--tab").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) })
    playAtStart(args)
    DesktopAlerts.start()
    // The account's profiles (if signed in), at startup.
    DesktopAccount.sync()
    application {
        Window(
            onCloseRequest = { DesktopDemo.stopRobot(); RosConnection.stop(); exitApplication() }, title = "VizMini2 $APP_VERSION", icon = appIcon(),
            onPreviewKeyEvent = ::shortcut,
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
        ) {
            DesktopRecorder.window = window
            recordAtStart(args)
            // --tab TYPE: the tab to start with (as with --shot), e.g. for recording one.
            DesktopApp(startTab = args.indexOf("--tab").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: importedTab)
        }
    }
}

/**
 * `--import FILE`: adds what is in a VizMini2 tab file, an RViz 2 file, a Foxglove layout or a PlotJuggler layout, as
 * *Add* → *Import* does, and opens it (prints what was not carried over).
 */
private var importedTab: String? = null

private fun importAtStart(args: Array<String>) {
    val f = args.indexOf("--import").takeIf { it >= 0 }?.let { java.io.File(args[it + 1]) } ?: return
    val r = runCatching { com.juancams.rviz2mini.ui.interop.ConfigInterop.import(f.readText(), f.name) }
        .getOrElse { System.err.println("VizMini2: " + tr("cli_import_failed", f, it.message)); return }
    for (t in r.tabs) com.juancams.rviz2mini.tabs.TabStore.get().add(t)
    importedTab = r.tabs.firstOrNull { !it.isWidget }?.id
    println(tr("cli_imported", f.name, r.format.label, r.tabs.count { !it.isWidget }))
    r.notes.distinct().forEach { println("  • $it") }
}

/** `--demo`: start in demo mode (the simulated robot, see [DesktopDemo]); without it, the robot comes back if the app was closed in demo mode. */
private fun startDemo(args: Array<String>) {
    if ("--demo" in args) DesktopDemo.enter() else DesktopDemo.resumeIfActive()
}

/**
 * `--set key=value` (several): settings of the tab `--tab` (its id or name, or the first of that type, created if there is
 * none), for captures, recordings
 * and tests; `key=@file` reads the value from a file (JSON with quotes, which gradle --args swallows).
 */
private fun applySettings(args: Array<String>, tab: String?) {
    val sets = args.withIndex().filter { it.value == "--set" }.mapNotNull { args.getOrNull(it.index + 1) }
    if (sets.isEmpty()) return
    val store = com.juancams.rviz2mini.tabs.TabStore.get()
    val spec = store.all.firstOrNull { it.id == tab || it.name == tab } ?: run {
        val type = com.juancams.rviz2mini.tabs.TabType.fromName(tab ?: "") ?: return
        store.all.firstOrNull { it.type == type } ?: com.juancams.rviz2mini.tabs.TabSpec.create(type, type.label()).also { store.add(it) }
    }
    for (kv in sets) {
        val v = kv.substringAfter('=')
        store.updateSetting(spec.id, kv.substringBefore('='), if (v.startsWith("@")) java.io.File(v.substring(1)).readText().trim() else v.replace("\\n", "\n"))
    }
}

/**
 * `vizmini2 --record out.mp4 --seconds N`: records the window for N seconds after the first ones and exits (to try
 * the recorder and to make clips). Only once per run.
 */
@Volatile private var recordStarted = false

private fun recordAtStart(args: Array<String>) {
    val i = args.indexOf("--record").takeIf { it >= 0 } ?: return
    if (recordStarted) return
    recordStarted = true
    val out = java.io.File(args[i + 1])
    val seconds = args.indexOf("--seconds").takeIf { it >= 0 }?.let { args[it + 1].toDouble() } ?: 5.0
    Thread {
        Thread.sleep(4000)
        if (!DesktopRecorder.start(out)) { System.err.println("record: ${DesktopRecorder.last}"); kotlin.system.exitProcess(1) }
        Thread.sleep((seconds * 1000).toLong())
        val f = DesktopRecorder.stop()
        println("record: ${f?.absolutePath ?: DesktopRecorder.last}")
        RosConnection.stop()
        kotlin.system.exitProcess(if (f != null) 0 else 1)
    }.apply { isDaemon = true }.start()
}

/**
 * `vizmini2 --check [--domain N]`: connects, waits a few seconds and prints what it sees (to check the network and the
 * natives without a UI). Exits with 0 if there is any node besides its own.
 */
private fun check(args: Array<String>) {
    val domain = args.indexOf("--domain").takeIf { it >= 0 }?.let { args[it + 1].toInt() }
    RosConnection.start()
    if (domain != null) RosConnection.applyConfig(RosConnection.config.copy(domainId = domain))
    Thread.sleep(6000)
    // "Connected" (the status) is not translated: scripts and the CI look for ": Connected".
    println(tr("cli_status", RosConnection.status))
    val g = RosConnection.graph()
    val nodes = g?.nodes().orEmpty()
    println(tr("cli_nodes", nodes.size, nodes.map { it.name }))
    println(tr("cli_topics", g?.topics()?.map { "${it.name} [${it.type}]" }))
    RosConnection.stop()
    Thread.sleep(500)
    kotlin.system.exitProcess(if (nodes.any { it.name != "/" + RosConnection.config.nodeName }) 0 else 1)
}

/**
 * The first time (no saved configuration), the one from the ROS environment: ROS_DOMAIN_ID, ROS_STATIC_PEERS and
 * ROS_DISCOVERY_SERVER, as any node launched from that terminal would do.
 */
private fun seedFromRosEnvironment() {
    if (com.juancams.rviz2mini.platform.Platform.prefs("rviz2mini_ros").contains("domainId")) return
    val env = System.getenv()
    var cfg = RosConfig.load()
    env["ROS_DOMAIN_ID"]?.trim()?.toIntOrNull()?.takeIf { it in RosConfig.MIN_DOMAIN_ID..RosConfig.MAX_DOMAIN_ID }?.let { cfg = cfg.copy(domainId = it) }
    env["ROS_STATIC_PEERS"]?.trim()?.takeIf { it.isNotEmpty() && RosConfig.validatePeers(it.replace(';', ',')) == null }
        ?.let { cfg = cfg.copy(discoveryPeers = it.replace(';', ',')) }
    env["ROS_DISCOVERY_SERVER"]?.trim()?.takeIf { it.isNotEmpty() && RosConfig.validateServers(it.replace(';', ',')) == null }
        ?.let { cfg = cfg.copy(discoveryServers = it.replace(';', ',')) }
    RosConfig.save(cfg)
}

/** The app version (vizmini2.version in gradle.properties), for the window title. */
val APP_VERSION: String by lazy {
    runCatching {
        java.util.Properties().apply { object {}.javaClass.getResourceAsStream("/vizmini2-version.properties")!!.use { load(it) } }.getProperty("version")
    }.getOrNull() ?: "dev"
}

/** The window icon: the logo (tools/make_icons.sh). */
private fun appIcon(): androidx.compose.ui.graphics.painter.Painter? =
    object {}.javaClass.getResourceAsStream("/vizmini2.png")?.use { javax.imageio.ImageIO.read(it) }
        ?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it.toComposeImageBitmap()) }

/**
 * Linux: Filament uses GLX from its own thread and AWT/Compose use X11 from theirs. Xlib requires XInitThreads() before
 * any other call in that case (without it, Mesa crashes in XQueryExtension), and Java does not call it:
 * libvizmini2-x11.so does when loaded (tools/x11-threads). Also, Compose renders in software so that Filament is the only
 * one using OpenGL in the process (`-Dskiko.renderApi=OPENGL` changes it).
 */
private fun linuxX11Setup() {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("linux")) return
    if (System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
    runCatching {
        val res = object {}.javaClass.getResourceAsStream("/natives/linux-x64/libvizmini2-x11.so") ?: return
        val f = java.io.File.createTempFile("vizmini2-x11", ".so").apply { deleteOnExit() }
        res.use { i -> f.outputStream().use { i.copyTo(it) } }
        System.load(f.absolutePath)
    }.onFailure { System.err.println("VizMini2: XInitThreads not available: $it") }
    // After XInitThreads (this opens the display): the X11 class of the windows, which the desktop matches against
    // StartupWMClass to show the app's name and logo (without it, the main class name and a generic icon).
    runCatching {
        val tk = java.awt.Toolkit.getDefaultToolkit()
        tk.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(tk, WM_CLASS)
    }
    LinuxDesktopEntry.install()
}

/** X11 window class (StartupWMClass of the desktop entries). */
const val WM_CLASS = "vizmini2"

/**
 * `--play file.mcap`: starts playing that bag (to the network, like "Play" in the Bags tab), in a loop; the
 * playback bar of the dashboard and the 3D view controls it. For when there is already a connection.
 */
internal fun playAtStart(args: Array<String>) {
    val f = args.indexOf("--play").takeIf { it >= 0 }?.let { java.io.File(args[it + 1]) } ?: return
    Thread {
        for (i in 0 until 100) { if (RosConnection.status is RosConnection.Status.Connected) break; Thread.sleep(100) }
        runCatching {
            val p = com.juancams.rviz2mini.bag.BagPlayer(f, com.juancams.rviz2mini.ros.dyn.Interfaces.get(), object : com.juancams.rviz2mini.bag.BagPlayer.Listener {
                override fun onProgress(positionNs: Long, durationNs: Long) {}
                override fun onFinished(error: String?) {
                    com.juancams.rviz2mini.platform.Platform.main.post { com.juancams.rviz2mini.bag.BagSession.player?.close(); com.juancams.rviz2mini.bag.BagSession.player = null }
                }
            })
            p.loop = true
            com.juancams.rviz2mini.platform.Platform.main.post { com.juancams.rviz2mini.bag.BagSession.player = p; p.start() }
        }.onFailure { System.err.println("VizMini2: " + tr("cli_play_failed", f, it)) }
    }.apply { isDaemon = true }.start()
}

/** PC shortcuts: Ctrl+Z undo in the dashboard, Ctrl+E edit, Esc exit editing, Ctrl+0 zoom to 100 %. */
private fun shortcut(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
    // Ctrl+Shift+R: record the window (or stop).
    if (e.isCtrlPressed && e.isShiftPressed && e.key == androidx.compose.ui.input.key.Key.R) { Thread { DesktopRecorder.toggle() }.start(); return true }
    val d = com.juancams.rviz2mini.ui.screens.DashboardController.visible ?: return false
    return when {
        e.isCtrlPressed && e.key == androidx.compose.ui.input.key.Key.Z -> { d.undo(); true }
        e.isCtrlPressed && e.key == androidx.compose.ui.input.key.Key.E -> { d.editing = !d.editing; true }
        e.isCtrlPressed && e.key == androidx.compose.ui.input.key.Key.Zero -> { com.juancams.rviz2mini.ui.screens.DashboardController.resetZoom?.invoke(); true }
        e.key == androidx.compose.ui.input.key.Key.Escape && d.editing -> { d.editing = false; true }
        else -> false
    }
}
