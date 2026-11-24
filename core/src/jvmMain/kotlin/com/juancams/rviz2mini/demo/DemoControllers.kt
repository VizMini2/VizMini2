// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.demo

import com.juancams.rviz2mini.ros.dyn.MsgValue

/**
 * The demo robot's ros2_control controller manager (/controller_manager, Lyrical's messages), to try the
 * Controllers tab without a robot: joint_state_broadcaster and diff_drive_controller active (deactivating it stops
 * the robot), a wheel velocity controller inactive (it uses the same wheels) and a spare one declared but not
 * loaded; one hardware component with the wheels' interfaces.
 */
internal class DemoControllers(
    private val robot: DemoRobot,
    private val serve: (String, String, (MsgValue, (MsgValue) -> Unit) -> Unit) -> Unit,
) {
    private class Ctl(val name: String, val type: String, @Volatile var state: String, val command: List<String>, val stateIfaces: List<String>)

    private val wheelsVel = listOf("left_wheel_joint/velocity", "right_wheel_joint/velocity")
    private val wheelsState = listOf("left_wheel_joint/position", "left_wheel_joint/velocity", "right_wheel_joint/position", "right_wheel_joint/velocity")
    private val controllers = LinkedHashMap<String, Ctl>().apply {
        put("joint_state_broadcaster", Ctl("joint_state_broadcaster", "joint_state_broadcaster/JointStateBroadcaster", "active", emptyList(), wheelsState))
        put("diff_drive_controller", Ctl("diff_drive_controller", "diff_drive_controller/DiffDriveController", "active", wheelsVel, wheelsState))
        put("wheel_velocity_controller", Ctl("wheel_velocity_controller", "forward_command_controller/ForwardCommandController", "inactive", wheelsVel, emptyList()))
    }
    /** Declared in the parameters but not loaded (the Load button). */
    private val declared = mapOf("spare_controller" to "forward_command_controller/ForwardCommandController")
    @Volatile private var hardwareActive = true

    private fun claimed(c: Ctl) = if (c.state == "active") c.command else emptyList()

    fun start() {
        serve("$MANAGER/list_controllers", "controller_manager_msgs/srv/ListControllers") { _, reply ->
            reply(MsgValue().apply {
                put("controller", synchronized(controllers) { controllers.values.toList() }.map { c ->
                    MsgValue().apply {
                        put("name", c.name); put("state", c.state); put("type", c.type); put("is_async", false); put("update_rate", 100L)
                        put("claimed_interfaces", claimed(c)); put("required_command_interfaces", c.command); put("required_state_interfaces", c.stateIfaces)
                    }
                })
            })
        }
        serve("$MANAGER/switch_controller", "controller_manager_msgs/srv/SwitchController") { req, reply ->
            val on = (req["activate_controllers"] as? List<*>).orEmpty().map { it.toString() }
            val off = (req["deactivate_controllers"] as? List<*>).orEmpty().map { it.toString() }
            val error = synchronized(controllers) { switch(on, off) }
            robot.later(300) { reply(MsgValue().apply { put("ok", error == null); put("message", error ?: "") }) }
        }
        for ((service, type) in listOf("load_controller" to "LoadController", "configure_controller" to "ConfigureController", "unload_controller" to "UnloadController")) {
            serve("$MANAGER/$service", "controller_manager_msgs/srv/$type") { req, reply ->
                val name = req["name"]?.toString().orEmpty()
                val ok = synchronized(controllers) {
                    val c = controllers[name]
                    when (service) {
                        "load_controller" -> (c == null && name in declared).also { if (it) controllers[name] = Ctl(name, declared.getValue(name), "unconfigured", listOf("left_wheel_joint/velocity"), emptyList()) }
                        "configure_controller" -> (c?.state == "unconfigured").also { if (it) c!!.state = "inactive" }
                        else -> (c != null && c.state != "active").also { if (it) controllers.remove(name) }
                    }
                }
                robot.later(200) { reply(MsgValue().apply { put("ok", ok) }) }
            }
        }
        serve("$MANAGER/list_hardware_components", "controller_manager_msgs/srv/ListHardwareComponents") { _, reply ->
            val claimedNow = synchronized(controllers) { controllers.values.flatMap { claimed(it) }.toSet() }
            fun iface(n: String) = MsgValue().apply { put("name", n); put("data_type", "double"); put("is_available", hardwareActive); put("is_claimed", n in claimedNow) }
            reply(MsgValue().apply {
                put("component", listOf(MsgValue().apply {
                    put("name", HARDWARE); put("type", "system"); put("is_async", false); put("rw_rate", 100L); put("plugin_name", "mock_components/GenericSystem")
                    put("state", MsgValue().apply { put("id", if (hardwareActive) 3L else 2L); put("label", if (hardwareActive) "active" else "inactive") })
                    put("command_interfaces", wheelsVel.map { iface(it) }); put("state_interfaces", wheelsState.map { iface(it) })
                }))
            })
        }
        serve("$MANAGER/set_hardware_component_state", "controller_manager_msgs/srv/SetHardwareComponentState") { req, reply ->
            val target = ((req["target_state"] as? Map<*, *>)?.get("id") as? Number)?.toInt()
            val ok = req["name"] == HARDWARE && (target == 2 || target == 3)
            if (ok) {
                hardwareActive = target == 3
                // Without hardware nothing can move: its controllers go inactive.
                if (!hardwareActive) synchronized(controllers) { controllers.values.filter { it.command.isNotEmpty() }.forEach { it.state = "inactive" } }
                updateRobot()
            }
            robot.later(300) {
                reply(MsgValue().apply { put("ok", ok); put("state", MsgValue().apply { put("id", if (hardwareActive) 3L else 2L); put("label", if (hardwareActive) "active" else "inactive") }) })
            }
        }
        // The parameters, for the controllers declared but not loaded.
        serve("$MANAGER/list_parameters", "rcl_interfaces/srv/ListParameters") { _, reply ->
            val names = listOf("update_rate") + (controllers.keys + declared.keys).map { "$it.type" }
            reply(MsgValue().apply { put("result", MsgValue().apply { put("names", names); put("prefixes", emptyList<String>()) }) })
        }
        serve("$MANAGER/describe_parameters", "rcl_interfaces/srv/DescribeParameters") { req, reply ->
            reply(MsgValue().apply {
                put("descriptors", (req["names"] as? List<*>).orEmpty().map { n -> MsgValue().apply { put("name", n.toString()); put("type", if (n == "update_rate") 2L else 4L) } })
            })
        }
        serve("$MANAGER/get_parameters", "rcl_interfaces/srv/GetParameters") { req, reply ->
            val types = synchronized(controllers) { controllers.mapValues { it.value.type } } + declared
            reply(MsgValue().apply {
                put("values", (req["names"] as? List<*>).orEmpty().map { n ->
                    val s = n.toString()
                    if (s == "update_rate") MsgValue().apply { put("type", 2L); put("integer_value", 100L) }
                    else MsgValue().apply { put("type", 4L); put("string_value", types[s.removeSuffix(".type")] ?: "") }
                })
            })
        }
    }

    /** Applies a switch like a strict manager: null if done, the reason otherwise. */
    private fun switch(on: List<String>, off: List<String>): String? {
        if (!hardwareActive && on.isNotEmpty()) return "hardware component $HARDWARE is not active"
        for (n in on + off) if (n !in controllers) return "controller $n is not loaded"
        val after = controllers.values.associate { it.name to (if (it.name in on) "active" else if (it.name in off) "inactive" else it.state) }
        for (n in on) if (controllers.getValue(n).state == "unconfigured") return "controller $n is not configured"
        // Two active controllers cannot claim the same command interface.
        val claims = controllers.values.filter { after[it.name] == "active" }.flatMap { it.command }
        if (claims.size != claims.toSet().size) return "command interfaces already claimed: ${claims.groupBy { it }.filter { it.value.size > 1 }.keys.joinToString()}"
        for ((n, s) in after) controllers.getValue(n).state = s
        updateRobot()
        return null
    }

    private fun updateRobot() { robot.drivesOn = hardwareActive && controllers["diff_drive_controller"]?.state == "active" }

    companion object {
        const val MANAGER = "/controller_manager"
        const val HARDWARE = "DemoRobotSystem"
    }
}
