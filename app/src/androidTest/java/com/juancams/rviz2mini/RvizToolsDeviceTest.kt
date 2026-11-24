// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.ViewType
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import geometry_msgs.PoseStamped
import geometry_msgs.PoseWithCovarianceStamped
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2QoSProfile
import us.ihmc.jros2.ROS2Topic
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.atan2

/** "2D Goal Pose", "2D Pose Estimate" and "Publish Point": gesture on the 3D view -> message received by a node; and "Measure". */
@RunWith(AndroidJUnit4::class)
class RvizToolsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var nav: ROS2Node
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_tools_test"))
        nav = RosConnection.createNode("fake_nav2", 77)
    }

    @After
    fun tearDown() {
        RosConnection.closeNode(nav)
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun goalAndPoseEstimateFromGesture() {
        val goals = CountDownLatch(1); var goal: PoseStamped? = null
        val poses = CountDownLatch(1); var pose: PoseWithCovarianceStamped? = null
        val points = CountDownLatch(1); var point: geometry_msgs.PointStamped? = null
        val gm = PoseStamped(); val pm = PoseWithCovarianceStamped(); val cm = geometry_msgs.PointStamped()
        nav.createSubscription(ROS2Topic(POINT, geometry_msgs.PointStamped::class.java), { r -> if (r.read(cm)) { point = geometry_msgs.PointStamped().also { it.set(cm) }; points.countDown() } }, ROS2QoSProfile())
        val gs = nav.createSubscription(ROS2Topic(GOAL, PoseStamped::class.java), { r -> if (r.read(gm)) { goal = PoseStamped().also { it.set(gm) }; goals.countDown() } }, ROS2QoSProfile())
        nav.createSubscription(ROS2Topic(POSE, PoseWithCovarianceStamped::class.java), { r -> if (r.read(pm)) { pose = PoseWithCovarianceStamped().also { it.set(pm) }; poses.countDown() } }, ROS2QoSProfile())

        val tab = TabSpec.create(TabType.RVIZ, "Tools").withSetting("displays", DisplayList.toJson(DisplayList.defaults()))
            .withSetting("goalTopic", GOAL).withSetting("poseTopic", POSE).withSetting(RvizFragment.KEY_POINT_TOPIC, POINT)
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
            }
            SystemClock.sleep(2500)
            var rviz: RvizFragment? = null
            val deadline = SystemClock.uptimeMillis() + 60_000
            do {
                var ready = false
                sc.onActivity { a ->
                    rviz = a.supportFragmentManager.fragments.filterIsInstance<RvizFragment>().first { it.isVisible }
                    ready = rviz!!.isReadyForTest
                }
                if (!ready) SystemClock.sleep(100)
            } while (!ready && SystemClock.uptimeMillis() < deadline)
            instrumentation.runOnMainSync { rviz!!.setViewForTest(ViewType.TOP_DOWN); rviz!!.viewForTest().apply { scale = 10.0; focal[0] = 0.0; focal[1] = 0.0; angle = 0.0 } }
            SystemClock.sleep(500)
            val surface = rviz!!.surfaceForTest()
            val w = surface.width.toFloat(); val h = surface.height.toFloat()

            // Goal: tap a point and drag up-right (45°).
            instrumentation.runOnMainSync { rviz!!.setToolForTest(RvizFragment.Tool.GOAL) }
            val sx = w * 0.6f; val sy = h * 0.4f
            gesture(surface, sx, sy, sx + 150f, sy - 150f)
            val expected = DoubleArray(3)
            instrumentation.runOnMainSync { rviz!!.viewForTest().groundPoint(sx, sy, w, h, expected) }
            assertTrue("El suscriptor no empareja", gs.waitForPublisher(10_000))
            assertTrue("No llegó el goal", goals.await(10, TimeUnit.SECONDS))
            val g = goal!!
            assertEquals(expected[0], g.pose.position.x, 0.05); assertEquals(expected[1], g.pose.position.y, 0.05)
            val yaw = 2 * atan2(g.pose.orientation.z, g.pose.orientation.w)
            assertEquals("Orientación 45°", Math.PI / 4, yaw, 0.1)
            assertEquals("map", g.header.frameIdAsString)

            // Pose estimate: drag down (-90°) and check the RViz covariance.
            instrumentation.runOnMainSync { rviz!!.setToolForTest(RvizFragment.Tool.POSE_ESTIMATE) }
            gesture(surface, w * 0.3f, h * 0.5f, w * 0.3f, h * 0.5f + 200f)
            assertTrue("No llegó la pose", poses.await(10, TimeUnit.SECONDS))
            val p = pose!!
            assertEquals(-Math.PI / 2, 2 * atan2(p.pose.pose.orientation.z, p.pose.pose.orientation.w), 0.1)
            assertEquals(0.25, p.pose.covariance[0], 1e-9); assertEquals(0.25, p.pose.covariance[7], 1e-9)
            assertEquals(0.06853891945200942, p.pose.covariance[35], 1e-12)

            // Publish Point: a tap -> PointStamped on the ground, in the fixed frame.
            instrumentation.runOnMainSync { rviz!!.setToolForTest(RvizFragment.Tool.PUBLISH_POINT) }
            val px = w * 0.7f; val py = h * 0.6f
            gesture(surface, px, py, px, py)
            val pe = DoubleArray(3)
            instrumentation.runOnMainSync { rviz!!.viewForTest().groundPoint(px, py, w, h, pe) }
            assertTrue("No llegó el punto", points.await(10, TimeUnit.SECONDS))
            val c = point!!
            assertEquals(pe[0], c.point.x, 0.05); assertEquals(pe[1], c.point.y, 0.05); assertEquals(0.0, c.point.z, 1e-9)
            assertEquals("map", c.header.frameIdAsString)

            // Measure: drag from A to B; the measurement stays until the tool is removed.
            instrumentation.runOnMainSync { rviz!!.setToolForTest(RvizFragment.Tool.MEASURE) }
            val ax = w * 0.2f; val ay = h * 0.3f; val bx = w * 0.8f; val by = h * 0.7f
            gesture(surface, ax, ay, bx, by)
            val a = DoubleArray(3); val b = DoubleArray(3)
            instrumentation.runOnMainSync { rviz!!.viewForTest().groundPoint(ax, ay, w, h, a); rviz!!.viewForTest().groundPoint(bx, by, w, h, b) }
            var text: String? = null
            instrumentation.runOnMainSync { text = rviz!!.measureForTest() }
            android.util.Log.i("RvizTools", "medida: $text")
            assertEquals(com.juancams.rviz2mini.rviz.geom.Measure.text(a, b), text)
            // The counter is rebuilt every frame: it is read on the main thread, between two frames.
            var labels = 0
            val until = SystemClock.uptimeMillis() + 2000
            while (labels < 1 && SystemClock.uptimeMillis() < until) {
                SystemClock.sleep(100)
                instrumentation.runOnMainSync { labels = rviz!!.labelCountForTest() }
            }
            assertTrue("sin etiqueta de la medida", labels >= 1)
            val shot = instrumentation.uiAutomation.takeScreenshot()
            if (shot != null) java.io.File(context.getExternalFilesDir(null), "rviz_measure.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            instrumentation.runOnMainSync { rviz!!.setToolForTest(RvizFragment.Tool.NONE) }
            instrumentation.runOnMainSync { text = rviz!!.measureForTest() }
            assertEquals(null, text)
        }
    }

    private fun gesture(view: android.view.View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) = instrumentation.runOnMainSync {
            val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            view.dispatchTouchEvent(ev); ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x0, y0)
        for (i in 1..10) { send(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10); SystemClock.sleep(20) }
        send(MotionEvent.ACTION_UP, x1, y1)
    }

    private companion object {
        const val GOAL = "/rviz2mini_test/goal_pose"
        const val POSE = "/rviz2mini_test/initialpose"
        const val POINT = "/rviz2mini_test/clicked_point"
    }
}
