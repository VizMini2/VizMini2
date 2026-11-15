// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.alerts.AlertLog
import com.juancams.rviz2mini.alerts.AlertMonitor
import com.juancams.rviz2mini.alerts.AlertSettings
import com.juancams.rviz2mini.alerts.TopicRule
import com.juancams.rviz2mini.alerts.TopicRules
import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.camera.CameraStream
import com.juancams.rviz2mini.camera.FrameStream
import com.juancams.rviz2mini.camera.ImageKind
import com.juancams.rviz2mini.camera.ImageTopic
import com.juancams.rviz2mini.control.ControllerManagerClient
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.nav.Zones
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.MAPS_KEY_ZONES
import com.juancams.rviz2mini.ui.screens.MapsScreens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The newer tabs with the demo robot, on the phone: ros2_control controllers (deactivating diff_drive_controller
 * stops the robot), H.264 video (the demo camera encoded and decoded with MediaCodec, in the Camera stream and in the
 * 3D view's stream), Maps (the demo map and a keepout mask published from the screen) and Alerts (a topic rule that
 * fires). Screenshots in files/features/. Leaves the tabs, connection, demo, alert settings and rules as they were.
 */
@RunWith(AndroidJUnit4::class)
class FeaturesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir = File(context.getExternalFilesDir(null), "features").apply { deleteRecursively(); mkdirs() }
    private val savedTabs = TestTabs.backup(context)
    private val savedRos = prefs("rviz2mini_ros").all
    private val savedAlerts = AlertSettings.load(context)
    private val savedRules = TopicRules.load()

    private fun prefs(n: String) = context.getSharedPreferences(n, Context.MODE_PRIVATE)

    @After fun tearDown() {
        TopicRules.save(savedRules)
        AlertMonitor.apply(context, savedAlerts)
        if (DemoMode.isActive(context)) instrumentation.runOnMainSync { DemoMode.exit(context, reconnect = false) {} }
        if (DemoMode.isActive(context)) prefs("rviz2mini_demo").edit().clear().commit()
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply { savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } } }.commit()
    }

    private fun waitFor(what: () -> String, timeoutMs: Long = 20_000, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!ok() && SystemClock.uptimeMillis() < end) SystemClock.sleep(200)
        assertTrue(what(), ok())
    }

    private fun open(sc: ActivityScenario<MainActivity>, tab: TabSpec) {
        sc.onActivity { a -> TabStore.get().add(tab); a.replaceTabs(TabStore.get().all.toList()); a.openTab(tab.id) }
        SystemClock.sleep(2500)
    }

    /** The demo robot's speed (from /odom), to see whether it moves. */
    private fun speed(): Double {
        var v: Double? = null
        val s = DynRos.subscribe("/odom", Interfaces.get().msg("nav_msgs/msg/Odometry"), RosConnection.defaultQos()) { m ->
            val lin = ((m["twist"] as Map<*, *>)["twist"] as Map<*, *>)["linear"] as Map<*, *>
            v = Math.abs((lin["x"] as Number).toDouble())
        }
        try { waitFor({ "no /odom" }, 10_000) { v != null } } finally { s.close() }
        SystemClock.sleep(300)
        return v!!
    }

    @Test fun controllersVideoMapsAndAlerts() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
            SystemClock.sleep(4000)

            // ---- Controllers: the demo controller manager, and the robot stops without its diff_drive_controller.
            open(sc, TabSpec.create(TabType.CONTROLLERS, "Controllers"))
            Ui.waitFor("diff_drive_controller", 20_000)
            Ui.shot(dir, "controllers")
            val cm = ControllerManagerClient(Interfaces.get())
            try {
                assertEquals(setOf("joint_state_broadcaster", "diff_drive_controller", "wheel_velocity_controller"), cm.listControllers().map { it.name }.toSet())
                assertEquals("DemoRobotSystem", cm.listHardwareComponents().single().name)
                // The panel's button (accessibility action: HyperOS blocks injected touches).
                Ui.click("deactivate diff_drive_controller")
                waitFor({ "diff_drive_controller still active" }) { cm.listControllers().first { it.name == "diff_drive_controller" }.state == "inactive" }
                waitFor({ "the robot still moves: ${speed()}" }, 10_000) { speed() < 0.02 }
                Ui.shot(dir, "controllers_inactive")
                assertTrue(cm.switchControllers(listOf("diff_drive_controller"), emptyList()).ok)
                waitFor({ "the robot does not move again" }, 15_000) { speed() > 0.05 }
                // Strict switch: the wheel controller wants the same wheels.
                assertTrue(!cm.switchControllers(listOf("wheel_velocity_controller"), emptyList()).ok)
            } finally { cm.close() }

            // ---- Video: the demo camera as H.264, decoded by the phone.
            val frames = CopyOnWriteArrayList<Pair<Int, Int>>()
            val errors = CopyOnWriteArrayList<String>()
            val fs = FrameStream(ImageTopic("/camera/image_raw/foxglove", ImageKind.VIDEO), { img, _ -> frames += img.width to img.height }, { errors += it })
            fs.start()
            try { waitFor({ "${frames.size} video frames, errors $errors" }, 20_000) { frames.size >= 5 } } finally { fs.stop() }
            assertEquals(320 to 240, frames.last())
            // The 3D view's stream on Android (Bitmaps).
            val bitmaps = CopyOnWriteArrayList<String>()
            val cs = CameraStream(ImageTopic("/camera/image_raw/foxglove", ImageKind.VIDEO), object : CameraStream.Listener {
                override fun onFrame(bitmap: android.graphics.Bitmap, info: CameraStream.FrameInfo) { bitmaps += "${bitmap.width}x${bitmap.height} ${info.encoding}" }
                override fun onStreamError(message: String) { errors += message }
            })
            instrumentation.runOnMainSync { cs.start() }
            try { waitFor({ "${bitmaps.size} bitmaps, errors $errors" }, 20_000) { bitmaps.size >= 3 } } finally { instrumentation.runOnMainSync { cs.stop() } }
            assertEquals("320x240 h264", bitmaps.last())

            // ---- Maps: the demo map with a keepout zone, published as a mask from the screen.
            val zone = Zones.Zone(Zones.Kind.KEEPOUT, listOf(6.2 to 4.7, 8.3 to 4.7, 8.3 to 5.9, 6.2 to 5.9), 100)
            val maps = TabSpec.create(TabType.MAPS, "Maps").withSetting(MAPS_KEY_ZONES, Zones.format(listOf(zone)))
            var masked = -1
            val maskSub = DynRos.subscribe("/keepout_filter_mask", Interfaces.get().msg("nav_msgs/msg/OccupancyGrid"), BagRecorder.qos(true)) { m ->
                // int8[] comes as a list of numbers (only uint8[] is a ByteArray).
                masked = when (val d = m["data"]) { is ByteArray -> d.count { it.toInt() == 100 }; is List<*> -> d.count { (it as? Number)?.toInt() == 100 }; else -> -2 }
            }
            try {
                open(sc, maps)
                waitFor({ "no map in the Maps tab" }) { MapsScreens.grids[maps.id] != null }
                assertEquals(200, MapsScreens.grids.getValue(maps.id).width)
                Ui.shot(dir, "maps")
                Ui.click("publish KEEPOUT")
                // 2.1 x 1.2 m at 5 cm: about 42 x 24 cells.
                waitFor({ "mask with $masked cells" }, 15_000) { masked in 900..1100 }
            } finally { maskSub.close() }

            // ---- Alerts: a rule on the demo battery (always below 99 %) fires.
            AlertLog.clear()
            AlertMonitor.apply(context, savedAlerts.copy(enabled = true))
            TopicRules.save(listOf(TopicRule(id = "device-test", name = "Battery below 99 %", topic = "/battery_state", field = "percentage", op = TopicRule.Op.LT, threshold = 0.99)))
            open(sc, TabSpec.create(TabType.ALERTS, "Alerts"))
            waitFor({ "the rule did not fire: ${AlertLog.all().map { it.alert.key }}" }, 20_000) { AlertLog.all().any { it.alert.key == "rule:device-test" } }
            Ui.waitFor("Battery below 99 %", 10_000)
            Ui.shot(dir, "alerts")
        }
    }
}
