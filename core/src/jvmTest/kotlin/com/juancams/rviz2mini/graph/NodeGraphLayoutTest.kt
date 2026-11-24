// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.graph

import com.juancams.rviz2mini.graph.NodeGraphLayout.Kind
import com.juancams.rviz2mini.graph.NodeGraphLayout.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeGraphLayoutTest {

    private val layout = NodeGraphLayout()
    private val all = Options(hideDebug = false, hideLeafTopics = false)

    private val talkerListener = listOf(
        GraphNode("/talker", publishers = listOf("/chatter", "/rosout", "/parameter_events"), subscribers = listOf("/parameter_events")),
        GraphNode("/listener", publishers = listOf("/rosout", "/parameter_events"), subscribers = listOf("/chatter", "/parameter_events")),
        GraphNode("/relay", isLifecycle = true, publishers = listOf("/chatter_out", "/_hidden"), subscribers = listOf("/chatter", "/cmd"))
    )

    private fun NodeGraphLayout.Result.names(kind: Kind) = vertices.filter { it.kind == kind }.map { it.name }.toSet()
    private fun NodeGraphLayout.Result.v(kind: Kind, name: String) = vertices[indexOf(kind, name)]

    private fun assertNoOverlaps(r: NodeGraphLayout.Result) {
        for ((_, l) in r.vertices.groupBy { it.layer }) {
            val s = l.sortedBy { it.y }
            for (i in 1 until s.size) assertTrue("${s[i - 1].name} solapa ${s[i].name}", s[i - 1].bottom <= s[i].y + 1e-2f)
            assertEquals(l.map { it.order }.sorted(), l.indices.toList())
            // The order within the layer matches the vertical one.
            assertEquals(s.map { it.order }, s.map { it.order }.sorted())
        }
        // Layers separated in X (they don't overlap each other).
        val byLayer = r.vertices.groupBy { it.layer }.toSortedMap()
        val ranges = byLayer.values.map { l -> l.minOf { it.x } to l.maxOf { it.right } }
        for (i in 1 until ranges.size) assertTrue(ranges[i - 1].second < ranges[i].first)
    }

    @Test
    fun bipartiteEdgesAndLeftToRight() {
        val r = layout.layout(talkerListener, Options(hideDebug = true))
        assertEquals(setOf("/talker", "/listener", "/relay"), r.names(Kind.NODE))
        assertEquals(setOf("/chatter", "/chatter_out", "/cmd"), r.names(Kind.TOPIC))
        for (e in r.edges) assertTrue(r.vertices[e.from].kind != r.vertices[e.to].kind)
        // talker -> /chatter -> listener / relay -> /chatter_out
        val talker = r.v(Kind.NODE, "/talker"); val chatter = r.v(Kind.TOPIC, "/chatter")
        val listener = r.v(Kind.NODE, "/listener"); val relay = r.v(Kind.NODE, "/relay")
        assertTrue(talker.layer < chatter.layer && chatter.layer < listener.layer && chatter.layer < relay.layer)
        assertTrue(relay.layer < r.v(Kind.TOPIC, "/chatter_out").layer)
        // /cmd only has a subscriber: it ends up just to the left of /relay.
        assertEquals(relay.layer - 1, r.v(Kind.TOPIC, "/cmd").layer)
        for (e in r.edges) assertTrue(r.vertices[e.from].layer < r.vertices[e.to].layer)
        for (e in r.edges) assertTrue(r.vertices[e.from].right < r.vertices[e.to].x)
        assertTrue(relay.isLifecycle)
        assertNoOverlaps(r)
    }

    @Test
    fun debugFilter() {
        val shown = layout.layout(talkerListener, all)
        assertTrue(shown.names(Kind.TOPIC).containsAll(listOf("/rosout", "/parameter_events", "/_hidden")))
        val hidden = layout.layout(talkerListener, Options(hideDebug = true))
        assertFalse(hidden.names(Kind.TOPIC).any { it == "/rosout" || it == "/parameter_events" || it == "/_hidden" })
        assertNoOverlaps(shown)
    }

    @Test
    fun leafTopicFilter() {
        val r = layout.layout(talkerListener, Options(hideDebug = true, hideLeafTopics = true))
        assertEquals(setOf("/chatter"), r.names(Kind.TOPIC))
        // Nodes are kept even if they are left without edges.
        assertEquals(3, r.names(Kind.NODE).size)
    }

    @Test
    fun textFilterKeepsMatchesAndNeighbours() {
        val r = layout.layout(talkerListener, Options(hideDebug = true, filter = "LISTEN"))
        assertEquals(setOf("/listener"), r.names(Kind.NODE))
        assertEquals(setOf("/chatter"), r.names(Kind.TOPIC))
        val t = layout.layout(talkerListener, Options(hideDebug = true, filter = "chatter_out"))
        assertEquals(setOf("/relay"), t.names(Kind.NODE))
        assertEquals(setOf("/chatter_out"), t.names(Kind.TOPIC))
        assertEquals(0, layout.layout(talkerListener, Options(filter = "nothing")).vertices.size)
    }

    @Test(timeout = 2000)
    fun cyclesDoNotHangAndMostEdgesGoRight() {
        // a -> /x -> b -> /y -> a  (cycle) and a tail b -> /z -> c
        val nodes = listOf(
            GraphNode("/a", publishers = listOf("/x"), subscribers = listOf("/y")),
            GraphNode("/b", publishers = listOf("/y", "/z"), subscribers = listOf("/x")),
            GraphNode("/c", subscribers = listOf("/z"))
        )
        val r = layout.layout(nodes, all)
        assertEquals(6, r.vertices.size)
        assertEquals(6, r.edges.size)
        val forward = r.edges.count { r.vertices[it.from].layer < r.vertices[it.to].layer }
        assertEquals(5, forward) // only the edge that closes the cycle goes backward
        assertNoOverlaps(r)
    }

    @Test
    fun deterministic() {
        val a = layout.layout(talkerListener, all)
        val b = layout.layout(talkerListener.reversed(), all)
        assertEquals(a.vertices, b.vertices)
        assertEquals(a.edges, b.edges)
    }

    @Test
    fun barycenterReducesCrossings() {
        // Two parallel chains; the initial alphabetical order would cross them (a->/z, b->/a).
        val nodes = listOf(
            GraphNode("/a", publishers = listOf("/t2")), GraphNode("/b", publishers = listOf("/t1")),
            GraphNode("/c", subscribers = listOf("/t1")), GraphNode("/d", subscribers = listOf("/t2"))
        )
        val r = layout.layout(nodes, all)
        assertEquals(0, crossings(r))
    }

    @Test
    fun largeGraphIsFastAndMostlyForward() {
        val rnd = java.util.Random(42)
        val nodes = (0 until 100).map { i ->
            GraphNode("/node_%03d".format(i), isLifecycle = i % 7 == 0,
                publishers = (0 until 3).map { "/topic_%03d".format(i * 3 + it) },
                subscribers = (0 until 4).map { "/topic_%03d".format(rnd.nextInt(300)) })
        }
        val t0 = System.nanoTime()
        val r = layout.layout(nodes, all)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("tardó $ms ms", ms < 2000)
        assertEquals(400, r.vertices.size)
        val forward = r.edges.count { r.vertices[it.from].layer < r.vertices[it.to].layer }
        assertTrue("$forward de ${r.edges.size}", forward > r.edges.size * 0.7)
        assertNoOverlaps(r)
    }

    /** Crossings between edges of consecutive layers. */
    private fun crossings(r: NodeGraphLayout.Result): Int {
        val es = r.edges.map { r.vertices[it.from] to r.vertices[it.to] }.filter { it.second.layer == it.first.layer + 1 }
        var c = 0
        for (i in es.indices) for (j in i + 1 until es.size) {
            val (a1, b1) = es[i]; val (a2, b2) = es[j]
            if (a1.layer != a2.layer) continue
            if ((a1.order - a2.order) * (b1.order - b2.order) < 0) c++
        }
        return c
    }
}
