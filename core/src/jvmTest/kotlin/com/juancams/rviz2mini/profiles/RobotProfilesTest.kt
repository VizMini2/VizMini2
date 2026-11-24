// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.profiles

import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RobotProfilesTest {

    private val tabs = listOf(
        TabSpec("a", TabType.RVIZ, "RViz", mapOf("fixedFrame" to "map", "displays" to "[]")),
        TabSpec("b", TabType.TELEOP_DUAL, "Dron", mapOf("topic" to "/cmd_vel"))
    )
    private val profile = RobotProfile("TurtleBot 4", RosConfig(42, "tablet_ui", "wlan0"), tabs)

    @Test
    fun roundTripsThroughJsonAndFile() {
        assertEquals(profile, RobotProfiles.fromJson(RobotProfiles.toJson(profile)))
        assertEquals(profile, RobotProfiles.parseFile(RobotProfiles.toJson(profile).toString(2)))
        assertEquals(listOf(profile), RobotProfiles.listFromJson(RobotProfiles.listToJson(listOf(profile))))
    }

    @Test
    fun fixesOutOfRangeValuesAndRejectsBrokenProfiles() {
        val o = RobotProfiles.toJson(profile)
        o.getJSONObject("ros").put("domainId", 999).put("nodeName", "1 bad name")
        val p = RobotProfiles.fromJson(o)!!
        assertEquals(RosConfig.MAX_DOMAIN_ID, p.ros.domainId)
        assertEquals(RosConfig.DEFAULT_NODE_NAME, p.ros.nodeName)

        assertNull(RobotProfiles.fromJson(JSONObject(o.toString()).put("name", " ")))
        assertNull(RobotProfiles.fromJson(JSONObject(o.toString()).apply { remove("ros") }))
        assertEquals(emptyList<RobotProfile>(), RobotProfiles.listFromJson("not json"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFilesThatAreNotProfiles() {
        RobotProfiles.parseFile("""{"name": "x", "tabs": []}""")
    }

    @Test
    fun upsertReplacesByNameIgnoringCaseAndSorts() {
        val other = profile.copy(name = "alpha")
        val updated = profile.copy(name = "turtlebot 4", ros = RosConfig(7))
        val list = RobotProfiles.upsert(RobotProfiles.upsert(listOf(profile), other), updated)
        assertEquals(listOf("alpha", "turtlebot 4"), list.map { it.name })
        assertEquals(7, list[1].ros.domainId)
    }

    @Test
    fun freshIdsKeepContent() {
        val fresh = profile.tabsWithFreshIds()
        assertEquals(tabs.map { it.copy(id = "") }, fresh.map { it.copy(id = "") })
        for (i in tabs.indices) assertNotEquals(tabs[i].id, fresh[i].id)
        assertNotNull(RobotProfiles.validateName(""))
        assertNull(RobotProfiles.validateName("Robot"))
    }
}
