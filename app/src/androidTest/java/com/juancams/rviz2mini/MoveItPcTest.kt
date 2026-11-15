// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.dashboard.DashboardFragment
import com.juancams.rviz2mini.dashboard.DashboardGrid
import com.juancams.rviz2mini.moveit.MoveItClient
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.DASHBOARD_KEY_LAYOUT
import com.juancams.rviz2mini.ui.screens.MoveItScreens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * MoveIt against the Panda from `tools/moveit_panda_headless.launch.py` on the PC (domain 77, `-e pc true`; meshes with
 * tools/mesh_server.py): the panel reads groups, states and joints; "extended" + Plan and execute moves the
 * arm; "ready" + Plan leaves the plan, which the 3D Trajectory display shows, and Execute brings it to "ready".
 * Screenshots in files/moveit/.
 */
@RunWith(AndroidJUnit4::class)
class MoveItPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir = File(context.getExternalFilesDir(null), "moveit").apply { deleteRecursively(); mkdirs() }
    private val savedTabs = TestTabs.backup(context)
    private val savedRos = prefs("rviz2mini_ros").all

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply { savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } } }.commit()
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!ok() && SystemClock.uptimeMillis() < end) SystemClock.sleep(200)
        assertTrue(what, ok())
    }

    @Test
    fun planAndExecuteThePanda() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true" && InstrumentationRegistry.getArguments().getString("clips") != "true")
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_moveit", discoveryPeers = TestArgs.peers))
        val mesh = "http://${TestArgs.pcIp}:8000"
        val traj = DisplayConfig.create(DisplayType.TRAJECTORY, "Planned path").with("meshUrl", mesh)
        val scene = DisplayConfig.create(DisplayType.PLANNING_SCENE, "Scene")
        val dash = TabSpec.create(TabType.DASHBOARD, "MoveIt")
        val rviz = TabSpec.create(TabType.RVIZ, "3D").withSetting(TabSpec.KEY_DASHBOARD, dash.id).withSetting("fixedFrame", "world")
            .withSetting("displays", DisplayList.toJson(listOf(DisplayConfig.create(DisplayType.GRID),
                DisplayConfig.create(DisplayType.ROBOT_MODEL, "Robot", "/robot_description").with("meshUrl", mesh), traj, scene)))
            .withSetting("view", """{"type":"ORBIT","distance":"2.2","yaw":"0.6","pitch":"0.45","fx":"0","fy":"0","fz":"0.4"}""")
        val panel = TabSpec.create(TabType.MOVEIT, "MoveIt").withSetting(TabSpec.KEY_DASHBOARD, dash.id)
        TabStore.get().add(dash.withSetting(DASHBOARD_KEY_LAYOUT, DashboardGrid.toJson(listOf(
            DashboardGrid.Item(rviz.id, 0, 0, 12, 7), DashboardGrid.Item(panel.id, 0, 7, 12, 12)))))
        TabStore.get().add(rviz); TabStore.get().add(panel)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.openTab(dash.id) }
            waitFor("el panel de MoveIt no aparece", 20_000) { MoveItScreens.clients[panel.id] != null }
            val c: MoveItClient = MoveItScreens.clients[panel.id]!!
            waitFor("sin URDF/SRDF de move_group", 60_000) { c.srdf != null && c.urdf != null }
            waitFor("sin /joint_states") { c.hasJointStates }
            Log.i(TAG, "grupos ${c.groups()} · estados ${c.namedStates()} · articulaciones ${c.groupJoints()}")
            assertEquals("panda_arm", c.group)
            assertEquals((1..7).map { "panda_joint$it" }, c.groupJoints())
            assertTrue(c.namedStates().containsAll(listOf("ready", "extended")))
            SystemClock.sleep(1500)
            Ui.shot(dir, "1_panel")

            // extended + Plan and execute: the arm stretches out (joint4 to 0).
            Ui.click("extended")
            Ui.click(context.getString(R.string.moveit_plan_execute))
            waitFor("no acabó de moverse: ${c.status}", 40_000) { !c.busy && c.status.orEmpty().startsWith(context.getString(R.string.moveit_done).substringBefore(':')) }
            SystemClock.sleep(500)
            Log.i(TAG, "tras extended: ${c.status} · joint4 ${c.current["panda_joint4"]}")
            assertEquals(0.0, c.current["panda_joint4"]!!, 0.05)

            // ready + Plan: the plan remains and the 3D view shows it with the ghost robot; Execute brings it to ready.
            Ui.click("ready")
            Ui.click(context.getString(R.string.moveit_plan))
            waitFor("no planificó: ${c.status}", 30_000) { !c.busy && c.lastPlan != null }
            SystemClock.sleep(2500)
            var trajStatus = ""
            sc.onActivity { a ->
                val d = a.supportFragmentManager.findFragmentByTag(dash.id) as DashboardFragment
                val r = d.childFragmentManager.findFragmentByTag(rviz.id) as RvizFragment
                trajStatus = r.displayStatus(traj.id)?.message.orEmpty()
            }
            Log.i(TAG, "plan: ${c.status} · Trajectory: $trajStatus")
            Ui.shot(dir, "2_planned")
            assertTrue("el display Trajectory no tiene la trayectoria: $trajStatus", trajStatus.contains("points"))
            Ui.click(context.getString(R.string.moveit_execute))
            waitFor("no ejecutó: ${c.status}", 40_000) { !c.busy && c.status == context.getString(R.string.moveit_executed) }
            SystemClock.sleep(500)
            Log.i(TAG, "tras ready: joint4 ${c.current["panda_joint4"]}")
            assertEquals(-2.356, c.current["panda_joint4"]!!, 0.05)
            Ui.shot(dir, "3_ready")

            // Move the gripper 5 cm down twice with IK (/compute_ik): the goal changes, not the robot.
            val z0 = c.tipPose()!![14]
            Ui.click("1 cm · 5°")
            repeat(2) {
                Ui.click("Z\u2212") // the button text uses a real minus sign
                SystemClock.sleep(300)
                waitFor("el IK no terminó: ${c.status}", 10_000) { !c.busy }
            }
            val z1 = c.tipPose()!![14]
            Log.i(TAG, "IK: punta de z=$z0 a z=$z1 · ${c.status}")
            assertEquals("la punta del objetivo baja 10 cm", z0 - 0.10, z1, 0.005)
            assertEquals("el robot no se mueve aún", -2.356, c.current["panda_joint4"]!!, 0.05)

            // An obstacle between the gripper and a distant goal (extended): it shows in the 3D view (PlanningScene) and the plan avoids it.
            // The scene already has (tools/moveit_scene_demo.py) a mesh on the floor and a box held by the hand.
            fun sceneText(): String {
                // The 3D view must be visible (off screen its displays are turned off): the dashboard, at the very top.
                Ui.scrollToTop()
                var t = ""
                sc.onActivity { a ->
                    val d = a.supportFragmentManager.findFragmentByTag(dash.id) as DashboardFragment
                    val r = d.childFragmentManager.findFragmentByTag(rviz.id) as RvizFragment
                    t = r.displayStatus(scene.id)?.message.orEmpty()
                }
                return t
            }
            waitFor("la escena no trae la malla y el objeto agarrado: ${sceneText()}", 20_000) { sceneText().startsWith("1 objects · 1 attached") }
            Ui.click("extended")
            SystemClock.sleep(300)
            Ui.click(context.getString(R.string.moveit_add_box))
            waitFor("no se añadió el obstáculo: ${c.status}", 10_000) { !c.busy && c.boxes.isNotEmpty() }
            waitFor("el 3D no enseña el obstáculo: ${sceneText()}", 15_000) { sceneText().startsWith("2 objects") }
            val sceneStatus = sceneText()
            Ui.click(context.getString(R.string.moveit_plan_execute))
            waitFor("no se movió con el obstáculo: ${c.status}", 60_000) { !c.busy && c.status.orEmpty().startsWith(context.getString(R.string.moveit_done).substringBefore(':')) }
            Log.i(TAG, "con obstáculo: ${c.status} · Scene: $sceneStatus")
            Ui.shot(dir, "4_obstacle")
            assertEquals("el robot llega a extended", 0.0, c.current["panda_joint4"]!!, 0.05)
            Ui.click(context.getString(R.string.moveit_clear_boxes, 1))
            waitFor("no se quitó el obstáculo: ${c.status}", 10_000) { !c.busy && c.boxes.isEmpty() }

            // In a straight line: the tip goes down 5 cm directly (/compute_cartesian_path) and it is executed.
            Ui.click("ready"); SystemClock.sleep(300)
            Ui.click(context.getString(R.string.moveit_plan_execute))
            waitFor("no volvió a ready: ${c.status}", 60_000) { !c.busy && c.status.orEmpty().startsWith(context.getString(R.string.moveit_done).substringBefore(':')) }
            Ui.click(context.getString(R.string.moveit_straight_line))
            assertTrue(c.cartesian)
            val zr = c.tipPose()!![14]
            Ui.click("Z\u2212"); SystemClock.sleep(300); waitFor("IK", 10_000) { !c.busy }
            Ui.click(context.getString(R.string.moveit_plan_execute))
            waitFor("no hizo la línea recta: ${c.status}", 60_000) { !c.busy && c.status.orEmpty().startsWith(context.getString(R.string.moveit_line_done).substringBefore(':')) }
            SystemClock.sleep(500)
            Log.i(TAG, "línea recta: ${c.status} · z $zr -> ${c.tipPose()!![14]}")
            Ui.shot(dir, "5_line")
            assertEquals("bajó en línea recta", zr - 0.05, c.tipPose()!![14], 0.01)
            Ui.click(context.getString(R.string.moveit_straight_line))
            assertTrue(!c.cartesian)

            // Another planner (Pilz, PTP) among those move_group reports.
            Ui.click(context.getString(R.string.moveit_options))
            waitFor("move_group no dijo sus planificadores", 10_000) { c.planners.isNotEmpty() }
            Log.i(TAG, "planificadores: ${c.planners}")
            Ui.click(context.getString(R.string.moveit_planner, context.getString(R.string.moveit_planner_default)))
            Ui.click("pilz_industrial_motion_planner · PTP")
            assertEquals("PTP", c.plannerId)
            Ui.click("ready"); SystemClock.sleep(300)
            Ui.click(context.getString(R.string.moveit_plan_execute))
            waitFor("no se movió con PTP: ${c.status}", 60_000) { !c.busy && c.status.orEmpty().startsWith(context.getString(R.string.moveit_done).substringBefore(':')) }
            SystemClock.sleep(500)
            Log.i(TAG, "PTP: ${c.status}")
            assertEquals(-2.356, c.current["panda_joint4"]!!, 0.05)

            // Back to the usual planner (Pilz has no gripper configured on this Panda).
            Ui.click(context.getString(R.string.moveit_planner, "pilz_industrial_motion_planner · PTP"))
            Ui.click(context.getString(R.string.moveit_planner_default))
            assertEquals("", c.plannerId)

            // The gripper: group "hand", SRDF state "open" (MoveIt moves it with GripperCommand).
            Ui.click(context.getString(R.string.moveit_group, "panda_arm"))
            Ui.click("hand")
            waitFor("no cambió al grupo hand", 5_000) { c.group == "hand" }
            assertTrue("hand sin estados: ${c.namedStates()}", c.namedStates().contains("open"))
            Ui.click("open"); SystemClock.sleep(300)
            Ui.click(context.getString(R.string.moveit_plan_execute))
            waitFor("no abrió la pinza: ${c.status}", 40_000) { !c.busy && c.status.orEmpty().startsWith(context.getString(R.string.moveit_done).substringBefore(':')) }
            SystemClock.sleep(800)
            Log.i(TAG, "pinza: ${c.status} · dedo ${c.current["panda_finger_joint1"]}")
            Ui.shot(dir, "6_gripper")
            assertEquals("la pinza abierta", 0.035, c.current["panda_finger_joint1"]!!, 0.005)

            // Drag the gripper's x arrow in the 3D view (MoveIt tool): the goal moves with IK.
            Ui.click(context.getString(R.string.moveit_group, "hand"))
            Ui.click("panda_arm")
            waitFor("no volvió al brazo", 5_000) { c.group == "panda_arm" }
            fun rvizFrag(): RvizFragment {
                var r: RvizFragment? = null
                sc.onActivity { a -> r = (a.supportFragmentManager.findFragmentByTag(dash.id) as DashboardFragment).childFragmentManager.findFragmentByTag(rviz.id) as RvizFragment }
                return r!!
            }
            val r = rvizFrag()
            instrumentation.runOnMainSync { r.setToolForTest(RvizFragment.Tool.MOVEIT_GOAL) }
            SystemClock.sleep(800)
            var start: FloatArray? = null; var dirv: FloatArray? = null
            instrumentation.runOnMainSync { start = r.toolsForTest()?.handleScreenPoint(0, false); dirv = r.toolsForTest()?.handleScreenDirection(0) }
            assertTrue("no se ve la flecha", start != null && dirv != null)
            val before = c.tipPose()!!.copyOf()
            val surface = r.surfaceForTest()
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) = instrumentation.runOnMainSync {
                val ev = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                surface.dispatchTouchEvent(ev); ev.recycle()
            }
            val (sx, sy) = start!!.let { it[0] to it[1] }
            send(android.view.MotionEvent.ACTION_DOWN, sx, sy)
            for (i in 1..10) { send(android.view.MotionEvent.ACTION_MOVE, sx + dirv!![0] * 6f * i, sy + dirv!![1] * 6f * i); SystemClock.sleep(20) }
            send(android.view.MotionEvent.ACTION_UP, sx + dirv!![0] * 60f, sy + dirv!![1] * 60f)
            waitFor("el IK del arrastre no terminó: ${c.status}", 10_000) { !c.busy }
            SystemClock.sleep(500)
            val after = c.tipPose()!!
            val moved = Math.sqrt((0..2).sumOf { (after[12 + it] - before[12 + it]).let { d -> d * d } })
            Log.i(TAG, "arrastre: la punta se movió %.3f m · ${c.status}".format(moved))
            Ui.shot(dir, "7_drag")
            assertTrue("arrastrar la flecha no movió el objetivo ($moved m)", moved > 0.01)
            instrumentation.runOnMainSync { r.setToolForTest(RvizFragment.Tool.NONE) }
        }
    }

    /**
     * The catalog video (not part of the suite: `-e clips true -e lang en|es`, with the app language already set): the 3D view
     * with the Panda, the blue goal at "extended", the orange plan and the arm moving, and back to "ready".
     * Leaves /sdcard/clips/<lang>/clip_moveit.mp4 (screen at 610 × 1356; tools/catalog_clips/encode.sh crops it).
     */
    @Test
    fun clip() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("clips") == "true" && args.getString("pc") == "true")
        val lang = args.getString("lang") ?: "en"
        // The app language from the test itself (like CatalogClips); at the end, the previous one.
        val savedLang = AppLanguage.current()
        instrumentation.runOnMainSync { AppLanguage.set(lang) }
        try { recordClip(lang) } finally { instrumentation.runOnMainSync { AppLanguage.set(savedLang) } }
    }

    private fun recordClip(lang: String) {
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_moveit", discoveryPeers = TestArgs.peers))
        val mesh = "http://${TestArgs.pcIp}:8000"
        val dash = TabSpec.create(TabType.DASHBOARD, "MoveIt")
        val rviz = TabSpec.create(TabType.RVIZ, "3D").withSetting(TabSpec.KEY_DASHBOARD, dash.id).withSetting("fixedFrame", "world")
            .withSetting("displays", DisplayList.toJson(listOf(DisplayConfig.create(DisplayType.GRID).with("cellSize", "0.25"),
                DisplayConfig.create(DisplayType.ROBOT_MODEL, "Robot", "/robot_description").with("meshUrl", mesh),
                DisplayConfig.create(DisplayType.TRAJECTORY, "Planned path").with("meshUrl", mesh), DisplayConfig.create(DisplayType.PLANNING_SCENE, "Scene"))))
            .withSetting("view", """{"type":"ORBIT","distance":"1.9","yaw":"0.7","pitch":"0.4","fx":"0.15","fy":"0","fz":"0.45"}""")
        val panel = TabSpec.create(TabType.MOVEIT, "MoveIt").withSetting(TabSpec.KEY_DASHBOARD, dash.id)
        TabStore.get().add(dash.withSetting(DASHBOARD_KEY_LAYOUT, DashboardGrid.toJson(listOf(
            DashboardGrid.Item(rviz.id, 0, 0, 12, 13), DashboardGrid.Item(panel.id, 0, 13, 12, 12)))))
        TabStore.get().add(rviz); TabStore.get().add(panel)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.openTab(dash.id) }
            waitFor("sin panel", 20_000) { MoveItScreens.clients[panel.id] != null }
            val c = MoveItScreens.clients[panel.id]!!
            waitFor("sin MoveIt", 60_000) { c.srdf != null && c.urdf != null && c.hasJointStates }
            instrumentation.runOnMainSync { c.applyNamedState("ready") }
            c.planAndExecute(); waitFor("a ready", 40_000) { !c.busy }
            SystemClock.sleep(6000) // the meshes
            val automation = instrumentation.uiAutomation
            val proc = automation.executeShellCommand("screenrecord --size 610x1356 --bit-rate 6000000 --time-limit 14 /sdcard/clips/$lang/clip_moveit.mp4")
            SystemClock.sleep(1200)
            instrumentation.runOnMainSync { c.applyNamedState("extended") }
            SystemClock.sleep(1500)
            c.plan(); waitFor("plan", 20_000) { !c.busy }
            SystemClock.sleep(4500) // the orange plan, looping
            c.execute(); waitFor("ejecutar", 30_000) { !c.busy }
            SystemClock.sleep(800)
            instrumentation.runOnMainSync { c.applyNamedState("ready") }
            c.planAndExecute(); waitFor("vuelta", 40_000) { !c.busy }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(proc).use { it.readBytes() }
            Log.i(TAG, "clip: /sdcard/clips/$lang/clip_moveit.mp4")
        }
    }

    companion object {
        private const val TAG = "MoveItPcTest"
    }
}
