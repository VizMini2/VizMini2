// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.control

import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.ihmc.fastddsjava.cdr.CDRBuffer

/** The controller_manager_msgs versions, how the client tells them apart, and the joint commands. */
class ControllerManagerTest {
    private val registry: InterfaceRegistry by lazy {
        InterfaceRegistry().apply { loadBundle(com.juancams.rviz2mini.testing.TestAssets.text("ros_interfaces.txt")) }
    }

    private fun bytes(spec: MsgSpec, v: MsgValue): ByteArray {
        val buf = CDRBuffer()
        buf.ensureRemainingCapacity(CDRBuffer.PAYLOAD_HEADER.size + DynCodec.size(spec, v, 0))
        buf.writePayloadHeader()
        DynCodec.write(spec, v, buf)
        val bb = buf.getBufferUnsafe().duplicate()
        val n = bb.position(); bb.rewind()
        return ByteArray(n).also { bb.get(it) }
    }

    /** Null if it cannot be read (the app drops such a reply). */
    private fun read(spec: MsgSpec, data: ByteArray): MsgValue? = runCatching {
        val buf = CDRBuffer()
        buf.ensureRemainingCapacity(data.size)
        buf.getBufferUnsafe().put(data)
        buf.rewind()
        buf.readPayloadHeader()
        DynCodec.read(spec, buf)
    }.getOrNull()

    private fun listSpec(v: ControlMsgs) = ControlMsgs.registry(registry, v).srv("controller_manager_msgs/srv/ListControllers").response

    private val arm = (1..7).map { "panda_joint$it/position" }

    /** What a Panda's manager answers (the fields a version does not have are left out when encoding). */
    private fun pandaList(v: ControlMsgs, withInactive: Boolean = true): MsgValue {
        fun c(name: String, state: String, type: String, claimed: List<String>, required: List<String>) = JSONObject()
            .put("name", name).put("state", state).put("type", type).put("claimed_interfaces", JSONArray(claimed))
            .put("required_command_interfaces", JSONArray(required)).put("required_state_interfaces", JSONArray(required.map { it.replace("/position", "/velocity") }))
            .put("is_async", false).put("update_rate", 100)
        val list = JSONArray()
            .put(c("panda_arm_controller", "active", "joint_trajectory_controller/JointTrajectoryController", arm, arm))
            .put(c("joint_state_broadcaster", "active", "joint_state_broadcaster/JointStateBroadcaster", emptyList(), emptyList()))
            .put(c("panda_hand_controller", "active", "forward_command_controller/ForwardCommandController", listOf("panda_finger_joint1/position"), listOf("panda_finger_joint1/position")))
        if (withInactive) list.put(c("panda_arm_position_controller", "inactive", "forward_command_controller/ForwardCommandController", emptyList(), arm))
        return DynCodec.fromJson(listSpec(v), JSONObject().put("controller", list))
    }

    /** The version the client settles on for a reply in `server`'s format (the first readable one in its order). */
    private fun detect(server: ControlMsgs, withInactive: Boolean = true): ControlMsgs? {
        val data = bytes(listSpec(server), pandaList(server, withInactive))
        return listOf(ControlMsgs.KILTED, ControlMsgs.HUMBLE, ControlMsgs.FOXY).firstOrNull { v ->
            val r = read(listSpec(v), data) ?: return@firstOrNull false
            val list = ControllerManagerClient.parseControllers(r)
            list.isNotEmpty() && ControllerManagerClient.plausible(list)
        }
    }

    @Test fun everyVersionHasItsDefinitions() {
        for (v in ControlMsgs.entries) {
            val reg = ControlMsgs.registry(registry, v)
            for (s in listOf("ListControllers", "SwitchController", "ListHardwareInterfaces", "LoadController", "ConfigureController", "UnloadController"))
                reg.srv("controller_manager_msgs/srv/$s")
            if (v.hasHardwareComponents) { reg.srv("controller_manager_msgs/srv/ListHardwareComponents"); reg.srv("controller_manager_msgs/srv/SetHardwareComponentState") }
        }
        val foxy = ControlMsgs.registry(registry, ControlMsgs.FOXY).msg("controller_manager_msgs/msg/ControllerState")
        assertEquals(listOf("name", "state", "type", "claimed_interfaces"), foxy.fields.map { it.name })
        val humbleSwitch = ControlMsgs.registry(registry, ControlMsgs.HUMBLE).srv("controller_manager_msgs/srv/SwitchController")
        assertEquals(listOf("ok"), humbleSwitch.response.fields.map { it.name })
        assertFalse(ControlMsgs.FOXY.hasHardwareComponents)
        // The app's own registry is untouched.
        assertEquals(13, registry.msg("controller_manager_msgs/msg/ControllerState").fields.size)
    }

    @Test fun eachFormatIsReadOnlyWithItsOwnDefinitions() {
        assertEquals(ControlMsgs.KILTED, detect(ControlMsgs.KILTED))
        assertEquals(ControlMsgs.KILTED, detect(ControlMsgs.JAZZY)) // same controller list: told apart by the hardware
        assertEquals(ControlMsgs.HUMBLE, detect(ControlMsgs.HUMBLE))
        assertEquals(ControlMsgs.FOXY, detect(ControlMsgs.FOXY))
        for (v in listOf(ControlMsgs.KILTED, ControlMsgs.HUMBLE, ControlMsgs.FOXY)) assertEquals(v, detect(v, withInactive = false))
    }

    @Test fun jazzyAndKiltedHardwareComponentsDiffer() {
        fun spec(v: ControlMsgs) = ControlMsgs.registry(registry, v).srv("controller_manager_msgs/srv/ListHardwareComponents").response
        fun comp(v: ControlMsgs) = DynCodec.fromJson(spec(v), JSONObject().put("component", JSONArray().put(JSONObject()
            .put("name", "PandaFakeSystem").put("type", "system").put("plugin_name", "mock_components/GenericSystem")
            .put("class_type", "mock_components/GenericSystem").put("rw_rate", 100)
            .put("state", JSONObject().put("id", 3).put("label", "active"))
            .put("command_interfaces", JSONArray(arm.map { JSONObject().put("name", it).put("data_type", "double").put("is_available", true).put("is_claimed", true) }))
            .put("state_interfaces", JSONArray()))))
        val jazzy = bytes(spec(ControlMsgs.JAZZY), comp(ControlMsgs.JAZZY))
        val asKilted = read(spec(ControlMsgs.KILTED), jazzy)?.let { ControllerManagerClient.parseComponents(it) }
        assertTrue("Jazzy's reply must not pass as Kilted's", asKilted == null || asKilted.any { it.stateId !in 0..4 || it.stateLabel != "active" })
        val ok = ControllerManagerClient.parseComponents(read(spec(ControlMsgs.JAZZY), jazzy)!!).single()
        assertEquals("PandaFakeSystem", ok.name); assertEquals("mock_components/GenericSystem", ok.plugin); assertTrue(ok.active)
        assertEquals(7, ok.command.count { it.claimed })
    }

    @Test fun switchRequestUsesEachVersionsFields() {
        for (v in listOf(ControlMsgs.KILTED, ControlMsgs.HUMBLE, ControlMsgs.FOXY)) {
            val spec = ControlMsgs.registry(registry, v).srv("controller_manager_msgs/srv/SwitchController").request
            val req = DynCodec.fromJson(spec, ControllerManagerClient.switchRequest(v, listOf("a"), listOf("b"), strict = true), strict = true)
            val on = if (v == ControlMsgs.FOXY) "start_controllers" else "activate_controllers"
            val off = if (v == ControlMsgs.FOXY) "stop_controllers" else "deactivate_controllers"
            assertEquals(listOf("a"), req[on]); assertEquals(listOf("b"), req[off]); assertEquals(2, (req["strictness"] as Number).toInt())
            assertTrue(bytes(spec, req).isNotEmpty())
        }
    }

    @Test fun controllerJointsAndKinds() {
        val list = ControllerManagerClient.parseControllers(pandaList(ControlMsgs.KILTED))
        val jtc = list.first { it.name == "panda_arm_controller" }
        assertEquals((1..7).map { "panda_joint$it" }, jtc.joints)
        assertEquals("position", jtc.commandKind)
        assertEquals(ControllerCommands.Kind.TRAJECTORY, jtc.kind)
        val inactive = list.first { it.name == "panda_arm_position_controller" }
        assertFalse(inactive.active)
        assertEquals(7, inactive.joints.size) // from the required interfaces: nothing claimed while inactive
        assertEquals(ControllerCommands.Kind.FORWARD, inactive.kind)
        assertEquals(ControllerCommands.Kind.NONE, list.first { it.name == "joint_state_broadcaster" }.kind)
        // Foxy: only the claimed interfaces.
        val foxy = ControllerManagerClient.parseControllers(pandaList(ControlMsgs.FOXY))
        assertTrue(foxy.first { it.name == "panda_arm_position_controller" }.joints.isEmpty())
        assertNull(foxy.first().updateRate)
    }

    @Test fun commandTopicsAndMessages() {
        assertEquals("", ControllerCommands.namespaceOf("/controller_manager"))
        assertEquals("/robot1", ControllerCommands.namespaceOf("robot1/controller_manager/"))
        assertEquals("/robot1/arm/joint_trajectory", ControllerCommands.topic("/robot1/controller_manager", "arm", ControllerCommands.Kind.TRAJECTORY))
        assertEquals("/hand/commands", ControllerCommands.topic("/controller_manager", "hand", ControllerCommands.Kind.FORWARD))
        assertNull(ControllerCommands.topic("/controller_manager", "jsb", ControllerCommands.Kind.NONE))
        assertEquals(ControllerCommands.Kind.FORWARD, ControllerCommands.kind("velocity_controllers/JointGroupVelocityController"))

        val spec = registry.msg("trajectory_msgs/msg/JointTrajectory")
        val t = DynCodec.fromJson(spec, ControllerCommands.trajectory(listOf("j1", "j2"), listOf(0.5, -1.0), 2.25), strict = true)
        assertEquals(listOf("j1", "j2"), t["joint_names"])
        val p = (t["points"] as List<*>).single() as Map<*, *>
        assertEquals(listOf(0.5, -1.0), p["positions"])
        val tfs = p["time_from_start"] as Map<*, *>
        assertEquals(2, (tfs["sec"] as Number).toInt()); assertEquals(250_000_000L, (tfs["nanosec"] as Number).toLong())
        val f = DynCodec.fromJson(registry.msg("std_msgs/msg/Float64MultiArray"), ControllerCommands.forward(listOf(0.02)), strict = true)
        assertEquals(listOf(0.02), f["data"])
    }

    @Test fun declaredControllersFromTheManagersParameters() {
        val params = listOf(
            "update_rate" to "100", "panda_arm_controller.type" to "joint_trajectory_controller/JointTrajectoryController",
            "spare_controller.type" to "forward_command_controller/ForwardCommandController", "panda_arm_controller.params_file" to "/x.yaml",
            "qos_overrides./parameter_events.publisher.depth" to "1000",
        )
        val declared = ControllerManagerClient.declaredControllers(params)
        assertEquals(listOf("panda_arm_controller", "spare_controller"), declared.map { it.first })
        assertEquals("/controller_manager", ControllerManagerClient.normalize(""))
        assertEquals("/ns/controller_manager", ControllerManagerClient.normalize("ns/controller_manager/"))
    }
}
