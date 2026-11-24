// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.profiles

import com.juancams.rviz2mini.profiles.ProfileSync.Record
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudProfilesTest {

    private fun rec(name: String, t: Long, deleted: Boolean = false) = Record(name, t, if (deleted) null else "{\"name\":\"$name\",\"t\":$t}")

    @Test
    fun newestWinsBothWays() {
        val local = mapOf("a" to rec("A", 10), "b" to rec("B", 5), "c" to rec("C", 1))
        val remote = mapOf("a" to rec("A", 5), "b" to rec("B", 9), "d" to rec("D", 3))
        val plan = ProfileSync.plan(local, remote)
        assertEquals(listOf("B", "D"), plan.toLocal.map { it.name })
        assertEquals(listOf("A", "C"), plan.toRemote.map { it.name })
    }

    @Test
    fun deletionsPropagateWhenNewer() {
        val local = mapOf("a" to rec("a", 20, deleted = true))
        val remote = mapOf("a" to rec("A", 10))
        val plan = ProfileSync.plan(local, remote)
        assertEquals(listOf(true), plan.toRemote.map { it.deleted })
        // A remote deletion of a profile the phone does not have does nothing.
        assertEquals(emptyList<Record>(), ProfileSync.plan(emptyMap(), mapOf("x" to rec("x", 1, deleted = true))).toLocal)
        // But if the phone has an older version of it, it is deleted.
        assertEquals(listOf(true), ProfileSync.plan(mapOf("x" to rec("X", 1)), mapOf("x" to rec("x", 2, deleted = true))).toLocal.map { it.deleted })
    }

    private fun own(r: Record, uid: String?) = r.copy(owner = uid)

    @Test
    fun profilesOfAnotherAccountAreNotUploaded() {
        // The phone has account A's profiles (and one of its deletions); signing in with B.
        val local = mapOf(
            "a1" to own(rec("A1", 10), "A"),
            "gone" to own(rec("gone", 12, deleted = true), "A"),
            "new" to own(rec("New", 20), null), // created without an account: uploaded to whichever signs in
        )
        val remote = mapOf("b1" to rec("B1", 5))
        val plan = ProfileSync.plan(local, remote, uid = "B")
        assertEquals(listOf("New"), plan.toRemote.map { it.name })
        assertEquals(listOf("B1"), plan.toLocal.map { it.name })
        assertEquals(setOf("b1", "new"), plan.synced)
    }

    @Test
    fun sameAccountSyncsAsBefore() {
        val local = mapOf("a1" to own(rec("A1", 10), "A"), "a2" to own(rec("A2", 30), "A"))
        val remote = mapOf("a1" to rec("A1", 15), "a2" to rec("A2", 20))
        val plan = ProfileSync.plan(local, remote, uid = "A")
        assertEquals(listOf("A1"), plan.toLocal.map { it.name })
        assertEquals(listOf("A2"), plan.toRemote.map { it.name })
    }

    @Test
    fun anotherAccountsDeletionDoesNotRemoveTheLocalCopy() {
        // B deleted in its cloud a profile with the same name as one of A's on the phone: A's stays.
        val local = mapOf("r" to own(rec("R", 10), "A"))
        val remote = mapOf("r" to rec("r", 50, deleted = true))
        assertEquals(emptyList<Record>(), ProfileSync.plan(local, remote, uid = "B").toLocal)
    }

    @Test
    fun keysIgnoreCaseAndOddCharacters() {
        assertEquals("turtlebot_4", ProfileSync.key(" TurtleBot 4 "))
        assertEquals(ProfileSync.key("Robot/A"), ProfileSync.key("robot/a"))
    }
}
