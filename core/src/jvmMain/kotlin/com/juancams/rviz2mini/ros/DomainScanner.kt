// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

/**
 * Which ROS 2 domains have someone on them, without creating a node in each one: every DDS participant announces its presence
 * (SPDP) by multicast on 239.255.0.1, port 7400 + 250·domain, every few seconds. It listens at once on the
 * ports of all domains and records which IPs RTPS announcements come from. Requires the network to pass
 * multicast (otherwise robots are found via [KnownRobots] or by setting their IP as a peer).
 */
object DomainScanner {
    private val GROUP: InetAddress = InetAddress.getByName("239.255.0.1")
    const val MAX_DOMAIN = 232

    /** Multicast discovery port of a domain (RTPS: PB + DG·domain + d0). */
    fun port(domain: Int) = 7400 + 250 * domain

    /** Does it start like an RTPS message? */
    fun isRtps(b: ByteArray, len: Int) = len >= 4 && b[0] == 'R'.code.toByte() && b[1] == 'T'.code.toByte() && b[2] == 'P'.code.toByte() && b[3] == 'S'.code.toByte()

    /**
     * Listens for `timeoutMs` (participants announce themselves every ~3 s) and returns domain → IPs that have announced
     * (including those of this machine: a local simulator counts; see [ownIps]). Blocks: off the UI thread.
     */
    fun scan(timeoutMs: Long = 3500, domains: IntRange = 0..MAX_DOMAIN): Map<Int, Set<String>> {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && it.supportsMulticast() && !it.isLoopback }.getOrDefault(false) && it.inetAddresses.toList().any { a -> a is java.net.Inet4Address } }
        val found = HashMap<Int, MutableSet<String>>()
        val selector = Selector.open()
        val channels = ArrayList<DatagramChannel>()
        try {
            for (d in domains) {
                val ch = runCatching {
                    DatagramChannel.open(StandardProtocolFamily.INET).apply {
                        setOption(StandardSocketOptions.SO_REUSEADDR, true)
                        bind(InetSocketAddress(port(d)))
                        for (i in ifaces) runCatching { join(GROUP, i) }
                        configureBlocking(false)
                    }
                }.getOrNull() ?: continue
                channels += ch
                ch.register(selector, SelectionKey.OP_READ, d)
            }
            val buf = ByteBuffer.allocate(2048)
            val end = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                if (selector.select(left) == 0) continue
                val it = selector.selectedKeys().iterator()
                while (it.hasNext()) {
                    val k = it.next(); it.remove()
                    val ch = k.channel() as DatagramChannel
                    while (true) {
                        buf.clear()
                        val from = ch.receive(buf) as? InetSocketAddress ?: break
                        val ip = from.address.hostAddress
                        if (from.address.isLoopbackAddress) continue
                        if (isRtps(buf.array(), buf.position())) found.getOrPut(k.attachment() as Int) { HashSet() }.add(ip)
                    }
                }
            }
        } finally {
            channels.forEach { runCatching { it.close() } }
            runCatching { selector.close() }
        }
        return found.toSortedMap()
    }

    /** The IPs of this machine (to mark "this machine" in the results). */
    fun ownIps(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { i -> i.inetAddresses.toList().map { it.hostAddress } }.toSet()
    }.getOrDefault(emptySet())
}
