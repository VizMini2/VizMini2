// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.e2e

import com.juancams.rviz2mini.nav.Nav2Mission
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Against tools/nav2_waypoints_server.py: a waypoint route with FollowWaypoints and with NavigateThroughPoses. */
class Nav2E2ETest {

    @Before fun connect() = E2E.connect("/follow_waypoints/_action/feedback")

    private fun run(mode: Nav2Mission.Mode) {
        val m = Nav2Mission(E2E.registry)
        m.add(Nav2Mission.Waypoint(1.0, 0.0, 0.0), "map")
        m.add(Nav2Mission.Waypoint(1.0, 1.0, 1.57), "map")
        m.mode = mode
        m.start()
        E2E.waitFor("the route did not succeed: ${m.status}", 40_000) { !m.running && m.status?.contains("Succeeded") == true }
        m.close()
    }

    @Test fun followWaypoints() { run(Nav2Mission.Mode.FOLLOW) }

    @Test fun navigateThroughPoses() {
        run(Nav2Mission.Mode.THROUGH)
        assertTrue("the newer Nav2 layout was not detected", Nav2Mission.preferNew != null)
    }
}
