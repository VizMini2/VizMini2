// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros

import java.security.MessageDigest

/**
 * rmw_zenoh_cpp names (rmw_zenoh's docs/design.md): the data key expressions, the liveliness tokens with
 * which each node, publisher, subscriber and service announces itself, and the ROS graph derived from those tokens. No
 * Zenoh or Android dependencies, so it can be tested on the JVM.
 */
object ZenohNames {

    /** Prefix of the ROS 2 liveliness tokens (Zenoh hermetic namespace). */
    const val LIVELINESS_PREFIX = "@ros2_lv"

    /** An already parsed liveliness token. `name`, `ddsType` and `hash` only for publishers, subscribers and services. */
    data class Token(
        val domainId: Int,
        val sessionId: String,
        val nodeId: String,
        val entityId: String,
        val kind: Kind,
        val namespace: String,
        val nodeName: String,
        val name: String = "",
        val ddsType: String = "",
        val hash: String = "",
        val qos: String = "",
    )

    /** NN node, MP publisher, MS subscriber, SS service server and SC service client. */
    enum class Kind(val code: String) {
        NODE("NN"), PUBLISHER("MP"), SUBSCRIBER("MS"), SERVICE_SERVER("SS"), SERVICE_CLIENT("SC");

        companion object {
            fun of(code: String): Kind? = entries.firstOrNull { it.code == code }
        }
    }

    /** Selector for all tokens of a domain (for liveliness get / declareSubscriber). */
    fun livelinessSelector(domainId: Int) = "$LIVELINESS_PREFIX/$domainId/**"

    /**
     * Data key expression of a topic or service: `<domain>/<name without the leading />/<DDS type>/<hash>`.
     * With `hash = "*"` any definition of the type matches (to subscribe without knowing the hash).
     */
    fun dataKey(domainId: Int, name: String, ddsType: String, hash: String = "*"): String =
        "$domainId/${name.trim('/')}/$ddsType/$hash"

    /** Token of a node: `@ros2_lv/<domain>/<session>/0/0/NN/%/<namespace>/<name>` (the app uses node 0). */
    fun nodeToken(domainId: Int, sessionId: String, nodeName: String, namespace: String = "/"): String =
        "$LIVELINESS_PREFIX/$domainId/$sessionId/0/0/${Kind.NODE.code}/%/${mangle(namespace)}/$nodeName"

    /** Token of a publisher, subscriber or service of the app's node 0. */
    fun endpointToken(
        domainId: Int, sessionId: String, entityId: Int, kind: Kind, nodeName: String,
        name: String, ddsType: String, hash: String, qos: String, namespace: String = "/",
    ): String = "$LIVELINESS_PREFIX/$domainId/$sessionId/0/$entityId/${kind.code}/%/${mangle(namespace)}/$nodeName/" +
        "${mangle(name)}/$ddsType/$hash/$qos"

    /**
     * Compact QoS of the tokens (rmw_zenoh's qos_to_keyexpr): `reliability:durability:history,depth:` and
     * default deadlines and liveliness. Empty is the default value (RELIABLE, VOLATILE, KEEP_LAST); 2 = BEST_EFFORT
     * and 1 = TRANSIENT_LOCAL, the rmw values.
     */
    fun qosKey(bestEffort: Boolean, transientLocal: Boolean, depth: Int): String =
        "${if (bestEffort) "2" else ""}:${if (transientLocal) "1" else ""}:,$depth:,:,:,,"

    /**
     * rmw_zenoh attachment of each message or request: int64 sequence number, int64 timestamp in ns (both
     * little-endian), 1 byte with the GID length (16) and the GID.
     */
    fun attachment(seq: Long, timestampNs: Long, gid: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(17 + gid.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(seq).putLong(timestampNs).put(gid.size.toByte()).put(gid).array()

    /** "/a/b" -> "%a%b"; "" or "/" -> "%". */
    fun mangle(name: String): String = if (name.isEmpty() || name == "/") "%" else name.replace('/', '%')

    /** "%a%b" -> "/a/b"; "%" -> "/". */
    fun unmangle(s: String): String = if (s == "%" || s.isEmpty()) "/" else s.replace('%', '/')

    /** Parses a liveliness token; null if it is not from rmw_zenoh or is malformed. */
    fun parseToken(key: String): Token? {
        val p = key.split('/')
        if (p.size < 9 || p[0] != LIVELINESS_PREFIX) return null
        val domain = p[1].toIntOrNull() ?: return null
        val kind = Kind.of(p[5]) ?: return null
        val base = Token(domain, p[2], p[3], p[4], kind, unmangle(p[7]), p[8])
        if (kind == Kind.NODE) return base
        if (p.size < 13) return null
        return base.copy(name = unmangle(p[9]), ddsType = p[10], hash = p[11], qos = p[12])
    }

    /**
     * DDS type of a service request or response from the service type announced by rmw_zenoh:
     * "example_interfaces::srv::dds_::AddTwoInts_" -> "example_interfaces::srv::dds_::AddTwoInts_Request_".
     */
    fun serviceMessageType(serviceDdsType: String, part: String): String =
        if (serviceDdsType.endsWith("_")) serviceDdsType.dropLast(1) + "_" + part + "_" else serviceDdsType + "_" + part

    /**
     * Type hash of a topic according to the tokens, or null. RIHS01 depends only on the type, so if nobody uses that
     * topic yet, the one from any other topic of the same type works (services announce the service type, they don't mix).
     */
    fun hashOf(tokens: Collection<Token>, name: String, ddsType: String): String? {
        fun ok(t: Token) = t.ddsType == ddsType && t.hash.isNotEmpty() && t.kind != Kind.NODE
        return (tokens.firstOrNull { ok(it) && it.name == name } ?: tokens.firstOrNull { ok(it) && (it.kind == Kind.PUBLISHER || it.kind == Kind.SUBSCRIBER) })?.hash
    }

    /**
     * ROS graph (the same model as with DDS) from the tokens: each session+node is a "participant" with a synthetic
     * GUID starting with [VENDOR_HEX] (so the node's DDS shows as "Zenoh"), and its endpoints carry the
     * usual DDS names ("rt/x", "rq/xRequest", "rr/xReply") so that lists, searches and actions work the same.
     */
    fun graph(tokens: Collection<Token>, ownName: String? = null): RosGraph {
        val endpoints = ArrayList<RosGraph.Endpoint>()
        val writersByNode = HashMap<String, MutableList<String>>()
        val readersByNode = HashMap<String, MutableList<String>>()
        val nodes = LinkedHashMap<String, Token>()
        fun prefix(t: Token) = VENDOR_HEX + sha1Hex(t.sessionId + "/" + t.nodeId).take(PREFIX_HEX - VENDOR_HEX.length)
        for (t in tokens) {
            val pre = prefix(t)
            if (t.kind == Kind.NODE) { nodes[pre] = t; continue }
            val id = "%06x".format((t.entityId.toLongOrNull() ?: 0L) and 0xFFFFFF)
            fun add(isWriter: Boolean, dds: String, type: String) {
                // One GUID per endpoint (a service has two): the last byte, as in DDS, 03 writer and 04 reader.
                val guid = pre + id + if (isWriter) "03" else "04"
                endpoints += RosGraph.Endpoint(isWriter, dds, type, guid)
                (if (isWriter) writersByNode else readersByNode).getOrPut(pre) { ArrayList() } += guid
            }
            when (t.kind) {
                Kind.PUBLISHER -> add(true, "rt" + t.name, t.ddsType)
                Kind.SUBSCRIBER -> add(false, "rt" + t.name, t.ddsType)
                Kind.SERVICE_SERVER -> {
                    add(false, "rq" + t.name + "Request", serviceMessageType(t.ddsType, "Request"))
                    add(true, "rr" + t.name + "Reply", serviceMessageType(t.ddsType, "Response"))
                }
                Kind.SERVICE_CLIENT -> {
                    add(true, "rq" + t.name + "Request", serviceMessageType(t.ddsType, "Request"))
                    add(false, "rr" + t.name + "Reply", serviceMessageType(t.ddsType, "Response"))
                }
                Kind.NODE -> {}
            }
        }
        val nodeInfo = nodes.entries.associate { (pre, t) ->
            (pre + "000001c1") to listOf(RosNodes.Entry(t.namespace, t.nodeName, readersByNode[pre].orEmpty(), writersByNode[pre].orEmpty()))
        }
        return RosGraph(endpoints, nodeInfo = nodeInfo, ownName = ownName)
    }

    /** Marker of the synthetic Zenoh GUIDs ("ze"): [RosGraph.ddsVendor] translates it to "Zenoh". */
    const val VENDOR_HEX = "7a65"
    private const val PREFIX_HEX = 24

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
