// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.e2e

import com.juancams.rviz2mini.moveit.MoveItClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Against MoveIt with the Panda (tools/moveit_panda_headless.launch.py): groups from the SRDF, planning, executing,
 * moving the gripper with IK, a straight line, the planners, obstacles and the gripper.
 */
class MoveItE2ETest {
    private lateinit var c: MoveItClient

    @Before fun connect() {
        E2E.connect("/display_planned_path", 60_000)
        c = MoveItClient(E2E.registry)
        c.start()
        E2E.waitFor({ "no URDF/SRDF/joint states: srdf=${c.srdf != null} urdf=${c.urdf != null} joints=${c.hasJointStates}" }, 30_000) { c.srdf != null && c.urdf != null && c.hasJointStates }
    }

    @After fun close() { if (::c.isInitialized) c.close() }

    private fun done(what: String, timeoutMs: Long = 60_000) = E2E.waitFor({ "$what: ${c.status}" }, timeoutMs) { !c.busy }

    private fun moveTo(state: String) {
        c.group = "panda_arm"
        c.applyNamedState(state)
        c.planAndExecute(); done("to $state")
        assertTrue("$state failed: ${c.status}", c.status.orEmpty().startsWith("Done"))
        Thread.sleep(500)
    }

    @Test fun groupsAndStates() {
        assertTrue(c.groups().containsAll(listOf("panda_arm", "hand")))
        c.group = "panda_arm"
        assertEquals((1..7).map { "panda_joint$it" }, c.groupJoints())
        assertTrue(c.namedStates().containsAll(listOf("ready", "extended")))
    }

    @Test fun planThenExecute() {
        moveTo("ready")
        c.applyNamedState("extended")
        c.plan(); done("plan")
        assertTrue("no plan: ${c.status}", c.lastPlan != null)
        c.execute(); done("execute")
        assertEquals(0.0, c.current["panda_joint4"]!!, 0.05)
    }

    @Test fun ikStraightLineAndObstacle() {
        moveTo("ready")
        val z0 = c.tipPose()!![14]
        c.jog(0.0, 0.0, -0.05); done("IK")
        assertEquals("IK: ${c.status}", z0 - 0.05, c.tipPose()!![14], 0.005)
        c.cartesian = true
        c.planAndExecute(); done("straight line")
        assertTrue(c.status.orEmpty(), c.status.orEmpty().startsWith("Done in a straight line"))
        c.cartesian = false
        c.applyNamedState("extended")
        c.addBox(); done("box")
        assertTrue("no box: ${c.status}", c.boxes.isNotEmpty())
        c.planAndExecute(); done("around the box")
        assertTrue(c.status.orEmpty(), c.status.orEmpty().startsWith("Done"))
        c.clearBoxes(); done("clear")
        assertTrue(c.boxes.isEmpty())
    }

    @Test fun plannersAndGripper() {
        c.queryPlanners()
        E2E.waitFor("no planners", 10_000) { c.planners.isNotEmpty() }
        assertTrue(c.planners.toString(), c.planners.any { it.second == "PTP" })
        c.group = "hand"
        c.applyNamedState("open")
        c.planAndExecute(); done("gripper")
        Thread.sleep(800)
        assertEquals(0.035, c.current["panda_finger_joint1"]!!, 0.005)
    }
}
