// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros

import com.juancams.rviz2mini.platform.Log
import io.zenoh.Config
import io.zenoh.Session
import io.zenoh.Zenoh
import io.zenoh.handlers.Callback
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.liveliness.LivelinessToken
import io.zenoh.pubsub.Publisher
import io.zenoh.pubsub.Subscriber
import io.zenoh.sample.Sample
import io.zenoh.sample.SampleKind
import io.zenoh.ext.CacheConfig
import io.zenoh.ext.HeartbeatMode
import io.zenoh.ext.HistoryConfig
import io.zenoh.ext.MissDetectionConfig
import io.zenoh.ext.RecoveryConfig
import io.zenoh.ext.RecoveryMode
import io.zenoh.pubsub.AdvancedPublisher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connection to robots using rmw_zenoh_cpp: a Zenoh session in client mode against their router (rmw_zenohd).
 * Follows the liveliness tokens for the graph and subscribes to topics; the data is the same CDR as over DDS, so
 * RosConnection delivers it just like foxglove_bridge data. See [ZenohNames] for the names.
 */
class ZenohLink(
    /** Router: "tcp/192.168.1.10:7447" (or "udp/..."). */
    private val endpoint: String,
    private val domainId: Int,
    /** Name of the app's node (announced with its liveliness token, like any rmw_zenoh node). */
    private val nodeName: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onError(message: String)
    }

    @Volatile private var session: Session? = null
    private var livelinessSub: Subscriber<Unit>? = null
    /** Live liveliness tokens: key expression -> token. */
    private val tokens = ConcurrentHashMap<String, ZenohNames.Token>()
    private val subscribers = ConcurrentHashMap<Int, AutoCloseable>()
    private val nextId = AtomicInteger(1)
    /** Ids of the app's entities in their tokens (the node is 0). */
    private val nextEntity = AtomicInteger(1)
    private val publishers = ConcurrentHashMap.newKeySet<Pub>()
    private var nodeToken: LivelinessToken? = null
    /** Session ZenohId in hexadecimal (the tokens' `session_id`). */
    @Volatile private var sessionId = ""

    val connected: Boolean get() = session != null

    /** Opens the session (blocks until connected or failed: call off the main thread). */
    fun open() {
        try {
            val config = Config.fromJson5(configJson(endpoint)).getOrThrow()
            val s = Zenoh.open(config).getOrThrow()
            session = s
            sessionId = s.info().zid().getOrThrow().toString()
            // The app's node, so it shows up in the robot's `ros2 node list`.
            nodeToken = declareToken(ZenohNames.nodeToken(domainId, sessionId, nodeName))
            // With history: existing tokens arrive first and then the changes.
            livelinessSub = s.liveliness().declareSubscriber(
                KeyExpr.tryFrom(ZenohNames.livelinessSelector(domainId)).getOrThrow(),
                Callback { sample ->
                    val key = sample.keyExpr.toString()
                    if (sample.kind == SampleKind.DELETE) tokens.remove(key)
                    else ZenohNames.parseToken(key)?.let { tokens[key] = it }
                },
                true,
            ).getOrThrow()
            listener.onConnected()
        } catch (e: Throwable) {
            Log.w(TAG, "No se pudo conectar con el router de Zenoh $endpoint", e)
            close()
            listener.onError(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Subscribes to a topic with any type hash (`*`): `onData` receives the CDR of each message, on a Zenoh
     * thread. Returns an id for [unsubscribe], or null if there is no session.
     *
     * With `transientLocal` (/tf_static, /map, /robot_description...) it is an advanced subscriber, like rmw_zenoh's:
     * it asks each TRANSIENT_LOCAL publisher's cache for its last `depth` messages, including publishers that appear
     * later, and with `reliable` it recovers lost ones (publisher heartbeat).
     */
    fun subscribe(
        topic: String, ddsType: String, transientLocal: Boolean = false, reliable: Boolean = true, depth: Int = 10,
        onData: (ByteArray) -> Unit,
    ): Int? {
        val s = session ?: return null
        return try {
            val key = KeyExpr.tryFrom(ZenohNames.dataKey(domainId, topic, ddsType)).getOrThrow()
            val callback = Callback<Sample> { sample ->
                try {
                    onData(sample.payload.toBytes())
                } catch (t: Throwable) {
                    Log.w(TAG, "Mensaje ilegible en $topic", t)
                }
            }
            val sub: AutoCloseable = if (transientLocal) {
                s.declareAdvancedSubscriber(
                    key,
                    HistoryConfig(true, depth.coerceAtLeast(1).toLong(), null),
                    if (reliable) RecoveryConfig(RecoveryMode.Heartbeat) else null,
                    true,
                    callback,
                ).getOrThrow()
            } else {
                s.declareSubscriber(key, callback).getOrThrow()
            }
            nextId.getAndIncrement().also { subscribers[it] = sub }
        } catch (e: Throwable) {
            Log.w(TAG, "No se pudo suscribir a $topic por Zenoh", e)
            null
        }
    }

    fun unsubscribe(id: Int) {
        subscribers.remove(id)?.let { runCatching { it.close() } }
    }

    /**
     * Publisher of a topic. The key expression carries the type hash, which the app does not compute: it is taken from the tokens of
     * whoever already uses that topic with that type. Until someone uses it there is no one to send to and [put] returns false.
     */
    inner class Pub constructor(
        val topic: String,
        val ddsType: String,
        private val qosKey: String,
        /** TRANSIENT_LOCAL: advanced publisher with a cache of `depth` messages for late joiners. */
        private val transientLocal: Boolean = false,
        private val reliable: Boolean = true,
        private val depth: Int = 1,
    ) {
        private val entityId = nextEntity.getAndIncrement()
        private val gid = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        private val seq = java.util.concurrent.atomic.AtomicLong()
        /** Publisher or AdvancedPublisher (TRANSIENT_LOCAL). */
        @Volatile private var publisher: Any? = null
        @Volatile private var token: LivelinessToken? = null
        @Volatile var closed = false; private set

        /** Publishes a CDR message (with its encapsulation header). False if there is no one yet or no session. */
        fun put(cdr: ByteArray): Boolean {
            if (closed) return false
            val p = publisher ?: declare() ?: return false
            val attachment = io.zenoh.bytes.ZBytes.from(ZenohNames.attachment(seq.incrementAndGet(), System.currentTimeMillis() * 1_000_000L, gid))
            val payload = io.zenoh.bytes.ZBytes.from(cdr)
            return when (p) {
                is AdvancedPublisher -> p.put(payload, attachment = attachment).isSuccess
                is Publisher -> p.put(payload, attachment = attachment).isSuccess
                else -> false
            }
        }

        /** Subscribers of the topic with that type according to the tokens (for "matched subscribers"). */
        fun subscribers(): Int = tokens.values.count { it.kind == ZenohNames.Kind.SUBSCRIBER && it.name == topic && it.ddsType == ddsType }

        @Synchronized private fun declare(): Any? {
            publisher?.let { return it }
            val s = session ?: return null
            val hash = ZenohNames.hashOf(tokens.values, topic, ddsType) ?: return null
            return try {
                val key = KeyExpr.tryFrom(ZenohNames.dataKey(domainId, topic, ddsType, hash)).getOrThrow()
                val p: AutoCloseable = if (transientLocal) {
                    // Like rmw_zenoh: cache for advanced subscribers' history, detectable via liveliness
                    // and, if RELIABLE, with heartbeat so they recover what was lost.
                    s.declareAdvancedPublisher(
                        key,
                        cacheConfig = CacheConfig(depth.coerceAtLeast(1).toLong()),
                        sampleMissDetection = if (reliable) MissDetectionConfig(HeartbeatMode.SporadicHeartbeat(500)) else null,
                        publisherDetection = true,
                    ).getOrThrow()
                } else {
                    s.declarePublisher(key).getOrThrow()
                }
                publisher = p
                token = declareToken(ZenohNames.endpointToken(domainId, sessionId, entityId, ZenohNames.Kind.PUBLISHER, nodeName, topic, ddsType, hash, qosKey))
                p
            } catch (e: Throwable) {
                Log.w(TAG, "No se pudo publicar en $topic por Zenoh", e)
                null
            }
        }

        fun close() {
            closed = true
            publishers.remove(this)
            token?.let { runCatching { it.close() } }; token = null
            (publisher as? AutoCloseable)?.let { runCatching { it.close() } }; publisher = null
        }
    }

    /** Publisher of `topic` (see [Pub]); `bestEffort`, `transientLocal` and `depth` go in its token, as in rmw_zenoh. */
    fun advertise(topic: String, ddsType: String, bestEffort: Boolean, transientLocal: Boolean, depth: Int): Pub =
        Pub(topic, ddsType, ZenohNames.qosKey(bestEffort, transientLocal, depth), transientLocal, !bestEffort, depth)
            .also { publishers.add(it) }

    /** The app's identity as a service client (goes in the attachment of each request). */
    private val clientGid = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    private val callSeq = java.util.concurrent.atomic.AtomicLong()

    /** Whether any node announces a server for `service`. */
    fun hasService(service: String): Boolean = tokens.values.any { it.kind == ZenohNames.Kind.SERVICE_SERVER && it.name == service }

    /**
     * Calls a service like rmw_zenoh: a get on the server's queryable (key expression with the type and hash it
     * announces) with the request in CDR and the attachment. `onReply` receives the reply CDR or the error, on a Zenoh
     * thread. Returns false if there is no session or server.
     */
    fun call(service: String, request: ByteArray, timeoutMs: Long, onReply: (ByteArray?, String?) -> Unit): Boolean {
        val s = session ?: return false
        val server = tokens.values.firstOrNull { it.kind == ZenohNames.Kind.SERVICE_SERVER && it.name == service } ?: return false
        val key = ZenohNames.dataKey(domainId, service, server.ddsType, server.hash)
        val attachment = ZenohNames.attachment(callSeq.incrementAndGet(), System.currentTimeMillis() * 1_000_000L, clientGid)
        return s.get(
            io.zenoh.query.Selector.tryFrom(key).getOrThrow(),
            Callback { reply ->
                val r = reply.result
                r.fold({ onReply(it.payload.toBytes(), null) }, { e -> onReply(null, e.message ?: "Error from $service") })
            },
            payload = io.zenoh.bytes.ZBytes.from(request),
            attachment = io.zenoh.bytes.ZBytes.from(attachment),
            timeout = java.time.Duration.ofMillis(timeoutMs),
            target = io.zenoh.query.QueryTarget.ALL_COMPLETE,
        ).isSuccess
    }

    private fun declareToken(key: String): LivelinessToken? = try {
        session?.liveliness()?.declareToken(KeyExpr.tryFrom(key).getOrThrow())?.getOrThrow()
    } catch (e: Throwable) {
        Log.w(TAG, "No se pudo anunciar $key", e)
        null
    }

    /** ROS graph from the seen tokens (see [ZenohNames.graph]). */
    fun graph(ownName: String?): RosGraph = ZenohNames.graph(tokens.values, ownName)

    fun close() {
        for (p in publishers.toList()) p.close()
        nodeToken?.let { runCatching { it.close() } }
        nodeToken = null
        for (sub in subscribers.values) runCatching { sub.close() }
        subscribers.clear()
        livelinessSub?.let { runCatching { it.close() } }
        livelinessSub = null
        tokens.clear()
        session?.let { runCatching { it.close() } }
        session = null
    }

    companion object {
        private const val TAG = "VizMini2"

        /**
         * Session configuration: client of the given router, without looking for others via multicast (like rmw_zenoh with
         * its default configuration) and retrying the router connection if it is lost.
         */
        fun configJson(endpoint: String): String = """
            {
              mode: "client",
              connect: { endpoints: ["${endpoint.replace("\"", "")}"], timeout_ms: { client: 5000 }, exit_on_failure: { client: true } },
              scouting: { multicast: { enabled: false } }
            }
        """.trimIndent()
    }
}
