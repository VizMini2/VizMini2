// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RosConfigTest {

    @Test
    fun domainIdRange() {
        assertNull(RosConfig.validateDomainId("0"))
        assertNull(RosConfig.validateDomainId(" 42 "))
        assertNull(RosConfig.validateDomainId("232"))
        assertNotNull(RosConfig.validateDomainId("233"))
        assertNotNull(RosConfig.validateDomainId("-1"))
        assertNotNull(RosConfig.validateDomainId(""))
        assertNotNull(RosConfig.validateDomainId("abc"))
    }

    /** The services format goes in the profiles JSON; an old one (without the field) stays on automatic. */
    @Test
    fun serviceFormatSurvivesJson() {
        for (f in ServiceFormat.entries) {
            val c = RosConfig(domainId = 7, serviceFormat = f)
            assertEquals(c, RosConfig.fromJson(org.json.JSONObject(c.toJson().toString())))
        }
        assertEquals(ServiceFormat.AUTO, RosConfig.fromJson(org.json.JSONObject("""{"domainId": 3}""")).serviceFormat)
        assertEquals(ServiceFormat.AUTO, ServiceFormat.fromKey("nonsense"))
    }

    @Test
    fun nodeNameRules() {
        assertNull(RosConfig.validateNodeName("rviz2mini"))
        assertNull(RosConfig.validateNodeName("_tablet_01"))
        assertNotNull(RosConfig.validateNodeName(""))
        assertNotNull(RosConfig.validateNodeName("1node"))
        assertNotNull(RosConfig.validateNodeName("my-node"))
        assertNotNull(RosConfig.validateNodeName("ns/node"))
    }

    @Test
    fun whitelistParsing() {
        assertEquals(emptyList<String>(), RosConfig().whitelistEntries())
        assertEquals(listOf("wlan0", "192.168.1.10"), RosConfig(interfaceWhitelist = " wlan0, ,192.168.1.10 ,").whitelistEntries())
    }

    @Test
    fun peersParsingAndValidation() {
        assertEquals(listOf("192.168.1.10", "10.0.0.2"), RosConfig(discoveryPeers = "192.168.1.10; 10.0.0.2,  192.168.1.10").peerEntries())
        assertEquals(null, RosConfig.validatePeers(""))
        assertEquals(null, RosConfig.validatePeers("192.168.200.124, 10.0.0.1"))
        assertEquals("\"robot.local\" is not an IPv4 address", RosConfig.validatePeers("10.0.0.1, robot.local"))
        assertEquals("\"300.1.1.1\" is not an IPv4 address", RosConfig.validatePeers("300.1.1.1"))
    }

    @Test
    fun discoveryServersValidation() {
        assertEquals(null, RosConfig.validateServers("192.168.1.10, 10.0.0.1:11888"))
        assertEquals("\"10.0.0.1:99999\" is not ip or ip:port", RosConfig.validateServers("10.0.0.1:99999"))
        assertEquals(listOf("10.0.0.1:11811"), RosConfig(discoveryServers = " 10.0.0.1:11811 ").serverEntries())
    }
}
