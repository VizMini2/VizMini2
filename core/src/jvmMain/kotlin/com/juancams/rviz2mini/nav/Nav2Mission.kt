// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.nav

import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.dyn.DynActionClient
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.ros.RosConnection
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A Nav2 waypoint route (like RViz's Nav2 panel): waypoints are placed in the 3D view and sent with the
 * FollowWaypoints action (stops at each one) or NavigateThroughPoses (passes through them without stopping). While running,
 * the status: which one it is on, remaining distance and time, recoveries; when done, how it ended. It also listens to
 * the navigate_to_pose status, so the regular Goal tool also shows progress. No Android or Filament.
 *
 * Changes are notified with `onChanged` on the main thread.
 */
class Nav2Mission(private val registry: InterfaceRegistry, private val onChanged: () -> Unit = {}) {

    enum class Mode(val action: String, val type: String, val labelKey: String) {
        FOLLOW("/follow_waypoints", "nav2_msgs/action/FollowWaypoints", "nav2_mode_follow"),
        THROUGH("/navigate_through_poses", "nav2_msgs/action/NavigateThroughPoses", "nav2_mode_through"),
    }

    data class Waypoint(val x: Double, val y: Double, val yaw: Double)

    var waypoints: List<Waypoint> = emptyList(); private set
    var frame: String = "map"; private set
    var mode = Mode.FOLLOW
        set(v) { field = v; changed() }
    /** Extra loops over the route (FollowWaypoints). */
    var loops = 0
        set(v) { field = v; changed() }

    /** Route in progress (ours). */
    @Volatile var running = false; private set
    /** What is shown: "Waypoint 2/4 · 3.2 m · 12 s" or "Succeeded". */
    @Volatile var status: String? = null; private set
    /** Which one it is on (index in [waypoints]), to highlight it in 3D. */
    @Volatile var current: Int = -1; private set

    @Volatile private var cancelRequested = false
    @Volatile private var closed = false
    private var navStatus: DynRos.Subscription? = null
    private var navFeedback: DynRos.Subscription? = null

    private fun changed() = Platform.main.post { if (!closed) onChanged() }

    fun add(w: Waypoint, fixedFrame: String) {
        if (waypoints.isEmpty()) frame = fixedFrame.ifEmpty { "map" }
        waypoints = waypoints + w; changed()
    }
    fun undo() { waypoints = waypoints.dropLast(1); changed() }
    fun clear() { if (!running) { waypoints = emptyList(); current = -1; status = null; changed() } }

    // ---------------------------------------------------------------- save (with the tab)

    fun toJson(): String = JSONObject().put("frame", frame).put("mode", mode.name).put("loops", loops)
        .put("points", JSONArray().apply { for (w in waypoints) put(JSONArray().put(w.x).put(w.y).put(w.yaw)) }).toString()

    fun load(json: String?) {
        val o = runCatching { JSONObject(json ?: return) }.getOrNull() ?: return
        frame = o.optString("frame", "map").ifEmpty { "map" }
        mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.FOLLOW)
        loops = o.optInt("loops", 0).coerceIn(0, 100)
        val a = o.optJSONArray("points") ?: JSONArray()
        waypoints = (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { p -> Waypoint(p.optDouble(0), p.optDouble(1), p.optDouble(2)) } }
            .filter { !it.x.isNaN() && !it.y.isNaN() }
    }

    // ---------------------------------------------------------------- send

    /**
     * The action goal (JSON for [DynCodec.fromJson]). `newLayout`: Nav2 from Kilted onwards, where
     * NavigateThroughPoses carries the poses in a nav_msgs/Goals (header + list) instead of a bare list.
     */
    fun goalJson(nowNs: Long = 0, newLayout: Boolean = false): JSONObject {
        val poses = JSONArray()
        for (w in waypoints) poses.put(JSONObject()
            .put("header", header(nowNs))
            .put("pose", JSONObject()
                .put("position", JSONObject().put("x", w.x).put("y", w.y).put("z", 0.0))
                .put("orientation", JSONObject().put("x", 0.0).put("y", 0.0).put("z", Math.sin(w.yaw / 2)).put("w", Math.cos(w.yaw / 2)))))
        return when (mode) {
            Mode.FOLLOW -> JSONObject().put("poses", poses).put("number_of_loops", loops)
            Mode.THROUGH -> JSONObject().put("poses", if (newLayout) JSONObject().put("header", header(nowNs)).put("goals", poses) else poses)
        }
    }

    private fun header(nowNs: Long) = JSONObject().put("frame_id", frame).put("stamp", JSONObject().put("sec", nowNs / 1_000_000_000L).put("nanosec", nowNs % 1_000_000_000L))

    /** Nav2 definitions from Kilted onwards (the ones in the app package are Humble/Jazzy's). */
    internal val newRegistry: InterfaceRegistry by lazy {
        registry.copy().apply {
            define("nav2_msgs/msg/WaypointStatus", "uint8 PENDING=0\nuint8 COMPLETED=1\nuint8 SKIPPED=2\nuint8 FAILED=3\nuint8 waypoint_status\nuint32 waypoint_index\n" +
                "geometry_msgs/PoseStamped waypoint_pose\nuint16 error_code\nstring error_msg")
            define("nav2_msgs/action/FollowWaypoints", "uint32 number_of_loops\nuint32 goal_index 0\ngeometry_msgs/PoseStamped[] poses\n---\n" +
                "WaypointStatus[] missed_waypoints\nuint16 error_code\nstring error_msg\n---\nuint32 current_waypoint")
            define("nav2_msgs/action/NavigateThroughPoses", "nav_msgs/Goals poses\nstring behavior_tree\n---\n" +
                "uint16 error_code\nstring error_msg\nWaypointStatus[] waypoint_statuses\n---\n" +
                "geometry_msgs/PoseStamped current_pose\nbuiltin_interfaces/Duration navigation_time\nbuiltin_interfaces/Duration estimated_time_remaining\n" +
                "int16 number_of_recoveries\nfloat32 distance_remaining\nfloat32 position_tracking_error\nfloat32 heading_tracking_error\n" +
                "int16 number_of_poses_remaining\nWaypointStatus[] waypoint_statuses")
        }
    }

    fun start() {
        if (running || waypoints.isEmpty()) return
        running = true; cancelRequested = false; current = 0
        status = tr("nav2_sending", mode.action); changed()
        val total = waypoints.size
        val mode = this.mode
        Thread({
            try {
                // First the format that worked before (or the classic one); if the server does not reply, it does not understand it
                // (another Nav2 version: it drops the message) and the other one is tried.
                val layouts = if (preferNew == true) listOf(true, false) else listOf(false, true)
                var out: DynActionClient.Outcome? = null
                var lastError: Exception? = null
                for ((k, newLayout) in layouts.withIndex()) {
                    val reg = if (newLayout) newRegistry else registry
                    val spec = reg.action(mode.type)
                    val client = DynActionClient(mode.action, spec, reg.srv("action_msgs/srv/CancelGoal"))
                    try {
                        // Stamp 0: "the most recent TF" (like RViz's tools).
                        val goal = DynCodec.fromJson(spec.goal, goalJson(0, newLayout))
                        out = client.run(goal, if (k == 0) FIRST_ACCEPT_TIMEOUT_MS else ACCEPT_TIMEOUT_MS,
                            onAccepted = { preferNew = newLayout; status = tr("nav2_accepted"); changed() },
                            onFeedback = { fb -> onFeedback(fb, total); changed() }, cancelRequested = { cancelRequested || closed },
                            abandoned = { closed }, cancelIfAbandoned = { true })
                        break
                    } catch (e: com.juancams.rviz2mini.ros.dyn.CallException) {
                        lastError = e
                        // No server, no connection or cancelled: no point trying the other format.
                        if (e.message?.contains("did not respond") != true || cancelRequested || closed) break
                    } finally {
                        runCatching { client.close() }
                    }
                }
                status = out?.let { resultText(it, total) } ?: tr("nav2_failed", lastError?.message ?: "?")
            } catch (e: Exception) {
                status = tr("nav2_failed", e.message ?: e.javaClass.simpleName)
            } finally {
                running = false; current = -1; changed()
            }
        }, "nav2-mission").apply { isDaemon = true }.start()
    }

    fun cancel() { if (running) { cancelRequested = true; status = tr("nav2_canceling"); changed() } }

    private fun onFeedback(fb: MsgValue, total: Int) {
        when (mode) {
            Mode.FOLLOW -> {
                val i = (fb["current_waypoint"] as? Number)?.toInt() ?: 0
                current = i.coerceIn(0, total - 1)
                status = tr("nav2_waypoint", current + 1, total)
            }
            Mode.THROUGH -> {
                val left = (fb["number_of_poses_remaining"] as? Number)?.toInt() ?: total
                current = (total - left).coerceIn(0, total - 1)
                status = tr("nav2_waypoint", current + 1, total) + progress(fb)
            }
        }
    }

    private fun resultText(o: DynActionClient.Outcome, total: Int): String {
        val err = (o.result["error_code"] as? Number)?.toInt() ?: 0
        val msg = (o.result["error_msg"] as? String).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val missed = (o.result["missed_waypoints"] as? List<*>)?.size ?: 0
        return buildString {
            append(tr("nav2_result", o.statusLabel.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }))
            if (missed > 0) append(" · ").append(tr("nav2_missed", missed, total))
            if (err != 0) append(" · error $err")
            if (msg.isNotBlank()) append(" · ").append(msg)
        }
    }

    // ---------------------------------------------------------------- navigate_to_pose (the Goal tool)

    /** Progress of anyone's navigate_to_pose goal (e.g. the Goal tool): only if there is no route of ours. */
    @Volatile var goalStatus: String? = null; private set
    @Volatile private var goalState = 0
    @Volatile private var goalProgress = ""

    /** Starts listening to the Nav2 status (if the type is available). */
    fun watchNavigateToPose() {
        if (navStatus != null) return
        runCatching {
            val st = registry.msg("action_msgs/msg/GoalStatusArray")
            navStatus = DynRos.subscribe("/navigate_to_pose/_action/status", st, RosConnection.defaultQos()) { v ->
                val list = v["status_list"] as? List<*> ?: return@subscribe
                val last = list.lastOrNull() as? Map<*, *> ?: return@subscribe
                val s = (last["status"] as? Number)?.toInt() ?: 0
                if (s != goalState) {
                    goalState = s
                    if (s != 2) goalProgress = ""
                    updateGoalStatus(); changed()
                }
            }
            val a = registry.action("nav2_msgs/action/NavigateToPose")
            navFeedback = DynRos.subscribe("/navigate_to_pose/_action/feedback", a.feedbackMessage, RosConnection.defaultQos()) { v ->
                val fb = v["feedback"] as? MsgValue ?: return@subscribe
                goalProgress = progress(fb).removePrefix(" · ")
                if (goalState == 2) { updateGoalStatus(); changed() }
            }
        }
    }

    private fun updateGoalStatus() {
        val label = DynActionClient.STATUS.getOrElse(goalState) { "status $goalState" }.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
        goalStatus = if (goalState == 0) null else tr("nav2_goal_status", label) + if (goalProgress.isNotEmpty()) " · $goalProgress" else ""
    }

    /** " · 3.2 m · 12 s · 1 recovery" from the Nav2 feedback. */
    private fun progress(fb: MsgValue): String {
        val f = Locale.ROOT
        val parts = ArrayList<String>()
        (fb["distance_remaining"] as? Number)?.toDouble()?.takeIf { it > 0 }?.let { parts += String.format(f, "%.1f m", it) }
        (fb["estimated_time_remaining"] as? Map<*, *>)?.let { t -> ((t["sec"] as? Number)?.toLong() ?: 0L).takeIf { it > 0 }?.let { parts += "$it s" } }
        (fb["number_of_recoveries"] as? Number)?.toInt()?.takeIf { it > 0 }?.let { parts += tr("nav2_recoveries", it) }
        return parts.joinToString("") { " · $it" }
    }

    fun close() {
        closed = true
        navStatus?.close(); navFeedback?.close(); navStatus = null; navFeedback = null
    }

    companion object {
        private const val ACCEPT_TIMEOUT_MS = 8000L
        private const val FIRST_ACCEPT_TIMEOUT_MS = 4000L
        /** Which Nav2 format replied last time (null: not known yet). */
        @Volatile var preferNew: Boolean? = null
    }
}
