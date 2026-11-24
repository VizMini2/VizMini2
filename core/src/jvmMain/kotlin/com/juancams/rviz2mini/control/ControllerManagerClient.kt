// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.control

import com.juancams.rviz2mini.params.ParamClient
import com.juancams.rviz2mini.ros.dyn.CallException
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * A ros2_control controller manager (like `ros2 control`): its controllers and their state, switching them on and
 * off, loading, configuring and unloading them, and its hardware components and interfaces.
 *
 * Foxy, Humble, Jazzy and Kilted/Lyrical speak different controller_manager_msgs ([ControlMsgs]): with AUTO the
 * version is found from the first non-empty controller list (a reply in another format either cannot be read, and
 * is dropped, or reads as nonsense, which [plausible] rejects).
 *
 * The calls block: run them off the main thread. `manager`: the node name ("/controller_manager").
 */
class ControllerManagerClient(private val registry: InterfaceRegistry, manager: String = DEFAULT_MANAGER) {

    val manager: String = normalize(manager)

    /** Which controller_manager_msgs the manager speaks; AUTO: detect it. */
    var msgVersion = ControlMsgs.AUTO
        set(v) { field = v; detected = null; hardwareProbed = false }
    /** The one that was detected (with AUTO). */
    @Volatile var detected: ControlMsgs? = null; private set
    /** Kilted and Jazzy only differ in the hardware components: told apart on the first such call. */
    @Volatile private var hardwareProbed = false
    /** The version in use: the chosen one, the detected one or (until then) Lyrical's. */
    val version: ControlMsgs get() = if (msgVersion != ControlMsgs.AUTO) msgVersion else detected ?: ControlMsgs.KILTED

    @Volatile private var closed = false
    private val clients = HashMap<String, DynServiceClient>()

    data class Controller(
        val name: String, val state: String, val type: String,
        val claimed: List<String> = emptyList(), val requiredCommand: List<String> = emptyList(),
        val requiredState: List<String> = emptyList(), val isChainable: Boolean = false, val isChained: Boolean = false,
        /** Hz (Jazzy and newer; null before). */
        val updateRate: Int? = null,
    ) {
        val active get() = state == "active"
        /** The command interfaces it uses ("joint1/position"...), in the controller's order when known. */
        val commandInterfaces: List<String> get() = requiredCommand.ifEmpty { claimed }
        /** The joints it commands, in order. */
        val joints: List<String> get() = commandInterfaces.map { it.substringBeforeLast('/') }.distinct()
        /** "position", "velocity", "effort"... (the first command interface's). */
        val commandKind: String? get() = commandInterfaces.firstOrNull()?.substringAfterLast('/', "")?.ifEmpty { null }
        val kind: ControllerCommands.Kind get() = ControllerCommands.kind(type)
    }

    data class Interface(val name: String, val claimed: Boolean, val available: Boolean? = null, val dataType: String? = null)

    data class HardwareComponent(
        val name: String, val type: String, val plugin: String, val stateId: Int, val stateLabel: String,
        val command: List<Interface>, val state: List<Interface>,
    ) {
        val active get() = stateId == STATE_ACTIVE
    }

    data class SwitchResult(val ok: Boolean, val message: String)

    private fun client(service: String, type: String, v: ControlMsgs): DynServiceClient = synchronized(clients) {
        clients.getOrPut("$service|${v.name}") {
            DynServiceClient("$manager/$service", ControlMsgs.registry(registry, v).srv("controller_manager_msgs/srv/$type"))
        }
    }

    private fun call(service: String, type: String, v: ControlMsgs, request: JSONObject?, timeoutMs: Long): MsgValue {
        if (closed) throw CallException("Canceled")
        val c = client(service, type, v)
        return c.call(DynCodec.fromJson(c.srv.request, request ?: JSONObject()), timeoutMs) { closed }
    }

    /** True if the manager is there (its list_controllers service has a server). */
    fun hasServer(): Boolean = runCatching { client("list_controllers", "ListControllers", version).hasServer() }.getOrDefault(false)

    /** The versions to try, the most likely first. */
    private fun candidates(): List<ControlMsgs> = when {
        msgVersion != ControlMsgs.AUTO -> listOf(msgVersion)
        detected != null -> listOf(detected!!)
        else -> listOf(ControlMsgs.KILTED, ControlMsgs.HUMBLE, ControlMsgs.FOXY)
    }

    /** The loaded controllers. Throws [CallException] if the manager does not answer. */
    fun listControllers(timeoutMs: Long = 3000): List<Controller> {
        var last: Exception? = null
        for ((i, v) in candidates().withIndex()) {
            try {
                val list = parseControllers(call("list_controllers", "ListControllers", v, null, if (i == 0) timeoutMs else 2000))
                if (!plausible(list)) { last = CallException("list_controllers: unreadable reply"); continue }
                if (msgVersion == ControlMsgs.AUTO && detected == null && list.isNotEmpty()) { detected = v; hardwareProbed = v != ControlMsgs.KILTED }
                return list
            } catch (e: Exception) {
                last = e
                if (closed) throw e
                // Nobody there: no point in trying the other formats.
                if (i == 0 && !hasServer()) throw CallException("$manager: no controller manager")
            }
        }
        throw (last as? CallException) ?: CallException(last?.message ?: "?")
    }

    /** The hardware components (empty on Foxy, which does not have them). */
    fun listHardwareComponents(timeoutMs: Long = 3000): List<HardwareComponent> {
        if (!version.hasHardwareComponents) return emptyList()
        val order = if (msgVersion == ControlMsgs.AUTO && !hardwareProbed && version == ControlMsgs.KILTED)
            listOf(ControlMsgs.KILTED, ControlMsgs.JAZZY) else listOf(version)
        var last: Exception? = null
        for (v in order) {
            try {
                val list = parseComponents(call("list_hardware_components", "ListHardwareComponents", v, null, timeoutMs))
                if (list.any { it.stateId !in 0..LAST_STATE_ID || it.name.isBlank() }) { last = CallException("list_hardware_components: unreadable reply"); continue }
                if (msgVersion == ControlMsgs.AUTO && list.isNotEmpty()) { detected = v; hardwareProbed = true }
                return list
            } catch (e: Exception) { last = e; if (closed) throw e }
        }
        throw (last as? CallException) ?: CallException(last?.message ?: "?")
    }

    /** All the command and state interfaces of the hardware. */
    fun listHardwareInterfaces(timeoutMs: Long = 3000): Pair<List<Interface>, List<Interface>> {
        val r = call("list_hardware_interfaces", "ListHardwareInterfaces", version, null, timeoutMs)
        return parseInterfaces(r["command_interfaces"]) to parseInterfaces(r["state_interfaces"])
    }

    /** Activates and deactivates controllers in one go (strict: all or nothing). */
    fun switchControllers(activate: List<String>, deactivate: List<String>, strict: Boolean = true, timeoutMs: Long = 10_000): SwitchResult {
        val v = version
        val r = call("switch_controller", "SwitchController", v, switchRequest(v, activate, deactivate, strict), timeoutMs)
        return SwitchResult(r["ok"] == true, (r["message"] as? String).orEmpty())
    }

    fun load(name: String) = simple("load_controller", "LoadController", name)
    fun configure(name: String) = simple("configure_controller", "ConfigureController", name)
    fun unload(name: String) = simple("unload_controller", "UnloadController", name)

    private fun simple(service: String, type: String, name: String): Boolean =
        call(service, type, version, JSONObject().put("name", name), 10_000)["ok"] == true

    /** Activates or deactivates a hardware component (Humble and newer). */
    fun setHardwareActive(name: String, active: Boolean): Boolean {
        val target = if (active) JSONObject().put("id", STATE_ACTIVE).put("label", "active") else JSONObject().put("id", STATE_INACTIVE).put("label", "inactive")
        return call("set_hardware_component_state", "SetHardwareComponentState", version,
            JSONObject().put("name", name).put("target_state", target), 10_000)["ok"] == true
    }

    /**
     * The controllers declared in the manager's parameters (`<name>.type`) but not loaded: those that
     * [load] can load. Empty if the parameters cannot be read.
     */
    fun configuredButNotLoaded(loaded: Collection<String>): List<Pair<String, String>> {
        val params = runCatching { ParamClient(manager, registry).let { p -> try { p.all() } finally { p.close() } } }.getOrNull() ?: return emptyList()
        return declaredControllers(params.map { it.descriptor.name to (it.value.value?.toString() ?: "") }).filter { it.first !in loaded }
    }

    fun close() {
        closed = true
        synchronized(clients) { clients.values.forEach { runCatching { it.close() } }; clients.clear() }
    }

    companion object {
        const val DEFAULT_MANAGER = "/controller_manager"
        const val STATE_UNCONFIGURED = 1
        const val STATE_INACTIVE = 2
        const val STATE_ACTIVE = 3
        const val STATE_FINALIZED = 4
        private const val LAST_STATE_ID = 4
        private const val STRICT = 2
        private const val BEST_EFFORT = 1
        private val STATES = setOf("unconfigured", "inactive", "active", "finalized", "unknown")
        private val NAME_CHARS = Regex("""[A-Za-z0-9_/~.\-]+""")

        fun normalize(name: String): String = "/" + name.trim().trim('/').ifEmpty { DEFAULT_MANAGER.trim('/') }

        /** The request of switch_controller in version `v`'s fields. */
        fun switchRequest(v: ControlMsgs, activate: List<String>, deactivate: List<String>, strict: Boolean): JSONObject {
            val r = JSONObject().put("strictness", if (strict) STRICT else BEST_EFFORT)
                .put("timeout", JSONObject().put("sec", 5).put("nanosec", 0))
            if (v == ControlMsgs.FOXY) r.put("start_controllers", JSONArray(activate)).put("stop_controllers", JSONArray(deactivate)).put("start_asap", true)
            else r.put("activate_controllers", JSONArray(activate)).put("deactivate_controllers", JSONArray(deactivate)).put("activate_asap", true)
            return r
        }

        private fun strings(v: Any?): List<String> = (v as? List<*>).orEmpty().map { it.toString() }

        fun parseControllers(r: MsgValue): List<Controller> = (r["controller"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map { m ->
            Controller(
                name = m["name"]?.toString().orEmpty(), state = m["state"]?.toString().orEmpty(), type = m["type"]?.toString().orEmpty(),
                claimed = strings(m["claimed_interfaces"]), requiredCommand = strings(m["required_command_interfaces"]),
                requiredState = strings(m["required_state_interfaces"]),
                isChainable = m["is_chainable"] == true, isChained = m["is_chained"] == true,
                updateRate = (m["update_rate"] as? Number)?.toInt(),
            )
        }

        /** A list read with the right definitions: known states, names and types that look like names. */
        fun plausible(list: List<Controller>): Boolean = list.all { c ->
            c.name.isNotEmpty() && NAME_CHARS.matches(c.name) && c.state in STATES && (c.type.contains('/') || c.type.contains("::")) &&
                (c.claimed + c.requiredCommand + c.requiredState).all { it.isNotEmpty() && it.length < 256 && it.none { ch -> ch < ' ' } }
        }

        fun parseInterfaces(v: Any?): List<Interface> = (v as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map { m ->
            Interface(m["name"]?.toString().orEmpty(), m["is_claimed"] == true, m["is_available"] as? Boolean, (m["data_type"] as? String)?.ifEmpty { null })
        }

        fun parseComponents(r: MsgValue): List<HardwareComponent> = (r["component"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map { m ->
            val st = m["state"] as? Map<*, *>
            HardwareComponent(
                name = m["name"]?.toString().orEmpty(), type = m["type"]?.toString().orEmpty(),
                plugin = (m["plugin_name"] as? String)?.ifEmpty { null } ?: (m["class_type"] as? String).orEmpty(),
                stateId = (st?.get("id") as? Number)?.toInt() ?: 0, stateLabel = st?.get("label")?.toString().orEmpty(),
                command = parseInterfaces(m["command_interfaces"]), state = parseInterfaces(m["state_interfaces"]),
            )
        }

        /** (name, type) of each `<name>.type` parameter of a controller manager (the controllers it knows). */
        fun declaredControllers(params: List<Pair<String, String>>): List<Pair<String, String>> =
            params.filter { (n, v) -> n.endsWith(".type") && n.count { it == '.' } == 1 && v.contains('/') }
                .map { (n, v) -> n.removeSuffix(".type") to v.trim('"') }.sortedBy { it.first }
    }
}
