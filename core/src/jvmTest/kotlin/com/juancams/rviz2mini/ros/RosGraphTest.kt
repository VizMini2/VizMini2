// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RosGraphTest {

    private val g = "0".repeat(32)

    private val graph = RosGraph.parse(
        arrayOf(
            "W\trt/cmd_vel\tgeometry_msgs::msg::dds_::Twist_\t$g",
            "R\trt/cmd_vel\tgeometry_msgs::msg::dds_::Twist_\t$g",
            "R\trt/cmd_vel\tgeometry_msgs::msg::dds_::Twist_\t$g",
            "W\trt/camera/image_raw\tsensor_msgs::msg::dds_::Image_\t$g",
            "R\trt/only_subscribed\tstd_msgs::msg::dds_::String_\t$g",
            "W\trt/nav/_action/feedback\tnav2_msgs::action::dds_::NavigateToPose_FeedbackMessage_\t$g",
            "R\trq/add_two_intsRequest\texample_interfaces::srv::dds_::AddTwoInts_Request_\t$g",
            "W\trr/add_two_intsReply\texample_interfaces::srv::dds_::AddTwoInts_Response_\t$g",
            "W\trq/client_onlyRequest\tstd_srvs::srv::dds_::Trigger_Request_\t$g",
            "R\trq/nav/_action/send_goalRequest\tnav2_msgs::action::dds_::NavigateToPose_SendGoal_Request_\t$g",
            "R\trq/talker/get_stateRequest\tlifecycle_msgs::srv::dds_::GetState_Request_\t$g",
            "R\trq/ns/camera_driver/get_stateRequest\tlifecycle_msgs::srv::dds_::GetState_Request_\t$g",
            "R\trq/fake/get_stateRequest\tstd_srvs::srv::dds_::Trigger_Request_\t$g",
            "R\tros_discovery_info\trmw_dds_common::msg::dds_::ParticipantEntitiesInfo_\t$g",
            "basura",
        )
    )

    @Test
    fun topicsLikeRos2TopicList() {
        val topics = graph.topics()
        assertEquals(listOf("/camera/image_raw", "/cmd_vel", "/only_subscribed"), topics.map { it.name })
        val cmd = topics.first { it.name == "/cmd_vel" }
        assertEquals("geometry_msgs/msg/Twist", cmd.type)
        assertEquals(1, cmd.publishers)
        assertEquals(2, cmd.subscribers)
        assertTrue(graph.topics(includeHidden = true).any { it.name == "/nav/_action/feedback" })
    }

    @Test
    fun topicsOfTypeNeedAPublisher() {
        assertEquals(listOf("/camera/image_raw"), graph.topicsOfType("sensor_msgs/msg/Image").map { it.name })
        assertEquals(emptyList<String>(), graph.topicsOfType("std_msgs/msg/String").map { it.name })
    }

    @Test
    fun servicesHideActionsAndKeepClients() {
        val services = graph.services()
        assertEquals(listOf("/add_two_ints", "/client_only", "/fake/get_state", "/ns/camera_driver/get_state", "/talker/get_state"), services.map { it.name })
        val add = services.first { it.name == "/add_two_ints" }
        assertEquals("example_interfaces/srv/AddTwoInts", add.type)
        assertEquals(1, add.servers)
        assertEquals(0, services.first { it.name == "/client_only" }.servers)
    }

    @Test
    fun actionsFromSendGoal() {
        val actions = graph.actions()
        assertEquals(1, actions.size)
        assertEquals("/nav", actions[0].name)
        assertEquals("nav2_msgs/action/NavigateToPose", actions[0].type)
    }

    @Test
    fun lifecycleNodesNeedTheGetStateType() {
        assertEquals(listOf("/ns/camera_driver", "/talker"), graph.lifecycleNodes())
    }

    @Test
    fun nodesFromDiscoveryInfoIncludeLifecycle() {
        val talkerPrefix = "aa".repeat(12)
        val gonePrefix = "bb".repeat(12)
        val g = RosGraph.parse(
            arrayOf(
                "W\trt/chatter\tstd_msgs::msg::dds_::String_\t${talkerPrefix}00000103",
                "R\trq/talker/get_stateRequest\tlifecycle_msgs::srv::dds_::GetState_Request_\t${talkerPrefix}00000204",
                "R\trt/cmd_vel\tgeometry_msgs::msg::dds_::Twist_\t${talkerPrefix}00000304",
                "R\trq/lonely/get_stateRequest\tlifecycle_msgs::srv::dds_::GetState_Request_\t${"cc".repeat(12)}00000104",
            ),
            nodeInfo = mapOf(
                talkerPrefix + "000001c1" to listOf(
                    RosNodes.Entry("/", "talker", listOf("${talkerPrefix}00000204", "${talkerPrefix}00000304"), listOf("${talkerPrefix}00000103")),
                    RosNodes.Entry("/", "_ros2cli_123", emptyList(), emptyList()),
                ),
                gonePrefix + "000001c1" to listOf(RosNodes.Entry("/ns", "gone", emptyList(), emptyList())),
            )
        )
        val nodes = g.nodes()
        assertEquals(listOf("/lonely", "/talker"), nodes.map { it.name })
        val talker = nodes.first { it.name == "/talker" }
        assertTrue(talker.isLifecycle)
        assertEquals(listOf("/chatter"), talker.publishers)
        assertEquals(listOf("/cmd_vel"), talker.subscribers)
        assertEquals(listOf("/talker/get_state"), talker.services)
        assertTrue(nodes.first { it.name == "/lonely" }.isLifecycle)
    }

    @Test
    fun participantsWithoutDiscoveryInfoUseTheirName() {
        val jros = "dd".repeat(12)
        val rmw = "ee".repeat(12)
        val g = RosGraph.parse(
            arrayOf(
                "W\trt/scan\tsensor_msgs::msg::dds_::LaserScan_\t${jros}00000103",
                "R\trt/cmd_vel\tgeometry_msgs::msg::dds_::Twist_\t${jros}00000204",
                "W\trt/chatter\tstd_msgs::msg::dds_::String_\t${rmw}00000103",
            ),
            nodeInfo = mapOf(rmw + "000001c1" to listOf(RosNodes.Entry("/", "talker", emptyList(), listOf("${rmw}00000103")))),
            participantNames = mapOf(jros to "demo_robot", rmw to "/")
        )
        val nodes = g.nodes()
        assertEquals(listOf("/demo_robot", "/talker"), nodes.map { it.name })
        assertEquals(listOf("/scan"), nodes[0].publishers)
        assertEquals(listOf("/cmd_vel"), nodes[0].subscribers)
    }

    /** Each node's DDS comes from its GUID's vendor: Fast DDS (010f), Cyclone DDS (0110) or another without a name. */
    @Test
    fun nodesKnowTheirDdsVendor() {
        val fast = "010f" + "11".repeat(10)
        val cyclone = "0110" + "22".repeat(10)
        val other = "01ff" + "33".repeat(10)
        val g = RosGraph.parse(
            arrayOf(
                "W\trt/chatter\tstd_msgs::msg::dds_::String_\t${fast}00000103",
                "W\trt/scan\tsensor_msgs::msg::dds_::LaserScan_\t${cyclone}00000103",
                "W\trt/odom\tnav_msgs::msg::dds_::Odometry_\t${other}00000103",
            ),
            nodeInfo = mapOf(
                fast + "000001c1" to listOf(RosNodes.Entry("/", "talker", emptyList(), listOf("${fast}00000103"))),
                cyclone + "000001c1" to listOf(RosNodes.Entry("/", "lidar", emptyList(), listOf("${cyclone}00000103"))),
                other + "000001c1" to listOf(RosNodes.Entry("/", "odom", emptyList(), listOf("${other}00000103"))),
            )
        )
        val byName = g.nodes().associate { it.name to it.dds }
        assertEquals(mapOf("/lidar" to "Cyclone DDS", "/odom" to null, "/talker" to "Fast DDS"), byName)
        assertEquals(null, RosGraph.ddsVendor("bridge:s3"))
    }

    @Test
    fun ownParticipantUsesTheAppNodeName() {
        val own = "ff".repeat(12)
        val g = RosGraph.parse(
            arrayOf("R\trt/tf\ttf2_msgs::msg::dds_::TFMessage_\t${own}00000104", "W\trt/scan\tsensor_msgs::msg::dds_::LaserScan_\t${own}00000203"),
            ownName = "rviz2mini_demo"
        )
        assertEquals(listOf("/rviz2mini_demo"), g.nodes().map { it.name })
        assertEquals(listOf("/scan"), g.nodes()[0].publishers)
    }

    @Test
    fun gidHex() {
        val gid = mapOf("data" to (0 until 16).map { it.toLong() * 17 })
        assertEquals("00112233445566778899aabbccddeeff", RosNodes.gidHex(gid))
    }

    @Test
    fun discoveryInfoDefinitionsParse() {
        val r = com.juancams.rviz2mini.ros.dyn.InterfaceRegistry()
        for ((name, text) in RosNodes.DEFINITIONS) r.define(name, text)
        val spec = r.msg("rmw_dds_common/msg/ParticipantEntitiesInfo")
        assertEquals("rmw_dds_common::msg::dds_::ParticipantEntitiesInfo_", spec.ddsName)
        assertEquals(listOf("gid", "node_entities_info_seq"), spec.fields.map { it.name })
    }
}
