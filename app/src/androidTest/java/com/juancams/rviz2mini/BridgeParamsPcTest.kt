// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juancams.rviz2mini.params.ParamClient
import com.juancams.rviz2mini.params.ParamModel
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.Interfaces
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parameters via the foxglove_bridge `parameters` capability (tools/pc_tests.sh BridgeParamsPcTest:
 * a bridge on 8766 that advertises no services, and parameter_blackboard).
 */
@RunWith(AndroidJUnit4::class)
class BridgeParamsPcTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var savedRos: Map<String, *>? = null

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pc") == "true")
        savedRos = context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).all
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("pc") != "true") return
        RosConnection.stop()
        context.getSharedPreferences("rviz2mini_ros", Context.MODE_PRIVATE).edit().clear().apply {
            savedRos?.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    @Test
    fun parametersWithoutServices() {
        RosConnection.applyConfig(RosConfig(domainId = 102, nodeName = "rviz2mini_bridge_params", bridgeUrl = "ws://${TestArgs.pcIp}:8766"))
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (RosConnection.bridgeWithParameters() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("el bridge no deja usar parámetros", RosConnection.bridgeWithParameters() != null)
        assertTrue("el bridge anuncia servicios", RosConnection.graph()?.services().orEmpty().isEmpty())
        val c = ParamClient("/rviz2mini_test/parameter_blackboard", Interfaces.get())
        try {
            val until = SystemClock.uptimeMillis() + 10_000
            var all = emptyList<ParamClient.Entry>()
            while (all.none { it.descriptor.name == "speed" } && SystemClock.uptimeMillis() < until) { all = c.all(); SystemClock.sleep(300) }
            Log.i(TAG, "parámetros: ${all.map { it.descriptor.name + "=" + it.value.display() }}")
            val byName = all.associateBy { it.descriptor.name }
            assertEquals(ParamModel.Value(ParamModel.Type.DOUBLE, 1.5), byName.getValue("speed").value)
            assertEquals(ParamModel.Value(ParamModel.Type.STRING, "robot"), byName.getValue("name").value)
            assertEquals(ParamModel.Value(ParamModel.Type.BOOL, true), byName.getValue("enabled").value)
            assertEquals(ParamModel.Value(ParamModel.Type.INTEGER_ARRAY, listOf(1L, 2L, 3L)), byName.getValue("ids").value)
            assertEquals(null, c.set("speed", ParamModel.Value(ParamModel.Type.DOUBLE, 2.0)))
            assertEquals(2.0, c.all().first { it.descriptor.name == "speed" }.value.value as Double, 1e-9)
            c.set("speed", ParamModel.Value(ParamModel.Type.DOUBLE, 1.5))
        } finally {
            c.close()
        }
    }

    private companion object {
        const val TAG = "BridgeParamsPc"
    }
}
