// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.e2e

import com.juancams.rviz2mini.control.ControlMsgs
import com.juancams.rviz2mini.control.ControllerCommands
import com.juancams.rviz2mini.control.ControllerManagerClient
import com.juancams.rviz2mini.control.RobotJoints
import com.juancams.rviz2mini.ros.dyn.DynRos
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Against a real ros2_control controller manager: the Panda with mock hardware in /rc
 * (tools/ros2_control_panda.launch.py namespace:=rc). Each test leaves the controllers as the launch file starts them.
 */
class ControllersE2ETest {
    private lateinit var c: ControllerManagerClient
    private lateinit var joints: RobotJoints
    private val pubs = HashMap<String, DynRos.Publisher>()
    private val manager = "/rc/controller_manager"
    private val arm = (1..7).map { "panda_joint$it" }

    @Before fun connect() {
        E2E.connect("/rc/joint_states", 60_000)
        c = ControllerManagerClient(E2E.registry, manager)
        joints = RobotJoints(E2E.registry, manager).apply { start() }
        // The spawners take a few seconds after the topics appear.
        E2E.waitFor({ "controllers not spawned: ${runCatching { c.listControllers().map { it.name to it.state } }.getOrElse { it.message }}" }, 60_000) {
            runCatching { c.listControllers().count { it.active } >= 3 && c.listControllers().any { it.name == "panda_arm_position_controller" } }.getOrDefault(false)
        }
        E2E.waitFor("no joint states in /rc", 20_000) { arm.all { joints.current.containsKey(it) } }
    }

    @After fun close() {
        if (!::c.isInitialized) return
        // Back to the launch file's state.
        runCatching {
            val now = c.listControllers().associate { it.name to it.state }
            if ("panda_hand_controller" !in now) { c.load("panda_hand_controller"); c.configure("panda_hand_controller") }
            if (c.listHardwareComponents().any { !it.active }) c.listHardwareComponents().filter { !it.active }.forEach { c.setHardwareActive(it.name, true) }
            c.switchControllers(listOf("panda_arm_controller", "panda_hand_controller"), listOf("panda_arm_position_controller").filter { now[it] == "active" }, strict = false)
        }
        pubs.values.forEach { runCatching { it.close() } }
        joints.close(); c.close()
    }

    private fun state(name: String) = c.listControllers().firstOrNull { it.name == name }?.state

    @Test fun listsControllersAndDetectsTheVersion() {
        val list = c.listControllers()
        assertEquals(setOf("joint_state_broadcaster", "panda_arm_controller", "panda_hand_controller", "panda_arm_position_controller"), list.map { it.name }.toSet())
        val jtc = list.first { it.name == "panda_arm_controller" }
        assertEquals("active", jtc.state)
        assertEquals(ControllerCommands.Kind.TRAJECTORY, jtc.kind)
        assertEquals(arm, jtc.joints)
        assertEquals("inactive", state("panda_arm_position_controller"))
        assertNotNull("version not detected", c.detected)
        // The hardware: the arm and the hand, active, with the arm's position interfaces claimed by the trajectory controller.
        val hw = c.listHardwareComponents()
        assertEquals(setOf("PandaFakeSystem", "PandaHandFakeSystem"), hw.map { it.name }.toSet())
        assertTrue(hw.all { it.active })
        val armHw = hw.first { it.name == "PandaFakeSystem" }
        assertTrue(armHw.command.filter { it.name.endsWith("/position") }.all { it.claimed })
        // Kilted/Lyrical and Jazzy only differ in the hardware components: it is settled now.
        val distro = System.getenv("ROS_DISTRO").orEmpty()
        val expected = mapOf("humble" to ControlMsgs.HUMBLE, "jazzy" to ControlMsgs.JAZZY, "kilted" to ControlMsgs.KILTED, "lyrical" to ControlMsgs.KILTED)[distro]
        if (expected != null) assertEquals(expected, c.detected)
        val (cmd, st) = c.listHardwareInterfaces()
        assertTrue(cmd.any { it.name == "panda_joint1/position" } && st.any { it.name == "panda_joint1/velocity" })
    }

    @Test fun trajectoryControllerMovesTheArm() {
        val jtc = c.listControllers().first { it.name == "panda_arm_controller" }
        val goal = arm.map { j -> joints.current.getValue(j) }.toMutableList()
        goal[0] = if (goal[0] > 0) goal[0] - 0.4 else goal[0] + 0.4
        E2E.waitFor("no /rc/robot_description", 20_000) { joints.urdf != null }
        val range = joints.range("panda_joint4", "position")
        assertEquals(0.0873, range.endInclusive, 1e-3) // from the URDF in /rc/robot_description (not the default ±π)
        assertTrue(ControllerCommands.send(E2E.registry, manager, jtc, arm, goal, 1.0, pubs))
        E2E.waitFor({ "joint1 did not reach ${goal[0]}: ${joints.current["panda_joint1"]}" }, 15_000) {
            Math.abs(joints.current.getValue("panda_joint1") - goal[0]) < 0.01
        }
    }

    @Test fun switchingToTheForwardControllerAndBack() {
        // Strict: the forward controller wants the joints the trajectory controller holds.
        val refused = c.switchControllers(listOf("panda_arm_position_controller"), emptyList())
        assertFalse("activated a controller whose joints were taken", refused.ok)
        assertEquals("inactive", state("panda_arm_position_controller"))
        // Both in one switch, as the panel does after asking.
        assertTrue(c.switchControllers(listOf("panda_arm_position_controller"), listOf("panda_arm_controller")).ok)
        assertEquals("active", state("panda_arm_position_controller"))
        assertEquals("inactive", state("panda_arm_controller"))
        val fwd = c.listControllers().first { it.name == "panda_arm_position_controller" }
        assertEquals(ControllerCommands.Kind.FORWARD, fwd.kind)
        assertEquals(arm, fwd.joints)
        val goal = arm.map { joints.current.getValue(it) }.toMutableList().also { it[1] = it[1] + 0.2 }
        // The publisher needs a moment to be matched: send until the joint follows.
        E2E.waitFor({ "joint2 did not reach ${goal[1]}: ${joints.current["panda_joint2"]}" }, 15_000) {
            ControllerCommands.send(E2E.registry, manager, fwd, arm, goal, 0.0, pubs)
            Math.abs(joints.current.getValue("panda_joint2") - goal[1]) < 0.01
        }
        assertTrue(c.switchControllers(listOf("panda_arm_controller"), listOf("panda_arm_position_controller")).ok)
        assertEquals("active", state("panda_arm_controller"))
    }

    @Test fun unloadLoadConfigureActivate() {
        assertTrue(c.switchControllers(emptyList(), listOf("panda_hand_controller")).ok)
        assertTrue(c.unload("panda_hand_controller"))
        assertEquals(null, state("panda_hand_controller"))
        // Still declared in the manager's parameters: offered to be loaded again.
        val declared = c.configuredButNotLoaded(c.listControllers().map { it.name })
        assertTrue("not offered: $declared", declared.any { it.first == "panda_hand_controller" && it.second.endsWith("ForwardCommandController") })
        assertTrue(c.load("panda_hand_controller"))
        assertEquals("unconfigured", state("panda_hand_controller"))
        assertTrue(c.configure("panda_hand_controller"))
        assertEquals("inactive", state("panda_hand_controller"))
        assertTrue(c.switchControllers(listOf("panda_hand_controller"), emptyList()).ok)
        assertEquals("active", state("panda_hand_controller"))
    }

    @Test fun hardwareComponentOffAndOn() {
        assertTrue(c.setHardwareActive("PandaHandFakeSystem", false))
        E2E.waitFor("the hand is still active", 10_000) { c.listHardwareComponents().first { it.name == "PandaHandFakeSystem" }.stateId == ControllerManagerClient.STATE_INACTIVE }
        assertTrue(c.setHardwareActive("PandaHandFakeSystem", true))
        E2E.waitFor("the hand did not come back", 10_000) { c.listHardwareComponents().first { it.name == "PandaHandFakeSystem" }.active }
    }
}
