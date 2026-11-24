// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.buttons.ButtonKind
import com.juancams.rviz2mini.buttons.ButtonList
import com.juancams.rviz2mini.buttons.ButtonSpec
import com.juancams.rviz2mini.buttons.ButtonState
import com.juancams.rviz2mini.buttons.ButtonsFragment
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.CallException
import com.juancams.rviz2mini.ros.dyn.DynActionClient
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.Interfaces
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Services, actions and publishing of the "Buttons" tab against ROS 2 on the PC. Optional:
 *   adb shell am instrument -w -e pc true -e class com.juancams.rviz2mini.ButtonsPcTest \
 *       com.juancams.vizmini2.test/com.juancams.rviz2mini.TestRunner
 * with, on the PC (ROS_DOMAIN_ID=77):
 *   python3 tools/buttons_test_server.py
 *   ros2 run demo_nodes_cpp add_two_ints_server --ros-args -r __ns:=/rviz2mini_test/cpp
 *   ros2 run action_tutorials_cpp fibonacci_action_server --ros-args -r __ns:=/rviz2mini_test/cpp
 */
@RunWith(AndroidJUnit4::class)
class ButtonsPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val registry by lazy { Interfaces.get() }
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
        assertTrue("Sin conexión ROS 2", RosConnection.status is RosConnection.Status.Connected)
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

    private fun waitServer(hasServer: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!hasServer() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
    }

    @Test
    fun serviceCalls() {
        val client = DynServiceClient("/rviz2mini_test/add_two_ints", registry.srv("example_interfaces/srv/AddTwoInts"))
        try {
            waitServer { client.hasServer() }
            for (i in 1..5) {
                val t0 = SystemClock.uptimeMillis()
                val r = client.call(MsgValue().apply { put("a", i.toLong()); put("b", 40L) }, 5000)
                Log.i(TAG, "add_two_ints $i + 40 = ${r["sum"]} (${SystemClock.uptimeMillis() - t0} ms)")
                assertEquals(i + 40L, r["sum"])
            }
        } finally {
            client.close()
        }

        val setBool = DynServiceClient("/rviz2mini_test/set_bool", registry.srv("std_srvs/srv/SetBool"))
        try {
            waitServer { setBool.hasServer() }
            val r = setBool.call(MsgValue().apply { put("data", true) }, 5000)
            assertEquals(true, r["success"])
            assertEquals("Camión recibido: True ✓", r["message"])
        } finally {
            setBool.close()
        }

        val missing = DynServiceClient("/rviz2mini_test/no_existe", registry.srv("std_srvs/srv/Trigger"))
        try {
            missing.call(MsgValue(), 2000)
            fail("No debería responder nadie")
        } catch (e: CallException) {
            Log.i(TAG, "sin servidor: ${e.message}")
            assertTrue(e.message!!, e.message!!.contains("No server for"))
        } finally {
            missing.close()
        }
    }

    @Test
    fun actionWithFeedbackAndCancel() {
        val spec = registry.action("example_interfaces/action/Fibonacci")
        val client = DynActionClient("/rviz2mini_test/fibonacci", spec, registry.srv("action_msgs/srv/CancelGoal"))
        try {
            waitServer { client.hasServer() }
            SystemClock.sleep(1000) // so that get_result and feedback get matched too
            val feedback = CopyOnWriteArrayList<MsgValue>()
            val accepted = AtomicBoolean(false)
            val out = client.run(MsgValue().apply { put("order", 6L) }, 5000, { accepted.set(true) }, { feedback.add(it) }, { false })
            Log.i(TAG, "fibonacci: ${out.statusLabel} ${out.result} feedback=${feedback.size}")
            assertTrue(accepted.get())
            assertEquals(DynActionClient.STATUS_SUCCEEDED, out.status)
            assertEquals(listOf(0L, 1L, 1L, 2L, 3L, 5L, 8L), out.result["sequence"])
            assertTrue("sin feedback", feedback.isNotEmpty())

            // Cancel halfway.
            val start = SystemClock.uptimeMillis()
            val steps = AtomicInteger()
            val cancelled = client.run(MsgValue().apply { put("order", 40L) }, 5000, {}, { steps.incrementAndGet() },
                cancelRequested = { SystemClock.uptimeMillis() - start > 1500 })
            Log.i(TAG, "fibonacci cancelada: ${cancelled.statusLabel} tras ${steps.get()} feedback, ${cancelled.result}")
            assertEquals("CANCELED", cancelled.statusLabel)
        } finally {
            client.close()
        }
    }

    @Test
    fun cppServers() {
        val add = DynServiceClient("/rviz2mini_test/cpp/add_two_ints", registry.srv("example_interfaces/srv/AddTwoInts"))
        val fib = DynActionClient(CppFibonacci.ACTION, registry.action(CppFibonacci.type()), registry.srv("action_msgs/srv/CancelGoal"))
        try {
            waitServer { add.hasServer() && fib.hasServer() }
            SystemClock.sleep(1000)
            assertEquals(7L, add.call(MsgValue().apply { put("a", 3L); put("b", 4L) }, 5000)["sum"])
            val fb = AtomicInteger()
            val out = fib.run(MsgValue().apply { put("order", 3L) }, 5000, {}, { fb.incrementAndGet() }, { false })
            Log.i(TAG, "cpp fibonacci: ${out.statusLabel} ${out.result} feedback=${fb.get()}")
            assertEquals(DynActionClient.STATUS_SUCCEEDED, out.status)
            assertEquals(listOf(0L, 1L, 1L, 2L), out.result["partial_sequence"] ?: out.result["sequence"])
        } finally {
            add.close(); fib.close()
        }
    }

    @Test
    fun publishReachesPc() {
        val echoes = CopyOnWriteArrayList<String>()
        val echo = DynRos.subscribe("/rviz2mini_test/button_echo", registry.msg("std_msgs/msg/String"), RosConnection.defaultQos()) {
            echoes.add(it["data"].toString())
        }
        val twist = DynRos.publisher("/rviz2mini_test/button_twist", registry.msg("geometry_msgs/msg/Twist"), RosConnection.defaultQos())
        val text = DynRos.publisher("/rviz2mini_test/button_text", registry.msg("std_msgs/msg/String"), RosConnection.defaultQos())
        try {
            waitServer { twist.matched() > 0 && text.matched() > 0 }
            echo.waitForPublisher(5000)
            SystemClock.sleep(500)
            twist.publish(MsgValue().apply {
                put("linear", MsgValue().apply { put("x", 0.5); put("y", 0.0); put("z", 0.0) })
                put("angular", MsgValue().apply { put("x", 0.0); put("y", 0.0); put("z", -1.25) })
            })
            text.publish(MsgValue().apply { put("data", "año ñandú €") })
            val deadline = SystemClock.uptimeMillis() + 5000
            while (echoes.size < 2 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            Log.i(TAG, "echo: $echoes")
            assertTrue(echoes.toString(), echoes.contains("twist 0.50 -1.25"))
            assertTrue(echoes.toString(), echoes.contains("text año ñandú €"))
        } finally {
            echo.close(); twist.close(); text.close()
        }
    }

    /** The real tab: the three button types, pressed the way the user would. */
    @Test
    fun buttonsTab() {
        val service = ButtonSpec(label = "Sumar", kind = ButtonKind.SERVICE, target = "/rviz2mini_test/add_two_ints",
            type = "example_interfaces/srv/AddTwoInts", values = """{"a":20,"b":22}""", color = 1)
        val action = ButtonSpec(label = "Fibonacci", kind = ButtonKind.ACTION, target = "/rviz2mini_test/fibonacci",
            type = "example_interfaces/action/Fibonacci", values = """{"order":5}""")
        val publish = ButtonSpec(label = "Hola", kind = ButtonKind.PUBLISH, target = "/rviz2mini_test/button_text",
            type = "std_msgs/msg/String", values = """{"data":"desde un botón"}""")
        val broken = ButtonSpec(label = "Roto", kind = ButtonKind.SERVICE, target = "/x", type = "no_existe/srv/Nada")
        val tab = TabSpec.create(TabType.BUTTONS, "Botones test")
            .withSetting("buttons", ButtonList.toJson(listOf(service, action, publish, broken)))
        TabStore.get().add(tab)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                wakeUp(scenario)
                scenario.onActivity { a ->
                    val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
                    (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == tab.id }.select()
                }
                instrumentation.waitForIdleSync()
                val f = fragment(scenario, tab.id)
                assertEquals(2, f.columnsForTest())
                assertTrue(f.setupErrorForTest(broken.id)!!.contains("no_existe/srv/Nada"))
                SystemClock.sleep(3000) // DDS discovery

                instrumentation.runOnMainSync { f.pressForTest(service.id); f.pressForTest(action.id); f.pressForTest(publish.id) }
                val sum = waitDone(f, service.id, 10_000)
                val fib = waitDone(f, action.id, 15_000)
                val pub = waitDone(f, publish.id, 5_000)
                Log.i(TAG, "UI: servicio=$sum acción=$fib publicar=$pub")
                assertTrue(sum.toString(), sum.ok && sum.text.startsWith("sum: 42"))
                assertTrue(fib.toString(), fib.ok && fib.text.startsWith("SUCCEEDED · sequence: [0, 1, 1, 2, 3, 5]"))
                // "Published · …" in the phone's language.
                val published = context.resources.getQuantityString(R.plurals.buttons_published_subscribers, 1, 1).substringBefore('·')
                assertTrue(pub.toString(), pub.ok && pub.text.startsWith(published))
                shot("1_botones")

                // The editor with the form generated from the NavigateToPose goal.
                val nav = ButtonSpec(label = "Ir a casa", kind = ButtonKind.ACTION, target = "/rviz2mini_test/navigate_to_pose",
                    type = "nav2_msgs/action/NavigateToPose", values = """{"pose":{"header":{"frame_id":"map","stamp":"now"},"pose":{"position":{"x":1.5}}}}""")
                instrumentation.runOnMainSync { f.upsertForTest(nav) }
                instrumentation.runOnMainSync { f.editForTest(nav.id) }
                shot("2_editor")
                instrumentation.runOnMainSync { com.juancams.rviz2mini.rviz.OpenDialogs.dismissLast() }
                instrumentation.runOnMainSync { f.editForTest(null) }
                shot("3_editor_nuevo")
                instrumentation.runOnMainSync { com.juancams.rviz2mini.rviz.OpenDialogs.dismissLast() }
                instrumentation.runOnMainSync { f.deleteForTest(nav.id) }

                // Edit from code: the change is saved and the new button works.
                instrumentation.runOnMainSync { f.upsertForTest(service.copy(values = """{"a":1,"b":1}""")) }
                SystemClock.sleep(2000)
                instrumentation.runOnMainSync { f.pressForTest(service.id) }
                val sum2 = waitDone(f, service.id, 10_000)
                assertTrue(sum2.toString(), sum2.text.startsWith("sum: 2"))
                val stored = ButtonList.fromJson(TabStore.get().get(tab.id)!!.settings["buttons"])
                assertEquals("""{"a":1,"b":1}""", stored.first { it.id == service.id }.values)
            }
        } finally {
            TabStore.get().remove(tab.id)
        }
    }

    /**
     * Safety: a held button sends the stop on release (and when the tab is hidden), and leaving
     * the app with an action running cancels it on the robot. The PC server checks it:
     * `button_echo` returns each Twist received and the action status says whether it was canceled.
     */
    @Test
    fun holdStopsOnReleaseAndLeavingCancelsTheAction() {
        val hold = ButtonSpec(label = "Avanzar", kind = ButtonKind.PUBLISH, target = "/rviz2mini_test/button_twist",
            type = "geometry_msgs/msg/Twist", values = """{"linear":{"x":0.4},"angular":{"z":0.2}}""", repeatHz = 20.0)
        val noStop = hold.copy(id = java.util.UUID.randomUUID().toString(), label = "Sin parada", stopOnRelease = false)
        val action = ButtonSpec(label = "Fibonacci", kind = ButtonKind.ACTION, target = "/rviz2mini_test/fibonacci",
            type = "example_interfaces/action/Fibonacci", values = """{"order":40}""")
        val tab = TabSpec.create(TabType.BUTTONS, "Seguridad test").withSetting("buttons", ButtonList.toJson(listOf(hold, noStop, action)))
        TabStore.get().add(tab)
        val echoes = CopyOnWriteArrayList<String>()
        val echo = DynRos.subscribe("/rviz2mini_test/button_echo", registry.msg("std_msgs/msg/String"), RosConnection.defaultQos()) { v -> echoes.add(v["data"] as String) }
        val statuses = CopyOnWriteArrayList<Long>()
        val status = DynRos.subscribe("/rviz2mini_test/fibonacci/_action/status", registry.msg("action_msgs/msg/GoalStatusArray"), RosConnection.defaultQos()) { v ->
            @Suppress("UNCHECKED_CAST")
            for (g in v["status_list"] as List<Map<String, Any?>>) statuses.add((g["status"] as Number).toLong())
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                wakeUp(scenario)
                selectTab(scenario, tab.id)
                val f = fragment(scenario, tab.id)
                SystemClock.sleep(3000) // DDS discovery

                fun holdFor(id: String, ms: Long, release: () -> Unit) {
                    echoes.clear()
                    instrumentation.runOnMainSync { f.pressForTest(id) }
                    SystemClock.sleep(ms)
                    release()
                    SystemClock.sleep(800)
                }
                // On release: the last one reaching the PC is the stop; the other fields are 0 too.
                holdFor(hold.id, 1000) { instrumentation.runOnMainSync { f.releaseForTest(hold.id) } }
                Log.i(TAG, "mantener: ${echoes.size} ecos, últimos ${echoes.takeLast(2)}")
                assertTrue("pocos mensajes a 20 Hz: ${echoes.size}", echoes.size >= 10)
                assertEquals("twist 0.40 0.20", echoes[echoes.size - 2])
                assertEquals("twist 0.00 0.00", echoes.last())

                // With the option off, the last value stays.
                holdFor(noStop.id, 600) { instrumentation.runOnMainSync { f.releaseForTest(noStop.id) } }
                assertEquals("twist 0.40 0.20", echoes.last())

                // Hide the tab with the finger still down: it stops all the same.
                holdFor(hold.id, 600) { selectTab(scenario, "home") }
                val n = echoes.size
                SystemClock.sleep(600)
                Log.i(TAG, "tras ocultar: ${echoes.takeLast(2)}")
                assertEquals("sigue publicando con la pestaña oculta", n, echoes.size)
                assertEquals("twist 0.00 0.00", echoes.last())

                // Long action (40 steps of 0.3 s) running and the app is left.
                selectTab(scenario, tab.id)
                instrumentation.runOnMainSync { f.pressForTest(action.id) }
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (2L !in statuses && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                assertTrue("la acción no ha empezado: $statuses", 2L in statuses) // EXECUTING
            }
            // ActivityScenario.close(): the activity is destroyed without it being a screen rotation. The app
            // closes its node as soon as the server accepts the cancellation, so CANCELING (3) is seen
            // here; the final CANCELED (5) does not always arrive anymore (the server log says so).
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (3L !in statuses && 5L !in statuses && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            Log.i(TAG, "estados de la acción: ${statuses.distinct()}")
            assertTrue("la acción no se ha cancelado al salir: ${statuses.distinct()}", 3L in statuses || 5L in statuses)
        } finally {
            echo.close(); status.close()
            TabStore.get().remove(tab.id)
        }
    }

    private fun selectTab(scenario: ActivityScenario<MainActivity>, id: String) {
        scenario.onActivity { a ->
            val tabs = a.findViewById<TabLayout>(R.id.bottomTabs)
            (0 until tabs.tabCount).map { tabs.getTabAt(it)!! }.first { it.tag == id }.select()
        }
        instrumentation.waitForIdleSync()
    }

    /** With the screen off the activity does not reach RESUMED and the tab cannot be changed. */
    private fun wakeUp(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { a -> a.setShowWhenLocked(true); a.setTurnScreenOn(true) }
        val deadline = SystemClock.uptimeMillis() + 10_000
        var resumed = false
        while (!resumed && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { a -> resumed = a.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            if (!resumed) SystemClock.sleep(200)
        }
        assertTrue("La actividad no pasa a primer plano (¿pantalla bloqueada?)", resumed)
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
        val dir = java.io.File(context.getExternalFilesDir(null), "buttons").apply { mkdirs() }
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun fragment(scenario: ActivityScenario<MainActivity>, tag: String): ButtonsFragment {
        var f: ButtonsFragment? = null
        scenario.onActivity { a -> f = a.supportFragmentManager.findFragmentByTag(tag) as ButtonsFragment }
        return f!!
    }

    private fun waitDone(f: ButtonsFragment, id: String, timeoutMs: Long): ButtonState.Done {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var s: ButtonState? = null
            instrumentation.runOnMainSync { s = f.stateForTest(id) }
            if (s is ButtonState.Done) return s as ButtonState.Done
            SystemClock.sleep(100)
        }
        var s: ButtonState? = null
        instrumentation.runOnMainSync { s = f.stateForTest(id) }
        fail("El botón no ha terminado: $s")
        throw IllegalStateException()
    }

    private companion object {
        const val TAG = "ButtonsPc"
    }
}
