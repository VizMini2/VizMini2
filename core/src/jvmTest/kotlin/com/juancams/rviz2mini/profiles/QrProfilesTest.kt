// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.profiles

import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrProfilesTest {

    private val profile = RobotProfile(
        "TurtleBot 4", RosConfig(42, "tablet_ui", "wlan0", "192.168.1.10", "192.168.1.2:11811"),
        listOf(TabSpec.create(TabType.RVIZ, "RViz").withSetting("displays", "[" + "{\"type\":\"GRID\"}," .repeat(20) + "{}]"),
            TabSpec.create(TabType.TELEOP, "Teleop"))
    )

    @Test
    fun fullProfileRoundTrip() {
        val payload = QrProfiles.encode(profile, connectionOnly = false)
        val d = QrProfiles.decode(payload)
        assertEquals(false, d.connectionOnly)
        assertEquals(profile.ros, d.profile.ros)
        assertEquals(profile.tabs.map { it.name }, d.profile.tabs.map { it.name })
        assertTrue(QrProfiles.fits(payload))
    }

    @Test
    fun connectionOnlyIsSmall() {
        val payload = QrProfiles.encode(profile, connectionOnly = true)
        val d = QrProfiles.decode(payload)
        assertTrue(d.connectionOnly)
        assertEquals(emptyList<TabSpec>(), d.profile.tabs)
        assertEquals("192.168.1.2:11811", d.profile.ros.discoveryServers)
        assertTrue(payload.length < 300)
    }

    @Test
    fun rejectsOtherCodes() {
        assertThrows(IllegalArgumentException::class.java) { QrProfiles.decode("https://example.com") }
        assertThrows(IllegalArgumentException::class.java) { QrProfiles.decode("R2M1:not-base64-!!") }
    }

    private fun bigProfile(): RobotProfile {
        val dash = TabSpec.create(TabType.DASHBOARD, "Dash")
        val widgets = (1..12).map { TabSpec.create(TabType.CAMERA, "Cam $it").withSetting(TabSpec.KEY_DASHBOARD, dash.id).withSetting("topic", "/robot/camera_$it/image_raw") }
        val layout = com.juancams.rviz2mini.dashboard.DashboardGrid.toJson(widgets.mapIndexed { i, w -> com.juancams.rviz2mini.dashboard.DashboardGrid.Item(w.id, i % 3 * 4, i / 3 * 4, 4, 4) })
        val tabs = listOf(dash.withSetting("layout", layout)) + widgets + (1..20).map { TabSpec.create(TabType.TOPICS, "Topics $it") }
        return RobotProfile("Big", RosConfig(), tabs)
    }

    @Test
    fun shortIdsShrinkAndSurviveTheRoundTrip() {
        val p = bigProfile()
        val long = QrProfiles.encode(p, connectionOnly = false)
        val json = RobotProfiles.toJson(p).toString()
        // What matters is the QR: compressed, with short ids it takes considerably less than with 36-character ones.
        val withoutShort = run {
            val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION); d.setInput(json.toByteArray()); d.finish()
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf)); d.end()
            "R2M1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        }
        println("QR del perfil grande: ${withoutShort.length} -> ${long.length} caracteres")
        assertTrue("ids cortos: ${long.length} < ${withoutShort.length}", long.length < withoutShort.length * 0.6)
        val back = QrProfiles.decode(long).profile
        assertEquals(p.tabs.map { it.name }, back.tabs.map { it.name })
        // The dashboard still points to its widgets (ids changed consistently).
        val d = back.tabs.first { it.type == TabType.DASHBOARD }
        val ids = com.juancams.rviz2mini.dashboard.DashboardGrid.fromJson(d.settings["layout"]).map { it.id }.toSet()
        assertEquals(back.tabs.filter { it.dashboardId == d.id }.map { it.id }.toSet(), ids)
    }

    @Test
    fun partsJoinInAnyOrder() {
        val payload = QrProfiles.encode(bigProfile(), connectionOnly = false)
        val parts = QrProfiles.parts(payload, max = 300, single = 300)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 300 && QrProfiles.isPart(it) })
        val a = QrProfiles.Assembler()
        var full: String? = null
        for (x in parts.reversed()) full = a.add(x) ?: full
        assertEquals(payload, full)
        assertEquals(listOf(payload), QrProfiles.parts(payload))
    }
}
