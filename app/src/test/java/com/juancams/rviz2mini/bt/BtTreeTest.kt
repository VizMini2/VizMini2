// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

class BtTreeTest {

    /** The XML returned by a real Groot2Publisher (tools/bt_groot/bt_groot_server.cpp). */
    private val xml = javaClass.getResource("/bt/groot2_full_tree.xml")!!.readText()

    @Test
    fun parsesTheGroot2TreeWithItsSubtrees() {
        val t = Groot2.parseTree(xml, "abc")
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), t.nodes.keys.toList())
        assertEquals(listOf(1), t.roots)
        val patrol = t.nodes.getValue(1)
        assertEquals("patrol", patrol.name)
        assertEquals("Sequence", patrol.type)
        assertEquals(BtCategory.CONTROL, patrol.category)
        assertEquals(listOf(2, 5, 6), patrol.children)
        assertEquals(BtCategory.ACTION, t.nodes.getValue(3).category)
        assertEquals("Wait", t.nodes.getValue(4).type)
        assertEquals(mapOf("ms" to "600"), t.nodes.getValue(4).attributes)
        // The SubTree is expanded with the BehaviorTree of its _fullpath.
        val dock = t.nodes.getValue(6)
        assertEquals(BtCategory.SUBTREE, dock.category)
        assertEquals("Dock", dock.name)
        assertEquals(listOf(7), dock.children)
        assertEquals(listOf(8, 9), t.nodes.getValue(7).children)
        assertEquals(6, t.nodes.getValue(7).parent)
    }

    @Test
    fun categoriesWithoutTreeNodesModel() {
        val t = Groot2.parseTree("""
            <root BTCPP_format="4" main_tree_to_execute="M">
              <BehaviorTree ID="M">
                <ReactiveFallback _uid="1">
                  <GoalReached _uid="2"/>
                  <RateController _uid="3" hz="1.0"><ComputePathToPose _uid="4" goal="{goal}"/></RateController>
                  <Action ID="Spin" name="spin" _uid="5"/>
                </ReactiveFallback>
              </BehaviorTree>
            </root>""")
        assertEquals(BtCategory.CONTROL, t.nodes.getValue(1).category)
        assertEquals(BtCategory.CONDITION, t.nodes.getValue(2).category)
        assertEquals(BtCategory.DECORATOR, t.nodes.getValue(3).category)
        assertEquals(BtCategory.ACTION, t.nodes.getValue(4).category)
        // v3 format: the type is in ID.
        assertEquals("Spin", t.nodes.getValue(5).type)
        assertEquals("spin", t.nodes.getValue(5).name)
        assertEquals(BtCategory.ACTION, t.nodes.getValue(5).category)
    }

    @Test
    fun statusBufferAsSentByGroot2() {
        // Real response: 1 RUNNING, 3 IDLE after FAILURE (13), 4 IDLE after SUCCESS (12), 9 IDLE.
        val bytes = "01000102000203000d04000c050001060000070000080000090000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val s = Groot2.parseStatus(bytes)
        assertEquals(9, s.size)
        assertEquals(BtNodeState(BtStatus.RUNNING), s[1])
        assertEquals(BtNodeState(BtStatus.SUCCESS), s[2])
        assertEquals(BtNodeState(BtStatus.IDLE, BtStatus.FAILURE), s[3])
        assertEquals(BtNodeState(BtStatus.IDLE, BtStatus.SUCCESS), s[4])
        assertEquals(BtNodeState(BtStatus.IDLE), s[9])
    }

    @Test
    fun blackboardsAsSentByGroot2() {
        // Real BLACKBOARD response with "Main;Dock::6": {Main: {loops: 1, speed: 0.5f, target: "dock"}, Dock::6: nil}.
        val bytes = "82a44d61696e83a56c6f6f707301a57370656564ca3f000000a6746172676574a4646f636ba7446f636b3a3a36c0"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val bb = Groot2.parseBlackboards(bytes)
        assertEquals(listOf("Main"), bb.keys.toList())
        assertEquals(1L, bb.getValue("Main")["loops"])
        assertEquals(0.5, bb.getValue("Main")["speed"] as Double, 0.0)
        assertEquals("dock", bb.getValue("Main")["target"])
        // More MessagePack types.
        assertEquals(listOf(-1L, 300L, true, null, "é"), MsgPack.decode(byteArrayOf(0x95.toByte(), 0xFF.toByte(), 0xCD.toByte(), 0x01, 0x2C, 0xC3.toByte(), 0xC0.toByte(),
            0xA2.toByte(), 0xC3.toByte(), 0xA9.toByte())))
        assertEquals(listOf("Main", "Dock::6"), Groot2.parseTree(xml).blackboards)
    }

    @Test
    fun nav2XmlNumberedLikeBehaviorTreeCpp() {
        val t = Groot2.parseTree(javaClass.getResource("/bt/nav2_tree.xml")!!.readText())
        assertEquals((1..12).toList(), t.nodes.keys.toList())
        assertEquals("NavigateRecovery", t.nodes.getValue(1).name)
        assertEquals("ComputePathToPose", t.nodes.getValue(4).name)
        assertEquals(BtCategory.SUBTREE, t.nodes.getValue(5).category)
        assertEquals("FollowPath", t.nodes.getValue(7).type)
        assertEquals("Spin", t.nodes.getValue(11).name)
        assertEquals(BtCategory.CONTROL, t.nodes.getValue(1).category) // RecoveryNode, one of the known ones

        // The Jazzy log (with uid) and the Humble one (no uid, by name) land on the same nodes.
        for (humble in listOf(false, true)) {
            val log = BtLogState(t)
            log.apply("FollowPath", if (humble) null else 7, "IDLE", "RUNNING", 1)
            log.apply("Spin", if (humble) 0 else 11, "RUNNING", "FAILURE", 2)
            assertNull(log.apply("NotInTheTree", if (humble) null else 99, "IDLE", "RUNNING", 3))
            assertTrue(log.tree() === t)
            assertEquals(BtNodeState(BtStatus.RUNNING), log.states[7])
            assertEquals(BtNodeState(BtStatus.FAILURE), log.states[11])
        }
    }

    @Test
    fun recordedTransitionsAsSentByGroot2() {
        // Real GET_TRANSITIONS response: 368334 µs uid 6 SUCCESS, 368335 uid 7 RUNNING…
        val b = "ce9e05000000060002cf9e05000000070001".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(listOf(Triple(368334L, 6, BtStatus.SUCCESS), Triple(368335L, 7, BtStatus.RUNNING)), Groot2.parseTransitions(b))
    }

    @Test
    fun breakpointMessages() {
        // Real notice from the notification socket: header with type 'N' and the uid as text.
        val head = "024e36c20ba1".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(6, Groot2.parseBreakpointReached(listOf(head, "6".toByteArray())))
        assertNull(Groot2.parseBreakpointReached(listOf(Groot2.requestHeader(Groot2.STATUS, 1), "6".toByteArray())))
        assertTrue(Groot2.breakpointJson(6).contains("\"uid\":6"))
        assertTrue(Groot2.unlockJson(6, true).contains("\"remove_when_done\":true"))
    }

    /** Real breakpoint against a Groot2Publisher, if it is running (tools/bt_groot/bt_groot_server). */
    @Test
    fun realBreakpointIfRunning() {
        val up = try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", Groot2.DEFAULT_PORT), 300) }; true } catch (_: Exception) { false }
        assumeTrue(up)
        fun req(z: ZmqReq, type: Int, body: String? = null) = z.request(listOfNotNull(Groot2.requestHeader(type, 1), body?.toByteArray()))
        ZmqReq("127.0.0.1", Groot2.DEFAULT_PORT).use { z ->
            ZmqReq("127.0.0.1", Groot2.DEFAULT_PORT + 1, 5000, "SUB").use { sub ->
                sub.subscribe()
                Thread.sleep(300)
                req(z, Groot2.TOGGLE_RECORDING, "stop")
                req(z, Groot2.HOOK_INSERT, Groot2.breakpointJson(6))
                val uid = Groot2.parseBreakpointReached(sub.receive())
                assertEquals(6, uid)
                // Stopped (before running "approach"): nothing in the tree changes within 1.5 s.
                val paused = Groot2.parseStatus(req(z, Groot2.STATUS)[1])
                Thread.sleep(1500)
                assertEquals(paused, Groot2.parseStatus(req(z, Groot2.STATUS)[1]))
                // Continue: the tree goes on and the states change.
                req(z, Groot2.BREAKPOINT_UNLOCK, Groot2.unlockJson(6, remove = true))
                req(z, Groot2.REMOVE_ALL_HOOKS)
                Thread.sleep(1500)
                assertTrue(paused != Groot2.parseStatus(req(z, Groot2.STATUS)[1]))
            }
        }
    }

    @Test
    fun headers() {
        assertArrayEquals(byteArrayOf(2, 'S'.code.toByte(), 0x78, 0x56, 0x34, 0x12), Groot2.requestHeader(Groot2.STATUS, 0x12345678))
        val reply = Groot2.requestHeader(Groot2.STATUS, 42) + ByteArray(16) { it.toByte() }
        assertEquals("000102030405060708090a0b0c0d0e0f", Groot2.checkReply(reply, Groot2.STATUS, 42))
        assertThrows(IllegalStateException::class.java) { Groot2.checkReply(reply, Groot2.STATUS, 43) }
        assertThrows(IllegalStateException::class.java) { Groot2.checkReply(reply, Groot2.FULL_TREE, 42) }
    }

    @Test
    fun nav2LogAccumulatesNodes() {
        val log = BtLogState()
        val t = log.apply("ComputePathToPose", 12, "IDLE", "RUNNING", 5)!!
        assertEquals(BtTransition(5, 12, "ComputePathToPose", BtStatus.IDLE, BtStatus.RUNNING), t)
        log.apply("FollowPath", 14, "IDLE", "RUNNING", 6)
        log.apply("ComputePathToPose", 12, "RUNNING", "SUCCESS", 7)
        log.apply("ComputePathToPose", 12, "SUCCESS", "IDLE", 8)
        assertEquals(listOf(12, 14), log.tree().nodes.keys.toList())
        assertEquals(BtNodeState(BtStatus.IDLE, BtStatus.SUCCESS), log.states[12])
        assertEquals(BtNodeState(BtStatus.RUNNING), log.states[14])
        // No uid (old Nav2): by name.
        log.apply("Spin", 0, "IDLE", "RUNNING", 9)
        log.apply("Spin", 0, "RUNNING", "FAILURE", 10)
        assertEquals(3, log.tree().nodes.size)
        assertNull(log.apply("Bad", 3, "IDLE", "WHATEVER", 11))
    }

    @Test
    fun addresses() {
        assertEquals("192.168.1.20" to 1667, BehaviorTreeFragment.parseAddress(" 192.168.1.20 "))
        assertEquals("robot.local" to 1700, BehaviorTreeFragment.parseAddress("tcp://robot.local:1700"))
        assertNull(BehaviorTreeFragment.parseAddress(""))
        assertNull(BehaviorTreeFragment.parseAddress("10.0.0.1:99999"))
        assertNull(BehaviorTreeFragment.parseAddress("bad host"))
    }

    @Test
    fun zmtpGreetingAndReady() {
        val g = ZmqReq.greeting()
        assertEquals(64, g.size)
        assertEquals(0xFF.toByte(), g[0]); assertEquals(0x7F.toByte(), g[9]); assertEquals(3.toByte(), g[10])
        assertEquals("NULL", String(g, 12, 4))
        val r = ZmqReq.readyCommand("REQ")
        assertEquals("\u0005READY\u000bSocket-Type\u0000\u0000\u0000\u0003REQ", String(r, Charsets.US_ASCII))
    }

    /** Against a real Groot2Publisher, if it is running (tools/bt_groot/bt_groot_server). */
    @Test
    fun realGroot2PublisherIfRunning() {
        val up = try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", Groot2.DEFAULT_PORT), 300) }; true } catch (_: Exception) { false }
        assumeTrue(up)
        ZmqReq("127.0.0.1", Groot2.DEFAULT_PORT).use { zmq ->
            val tree = zmq.request(listOf(Groot2.requestHeader(Groot2.FULL_TREE, 1)))
            val uuid = Groot2.checkReply(tree[0], Groot2.FULL_TREE, 1)
            assertTrue(Groot2.parseTree(String(tree[1]), uuid).nodes.isNotEmpty())
            repeat(3) { i ->
                val st = zmq.request(listOf(Groot2.requestHeader(Groot2.STATUS, 100 + i)))
                assertEquals(uuid, Groot2.checkReply(st[0], Groot2.STATUS, 100 + i))
                assertTrue(Groot2.parseStatus(st[1]).isNotEmpty())
            }
        }
    }
}
