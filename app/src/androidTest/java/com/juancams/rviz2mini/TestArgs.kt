// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** `am instrument -e ...` arguments shared by the tests against the PC. */
object TestArgs {
    private fun arg(name: String): String? = InstrumentationRegistry.getArguments().getString(name)?.trim()?.ifEmpty { null }

    /**
     * Unicast discovery IPs (`-e peers 192.168.1.137`): needed when the Wi-Fi filters
     * multicast between the phone and the PC.
     */
    val peers: String get() = arg("peers").orEmpty()

    /** RMW of the PC servers (`-e rmw`, passed by tools/pc_tests.sh): rmw_fastrtps_cpp if not given. */
    val rmw: String get() = arg("rmw") ?: "rmw_fastrtps_cpp"

    /**
     * IP of the test PC (Discovery Server, mesh server...): `-e pcIp`, or otherwise the first `-e peers`.
     * The test fails if none is given (there used to be a fixed IP of a specific PC).
     */
    val pcIp: String get() {
        val ip = arg("pcIp") ?: peers.split(',', ' ', ';').firstOrNull { it.isNotBlank() }?.substringBefore(':')
        assertTrue("Falta la IP del PC: -e pcIp <IP> o -e peers <IP>", ip != null)
        return ip!!
    }
}
