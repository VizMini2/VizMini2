// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.camera.CameraStream
import com.juancams.rviz2mini.camera.ImageKind
import com.juancams.rviz2mini.camera.ImageTopic
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import geometry_msgs.Twist
import geometry_msgs.TwistStamped
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sensor_msgs.CompressedImage
import sensor_msgs.Image
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2QoSProfile
import us.ihmc.jros2.ROS2Topic
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end tests ON THE DEVICE: the app publishes/subscribes over real DDS and an independent
 * jros2 node (another DDS participant on the same device) plays the "robot".
 *
 * They use their own domain and topics so as not to interfere with real robots on the network, and restore
 * the user's configuration and tabs when done.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var robot: ROS2Node
    private var savedTabs: String? = null
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        savedTabs = TestTabs.backup(context)
        savedRos = prefs(ROS_PREFS).all
        RosConnection.applyConfig(RosConfig(domainId = TEST_DOMAIN, nodeName = "rviz2mini_test"))
        robot = RosConnection.createNode("fake_robot", TEST_DOMAIN)
    }

    @After
    fun tearDown() {
        RosConnection.closeNode(robot)
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs(ROS_PREFS).edit().clear().apply {
            savedRos?.forEach { (k, v) ->
                when (v) { is Int -> putInt(k, v); is String -> putString(k, v) }
            }
        }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun waitConnected() {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.status !is RosConnection.Status.Connected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
        }
        assertTrue("ROS 2 no conecta: ${RosConnection.status}", RosConnection.status is RosConnection.Status.Connected)
    }

    // ------------------------------------------------------------------ teleop

    @Test
    fun joystickDragPublishesTwistAndReleaseStopsTheRobot() {
        val received = CopyOnWriteArrayList<Pair<Double, Double>>()
        val msg = Twist()
        val sub = robot.createSubscription(ROS2Topic(TELEOP_TOPIC, Twist::class.java), { reader ->
            if (reader.read(msg)) received.add(msg.linear.x to msg.angular.z)
        }, ROS2QoSProfile())

        val tab = TabSpec.create(TabType.TELEOP, "Teleop test")
            .withSetting("topic", TELEOP_TOPIC).withSetting("maxLinear", "0.5").withSetting("maxAngular", "1.0")
        TabStore.get().add(tab)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitConnected()
            selectTab(scenario, tab.id)
            assertTrue("La app no empareja con el suscriptor", sub.waitForPublisher(10_000))

            val joystick = findJoystick(scenario)
            // Finger at the top right for 1.5 s.
            drag(joystick, 0.5f, 0.8f, holdMs = 1500, release = true)
            SystemClock.sleep(1000)

            val moving = received.filter { it.first != 0.0 || it.second != 0.0 }
            assertTrue("Esperaba ~15 mensajes a 10 Hz, llegaron ${moving.size}", moving.size in 8..25)
            for ((lin, ang) in moving.drop(2)) {
                assertTrue("lineal debería ser > 0 y <= 0.5: $lin", lin > 0.0 && lin <= 0.5)
                assertTrue("angular debería ser < 0 (giro a la derecha) y >= -1: $ang", ang < 0.0 && ang >= -1.0)
            }
            assertEquals("Al soltar se debe publicar cero", 0.0 to 0.0, received.last())
            val countAfterRelease = received.size
            SystemClock.sleep(1000)
            assertEquals("Tras los ceros no se publica nada más", countAfterRelease, received.size)
        }
    }

    @Test
    fun switchingTabWhileDrivingStopsTheRobot() {
        val received = CopyOnWriteArrayList<Pair<Double, Double>>()
        val msg = Twist()
        val sub = robot.createSubscription(ROS2Topic(TELEOP_TOPIC, Twist::class.java), { reader ->
            if (reader.read(msg)) received.add(msg.linear.x to msg.angular.z)
        }, ROS2QoSProfile())
        val tab = TabSpec.create(TabType.TELEOP, "Teleop test").withSetting("topic", TELEOP_TOPIC)
        TabStore.get().add(tab)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitConnected()
            selectTab(scenario, tab.id)
            assertTrue(sub.waitForPublisher(10_000))
            val joystick = findJoystick(scenario)

            drag(joystick, 0f, 1f, holdMs = 800, release = false) // finger down, not released
            assertTrue("Debería estar publicando", received.any { it.first > 0.0 })
            selectTab(scenario, "config") // the Teleop tab freezes
            SystemClock.sleep(1000)
            assertEquals("Al ocultar la pestaña se debe publicar cero", 0.0 to 0.0, received.last())
            val count = received.size
            SystemClock.sleep(800)
            assertEquals(count, received.size)
            // We lift the finger to leave the system clean: it must not publish movement again.
            touch(joystick, MotionEvent.ACTION_UP, 0f, 0f, SystemClock.uptimeMillis())
            SystemClock.sleep(500)
            assertTrue(received.drop(count).all { it == 0.0 to 0.0 })
        }
    }

    @Test
    fun changingTypeOnSameTopicDoesNotCrashAndStampedArrives() {
        RosConnection.start()
        waitConnected()
        val received = CountDownLatch(3)
        var frameId = ""
        val msg = TwistStamped()
        val sub = robot.createSubscription(ROS2Topic(TYPE_TOPIC, TwistStamped::class.java), { reader ->
            if (reader.read(msg)) { frameId = msg.header.frameIdAsString; received.countDown() }
        }, ROS2QoSProfile())

        // First Twist and then TwistStamped on the SAME topic: without TopicTypeRegistry this is a SIGSEGV.
        val first = RosConnection.createPublisher(TYPE_TOPIC, Twist::class.java)
        SystemClock.sleep(1000)
        first.close()
        // Same topic and same type again (as when saving the teleop settings): previously, SIGSEGV.
        val again = RosConnection.createPublisher(TYPE_TOPIC, Twist::class.java)
        SystemClock.sleep(1000)
        again.close()
        val second = RosConnection.createPublisher(TYPE_TOPIC, TwistStamped::class.java)
        waitConnected()
        assertTrue("El publicador TwistStamped no empareja", sub.waitForPublisher(15_000))

        val out = TwistStamped().apply { header.setFrameId("chassis") } // 7 letters: the case jros2 1.2 truncated
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (received.count > 0 && SystemClock.uptimeMillis() < deadline) {
            second.publish(out)
            SystemClock.sleep(100)
        }
        assertEquals(0L, received.count)
        assertEquals("chassis", frameId)
        second.close()
    }

    // ------------------------------------------------------------------ camera

    @Test
    fun probeFindsImageTopicAndStreamDecodesIt() {
        RosConnection.start()
        waitConnected()
        val w = 320; val h = 240 // ~230 KB: arrives fragmented, like the PC camera
        val pub = robot.createPublisher(ROS2Topic(IMAGE_TOPIC, Image::class.java), ROS2QoSProfile())
        val img = Image().apply {
            width = w; height = h; step = w * 3; setEncoding("rgb8")
            for (y in 0 until h) for (x in 0 until w) {
                data.add((x * 4 % 256).toByte()); data.add((y * 5 % 256).toByte()); data.add(200.toByte())
            }
        }
        val publishing = java.util.concurrent.atomic.AtomicBoolean(true)
        val publisherThread = Thread {
            while (publishing.get()) { pub.publish(img); SystemClock.sleep(100) }
        }.apply { start() }

        try {
            // Probe with the SAME name as Image and CompressedImage (the case that caused the SIGSEGV).
            val probeDone = CountDownLatch(1)
            var found: List<RosConnection.ProbeRequest<*>> = emptyList()
            RosConnection.probe(
                listOf(
                    RosConnection.ProbeRequest(IMAGE_TOPIC, Image::class.java),
                    RosConnection.ProbeRequest(IMAGE_TOPIC, CompressedImage::class.java),
                    RosConnection.ProbeRequest("/rviz2mini_test/nothing", Image::class.java)
                ), 4000
            ) { found = it; probeDone.countDown() }
            assertTrue(probeDone.await(20, TimeUnit.SECONDS))
            assertEquals(listOf(IMAGE_TOPIC to Image::class.java), found.map { it.topicName to it.type })

            // Twice in a row: the second one re-subscribes to the same topic (previously: SIGSEGV in jros2).
            repeat(2) { round ->
                val frames = java.util.concurrent.atomic.AtomicInteger(0)
                val firstFrame = CountDownLatch(1)
                var bitmap: Bitmap? = null
                var info: CameraStream.FrameInfo? = null
                var error: String? = null
                val stream = CameraStream(ImageTopic(IMAGE_TOPIC, ImageKind.RAW), object : CameraStream.Listener {
                    override fun onFrame(bitmap: Bitmap, info: CameraStream.FrameInfo) {
                        if (frames.incrementAndGet() == 1) {
                            onFirst(bitmap.copy(Bitmap.Config.ARGB_8888, false), info)
                        }
                    }
                    override fun onStreamError(message: String) { error = message }
                    fun onFirst(b: Bitmap, i: CameraStream.FrameInfo) { bitmap = b; info = i; firstFrame.countDown() }
                })
                instrumentation.runOnMainSync { stream.start() }
                assertTrue("Ronda $round: no llegó ningún frame", firstFrame.await(15, TimeUnit.SECONDS))
                SystemClock.sleep(2500)
                instrumentation.runOnMainSync { stream.stop() }

                // Before the IDLSequence fix only the first frame was read.
                // What is checked is that more than one arrives (the old IDLSequence bug left only the first), not
                // performance: decoding 230 KB per frame gives 7 to 13 frames on a Mi 9T depending on how loaded it is, and on an
                // emulator without a GPU even fewer.
                val minFrames = if (isEmulator) 2 else 5
                assertTrue("Ronda $round: solo ${frames.get()} frames en 2,5 s a 10 Hz (error=$error)", frames.get() >= minFrames)
                assertEquals(null, error)
                assertNotNull(bitmap)
                assertEquals(w, info!!.width); assertEquals(h, info!!.height); assertEquals("rgb8", info!!.encoding)
                val px = bitmap!!.getPixel(10, 20)
                assertEquals(40, (px shr 16) and 0xFF)
                assertEquals(100, (px shr 8) and 0xFF)
                assertEquals(200, px and 0xFF)
            }
        } finally {
            publishing.set(false)
            publisherThread.join()
        }
    }

    // ------------------------------------------------------------------ configuration

    @Test
    fun changingDomainMovesEverySubscriptionAndPublisher() {
        RosConnection.start()
        waitConnected()
        val otherDomain = TEST_DOMAIN + 1
        val robotB = RosConnection.createNode("fake_robot_b", otherDomain)
        try {
            // Managed subscription (like the camera's) and managed publisher (like teleop).
            val countA = java.util.concurrent.atomic.AtomicInteger()
            val image = Image()
            val sub = RosConnection.subscribe(IMAGE_TOPIC, Image::class.java, RosConnection.sensorQos(),
                us.ihmc.jros2.ROS2SubscriptionCallback { r -> image.data.clear(); if (r.read(image)) countA.incrementAndGet() })
            val pub = RosConnection.createPublisher(TELEOP_TOPIC, Twist::class.java)
            val small = Image().apply { width = 1; height = 1; step = 3; setEncoding("rgb8"); data.add(1); data.add(2); data.add(3) }

            val pubA = robot.createPublisher(ROS2Topic(IMAGE_TOPIC, Image::class.java), ROS2QoSProfile())
            val pubB = robotB.createPublisher(ROS2Topic(IMAGE_TOPIC, Image::class.java), ROS2QoSProfile())
            val listenB = CopyOnWriteArrayList<Double>()
            val twistB = Twist()
            val subB = robotB.createSubscription(ROS2Topic(TELEOP_TOPIC, Twist::class.java), { r ->
                if (r.read(twistB)) listenB.add(twistB.linear.x)
            }, ROS2QoSProfile())

            assertTrue(pubA.waitForSubscription(10_000))
            repeat(10) { pubA.publish(small); SystemClock.sleep(50) }
            assertTrue("Domain $TEST_DOMAIN: no llegan imágenes", countA.get() > 0)

            // Hot domain change (what "Apply and reconnect" does).
            RosConnection.applyConfig(RosConfig(domainId = otherDomain, nodeName = "rviz2mini_test"))
            SystemClock.sleep(500)
            waitConnected()
            assertTrue("La suscripción no se recrea en el domain nuevo", pubB.waitForSubscription(10_000))
            assertTrue("El publicador no se recrea en el domain nuevo", subB.waitForPublisher(10_000))

            countA.set(0)
            repeat(10) { pubA.publish(small); SystemClock.sleep(50) } // old domain: must not arrive
            assertEquals("Siguen llegando datos del domain viejo", 0, countA.get())
            repeat(10) { pubB.publish(small); SystemClock.sleep(50) }
            assertTrue("Domain $otherDomain: no llegan imágenes", countA.get() > 0)

            val out = Twist().apply { linear.x = 0.25 }
            val deadline = SystemClock.uptimeMillis() + 5000
            while (listenB.isEmpty() && SystemClock.uptimeMillis() < deadline) { pub.publish(out); SystemClock.sleep(100) }
            assertEquals(0.25, listenB.firstOrNull() ?: -1.0, 1e-9)
            sub.cancel(); pub.close()
        } finally {
            RosConnection.closeNode(robotB)
        }
    }

    @Test
    fun restartingRosRepeatedlyDoesNotCrashOrLoseSubscriptions() {
        RosConnection.start()
        waitConnected()
        val count = java.util.concurrent.atomic.AtomicInteger()
        val image = Image()
        val sub = RosConnection.subscribe(IMAGE_TOPIC, Image::class.java, RosConnection.sensorQos(),
            us.ihmc.jros2.ROS2SubscriptionCallback { r -> image.data.clear(); if (r.read(image)) count.incrementAndGet() })
        val pubA = robot.createPublisher(ROS2Topic(IMAGE_TOPIC, Image::class.java), ROS2QoSProfile())
        val small = Image().apply { width = 1; height = 1; step = 1; setEncoding("mono8"); data.add(9) }
        // Restarts in a row, like tapping "Restart ROS 2" several times very quickly.
        repeat(5) { RosConnection.restart(); SystemClock.sleep(150) }
        SystemClock.sleep(800)
        waitConnected()
        // jros2's waitForSubscription() remembers old matches (from before the restart):
        // we publish until the NEW reader receives something, with a maximum of 10 s.
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (count.get() == 0 && SystemClock.uptimeMillis() < deadline) { pubA.publish(small); SystemClock.sleep(50) }
        assertTrue("Tras reiniciar no llegan datos en 10 s (emparejados: ${pubA.publicationMatchedStatus})", count.get() > 0)
        sub.cancel()
    }

    // ------------------------------------------------------------------ utilities

    private fun selectTab(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            val tab = (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }
            tab.select()
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
    }

    /** The joystick of the visible Teleop tab (shared screen, Compose; see TestJoystick). */
    private fun findJoystick(scenario: ActivityScenario<MainActivity>): TestJoystick = TestJoystick.sticks(scenario).first

    /**
     * Drags from the center to (fx, fy) of the usable radius (fy upward) and holds.
     * Events are delivered directly to the view: HyperOS blocks system touch injection
     * (INJECT_EVENTS) unless "USB debugging (Security settings)" is enabled.
     */
    private fun drag(view: TestJoystick, fx: Float, fy: Float, holdMs: Long, release: Boolean) {
        val cx = view.width / 2f
        val cy = view.height / 2f
        val r = minOf(view.width, view.height) / 2f * 0.7f
        val down = SystemClock.uptimeMillis()
        touch(view, MotionEvent.ACTION_DOWN, cx, cy, down)
        val steps = 10
        for (i in 1..steps) {
            touch(view, MotionEvent.ACTION_MOVE, cx + fx * r * i / steps, cy - fy * r * i / steps, down)
            SystemClock.sleep(20)
        }
        val end = SystemClock.uptimeMillis() + holdMs
        while (SystemClock.uptimeMillis() < end) {
            touch(view, MotionEvent.ACTION_MOVE, cx + fx * r, cy - fy * r, down)
            SystemClock.sleep(50)
        }
        if (release) touch(view, MotionEvent.ACTION_UP, cx + fx * r, cy - fy * r, down)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun touch(view: TestJoystick, action: Int, x: Float, y: Float, downTime: Long) = view.touch(action, x, y)

    private companion object {
        const val TEST_DOMAIN = 77
        val isEmulator: Boolean get() = android.os.Build.FINGERPRINT.startsWith("generic") ||
            android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish")
        const val TELEOP_TOPIC = "/rviz2mini_test/cmd_vel"
        const val TYPE_TOPIC = "/rviz2mini_test/type_change"
        const val IMAGE_TOPIC = "/rviz2mini_test/image"
        const val TABS_PREFS = "rviz2mini_tabs"
        const val ROS_PREFS = "rviz2mini_ros"
    }
}
