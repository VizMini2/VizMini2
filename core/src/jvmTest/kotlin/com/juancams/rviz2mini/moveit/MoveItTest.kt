// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.moveit

import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.rviz.geom.UrdfKinematics
import com.juancams.rviz2mini.rviz.geom.UrdfParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** SRDF, kinematics and the MoveGroup goal with the moveit_resources Panda (real URDF and SRDF). */
class MoveItTest {
    private fun res(n: String) = javaClass.getResource("/moveit/$n")!!.readText()
    private val urdf = UrdfParser.parse(res("panda.urdf"))
    private val srdf = Srdf.parse(res("panda.srdf"))

    @Test fun groupsAndStates() {
        assertEquals((1..7).map { "panda_joint$it" }, srdf.groupJoints("panda_arm", urdf))
        // The second finger copies the first one (mimic): only one moves.
        assertEquals(listOf("panda_finger_joint1"), srdf.groupJoints("hand", urdf))
        assertEquals((1..7).map { "panda_joint$it" } + "panda_finger_joint1", srdf.groupJoints("panda_arm_hand", urdf))
        assertTrue(srdf.statesOf("panda_arm").map { it.name }.containsAll(listOf("ready", "extended", "transport")))
        assertEquals(-2.356, srdf.statesOf("panda_arm").first { it.name == "ready" }.values["panda_joint4"]!!, 1e-9)
        val j4 = urdf.joints.first { it.name == "panda_joint4" }
        assertEquals(-3.1416, j4.lower, 1e-9); assertEquals(0.0873, j4.upper, 1e-9)
    }

    @Test fun forwardKinematics() {
        val k = UrdfKinematics(urdf)
        assertEquals("panda_link0", k.root)
        // All at zero: the Panda stands stretched upwards; the flange (link8) at 0.333+0.316+0.384+0.107 height and 0.088 forward.
        val zero = k.linkPoses(emptyMap())
        val f = zero["panda_link8"]!!
        assertEquals(0.088, f[12], 1e-6); assertEquals(0.0, f[13], 1e-6); assertEquals(0.333 + 0.316 + 0.384 - 0.107, f[14], 1e-6)
        // Rotate the base 90°: the flange moves onto the y axis.
        val turned = k.linkPoses(mapOf("panda_joint1" to Math.PI / 2))["panda_link8"]!!
        assertEquals(0.0, turned[12], 1e-6); assertEquals(0.088, turned[13], 1e-6)
        // The fingers: the second one (mimic) opens the same as the first, towards the other side.
        val open = k.linkPoses(mapOf("panda_finger_joint1" to 0.03))
        val hand = open["panda_hand"]!!; val l = open["panda_leftfinger"]!!; val r = open["panda_rightfinger"]!!
        val dl = Math.hypot(l[12] - hand[12], l[13] - hand[13]); val dr = Math.hypot(r[12] - hand[12], r[13] - hand[13])
        assertEquals(0.03, dl, 1e-6); assertEquals(0.03, dr, 1e-6)
    }

    @Test fun moveGroupGoalEncodes() {
        val registry = InterfaceRegistry().apply {
            loadBundle(listOf("../app/src/main/assets/ros_interfaces.txt", "app/src/main/assets/ros_interfaces.txt").map(::File).first { it.isFile }.readText())
        }
        val c = MoveItClient(registry)
        val spec = registry.action("moveit_msgs/action/MoveGroup")
        val goal = DynCodec.fromJson(spec.goal, JsonGoal.of(c))
        val req = goal["request"] as MsgValue
        assertEquals(0.3, (req["max_velocity_scaling_factor"] as Number).toDouble(), 1e-9)
        val opts = goal["planning_options"] as MsgValue
        assertEquals(true, opts["plan_only"])
        // Encodes to CDR without complaining (all fields with their type).
        assertTrue(com.juancams.rviz2mini.ros.dyn.CdrBytes.serialize(spec.goal, goal).size > 100)
        registry.action("moveit_msgs/action/ExecuteTrajectory")
    }

    @Test fun ikAndSceneRequestsEncode() {
        val registry = InterfaceRegistry().apply {
            loadBundle(listOf("../app/src/main/assets/ros_interfaces.txt", "app/src/main/assets/ros_interfaces.txt").map(::File).first { it.isFile }.readText())
        }
        val c = MoveItClient(registry)
        val pose = com.juancams.rviz2mini.rviz.geom.UrdfParser.poseFromXyzRpy(0.3, 0.0, 0.6, Math.PI, 0.0, 0.0)
        val ik = registry.srv("moveit_msgs/srv/GetPositionIK")
        val req = DynCodec.fromJson(ik.request, c.ikRequest("panda_link0", "panda_link8", pose))
        val ps = ((req["ik_request"] as MsgValue)["pose_stamped"] as MsgValue)
        val q = (ps["pose"] as MsgValue)["orientation"] as MsgValue
        assertEquals(1.0, Math.abs((q["x"] as Number).toDouble()), 1e-9) // 180° about x
        assertTrue(com.juancams.rviz2mini.ros.dyn.CdrBytes.serialize(ik.request, req).isNotEmpty())
        val apply = registry.srv("moveit_msgs/srv/ApplyPlanningScene")
        val scene = DynCodec.fromJson(apply.request, c.boxScene("panda_link0", listOf("b1"), doubleArrayOf(0.4, 0.0, 0.3), 0.1, remove = false))
        val obj = (((scene["scene"] as MsgValue)["world"] as MsgValue)["collision_objects"] as List<*>).first() as MsgValue
        assertEquals("b1", obj["id"])
        assertEquals(listOf(0.1, 0.1, 0.1), ((obj["primitives"] as List<*>).first() as MsgValue)["dimensions"])
        assertTrue(com.juancams.rviz2mini.ros.dyn.CdrBytes.serialize(apply.request, scene).isNotEmpty())
    }

    @Test fun ikSeedHasOnlyThisRobotsJoints() {
        val registry = InterfaceRegistry().apply {
            loadBundle(listOf("../app/src/main/assets/ros_interfaces.txt", "app/src/main/assets/ros_interfaces.txt").map(::File).first { it.isFile }.readText())
        }
        val c = MoveItClient(registry)
        // Before the URDF arrives every joint passes; with it, only the robot's (another robot's wheels are dropped).
        assertEquals(setOf("panda_joint1", "left_wheel_joint"), c.robotJoints(mapOf("panda_joint1" to 0.0, "left_wheel_joint" to 1.0)).keys)
        val f = MoveItClient::class.java.getDeclaredField("urdf").apply { isAccessible = true }
        f.set(c, urdf)
        assertEquals(setOf("panda_joint1"), c.robotJoints(mapOf("panda_joint1" to 0.0, "left_wheel_joint" to 1.0)).keys)
        val names = ((c.ikRequest("panda_link0", "panda_link8", com.juancams.rviz2mini.TfMath.identity())
            .getJSONObject("ik_request").getJSONObject("robot_state").getJSONObject("joint_state").getJSONArray("name")))
        assertTrue((0 until names.length()).none { names.getString(it) == "left_wheel_joint" })
    }

    @Test fun urdfThatGoesWithMoveIt() {
        val base = UrdfParser.parse("<robot name=\"mobile_base\"><link name=\"base_link\"/></robot>")
        val both = mapOf("mobile_base" to base, "panda" to urdf)
        assertEquals("panda", MoveItClient.chooseUrdf(both, srdf.robot)?.name)
        assertEquals("mobile_base", MoveItClient.chooseUrdf(mapOf("mobile_base" to base), "panda")?.name) // only one: that one
        assertEquals(null, MoveItClient.chooseUrdf(emptyMap(), "panda"))
    }

    @Test fun quaternionRoundTrip() {
        for (rpy in listOf(doubleArrayOf(0.1, -0.4, 2.0), doubleArrayOf(Math.PI, 0.0, 0.0), doubleArrayOf(0.0, Math.PI / 2 - 0.01, 0.3))) {
            val m = com.juancams.rviz2mini.rviz.geom.UrdfParser.poseFromXyzRpy(0.0, 0.0, 0.0, rpy[0], rpy[1], rpy[2])
            val q = com.juancams.rviz2mini.TfMath.toQuaternion(m)
            val back = com.juancams.rviz2mini.TfMath.fromTranslationQuaternion(0.0, 0.0, 0.0, q[0], q[1], q[2], q[3])
            for (i in 0 until 16) assertEquals("rpy ${rpy.toList()} [$i]", m[i], back[i], 1e-9)
        }
    }

    @Test fun tipOfTheArm() {
        assertEquals("panda_link8", srdf.tipLink("panda_arm"))
        assertEquals("panda_link8", srdf.tipLink("panda_arm_hand"))
    }

    @Test fun olderMoveItLayouts() {
        val registry = InterfaceRegistry().apply {
            loadBundle(listOf("../app/src/main/assets/ros_interfaces.txt", "app/src/main/assets/ros_interfaces.txt").map(::File).first { it.isFile }.readText())
        }
        val c = MoveItClient(registry)
        fun goalBytes(v: MoveItMsgs): Int {
            val spec = MoveItMsgs.registry(registry, v).action("moveit_msgs/action/MoveGroup").goal
            return com.juancams.rviz2mini.ros.dyn.CdrBytes.serialize(spec, DynCodec.fromJson(spec, c.moveGroupGoal(true))).size
        }
        // Jazzy and Humble: no smoothness_level (8 bytes) in the MoveGroup goal.
        assertEquals(goalBytes(MoveItMsgs.V27) - 8, goalBytes(MoveItMsgs.V26))
        assertEquals(goalBytes(MoveItMsgs.V26), goalBytes(MoveItMsgs.V22))
        // Humble: MoveItErrorCodes with only val; ExecuteTrajectory without controller_names.
        val h = MoveItMsgs.registry(registry, MoveItMsgs.V22)
        assertEquals(listOf("val"), h.msg("moveit_msgs/msg/MoveItErrorCodes").fields.map { it.name })
        assertEquals(listOf("trajectory"), h.action("moveit_msgs/action/ExecuteTrajectory").goal.fields.map { it.name })
        assertEquals(listOf("val", "message", "source"), registry.msg("moveit_msgs/msg/MoveItErrorCodes").fields.map { it.name })
        assertEquals(listOf("trajectory", "controller_names"), registry.action("moveit_msgs/action/ExecuteTrajectory").goal.fields.map { it.name })
        // Humble requests are read with what we send (GetCartesianPath: the same fields at the start).
        val cart = h.srv("moveit_msgs/srv/GetCartesianPath")
        assertTrue(com.juancams.rviz2mini.ros.dyn.CdrBytes.serialize(cart.request, DynCodec.fromJson(cart.request,
            c.cartesianRequest("panda_link0", "panda_link8", com.juancams.rviz2mini.TfMath.identity()))).isNotEmpty())
    }

    private object JsonGoal { fun of(c: MoveItClient) = c.moveGroupGoal(planOnly = true) }
}

/** MoveIt's Panda .rviz: Trajectory and MotionPlanning become Trajectory displays (and the RobotModel stays). */
class MoveItRvizImportTest {
    @Test fun importsTrajectoryDisplays() {
        val text = javaClass.getResource("/moveit/panda_moveit.rviz")!!.readText()
        val r = com.juancams.rviz2mini.rviz.rvizfile.RvizImporter.import(text, 1000)
        val traj = r.displays.filter { it.type == com.juancams.rviz2mini.rviz.display.DisplayType.TRAJECTORY }
        assertTrue("sin Trajectory: ${r.displays.map { "${it.type}:${it.name}" }}", traj.isNotEmpty())
        assertTrue(traj.all { it.topic == "/display_planned_path" })
        assertEquals("/robot_description", traj.first().string("description"))
    }
}
