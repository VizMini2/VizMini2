// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import com.juancams.rviz2mini.platform.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client of the Foxglove WebSocket protocol (the foxglove_bridge one: `foxglove.sdk.v1` since
 * 3.x, `foxglove.websocket.v1` in earlier ones; both are offered and the server chooses): instead
 * of DDS, topics arrive over a websocket (also over the internet or mobile data). The
 * messages travel in CDR, as in DDS, so they are read with the same message classes.
 *
 * Callbacks arrive on the OkHttp thread.
 */
class FoxgloveBridge(val url: String, private val listener: Listener) {

    interface Listener {
        fun onConnected(serverName: String, capabilities: Set<String>)
        fun onChannelsChanged()
        fun onClosed(reason: String?)
    }

    /** A topic advertised by the server. */
    data class Channel(val id: Int, val topic: String, val encoding: String, val schemaName: String)

    /** A service advertised by the server (`type`: "example_interfaces/srv/AddTwoInts"). */
    data class Service(val id: Int, val name: String, val type: String)

    /** A service call in progress: `done` receives the CDR response or the error. */
    private class Call(val serviceId: Int, val done: (ByteArray?, String?) -> Unit)

    private class Sub(val id: Int, val topic: String, val onData: (ByteArray, Long) -> Unit) {
        @Volatile var channelId: Int? = null
    }

    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var socket: WebSocket? = null
    val channels = ConcurrentHashMap<Int, Channel>()
    private val subs = ConcurrentHashMap<Int, Sub>()
    private val nextSub = AtomicInteger(1)
    private val nextClientChannel = AtomicInteger(1)
    /** Own channels for publishing: topic -> (id, type). */
    private val clientChannels = ConcurrentHashMap<String, Pair<Int, String>>()
    /** Advertised services, by name. */
    val services = ConcurrentHashMap<String, Service>()
    private val calls = ConcurrentHashMap<Int, Call>()
    private val nextCall = AtomicInteger(1)
    @Volatile var capabilities: Set<String> = emptySet(); private set

    /**
     * Connection graph (`connectionGraph` capability): per topic, which nodes publish it and which
     * subscribe; per service, which nodes provide it. Node names are the ones the bridge gives.
     */
    class GraphEntry(val publishers: Set<String>, val subscribers: Set<String>)
    val graphTopics = ConcurrentHashMap<String, GraphEntry>()
    val graphServices = ConcurrentHashMap<String, Set<String>>()
    @Volatile var hasConnectionGraph = false; private set

    /** Parameter requests in progress (`parameters` capability): id -> who is waiting for the response. */
    private val paramCalls = ConcurrentHashMap<String, (JSONArray?) -> Unit>()
    private val nextParamCall = AtomicInteger(1)

    val canUseParameters: Boolean get() = connected && "parameters" in capabilities

    /** Requests parameters ("/node.name"; empty = all). `done` receives the response's `parameters` list. */
    fun getParameters(names: List<String>, done: (JSONArray?) -> Unit): String {
        val id = "p" + nextParamCall.getAndIncrement()
        paramCalls[id] = done
        send(JSONObject().put("op", "getParameters").put("parameterNames", JSONArray(names)).put("id", id))
        return id
    }

    /** Sets parameters (`params`: [{name, value, type?}]); the response carries the resulting values. */
    fun setParameters(params: JSONArray, done: (JSONArray?) -> Unit): String {
        val id = "p" + nextParamCall.getAndIncrement()
        paramCalls[id] = done
        send(JSONObject().put("op", "setParameters").put("parameters", params).put("id", id))
        return id
    }

    fun forgetParameterCall(id: String) { paramCalls.remove(id) }
    @Volatile var connected = false; private set
    @Volatile var lastStatus: String? = null; private set

    fun open() {
        val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", PROTOCOLS).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onBinary(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(reason.ifBlank { "closed ($code)" })
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(t.message ?: t.javaClass.simpleName)
        })
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
        connected = false
        failAllCalls("Disconnected")
        client.dispatcher.executorService.shutdown()
    }

    private fun closed(reason: String) {
        if (socket == null) return
        connected = false
        socket = null
        failAllCalls(reason)
        listener.onClosed(reason)
    }

    private fun failAllCalls(reason: String) {
        for (id in calls.keys.toList()) calls.remove(id)?.done?.invoke(null, reason)
        for (id in paramCalls.keys.toList()) paramCalls.remove(id)?.invoke(null)
    }

    // ---------------------------------------------------------------- receive

    private fun onText(text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("op")) {
            "serverInfo" -> {
                capabilities = o.optJSONArray("capabilities")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
                connected = true
                if ("connectionGraph" in capabilities) send(JSONObject().put("op", "subscribeConnectionGraph"))
                listener.onConnected(o.optString("name"), capabilities)
            }
            "advertise" -> {
                val a = o.optJSONArray("channels") ?: return
                for (i in 0 until a.length()) {
                    val c = a.optJSONObject(i) ?: continue
                    val ch = Channel(c.optInt("id"), c.optString("topic"), c.optString("encoding"), c.optString("schemaName"))
                    channels[ch.id] = ch
                }
                // Subscriptions that were waiting for their topic to appear.
                for (s in subs.values) if (s.channelId == null) trySubscribe(s)
                listener.onChannelsChanged()
            }
            "unadvertise" -> {
                val ids = o.optJSONArray("channelIds") ?: return
                for (i in 0 until ids.length()) {
                    val id = ids.optInt(i)
                    channels.remove(id)
                    for (s in subs.values) if (s.channelId == id) s.channelId = null
                }
                listener.onChannelsChanged()
            }
            "advertiseServices" -> {
                val a = o.optJSONArray("services") ?: return
                for (i in 0 until a.length()) {
                    val c = a.optJSONObject(i) ?: continue
                    val s = Service(c.optInt("id"), c.optString("name"), c.optString("type"))
                    services[s.name] = s
                }
                listener.onChannelsChanged()
            }
            "unadvertiseServices" -> {
                val ids = o.optJSONArray("serviceIds") ?: return
                val gone = (0 until ids.length()).map { ids.optInt(it) }.toSet()
                services.values.removeAll { it.id in gone }
                for ((id, c) in calls.entries.toList()) if (c.serviceId in gone) calls.remove(id)?.done?.invoke(null, "The service went away")
                listener.onChannelsChanged()
            }
            "connectionGraphUpdate" -> {
                fun ids(a: JSONArray?) = a?.let { x -> (0 until x.length()).map { x.optString(it) }.toSet() }.orEmpty()
                val pubs = HashMap<String, Set<String>>()
                o.optJSONArray("publishedTopics")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { pubs[it.optString("name")] = ids(it.optJSONArray("publisherIds")) } }
                val subs = HashMap<String, Set<String>>()
                o.optJSONArray("subscribedTopics")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { subs[it.optString("name")] = ids(it.optJSONArray("subscriberIds")) } }
                // Updates carry what changes: an incoming topic replaces whatever there was for it.
                for (name in pubs.keys + subs.keys) {
                    val old = graphTopics[name]
                    graphTopics[name] = GraphEntry(pubs[name] ?: old?.publishers.orEmpty(), subs[name] ?: old?.subscribers.orEmpty())
                }
                o.optJSONArray("advertisedServices")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { graphServices[it.optString("name")] = ids(it.optJSONArray("providerIds")) } }
                ids(o.optJSONArray("removedTopics")).forEach { graphTopics.remove(it) }
                ids(o.optJSONArray("removedServices")).forEach { graphServices.remove(it) }
                hasConnectionGraph = true
                listener.onChannelsChanged()
            }
            "parameterValues" -> o.optString("id").takeIf { it.isNotEmpty() }?.let { paramCalls.remove(it) }?.invoke(o.optJSONArray("parameters"))
            "serviceCallFailure" -> calls.remove(o.optInt("callId"))?.done?.invoke(null, o.optString("message").ifBlank { "Service call failed" })
            "status" -> {
                lastStatus = o.optString("message")
                Log.i(TAG, "Bridge: ${o.optInt("level")} $lastStatus")
            }
        }
    }

    private fun onBinary(b: ByteArray) {
        if (b.isNotEmpty() && b[0].toInt() == OP_SERVICE_RESPONSE) { onServiceResponse(b); return }
        if (b.isEmpty() || b[0].toInt() != OP_MESSAGE_DATA || b.size < 13) return
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val subId = bb.getInt(1)
        val stamp = bb.getLong(5)
        val s = subs[subId] ?: return
        s.onData(b.copyOfRange(13, b.size), stamp)
    }

    // ---------------------------------------------------------------- subscribe

    /** Subscribes to `topic` (as soon as the server advertises it). Returns the id for [unsubscribe]. */
    fun subscribe(topic: String, onData: (ByteArray, Long) -> Unit): Int {
        val s = Sub(nextSub.getAndIncrement(), topic, onData)
        subs[s.id] = s
        trySubscribe(s)
        return s.id
    }

    private fun trySubscribe(s: Sub) {
        val ch = channels.values.firstOrNull { it.topic == s.topic && it.encoding == "cdr" } ?: return
        s.channelId = ch.id
        send(JSONObject().put("op", "subscribe").put("subscriptions", JSONArray().put(JSONObject().put("id", s.id).put("channelId", ch.id))))
    }

    fun unsubscribe(id: Int) {
        val s = subs.remove(id) ?: return
        if (s.channelId != null) send(JSONObject().put("op", "unsubscribe").put("subscriptionIds", JSONArray().put(id)))
    }

    // ---------------------------------------------------------------- publish

    val canPublish: Boolean get() = "clientPublish" in capabilities

    /** Advertises an own topic (once per topic). Returns its id, or null if the server does not allow publishing. */
    fun advertise(topic: String, rosType: String): Int? {
        if (!canPublish) return null
        clientChannels[topic]?.let { return it.first }
        val id = nextClientChannel.getAndIncrement()
        clientChannels[topic] = id to rosType
        send(JSONObject().put("op", "advertise").put("channels", JSONArray().put(
            JSONObject().put("id", id).put("topic", topic).put("encoding", "cdr").put("schemaName", rosType))))
        return id
    }

    fun publish(channelId: Int, cdr: ByteArray): Boolean {
        val s = socket ?: return false
        val out = ByteBuffer.allocate(5 + cdr.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(OP_CLIENT_MESSAGE_DATA.toByte()).putInt(channelId).put(cdr)
        return s.send(out.array().toByteString())
    }

    // ---------------------------------------------------------------- services

    val canCallServices: Boolean get() = "services" in capabilities

    /**
     * Calls a service with the request in CDR (with its encapsulation header). `done` receives the
     * CDR response or the failure reason (on the OkHttp thread). Returns the call id, or
     * null if it cannot be made (the bridge does not advertise that service or does not support calls).
     */
    fun callService(name: String, cdr: ByteArray, done: (ByteArray?, String?) -> Unit): Int? {
        val s = services[name] ?: return null
        val ws = socket ?: return null
        if (!canCallServices) return null
        val callId = nextCall.getAndIncrement()
        calls[callId] = Call(s.id, done)
        val enc = ENCODING_CDR.toByteArray(Charsets.UTF_8)
        val out = ByteBuffer.allocate(1 + 4 + 4 + 4 + enc.size + cdr.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(OP_SERVICE_REQUEST.toByte()).putInt(s.id).putInt(callId).putInt(enc.size).put(enc).put(cdr)
        if (!ws.send(out.array().toByteString())) { calls.remove(callId); return null }
        return callId
    }

    /** The response is no longer wanted (cancelled or timed out). */
    fun forgetCall(callId: Int) { calls.remove(callId) }

    private fun onServiceResponse(b: ByteArray) {
        if (b.size < 13) return
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val callId = bb.getInt(5)
        val encLen = bb.getInt(9)
        if (encLen < 0 || 13 + encLen > b.size) return
        calls.remove(callId)?.done?.invoke(b.copyOfRange(13 + encLen, b.size), null)
    }

    private fun send(o: JSONObject) { socket?.send(o.toString()) }

    companion object {
        const val PROTOCOLS = "foxglove.sdk.v1, foxglove.websocket.v1"
        private const val OP_MESSAGE_DATA = 0x01
        private const val OP_CLIENT_MESSAGE_DATA = 0x01
        private const val OP_SERVICE_RESPONSE = 0x03
        private const val OP_SERVICE_REQUEST = 0x02
        private const val ENCODING_CDR = "cdr"
        private const val TAG = "VizMini2"
    }
}
