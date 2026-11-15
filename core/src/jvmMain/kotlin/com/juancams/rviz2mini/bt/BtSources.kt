// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bt

import com.juancams.rviz2mini.platform.SystemClock
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.Interfaces
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.random.Random

/**
 * Where the tree shown by the tab comes from. They write on their own thread and the screen reads the latest
 * every so often with [snapshot].
 */
abstract class BtSource : AutoCloseable {

    class Snapshot(val tree: BtTree, val states: Map<Int, BtNodeState>, val connected: Boolean, val error: String?, val version: Long,
                   val blackboard: Map<String, Map<String, Any?>> = emptyMap())

    @Volatile protected var tree: BtTree = BtTree.EMPTY
    @Volatile protected var states: Map<Int, BtNodeState> = emptyMap()
    @Volatile protected var connected = false
    @Volatile protected var error: String? = null
    /** Blackboards (Groot2 only): blackboard -> variable -> value. */
    @Volatile protected var blackboard: Map<String, Map<String, Any?>> = emptyMap()
    @Volatile private var version = 0L
    /** The latest status changes, oldest to newest. */
    val transitions = ConcurrentLinkedDeque<BtTransition>()

    fun snapshot() = Snapshot(tree, states, connected, error, version, blackboard)

    protected fun changed() { version++ }

    /** A transition recorded by the publisher (already with its previous status). */
    protected fun exact(t: BtTransition) = record(t)

    protected fun record(t: BtTransition) {
        transitions.addLast(t)
        while (transitions.size > MAX_TRANSITIONS) transitions.pollFirst()
    }

    companion object { const val MAX_TRANSITIONS = 200 }
}

/**
 * BehaviorTree.CPP 4.x Groot2Publisher: requests the tree (FULLTREE) and then the statuses (STATUS)
 * every [periodMs]; if the tree UUID changes (another tree loaded) it requests it again. Status
 * changes are deduced by comparing one query with the previous one. Reconnects by itself.
 */
class Groot2Source(val host: String, val port: Int, private val periodMs: Long = 100) : BtSource() {

    private val thread = Thread(::loop, "groot2").apply { isDaemon = true; start() }

    // ---- breakpoints, like Groot2: stops before executing the node until "Continue"

    /** Nodes with a breakpoint. */
    val breakpoints: Set<Int> get() = breakpointSet.toSet()
    /** The node where the tree is stopped (notice from the notification socket), or null. */
    @Volatile var pausedAt: Int? = null; private set
    private val breakpointSet = java.util.concurrent.CopyOnWriteArraySet<Int>()
    /** Commands for the request socket: run on the loop thread, between queries. */
    private val commands = java.util.concurrent.ConcurrentLinkedQueue<(ZmqReq) -> Unit>()
    @Volatile private var notifications: ZmqReq? = null
    private val notifyThread = Thread(::listen, "groot2-notify").apply { isDaemon = true; start() }

    fun toggleBreakpoint(uid: Int) {
        if (breakpointSet.remove(uid)) {
            // If it was stopped there, it continues; and the hook is removed (the others are set again).
            val wasPaused = pausedAt == uid
            commands += { z ->
                if (wasPaused) { send(z, Groot2.BREAKPOINT_UNLOCK, Groot2.unlockJson(uid, remove = true)); pausedAt = null }
                send(z, Groot2.REMOVE_ALL_HOOKS)
                for (u in breakpointSet) send(z, Groot2.HOOK_INSERT, Groot2.breakpointJson(u))
                if (breakpointSet.isEmpty()) startRecording(z)
            }
        } else {
            breakpointSet += uid
            // With transition recording active BT.CPP does not stop at breakpoints: it is turned off while there are any.
            commands += { z -> stopRecording(z); send(z, Groot2.HOOK_INSERT, Groot2.breakpointJson(uid)) }
        }
        changed()
    }

    /** Continues from the breakpoint where it is stopped (the breakpoint stays for the next round). */
    fun resume() {
        val uid = pausedAt ?: return
        pausedAt = null
        commands += { z -> send(z, Groot2.BREAKPOINT_UNLOCK, Groot2.unlockJson(uid, remove = false)) }
        changed()
    }

    private fun send(z: ZmqReq, type: Int, body: String? = null) {
        val id = Random.nextInt()
        val reply = z.request(listOfNotNull(Groot2.requestHeader(type, id), body?.toByteArray()))
        Groot2.checkReply(reply[0], type, id)
    }

    /** Notification socket (Groot2Publisher's PUB, on the next port): "stopped at node X". */
    private fun listen() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                ZmqReq(host, port + 1, 1000, "SUB").use { sub ->
                    notifications = sub
                    sub.subscribe()
                    while (!Thread.currentThread().isInterrupted) {
                        val parts = try { sub.receive() } catch (_: java.net.SocketTimeoutException) { continue }
                        Groot2.parseBreakpointReached(parts)?.let { uid -> if (uid in breakpointSet) { pausedAt = uid; changed() } }
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                if (Thread.currentThread().isInterrupted) break
                try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { break }
            }
        }
    }


    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                ZmqReq(host, port).use { zmq ->
                    var treeId = fetchTree(zmq)
                    connected = true; error = null; changed()
                    // After reconnecting, any existing breakpoints are set again.
                    if (breakpointSet.isEmpty()) startRecording(zmq) else for (u in breakpointSet) send(zmq, Groot2.HOOK_INSERT, Groot2.breakpointJson(u))
                    var polls = 0
                    while (!Thread.currentThread().isInterrupted) {
                        while (true) (commands.poll() ?: break)(zmq)
                        // The blackboard, every few status queries.
                        if (polls++ % BLACKBOARD_EVERY == 0 && tree.blackboards.isNotEmpty()) fetchBlackboard(zmq)
                        val id = Random.nextInt()
                        val reply = zmq.request(listOf(Groot2.requestHeader(Groot2.STATUS, id)))
                        val uuid = Groot2.checkReply(reply[0], Groot2.STATUS, id)
                        if (uuid != treeId) { treeId = fetchTree(zmq); changed(); if (breakpointSet.isEmpty()) startRecording(zmq); continue }
                        update(Groot2.parseStatus(reply.getOrNull(1) ?: ByteArray(0)))
                        if (recording) fetchTransitions(zmq)
                        Thread.sleep(periodMs)
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) break
                connected = false
                error = e.message ?: e.javaClass.simpleName
                changed()
                try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { break }
            }
        }
        connected = false
    }

    // ---- recording (exact transitions, with their timestamp, instead of deducing them from the status)

    /** Whether the publisher records transitions (recent BT.CPP): then those are used. */
    @Volatile var recording = false; private set
    /** Instant (elapsedRealtime) when recording started, and the last transition seen (µs). */
    private var recordStartMs = 0L
    private var lastTransitionUs = -1L
    private val lastRecorded = HashMap<Int, BtStatus>()

    private fun startRecording(zmq: ZmqReq) {
        recording = false
        try {
            val id = Random.nextInt()
            val reply = zmq.request(listOf(Groot2.requestHeader(Groot2.TOGGLE_RECORDING, id), "start".toByteArray()))
            Groot2.checkReply(reply[0], Groot2.TOGGLE_RECORDING, id)
            if (reply.size >= 2) { recordStartMs = SystemClock.elapsedRealtime(); lastTransitionUs = -1; lastRecorded.clear(); recording = true }
        } catch (e: java.io.IOException) {
            throw e
        } catch (_: Exception) {
            // Without recording: transitions are still deduced from the status.
        }
    }

    private fun stopRecording(zmq: ZmqReq) {
        if (!recording) return
        recording = false
        try { send(zmq, Groot2.TOGGLE_RECORDING, "stop") } catch (e: java.io.IOException) { throw e } catch (_: Exception) {}
    }

    private fun fetchTransitions(zmq: ZmqReq) {
        val id = Random.nextInt()
        val reply = zmq.request(listOf(Groot2.requestHeader(Groot2.GET_TRANSITIONS, id)))
        Groot2.checkReply(reply[0], Groot2.GET_TRANSITIONS, id)
        val prev = lastRecorded
        for ((us, uid, status) in Groot2.parseTransitions(reply.getOrNull(1) ?: return)) {
            if (us <= lastTransitionUs) continue
            lastTransitionUs = us
            val from = prev[uid] ?: BtStatus.IDLE
            prev[uid] = status
            exact(BtTransition(recordStartMs + us / 1000, uid, tree.nodes[uid]?.name ?: "#$uid", from, status))
        }
    }

    private fun fetchBlackboard(zmq: ZmqReq) {
        val id = Random.nextInt()
        val reply = zmq.request(listOf(Groot2.requestHeader(Groot2.BLACKBOARD, id), tree.blackboards.joinToString(";").toByteArray()))
        Groot2.checkReply(reply[0], Groot2.BLACKBOARD, id)
        val bb = try { Groot2.parseBlackboards(reply.getOrNull(1) ?: return) } catch (_: Exception) { return }
        if (bb != blackboard) { blackboard = bb; changed() }
    }

    private fun fetchTree(zmq: ZmqReq): String {
        val id = Random.nextInt()
        val reply = zmq.request(listOf(Groot2.requestHeader(Groot2.FULL_TREE, id)))
        val uuid = Groot2.checkReply(reply[0], Groot2.FULL_TREE, id)
        tree = Groot2.parseTree(String(reply.getOrNull(1) ?: ByteArray(0), Charsets.UTF_8), uuid)
        states = emptyMap()
        blackboard = emptyMap()
        return uuid
    }

    private fun update(now: Map<Int, BtNodeState>) {
        val before = states
        val t = SystemClock.elapsedRealtime()
        var any = false
        for ((uid, s) in now) {
            val old = before[uid]?.status ?: BtStatus.IDLE
            if (old != s.status) {
                any = true
                // With recording, the transitions arrive exactly on their own.
                if (!recording) record(BtTransition(t, uid, tree.nodes[uid]?.name ?: "#$uid", old, s.status))
            } else if (before[uid] != s) any = true
        }
        states = now
        if (any) changed()
    }

    /** On close: lets it continue and removes the breakpoints (otherwise the robot's tree would stay stopped). */
    private fun removeHooks() {
        try {
            ZmqReq(host, port).use { z ->
                for (u in breakpointSet) send(z, Groot2.BREAKPOINT_UNLOCK, Groot2.unlockJson(u, remove = true))
                send(z, Groot2.REMOVE_ALL_HOOKS)
            }
        } catch (_: Exception) {}
        breakpointSet.clear()
    }

    override fun close() {
        thread.interrupt()
        notifyThread.interrupt()
        notifications?.close()
        if (breakpointSet.isNotEmpty()) Thread({ removeHooks() }, "groot2-unhook").apply { isDaemon = true; start() }
    }

    private companion object {
        const val RETRY_MS = 1000L
        const val BLACKBOARD_EVERY = 5
    }
}

/**
 * Nav2's `/behavior_tree_log` (bt_navigator): the nodes as they change status, or over the
 * tree from its XML if given (`structure`). `humble`: the Humble message, which has no uid.
 */
class Nav2LogSource(val topic: String, structure: BtTree? = null, humble: Boolean = false) : BtSource() {

    private val log = BtLogState(structure)
    private val spec = if (humble) HUMBLE.msg("nav2_msgs/msg/BehaviorTreeLog") else Interfaces.get().msg("nav2_msgs/msg/BehaviorTreeLog")

    init { if (structure != null) { tree = structure; changed() } }

    private val subscription: DynRos.Subscription = DynRos.subscribe(DynRos.normalizeName(topic), spec, RosConnection.defaultQos()) { v ->
        synchronized(log) {
            val t = SystemClock.elapsedRealtime()
            for (e in (v["event_log"] as? List<*>).orEmpty()) {
                val m = e as? Map<*, *> ?: continue
                log.apply(m["node_name"]?.toString() ?: continue, (m["uid"] as? Number)?.toInt(),
                    m["previous_status"]?.toString().orEmpty(), m["current_status"]?.toString().orEmpty(), t)?.let(::record)
            }
            tree = log.tree()
            states = HashMap(log.states)
            connected = true
            changed()
        }
    }

    override fun close() { subscription.close() }

    private companion object {
        /** Humble's BehaviorTreeLog (without Jazzy's uid): same DDS name, different structure. */
        val HUMBLE = com.juancams.rviz2mini.ros.dyn.InterfaceRegistry().apply {
            define("builtin_interfaces/msg/Time", "int32 sec\nuint32 nanosec")
            define("nav2_msgs/msg/BehaviorTreeStatusChange", "builtin_interfaces/Time timestamp\nstring node_name\nstring previous_status\nstring current_status")
            define("nav2_msgs/msg/BehaviorTreeLog", "builtin_interfaces/Time timestamp\nnav2_msgs/BehaviorTreeStatusChange[] event_log")
        }
    }
}
