// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.demo.DemoMode
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** TF tree and Node graph tabs created from the "+" (with the demo robot). */
@RunWith(AndroidJUnit4::class)
class GraphTabsDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "graphtabs").apply { deleteRecursively(); mkdirs() }
    private val savedTabs = TestTabs.backup(context)
    private val savedRos = context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).all

    @After
    fun tearDown() {
        // If the test fails inside the demo, the simulated robot would keep publishing and the following tests
        // would receive its data: really exit (stopping the robot) and then delete whatever is left.
        if (DemoMode.isActive(context)) InstrumentationRegistry.getInstrumentation().runOnMainSync { DemoMode.exit(context, reconnect = false) {} }
        if (DemoMode.isActive(context)) context.getSharedPreferences("rviz2mini_demo", Context.MODE_PRIVATE).edit().clear().commit()
        RosConnection.stop()
        TestTabs.restore(context, savedTabs)
        context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).edit().clear().apply {
            savedRos.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    @Test
    fun createTfTreeAndNodeGraphFromTheCatalog() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
            SystemClock.sleep(4000)
            for (name in listOf("TF tree", "Node graph")) {
                Ui.click("New tab")
                Ui.click(name)
                Ui.waitFor("What it does")
                Ui.click("Create")
                SystemClock.sleep(4000)
                Ui.shot(dir, name.replace(' ', '_'))
            }
            assertTrue("Falta la pestaña TF tree", TabStore.get().all.any { it.type == TabType.TF_TREE })
            assertTrue("Falta la pestaña Node graph", TabStore.get().all.any { it.type == TabType.NODE_GRAPH })
            // The simulated robot appears in the graph even though it does not announce nodes via ros_discovery_info.
            val nodes = RosConnection.graph()?.nodes().orEmpty()
            assertTrue("Grafo sin nodos: $nodes", nodes.any { it.publishers.contains("/scan") })
            scenario.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }
        }
    }

    @Test
    fun tfTreeAsADashboardWidget() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> DemoMode.enter(a) { a.replaceTabs(it) } }
            val dash = TabStore.get().all.first { it.type == TabType.DASHBOARD }
            scenario.onActivity { it.openTab(dash.id) }
            SystemClock.sleep(4000)
            // Widget from the UI: Edit -> Widget -> TF tree -> Create.
            Ui.click("Edit")
            Ui.click("Widget")
            Ui.click("TF tree")
            Ui.waitFor("What it does")
            Ui.click("Create")
            SystemClock.sleep(2000)
            Ui.click("Done")
            SystemClock.sleep(4000)
            Ui.shot(dir, "dashboard_tf_widget")
            val widget = TabStore.get().all.firstOrNull { it.dashboardId == dash.id && it.type == TabType.TF_TREE }
            assertTrue("No se creó el widget TF tree", widget != null)
            assertTrue("El widget no debe salir en la barra de pestañas", widget!!.isWidget)
            assertTrue("Sin frames TF", RosConnection.tfTree.snapshot().any { it.id == "base_link" })
            scenario.onActivity { a -> DemoMode.exit(a) { a.replaceTabs(it) } }
        }
    }
}
