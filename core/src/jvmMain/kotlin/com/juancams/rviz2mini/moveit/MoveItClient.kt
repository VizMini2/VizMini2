// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.moveit

import com.juancams.rviz2mini.bag.BagRecorder
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosStrings
import com.juancams.rviz2mini.ros.dyn.CallException
import com.juancams.rviz2mini.ros.dyn.DynActionClient
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.rviz.geom.UrdfModel
import com.juancams.rviz2mini.rviz.geom.UrdfParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The MoveIt panel (like RViz's "Motion Planning", simplified): the robot and its groups come from
 * `/robot_description` and `/robot_description_semantic`, the current position from `/joint_states`, and the goal is given in
 * joints (sliders or a named state from the SRDF). It plans with the MoveGroup action (`/move_action`),
 * executes the plan with ExecuteTrajectory (`/execute_trajectory`) or does both at once. move_group
 * publishes the plan on `/display_planned_path`, which the 3D Trajectory display shows as a ghost robot.
 *
 * `ns`: the move_group namespace ("" normally). Changes are reported through `onChanged` on the main thread.
 */
class MoveItClient(private val registry: InterfaceRegistry, val ns: String = "", private val onChanged: () -> Unit = {}) {

    @Volatile var urdf: UrdfModel? = null; private set
    @Volatile var srdf: Srdf? = null; private set
    /** Current position of each joint (from /joint_states). */
    val current = java.util.concurrent.ConcurrentHashMap<String, Double>()
    @Volatile var hasJointStates = false; private set

    /** The selected group and the goal (joint → value). */
    var group: String? = null
        set(v) { field = v; target.clear(); groupJoints().forEach { j -> target[j] = current[j] ?: 0.0 }; goalChanged() }
    val target = java.util.concurrent.ConcurrentHashMap<String, Double>()
    /** Planner: pipeline ("ompl", "pilz_industrial_motion_planner"...) and planner ("RRTConnectkConfigDefault", "PTP"...); empty = MoveIt's default. */
    var pipelineId = ""
    var plannerId = ""
    /** The ones move_group offers (pipeline, planner), from `query_planner_interface`. */
    @Volatile var planners: List<Pair<String, String>> = emptyList(); private set
    /** Which moveit_msgs version move_group speaks ([MoveItMsgs]); AUTO: detect it. */
    var msgVersion = MoveItMsgs.AUTO
        set(v) { field = v; detected = null; probed = false }
    @Volatile private var probed = false
    /** The one that was detected (with AUTO). */
    @Volatile var detected: MoveItMsgs? = null; private set

    /** The versions to try, most likely first. */
    private fun candidates(): List<MoveItMsgs> = when {
        msgVersion != MoveItMsgs.AUTO -> listOf(msgVersion)
        detected == MoveItMsgs.V22 -> listOf(MoveItMsgs.V22)
        detected != null -> listOf(detected!!) + listOf(MoveItMsgs.V27, MoveItMsgs.V26).filter { it != detected }
        else -> listOf(MoveItMsgs.V27, MoveItMsgs.V26)
    }

    private fun reg(v: MoveItMsgs) = MoveItMsgs.registry(registry, v)

    /**
     * Tries each version until move_group answers (if it doesn't understand the format, it doesn't answer: the
     * message is dropped or its reply can't be read). `block` receives the registry and the timeout.
     */
    private fun <T> withVersions(firstTimeoutMs: Long, block: (InterfaceRegistry, Long) -> T): T {
        ensureDetected()
        val list = candidates()
        var last: Exception? = null
        for ((i, v) in list.withIndex()) {
            try {
                val r = block(reg(v), if (i < list.size - 1) firstTimeoutMs else maxOf(firstTimeoutMs, 10_000))
                if (msgVersion == MoveItMsgs.AUTO && detected != v) { detected = v; changed() }
                return r
            } catch (e: CallException) {
                last = e
                if (e.message?.contains("did not respond") != true || closed || cancelRequested) throw e
            }
        }
        throw last ?: CallException("?")
    }

    /**
     * With AUTO, the first time: is this a Humble MoveIt? A plan with no goal is requested from `plan_kinematic_path` (it answers
     * right away with an error): its reply ends in MoveItErrorCodes, which is shorter in Humble, so it can't be read
     * with the new definitions but can with the Humble ones.
     */
    private fun ensureDetected() {
        if (msgVersion != MoveItMsgs.AUTO || detected != null || probed) return
        val g = group ?: return
        probed = true
        for (v in listOf(MoveItMsgs.V27, MoveItMsgs.V22)) {
            val srv = runCatching { reg(v).srv("moveit_msgs/srv/GetMotionPlan") }.getOrNull() ?: return
            val client = com.juancams.rviz2mini.ros.dyn.DynServiceClient(name("/plan_kinematic_path"), srv)
            try {
                client.call(DynCodec.fromJson(srv.request, JSONObject().put("motion_plan_request", JSONObject()
                    .put("group_name", g).put("allowed_planning_time", 0.1).put("start_state", JSONObject().put("is_diff", true)))), 4000) { closed }
                if (v == MoveItMsgs.V22) { detected = MoveItMsgs.V22; changed() }
                return
            } catch (e: CallException) {
                if (e.message?.contains("did not respond") != true) return // no server: we'll see later
            } catch (_: Exception) {
                return
            } finally {
                client.close()
            }
        }
    }

    /** In a straight line (Cartesian path of the tip) instead of letting MoveIt choose the path. */
    var cartesian = false
    var velocityScaling = 0.3
    var accelerationScaling = 0.3
    var planningTimeS = 5.0

    @Volatile var busy = false; private set
    @Volatile var status: String? = null; private set
    /** The last planned trajectory (moveit_msgs/RobotTrajectory) and its duration, to execute it. */
    @Volatile var lastPlan: MsgValue? = null; private set
    @Volatile var lastPlanS: Double = 0.0; private set

    @Volatile private var cancelRequested = false
    @Volatile private var closed = false
    private val subs = ArrayList<DynRos.Subscription>()
    private var lastJointChange = 0L

    private fun changed() = Platform.main.post { if (!closed) onChanged() }
    private fun name(n: String) = if (ns.isBlank()) n else "/" + ns.trim('/') + n

    fun start() {
        if (subs.isNotEmpty()) return
        runCatching {
            val str = registry.msg("std_msgs/msg/String")
            subs += DynRos.subscribe(name("/robot_description"), str, BagRecorder.qos(true)) { v ->
                val xml = RosStrings.fix(v["data"] as? String ?: return@subscribe)
                runCatching { UrdfParser.parse(xml) }.getOrNull()?.let { u -> urdfs[u.name] = u; pickUrdf() }
            }
            subs += DynRos.subscribe(name("/robot_description_semantic"), str, BagRecorder.qos(true)) { v ->
                val xml = RosStrings.fix(v["data"] as? String ?: return@subscribe)
                runCatching { Srdf.parse(xml) }.getOrNull()?.let { srdf = it; pickUrdf() }
            }
            subs += DynRos.subscribe(name("/joint_states"), registry.msg("sensor_msgs/msg/JointState"), RosConnection.defaultQos()) { v ->
                val names = v["name"] as? List<*> ?: return@subscribe
                val pos = v["position"] as? List<*> ?: return@subscribe
                for (i in names.indices) {
                    val n = names[i] as? String ?: continue
                    (pos.getOrNull(i) as? Number)?.toDouble()?.let { current[n] = it }
                }
                val first = !hasJointStates
                hasJointStates = true
                if (first) { syncTargetToCurrent(); changed() }
                // The bar refreshes at most 5 times per second (joints arrive at 50-100 Hz).
                val now = System.currentTimeMillis()
                if (now - lastJointChange > 200) { lastJointChange = now; MoveItGoal.current = HashMap(current); changed() }
            }
        }.onFailure { status = it.message; changed() }
    }

    /**
     * Every robot description heard (by robot name): with several robots on the network (a mobile base and an arm...)
     * the one that goes with MoveIt is the one whose name is the SRDF's.
     */
    private val urdfs = java.util.concurrent.ConcurrentHashMap<String, UrdfModel>()

    /** The entries of `values` that are joints of the robot's URDF (all of them while the URDF is unknown). */
    fun robotJoints(values: Map<String, Double>): Map<String, Double> {
        val known = urdf?.joints?.map { it.name }?.toSet() ?: return values
        return values.filterKeys { it in known }
    }

    @Synchronized private fun pickUrdf() {
        val chosen = chooseUrdf(urdfs, srdf?.robot)
        if (chosen != null) urdf = chosen
        // Always: the SRDF may arrive after the URDF it goes with, and only then can the group be chosen.
        resetGroup()
        changed()
    }

    private fun resetGroup() {
        val s = srdf ?: return
        if (urdf == null) return
        if (group == null || s.group(group!!) == null) group = s.groups.firstOrNull { groupJointsOf(it.name).isNotEmpty() }?.name
    }

    private fun groupJointsOf(g: String): List<String> { val u = urdf ?: return emptyList(); return srdf?.groupJoints(g, u) ?: emptyList() }

    /** The joints of the selected group (the ones that move). */
    fun groupJoints(): List<String> = group?.let { groupJointsOf(it) } ?: emptyList()

    /** The groups that have something to move. */
    fun groups(): List<String> = srdf?.groups?.map { it.name }?.filter { groupJointsOf(it).isNotEmpty() } ?: emptyList()

    /** The named states of the selected group ("ready", "open"...). */
    fun namedStates(): List<String> = group?.let { g -> srdf?.statesOf(g)?.map { it.name } } ?: emptyList()

    /** Limits of a joint (from the URDF; ±π if it is continuous or has none). */
    fun limits(joint: String): Pair<Double, Double> {
        val j = urdf?.joints?.firstOrNull { it.name == joint }
        val lo = j?.lower?.takeIf { !it.isNaN() }; val hi = j?.upper?.takeIf { !it.isNaN() }
        return if (lo != null && hi != null && hi > lo) lo to hi else -Math.PI to Math.PI
    }

    fun setTarget(joint: String, value: Double) { val (lo, hi) = limits(joint); target[joint] = value.coerceIn(lo, hi); goalChanged() }
    fun syncTargetToCurrent() { for (j in groupJoints()) current[j]?.let { target[j] = it }; goalChanged() }
    fun applyNamedState(name: String) {
        val st = srdf?.statesOf(group ?: return)?.firstOrNull { it.name == name } ?: return
        for ((j, v) in st.values) if (j in groupJoints()) target[j] = v
        goalChanged()
    }

    /** The goal, for the blue ghost robot in 3D: the current joints with the goal ones on top. */
    private fun goalChanged() {
        MoveItGoal.set(HashMap<String, Double>(current).apply { putAll(target) })
        changed()
    }

    // ---------------------------------------------------------------- moving the gripper (IK)

    /** The tip link of the group (the gripper or the flange), or null if the group is not a chain. */
    fun tipLink(): String? {
        val g = group ?: return null
        srdf?.tipLink(g)?.let { return it }
        val last = groupJoints().lastOrNull() ?: return null
        return urdf?.joints?.firstOrNull { it.name == last }?.child
    }

    /** Where the tip ends up with the goal (relative to the URDF root link), or null. */
    fun tipPose(): DoubleArray? {
        val u = urdf ?: return null
        val tip = tipLink() ?: return null
        val all = HashMap<String, Double>(current).apply { putAll(target) }
        return com.juancams.rviz2mini.rviz.geom.UrdfKinematics(u).linkPoses(all)[tip]
    }

    /**
     * Moves the goal tip `dx, dy, dz` meters (robot axes) and rotates it `droll, dpitch, dyaw` radians (about
     * its own axes); the joints are computed by MoveIt (`/compute_ik`) starting from the current goal.
     */
    fun jog(dx: Double, dy: Double, dz: Double, droll: Double = 0.0, dpitch: Double = 0.0, dyaw: Double = 0.0) {
        val pose = tipPose() ?: run { if (tipLink() == null) { status = tr("moveit_no_tip"); changed() }; return }
        val rot = com.juancams.rviz2mini.rviz.geom.UrdfParser.poseFromXyzRpy(0.0, 0.0, 0.0, droll, dpitch, dyaw)
        val moved = com.juancams.rviz2mini.TfMath.multiply(pose, rot, DoubleArray(16))
        moved[12] += dx; moved[13] += dy; moved[14] += dz
        moveTipTo(moved)
    }

    /** The name of the URDF root link (the frame of [tipPose] and of the poses sent to MoveIt). */
    fun rootLink(): String? = urdf?.let { com.juancams.rviz2mini.rviz.geom.UrdfKinematics(it).root }

    /** Puts the goal tip at `moved` (relative to the root link): IK with MoveIt. `onDone(true)` if a solution was found. */
    fun moveTipTo(moved: DoubleArray, onDone: (Boolean) -> Unit = {}) {
        if (busy) return
        val tip = tipLink() ?: run { status = tr("moveit_no_tip"); changed(); return }
        val root = rootLink() ?: return
        busy = true
        Thread({
            try {
                val sol = solveIk(root, tip, moved)
                onDone(sol != null)
                if (sol == null) status = tr("moveit_no_ik") else {
                    for ((j, v) in sol) if (j in groupJoints()) target[j] = v
                    status = String.format(Locale.ROOT, tr("moveit_tip_at"), tip, moved[12], moved[13], moved[14])
                    MoveItGoal.set(HashMap<String, Double>(current).apply { putAll(target) })
                }
            } catch (e: Exception) {
                status = tr("moveit_failed", e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false; changed()
            }
        }, "moveit-ik").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- obstacles (planning scene)

    /** The boxes this panel has added (ids in the MoveIt scene). */
    val boxes = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** The scene with a box (or removing boxes): moveit_msgs/PlanningScene JSON as a diff. */
    fun boxScene(frame: String, ids: List<String>, at: DoubleArray?, size: Double, remove: Boolean): JSONObject {
        val objects = JSONArray()
        for (id in ids) {
            val o = JSONObject().put("header", JSONObject().put("frame_id", frame)).put("id", id).put("operation", if (remove) 1 else 0)
                .put("pose", JSONObject().put("orientation", JSONObject().put("w", 1.0)))
            if (!remove && at != null) {
                o.put("primitives", JSONArray().put(JSONObject().put("type", 1).put("dimensions", JSONArray().put(size).put(size).put(size))))
                o.put("primitive_poses", JSONArray().put(JSONObject()
                    .put("position", JSONObject().put("x", at[0]).put("y", at[1]).put("z", at[2])).put("orientation", JSONObject().put("w", 1.0))))
            }
            objects.put(o)
        }
        return JSONObject().put("scene", JSONObject().put("is_diff", true)
            .put("robot_state", JSONObject().put("is_diff", true))
            .put("world", JSONObject().put("collision_objects", objects)))
    }

    /**
     * A box of `size` m as an obstacle: halfway between the current tip and the goal tip (to see how the
     * plan avoids it) or, if they are close, 30 cm to one side of the tip.
     */
    fun addBox(size: Double = 0.1) {
        val u = urdf ?: return
        val tip = tipLink() ?: return
        val kin = com.juancams.rviz2mini.rviz.geom.UrdfKinematics(u)
        val now = kin.linkPoses(current)[tip] ?: return
        val goal = kin.linkPoses(HashMap<String, Double>(current).apply { putAll(target) })[tip] ?: return
        val d = Math.sqrt((0..2).sumOf { (goal[12 + it] - now[12 + it]).let { v -> v * v } })
        // Halfway only if there is room (otherwise it would touch the gripper and MoveIt would say it already collides); otherwise, to one side.
        val at = if (d >= MIN_GAP_FOR_MIDDLE) DoubleArray(3) { (now[12 + it] + goal[12 + it]) / 2 }
            else doubleArrayOf(now[12], now[13] + (if (now[13] >= 0) 0.3 else -0.3), now[14])
        val id = "vizmini2_box_${boxes.size + 1}"
        applyScene(boxScene(kin.root, listOf(id), at, size, remove = false)) { ok -> if (ok) boxes += id; tr(if (ok) "moveit_box_added" else "moveit_scene_failed", id) }
    }

    fun clearBoxes() {
        val u = urdf ?: return
        val ids = boxes.toList().ifEmpty { return }
        applyScene(boxScene(com.juancams.rviz2mini.rviz.geom.UrdfKinematics(u).root, ids, null, 0.0, remove = true)) { ok ->
            if (ok) boxes.removeAll(ids.toSet()); tr(if (ok) "moveit_boxes_cleared" else "moveit_scene_failed", ids.size.toString())
        }
    }

    private fun applyScene(scene: JSONObject, done: (Boolean) -> String) {
        if (busy) return
        busy = true
        Thread({
            try {
                val r = withVersions(5000) { reg, timeout ->
                    val srv = reg.srv("moveit_msgs/srv/ApplyPlanningScene")
                    val client = com.juancams.rviz2mini.ros.dyn.DynServiceClient(name("/apply_planning_scene"), srv)
                    try { client.call(DynCodec.fromJson(srv.request, scene), timeout) { closed } } finally { client.close() }
                }
                status = done(r["success"] == true)
            } catch (e: Exception) {
                status = tr("moveit_failed", e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false; changed()
            }
        }, "moveit-scene").apply { isDaemon = true }.start()
    }

    /** The `/compute_ik` request (JSON): the tip at `pose` (relative to `frame`), starting from the goal. */
    fun ikRequest(frame: String, tip: String, pose: DoubleArray): JSONObject {
        val q = com.juancams.rviz2mini.TfMath.toQuaternion(pose)
        // Only this robot's joints: /joint_states can also carry other robots' (MoveIt aborts on unknown joint names).
        val seed = robotJoints(HashMap<String, Double>(current).apply { putAll(target) })
        return JSONObject().put("ik_request", JSONObject()
            .put("group_name", group ?: "")
            .put("robot_state", JSONObject().put("joint_state", JSONObject()
                .put("name", JSONArray(seed.keys.toList())).put("position", JSONArray(seed.values.toList()))))
            .put("avoid_collisions", true)
            .put("ik_link_name", tip)
            .put("pose_stamped", JSONObject().put("header", JSONObject().put("frame_id", frame)).put("pose", JSONObject()
                .put("position", JSONObject().put("x", pose[12]).put("y", pose[13]).put("z", pose[14]))
                .put("orientation", JSONObject().put("x", q[0]).put("y", q[1]).put("z", q[2]).put("w", q[3]))))
            .put("timeout", JSONObject().put("sec", 0).put("nanosec", 200_000_000)))
    }

    private fun solveIk(frame: String, tip: String, pose: DoubleArray): Map<String, Double>? {
        val r = withVersions(4000) { reg, timeout ->
            val srv = reg.srv("moveit_msgs/srv/GetPositionIK")
            val client = com.juancams.rviz2mini.ros.dyn.DynServiceClient(name("/compute_ik"), srv)
            try { client.call(DynCodec.fromJson(srv.request, ikRequest(frame, tip, pose)), timeout) { closed } } finally { client.close() }
        }
        run {
            if (errorCode(r) != SUCCESS) return null
            val js = (r["solution"] as? Map<*, *>)?.get("joint_state") as? Map<*, *> ?: return null
            val n = js["name"] as? List<*> ?: return null; val p = js["position"] as? List<*> ?: return null
            return n.indices.mapNotNull { i -> (p.getOrNull(i) as? Number)?.toDouble()?.let { n[i].toString() to it } }.toMap()
        }
    }

    // ---------------------------------------------------------------- planning and executing

    /** The MoveGroup goal (JSON for [DynCodec.fromJson]): joint constraints over the current state. */
    fun moveGroupGoal(planOnly: Boolean): JSONObject {
        val constraints = JSONArray()
        for (j in groupJoints()) {
            val v = target[j] ?: continue
            constraints.put(JSONObject().put("joint_name", j).put("position", v).put("tolerance_above", 0.001).put("tolerance_below", 0.001).put("weight", 1.0))
        }
        val request = JSONObject()
            .put("group_name", group ?: "")
            .put("pipeline_id", pipelineId)
            .put("planner_id", plannerId)
            .put("num_planning_attempts", 3)
            .put("allowed_planning_time", planningTimeS)
            .put("max_velocity_scaling_factor", velocityScaling)
            .put("max_acceleration_scaling_factor", accelerationScaling)
            .put("start_state", JSONObject().put("is_diff", true))
            .put("goal_constraints", JSONArray().put(JSONObject().put("name", "vizmini2").put("joint_constraints", constraints)))
        val options = JSONObject().put("plan_only", planOnly)
            .put("planning_scene_diff", JSONObject().put("is_diff", true).put("robot_state", JSONObject().put("is_diff", true)))
        return JSONObject().put("request", request).put("planning_options", options)
    }

    fun plan() = run(planOnly = true)
    fun planAndExecute() = run(planOnly = false)

    private fun run(planOnly: Boolean) {
        if (busy || group == null) return
        if (cartesian) { runCartesian(planOnly); return }
        busy = true; cancelRequested = false
        status = tr(if (planOnly) "moveit_planning" else "moveit_moving"); changed()
        Thread({
            try {
                val out = withVersions(4000) { reg, timeout ->
                    val spec = reg.action("moveit_msgs/action/MoveGroup")
                    val client = DynActionClient(name("/move_action"), spec, reg.srv("action_msgs/srv/CancelGoal"))
                    try {
                        client.run(DynCodec.fromJson(spec.goal, moveGroupGoal(planOnly)), timeout, onAccepted = {}, onFeedback = { fb ->
                            (fb["state"] as? String)?.takeIf { it.isNotBlank() }?.let { status = tr("moveit_state", it); changed() }
                        }, cancelRequested = { cancelRequested || closed }, abandoned = { closed }, cancelIfAbandoned = { true })
                    } finally { runCatching { client.close() } }
                }
                val code = errorCode(out.result)
                val traj = out.result["planned_trajectory"] as? MsgValue
                val secs = trajectoryDuration(traj)
                // Only planned: kept for Execute. Planned and executed: no longer valid (the robot is no longer at the start).
                if (code == SUCCESS && traj != null && planOnly) { lastPlan = traj; lastPlanS = secs } else if (!planOnly) lastPlan = null
                val f = Locale.ROOT
                status = if (code == SUCCESS) {
                    val points = ((traj?.get("joint_trajectory") as? MsgValue)?.get("points") as? List<*>)?.size ?: 0
                    tr(if (planOnly) "moveit_planned" else "moveit_done", String.format(f, "%.1f", secs), points,
                        String.format(f, "%.2f", (out.result["planning_time"] as? Number)?.toDouble() ?: 0.0))
                } else tr("moveit_failed", errorName(code)) + if (plannerId.isNotEmpty() && code == -1) " · " + tr("moveit_try_default_planner") else ""
            } catch (e: Exception) {
                status = tr("moveit_failed", e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false; changed()
            }
        }, "moveit").apply { isDaemon = true }.start()
    }

    /** Executes the last planned one. */
    fun execute() {
        val traj = lastPlan ?: return
        if (busy) return
        busy = true; cancelRequested = false
        status = tr("moveit_executing"); changed()
        Thread({
            try {
                val code = executeNow(traj)
                status = if (code == SUCCESS) tr("moveit_executed") else tr("moveit_failed", errorName(code))
                if (code == SUCCESS) lastPlan = null
            } catch (e: Exception) {
                status = tr("moveit_failed", e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false; changed()
            }
        }, "moveit-exec").apply { isDaemon = true }.start()
    }

    /** ExecuteTrajectory with a moveit_msgs/RobotTrajectory; blocks and returns the MoveIt code. */
    private fun executeNow(traj: MsgValue): Int = withVersions(4000) { reg, timeout ->
        val spec = reg.action("moveit_msgs/action/ExecuteTrajectory")
        val client = DynActionClient(name("/execute_trajectory"), spec, reg.srv("action_msgs/srv/CancelGoal"))
        try {
            val goal = DynCodec.defaultValue(spec.goal).apply { put("trajectory", DynCodec.deepCopy(traj)) }
            val out = client.run(goal, timeout, onAccepted = {}, onFeedback = { fb ->
                (fb["state"] as? String)?.takeIf { it.isNotBlank() }?.let { status = tr("moveit_state", it); changed() }
            }, cancelRequested = { cancelRequested || closed }, abandoned = { closed }, cancelIfAbandoned = { true })
            errorCode(out.result)
        } finally {
            runCatching { client.close() }
        }
    }

    // ---------------------------------------------------------------- in a straight line

    /** The `/compute_cartesian_path` request (JSON): from the current tip to the goal tip, in 1 cm segments. */
    fun cartesianRequest(frame: String, tip: String, goal: DoubleArray): JSONObject {
        val q = com.juancams.rviz2mini.TfMath.toQuaternion(goal)
        return JSONObject()
            .put("header", JSONObject().put("frame_id", frame))
            .put("start_state", JSONObject().put("is_diff", true))
            .put("group_name", group ?: "")
            .put("link_name", tip)
            .put("waypoints", JSONArray().put(JSONObject()
                .put("position", JSONObject().put("x", goal[12]).put("y", goal[13]).put("z", goal[14]))
                .put("orientation", JSONObject().put("x", q[0]).put("y", q[1]).put("z", q[2]).put("w", q[3]))))
            .put("max_step", 0.01)
            .put("jump_threshold", 0.0)
            .put("avoid_collisions", true)
            .put("max_velocity_scaling_factor", velocityScaling)
            .put("max_acceleration_scaling_factor", accelerationScaling)
    }

    private var displayPub: DynRos.Publisher? = null

    /** What was planned here (in a straight line) to the planned-path topic, so the Trajectory display shows it. */
    private fun publishDisplay(traj: MsgValue) = runCatching {
        val spec = registry.msg("moveit_msgs/msg/DisplayTrajectory")
        val pub = displayPub ?: DynRos.publisher(name("/display_planned_path"), spec, RosConnection.defaultQos()).also { displayPub = it }
        val names = robotJoints(current).keys.toList()
        val msg = DynCodec.fromJson(spec, JSONObject().put("model_id", urdf?.name.orEmpty())
            .put("trajectory_start", JSONObject().put("joint_state", JSONObject().put("name", JSONArray(names)).put("position", JSONArray(names.map { current[it] ?: 0.0 })))))
        msg["trajectory"] = arrayListOf<Any?>(DynCodec.deepCopy(traj))
        pub.publish(msg)
    }

    private fun runCartesian(planOnly: Boolean) {
        val tip = tipLink() ?: run { status = tr("moveit_no_tip"); changed(); return }
        val root = rootLink() ?: return
        val goal = tipPose() ?: return
        busy = true; cancelRequested = false
        status = tr("moveit_planning"); changed()
        Thread({
            try {
                val r = withVersions(15_000) { reg, timeout ->
                    val srv = reg.srv("moveit_msgs/srv/GetCartesianPath")
                    val client = com.juancams.rviz2mini.ros.dyn.DynServiceClient(name("/compute_cartesian_path"), srv)
                    try { client.call(DynCodec.fromJson(srv.request, cartesianRequest(root, tip, goal)), timeout) { closed || cancelRequested } } finally { client.close() }
                }
                val fraction = (r["fraction"] as? Number)?.toDouble() ?: 0.0
                val traj = r["solution"] as? MsgValue
                val f = Locale.ROOT
                if (traj == null || fraction < 0.999) {
                    status = tr("moveit_line_partial", String.format(f, "%.0f", fraction * 100)); return@Thread
                }
                publishDisplay(traj)
                val secs = trajectoryDuration(traj)
                val points = ((traj["joint_trajectory"] as? MsgValue)?.get("points") as? List<*>)?.size ?: 0
                if (planOnly) {
                    lastPlan = traj; lastPlanS = secs
                    status = tr("moveit_line_planned", String.format(f, "%.1f", secs), points)
                } else {
                    lastPlan = null
                    status = tr("moveit_executing"); changed()
                    val code = executeNow(traj)
                    status = if (code == SUCCESS) tr("moveit_line_done", String.format(f, "%.1f", secs), points) else tr("moveit_failed", errorName(code))
                }
            } catch (e: Exception) {
                status = tr("moveit_failed", e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false; changed()
            }
        }, "moveit-line").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- planners

    /** Asks move_group which planners it has (`query_planner_interface`). */
    fun queryPlanners() {
        Thread({
            val srv = runCatching { registry.srv("moveit_msgs/srv/QueryPlannerInterfaces") }.getOrNull() ?: return@Thread
            val client = com.juancams.rviz2mini.ros.dyn.DynServiceClient(name("/query_planner_interface"), srv)
            try {
                val r = client.call(MsgValue(), 5000) { closed }
                val out = ArrayList<Pair<String, String>>()
                for (d in (r["planner_interfaces"] as? List<*>).orEmpty()) {
                    val m = d as? Map<*, *> ?: continue
                    val pipeline = (m["pipeline_id"] as? String).orEmpty().ifEmpty { (m["name"] as? String).orEmpty() }
                    for (pid in (m["planner_ids"] as? List<*>).orEmpty()) out += pipeline to pid.toString()
                }
                planners = out; changed()
            } catch (_: Exception) {
            } finally { client.close() }
        }, "moveit-planners").apply { isDaemon = true }.start()
    }

    fun stop() { if (busy) { cancelRequested = true; status = tr("moveit_stopping"); changed() } }

    fun close() {
        closed = true
        displayPub?.let { runCatching { it.close() } }; displayPub = null
        subs.forEach { runCatching { it.close() } }; subs.clear()
    }

    companion object {
        const val SUCCESS = 1
        /** Distance between the tip and the goal above which a box fits in between. */
        const val MIN_GAP_FOR_MIDDLE = 0.35
        private val ERRORS = mapOf(
            1 to "SUCCESS", 99999 to "FAILURE", -1 to "PLANNING_FAILED", -2 to "INVALID_MOTION_PLAN",
            -3 to "MOTION_PLAN_INVALIDATED_BY_ENVIRONMENT_CHANGE", -4 to "CONTROL_FAILED", -5 to "UNABLE_TO_AQUIRE_SENSOR_DATA",
            -6 to "TIMED_OUT", -7 to "PREEMPTED", -10 to "START_STATE_IN_COLLISION", -11 to "START_STATE_VIOLATES_PATH_CONSTRAINTS",
            -12 to "GOAL_IN_COLLISION", -13 to "GOAL_VIOLATES_PATH_CONSTRAINTS", -14 to "GOAL_CONSTRAINTS_VIOLATED",
            -15 to "INVALID_GROUP_NAME", -16 to "INVALID_GOAL_CONSTRAINTS", -17 to "INVALID_ROBOT_STATE", -18 to "INVALID_LINK_NAME",
            -19 to "INVALID_OBJECT_NAME", -21 to "FRAME_TRANSFORM_FAILURE", -22 to "COLLISION_CHECKING_UNAVAILABLE",
            -23 to "ROBOT_STATE_STALE", -24 to "SENSOR_INFO_STALE", -25 to "COMMUNICATION_FAILURE", -26 to "START_STATE_INVALID",
            -27 to "GOAL_STATE_INVALID", -28 to "UNRECOGNIZED_GOAL_TYPE", -29 to "CRASH", -30 to "ABORT", -31 to "NO_IK_SOLUTION",
        )
        fun errorName(code: Int) = ERRORS[code] ?: "error $code"

        /** The robot description that goes with MoveIt: the one named as the SRDF's robot; any single one otherwise. */
        fun chooseUrdf(urdfs: Map<String, UrdfModel>, srdfRobot: String?): UrdfModel? =
            if (srdfRobot != null) urdfs[srdfRobot] ?: urdfs.values.singleOrNull() else urdfs.values.lastOrNull()
        fun errorCode(result: MsgValue): Int = ((result["error_code"] as? Map<*, *>)?.get("val") as? Number)?.toInt() ?: 0

        /** Duration (s) of a moveit_msgs/RobotTrajectory: the time_from_start of the last point. */
        fun trajectoryDuration(traj: Map<*, *>?): Double {
            val pts = (traj?.get("joint_trajectory") as? Map<*, *>)?.get("points") as? List<*> ?: return 0.0
            val t = (pts.lastOrNull() as? Map<*, *>)?.get("time_from_start") as? Map<*, *> ?: return 0.0
            return ((t["sec"] as? Number)?.toDouble() ?: 0.0) + ((t["nanosec"] as? Number)?.toDouble() ?: 0.0) / 1e9
        }
    }
}
