// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import geometry_msgs.Twist
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2QoSProfile
import us.ihmc.jros2.ROS2Subscription
import us.ihmc.jros2.ROS2Topic
import java.util.concurrent.CopyOnWriteArrayList

/** Teleop with 1 and 2 joysticks, safety (watchdog, subscriber loss) and physical gamepad. */
@RunWith(AndroidJUnit4::class)
class TeleopDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var robot: ROS2Node
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null
    private val received = CopyOnWriteArrayList<Twist>()
    private lateinit var sub: ROS2Subscription<Twist>

    @Before
    fun setUp() {
        savedTabs = TestTabs.backup(context)
        savedRos = prefs("rviz2mini_ros").all
        RosConnection.applyConfig(RosConfig(domainId = 77, nodeName = "rviz2mini_teleop_test"))
        robot = RosConnection.createNode("fake_base", 77)
        sub = subscribe()
    }

    private fun subscribe(): ROS2Subscription<Twist> {
        val msg = Twist()
        return robot.createSubscription(ROS2Topic(TOPIC, Twist::class.java), { r ->
            if (r.read(msg)) received.add(Twist().also { it.set(msg) })
        }, ROS2QoSProfile())
    }

    @After
    fun tearDown() {
        RosConnection.closeNode(robot)
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun dualJoysticksDriveAndTurn() {
        val tab = TabSpec.create(TabType.TELEOP_DUAL, "Dual").withSetting("topic", TOPIC)
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            select(sc, tab.id)
            assertTrue(sub.waitForPublisher(10_000))
            val (left, r) = TestJoystick.sticks(sc)
            val right = r ?: throw AssertionError("Sin joystick derecho")
            val down = SystemClock.uptimeMillis()
            left.touch(MotionEvent.ACTION_DOWN, left.width / 2f, left.height / 2f)
            right.touch(MotionEvent.ACTION_DOWN, right.width / 2f, right.height / 2f)
            hold(1200) {
                left.touch(MotionEvent.ACTION_MOVE, left.width / 2f, left.height * 0.1f)   // left one up
                right.touch(MotionEvent.ACTION_MOVE, right.width * 0.9f, right.height / 2f) // right one to the right
            }
            // While driving, the settings cannot be changed.
            assertFalse(settingsEnabled())
            left.touch(MotionEvent.ACTION_UP, 0f, 0f); right.touch(MotionEvent.ACTION_UP, 0f, 0f)
            SystemClock.sleep(800)
            val moving = received.filter { it.linear.x != 0.0 || it.angular.z != 0.0 }
            assertTrue("Sin movimiento: ${received.size}", moving.size > 5)
            val bad = moving.drop(2).filterNot { it.linear.x > 0.3 && it.angular.z < -0.5 && it.linear.y == 0.0 }
            assertTrue("Fuera de rango (${bad.size}/${moving.size}): " + bad.take(6).joinToString { "x=%.2f y=%.2f z=%.2f".format(it.linear.x, it.linear.y, it.angular.z) }, bad.isEmpty())
            assertTrue(received.last().linear.x == 0.0 && received.last().angular.z == 0.0)
            assertTrue(settingsEnabled())
        }
    }

    @Test
    fun watchdogStopsWhenUiFreezes() {
        val tab = TabSpec.create(TabType.TELEOP, "T").withSetting("topic", TOPIC)
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            select(sc, tab.id)
            assertTrue(sub.waitForPublisher(10_000))
            val (left, _) = TestJoystick.sticks(sc)
            val down = SystemClock.uptimeMillis()
            left.touch(MotionEvent.ACTION_DOWN, left.width / 2f, left.height / 2f)
            hold(600) { left.touch(MotionEvent.ACTION_MOVE, left.width / 2f, left.height * 0.1f) }
            assertTrue(received.any { it.linear.x > 0.0 })
            // UI frozen for 1.5 s with the finger down: the publisher thread must stop by itself.
            instrumentation.runOnMainSync { SystemClock.sleep(1500) }
            SystemClock.sleep(400)
            val last = received.last()
            assertTrue("El robot sigue recibiendo velocidad con la interfaz congelada", last.linear.x == 0.0)
            val banner = autoStopText()
            assertTrue(banner, banner.contains("stopped responding"))
            // With the warning active the joystick does not move the robot.
            val before = received.size
            hold(500) { left.touch(MotionEvent.ACTION_MOVE, left.width / 2f, left.height * 0.1f) }
            assertTrue(received.drop(before).all { it.linear.x == 0.0 })
            left.touch(MotionEvent.ACTION_UP, 0f, 0f)
        }
    }

    @Test
    fun robotStopsListeningWhileDriving() {
        val tab = TabSpec.create(TabType.TELEOP, "T").withSetting("topic", TOPIC)
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            select(sc, tab.id)
            assertTrue(sub.waitForPublisher(10_000))
            val (left, _) = TestJoystick.sticks(sc)
            val down = SystemClock.uptimeMillis()
            left.touch(MotionEvent.ACTION_DOWN, left.width / 2f, left.height / 2f)
            hold(800) { left.touch(MotionEvent.ACTION_MOVE, left.width / 2f, left.height * 0.1f) }
            robot.destroySubscription(sub) // the robot "disappears"
            hold(2500) { left.touch(MotionEvent.ACTION_MOVE, left.width / 2f, left.height * 0.1f) }
            val banner = autoStopText()
            assertTrue("Sin aviso: '$banner'", banner.contains("stopped listening"))
            left.touch(MotionEvent.ACTION_UP, 0f, 0f)
        }
    }

    @Test
    fun gamepadDrivesAndButtonBStops() {
        val tab = TabSpec.create(TabType.TELEOP, "T").withSetting("topic", TOPIC)
        TabStore.get().add(tab)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            select(sc, tab.id)
            assertTrue(sub.waitForPublisher(10_000))
            hold(1000) { sc.onActivity { it.dispatchGenericMotionEvent(joystickEvent(0f, -1f)) } } // left stick up
            assertTrue("El mando no mueve el robot", received.any { it.linear.x > 0.4 })
            sc.onActivity {
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
                it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
            }
            SystemClock.sleep(600)
            assertEquals(0.0, received.last().linear.x, 0.0)
            sc.onActivity { it.dispatchGenericMotionEvent(joystickEvent(0f, 0f)) } // release the stick
            SystemClock.sleep(400)
            val n = received.size
            SystemClock.sleep(600)
            assertTrue("Sigue publicando tras soltar el mando", received.size - n <= 1)
        }
    }

    // ------------------------------------------------------------------ utilities

    private fun joystickEvent(x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_X, x); setAxisValue(MotionEvent.AXIS_Y, y) })
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }

    private fun hold(ms: Long, step: () -> Unit) {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { step(); SystemClock.sleep(50) }
    }

    /** The teleop settings button (Compose): enabled or not. */
    private fun settingsEnabled(): Boolean = Ui.waitFor(context.getString(R.string.teleop_settings), exact = true).isEnabled

    /** The safety stop warning, or "" if it is not shown. */
    private fun autoStopText(): String {
        val prefix = context.getString(R.string.teleop_safety_stop, "").substringBefore(":").trim()
        return Ui.find(prefix, exact = false)?.text?.toString().orEmpty()
    }

    private fun <T> onMainGet(block: () -> T): T {
        var r: T? = null
        instrumentation.runOnMainSync { r = block() }
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun select(sc: ActivityScenario<MainActivity>, id: String) {
        sc.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1500)
    }

    private companion object { const val TOPIC = "/rviz2mini_test/teleop" }
}
