// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros.dyn

import com.juancams.rviz2mini.ros.RosConnection
import us.ihmc.jros2.ROS2Message
import us.ihmc.jros2.ROS2QoSProfile
import us.ihmc.jros2.ROS2SubscriptionCallback
import us.ihmc.jros2.GraphAccess
import us.ihmc.jros2.SampleInfo
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishers and subscriptions of dynamic messages over [RosConnection] (recreated automatically on
 * reconnect).
 *
 * The [DynSlots] classes only exist at runtime: everything that touches the generic type
 * goes in generic functions so Kotlin does not insert casts to a concrete class.
 */
object DynRos {

    class Publisher constructor(val spec: MsgSpec, private val managed: RosConnection.ManagedPublisher<*>) {
        /** False if there is no connection yet. Always call from the same thread. */
        fun publish(value: MsgValue): Boolean = publishAny(managed, DynTypes.newMessage(spec, value))
        /**
         * Like [publish] and copies the sample's DDS identity into `identityOut` (24 bytes); `related`, its
         * related_sample_identity (see [RosConnection.ManagedPublisher.publishWithIdentity]).
         */
        fun publishWithIdentity(value: MsgValue, identityOut: ByteArray, related: ByteArray? = null): Boolean =
            publishIdentityAny(managed, DynTypes.newMessage(spec, value), identityOut, related)
        /** Whether it is matched with the reader with that GUID (16 bytes); true if it cannot be known. */
        fun isMatchedWith(readerGuid: ByteArray): Boolean = managed.isMatchedWith(readerGuid)
        fun matched(): Int = managed.matchedSubscribers()
        val isReady: Boolean get() = managed.isReady
        fun close() = managed.close()
    }

    class Subscription constructor(private val managed: RosConnection.ManagedSubscription<*>) {
        val error: String? get() = managed.error
        fun waitForPublisher(timeoutMs: Long) = managed.waitForPublisher(timeoutMs)
        /** The GUID (16 bytes) of its DDS reader; false if there is none yet. */
        fun readerGuid(out: ByteArray): Boolean = managed.readerGuid(out)
        fun close() = managed.cancel()
    }

    fun publisher(topic: String, spec: MsgSpec, qos: ROS2QoSProfile): Publisher =
        Publisher(spec, createPublisherAny(topic, null, DynTypes.classFor(spec), qos))

    fun rawPublisher(ddsName: String, spec: MsgSpec, qos: ROS2QoSProfile): Publisher =
        Publisher(spec, createPublisherAny(null, ddsName, DynTypes.classFor(spec), qos))

    /** `onMessage` is called on the DDS thread; exceptions are already caught by RosConnection. */
    fun subscribe(topic: String, spec: MsgSpec, qos: ROS2QoSProfile, onMessage: (MsgValue) -> Unit): Subscription =
        Subscription(subscribeAny(topic, null, DynTypes.classFor(spec), qos, onMessage))

    fun subscribeRaw(ddsName: String, spec: MsgSpec, qos: ROS2QoSProfile, onMessage: (MsgValue) -> Unit): Subscription =
        Subscription(subscribeAny(null, ddsName, DynTypes.classFor(spec), qos, onMessage))

    /**
     * Like [subscribeRaw] but also passes the "related sample identity" of each sample: in a
     * service response it is the identity of the request it answers.
     */
    fun subscribeRawWithIdentity(ddsName: String, spec: MsgSpec, qos: ROS2QoSProfile, onMessage: (MsgValue, ByteArray) -> Unit): Subscription =
        Subscription(subscribeIdentityAny(ddsName, DynTypes.classFor(spec), qos) { v, _, related -> onMessage(v, related) })

    /**
     * Like [subscribeRawWithIdentity], with each sample's own identity too: in a service request, what its reply
     * must carry as related_sample_identity; its related one, the client's reply reader (rmw_fastrtps and this app).
     */
    fun subscribeRawWithIdentities(
        ddsName: String, spec: MsgSpec, qos: ROS2QoSProfile, onMessage: (value: MsgValue, identity: ByteArray, related: ByteArray) -> Unit
    ): Subscription = Subscription(subscribeIdentityAny(ddsName, DynTypes.classFor(spec), qos, onMessage))

    @Suppress("UNCHECKED_CAST")
    private fun subscribeIdentityAny(ddsName: String, cls: Class<*>, qos: ROS2QoSProfile, onMessage: (MsgValue, ByteArray, ByteArray) -> Unit) =
        subscribeIdentityTyped(ddsName, cls as Class<DynSlots.S0>, qos, onMessage)

    private fun <T : ROS2Message<T>> subscribeIdentityTyped(
        ddsName: String, cls: Class<T>, qos: ROS2QoSProfile, onMessage: (MsgValue, ByteArray, ByteArray) -> Unit
    ): RosConnection.ManagedSubscription<T> {
        val cb = ROS2SubscriptionCallback<T> { reader ->
            val data = cls.getDeclaredConstructor().newInstance()
            // Own native SampleInfo to read the identities; services run at low frequency.
            SampleInfo().use { info ->
                if (reader.read(data, info)) {
                    val identity = ByteArray(IDENTITY_SIZE)
                    val related = ByteArray(IDENTITY_SIZE)
                    GraphAccess.sampleIdentity(info, identity)
                    GraphAccess.relatedSampleIdentity(info, related)
                    (data as? DynMessage<*>)?.let { onMessage(it.value, identity, related) }
                }
            }
        }
        return RosConnection.subscribeRaw(ddsName, cls, qos, cb)
    }

    /** DDS identity of a sample: writer GUID (16 bytes) + sequence number (8). */
    const val IDENTITY_SIZE = 24
    const val GUID_SIZE = 16

    /** An identity with only a GUID: the sequence number "unknown" (high -1, low 0), as rmw_fastrtps sends it. */
    fun guidIdentity(guid: ByteArray): ByteArray = ByteArray(IDENTITY_SIZE).also {
        guid.copyInto(it, 0, 0, GUID_SIZE)
        for (i in 16 until 20) it[i] = 0xFF.toByte()
    }

    @Suppress("UNCHECKED_CAST")
    private fun publishIdentityAny(p: RosConnection.ManagedPublisher<*>, msg: DynMessage<*>, identityOut: ByteArray, related: ByteArray?): Boolean =
        publishIdentityTyped(p as RosConnection.ManagedPublisher<DynSlots.S0>, msg, identityOut, related)

    @Suppress("UNCHECKED_CAST")
    private fun <T : ROS2Message<T>> publishIdentityTyped(p: RosConnection.ManagedPublisher<T>, msg: DynMessage<*>, identityOut: ByteArray, related: ByteArray?): Boolean =
        p.publishWithIdentity(msg as T, identityOut, related)

    @Suppress("UNCHECKED_CAST")
    private fun createPublisherAny(topic: String?, ddsName: String?, cls: Class<*>, qos: ROS2QoSProfile): RosConnection.ManagedPublisher<*> =
        createPublisherTyped(topic, ddsName, cls as Class<DynSlots.S0>, qos)

    private fun <T : ROS2Message<T>> createPublisherTyped(topic: String?, ddsName: String?, cls: Class<T>, qos: ROS2QoSProfile) =
        if (ddsName != null) RosConnection.createRawPublisher(ddsName, cls, qos) else RosConnection.createPublisher(topic!!, cls, qos)

    @Suppress("UNCHECKED_CAST")
    private fun publishAny(p: RosConnection.ManagedPublisher<*>, msg: DynMessage<*>): Boolean =
        publishTyped(p as RosConnection.ManagedPublisher<DynSlots.S0>, msg)

    @Suppress("UNCHECKED_CAST")
    private fun <T : ROS2Message<T>> publishTyped(p: RosConnection.ManagedPublisher<T>, msg: DynMessage<*>): Boolean = p.publish(msg as T)

    @Suppress("UNCHECKED_CAST")
    private fun subscribeAny(topic: String?, ddsName: String?, cls: Class<*>, qos: ROS2QoSProfile, onMessage: (MsgValue) -> Unit) =
        subscribeTyped(topic, ddsName, cls as Class<DynSlots.S0>, qos, onMessage)

    private fun <T : ROS2Message<T>> subscribeTyped(
        topic: String?, ddsName: String?, cls: Class<T>, qos: ROS2QoSProfile, onMessage: (MsgValue) -> Unit
    ): RosConnection.ManagedSubscription<T> {
        val cb = ROS2SubscriptionCallback<T> { reader ->
            val m: Any? = reader.read()
            (m as? DynMessage<*>)?.let { onMessage(it.value) }
        }
        return if (ddsName != null) RosConnection.subscribeRaw(ddsName, cls, qos, cb) else RosConnection.subscribe(topic!!, cls, qos, cb)
    }

    /** "/add_two_ints" or "add_two_ints" -> "/add_two_ints" (no trailing slash). */
    fun normalizeName(name: String): String {
        val t = name.trim().trimEnd('/')
        return if (t.startsWith("/")) t else "/$t"
    }
}

/** Failure calling a service or an action, with a message for the user. */
class CallException(message: String) : Exception(message)

/**
 * Client of a ROS 2 service: writes to "rq<name>Request" and reads "rr<name>Reply".
 *
 * With an rmw_cyclonedds_cpp server, requests and responses carry their own header in front (client
 * guid and call number) instead of the DDS identity: this is detected from the vendor of the server's request
 * reader, and a separate channel with that header is used ([CycloneChannel]).
 *
 * Each response is matched to its request as rmw does: the server writes it with the
 * request's DDS identity as "related sample identity" (natives from the jros2 fork), so
 * responses to other clients of the same service are ignored. Supports several calls at once:
 * each one waits only for the response with its identity.
 */
class DynServiceClient(name: String, val srv: SrvSpec) {

    val serviceName = DynRos.normalizeName(name)
    /** Calls in progress: request identity (hex) -> where to put its response. Under [lock]. */
    private val waiters = LinkedHashMap<String, ArrayBlockingQueue<MsgValue>>()
    /** Responses that arrive before their call has been registered (right after publishing). Under [lock]. */
    private val early = LinkedHashMap<String, MsgValue>()
    /**
     * Calls in progress in arrival order, recorded before publishing: for responses without
     * identity, which may arrive before the call is registered in [waiters]. Under [lock].
     */
    private val pending = ArrayDeque<ArrayBlockingQueue<MsgValue>>()
    private val lock = Any()
    /** Through foxglove_bridge calls go over the WebSocket: the service's DDS topics are not needed. */
    private val viaBridge = RosConnection.usesBridge
    /** Through Zenoh (rmw_zenoh_cpp) calls are Zenoh queries: not needed either. */
    private val viaZenoh = !viaBridge && RosConnection.usesZenoh
    private val requests = if (viaBridge || viaZenoh) null else DynRos.rawPublisher("rq${serviceName}Request", srv.request, RosConnection.defaultQos())
    private val replies = if (viaBridge || viaZenoh) null else DynRos.subscribeRawWithIdentity("rr${serviceName}Reply", srv.response, RosConnection.defaultQos()) { v, related ->
        deliver(v, related)
    }
    @Volatile private var warmedUp = false

    /** Channel with the rmw_cyclonedds header (see [MsgSpec.withCycloneHeader]); created the first time it is needed. */
    private inner class CycloneChannel {
        /** Identifies this client in the responses: the Cyclone server sends them to all the service's readers. */
        val guid: Long = java.security.SecureRandom().nextLong().let { if (it == 0L) 1L else it }
        val nextSeq = java.util.concurrent.atomic.AtomicLong()
        val waiters = java.util.concurrent.ConcurrentHashMap<Long, ArrayBlockingQueue<MsgValue>>()
        val requests = DynRos.rawPublisher("rq${serviceName}Request", srv.request.withCycloneHeader(), RosConnection.defaultQos())
        val replies = DynRos.subscribeRaw("rr${serviceName}Reply", srv.response.withCycloneHeader(), RosConnection.defaultQos()) { v ->
            if ((v[MsgSpec.CYCLONE_GUID] as? Number)?.toLong() != guid) return@subscribeRaw // response to another client
            val seq = (v.remove(MsgSpec.CYCLONE_SEQ) as? Number)?.toLong() ?: return@subscribeRaw
            v.remove(MsgSpec.CYCLONE_GUID)
            if (srv.response.fields.isEmpty()) v.remove(MsgSpec.EMPTY_STRUCT_FIELD)
            waiters[seq]?.offer(v)
        }
        /** When it was created: the first call's grace period counts from here (see [callCyclone]). */
        val createdAt = System.currentTimeMillis()
        @Volatile var warmedUp = false
        fun close() { requests.close(); replies.close() }
    }
    @Volatile private var cyclone: CycloneChannel? = null

    @Volatile private var closed = false

    private fun cycloneChannel(): CycloneChannel = cyclone ?: synchronized(lock) {
        cyclone ?: CycloneChannel().also { ch -> if (closed) ch.close() else cyclone = ch }
    }

    /**
     * Prepares in the background what is needed to call without discovery waits: with a Cyclone server it creates
     * the channel (its endpoints take a while to match over Wi-Fi). Used by the action for cancel_goal and get_result as soon as
     * it sends the goal: when leaving the app there are only a few seconds to send the cancellation.
     */
    fun prepare() {
        if (viaBridge || viaZenoh || closed || cyclone != null || RosConnection.config.serviceFormat == com.juancams.rviz2mini.ros.ServiceFormat.FAST_DDS) return
        Thread({
            runCatching {
                val forced = RosConnection.config.serviceFormat == com.juancams.rviz2mini.ros.ServiceFormat.CYCLONE
                if (forced || serverIsCyclone(System.currentTimeMillis() + GRAPH_WAIT_MS)) cycloneChannel()
            }
        }, "prepare $serviceName").apply { isDaemon = true }.start()
    }

    /** Whether the server is rmw_cyclonedds, once seen in the graph (null: not known yet). */
    @Volatile private var cycloneServer: Boolean? = null

    /**
     * The server (the one reading the requests) is rmw_cyclonedds: its GUID starts with the Cyclone DDS vendor.
     * Decided once and remembered: a graph snapshot that does not have the server yet would send the call via the
     * Fast DDS path, which a Cyclone server does not understand. If the graph does not show it yet, wait a bit.
     */
    private fun serverIsCyclone(deadline: Long): Boolean {
        cycloneServer?.let { return it }
        val until = minOf(deadline, System.currentTimeMillis() + GRAPH_WAIT_MS)
        while (true) {
            val readers = RosConnection.graph()?.endpoints?.filter { !it.isWriter && it.ddsTopic == "rq${serviceName}Request" }.orEmpty()
            if (readers.isNotEmpty()) return readers.all { it.guid.startsWith(CYCLONE_VENDOR_HEX) }.also { cycloneServer = it }
            if (System.currentTimeMillis() >= until) return false // no graph data: as usual (Fast DDS)
            Thread.sleep(50)
        }
    }

    private fun deliver(v: MsgValue, related: ByteArray) = synchronized(lock) {
        // A server that does not fill in the identity gives no way to tell: goes to the oldest call. Fast DDS
        // leaves it "unknown": zero GUID (16 bytes) and sequence number -1, not all zeros.
        if ((0 until GUID_SIZE).all { related[it] == 0.toByte() }) {
            pending.firstOrNull { it.isEmpty() }?.offer(v)
            return@synchronized
        }
        val key = hex(related)
        val q = waiters[key]
        if (q != null) q.offer(v)
        else {
            // May be from another client of the same service or from one of our calls not yet
            // registered: kept for a while (other clients' ones are discarded on their own).
            early[key] = v
            while (early.size > EARLY_MAX) early.remove(early.keys.first())
        }
    }

    /** Blocks until the response. `cancelled` is checked while waiting. May be called from several threads. */
    fun call(request: MsgValue, timeoutMs: Long, cancelled: () -> Boolean = { false }): MsgValue {
        if (viaBridge) return RosConnection.callServiceOverBridge(serviceName, srv, request, timeoutMs, cancelled)
        if (viaZenoh) return RosConnection.callServiceOverZenoh(serviceName, srv, request, timeoutMs, cancelled)
        val requests = requests!!
        val deadline = System.currentTimeMillis() + timeoutMs
        waitForServer(deadline, cancelled)
        val useCyclone = when (RosConnection.config.serviceFormat) {
            com.juancams.rviz2mini.ros.ServiceFormat.CYCLONE -> true
            com.juancams.rviz2mini.ros.ServiceFormat.FAST_DDS -> false
            com.juancams.rviz2mini.ros.ServiceFormat.AUTO -> serverIsCyclone(deadline)
        }
        if (useCyclone) return callCyclone(request, deadline, timeoutMs, cancelled)
        val identity = ByteArray(DynRos.IDENTITY_SIZE)
        val queue = ArrayBlockingQueue<MsgValue>(1)
        synchronized(lock) { pending.addLast(queue) }
        var key = ""
        var altKey: String? = null
        try {
            // The reply reader's GUID as the request's related identity (as rmw_fastrtps): the server can wait until it
            // is matched before replying, so the first reply is not lost while discovery finishes.
            val readerGuid = ByteArray(DynRos.GUID_SIZE)
            val related = if (replies!!.readerGuid(readerGuid)) DynRos.guidIdentity(readerGuid) else null
            if (!requests.publishWithIdentity(request, identity, related)) throw CallException("No ROS 2 connection")
            key = hex(identity)
            // rmw_fastrtps servers answer a request that names the reply reader with that reader's GUID (and the
            // request's sequence number) instead of the request writer's: either form is this call's reply.
            if (related != null) altKey = hex(readerGuid + identity.copyOfRange(DynRos.GUID_SIZE, DynRos.IDENTITY_SIZE))
            synchronized(lock) {
                early.remove(key)?.let { return it }
                altKey?.let { k -> early.remove(k)?.let { return it } }
                waiters[key] = queue
                altKey?.let { waiters[it] = queue }
            }
            while (true) {
                if (cancelled()) throw CallException("Canceled")
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw CallException("$serviceName did not respond within ${timeoutMs / 1000.0} s")
                queue.poll(minOf(left, 100L), TimeUnit.MILLISECONDS)?.let { return it }
            }
        } finally {
            synchronized(lock) { waiters.remove(key); altKey?.let { waiters.remove(it) }; pending.remove(queue) }
        }
    }

    /** Call to an rmw_cyclonedds_cpp server: the request carries the header and the response is recognized by it. */
    private fun callCyclone(request: MsgValue, deadline: Long, timeoutMs: Long, cancelled: () -> Boolean): MsgValue {
        val ch = cycloneChannel()
        // The channel endpoints may be new: wait for them to match as in waitForServer.
        while (ch.requests.matched() == 0) {
            if (cancelled()) throw CallException("Canceled")
            if (System.currentTimeMillis() >= deadline) throw CallException("No server for $serviceName")
            Thread.sleep(50)
        }
        if (!waitForReplyPublisher(ch.replies, deadline, cancelled)) throw CallException("The $serviceName server does not publish responses")
        if (!ch.warmedUp) {
            // The channel endpoints are new and the server has to discover our response reader before
            // answering (otherwise the VOLATILE response is lost). Cyclone over Wi-Fi takes longer than Fast DDS. If the
            // channel was prepared earlier ([prepare]), that time has already passed.
            val wait = ch.createdAt + CYCLONE_WARMUP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            ch.warmedUp = true
        }
        val seq = ch.nextSeq.incrementAndGet()
        val queue = ArrayBlockingQueue<MsgValue>(1)
        ch.waiters[seq] = queue
        try {
            val msg = MsgValue(request).apply {
                put(MsgSpec.CYCLONE_GUID, ch.guid); put(MsgSpec.CYCLONE_SEQ, seq)
                if (srv.request.fields.isEmpty()) put(MsgSpec.EMPTY_STRUCT_FIELD, 0L)
            }
            if (!ch.requests.publish(msg)) throw CallException("No ROS 2 connection")
            while (true) {
                if (cancelled()) throw CallException("Canceled")
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw CallException("$serviceName did not respond within ${timeoutMs / 1000.0} s")
                queue.poll(minOf(left, 100L), TimeUnit.MILLISECONDS)?.let { return it }
            }
        } finally {
            ch.waiters.remove(seq)
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Waits for DDS to match the request with a server and the response with it. If the response
     * goes out before the server knows our reader, it is lost: the first time a small
     * grace period is given after matching.
     */
    private fun waitForServer(deadline: Long, cancelled: () -> Boolean) {
        val requests = requests!!; val replies = replies!!
        if (warmedUp && requests.matched() > 0) return
        val connectDeadline = minOf(deadline, System.currentTimeMillis() + SERVER_WAIT_MS)
        while (requests.matched() == 0) {
            if (cancelled()) throw CallException("Canceled")
            if (System.currentTimeMillis() >= connectDeadline) {
                throw CallException(if (!requests.isReady) "No ROS 2 connection" else "No server for $serviceName")
            }
            Thread.sleep(50)
        }
        // The response reader may match somewhat later than the request writer (especially
        // right after connecting, or with a lot of discovery at once): it gets whatever is left
        // of the call's timeout (previously only 3 s, which was sometimes not enough).
        if (!waitForReplyPublisher(replies, deadline, cancelled)) throw CallException("The $serviceName server does not publish responses (different RMW?)")
        if (!warmedUp) {
            Thread.sleep(WARMUP_MS)
            warmedUp = true
        }
    }

    /**
     * Waits until `deadline` for the response reader to match the server. Polls in short steps:
     * right after creating it, the jros2 subscription does not exist yet (RosConnection creates it on its thread) and
     * waitForPublisher returns false immediately instead of waiting.
     */
    private fun waitForReplyPublisher(replies: DynRos.Subscription, deadline: Long, cancelled: () -> Boolean): Boolean {
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return false
            if (replies.waitForPublisher(minOf(left, 200L))) return true
            if (cancelled()) throw CallException("Canceled")
            Thread.sleep(20) // if the subscription does not exist yet, waitForPublisher returns immediately
        }
    }

    fun hasServer(): Boolean = when {
        viaBridge -> RosConnection.bridgeHasService(serviceName)
        viaZenoh -> RosConnection.zenohHasService(serviceName)
        else -> requests!!.matched() > 0
    }

    fun close() {
        closed = true
        requests?.close()
        replies?.close()
        synchronized(lock) { cyclone?.close(); cyclone = null }
    }

    companion object {
        private const val SERVER_WAIT_MS = 3000L
        private const val WARMUP_MS = 200L
        private const val EARLY_MAX = 64
        /** The identity is writer GUID (16 bytes) + sequence number (8). */
        private const val GUID_SIZE = 16
        /** The first two GUID bytes are the DDS vendor: 0x0110 is Eclipse Cyclone DDS (0x010f, Fast DDS). */
        private const val CYCLONE_VENDOR_HEX = "0110"
        /** Grace period the first time the Cyclone channel is used (see [callCyclone]). */
        private const val CYCLONE_WARMUP_MS = 1000L
        /** Maximum wait for the graph to show the server to learn its RMW. */
        private const val GRAPH_WAIT_MS = 1500L
    }
}

/**
 * Client of a ROS 2 action built from the rcl_action services and topics:
 * <name>/_action/send_goal, get_result, cancel_goal and the topic <name>/_action/feedback.
 */
class DynActionClient(name: String, val action: ActionSpec, cancelSrv: SrvSpec) {

    val actionName = DynRos.normalizeName(name)
    private val sendGoal = DynServiceClient("$actionName/_action/send_goal", action.sendGoal)
    private val getResult = DynServiceClient("$actionName/_action/get_result", action.getResult)
    private val cancel = DynServiceClient("$actionName/_action/cancel_goal", cancelSrv)
    /** Goals in progress (uuid) -> who receives their feedback: several can run at once. */
    private val feedbackListeners = java.util.concurrent.ConcurrentHashMap<List<Long>, (MsgValue) -> Unit>()
    private val feedback = DynRos.subscribe("$actionName/_action/feedback", action.feedbackMessage, RosConnection.defaultQos()) { v ->
        @Suppress("UNCHECKED_CAST")
        val id = (v["goal_id"] as? Map<String, Any?>)?.get("uuid") as? List<*>
        val listener = id?.let { feedbackListeners[it.map { x -> (x as Number).toLong() }] }
        @Suppress("UNCHECKED_CAST")
        if (listener != null) (v["feedback"] as? MsgValue)?.let { fb -> listener(fb) }
    }

    class Outcome(val status: Int, val result: MsgValue) {
        val statusLabel: String get() = STATUS.getOrElse(status) { "status $status" }
    }

    /**
     * Sends the goal and waits for the result (blocking). Meanwhile it calls `onAccepted` and
     * `onFeedback` (on the DDS thread). If `cancelRequested` becomes true, cancellation is requested and it
     * keeps waiting for the result (it will arrive as CANCELED). If `abandoned` becomes true it stops
     * waiting; the goal is only cancelled if `cancelIfAbandoned` is also set (otherwise the robot carries on with it).
     */
    fun run(
        goal: MsgValue, acceptTimeoutMs: Long, onAccepted: () -> Unit, onFeedback: (MsgValue) -> Unit,
        cancelRequested: () -> Boolean, abandoned: () -> Boolean = { false },
        cancelIfAbandoned: () -> Boolean = { false }
    ): Outcome {
        val uuid = randomUuid()
        val goalId = MsgValue().apply { put("uuid", uuid) }
        feedbackListeners[uuid] = onFeedback
        try {
            val response = sendGoal.call(MsgValue().apply { put("goal_id", goalId); put("goal", goal) }, acceptTimeoutMs) { abandoned() || cancelRequested() }
            if (response["accepted"] != true) throw CallException("The server rejected the goal")
            // With a Cyclone server, the cancel and result channels are created now (see DynServiceClient.prepare).
            cancel.prepare(); getResult.prepare()
            onAccepted()
            var cancelSent = false
            // get_result does not respond until the goal finishes: wait without limit, cancellable.
            while (true) {
                try {
                    val r = getResult.call(MsgValue().apply { put("goal_id", DynCodec.deepCopy(goalId)) }, RESULT_POLL_MS) {
                        abandoned() || (!cancelSent && cancelRequested())
                    }
                    @Suppress("UNCHECKED_CAST")
                    return Outcome((r["status"] as? Long)?.toInt() ?: 0, (r["result"] as? MsgValue) ?: MsgValue())
                } catch (e: CallException) {
                    if (abandoned()) throw CallException("Stopped waiting for the result")
                    if (!cancelSent && cancelRequested()) {
                        cancelSent = true
                        requestCancel(goalId)
                    } else if (e.message?.startsWith("No server for") == true || e.message == "No ROS 2 connection") {
                        throw e
                    }
                    // Otherwise it was the long wait's timeout: request the result again.
                }
            }
        } catch (e: CallException) {
            // Cancel even if send_goal had not answered yet: if it got accepted, this stops it.
            if (abandoned() && cancelIfAbandoned()) requestCancel(goalId)
            throw e
        } finally {
            feedbackListeners.remove(uuid)
        }
    }

    private fun requestCancel(goalId: MsgValue) {
        val req = MsgValue().apply {
            put("goal_info", MsgValue().apply { put("goal_id", DynCodec.deepCopy(goalId)); put("stamp", DynCodec.timeValue(0)) })
        }
        try {
            cancel.call(req, CANCEL_TIMEOUT_MS)
        } catch (_: CallException) {
            // The result (or its absence) tells how it ended.
        }
    }

    fun hasServer() = sendGoal.hasServer()

    fun close() {
        sendGoal.close(); getResult.close(); cancel.close(); feedback.close()
    }

    companion object {
        private const val RESULT_POLL_MS = 60_000L
        private const val CANCEL_TIMEOUT_MS = 5_000L
        /** action_msgs/GoalStatus. */
        val STATUS = listOf("UNKNOWN", "ACCEPTED", "EXECUTING", "CANCELING", "SUCCEEDED", "CANCELED", "ABORTED")
        const val STATUS_SUCCEEDED = 4

        fun randomUuid(): List<Long> {
            val u = UUID.randomUUID()
            val out = ArrayList<Long>(16)
            for (i in 7 downTo 0) out.add((u.mostSignificantBits ushr (i * 8)) and 0xFF)
            for (i in 7 downTo 0) out.add((u.leastSignificantBits ushr (i * 8)) and 0xFF)
            return out
        }
    }
}

/**
 * Server of a ROS 2 service (rmw_fastrtps): reads "rq<name>Request" and answers on
 * "rr<name>Reply". `handler` receives the request and a function to respond with, which it may call
 * later (e.g. get_result of an action, which does not respond until the goal finishes).
 *
 * As rmw_fastrtps: each reply carries its request's identity as related_sample_identity, so each client (of this app
 * or any ROS 2 one) takes only its own replies, even when they come out of order; and if the request names the
 * client's reply reader (its related identity, as rmw_fastrtps and this app's client send it), the reply waits until
 * that reader is matched, so it is not lost while discovery finishes.
 */
class DynServiceServer(name: String, val srv: SrvSpec, handler: (request: MsgValue, reply: (MsgValue) -> Unit) -> Unit) {

    val serviceName = DynRos.normalizeName(name)
    private val replies = DynRos.rawPublisher("rr${serviceName}Reply", srv.response, RosConnection.defaultQos())
    private val requests = DynRos.subscribeRawWithIdentities("rq${serviceName}Request", srv.request, RosConnection.defaultQos()) { req, identity, related ->
        handler(req) { response -> reply(response, identity, related) }
    }

    private fun reply(response: MsgValue, request: ByteArray, clientReader: ByteArray) {
        val reader = clientReader.copyOf(DynRos.GUID_SIZE)
        if (reader.any { it != 0.toByte() }) {
            val until = System.currentTimeMillis() + MATCH_WAIT_MS
            while (!replies.isMatchedWith(reader) && System.currentTimeMillis() < until) Thread.sleep(10)
        }
        replies.publishWithIdentity(response, ByteArray(DynRos.IDENTITY_SIZE), related = request)
    }

    private companion object {
        /** At most this long for the client's reply reader to match (rmw_fastrtps waits too; then it sends anyway). */
        const val MATCH_WAIT_MS = 1000L
    }

    fun close() {
        requests.close()
        replies.close()
    }
}
