// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownRobotsTest {

    @Test
    fun remembersOthersPerDomainMostRecentFirst() {
        val b = KnownRobots.Book()
        assertTrue(b.remember(77, listOf("192.168.1.10" to "robot", "192.168.1.5" to "me"), ownIps = setOf("192.168.1.5"), nowMs = 1000))
        assertTrue(b.remember(77, listOf("192.168.1.20" to "pc"), emptySet(), 2000))
        assertFalse(b.remember(77, listOf("192.168.1.10" to ""), emptySet(), 3000))
        assertEquals(listOf("192.168.1.10", "192.168.1.20"), b.peers(77, 4000))
        assertEquals("robot", b.byDomain[77]!!.first().name)
        assertEquals(emptyList<String>(), b.peers(0, 4000))
    }

    @Test
    fun ignoresLoopbackForgetsOldAndRoundTrips() {
        val b = KnownRobots.Book()
        b.remember(5, listOf("127.0.0.1" to "x", "10.0.0.2" to "old"), emptySet(), 0)
        assertEquals(listOf("10.0.0.2"), b.peers(5, 1000))
        assertEquals(emptyList<String>(), b.peers(5, KnownRobots.Book.FORGET_AFTER_MS + 1))
        val back = KnownRobots.Book.fromJson(b.toJson())
        assertEquals(b.byDomain, back.byDomain)
        back.forget(5, "10.0.0.2")
        assertEquals(emptyList<String>(), back.peers(5, 1000))
    }

    @Test
    fun capsTheList() {
        val b = KnownRobots.Book()
        for (i in 0 until 30) b.remember(1, listOf("10.0.0.$i" to ""), emptySet(), i.toLong())
        assertEquals(KnownRobots.Book.MAX_PER_DOMAIN, b.byDomain[1]!!.size)
        assertEquals(KnownRobots.Book.MAX_PEERS, b.peers(1, 100).size)
        assertEquals("10.0.0.29", b.peers(1, 100).first())
    }

    @Test
    fun onlyReachableSubnetsAreRemembered() {
        assertTrue(KnownRobots.Book.sameSubnet("192.168.200.124", "192.168.200.170", 24))
        assertFalse(KnownRobots.Book.sameSubnet("172.17.0.1", "192.168.200.170", 24))
        assertTrue(KnownRobots.Book.sameSubnet("10.1.2.3", "10.9.9.9", 8))
        val b = KnownRobots.Book()
        b.remember(77, listOf("192.168.200.124" to "pc", "172.17.0.1" to "docker"), emptySet(), 1, listOf("192.168.200.170" to 24))
        assertEquals(listOf("192.168.200.124"), b.peers(77, 2))
    }

    @Test
    fun hiddenNodesAreNotUsedAsNames() {
        assertEquals("/robot", KnownRobots.Book.cleanName("/_ros2cli_daemon_0_abc, /robot"))
        assertEquals("", KnownRobots.Book.cleanName("/"))
        val b = KnownRobots.Book()
        b.remember(0, listOf("10.0.0.5" to "/_ros2cli_daemon_0_abc"), emptySet(), 1)
        assertEquals("", b.byDomain[0]!!.first().name)
    }
}
