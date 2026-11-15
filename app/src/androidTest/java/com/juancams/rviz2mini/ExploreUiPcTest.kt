// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.explore.ActionActivity
import com.juancams.rviz2mini.explore.ServiceActivity
import com.juancams.rviz2mini.explore.TopicActivity
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.AddTabActivity
import com.juancams.rviz2mini.tabs.AddTabDetailActivity
import com.juancams.rviz2mini.tabs.TabList
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

/**
 * New screens against ROS 2 on the PC: Nodes/Topics/Services/Actions tabs, echo of a topic,
 * a service call and a goal to an action from their form, tab catalog,
 * onboarding and administration. Optional (`-e pc true`, `-e peers <PC IP>`), with the
 * ButtonsPcTest and GraphLifecyclePcTest setup running on the PC. Screenshots in files/explore/.
 */
@RunWith(AndroidJUnit4::class)
class ExploreUiPcTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir = File(context.getExternalFilesDir(null), "explore").apply { mkdirs() }
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
        SystemClock.sleep(3000)
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("pc") != "true") return
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        prefs("rviz2mini_ros").edit().clear().apply { savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } } }.commit()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun exploreTabsListTheNetwork() {
        val tabs = listOf(TabType.NODES, TabType.TOPICS, TabType.SERVICES, TabType.ACTIONS).map { TabSpec.create(it, it.displayName) }
        TabStore.get().replaceAll(tabs)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val expected = mapOf(TabType.NODES to "/rviz2mini_test/lc_talker", TabType.TOPICS to "/scan",
                TabType.SERVICES to "/rviz2mini_test/cpp/add_two_ints", TabType.ACTIONS to "/rviz2mini_test/cpp/fibonacci")
            for (t in tabs) {
                scenario.onActivity { it.openTab(t.id) }
                // The list only creates the visible rows: filter by name, as a person would.
                val filterHint = context.getString(when (t.type) {
                    TabType.NODES -> R.string.explore_filter_nodes
                    TabType.TOPICS -> R.string.explore_filter_topics
                    TabType.SERVICES -> R.string.explore_filter_services
                    else -> R.string.explore_filter_actions
                })
                Ui.waitFor(filterHint)
                Ui.type(filterHint, expected[t.type]!!)
                assertTrue("${t.type}: falta ${expected[t.type]}", waitText(scenario, expected[t.type]!!))
                shot("tab_${t.type.name.lowercase()}")
            }
        }
    }

    @Test
    fun topicEchoServiceCallAndActionGoal() {
        ActivityScenario.launch<TopicActivity>(Intent(context, TopicActivity::class.java).putExtra("name", "/odom").putExtra("type", "nav_msgs/msg/Odometry")).use { s ->
            assertTrue("echo de /odom", waitText(s, "child_frame_id"))
            assertTrue("rate", waitText(s, " Hz"))
            shot("topic_odom")
        }
        ActivityScenario.launch<ServiceActivity>(Intent(context, ServiceActivity::class.java)
            .putExtra("name", "/rviz2mini_test/cpp/add_two_ints").putExtra("type", "example_interfaces/srv/AddTwoInts")).use { s ->
            SystemClock.sleep(1000)
            // Shared screen (Compose): the fields and the button, via accessibility.
            Ui.typeInto(0, "20"); Ui.typeInto(1, "22")
            Ui.click(context.getString(R.string.explore_call))
            assertTrue("respuesta del servicio", waitText(s, "sum: 42"))
            shot("service_call")
        }
        ActivityScenario.launch<ActionActivity>(Intent(context, ActionActivity::class.java)
            .putExtra("name", CppFibonacci.ACTION).putExtra("type", CppFibonacci.type())).use { s ->
            SystemClock.sleep(1000)
            Ui.typeInto(0, "5")
            Ui.click(context.getString(R.string.explore_send_goal))
            assertTrue("resultado de la acción", waitText(s, "SUCCEEDED", 30_000))
            shot("action_goal")
        }
    }

    @Test
    fun addTabCatalogDetailAndOnboarding() {
        // No roles: the catalog offers all tab types.
        ActivityScenario.launch(AddTabActivity::class.java).use { s ->
            SystemClock.sleep(1500)
            var titles: List<String> = emptyList()
            s.onActivity { a -> titles = views(a.window.decorView).filterIsInstance<TextView>().map { it.text.toString() } }
            val expected = listOf(TabType.RVIZ, TabType.CAMERA, TabType.TELEOP).map { it.label() }
            assertTrue("$expected en $titles", titles.containsAll(expected))
            shot("catalog")
        }
        ActivityScenario.launchActivityForResult<AddTabDetailActivity>(Intent(context, AddTabDetailActivity::class.java).putExtra(AddTabDetailActivity.EXTRA_TYPE, "CAMERA")).use { s ->
            SystemClock.sleep(1500)
            shot("detail_camera")
            s.onActivity { a ->
                val edits = views(a.window.decorView).filterIsInstance<EditText>()
                edits[0].setText("Front camera"); edits[1].setText("/image")
                views(a.window.decorView).filterIsInstance<Button>().first { it.text == context.getString(R.string.common_create) }.performClick()
            }
            val r = s.result
            assertEquals(Activity.RESULT_OK, r.resultCode)
            val spec = TabList.fromJson(r.resultData.getStringExtra(AddTabActivity.RESULT_TAB))!!.single()
            assertEquals("Front camera", spec.name)
            assertEquals("/image", spec.settings["topic"])
        }
        ActivityScenario.launch(com.juancams.rviz2mini.home.OnboardingActivity::class.java).use { s ->
            for (i in 0 until 3) {
                SystemClock.sleep(800); shot("onboarding_$i")
                s.onActivity { a -> views(a.window.decorView).filterIsInstance<Button>().first { it.text == context.getString(R.string.onboarding_next) }.performClick() }
            }
        }
    }

    private fun <A : Activity> waitText(s: ActivityScenario<A>, text: String, timeoutMs: Long = 15_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var found = false
            s.onActivity { a -> found = views(a.window.decorView).any { it is TextView && it.isShown && it.text.contains(text) } }
            // The shared screens (Compose) have no TextView: their texts are in the accessibility tree.
            if (found || Ui.exists(text)) return true
            SystemClock.sleep(250)
        }
        return false
    }

    private fun views(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else listOf(v)

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("ExploreUiPcTest", "captura $name")
    }
}
