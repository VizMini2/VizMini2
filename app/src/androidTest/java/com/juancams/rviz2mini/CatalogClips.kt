// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.app.Activity
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Videos of each tab type for the "+" catalog cards (not a test of the suite):
 * `-e clips true -e lang en|es`, with the app language already set (`cmd locale set-app-locales`).
 * All with the demo robot. Leaves the MP4s in /sdcard/clips/<lang>/ (full
 * screen at 610 × 1356); they are then cropped to res/raw(-es)/clip_*.mp4. Leaves the tabs and the connection
 * as they were.
 */
@RunWith(AndroidJUnit4::class)
class CatalogClips {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private var lang = "en"

    /** Text in the language the app is shown in (the activity's). */
    private fun str(scenario: ActivityScenario<MainActivity>, id: Int): String { var s = ""; scenario.onActivity { s = it.getString(id) }; return s }

    @Test
    fun clips() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("clips") == "true")
        val only = InstrumentationRegistry.getArguments().getString("only")?.split(',')?.toSet()
        lang = InstrumentationRegistry.getArguments().getString("lang") ?: "en"
        shell("mkdir -p /sdcard/clips/$lang")
        // If the demo is already active, exit it first: that way the user's state is saved (and restored later).
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }
        }
        // The app language from the test itself (cmd locale only exists since Android 13).
        val savedLang = AppLanguage.current()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.set(lang) }
        val savedTabs = TestTabs.backup(context)
        val savedRos = context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).all
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
                SystemClock.sleep(5000)
                val extra = listOf(TabType.TELEOP_DUAL, TabType.BAGS, TabType.TF_TREE, TabType.NODES, TabType.SERVICES,
                    TabType.ACTIONS, TabType.NODE_GRAPH, TabType.CONTROLLERS, TabType.ALERTS).map { TabSpec.create(it, it.label()) } + listOf(
                    // Maps: the demo room with a keepout zone around a box and a slow zone near home.
                    TabSpec.create(TabType.MAPS, TabType.MAPS.label()).withSetting(com.juancams.rviz2mini.ui.screens.MAPS_KEY_ZONES, DEMO_ZONES)) + listOf(
                    TabSpec.create(TabType.MESSAGE, TabType.MESSAGE.label()).withSetting(com.juancams.rviz2mini.explore.MessageFragment.KEY_TOPIC, "/odom")
                        .withSetting(com.juancams.rviz2mini.explore.MessageFragment.KEY_TYPE, "nav_msgs/msg/Odometry"),
                    TabSpec.create(TabType.PARAMETERS, TabType.PARAMETERS.label()).withSetting(com.juancams.rviz2mini.params.ParamsFragment.KEY_NODE, "/demo_robot")) +
                    // Behavior tree: Groot2 over TCP (not DDS) against tools/bt_groot/bt_groot_server on the PC (-e pcIp).
                    listOfNotNull(InstrumentationRegistry.getArguments().getString("pcIp")?.let { ip ->
                        TabSpec.create(TabType.BEHAVIOR_TREE, TabType.BEHAVIOR_TREE.label()).withSetting(com.juancams.rviz2mini.bt.BehaviorTreeFragment.KEY_HOST, ip)
                    })
                scenario.onActivity { a -> for (t in extra) TabStore.get().add(t); a.replaceTabs(TabStore.get().all.toList()) }
                SystemClock.sleep(2000)
                val byType = TabStore.get().all.filter { !it.isWidget }.associateBy { it.type }
                fun open(type: TabType, settle: Long = 2500) {
                    scenario.onActivity { it.openTab(byType.getValue(type).id) }
                    SystemClock.sleep(settle)
                }
                fun clip(name: String, seconds: Int, block: () -> Unit) {
                    if (only != null && name !in only) return
                    record(name, seconds, block)
                }

                open(TabType.RVIZ, 6000)
                clip("rviz", 9) {
                    SystemClock.sleep(1200)
                    drag(centerOf(scenario, 0.5f, 0.45f), dx = 260f, dy = 0f, ms = 2600)
                    SystemClock.sleep(800)
                    drag(centerOf(scenario, 0.5f, 0.45f), dx = -200f, dy = 60f, ms = 2400)
                }
                // Select tool (ruler button): pick a laser point and see where it is. Only for the
                // documentation (docs/clips/), not shipped in the APK.
                clip("rviz_select", 8) {
                    SystemClock.sleep(900)
                    Ui.click(str(scenario, R.string.rviz_more_tools))
                    SystemClock.sleep(1000)
                    Ui.click(str(scenario, R.string.rviz_tool_select_menu))
                    SystemClock.sleep(1300)
                    // MIUI/HyperOS block injected touches: the "touch" goes straight to the tool's handler.
                    for (fy in listOf(0.2f, 0.55f)) {
                        scenario.onActivity { a ->
                            val rviz = a.supportFragmentManager.fragments.filterIsInstance<com.juancams.rviz2mini.rviz.RvizFragment>().firstOrNull { it.isVisible }
                            rviz?.selectablePointForTest(fy, maxFx = 0.55f)?.let { (x, y) ->
                                rviz.touchToolForTest(MotionEvent.ACTION_DOWN, x, y)
                                rviz.touchToolForTest(MotionEvent.ACTION_UP, x, y)
                            }
                        }
                        SystemClock.sleep(2500)
                    }
                    Ui.click(str(scenario, R.string.rviz_more_tools)) // the ruler again: leaves the tool
                }
                open(TabType.CAMERA, 3000)
                clip("camera", 8) { SystemClock.sleep(7500) }
                open(TabType.TELEOP)
                clip("teleop", 9) { joystick(scenario, 0, listOf(0f to -0.8f, 0.5f to -0.7f, 0.7f to -0.3f, 0f to -0.9f, -0.6f to -0.6f, -0.7f to 0f), 7500) }
                open(TabType.TELEOP_DUAL)
                clip("teleop_dual", 9) {
                    joystick(scenario, 0, listOf(0f to -0.8f, 0f to -0.9f, 0f to -0.5f), 3500)
                    joystick(scenario, 1, listOf(0.8f to 0f, -0.8f to 0f, 0.6f to 0f), 3500)
                }
                open(TabType.DIAGNOSTICS, 4000)
                clip("diagnostics", 8) {
                    SystemClock.sleep(1500)
                    runCatching { Ui.click("demo_robot: Motors", exact = false) }
                    SystemClock.sleep(4500)
                }
                open(TabType.DASHBOARD, 6000)
                clip("dashboard", 9) { joystick(scenario, 0, listOf(0f to -0.9f, 0.6f to -0.7f, -0.6f to -0.7f), 7000) }
                open(TabType.TOPICS, 3500)
                clip("topics", 9) {
                    SystemClock.sleep(2000)
                    Ui.click("/odom")
                    SystemClock.sleep(5500)
                    Ui.back()
                }
                open(TabType.BAGS)
                clip("bags", 11) {
                    SystemClock.sleep(1200)
                    Ui.click(str(scenario, R.string.bags_record))
                    runCatching { Ui.click(str(scenario, R.string.bags_all)) }
                    Ui.click(str(scenario, R.string.bags_record))
                    SystemClock.sleep(4500)
                    Ui.click(str(scenario, R.string.bags_stop))
                    SystemClock.sleep(2500)
                }
                open(TabType.TF_TREE, 3000)
                clip("tf_tree", 8) {
                    SystemClock.sleep(1500)
                    drag(centerOf(scenario, 0.5f, 0.5f), dx = 0f, dy = 160f, ms = 2000)
                    SystemClock.sleep(3500)
                }
                open(TabType.NODE_GRAPH, 3500)
                clip("node_graph", 8) {
                    SystemClock.sleep(1500)
                    drag(centerOf(scenario, 0.5f, 0.5f), dx = -150f, dy = 0f, ms = 2000)
                    SystemClock.sleep(3500)
                }
                open(TabType.NODES, 3500)
                clip("nodes", 10) {
                    SystemClock.sleep(1800)
                    Ui.click("/demo_robot")
                    SystemClock.sleep(6000)
                    Ui.back()
                }
                open(TabType.SERVICES, 3500)
                clip("services", 10) {
                    SystemClock.sleep(1800)
                    Ui.click("/add_two_ints")
                    SystemClock.sleep(1500)
                    onResumed { a ->
                        val edits = views(a.window.decorView).filterIsInstance<EditText>()
                        edits[0].setText("20"); edits[1].setText("22")
                    }
                    SystemClock.sleep(1200)
                    onResumed { a -> views(a.window.decorView).filterIsInstance<Button>().first { it.text == a.getString(R.string.explore_call) }.performClick() }
                    SystemClock.sleep(3500)
                    Ui.back()
                }
                open(TabType.ACTIONS, 3500)
                clip("actions", 12) {
                    SystemClock.sleep(1800)
                    Ui.click("/fibonacci")
                    SystemClock.sleep(1500)
                    onResumed { a -> views(a.window.decorView).filterIsInstance<EditText>().first().setText("12") }
                    SystemClock.sleep(1000)
                    onResumed { a -> views(a.window.decorView).filterIsInstance<Button>().first { it.text == a.getString(R.string.explore_send_goal) }.performClick() }
                    SystemClock.sleep(7000)
                    Ui.back()
                }
                open(TabType.LIFECYCLE, 3500)
                clip("lifecycle", 9) {
                    SystemClock.sleep(1500)
                    Ui.click("Deactivate")
                    SystemClock.sleep(3000)
                    Ui.click("Activate")
                    SystemClock.sleep(3000)
                }
                open(TabType.BUTTONS, 2500)
                clip("buttons", 10) {
                    SystemClock.sleep(1200)
                    Ui.click(str(scenario, R.string.demo_button_fibonacci))
                    SystemClock.sleep(2500)
                    Ui.click("2 + 3")
                    SystemClock.sleep(1500)
                    Ui.click(str(scenario, R.string.demo_button_home))
                    SystemClock.sleep(2500)
                }
                open(TabType.SLIDERS, 2500)
                clip("sliders", 9) {
                    // The demo sliders (forward and turn via /cmd_vel) return to 0 when released.
                    SystemClock.sleep(800)
                    slide(scenario, 0, listOf(0.5f, 0.9f, 0.7f), 2600)
                    SystemClock.sleep(700)
                    slide(scenario, 1, listOf(-0.6f, 0.6f), 2600)
                    SystemClock.sleep(1200)
                }
                // New tabs: the demo moving (the indicators, the plots and the GPS with
                // the robot driving; the /odom message changing; the robot's parameters).
                open(TabType.INDICATORS, 3000)
                clip("indicators", 8) { SystemClock.sleep(7500) }
                open(TabType.PLOT, 6000)
                clip("plot", 9) { SystemClock.sleep(8500) }
                open(TabType.GPS, 6000)
                clip("gps", 8) { SystemClock.sleep(7500) }
                open(TabType.MESSAGE, 3000)
                clip("message", 8) { SystemClock.sleep(7500) }
                open(TabType.PARAMETERS, 4000)
                clip("parameters", 7) { SystemClock.sleep(6500) }
                if (TabType.BEHAVIOR_TREE in byType) {
                    open(TabType.BEHAVIOR_TREE, 3000)
                    clip("behavior_tree", 8) { SystemClock.sleep(7500) }
                }
                open(TabType.CONTROLLERS, 3500)
                clip("controllers", 10) {
                    SystemClock.sleep(1500)
                    runCatching { Ui.click("deactivate diff_drive_controller") }
                    SystemClock.sleep(3000)
                    runCatching { Ui.click("activate diff_drive_controller") }
                    SystemClock.sleep(3500)
                }
                open(TabType.MAPS, 4000)
                clip("maps", 8) {
                    // screenrecord only records changes: zoom in twice and back.
                    SystemClock.sleep(1200)
                    runCatching { Ui.click("+") }; SystemClock.sleep(1300)
                    runCatching { Ui.click("+") }; SystemClock.sleep(1800)
                    runCatching { Ui.click(str(scenario, R.string.maps_fit)) }; SystemClock.sleep(2500)
                }
                // PlanSys2 (the demo's cleaning robot): the four boxes, then the plan and the plan log on their own.
                open(TabType.PLANSYS2, 4000)
                clip("plansys2", 9) {
                    SystemClock.sleep(3000)
                    runCatching { Ui.click(str(scenario, R.string.ps2_plan)) }; SystemClock.sleep(2800)
                    runCatching { Ui.click(str(scenario, R.string.ps2_log)) }; SystemClock.sleep(2500)
                }
                runCatching { Ui.click(str(scenario, R.string.ps2_overview)) }
                val savedRules = com.juancams.rviz2mini.alerts.TopicRules.load()
                val savedAlerts = com.juancams.rviz2mini.alerts.AlertSettings.load(context)
                com.juancams.rviz2mini.alerts.AlertMonitor.apply(context, savedAlerts.copy(enabled = true))
                com.juancams.rviz2mini.alerts.TopicRules.save(demoRules(lang))
                open(TabType.ALERTS, 3000)
                clip("alerts", 8) { SystemClock.sleep(7500) }
                com.juancams.rviz2mini.alerts.TopicRules.save(savedRules)
                com.juancams.rviz2mini.alerts.AlertMonitor.apply(context, savedAlerts)
                // The bag recordings from this test are not kept.
                java.io.File(context.getExternalFilesDir(null), "bags").listFiles()?.forEach { if (it.name.startsWith("vizmini2_")) it.delete() }
                scenario.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }
            }
        } finally {
            RosConnection.stop()
            // If something failed halfway, the demo is not left active.
            DemoMode.exit(context, reconnect = false) { TabStore.get().replaceAll(it) }
            TestTabs.restore(context, savedTabs)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.set(savedLang) }
            context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).edit().clear().apply {
                savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
            }.commit()
        }
    }

    /** Records the screen for `seconds` seconds while `block` runs. */
    private fun record(name: String, seconds: Int, block: () -> Unit) {
        val start = SystemClock.uptimeMillis()
        val proc = automation.executeShellCommand("screenrecord --size 610x1356 --bit-rate 6000000 --time-limit $seconds /sdcard/clips/$lang/clip_$name.mp4")
        SystemClock.sleep(600)
        try { block() } finally {
            val left = seconds * 1000L + 1500 - (SystemClock.uptimeMillis() - start)
            if (left > 0) SystemClock.sleep(left)
            ParcelFileDescriptor.AutoCloseInputStream(proc).use { it.readBytes() }
        }
    }

    private fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use { it.readBytes() }

    private fun onResumed(block: (Activity) -> Unit) = instrumentation.runOnMainSync {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.let(block)
    }

    private fun views(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    /** Screen point at fraction (fx, fy) of the activity's content. */
    private fun centerOf(scenario: ActivityScenario<MainActivity>, fx: Float, fy: Float): Pair<Float, Float> {
        var p = 0f to 0f
        scenario.onActivity { a ->
            val c = a.findViewById<View>(android.R.id.content)
            val loc = IntArray(2).also { c.getLocationOnScreen(it) }
            p = (loc[0] + c.width * fx) to (loc[1] + c.height * fy)
        }
        return p
    }

    /** Moves visible joystick number `index` through the points (fractions of its radius) over `ms`. */
    private fun joystick(scenario: ActivityScenario<MainActivity>, index: Int, points: List<Pair<Float, Float>>, ms: Long) {
        var center = 0f to 0f
        var radius = 0f
        // The joysticks (Compose) by their description: 0 is the left one and 1 the right one.
        val desc = context.getString(if (index == 0) R.string.teleop_left_joystick else R.string.teleop_right_joystick)
        val r = android.graphics.Rect().also { Ui.waitFor(desc, exact = true).getBoundsInScreen(it) }
        center = r.exactCenterX() to r.exactCenterY()
        radius = minOf(r.width(), r.height()) / 2f * 0.8f
        val path = listOf(0f to 0f) + points + listOf(0f to 0f)
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, center.first, center.second)
        val stepMs = 16L
        val steps = (ms / stepMs).toInt()
        for (i in 1..steps) {
            val t = i.toFloat() / steps * (path.size - 1)
            val k = t.toInt().coerceAtMost(path.size - 2)
            val f = t - k
            val x = path[k].first + (path[k + 1].first - path[k].first) * f
            val y = path[k].second + (path[k + 1].second - path[k].second) * f
            inject(MotionEvent.ACTION_MOVE, down, center.first + x * radius, center.second + y * radius)
            SystemClock.sleep(stepMs)
        }
        inject(MotionEvent.ACTION_UP, down, center.first, center.second)
    }

    /**
     * Drags visible slider number `index` (top to bottom) from its center (value 0 in a symmetric range)
     * through the positions `points` (fractions of half the track, -1 to 1) over `ms`, and releases it.
     */
    private fun slide(scenario: ActivityScenario<MainActivity>, index: Int, points: List<Float>, ms: Long) {
        var cx = 0f; var cy = 0f; var half = 0f
        scenario.onActivity { a ->
            val sl = views(a.window.decorView).filterIsInstance<com.google.android.material.slider.Slider>().filter { it.isShown }
                .sortedBy { IntArray(2).also { l -> it.getLocationOnScreen(l) }[1] }[index]
            val loc = IntArray(2).also { sl.getLocationOnScreen(it) }
            cx = loc[0] + sl.width / 2f; cy = loc[1] + sl.height / 2f
            half = (sl.width - 2f * sl.trackSidePadding) / 2f
        }
        val path = listOf(0f) + points
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, cx, cy)
        val steps = (ms / 16).toInt()
        var x = cx
        for (i in 1..steps) {
            val t = i.toFloat() / steps * (path.size - 1)
            val k = t.toInt().coerceAtMost(path.size - 2)
            val f = (1 - kotlin.math.cos(Math.PI * (t - k)).toFloat()) / 2
            x = cx + (path[k] + (path[k + 1] - path[k]) * f) * half
            inject(MotionEvent.ACTION_MOVE, down, x, cy)
            SystemClock.sleep(16)
        }
        inject(MotionEvent.ACTION_UP, down, x, cy)
    }

    private fun drag(from: Pair<Float, Float>, dx: Float, dy: Float, ms: Long) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, from.first, from.second)
        val steps = (ms / 16).toInt()
        for (i in 1..steps) {
            // Smooth at the start and at the end.
            val f = (1 - kotlin.math.cos(Math.PI * i / steps).toFloat()) / 2
            inject(MotionEvent.ACTION_MOVE, down, from.first + dx * f, from.second + dy * f)
            SystemClock.sleep(16)
        }
        inject(MotionEvent.ACTION_UP, down, from.first + dx, from.second + dy)
    }

    private fun inject(action: Int, down: Long, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        automation.injectInputEvent(e, false)
        e.recycle()
    }

    companion object {
        /** The Maps clip's zones (the demo room is 10 x 8 m from the origin). */
        val DEMO_ZONES = com.juancams.rviz2mini.nav.Zones.format(listOf(
            com.juancams.rviz2mini.nav.Zones.Zone(com.juancams.rviz2mini.nav.Zones.Kind.KEEPOUT, listOf(6.2 to 4.7, 8.3 to 4.7, 8.3 to 5.9, 6.2 to 5.9), 100),
            com.juancams.rviz2mini.nav.Zones.Zone(com.juancams.rviz2mini.nav.Zones.Kind.SPEED, listOf(0.6 to 5.8, 3.2 to 5.8, 3.2 to 7.4, 0.6 to 7.4), 40)))

        /** The Alerts clip's rules: the battery one fires (the demo battery is below 99 %), the laser one does not. */
        fun demoRules(lang: String) = listOf(
            com.juancams.rviz2mini.alerts.TopicRule(name = if (lang == "es") "Batería por debajo del 99 %" else "Battery below 99 %", topic = "/battery_state", field = "percentage", op = com.juancams.rviz2mini.alerts.TopicRule.Op.LT, threshold = 0.99),
            com.juancams.rviz2mini.alerts.TopicRule(name = if (lang == "es") "Láser parado" else "Laser stopped", topic = "/scan", kind = com.juancams.rviz2mini.alerts.TopicRule.Kind.SILENCE, silenceS = 3.0,
                severity = com.juancams.rviz2mini.alerts.AlertRules.Severity.ERROR))
    }
}
