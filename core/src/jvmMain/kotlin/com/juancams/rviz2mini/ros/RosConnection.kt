// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros

import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.Ros2Bridge
import com.juancams.rviz2mini.TfTree
import us.ihmc.jros2.ROS2Message
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2QoSProfile
import us.ihmc.jros2.ROS2Subscription
import us.ihmc.jros2.ROS2SubscriptionCallback
import us.ihmc.jros2.ROS2Topic
import us.ihmc.jros2.RawTopics
import us.ihmc.jros2.GraphAccess
import us.ihmc.fastddsjava.natives.fastddsjavaGraph
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single ROS 2 connection for the whole app. All tabs share the same DDS node:
 * this way the configuration (domain id...) applies to all of them and there is no participant per tab.
 *
 * Opening/closing the node is serialized on its own thread (creating a DDS participant can take time
 * and must not block the UI). Each (re)connection increments `generation`; callbacks from previous
 * nodes are discarded by comparing it.
 */
object RosConnection {

    private const val TAG = "VizMini2"
    private const val RETRY_MS = 3000L
    /** How long to wait for the bridge to advertise a service before giving the error. */
    private const val SERVICE_WAIT_MS = 3000L
    private const val LEARN_MS = 15_000L
    private const val INTERFACE_WHITELIST_PROPERTY = "fastdds.interface.whitelist"
    /** Internal key of topics with a literal DDS name (deliberately not a valid ROS name). */
    private const val RAW_PREFIX = "/@dds/"
    /** The longest [stop] waits for [holdOpen]: the time a cancellation takes to respond. */
    private const val HOLD_MAX_MS = 6000L
    /** DDS type of /tf and /tf_static (for its Zenoh key expression). */
    private const val TF_DDS_TYPE = "tf2_msgs::msg::dds_::TFMessage_"

    /** Strong reference: java.util.logging only keeps weak references to its Loggers. */
    private val jros2Logger: java.util.logging.Logger = java.util.logging.Logger.getLogger("jros2")

    init {
        // jros2 logs its errors (e.g. exceptions while deserializing in a callback) with a StreamHandler over
        // System.out that does not flush after each record: on Android they never reached logcat. They are sent to logcat.
        // Same Logger as us.ihmc.jros2.jros2 (non-public class): java.util.logging shares it by name.
        jros2Logger.addHandler(object : java.util.logging.Handler() {
            override fun publish(r: java.util.logging.LogRecord) {
                val level = r.level.intValue()
                val priority = when {
                    level >= java.util.logging.Level.SEVERE.intValue() -> Log.ERROR
                    level >= java.util.logging.Level.WARNING.intValue() -> Log.WARN
                    level >= java.util.logging.Level.INFO.intValue() -> Log.INFO
                    else -> Log.DEBUG
                }
                val text = r.message ?: ""
                Log.println(priority, "jros2", if (r.thrown != null) text + "\n" + Log.getStackTraceString(r.thrown) else text)
            }
            override fun flush() {}
            override fun close() {}
        })
    }

    sealed class Status {
        data object Disconnected : Status()
        data object Connecting : Status()
        /** `bridge`: URL of the foxglove_bridge if the connection is over WebSocket (null = DDS). */
        data class Connected(val domainId: Int, val nodeName: String, val bridge: String? = null, val zenoh: String? = null) : Status()
        data class Error(val message: String) : Status()
    }

    /** TF tree events, always on the main thread. */
    interface TfListener {
        fun onFramesAdded()
        fun onTfReset()
    }

    /** Subscription that recreates itself after each reconnection. Cancel with [cancel]. */
    class ManagedSubscription<T : ROS2Message<T>> constructor(
        val topicName: String,
        val type: Class<T>,
        val qos: ROS2QoSProfile,
        val callback: ROS2SubscriptionCallback<T>,
        val ddsName: String? = null
    ) {
        @Volatile var subscription: ROS2Subscription<T>? = null
        /** Subscription on the foxglove_bridge (WebSocket connection). */
        @Volatile var bridgeSubId: Int? = null
        /** Subscription over Zenoh (robots with rmw_zenoh_cpp). */
        @Volatile var zenohSubId: Int? = null
        @Volatile var cancelled = false
        /** Reason it could not be created (e.g. topic already used with another type), or null. */
        @Volatile var error: String? = null
            set
        fun cancel() = unsubscribe(this)

        /** The GUID (16 bytes) of this subscription's DDS reader; false if there is none (or no natives for it). */
        fun readerGuid(out: ByteArray): Boolean {
            val sub = subscription ?: return false
            return try { GraphAccess.readerGuid(sub, out) } catch (e: Throwable) { false }
        }

        /** Waits (blocking) until there is a matched publisher. False if there is no connection. */
        fun waitForPublisher(timeoutMs: Long): Boolean = try {
            subscription?.waitForPublisher(timeoutMs) ?: false
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Publisher that recreates itself after each reconnection. `publish` must always be called from the
     * same thread (jros2's ROS2Publisher reuses a write buffer).
     */
    class ManagedPublisher<T : ROS2Message<T>> constructor(
        val topicName: String,
        val type: Class<T>,
        val qos: ROS2QoSProfile,
        val ddsName: String? = null
    ) {
        @Volatile var publisher: ROS2Publisher<T>? = null
        /** Own channel on the foxglove_bridge (WebSocket connection). */
        @Volatile var bridgeChannel: Int? = null
        /** Publisher over Zenoh (robots with rmw_zenoh_cpp). */
        @Volatile var zenohPub: ZenohLink.Pub? = null
        @Volatile var closed = false

        /**
         * Returns false if there is no connection yet. One publication at a time: jros2 serializes under a lock but does the write outside it, over a shared native
         * buffer, so two threads at once could send two copies of the same message (seen with concurrent
         * calls to a service). It also makes publishing and reading the identity atomic in [publishWithIdentity].
         * The lock is the jros2 publisher itself, in case several share it.
         */
        fun publish(msg: T): Boolean {
            bridgeChannel?.let { ch -> return bridge?.publish(ch, cdrOf(msg)) ?: false }
            zenohPub?.let { z -> return try { z.put(cdrOf(msg)) } catch (e: Throwable) { Log.w(TAG, "Error publicando en $topicName por Zenoh", e); false } }
            val p = publisher ?: return false
            return try {
                synchronized(p) { p.publish(msg) }
                true
            } catch (e: Throwable) {
                Log.w(TAG, "Error publicando en $topicName: ${e.message}")
                false
            }
        }

        /**
         * Like [publish] and additionally copies into `identityOut` (24 bytes) the DDS identity of the written
         * sample: a service client needs it to recognize its response. `related`: the sample's
         * related_sample_identity (24 bytes): a service reply carries its request's identity; a request, the GUID of
         * the client's reply reader (as rmw_fastrtps does). False if it could not be written.
         */
        @Synchronized
        fun publishWithIdentity(msg: T, identityOut: ByteArray, related: ByteArray? = null): Boolean {
            val p = publisher ?: return false
            return try {
                synchronized(p) {
                    if (related != null) GraphAccess.setNextRelatedSampleIdentity(p, related)
                    try {
                        p.publish(msg)
                    } finally {
                        // If the write threw, the next sample must not take it.
                        if (related != null) GraphAccess.setNextRelatedSampleIdentity(p, null)
                    }
                    GraphAccess.lastSampleIdentity(p, identityOut)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error publicando en $topicName: ${e.message}")
                false
            }
        }

        /** Whether this publisher is matched with the reader with that GUID (16 bytes); true if it cannot be known. */
        fun isMatchedWith(readerGuid: ByteArray): Boolean {
            val p = publisher ?: return false
            return try { GraphAccess.isMatchedWith(p, readerGuid) } catch (e: Throwable) { true }
        }

        /** Number of subscribers matched right now (0 if there is no connection). */
        fun matchedSubscribers(): Int = try {
            // Through the bridge it is unknown who listens: assume someone does (the bridge itself).
            if (bridgeChannel != null) 1 else zenohPub?.subscribers() ?: publisher?.publicationMatchedStatus ?: 0
        } catch (e: Throwable) {
            0
        }

        /** True if there is a DDS publisher (connected and created), or over Zenoh. */
        val isReady: Boolean get() = publisher != null || zenohPub != null

        fun close() = destroyPublisher(this)
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ros2-connection").apply { isDaemon = true } }
    private val mainHandler get() = Platform.main

    @Volatile private var node: ROS2Node? = null
    private var multicastRelease: (() -> Unit)? = null // executor only
    private val generation = AtomicInteger(0)
    /** Started with [start] (and not stopped). */
    @Volatile private var started = false

    @Volatile var config: RosConfig = RosConfig()
        private set

    @Volatile var status: Status = Status.Disconnected
        private set

    val tfTree = TfTree()

    /**
     * jros2 1.5 loads a Fast DDS XML profile for each node and that profile manager is not
     * thread-safe: creating (or closing) two nodes at once from different threads fails with
     * "Failed to load participant profile". Everything goes through here (also the test nodes).
     */
    private val nodeLock = Any()

    fun createNode(name: String, domainId: Int): ROS2Node = synchronized(nodeLock) { ROS2Node(name, domainId) }

    fun closeNode(n: ROS2Node) = synchronized(nodeLock) { n.close() }
    private val tfListeners = CopyOnWriteArrayList<TfListener>()
    private val statusListeners = CopyOnWriteArrayList<(Status) -> Unit>()
    private val subscriptions = CopyOnWriteArrayList<ManagedSubscription<*>>()
    private val publishers = CopyOnWriteArrayList<ManagedPublisher<*>>()
    private val tfSyncPending = AtomicBoolean(false)
    private val topicTypes = TopicTypeRegistry() // executor only
    /**
     * One ROS2Topic instance per name and node (executor only). jros2 indexes its topics
     * by ROS2Topic object identity (it does not implement equals): creating another `ROS2Topic("/x")` makes it
     * try to create the DDS topic again, Fast DDS responds "Topic already exists" and jros2 crashes
     * with SIGSEGV. It happened when returning to a camera tab or saving the teleop settings.
     */
    private val topics = HashMap<String, ROS2Topic<*>>()

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (started) return
        started = true
        config = RosConfig.load()
        connect(delayMs = 0)
        RosNodes.start()
    }

    /** Applies a new configuration (saves it) and reconnects. */
    fun applyConfig(newConfig: RosConfig) {
        RosConfig.save(newConfig)
        config = newConfig
        if (!started) start() else connect(delayMs = 300)
    }

    /** Closes and reopens the node (equivalent to the old "Reset DDS"). */
    fun restart() = connect(delayMs = 500)

    fun stop() {
        generation.incrementAndGet()
        started = false
        executor.execute {
            awaitHolds()
            closeNode()
        }
        setStatus(Status.Disconnected)
    }

    // What must go out before closing the node when leaving the app (cancelling an action in progress).
    private val holds = AtomicInteger()
    private val holdsLock = Object()

    /**
     * Delays closing the node in [stop] until the returned function is called (at most
     * [HOLD_MAX_MS]). Calling it more than once does nothing.
     */
    fun holdOpen(): () -> Unit {
        holds.incrementAndGet()
        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true) && holds.decrementAndGet() == 0) synchronized(holdsLock) { holdsLock.notifyAll() }
        }
    }

    private fun awaitHolds() {
        val deadline = System.currentTimeMillis() + HOLD_MAX_MS
        synchronized(holdsLock) {
            while (holds.get() > 0) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try { holdsLock.wait(left) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun connect(delayMs: Long) {
        val gen = generation.incrementAndGet()
        tfTree.clear()
        RosNodes.clear()
        mainHandler.post { for (l in tfListeners) l.onTfReset() }
        setStatus(Status.Connecting)
        executor.execute {
            closeNode()
            if (delayMs > 0) {
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@execute }
            }
            openNode(gen)
        }
    }

    /** Runs on executor. */
    private fun openNode(gen: Int) {
        if (gen != generation.get()) return
        if (!started) return
        val cfg = config
        if (cfg.usesBridge) { openBridge(gen, cfg); return }
        if (cfg.usesZenoh) { openZenoh(gen, cfg); return }
        try {
            multicastRelease = Platform.acquireMulticast()

            // jros2 reads the whitelist from this property when creating the participant.
            val whitelist = cfg.whitelistEntries()
            if (whitelist.isEmpty()) System.clearProperty(INTERFACE_WHITELIST_PROPERTY)
            else System.setProperty(INTERFACE_WHITELIST_PROPERTY, whitelist.joinToString(","))

            // Unicast discovery peers (natives from the fork); without them, multicast only. The native libraries
            // must be loaded beforehand (on the PC jros2 loads them when creating the first node, which is later).
            us.ihmc.fastddsjava.library.fastddsjavaNativeLibrary.load()
            try {
                // Peers typed by the user + robots already seen in this domain (in case the
                // Wi-Fi multicast stops working).
                val learned = if (KnownRobots.enabled() && cfg.domainId != RosConfig.DEMO_DOMAIN_ID)
                    KnownRobots.peers(cfg.domainId) else emptyList()
                activePeers = (cfg.peerEntries() + learned).distinct()
                fastddsjavaGraph.setInitialPeers(activePeers.toTypedArray())
                fastddsjavaGraph.setDiscoveryServers(cfg.serverEntries().toTypedArray())
            } catch (e: Throwable) {
                Log.w(TAG, "Las librerías nativas no admiten peers de descubrimiento", e)
            }

            val n = createNode(cfg.nodeName, cfg.domainId)
            node = n

            val tfCallback = tfCallback(gen)
            Ros2Bridge.setupTFSubscription(n, "/tf", false, tfCallback)
            Ros2Bridge.setupTFSubscription(n, "/tf_static", true, tfCallback)
            topicTypes.register("/tf", ddsType(tf2_msgs.TFMessage::class.java))
            topicTypes.register("/tf_static", ddsType(tf2_msgs.TFMessage::class.java))

            for (s in subscriptions) attach(n, s)
            for (p in publishers) attach(n, p)

            lastConnectedAt = System.currentTimeMillis()
            scheduleLearn(gen)
            if (gen == generation.get()) setStatus(Status.Connected(cfg.domainId, cfg.nodeName))
        } catch (e: Throwable) {
            Log.e(TAG, "Error iniciando ROS 2", e)
            if (gen == generation.get()) {
                setStatus(Status.Error((e.message ?: e.javaClass.simpleName) + " (retrying…)"))
                // Automatic retry as long as nobody has requested another connection.
                mainHandler.postDelayed({ if (gen == generation.get() && started) connect(delayMs = 0) }, RETRY_MS)
            }
        }
    }

    private fun tfCallback(gen: Int) = Ros2Bridge.TransformCallback { parentId, childId, tx, ty, tz, qx, qy, qz, qw, isStatic, stampNs ->
        if (gen != generation.get()) return@TransformCallback
        val jumps = tfTree.timeJumps
        val created = tfTree.update(parentId, childId, tx, ty, tz, qx, qy, qz, qw, isStatic, stampNs)
        if (tfTree.timeJumps != jumps) Log.i(TAG, "El reloj de las TF ha ido hacia atrás ($childId): se vacía su historial")
        if (created && tfSyncPending.compareAndSet(false, true)) {
            mainHandler.post {
                tfSyncPending.set(false)
                if (gen == generation.get()) for (l in tfListeners) l.onFramesAdded()
            }
        }
    }

    // ---------------------------------------------------------------- foxglove_bridge

    @Volatile private var bridge: FoxgloveBridge? = null

    /** WebSocket connection to a foxglove_bridge instead of DDS. Runs on executor. */
    private fun openBridge(gen: Int, cfg: RosConfig) {
        val url = cfg.bridgeUrl.trim()
        val b = FoxgloveBridge(url, object : FoxgloveBridge.Listener {
            override fun onConnected(serverName: String, capabilities: Set<String>) {
                executor.execute {
                    if (gen != generation.get()) return@execute
                    for (s in subscriptions) attachBridge(s)
                    for (p in publishers) attachBridge(p)
                    lastConnectedAt = System.currentTimeMillis()
                    setStatus(Status.Connected(cfg.domainId, cfg.nodeName, url))
                }
            }
            override fun onChannelsChanged() {}
            override fun onClosed(reason: String?) {
                if (gen != generation.get()) return
                setStatus(Status.Error("foxglove_bridge: $reason (retrying…)"))
                mainHandler.postDelayed({ if (gen == generation.get() && started) connect(delayMs = 0) }, RETRY_MS)
            }
        })
        bridge = b
        // TF: same as over DDS, into the same tree.
        val tf = tfCallback(gen)
        for ((name, isStatic) in listOf("/tf" to false, "/tf_static" to true)) b.subscribe(name) { data, _ -> deliverTf(tf, data, isStatic) }
        b.open()
    }

    // ---------------------------------------------------------------- Zenoh (rmw_zenoh_cpp)

    @Volatile private var zenoh: ZenohLink? = null

    /** Zenoh connection to the router of a robot with rmw_zenoh_cpp, instead of DDS. Runs on executor. */
    private fun openZenoh(gen: Int, cfg: RosConfig) {
        val endpoint = cfg.zenohRouter.trim()
        val z = ZenohLink(endpoint, cfg.domainId, cfg.nodeName, object : ZenohLink.Listener {
            override fun onConnected() {
                executor.execute {
                    if (gen != generation.get()) return@execute
                    for (s in subscriptions) attachZenoh(s)
                    for (p in publishers) attachZenoh(p)
                    lastConnectedAt = System.currentTimeMillis()
                    setStatus(Status.Connected(cfg.domainId, cfg.nodeName, zenoh = endpoint))
                }
            }
            override fun onError(message: String) {
                if (gen != generation.get()) return
                setStatus(Status.Error("Zenoh ($endpoint): $message (retrying…)"))
                mainHandler.postDelayed({ if (gen == generation.get() && started) connect(delayMs = 0) }, RETRY_MS)
            }
        })
        zenoh = z
        z.open()
        if (!z.connected) return
        // TF: same as over DDS, into the same tree.
        val tf = tfCallback(gen)
        // /tf_static is TRANSIENT_LOCAL: the advanced subscriber asks each publisher for what it already published (like
        // tf2_ros, up to 100 messages per publisher).
        z.subscribe("/tf", TF_DDS_TYPE) { data -> deliverTf(tf, data, false) }
        z.subscribe("/tf_static", TF_DDS_TYPE, transientLocal = true, depth = 100) { data -> deliverTf(tf, data, true) }
    }

    /** Runs on executor. */
    private fun attachZenoh(s: ManagedSubscription<*>) {
        val z = zenoh ?: return
        if (s.cancelled || s.zenohSubId != null || !z.connected) return
        // Services and actions go through Zenoh queries, not through these internal topics.
        if (s.ddsName != null) { s.error = "Services and actions are not available through Zenoh yet"; return }
        s.error = null
        val q = s.qos
        s.zenohSubId = z.subscribe(s.topicName, ddsType(s.type), q.durability == ROS2QoSProfile.Durability.TRANSIENT_LOCAL,
            q.reliability != ROS2QoSProfile.Reliability.BEST_EFFORT, q.depth) { data -> s.deliver(data) }
    }

    /** Runs on executor. */
    private fun attachZenoh(p: ManagedPublisher<*>) {
        val z = zenoh ?: return
        if (p.closed || p.zenohPub != null || p.ddsName != null || !z.connected) return
        val q = p.qos
        p.zenohPub = z.advertise(p.topicName, ddsType(p.type), q.reliability == ROS2QoSProfile.Reliability.BEST_EFFORT,
            q.durability == ROS2QoSProfile.Durability.TRANSIENT_LOCAL, q.depth)
    }

    /** Service call over Zenoh (see [ZenohLink.call]); like [callServiceOverBridge]. */
    fun callServiceOverZenoh(name: String, srv: com.juancams.rviz2mini.ros.dyn.SrvSpec, request: com.juancams.rviz2mini.ros.dyn.MsgValue,
                             timeoutMs: Long, cancelled: () -> Boolean): com.juancams.rviz2mini.ros.dyn.MsgValue {
        val deadline = System.currentTimeMillis() + timeoutMs
        // The server may take a moment to appear in the tokens when connecting.
        val waitUntil = minOf(deadline, System.currentTimeMillis() + SERVICE_WAIT_MS)
        var z = zenoh
        while (z == null || !z.connected || !z.hasService(name)) {
            if (cancelled()) throw com.juancams.rviz2mini.ros.dyn.CallException("Canceled")
            if (System.currentTimeMillis() >= waitUntil)
                throw com.juancams.rviz2mini.ros.dyn.CallException(if (z == null || !z.connected) "No Zenoh connection" else "No server for $name")
            Thread.sleep(50)
            z = zenoh
        }
        val result = java.util.concurrent.ArrayBlockingQueue<Pair<ByteArray?, String?>>(1)
        val cdr = com.juancams.rviz2mini.ros.dyn.CdrBytes.serialize(srv.request, request)
        if (!z.call(name, cdr, timeoutMs) { data, error -> result.offer(data to error) }) {
            throw com.juancams.rviz2mini.ros.dyn.CallException("Could not call $name through Zenoh")
        }
        while (true) {
            if (cancelled()) throw com.juancams.rviz2mini.ros.dyn.CallException("Canceled")
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw com.juancams.rviz2mini.ros.dyn.CallException("$name did not respond within ${timeoutMs / 1000.0} s")
            val (data, error) = result.poll(minOf(left, 100L), java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (error != null) throw com.juancams.rviz2mini.ros.dyn.CallException(error)
            return com.juancams.rviz2mini.ros.dyn.CdrBytes.deserialize(srv.response, data!!)
        }
    }

    /** Whether any node advertises the service over Zenoh. */
    fun zenohHasService(name: String): Boolean = zenoh?.hasService(name) == true

    /** Connected over Zenoh (rmw_zenoh_cpp). */
    val usesZenoh: Boolean get() = zenoh != null

    /** A CDR TFMessage (bridge or bag) into the TF tree. */
    private fun deliverTf(tf: Ros2Bridge.TransformCallback, data: ByteArray, isStatic: Boolean) {
        try {
            val m = BridgeReader(data, tf2_msgs.TFMessage::class.java).read() ?: return
            for (i in 0 until m.transforms.size()) {
                val t = m.transforms.get(i)
                val tr = t.transform.translation; val r = t.transform.rotation
                tf.onTransformReceived(t.header.frameIdAsString, t.childFrameIdAsString, tr.x, tr.y, tr.z, r.x, r.y, r.z, r.w, isStatic,
                    t.header.stamp.sec * 1_000_000_000L + t.header.stamp.nanosec)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "TF ilegible", e)
        }
    }

    // ---------------------------------------------------------------- direct bag

    /**
     * Bag playback straight into the app (without DDS): while active, [deliverFromBag]
     * delivers each message to the subscriptions of its topic, just as if it arrived from the network. All
     * tabs see it at once and follow the same timeline.
     */
    @Volatile var bagPlaybackActive = false; private set
    private val bagMessageTime = ThreadLocal<Long?>()

    fun setBagPlayback(active: Boolean) { bagPlaybackActive = active }

    /**
     * Bag timestamp of the message being delivered on this thread (so plots use the
     * bag time and not the phone's); null outside a direct playback.
     */
    fun bagMessageTimeNs(): Long? = bagMessageTime.get()

    /** From the player thread: `data` is the bag's CDR (with its encapsulation header). */
    fun deliverFromBag(topic: String, data: ByteArray, logTimeNs: Long) {
        if (!bagPlaybackActive) return
        bagMessageTime.set(logTimeNs)
        try {
            when (topic) {
                "/tf" -> deliverTf(tfCallback(generation.get()), data, false)
                "/tf_static" -> deliverTf(tfCallback(generation.get()), data, true)
            }
            for (s in subscriptions) {
                if (s.cancelled || s.ddsName != null || s.topicName != topic) continue
                try { s.deliver(data) } catch (t: Throwable) { Log.w(TAG, "Mensaje del bag ilegible en $topic", t) }
            }
        } finally {
            bagMessageTime.set(null)
        }
    }

    /** Connected via foxglove_bridge (services, actions and parameters go through it). */
    val usesBridge: Boolean get() = bridge != null

    /**
     * Calls a service through the foxglove_bridge: request and response go in CDR. Blocks until
     * the response, `timeoutMs` or `cancelled`; failures arrive as [CallException].
     */
    fun callServiceOverBridge(name: String, srv: com.juancams.rviz2mini.ros.dyn.SrvSpec, request: com.juancams.rviz2mini.ros.dyn.MsgValue,
                              timeoutMs: Long, cancelled: () -> Boolean): com.juancams.rviz2mini.ros.dyn.MsgValue {
        val deadline = System.currentTimeMillis() + timeoutMs
        // The service may take a moment to be advertised when connecting.
        val waitUntil = minOf(deadline, System.currentTimeMillis() + SERVICE_WAIT_MS)
        var b = bridge
        while (b == null || !b.connected || b.services[name] == null) {
            if (cancelled()) throw com.juancams.rviz2mini.ros.dyn.CallException("Canceled")
            if (System.currentTimeMillis() >= waitUntil)
                throw com.juancams.rviz2mini.ros.dyn.CallException(if (b == null || !b.connected) "No connection with foxglove_bridge" else "No server for $name (through foxglove_bridge)")
            Thread.sleep(50)
            b = bridge
        }
        if (!b.canCallServices) throw com.juancams.rviz2mini.ros.dyn.CallException("This foxglove_bridge does not allow service calls")
        val result = java.util.concurrent.ArrayBlockingQueue<Pair<ByteArray?, String?>>(1)
        val cdr = com.juancams.rviz2mini.ros.dyn.CdrBytes.serialize(srv.request, request)
        val callId = b.callService(name, cdr) { data, error -> result.offer(data to error) }
            ?: throw com.juancams.rviz2mini.ros.dyn.CallException("Could not call $name through foxglove_bridge")
        try {
            while (true) {
                if (cancelled()) throw com.juancams.rviz2mini.ros.dyn.CallException("Canceled")
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw com.juancams.rviz2mini.ros.dyn.CallException("$name did not respond within ${timeoutMs / 1000.0} s")
                val (data, error) = result.poll(minOf(left, 100L), java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (error != null) throw com.juancams.rviz2mini.ros.dyn.CallException(error)
                return com.juancams.rviz2mini.ros.dyn.CdrBytes.deserialize(srv.response, data!!)
            }
        } finally {
            b.forgetCall(callId)
        }
    }

    /**
     * Graph with nodes from the bridge's `connectionGraph`: each node is like a DDS participant
     * with its readers and writers, so the node list and the node graph work the same.
     */
    private fun bridgeGraph(b: FoxgloveBridge, topics: List<RosGraph.Endpoint>, services: List<RosGraph.Endpoint>): RosGraph {
        val types = topics.associate { it.ddsTopic to it.ddsType }
        val serviceTypes = services.associate { it.ddsTopic to it.ddsType }
        val nodes = (b.graphTopics.values.flatMap { it.publishers + it.subscribers } + b.graphServices.values.flatten()).filter { it.isNotBlank() }.distinct().sorted()
        val endpoints = ArrayList<RosGraph.Endpoint>()
        val info = HashMap<String, List<RosNodes.Entry>>()
        for ((i, node) in nodes.withIndex()) {
            val prefix = String.format(java.util.Locale.ROOT, "%024x", i + 1)
            var k = 0
            val writers = ArrayList<String>(); val readers = ArrayList<String>()
            fun add(isWriter: Boolean, dds: String, type: String) {
                val guid = prefix + String.format(java.util.Locale.ROOT, "%08x", ++k)
                endpoints += RosGraph.Endpoint(isWriter, dds, type, guid)
                if (isWriter) writers += guid else readers += guid
            }
            for ((t, e) in b.graphTopics) {
                val dds = "rt$t"
                val type = types[dds] ?: continue
                if (node in e.publishers) add(true, dds, type)
                if (node in e.subscribers) add(false, dds, type)
            }
            for ((s, providers) in b.graphServices) if (node in providers) {
                val dds = "rq${s}Request"
                serviceTypes[dds]?.let { add(false, dds, it) }
            }
            val full = if (node.startsWith("/")) node else "/$node"
            info[prefix + "00000000"] = listOf(RosNodes.Entry(full.substringBeforeLast('/').ifEmpty { "/" }, full.substringAfterLast('/'), readers, writers))
        }
        // Whatever the graph does not relate to any node (e.g. the bridge's own topics) is still shown.
        val known = endpoints.mapTo(HashSet()) { it.ddsTopic }
        return RosGraph(endpoints + (topics + services).filter { it.ddsTopic !in known }, nodeInfo = info, ownName = config.nodeName)
    }

    /** The bridge, if connected and it allows reading and changing parameters (`parameters` capability). */
    fun bridgeWithParameters(): FoxgloveBridge? = bridge?.takeIf { it.canUseParameters }

    /** Whether the bridge advertises the service. */
    fun bridgeHasService(name: String): Boolean = bridge?.services?.containsKey(name) == true

    /** Runs on executor. */
    private fun attachBridge(s: ManagedSubscription<*>) {
        val b = bridge ?: return
        if (s.cancelled || s.bridgeSubId != null || !b.connected) return
        // Services and actions go through internal DDS topics that the bridge does not expose.
        if (s.ddsName != null) { s.error = "Services and actions are not available through foxglove_bridge"; return }
        s.error = null
        s.bridgeSubId = b.subscribe(s.topicName) { data, _ -> s.deliver(data) }
    }

    /** Runs on executor. */
    private fun attachBridge(p: ManagedPublisher<*>) {
        val b = bridge ?: return
        if (p.closed || p.bridgeChannel != null || p.ddsName != null || !b.connected) return
        p.bridgeChannel = b.advertise(p.topicName, rosTypeName(ddsType(p.type)))
    }

    private fun <T : ROS2Message<T>> ManagedSubscription<T>.deliver(data: ByteArray) = callback.onMessage(BridgeReader(data, type))

    /** jros2 reader over a CDR message arriving through the bridge. */
    private class BridgeReader<T : ROS2Message<T>>(private val data: ByteArray, private val type: Class<T>) : us.ihmc.jros2.ROS2MessageReader<T> {
        private var used = false
        override fun hasHadData() = true
        override fun read(msg: T): Boolean {
            if (used) return false
            used = true
            val buf = us.ihmc.fastddsjava.cdr.CDRBuffer()
            buf.ensureRemainingCapacity(data.size + 8)
            buf.getBufferUnsafe().put(data)
            buf.rewind()
            buf.readPayloadHeader()
            msg.deserialize(buf)
            return true
        }
        override fun read(msg: T, info: us.ihmc.jros2.SampleInfo?): Boolean = read(msg)
        override fun read(): T? = ROS2Message.createInstance(type).takeIf { read(it) }
        override fun readLatest(msg: T): Int = if (read(msg)) 1 else 0
        override fun readLatest(): T? = read()
    }

    /** Message -> CDR with the encapsulation header (for publishing through the bridge). */
    private fun <T : ROS2Message<T>> cdrOf(msg: T): ByteArray {
        val buf = us.ihmc.fastddsjava.cdr.CDRBuffer()
        buf.ensureRemainingCapacity(us.ihmc.fastddsjava.cdr.CDRBuffer.PAYLOAD_HEADER.size + msg.calculateSizeBytes(0) + 16)
        buf.writePayloadHeader()
        msg.serialize(buf)
        val bb = buf.getBufferUnsafe()
        val out = ByteArray(bb.position())
        bb.duplicate().apply { flip() }.get(out)
        return out
    }

    /** Runs on executor. */
    private fun closeNode() {
        bridge?.let { b ->
            b.close()
            bridge = null
            for (s in subscriptions) s.bridgeSubId = null
            for (p in publishers) p.bridgeChannel = null
        }
        zenoh?.let { z ->
            z.close()
            zenoh = null
            for (s in subscriptions) s.zenohSubId = null
            for (p in publishers) p.zenohPub = null
        }
        for (s in subscriptions) s.subscription = null
        for (p in publishers) p.publisher = null
        topicTypes.clear()
        topics.clear()
        try {
            node?.let { closeNode(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando ROS2Node", e)
        }
        node = null
        try {
            multicastRelease?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando MulticastLock", e)
        }
        multicastRelease = null
    }

    // ---------------------------------------------------------------- listeners

    fun addTfListener(l: TfListener) { tfListeners.addIfAbsent(l) }
    fun removeTfListener(l: TfListener) { tfListeners.remove(l) }

    /** Notified on the main thread; called immediately with the current state. */
    fun addStatusListener(l: (Status) -> Unit) {
        statusListeners.addIfAbsent(l)
        val current = status
        mainHandler.post { if (l in statusListeners) l(current) }
    }

    fun removeStatusListener(l: (Status) -> Unit) { statusListeners.remove(l) }

    private fun setStatus(s: Status) {
        status = s
        mainHandler.post { for (l in statusListeners) l(s) }
    }

    // ---------------------------------------------------------------- generic subscriptions

    /** QoS compatible with any sensor publisher (best effort matches both reliable and best effort). */
    fun sensorQos(): ROS2QoSProfile = ROS2QoSProfile().apply {
        reliability(ROS2QoSProfile.Reliability.BEST_EFFORT)
        durability(ROS2QoSProfile.Durability.VOLATILE)
        history(ROS2QoSProfile.History.KEEP_LAST)
        depth(1)
    }

    fun <T : ROS2Message<T>> subscribe(
        topicName: String, type: Class<T>, qos: ROS2QoSProfile, callback: ROS2SubscriptionCallback<T>
    ): ManagedSubscription<T> = subscribe(topicName, type, qos, callback, null)

    /**
     * Like [subscribe] but with a literal DDS name ("rr/add_two_intsReply"), without the "rt" prefix
     * of topics: for services and actions.
     */
    fun <T : ROS2Message<T>> subscribeRaw(
        ddsName: String, type: Class<T>, qos: ROS2QoSProfile, callback: ROS2SubscriptionCallback<T>
    ): ManagedSubscription<T> = subscribe(RAW_PREFIX + ddsName, type, qos, callback, ddsName)

    private fun <T : ROS2Message<T>> subscribe(
        topicName: String, type: Class<T>, qos: ROS2QoSProfile, callback: ROS2SubscriptionCallback<T>, ddsName: String?
    ): ManagedSubscription<T> {
        // An exception escaping a callback reaches JavaCPP as a C++ exception and abort()s
        // the whole app (seen on a POCO X7 Pro). Nothing must ever escape from here.
        val safe = ROS2SubscriptionCallback<T> { reader ->
            try {
                callback.onMessage(reader)
            } catch (t: Throwable) {
                Log.e(TAG, "Error procesando un mensaje de $topicName", t)
            }
        }
        // ${variable} in the name (TopicVariables): resolved here, for everyone.
        val s = ManagedSubscription(if (ddsName == null) TopicVariables.resolve(topicName) else topicName, type, qos, safe, ddsName)
        subscriptions.add(s)
        executor.execute { node?.let { attach(it, s) } ?: run { attachBridge(s); attachZenoh(s) } }
        return s
    }

    private fun unsubscribe(s: ManagedSubscription<*>) {
        s.cancelled = true
        subscriptions.remove(s)
        executor.execute { detach(s) }
    }

    private fun <T : ROS2Message<T>> attach(n: ROS2Node, s: ManagedSubscription<T>) {
        if (s.cancelled || s.subscription != null) return
        if (!ensureTypeAvailable(s.topicName, s.type)) {
            if (!topicTypes.isCompatible(s.topicName, ddsType(s.type))) {
                s.error = "${s.topicName} is already used with type ${topicTypes.typeOf(s.topicName)?.let { rosTypeName(it) }}"
            }
            return
        }
        s.error = null
        try {
            val topic = topicFor(s.topicName, s.type)
            s.ddsName?.let { RawTopics.bind(n, topic, it) }
            s.subscription = n.createSubscription(topic, s.callback, s.qos)
            topicTypes.register(s.topicName, ddsType(s.type))
        } catch (e: Throwable) {
            Log.e(TAG, "No se pudo suscribir a ${s.topicName}", e)
            s.error = "Could not subscribe: ${e.message}"
        }
    }

    private fun detach(s: ManagedSubscription<*>) {
        s.bridgeSubId?.let { id -> s.bridgeSubId = null; bridge?.unsubscribe(id) }
        s.zenohSubId?.let { id -> s.zenohSubId = null; zenoh?.unsubscribe(id) }
        val sub = s.subscription ?: return
        s.subscription = null
        try {
            node?.destroySubscription(sub)
        } catch (e: Throwable) {
            Log.e(TAG, "Error destruyendo la suscripción a ${s.topicName}", e)
        }
    }

    /**
     * Runs on executor, after [ensureTypeAvailable] (the DDS type is already compatible). By
     * name and class: the same DDS type can arrive with the jros2 class or a dynamic one, and
     * jros2 creates received messages with the ROS2Topic's class.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : ROS2Message<T>> topicFor(name: String, type: Class<T>): ROS2Topic<T> =
        topics.getOrPut(name + "\u0000" + type.name) { ROS2Topic(name, type) } as ROS2Topic<T>

    /** "geometry_msgs::msg::dds_::Twist_" -> "geometry_msgs/msg/Twist". */
    fun rosTypeName(dds: String): String =
        dds.split("::").filter { it != "dds_" }.joinToString("/").removeSuffix("_")

    /** DDS type name of a message class ("geometry_msgs::msg::dds_::Twist_"). */
    private fun ddsType(type: Class<*>): String = try {
        type.getField("name").get(null) as? String ?: type.name
    } catch (_: Throwable) {
        type.name
    }

    /**
     * Runs on executor. Avoids jros2's SIGSEGV when reusing a name with another type
     * (see [TopicTypeRegistry]): if nobody wants the previous type anymore, the node is recreated
     * (the endpoint will be created when reopening it); if someone still uses it, it is rejected.
     */
    private fun ensureTypeAvailable(name: String, type: Class<*>): Boolean {
        val wanted = ddsType(type)
        if (topicTypes.isCompatible(name, wanted)) return true
        val previous = topicTypes.typeOf(name)
        val stillWanted = subscriptions.any { it.topicName == name && ddsType(it.type) == previous } ||
                publishers.any { it.topicName == name && ddsType(it.type) == previous }
        if (stillWanted) {
            Log.e(TAG, "El topic $name ya se usa con el tipo $previous; no se puede usar a la vez como $wanted")
        } else {
            Log.w(TAG, "El topic $name cambia de tipo ($previous -> $wanted): se recrea el nodo DDS")
            connect(delayMs = 0)
        }
        return false
    }

    // ---------------------------------------------------------------- publishers

    /** Default ROS 2 QoS (reliable, volatile, keep last 10): compatible with any subscriber. */
    fun defaultQos(): ROS2QoSProfile = ROS2QoSProfile().apply {
        reliability(ROS2QoSProfile.Reliability.RELIABLE)
        durability(ROS2QoSProfile.Durability.VOLATILE)
        history(ROS2QoSProfile.History.KEEP_LAST)
        depth(10)
    }

    fun <T : ROS2Message<T>> createPublisher(topicName: String, type: Class<T>, qos: ROS2QoSProfile = defaultQos()): ManagedPublisher<T> =
        createPublisher(topicName, type, qos, null)

    /** Like [createPublisher] with a literal DDS name ("rq/add_two_intsRequest"): services and actions. */
    fun <T : ROS2Message<T>> createRawPublisher(ddsName: String, type: Class<T>, qos: ROS2QoSProfile = defaultQos()): ManagedPublisher<T> =
        createPublisher(RAW_PREFIX + ddsName, type, qos, ddsName)

    private fun <T : ROS2Message<T>> createPublisher(topicName: String, type: Class<T>, qos: ROS2QoSProfile, ddsName: String?): ManagedPublisher<T> {
        val p = ManagedPublisher(if (ddsName == null) TopicVariables.resolve(topicName) else topicName, type, qos, ddsName)
        publishers.add(p)
        executor.execute { node?.let { attach(it, p) } ?: run { attachBridge(p); attachZenoh(p) } }
        return p
    }

    private fun destroyPublisher(p: ManagedPublisher<*>) {
        p.closed = true
        p.bridgeChannel = null
        p.zenohPub?.close(); p.zenohPub = null
        publishers.remove(p)
        executor.execute {
            val pub = p.publisher ?: return@execute
            p.publisher = null
            try {
                node?.destroyPublisher(pub)
            } catch (e: Throwable) {
                Log.e(TAG, "Error destruyendo el publicador de ${p.topicName}", e)
            }
        }
    }

    private fun <T : ROS2Message<T>> attach(n: ROS2Node, p: ManagedPublisher<T>) {
        if (p.closed || p.publisher != null) return
        if (!ensureTypeAvailable(p.topicName, p.type)) return
        try {
            val topic = topicFor(p.topicName, p.type)
            p.ddsName?.let { RawTopics.bind(n, topic, it) }
            p.publisher = n.createPublisher(topic, p.qos)
            topicTypes.register(p.topicName, ddsType(p.type))
        } catch (e: Throwable) {
            Log.e(TAG, "No se pudo crear el publicador de ${p.topicName}", e)
        }
    }

    // ---------------------------------------------------------------- ROS graph

    /**
     * ROS 2 graph currently seen by the main node (topics, services, actions), like `ros2 topic
     * list`. Null if there is no connection or if the native libraries do not include discovery.
     * Can be called from any thread: it is a copy of the native listener's state.
     */
    fun graph(): RosGraph? {
        // Over Zenoh: the nodes' liveliness tokens (topics, services and nodes, as over DDS).
        zenoh?.let { return it.graph(config.nodeName) }
        // Through the bridge: the topics it advertises (no DDS nodes or services in view).
        bridge?.let { b ->
            val topics = b.channels.values.mapNotNull { c ->
                // "pkg/msg/Type" (or "pkg/Type"); non-ROS channels (without a type) are not listed.
                val p = c.schemaName.split('/')
                val type = when (p.size) { 3 -> c.schemaName; 2 -> "${p[0]}/msg/${p[1]}"; else -> return@mapNotNull null }
                RosGraph.Endpoint(true, "rt" + c.topic, com.juancams.rviz2mini.ros.dyn.MsgSpec.ddsTypeName(type), "bridge:${c.id}")
            }
            // Services, as a server reading its requests (so they show up in the service list).
            val services = b.services.values.mapNotNull { s ->
                if (s.type.split('/').size != 3) return@mapNotNull null
                RosGraph.Endpoint(false, "rq${s.name}Request", com.juancams.rviz2mini.ros.dyn.MsgSpec.ddsTypeName(s.type + "_Request"), "bridge:s${s.id}")
            }
            return if (b.hasConnectionGraph) bridgeGraph(b, topics, services) else RosGraph(topics + services, ownName = config.nodeName)
        }
        val n = node ?: return null
        return try {
            // Lines "prefix \t name \t IPs" -> prefix -> name.
            val names = GraphAccess.discoveredParticipantAddresses(n).mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }.toMap()
            RosGraph.parse(GraphAccess.discoveredEndpoints(n), GraphAccess.discoveredParticipants(n), RosNodes.snapshot(), names, (status as? Status.Connected)?.nodeName)
        } catch (e: Throwable) {
            Log.e(TAG, "No se pudo leer el grafo ROS 2", e)
            null
        }
    }

    /**
     * Waits (without blocking the UI) for discovery to fill the graph: right after connecting, DDS
     * takes a while to learn about the other participants. Calls `onResult` on the main thread.
     */
    fun discover(waitMs: Long = 1500, onResult: (RosGraph?) -> Unit) {
        Thread({
            val connectedAt = lastConnectedAt
            val settle = connectedAt + waitMs - System.currentTimeMillis()
            if (settle > 0) try { Thread.sleep(settle) } catch (_: InterruptedException) {}
            val g = graph()
            mainHandler.post { onResult(g) }
        }, "ros2-discover").start()
    }

    @Volatile private var lastConnectedAt = 0L

    /** Peers in use on the current connection (the user's and the learned ones). */
    @Volatile var activePeers: List<String> = emptyList()
        private set

    /** Remote participants currently seen, with their IPs: (name, IPs). */
    fun remoteParticipants(): List<Pair<String, List<String>>> {
        val n = node ?: return emptyList()
        return try {
            val own = graph()?.ownPrefix()
            // Useful name: the participant's ROS nodes (the DDS name is usually "/").
            val nodes = RosNodes.snapshot().entries.associate { (gid, list) -> gid.take(24) to list.map { it.fullName } }
            GraphAccess.discoveredParticipantAddresses(n).mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 3 || p[0] == own) null
                else (nodes[p[0]]?.joinToString()?.ifBlank { null } ?: p[1]) to p[2].split(',').filter { it.isNotBlank() }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** Periodically records in [KnownRobots] the machines seen, while this connection lasts. */
    private fun scheduleLearn(gen: Int) {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!started) return
                if (gen != generation.get()) return
                val cfg = config
                if (cfg.domainId != RosConfig.DEMO_DOMAIN_ID) {
                    val seen = remoteParticipants().flatMap { (name, ips) -> ips.map { it to name } }
                    if (seen.isNotEmpty()) Thread({ KnownRobots.remember(cfg.domainId, seen, KnownRobots.ownIps()) }, "known-robots").start()
                }
                mainHandler.postDelayed(this, LEARN_MS)
            }
        }, 4000)
    }

    // ---------------------------------------------------------------- topic probing

    class ProbeRequest<T : ROS2Message<T>>(val topicName: String, val type: Class<T>)

    /**
     * Creates temporary readers for each candidate, waits `windowMs` for DDS to match them with
     * some publisher and destroys them. Returns (on the main thread) those that had a publisher.
     *
     * It is done with temporary DDS nodes (one per "layer" of repeated names) instead of the main
     * node: this way the same name can be tried with several types without jros2's SIGSEGV and the
     * main node does not accumulate topics that jros2 never frees.
     */
    fun probe(requests: List<ProbeRequest<*>>, windowMs: Long, onResult: (List<ProbeRequest<*>>) -> Unit) {
        if (status !is Status.Connected) {
            mainHandler.post { onResult(emptyList()) }
            return
        }
        val cfg = config
        Thread({
            val nodes = ArrayList<ROS2Node>()
            val created = ArrayList<Pair<ProbeRequest<*>, ROS2Subscription<*>>>()
            val found = ArrayList<ProbeRequest<*>>()
            try {
                val groups = TopicTypeRegistry.groupWithoutNameClashes(requests) { it.topicName }
                for ((i, group) in groups.withIndex()) {
                    val n = createNode("${cfg.nodeName}_probe$i", cfg.domainId)
                    nodes.add(n)
                    for (r in group) createProbe(n, r)?.let { created.add(r to it) }
                }
                val deadline = System.currentTimeMillis() + windowMs
                for ((r, sub) in created) {
                    val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
                    if (sub.waitForPublisher(remaining)) found.add(r)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error sondeando topics", e)
            } finally {
                for (n in nodes) {
                    try { closeNode(n) } catch (_: Throwable) {}
                }
            }
            mainHandler.post { onResult(found) }
        }, "ros2-topic-probe").start()
    }

    private fun <T : ROS2Message<T>> createProbe(n: ROS2Node, r: ProbeRequest<T>): ROS2Subscription<T>? =
        try {
            n.createSubscription(ROS2Topic(r.topicName, r.type), sensorQos())
        } catch (e: Throwable) {
            Log.w(TAG, "No se pudo sondear ${r.topicName}: ${e.message}")
            null
        }
}
