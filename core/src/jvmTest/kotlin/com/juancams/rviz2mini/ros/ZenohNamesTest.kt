// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** rmw_zenoh_cpp names and graph, with real tokens (rmw_zenoh's docs/design.md and those of fake_robot on Lyrical). */
class ZenohNamesTest {

    private val listenerNode = "@ros2_lv/0/aac3178e146ba6f1fc6e6a4085e77f21/0/0/NN/%/%/listener"
    private val listenerSub = "@ros2_lv/0/aac3178e146ba6f1fc6e6a4085e77f21/0/10/MS/%/%/listener/%chatter/std_msgs::msg::dds_::String_/" +
        "RIHS01_df668c740482bbd48fb39d76a70dfd4bd59db1288021743503259e948f6b1a18/::,10:,:,:,,"
    private val talkerNode = "@ros2_lv/0/8b20917502ee955ac4476e0266340d5c/0/0/NN/%/%robot1/talker"
    private val talkerPub = "@ros2_lv/0/8b20917502ee955ac4476e0266340d5c/0/10/MP/%/%robot1/talker/%robot1%chatter/std_msgs::msg::dds_::String_/" +
        "RIHS01_df668c740482bbd48fb39d76a70dfd4bd59db1288021743503259e948f6b1a18/::,7:,:,:,,"
    private val server = "@ros2_lv/0/f9980ee0495eaafb3e38f0d19e2eae12/0/10/SS/%/%/add_two_ints_server/%add_two_ints/example_interfaces::srv::dds_::AddTwoInts_/" +
        "RIHS01_e118de6bf5eeb66a2491b5bda11202e7b68f198d6f67922cf30364858239c81a/::,10:,:,:,,"
    private val serverNode = "@ros2_lv/0/f9980ee0495eaafb3e38f0d19e2eae12/0/0/NN/%/%/add_two_ints_server"
    // With the optional suffix of the types with buffers (PointCloud2, Image...).
    private val points = "@ros2_lv/77/4fca9f627d0b05176c62c7d2128c8f69/0/21/MP/%/%/fake_robot/%points/sensor_msgs::msg::dds_::PointCloud2_/" +
        "RIHS01_9198cabf7da3796ae6fe19c4cb3bdd3525492988c70522628af5daa124bae2b5/2::,5:,:,:,,/backends:cpu:"

    @Test
    fun parsesNodesEndpointsAndServices() {
        val n = ZenohNames.parseToken(listenerNode)!!
        assertEquals(ZenohNames.Kind.NODE, n.kind)
        assertEquals("/", n.namespace); assertEquals("listener", n.nodeName); assertEquals(0, n.domainId)
        val s = ZenohNames.parseToken(listenerSub)!!
        assertEquals(ZenohNames.Kind.SUBSCRIBER, s.kind)
        assertEquals("/chatter", s.name); assertEquals("std_msgs::msg::dds_::String_", s.ddsType)
        assertEquals("RIHS01_df668c740482bbd48fb39d76a70dfd4bd59db1288021743503259e948f6b1a18", s.hash)
        val p = ZenohNames.parseToken(talkerPub)!!
        assertEquals("/robot1", p.namespace); assertEquals("/robot1/chatter", p.name)
        val pc = ZenohNames.parseToken(points)!!
        assertEquals(77, pc.domainId); assertEquals("/points", pc.name); assertEquals(ZenohNames.Kind.PUBLISHER, pc.kind)
        assertNull(ZenohNames.parseToken("@ros2_lv/0/x"))
        assertNull(ZenohNames.parseToken("0/chatter/std_msgs::msg::dds_::String_/RIHS01_x"))
    }

    @Test
    fun dataKeysLikeRmwZenoh() {
        assertEquals("0/chatter/std_msgs::msg::dds_::String_/RIHS01_df",
            ZenohNames.dataKey(0, "/chatter", "std_msgs::msg::dds_::String_", "RIHS01_df"))
        assertEquals("2/robot1/chatter/std_msgs::msg::dds_::String_/*", ZenohNames.dataKey(2, "/robot1/chatter", "std_msgs::msg::dds_::String_"))
        assertEquals("@ros2_lv/7/**", ZenohNames.livelinessSelector(7))
        assertEquals("%robot1%chatter", ZenohNames.mangle("/robot1/chatter"))
        assertEquals("/robot1/chatter", ZenohNames.unmangle("%robot1%chatter"))
        assertEquals("example_interfaces::srv::dds_::AddTwoInts_Request_",
            ZenohNames.serviceMessageType("example_interfaces::srv::dds_::AddTwoInts_", "Request"))
    }

    /** The graph comes out with the same model as over DDS: nodes with their endpoints, topics, services and the "Zenoh" DDS. */
    @Test
    fun graphFromTokens() {
        val tokens = listOf(listenerNode, listenerSub, talkerNode, talkerPub, server, serverNode).map { ZenohNames.parseToken(it)!! }
        val g = ZenohNames.graph(tokens)
        assertEquals(listOf("/chatter", "/robot1/chatter"), g.topics(true).map { it.name }.sorted())
        assertEquals("std_msgs/msg/String", g.topics(true).first { it.name == "/robot1/chatter" }.type)
        val add = g.services().first { it.name == "/add_two_ints" }
        assertEquals("example_interfaces/srv/AddTwoInts", add.type)
        val nodes = g.nodes().associateBy { it.name }
        assertEquals(setOf("/listener", "/robot1/talker", "/add_two_ints_server"), nodes.keys)
        assertEquals(listOf("/robot1/chatter"), nodes.getValue("/robot1/talker").publishers)
        assertEquals(listOf("/chatter"), nodes.getValue("/listener").subscribers)
        assertEquals(listOf("/add_two_ints"), nodes.getValue("/add_two_ints_server").services)
        assertEquals("Zenoh", nodes.getValue("/listener").dds)
        assertEquals("RIHS01_df668c740482bbd48fb39d76a70dfd4bd59db1288021743503259e948f6b1a18",
            ZenohNames.hashOf(tokens, "/chatter", "std_msgs::msg::dds_::String_"))
        // A topic nobody uses yet: the hash of the same type on another topic.
        assertEquals("RIHS01_df668c740482bbd48fb39d76a70dfd4bd59db1288021743503259e948f6b1a18",
            ZenohNames.hashOf(tokens, "/nuevo", "std_msgs::msg::dds_::String_"))
        assertNull(ZenohNames.hashOf(tokens, "/nuevo", "std_msgs::msg::dds_::Bool_"))
    }
}
