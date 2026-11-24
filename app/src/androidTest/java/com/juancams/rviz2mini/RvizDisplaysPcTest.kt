// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayStatus
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.display.StatusLevel
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * RViz displays against the PC's simulated robot (fake_robot.py on ROS_DOMAIN_ID=77).
 * Only with `-e pc true`. Saves screenshots to Android/data/com.juancams.vizmini2/files/displays/.
 */
@RunWith(AndroidJUnit4::class)
class RvizDisplaysPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null
    private lateinit var dir: File

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        dir = File(context.getExternalFilesDir(null), "displays").apply { mkdirs() }
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_displays", discoveryPeers = TestArgs.peers))
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("pc") != "true") return
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val meshUrl get() = InstrumentationRegistry.getArguments().getString("meshUrl") ?: "http://${TestArgs.pcIp}:8000"

    /** Each type with its default topic and the camera view for the screenshot. */
    private val cases = listOf(
        DisplayType.MAP, DisplayType.LASER_SCAN, DisplayType.POINT_CLOUD2, DisplayType.POINT_CLOUD,
        DisplayType.ODOMETRY, DisplayType.PATH, DisplayType.POSE, DisplayType.POSE_COV, DisplayType.POSE_ARRAY,
        DisplayType.POINT, DisplayType.POLYGON, DisplayType.MARKER, DisplayType.MARKER_ARRAY, DisplayType.RANGE,
        DisplayType.AXES, DisplayType.IMAGE, DisplayType.GRID_CELLS, DisplayType.WRENCH, DisplayType.TWIST, DisplayType.ACCEL,
        DisplayType.TEMPERATURE, DisplayType.ILLUMINANCE, DisplayType.FLUID_PRESSURE, DisplayType.RELATIVE_HUMIDITY,
        DisplayType.CAMERA, DisplayType.DEPTH_CLOUD, DisplayType.EFFORT
    )

    private fun acceptable(type: DisplayType, s: DisplayStatus?): Boolean = when {
        s == null -> false
        s.level == StatusLevel.OK -> true
        else -> false
    }

    @Test
    fun everyDisplayTypeOneByOneThenAllTogether() {
        val tab = TabSpec.create(TabType.RVIZ, "RViz test").withSetting("displays", DisplayList.toJson(DisplayList.defaults()))
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, tab.id)
            SystemClock.sleep(5000)
            val rviz = rviz(scenario)
            onMain { rviz.setCameraForTest(16.0, -Math.PI / 2, 1.0, 0.0, 0.0) }
            assertEquals("map", onMainGet { rviz.currentFixedFrame() })
            shot("00_grid_tf")

            val failures = ArrayList<String>()
            val ids = HashMap<DisplayType, String>()
            for ((i, type) in cases.withIndex()) {
                // The test MarkerArray carries a MESH_RESOURCE: uses the PC's mesh server.
                val c = DisplayConfig.create(type).let { if (type == DisplayType.MARKER_ARRAY) it.with("meshUrl", meshUrl) else it }
                    // Camera with fake_robot's annotations (ImageMarker).
                    .let { if (type == DisplayType.CAMERA || type == DisplayType.IMAGE) it.with("annotations", "/camera/image_markers") else it }
                ids[type] = c.id
                onMain { rviz.addDisplayForTest(c) }
                val s = waitStatus(rviz, c.id, type, 12_000)
                Log.i(TAG, "DISPLAY ${type.label}: ${s?.level} ${s?.message}")
                if (!acceptable(type, s)) failures += "${type.label}: ${s?.level} ${s?.message}"
                shot("%02d_%s".format(i + 1, type.label))
                // Disable it for the next screenshot (and test enabling/disabling).
                onMain { rviz.updateDisplayForTest(c.copy(enabled = false)) }
                SystemClock.sleep(300)
                assertEquals(StatusLevel.DISABLED, onMainGet { rviz.displayStatus(c.id)?.level })
            }

            // All at once.
            onMain { for (c in rviz.displayConfigs()) rviz.updateDisplayForTest(c.copy(enabled = true)) }
            SystemClock.sleep(6000)
            val fps = measureFps(3000)
            for ((type, id) in ids) {
                val s = onMainGet { rviz.displayStatus(id) }
                Log.i(TAG, "TODOS ${type.label}: ${s?.level} ${s?.message}")
                if (!acceptable(type, s)) failures += "(todos) ${type.label}: ${s?.level} ${s?.message}"
            }
            Log.i(TAG, "FPS con todos los displays: %.1f".format(fps))
            shot("90_todos")

            // Select: some point of the clouds or of the laser under the finger.
            onMain { rviz.setToolForTest(com.juancams.rviz2mini.rviz.RvizFragment.Tool.SELECT) }
            var picked: String? = null
            val (sw, sh) = onMainGet { rviz.surfaceForTest().width to rviz.surfaceForTest().height }
            loop@ for (gy in 1..24) for (gx in 1..14) {
                picked = onMainGet { rviz.selectForTest(sw * gx / 15f, sh * gy / 25f) }
                if (picked != null) break@loop
            }
            Log.i(TAG, "SELECT: $picked")
            if (picked == null || !Regex("(LaserScan|PointCloud2?): map \\(").containsMatchIn(picked!!)) failures += "Select: $picked"
            onMain { rviz.setToolForTest(com.juancams.rviz2mini.rviz.RvizFragment.Tool.NONE) }

            // Edit properties live (point size, color, map color scheme...).
            onMain {
                for (c in rviz.displayConfigs()) {
                    val edited = when (c.type) {
                        DisplayType.LASER_SCAN -> c.with("size", "0.15").with("colorMode", "flat").with("color", "#FFEB3B")
                        DisplayType.MAP -> c.with("scheme", "costmap").with("alpha", "0.9")
                        DisplayType.POINT_CLOUD2 -> c.with("colorMode", "z").with("size", "0.05")
                        DisplayType.ODOMETRY -> c.with("shape", "axes").with("keep", "20")
                        DisplayType.GRID -> c.with("cellSize", "0.5").with("cellCount", "40")
                        DisplayType.TF -> c.with("showNames", "false")
                        else -> c
                    }
                    rviz.updateDisplayForTest(edited)
                }
            }
            SystemClock.sleep(3000)
            shot("91_editados")
            for ((type, id) in ids) {
                val s = onMainGet { rviz.displayStatus(id) }
                if (!acceptable(type, s)) failures += "(editados) ${type.label}: ${s?.level} ${s?.message}"
            }

            // Switch to a topic that does not exist: it must keep waiting, without breaking anything.
            val scanId = ids[DisplayType.LASER_SCAN]!!
            onMain { rviz.displayConfigs().first { it.id == scanId }.let { rviz.updateDisplayForTest(it.copy(topic = "/no_existe")) } }
            SystemClock.sleep(1500)
            val waiting = onMainGet { rviz.displayStatus(scanId) }
            if (waiting?.level != StatusLevel.WARN) failures += "Topic inexistente: ${waiting?.level} ${waiting?.message}"
            onMain { rviz.displayConfigs().first { it.id == scanId }.let { rviz.updateDisplayForTest(it.copy(topic = "/scan")) } }

            // Freeze (switch tab) and come back, and restart ROS: everything must recover.
            select(scenario, "config"); SystemClock.sleep(1500)
            select(scenario, tab.id); SystemClock.sleep(1000)
            RosConnection.restart()
            SystemClock.sleep(9000)
            for ((type, id) in ids) {
                val s = onMainGet { rviz.displayStatus(id) }
                Log.i(TAG, "TRAS REINICIO ${type.label}: ${s?.level} ${s?.message}")
                if (!acceptable(type, s)) failures += "(reinicio) ${type.label}: ${s?.level} ${s?.message}"
            }
            shot("92_tras_reinicio")

            // Delete every display that has a topic.
            onMain { for (id in ids.values) rviz.removeDisplayForTest(id) }
            SystemClock.sleep(500)
            assertEquals(listOf(DisplayType.GRID, DisplayType.TF), onMainGet { rviz.displayConfigs().map { it.type } })
            shot("93_borrados")

            assertTrue("Fallos:\n" + failures.joinToString("\n"), failures.isEmpty())
        }
    }

    @Test
    fun robotModelOptionsAndViews() {
        val tab = TabSpec.create(TabType.RVIZ, "RViz opciones").withSetting("displays", DisplayList.toJson(DisplayList.defaults()))
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, tab.id)
            SystemClock.sleep(4000)
            val rviz = rviz(scenario)
            val failures = ArrayList<String>()

            // ---- RobotModel: primitives + STL + DAE from the mesh server; the "head" link (no TF) warns.
            val robot = DisplayConfig.create(DisplayType.ROBOT_MODEL).with("meshUrl", meshUrl)
            onMain { rviz.addDisplayForTest(robot) }
            var s: DisplayStatus? = null
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                s = onMainGet { rviz.displayStatus(robot.id) }
                if (s?.message?.contains("meshes 2/2") == true) break
                SystemClock.sleep(300)
            }
            Log.i(TAG, "ROBOT: ${s?.level} ${s?.message}")
            val msg = s?.message.orEmpty()
            if (!msg.contains("6 links") || !msg.contains("meshes 2/2") || !msg.contains("1 without TF") || msg.contains("unsupported")) {
                failures += "RobotModel: ${s?.level} $msg"
            }
            onMain { rviz.setViewForTest(com.juancams.rviz2mini.rviz.ViewType.FOLLOW) }
            onMain { rviz.viewForTest().apply { followFrame = "base_link"; distance = 2.5; pitch = 0.45; yaw = 2.4 } }
            SystemClock.sleep(1500)
            shot("50_robot_follow")
            onMain { rviz.displayConfigs().first { it.id == robot.id }.let { rviz.updateDisplayForTest(it.with("collision", "true").with("visual", "false").with("alpha", "0.5")) } }
            SystemClock.sleep(1000)
            shot("51_robot_colision_alpha")
            onMain { rviz.displayConfigs().first { it.id == robot.id }.let { rviz.updateDisplayForTest(it.with("collision", "false").with("visual", "true").with("alpha", "1.0")) } }

            // ---- RViz-style options
            val scan = DisplayConfig.create(DisplayType.LASER_SCAN).with("style", "circles").with("size", "0.12")
                .with("alpha", "0.6").with("decay", "3.0").with("colorMode", "range")
            val path = DisplayConfig.create(DisplayType.PATH).with("lineStyle", "billboards").with("lineWidth", "0.08")
            val cloud = DisplayConfig.create(DisplayType.POINT_CLOUD2).with("colorMode", "x").with("autoBounds", "false")
                .with("minValue", "-1.5").with("maxValue", "1.5").with("style", "circles").with("size", "0.04")
            val poly = DisplayConfig.create(DisplayType.POLYGON).with("lineStyle", "billboards").with("lineWidth", "0.04")
            val markers = DisplayConfig.create(DisplayType.MARKER_ARRAY).with("meshUrl", meshUrl)
            val pose = DisplayConfig.create(DisplayType.POSE).with("shaftRadius", "0.1").with("headRadius", "0.25").with("alpha", "0.5")
            val map = DisplayConfig.create(DisplayType.MAP).with("drawBehind", "true")
            onMain {
                rviz.displayConfigs().first { it.type == DisplayType.GRID }.let {
                    rviz.updateDisplayForTest(it.with("lineStyle", "billboards").with("lineWidth", "0.02").with("color", "#00E5FF").with("alpha", "0.8"))
                }
                for (c in listOf(map, scan, path, cloud, poly, markers, pose)) rviz.addDisplayForTest(c)
            }
            SystemClock.sleep(6000)
            for (c in listOf(map, scan, path, cloud, poly, markers, pose)) {
                val st = onMainGet { rviz.displayStatus(c.id) }
                Log.i(TAG, "OPCIONES ${c.type.label}: ${st?.level} ${st?.message}")
                if (!acceptable(c.type, st)) failures += "(opciones) ${c.type.label}: ${st?.level} ${st?.message}"
            }
            val scanStatus = onMainGet { rviz.displayStatus(scan.id) }?.message.orEmpty()
            if (!scanStatus.contains("accumulated")) failures += "Decay sin efecto: $scanStatus"

            // ---- Views
            onMain { rviz.setCameraForTest(14.0, -Math.PI / 2, 0.9, 0.0, 0.0) }
            SystemClock.sleep(800); shot("60_vista_orbit_opciones")
            onMain { rviz.setViewForTest(com.juancams.rviz2mini.rviz.ViewType.TOP_DOWN); rviz.viewForTest().apply { scale = 12.0; focal[0] = 0.0; focal[1] = 0.0; angle = 0.0 } }
            SystemClock.sleep(800); shot("61_vista_topdown")
            onMain { rviz.viewForTest().twist(Math.PI / 4) }
            SystemClock.sleep(500); shot("62_vista_topdown_girada")
            onMain { rviz.setViewForTest(com.juancams.rviz2mini.rviz.ViewType.FPS); rviz.viewForTest().apply { position[0] = -5.5; position[1] = -4.5; position[2] = 1.6; fpsYaw = 0.7; fpsPitch = -0.15 } }
            SystemClock.sleep(800); shot("63_vista_fps")
            onMain { rviz.setViewForTest(com.juancams.rviz2mini.rviz.ViewType.XY_ORBIT) }
            SystemClock.sleep(800); shot("64_vista_xyorbit")
            onMain { rviz.setBackgroundForTest("#B0B0B0") }
            SystemClock.sleep(600); shot("65_fondo_claro")

            // The chosen view is saved per tab.
            scenario.recreate()
            SystemClock.sleep(2500)
            select(scenario, tab.id)
            SystemClock.sleep(800)
            val type = onMainGet { rviz(scenario).viewForTest().type }
            if (type != com.juancams.rviz2mini.rviz.ViewType.XY_ORBIT) failures += "La vista no se ha guardado: $type"

            assertTrue("Fallos:\n" + failures.joinToString("\n"), failures.isEmpty())
        }
    }

    // ------------------------------------------------------------------ utilities

    /**
     * A message that cannot be read puts the display in ERROR, but only while it is the latest one:
     * it recovers with the following good ones (a while with a warning and then OK). Needs on the PC
     * `python3 tools/bad_cloud_pub.py` (12 s cycles: good, 2 s broken, good).
     */
    @Test
    fun badMessageDoesNotLeaveTheDisplayInError() {
        val tab = TabSpec.create(TabType.RVIZ, "RViz test").withSetting("displays", DisplayList.toJson(emptyList()))
        TabStore.get().add(tab)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                select(scenario, tab.id)
                val rviz = rviz(scenario)
                val c = DisplayConfig.create(DisplayType.POINT_CLOUD2).copy(topic = InstrumentationRegistry.getArguments().getString("cloud_topic") ?: "/rviz2mini_test/bad_cloud")
                onMain { rviz.addDisplayForTest(c) }
                // What is shown, in order and without repeats: it has to go through OK → ERROR → warning → OK.
                val seen = ArrayList<String>()
                val deadline = SystemClock.uptimeMillis() + 40_000
                val want = listOf("OK", "ERROR", "RECOVERING", "OK")
                while (SystemClock.uptimeMillis() < deadline) {
                    val st = onMainGet { rviz.displayStatus(c.id) }
                    val k = when {
                        st == null -> "NONE"
                        st.level == StatusLevel.WARN && st.message.contains("could not be read") -> "RECOVERING"
                        else -> st.level.name
                    }
                    if (seen.lastOrNull() != k) { seen += k; Log.i(TAG, "estado: ${st?.level} ${st?.message}") }
                    if (seen.windowed(4).any { it == want }) break
                    SystemClock.sleep(100)
                }
                Log.i(TAG, "estados vistos: $seen")
                assertTrue("no se recupera tras un mensaje malo: $seen", seen.windowed(4).any { it == want })
            }
        } finally {
            TabStore.get().remove(tab.id)
        }
    }

    private fun waitStatus(rviz: RvizFragment, id: String, type: DisplayType, timeoutMs: Long): DisplayStatus? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var s: DisplayStatus? = null
        while (SystemClock.uptimeMillis() < deadline) {
            s = onMainGet { rviz.displayStatus(id) }
            if (acceptable(type, s)) {
                SystemClock.sleep(1200) // let a couple more messages arrive for the screenshot
                return onMainGet { rviz.displayStatus(id) }
            }
            SystemClock.sleep(200)
        }
        return s
    }

    private fun measureFps(ms: Long): Double {
        val frames = AtomicInteger()
        val start = SystemClock.uptimeMillis()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                frames.incrementAndGet()
                if (SystemClock.uptimeMillis() - start < ms) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        onMain { Choreographer.getInstance().postFrameCallback(cb) }
        SystemClock.sleep(ms + 100)
        return frames.get() * 1000.0 / ms
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> onMainGet(block: () -> T): T {
        var r: T? = null
        instrumentation.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
        instrumentation.waitForIdleSync()
    }

    private fun rviz(scenario: ActivityScenario<MainActivity>): RvizFragment {
        var f: RvizFragment? = null
        // Wait for the viewer to be set up (materials are prepared in the background).
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (true) {
            var ready = false
            scenario.onActivity { a ->
                f = a.supportFragmentManager.fragments.filterIsInstance<RvizFragment>().first { it.isVisible }
                ready = f!!.isReadyForTest
            }
            if (ready || SystemClock.uptimeMillis() > deadline) break
            SystemClock.sleep(100)
        }
        return f!!
    }

    private companion object { const val TAG = "RvizDisplays" }
}
