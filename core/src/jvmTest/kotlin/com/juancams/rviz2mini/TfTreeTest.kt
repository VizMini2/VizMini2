// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class TfTreeTest {

    private val eps = 1e-9
    private val out = DoubleArray(16)

    private fun TfTree.put(parent: String, child: String, x: Double = 0.0, y: Double = 0.0, z: Double = 0.0, yaw: Double = 0.0) =
        update(parent, child, x, y, z, 0.0, 0.0, sin(yaw / 2), cos(yaw / 2))

    private fun TfTree.position(id: String): DoubleArray? =
        if (poseInFixed(id, out)) doubleArrayOf(out[12], out[13], out[14]) else null

    @Test
    fun parentFramesAreCreatedAutomatically() {
        val tree = TfTree()
        assertTrue(tree.put("map", "odom"))
        assertEquals(listOf("map", "odom"), tree.frameIds())
        assertEquals("", tree.parentOf("map"))
        assertFalse("Repetir la misma TF no crea frames nuevos", tree.put("map", "odom", x = 1.0))
    }

    @Test
    fun chainIsComposedInOrder() {
        val tree = TfTree()
        tree.put("map", "odom", x = 1.0)
        tree.put("odom", "base_link", x = 2.0, yaw = Math.PI / 2)
        tree.put("base_link", "laser", x = 0.5)
        tree.compute("map")
        assertArrayEquals(doubleArrayOf(3.0, 0.5, 0.0), tree.position("laser")!!, eps)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), tree.position("map")!!, eps)
    }

    @Test
    fun outOfOrderMessagesGiveSameResult() {
        val tree = TfTree()
        tree.put("base_link", "laser", x = 0.5)
        tree.put("odom", "base_link", x = 2.0, yaw = Math.PI / 2)
        tree.put("map", "odom", x = 1.0)
        tree.compute("map")
        assertArrayEquals(doubleArrayOf(3.0, 0.5, 0.0), tree.position("laser")!!, eps)
    }

    @Test
    fun posesAreExpressedInTheFixedFrame() {
        val tree = TfTree()
        tree.put("map", "base_link", x = 5.0, yaw = Math.PI / 2)
        tree.put("base_link", "laser", x = 1.0)
        tree.compute("base_link")
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), tree.position("base_link")!!, eps)
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0), tree.position("laser")!!, eps)
        // map seen from base_link: 5 m behind (rotated 90º) => (0, 5, 0)
        assertArrayEquals(doubleArrayOf(0.0, 5.0, 0.0), tree.position("map")!!, eps)
    }

    @Test
    fun fixedFramePoseIsIdentity() {
        val tree = TfTree()
        tree.put("map", "a", x = 1.0, yaw = 0.3)
        tree.put("a", "b", y = 2.0, yaw = -1.1)
        tree.compute("b")
        assertTrue(tree.poseInFixed("b", out))
        assertArrayEquals(TfMath.identity(), out, eps)
    }

    @Test
    fun updatesAreReflectedOnNextCompute() {
        val tree = TfTree()
        tree.put("map", "robot", x = 1.0)
        tree.compute("map")
        assertEquals(1.0, tree.position("robot")!![0], eps)
        tree.put("map", "robot", x = 2.0)
        tree.compute("map")
        assertEquals(2.0, tree.position("robot")!![0], eps)
    }

    @Test
    fun reparentingIsHandled() {
        val tree = TfTree()
        tree.put("map", "a", x = 1.0)
        tree.put("map", "b", x = 10.0)
        tree.put("a", "c", x = 1.0)
        tree.put("b", "c", x = 1.0)
        tree.compute("map")
        assertEquals("b", tree.parentOf("c"))
        assertEquals(11.0, tree.position("c")!![0], eps)
    }

    @Test
    fun disconnectedTreesAreNotShownRelativeToFixedFrame() {
        val tree = TfTree()
        tree.put("map", "odom", x = 1.0)
        tree.put("camera_link", "camera_optical")
        tree.compute("map")
        assertTrue(tree.poseInFixed("odom", out))
        assertNull(tree.position("camera_optical"))
        assertNull(tree.position("camera_link"))
    }

    @Test
    fun withoutFixedFrameEveryRootActsAsWorld() {
        val tree = TfTree()
        tree.put("map", "odom", x = 1.0)
        tree.put("camera_link", "camera_optical", z = 2.0)
        tree.compute("")
        assertEquals(1.0, tree.position("odom")!![0], eps)
        assertEquals(2.0, tree.position("camera_optical")!![2], eps)
        tree.compute("does_not_exist")
        assertEquals(1.0, tree.position("odom")!![0], eps)
    }

    @Test
    fun cyclesDoNotCrashAndAreMarkedInvalid() {
        val tree = TfTree()
        tree.put("map", "a", x = 1.0)
        tree.put("a", "b", x = 1.0)
        tree.put("b", "c", x = 1.0)
        tree.put("c", "a", x = 1.0) // a now hangs from c => cycle a -> b -> c -> a
        tree.put("c", "d", x = 1.0)
        tree.compute("map")
        assertNull(tree.position("a"))
        assertNull(tree.position("b"))
        assertNull(tree.position("c"))
        assertNull("Los descendientes de un ciclo tampoco son válidos", tree.position("d"))
        assertTrue(tree.poseInFixed("map", out))

        // Once the cycle is broken it works again.
        tree.put("map", "a", x = 1.0)
        tree.compute("map")
        assertEquals(4.0, tree.position("d")!![0], eps)
    }

    @Test
    fun cycleAsFixedFrameDoesNotCrash() {
        val tree = TfTree()
        tree.put("a", "b")
        tree.put("b", "a")
        tree.put("map", "x", x = 1.0)
        tree.compute("a")
        assertNull(tree.position("a"))
        assertEquals(1.0, tree.position("x")!![0], eps)
    }

    @Test
    fun deepChainDoesNotOverflowStack() {
        val tree = TfTree()
        val depth = 20_000
        for (i in 0 until depth) tree.put("f$i", "f${i + 1}", x = 0.001)
        tree.compute("f0")
        assertEquals(depth * 0.001, tree.position("f$depth")!![0], 1e-6)
    }

    @Test
    fun invalidMessagesAreRejected() {
        val tree = TfTree()
        assertFalse(tree.put("a", "a"))
        assertFalse(tree.put("", "a"))
        assertFalse(tree.put("a", ""))
        assertFalse(tree.update("map", "nan", Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        assertFalse(tree.update("map", "inf", 0.0, 0.0, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, 1.0))
        assertEquals(0, tree.size)
    }

    @Test
    fun frameIdsAreNormalized() {
        val tree = TfTree()
        tree.put("/map", " base_link\u0000")
        assertEquals(listOf("base_link", "map"), tree.frameIds())
        assertEquals("map", tree.parentOf("base_link"))
    }

    @Test
    fun staticFlagIsStored() {
        val tree = TfTree()
        tree.update("base_link", "laser", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, isStatic = true)
        tree.compute("base_link")
        assertTrue(tree.poseInFixed("laser", out))
    }

    @Test
    fun defaultFixedFramePrefersRep105Names() {
        val tree = TfTree()
        tree.put("base_link", "laser")
        assertEquals("base_link", tree.chooseDefaultFixedFrame())
        tree.put("odom", "base_link")
        assertEquals("odom", tree.chooseDefaultFixedFrame())
        tree.put("map", "odom")
        assertEquals("map", tree.chooseDefaultFixedFrame())
    }

    @Test
    fun defaultFixedFrameFallsBackToARoot() {
        val tree = TfTree()
        tree.put("zeta", "alpha")
        assertEquals("zeta", tree.chooseDefaultFixedFrame())
        assertNull(TfTree().chooseDefaultFixedFrame())
    }

    @Test
    fun clearRemovesEverything() {
        val tree = TfTree()
        tree.put("map", "odom")
        tree.clear()
        assertEquals(0, tree.size)
        tree.compute("map")
        assertNull(tree.position("odom"))
    }

    @Test
    fun concurrentUpdatesWhileComputingDoNotThrow() {
        val tree = TfTree()
        val writer = Thread {
            for (i in 0 until 20_000) tree.put("f${i % 50}", "f${i % 50 + 1}", x = i.toDouble())
        }
        writer.start()
        while (writer.isAlive) {
            tree.compute("f0")
            for (id in tree.frameIds()) tree.poseInFixed(id, out)
        }
        writer.join()
        tree.compute("f0")
        assertEquals(51, tree.size)
        assertTrue(tree.poseInFixed("f50", out))
    }
}
