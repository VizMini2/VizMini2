// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.control

import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry

/**
 * The controller_manager_msgs versions with a different wire format (the app bundles Lyrical's, which Kilted and
 * Rolling share):
 * - Jazzy: HardwareComponentState with `class_type` (between `rw_rate` and `plugin_name`).
 * - Humble: ControllerState without `is_async`, `update_rate` or `exported_state_interfaces`; SwitchController with
 *   the old `start_controllers`/`stop_controllers` beside the new ones, and only `ok` in the reply;
 *   HardwareComponentState and HardwareInterface shorter.
 * - Foxy: ControllerState with only name, state, type and claimed interfaces; SwitchController with only
 *   start/stop; HardwareInterface with only name and `is_claimed`; no hardware components service.
 */
enum class ControlMsgs(val labelKey: String) {
    AUTO("control_msgs_auto"), KILTED("control_msgs_kilted"), JAZZY("control_msgs_jazzy"),
    HUMBLE("control_msgs_humble"), FOXY("control_msgs_foxy");

    /** Whether the controller manager has `list_hardware_components` and `set_hardware_component_state`. */
    val hasHardwareComponents: Boolean get() = this != FOXY

    companion object {
        private val cache = java.util.WeakHashMap<InterfaceRegistry, MutableMap<ControlMsgs, InterfaceRegistry>>()
        private const val PKG = "controller_manager_msgs"

        /** The registry with that version's definitions (KILTED/AUTO: the app's one). */
        @Synchronized fun registry(base: InterfaceRegistry, v: ControlMsgs): InterfaceRegistry {
            if (v == AUTO || v == KILTED) return base
            return cache.getOrPut(base) { HashMap() }.getOrPut(v) {
                base.copy().apply { for ((name, text) in definitions(v)) define("$PKG/$name", text) }
            }
        }

        /** The definitions that differ from Lyrical's in version `v`. */
        fun definitions(v: ControlMsgs): Map<String, String> = when (v) {
            AUTO, KILTED -> emptyMap()
            JAZZY -> mapOf(
                "msg/HardwareComponentState" to """string name
string type
bool is_async
uint16 rw_rate
string class_type
string plugin_name
lifecycle_msgs/State state
HardwareInterface[] command_interfaces
HardwareInterface[] state_interfaces""",
            )
            HUMBLE -> mapOf(
                "msg/ControllerState" to """string name
string state
string type
string[] claimed_interfaces
string[] required_command_interfaces
string[] required_state_interfaces
bool is_chainable
bool is_chained
string[] reference_interfaces
ChainConnection[] chain_connections""",
                "srv/SwitchController" to """string[] activate_controllers
string[] deactivate_controllers
string[] start_controllers
string[] stop_controllers
int32 strictness
int32 BEST_EFFORT=1
int32 STRICT=2
bool start_asap
bool activate_asap
builtin_interfaces/Duration timeout
---
bool ok""",
                "msg/HardwareComponentState" to """string name
string type
string class_type
lifecycle_msgs/State state
HardwareInterface[] command_interfaces
HardwareInterface[] state_interfaces""",
                "msg/HardwareInterface" to "string name\nbool is_available\nbool is_claimed",
            )
            FOXY -> mapOf(
                "msg/ControllerState" to "string name\nstring state\nstring type\nstring[] claimed_interfaces",
                "srv/SwitchController" to """string[] start_controllers
string[] stop_controllers
int32 strictness
int32 BEST_EFFORT=1
int32 STRICT=2
bool start_asap
builtin_interfaces/Duration timeout
---
bool ok""",
                "msg/HardwareInterface" to "string name\nbool is_claimed",
            )
        }
    }
}
