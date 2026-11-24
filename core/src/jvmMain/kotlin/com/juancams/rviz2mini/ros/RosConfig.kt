// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import com.juancams.rviz2mini.platform.Platform

/** DDS/ROS 2 connection configuration. */
data class RosConfig(
    val domainId: Int = 0,
    val nodeName: String = DEFAULT_NODE_NAME,
    /** Comma-separated IPs or interface names (empty = all). */
    val interfaceWhitelist: String = "",
    /**
     * IPs to announce to via unicast in addition to multicast (like ROS_STATIC_PEERS), comma
     * separated. Needed on Wi-Fi networks that filter multicast.
     */
    val discoveryPeers: String = "",
    /**
     * Fast DDS Discovery Servers ("ip" or "ip:port", comma separated), like ROS_DISCOVERY_SERVER.
     * If there are any, discovery goes through them instead of multicast.
     */
    val discoveryServers: String = "",
    /**
     * URL of a foxglove_bridge ("ws://192.168.1.10:8765"). If set, the app connects via
     * WebSocket instead of DDS (works over the internet or mobile data).
     */
    val bridgeUrl: String = "",
    /**
     * How to package service calls (and actions, parameters, lifecycle): each RMW does it its own way.
     * AUTO decides per service based on the server's DDS; the other two force one (in case detection fails).
     */
    val serviceFormat: ServiceFormat = ServiceFormat.AUTO,
    /**
     * Zenoh router ("tcp/192.168.1.10:7447") of a robot with rmw_zenoh_cpp. If set, the app talks to it over
     * Zenoh instead of DDS (not used at the same time as foxglove_bridge).
     */
    val zenohRouter: String = "",
) {
    val usesBridge: Boolean get() = bridgeUrl.isNotBlank()
    val usesZenoh: Boolean get() = !usesBridge && zenohRouter.isNotBlank()

    fun whitelistEntries(): List<String> =
        interfaceWhitelist.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun peerEntries(): List<String> = splitPeers(discoveryPeers)

    fun serverEntries(): List<String> = splitPeers(discoveryServers)

    fun toJson(): org.json.JSONObject = org.json.JSONObject().put("domainId", domainId).put("nodeName", nodeName)
        .put("interfaceWhitelist", interfaceWhitelist).put("discoveryPeers", discoveryPeers).put("discoveryServers", discoveryServers)
        .put("bridgeUrl", bridgeUrl).put("serviceFormat", serviceFormat.key).put("zenohRouter", zenohRouter)

    companion object {
        const val DEFAULT_NODE_NAME = "vizmini2"

        fun fromJson(o: org.json.JSONObject?): RosConfig = if (o == null) RosConfig() else RosConfig(
            o.optInt("domainId", 0), o.optString("nodeName", DEFAULT_NODE_NAME), o.optString("interfaceWhitelist"),
            o.optString("discoveryPeers"), o.optString("discoveryServers"), o.optString("bridgeUrl"),
            ServiceFormat.fromKey(o.optString("serviceFormat")), o.optString("zenohRouter"))
        const val MIN_DOMAIN_ID = 0
        const val MAX_DOMAIN_ID = 232 // ROS 2 / Fast DDS limit (UDP ports)

        private val NODE_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /** Returns the error message, or null if valid. */
        fun validateDomainId(text: String): String? {
            val value = text.trim().toIntOrNull() ?: return "Must be a number"
            if (value !in MIN_DOMAIN_ID..MAX_DOMAIN_ID) return "Must be between $MIN_DOMAIN_ID and $MAX_DOMAIN_ID"
            return null
        }

        private val IPV4_REGEX = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")

        /** Splits on commas, spaces or semicolons ("10.0.0.2; 10.0.0.3"). */
        fun splitPeers(text: String): List<String> = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /** IPv4 only (Fast DDS does not resolve names in initial peers). */
        fun validatePeers(text: String): String? {
            val bad = splitPeers(text).firstOrNull { !IPV4_REGEX.matches(it) } ?: return null
            return "\"$bad\" is not an IPv4 address"
        }

        private val SERVER_REGEX = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}(:\d{1,5})?$""")

        /** "ip" or "ip:port" (port 1–65535; 11811 by default). */
        fun validateServers(text: String): String? {
            for (s in splitPeers(text)) {
                val port = s.substringAfter(':', "11811").toIntOrNull()
                if (!SERVER_REGEX.matches(s) || port == null || port !in 1..65535) return "\"$s\" is not ip or ip:port"
            }
            return null
        }

        /** "ws://host:port" or "wss://..." (empty = DDS). */
        fun validateBridgeUrl(text: String): String? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val ok = Regex("""^wss?://[A-Za-z0-9.\-]+(:\d{1,5})?(/.*)?$""").matches(t)
            return if (ok) null else "Use ws://host:port (for example ws://192.168.1.10:8765)"
        }

        private val ZENOH_ENDPOINT_REGEX = Regex("""^(tcp|udp|tls|quic)/[A-Za-z0-9.\-\[\]:]+:\d{1,5}$""")

        /** "tcp/host:port" (also udp, tls, quic), like Zenoh endpoints; empty = DDS. */
        fun validateZenohRouter(text: String): String? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val port = t.substringAfterLast(':').toIntOrNull()
            return if (ZENOH_ENDPOINT_REGEX.matches(t) && port != null && port in 1..65535) null
            else "Use tcp/host:port (for example tcp/192.168.1.10:7447)"
        }

        /** Same rules as rcl for node names. */
        fun validateNodeName(text: String): String? {
            val value = text.trim()
            if (value.isEmpty()) return "Cannot be empty"
            if (!NODE_NAME_REGEX.matches(value)) return "Only letters, digits and '_' (cannot start with a digit)"
            return null
        }

        private const val PREFS = "rviz2mini_ros"

        /** Domain of the demo mode's simulated robot (DemoMode): its participants are not recorded as known robots. */
        const val DEMO_DOMAIN_ID = 231

        fun load(): RosConfig {
            val p = Platform.prefs(PREFS)
            return RosConfig(
                domainId = p.getInt("domainId", 0).coerceIn(MIN_DOMAIN_ID, MAX_DOMAIN_ID),
                nodeName = p.getString("nodeName", null)?.takeIf { validateNodeName(it) == null } ?: DEFAULT_NODE_NAME,
                interfaceWhitelist = p.getString("interfaceWhitelist", "") ?: "",
                discoveryPeers = p.getString("discoveryPeers", "") ?: "",
                discoveryServers = p.getString("discoveryServers", "") ?: "",
                bridgeUrl = p.getString("bridgeUrl", "")?.takeIf { validateBridgeUrl(it) == null } ?: "",
                serviceFormat = ServiceFormat.fromKey(p.getString("serviceFormat", null)),
                zenohRouter = p.getString("zenohRouter", "")?.takeIf { validateZenohRouter(it) == null } ?: ""
            )
        }

        fun save(config: RosConfig) {
            Platform.prefs(PREFS).edit()
                .putInt("domainId", config.domainId)
                .putString("nodeName", config.nodeName)
                .putString("interfaceWhitelist", config.interfaceWhitelist)
                .putString("discoveryPeers", config.discoveryPeers)
                .putString("discoveryServers", config.discoveryServers)
                .putString("bridgeUrl", config.bridgeUrl)
                .putString("serviceFormat", config.serviceFormat.key)
                .putString("zenohRouter", config.zenohRouter)
                .apply()
        }
    }
}

/** Service call format: rmw_fastrtps's (sample identity) or rmw_cyclonedds's (header). */
enum class ServiceFormat(val key: String) {
    AUTO("auto"), FAST_DDS("fastdds"), CYCLONE("cyclone");

    companion object {
        fun fromKey(key: String?): ServiceFormat = entries.firstOrNull { it.key == key } ?: AUTO
    }
}
