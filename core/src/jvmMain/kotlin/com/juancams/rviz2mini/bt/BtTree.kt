// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.bt

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

/** State of a BehaviorTree.CPP node (values are those of the Groot2 protocol). */
enum class BtStatus { IDLE, RUNNING, SUCCESS, FAILURE, SKIPPED;

    companion object {
        fun of(code: Int): BtStatus? = entries.getOrNull(code)
        fun of(name: String): BtStatus? = entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * State that is drawn: `status`, and if it is IDLE, `previous` is how it ended last time (Groot2
 * sends it this way so you can see which branch just ran even if the tree has already restarted).
 */
data class BtNodeState(val status: BtStatus, val previous: BtStatus? = null)

enum class BtCategory { CONTROL, DECORATOR, ACTION, CONDITION, SUBTREE }

class BtNode(
    val uid: Int,
    /** Registered type ("Sequence", "Wait", "ComputePathToPose"…). */
    val type: String,
    /** Instance name (the one in the XML, or the type if it has none). */
    val name: String,
    val category: BtCategory,
    val parent: Int?,
    /** Ports and other XML attributes. */
    val attributes: Map<String, String> = emptyMap(),
) {
    val children = ArrayList<Int>()
}

/**
 * An already expanded tree (SubTrees hang with their nodes inside). `nodes` is in pre-order.
 * `blackboards`: the names of its blackboards for Groot2 (the main tree's ID and the
 * `_fullpath` of each SubTree).
 */
class BtTree(val nodes: Map<Int, BtNode>, val treeId: String? = null, val blackboards: List<String> = emptyList()) {
    val roots: List<Int> get() = nodes.values.filter { it.parent == null }.map { it.uid }

    companion object { val EMPTY = BtTree(emptyMap()) }
}

/** A state change seen (for the tab's log). */
data class BtTransition(val timeMs: Long, val uid: Int, val name: String, val from: BtStatus, val to: BtStatus)

/**
 * The Groot2 protocol (BT::Groot2Publisher, BehaviorTree.CPP 4.x): ZeroMQ REQ/REP requests
 * to its port (1667 by default). Each request starts with a 6-byte header (protocol,
 * type, random id) and the reply repeats that header followed by the tree UUID (16 bytes).
 */
object Groot2 {
    const val DEFAULT_PORT = 1667
    const val PROTOCOL = 2
    const val FULL_TREE = 'T'.code
    const val STATUS = 'S'.code
    const val BLACKBOARD = 'B'.code
    const val TOGGLE_RECORDING = 'r'.code
    const val HOOK_INSERT = 'I'.code
    const val BREAKPOINT_REACHED = 'N'.code
    const val BREAKPOINT_UNLOCK = 'U'.code
    const val REMOVE_ALL_HOOKS = 'A'.code

    /** Breakpoint before executing node `uid` (the one Groot2 sets). */
    fun breakpointJson(uid: Int): String =
        """{"enabled":true,"uid":$uid,"mode":0,"once":false,"desired_status":"SUCCESS","position":0}"""

    /** Releases the breakpoint of `uid` (and removes it if `remove`). */
    fun unlockJson(uid: Int, remove: Boolean): String =
        """{"uid":$uid,"desired_status":"SUCCESS","position":0,"remove_when_done":$remove}"""

    /** Message from the notification socket: the uid of the breakpoint reached, or null if it is not that. */
    fun parseBreakpointReached(parts: List<ByteArray>): Int? {
        if (parts.size < 2 || parts[0].size < 2 || parts[0][1].toInt() != BREAKPOINT_REACHED) return null
        return String(parts[1], Charsets.US_ASCII).trim().toIntOrNull()
    }

    const val GET_TRANSITIONS = 't'.code

    /** Recorded transitions: (µs since recording started, uid, state), 9 bytes each. */
    fun parseTransitions(b: ByteArray): List<Triple<Long, Int, BtStatus>> {
        val out = ArrayList<Triple<Long, Int, BtStatus>>(b.size / 9)
        var i = 0
        while (i + 9 <= b.size) {
            var t = 0L
            for (k in 5 downTo 0) t = (t shl 8) or (b[i + k].toLong() and 0xFF)
            val uid = (b[i + 6].toInt() and 0xFF) or ((b[i + 7].toInt() and 0xFF) shl 8)
            val code = b[i + 8].toInt() and 0xFF
            BtStatus.of(if (code >= 10) 0 else code)?.let { out += Triple(t, uid, it) }
            i += 9
        }
        return out
    }
    private const val REPLY_HEADER = 22

    fun requestHeader(type: Int, id: Int): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).put(PROTOCOL.toByte()).put(type.toByte()).putInt(id).array()

    /** Checks a reply header and returns the tree UUID (in hexadecimal). */
    fun checkReply(header: ByteArray, type: Int, id: Int): String {
        if (header.size < REPLY_HEADER) throw IllegalStateException("Groot2: short reply")
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.get(0).toInt() != PROTOCOL) throw IllegalStateException("Groot2: protocol ${bb.get(0)} is not supported (expected $PROTOCOL)")
        if (bb.get(1).toInt() != type || bb.getInt(2) != id) throw IllegalStateException("Groot2: reply does not match the request")
        return header.copyOfRange(6, REPLY_HEADER).joinToString("") { "%02x".format(it) }
    }

    /** States from the STATUS buffer: 3 bytes per node (uid u16 LE, state; 10 + X = IDLE after X). */
    fun parseStatus(buffer: ByteArray): Map<Int, BtNodeState> {
        val out = HashMap<Int, BtNodeState>(buffer.size / 3)
        var i = 0
        while (i + 3 <= buffer.size) {
            val uid = (buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8)
            val code = buffer[i + 2].toInt() and 0xFF
            out[uid] = if (code >= 10) BtNodeState(BtStatus.IDLE, BtStatus.of(code - 10)) else BtNodeState(BtStatus.of(code) ?: BtStatus.IDLE)
            i += 3
        }
        return out
    }

    /**
     * The FULLTREE XML (the tree with the `_uid` and `_fullpath` attributes): it starts from the
     * main BehaviorTree and each SubTree is replaced by the BehaviorTree of its `_fullpath`.
     */
    fun parseTree(xml: String, treeId: String? = null): BtTree {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false; isExpandEntityReferences = false }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        val trees = root.childElements().filter { it.tagName == "BehaviorTree" }
        // Groot2's has _fullpath and _uid; a hand-written one (Nav2's) does not: SubTrees are looked up
        // by ID and uids are numbered in pre-order from 1, as BehaviorTree.CPP assigns them when creating the tree.
        val fromGroot = trees.any { it.hasAttribute("_fullpath") }
        val byPath = if (fromGroot) trees.associateBy { it.getAttribute("_fullpath") } else emptyMap()
        val byId = trees.associateBy { it.getAttribute("ID") }
        val main = byPath[""] ?: trees.firstOrNull { it.getAttribute("ID") == root.getAttribute("main_tree_to_execute") } ?: trees.firstOrNull()
            ?: return BtTree(emptyMap(), treeId)
        val models = HashMap<String, BtCategory>()
        root.childElements().filter { it.tagName == "TreeNodesModel" }.forEach { m ->
            for (e in m.childElements()) categoryOfTag(e.tagName)?.let { models[e.getAttribute("ID")] = it }
        }
        val nodes = LinkedHashMap<Int, BtNode>()
        var synthetic = -1
        var next = 1
        fun add(e: Element, parent: Int?, depth: Int) {
            if (depth > MAX_DEPTH) return
            val tag = e.tagName
            // Format v3 (<Action ID="X"/>) and v4 (<X/>).
            val type = if (categoryOfTag(tag) != null && e.hasAttribute("ID")) e.getAttribute("ID") else tag
            val uid = e.getAttribute("_uid").toIntOrNull() ?: if (fromGroot) synthetic-- else next++
            val kids = e.childElements()
            val category = when {
                tag == "SubTree" -> BtCategory.SUBTREE
                else -> categoryOfTag(tag)?.takeIf { it != BtCategory.SUBTREE } ?: models[type] ?: BUILTIN[type] ?: when {
                    kids.size > 1 -> BtCategory.CONTROL
                    kids.size == 1 -> BtCategory.DECORATOR
                    else -> BtCategory.ACTION
                }
            }
            val attrs = LinkedHashMap<String, String>()
            val a = e.attributes
            for (i in 0 until a.length) {
                val n = a.item(i).nodeName
                if (n != "name" && n != "ID" && !n.startsWith("_")) attrs[n] = a.item(i).nodeValue
            }
            val label = e.getAttribute("name").ifBlank { if (tag == "SubTree") e.getAttribute("ID") else type }
            val node = BtNode(uid, if (tag == "SubTree") "SubTree" else type, label, category, parent, attrs)
            if (nodes.containsKey(uid)) return
            nodes[uid] = node
            parent?.let { nodes[it]?.children?.add(uid) }
            val inner = if (tag == "SubTree") (if (fromGroot) byPath[e.getAttribute("_fullpath")] else byId[e.getAttribute("ID")])?.childElements().orEmpty() else kids
            for (c in inner) add(c, uid, depth + 1)
        }
        for (e in main.childElements()) add(e, null, 0)
        val boards = listOf(main.getAttribute("ID")).filter { it.isNotEmpty() } +
            trees.map { it.getAttribute("_fullpath") }.filter { it.isNotEmpty() }.distinct()
        return BtTree(nodes, treeId, boards)
    }

    /**
     * The BLACKBOARD reply (MessagePack): blackboard -> variable -> value. Empty or
     * nonexistent blackboards come as null and are not returned.
     */
    fun parseBlackboards(bytes: ByteArray): Map<String, Map<String, Any?>> {
        val m = MsgPack.decode(bytes) as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Map<String, Any?>>()
        for ((k, v) in m) {
            val vars = v as? Map<*, *> ?: continue
            out[k.toString()] = vars.entries.associate { it.key.toString() to it.value }
        }
        return out
    }

    private fun categoryOfTag(tag: String): BtCategory? = when (tag) {
        "Action" -> BtCategory.ACTION
        "Condition" -> BtCategory.CONDITION
        "Control" -> BtCategory.CONTROL
        "Decorator" -> BtCategory.DECORATOR
        "SubTree" -> BtCategory.SUBTREE
        else -> null
    }

    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val list = childNodes
        for (i in 0 until list.length) (list.item(i) as? Element)?.let { out += it }
        return out
    }

    private const val MAX_DEPTH = 200

    /** In case the XML has no TreeNodesModel: the most common BehaviorTree.CPP and Nav2 nodes. */
    private val BUILTIN: Map<String, BtCategory> = buildMap {
        for (t in listOf("Sequence", "SequenceWithMemory", "ReactiveSequence", "Fallback", "ReactiveFallback", "Parallel", "ParallelAll",
            "IfThenElse", "WhileDoElse", "Switch2", "Switch3", "Switch4", "Switch5", "Switch6", "AsyncSequence", "AsyncFallback",
            "PipelineSequence", "RecoveryNode", "RoundRobin")) put(t, BtCategory.CONTROL)
        for (t in listOf("Inverter", "ForceSuccess", "ForceFailure", "Repeat", "RetryUntilSuccessful", "KeepRunningUntilFailure", "Timeout",
            "Delay", "RunOnce", "Precondition", "RateController", "DistanceController", "SpeedController", "GoalUpdater",
            "PathLongerOnApproach", "SingleTrigger")) put(t, BtCategory.DECORATOR)
        for (t in listOf("GoalReached", "GoalUpdated", "IsStuck", "TransformAvailable", "InitialPoseReceived", "IsBatteryLow",
            "IsBatteryCharging", "TimeExpired", "DistanceTraveled", "IsPathValid", "PathExpiringTimer", "AreErrorCodesPresent",
            "WouldAControllerRecoveryHelp", "WouldAPlannerRecoveryHelp", "WouldASmootherRecoveryHelp", "GloballyUpdatedGoal",
            "ScriptCondition")) put(t, BtCategory.CONDITION)
    }
}

/**
 * What Nav2's bt_navigator publishes on `/behavior_tree_log` (nav2_msgs/BehaviorTreeLog): only
 * state changes, without the tree's shape. Nodes that appear are accumulated (by uid
 * if present, otherwise by name).
 *
 * With `structure` (the XML of the tree bt_navigator runs) that tree is shown: the log's uids
 * are its own and, without uid (Humble), each name goes to the first node with that name.
 */
class BtLogState(private val structure: BtTree? = null) {
    private val byName: Map<String, Int> = structure?.nodes?.values?.groupBy { it.name }?.mapValues { it.value.first().uid }.orEmpty()

    private val nodes = LinkedHashMap<Int, BtNode>()
    private val keys = HashMap<String, Int>()
    val states = HashMap<Int, BtNodeState>()

    /** Applies a BehaviorTreeStatusChange; returns the transition or null if it is not valid. */
    fun apply(name: String, uid: Int?, previous: String, current: String, timeMs: Long): BtTransition? {
        val to = BtStatus.of(current) ?: return null
        val from = BtStatus.of(previous) ?: BtStatus.IDLE
        val id: Int
        if (structure != null) {
            // On the XML tree: by uid, or by name if it has none.
            id = uid?.takeIf { it > 0 && it in structure.nodes } ?: byName[name] ?: return null
        } else {
            val key = if (uid != null && uid > 0) "#$uid" else "n:$name"
            id = keys.getOrPut(key) { uid?.takeIf { it > 0 } ?: (FIRST_SYNTHETIC + keys.size) }
            if (id !in nodes) nodes[id] = BtNode(id, name, name, BtCategory.ACTION, null)
        }
        // Same as Groot2: when going back to IDLE, how it ended is remembered.
        states[id] = if (to == BtStatus.IDLE) BtNodeState(BtStatus.IDLE, from.takeIf { it != BtStatus.IDLE }) else BtNodeState(to)
        return BtTransition(timeMs, id, name, from, to)
    }

    /** By uid: bt_navigator numbers them in pre-order, so they come out in tree order. */
    fun tree(): BtTree = structure ?: BtTree(nodes.toSortedMap().let { LinkedHashMap(it) })

    fun clear() { nodes.clear(); keys.clear(); states.clear() }

    private companion object { const val FIRST_SYNTHETIC = 100_000 }
}
