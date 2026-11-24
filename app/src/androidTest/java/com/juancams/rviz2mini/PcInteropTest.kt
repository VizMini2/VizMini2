// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.camera.CameraStream
import com.juancams.rviz2mini.camera.ImageKind
import com.juancams.rviz2mini.camera.ImageTopic
import com.juancams.rviz2mini.camera.ImageTopics
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sensor_msgs.CompressedImage
import sensor_msgs.Image
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Interoperability with a real ROS 2 on ANOTHER machine on the network (not part of the normal
 * suite). Run with:
 *   adb shell am instrument -w -e pc true -e class com.juancams.rviz2mini.PcInteropTest \
 *       com.juancams.vizmini2.test/com.juancams.rviz2mini.TestRunner
 * with, on the PC (ROS_DOMAIN_ID=77):  ros2 topic echo /cmd_vel,
 *   ros2 run image_tools cam2image --ros-args -p burger_mode:=true,
 *   ros2 run tf2_ros static_transform_publisher --x 1 --y 2 --yaw 1.5707963 --frame-id map --child-frame-id odom
 */
@RunWith(AndroidJUnit4::class)
class PcInteropTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        assumeTrue("Solo con -e pc true", InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_phone", discoveryPeers = TestArgs.peers))
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
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

    @Test
    fun teleopToPc() {
        val tab = TabSpec.create(TabType.TELEOP, "Teleop PC").withSetting("topic", "/cmd_vel")
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, tab.id)
            SystemClock.sleep(4000) // DDS discovery over WiFi
            val joystick = joystick(scenario)
            val cx = joystick.width / 2f
            val cy = joystick.height / 2f
            val r = minOf(joystick.width, joystick.height) / 2f * 0.7f
            val down = SystemClock.uptimeMillis()
            touch(joystick, MotionEvent.ACTION_DOWN, cx, cy, down)
            val end = SystemClock.uptimeMillis() + 3000
            while (SystemClock.uptimeMillis() < end) {
                touch(joystick, MotionEvent.ACTION_MOVE, cx + 0.5f * r, cy - 0.8f * r, down)
                SystemClock.sleep(50)
            }
            touch(joystick, MotionEvent.ACTION_UP, cx + 0.5f * r, cy - 0.8f * r, down)
            SystemClock.sleep(1500)
        }
    }

    @Test
    fun cameraFromPc() {
        // Real probe with the app's candidates.
        val probeDone = CountDownLatch(1)
        var found: List<RosConnection.ProbeRequest<*>> = emptyList()
        val requests = ImageTopics.candidates().map {
            if (it.kind == ImageKind.RAW) RosConnection.ProbeRequest(it.name, Image::class.java)
            else RosConnection.ProbeRequest(it.name, CompressedImage::class.java)
        }
        RosConnection.probe(requests, 4000) { found = it; probeDone.countDown() }
        assertTrue(probeDone.await(60, TimeUnit.SECONDS))
        Log.i(TAG, "PROBE encontrados: ${found.map { it.topicName + ":" + it.type.simpleName }}")
        assertTrue("No se encontró /image", found.any { it.topicName == "/image" && it.type == Image::class.java })

        var frames = 0
        var last: CameraStream.FrameInfo? = null
        val first = CountDownLatch(1)
        val stream = CameraStream(ImageTopic("/image", ImageKind.RAW), object : CameraStream.Listener {
            override fun onFrame(bitmap: Bitmap, info: CameraStream.FrameInfo) { frames++; last = info; first.countDown() }
            override fun onStreamError(message: String) { Log.e(TAG, "CAMERA error: $message") }
        })
        instrumentation.runOnMainSync { stream.start() }
        assertTrue("No llegan imágenes del PC", first.await(15, TimeUnit.SECONDS))
        SystemClock.sleep(5000)
        instrumentation.runOnMainSync { stream.stop() }
        Log.i(TAG, "CAMERA frames=$frames info=$last")
    }

    @Test
    fun tfFromPc() {
        SystemClock.sleep(5000)
        val tree = RosConnection.tfTree
        tree.compute("map")
        val out = DoubleArray(16)
        Log.i(TAG, "TF frames=${tree.frameIds()}")
        assertTrue(tree.poseInFixed("odom", out))
        Log.i(TAG, "TF odom en map: (%.3f, %.3f, %.3f) R00=%.3f R10=%.3f".format(out[12], out[13], out[14], out[0], out[1]))
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
        instrumentation.waitForIdleSync()
    }

    /** The visible Teleop tab joystick (shared screen, Compose; see TestJoystick). */
    private fun joystick(scenario: ActivityScenario<MainActivity>): TestJoystick = TestJoystick.sticks(scenario).first

    @Suppress("UNUSED_PARAMETER")
    private fun touch(view: TestJoystick, action: Int, x: Float, y: Float, downTime: Long) = view.touch(action, x, y)

    private companion object {
        const val TAG = "PcInterop"
    }
}
