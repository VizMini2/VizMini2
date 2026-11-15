// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros

import com.juancams.rviz2mini.platform.Platform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Robots (machines running ROS 2) seen in each domain and their IPs. While multicast works they
 * are learned automatically; afterwards they are used as unicast peers when connecting, so they are still found
 * even if the Wi-Fi stops passing multicast. The logic is in [Book] (no Android, with tests).
 */
object KnownRobots {

    data class Robot(val ip: String, val name: String, val lastSeenMs: Long)

    /** List per domain, sorted by last seen. Pure: tested on the JVM. */
    class Book(val byDomain: MutableMap<Int, MutableList<Robot>> = HashMap()) {

        /**
         * Records what is seen now (without our own IPs). With `subnets` (network, prefix length) only
         * IPs reachable from the phone are stored: a PC also announces its internal networks
         * (Docker, VPN...). True if there is anything new.
         */
        fun remember(domain: Int, seen: List<Pair<String, String>>, ownIps: Set<String>, nowMs: Long,
                     subnets: List<Pair<String, Int>> = emptyList()): Boolean {
            val list = byDomain.getOrPut(domain) { ArrayList() }
            var changed = false
            for ((ip, name) in seen) {
                if (ip in ownIps || ip.startsWith("127.")) continue
                if (subnets.isNotEmpty() && subnets.none { (net, len) -> sameSubnet(ip, net, len) }) continue
                val i = list.indexOfFirst { it.ip == ip }
                // If it now arrives without a name, the previous one is kept.
                val previous = if (i >= 0) list.removeAt(i) else null
                if (previous == null) changed = true
                list.add(0, Robot(ip, cleanName(name).ifBlank { cleanName(previous?.name.orEmpty()) }, nowMs))
            }
            while (list.size > MAX_PER_DOMAIN) list.removeAt(list.size - 1)
            return changed
        }

        /** IPs to use as peers in that domain (the most recent ones, forgetting very old ones). */
        fun peers(domain: Int, nowMs: Long): List<String> =
            byDomain[domain].orEmpty().filter { nowMs - it.lastSeenMs < FORGET_AFTER_MS }.take(MAX_PEERS).map { it.ip }

        fun forget(domain: Int, ip: String) { byDomain[domain]?.removeAll { it.ip == ip } }

        fun toJson(): String = JSONObject().apply {
            for ((d, list) in byDomain) put(d.toString(), JSONArray().apply {
                for (r in list) put(JSONObject().put("ip", r.ip).put("name", r.name).put("t", r.lastSeenMs))
            })
        }.toString()

        companion object {
            /** Display name: without hidden nodes ("/_ros2cli_daemon_...") or the DDS "/". */
            fun cleanName(raw: String): String =
                raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "/" && !RosGraph.isHidden(it) }.joinToString(", ")

            /** True if `ip` is in the `net`/`prefix` network (IPv4). */
            fun sameSubnet(ip: String, net: String, prefix: Int): Boolean {
                fun v(a: String) = a.split('.').takeIf { it.size == 4 }?.fold(0L) { acc, p -> (acc shl 8) or (p.toLongOrNull() ?: return null) }
                val a = v(ip) ?: return false
                val b = v(net) ?: return false
                val mask = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix.coerceAtMost(32))) and 0xFFFFFFFFL
                return (a and mask) == (b and mask)
            }

            const val MAX_PER_DOMAIN = 16
            const val MAX_PEERS = 8
            const val FORGET_AFTER_MS = 30L * 24 * 3600 * 1000

            fun fromJson(json: String?): Book {
                val book = Book()
                if (json.isNullOrBlank()) return book
                try {
                    val o = JSONObject(json)
                    for (k in o.keys()) {
                        val arr = o.getJSONArray(k)
                        book.byDomain[k.toInt()] = (0 until arr.length()).map { i ->
                            arr.getJSONObject(i).let { Robot(it.getString("ip"), it.optString("name"), it.optLong("t")) }
                        }.toMutableList()
                    }
                } catch (_: Exception) { }
                return book
            }
        }
    }

    private const val PREFS = "rviz2mini_known_robots"

    private var book: Book? = null

    @Synchronized
    private fun book(): Book = book ?: Book.fromJson(
        Platform.prefs(PREFS).getString("book", null)).also { book = it }

    @Synchronized
    fun remember(domain: Int, seen: List<Pair<String, String>>, ownIps: Set<String>) {
        val b = book()
        b.remember(domain, seen, ownIps, System.currentTimeMillis(), ownSubnets())
        Platform.prefs(PREFS).edit().putString("book", b.toJson()).apply()
    }

    @Synchronized
    fun list(domain: Int): List<Robot> = book().byDomain[domain].orEmpty().map { it.copy(name = Book.cleanName(it.name)) }

    @Synchronized
    fun peers(domain: Int): List<String> = book().peers(domain, System.currentTimeMillis())

    @Synchronized
    fun forget(domain: Int, ip: String) {
        val b = book()
        b.forget(domain, ip)
        Platform.prefs(PREFS).edit().putString("book", b.toJson()).apply()
    }

    /** Whether the app automatically uses known robots as peers (Config). Enabled by default. */
    fun enabled() = Platform.prefs(PREFS).getBoolean("enabled", true)
    fun setEnabled(on: Boolean) =
        Platform.prefs(PREFS).edit().putBoolean("enabled", on).apply()

    /** IPv4 networks of the phone (no loopback): (address, prefix length). */
    fun ownSubnets(): List<Pair<String, Int>> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }.filter { it.address is java.net.Inet4Address }
            .mapNotNull { a -> a.address.hostAddress?.let { it to a.networkPrefixLength.toInt() } }
    } catch (_: Exception) { emptyList() }

    /** The phone's own IPv4 addresses (so it does not record itself). */
    fun ownIps(): Set<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }.toSet()
    } catch (_: Exception) { emptySet() }
}
