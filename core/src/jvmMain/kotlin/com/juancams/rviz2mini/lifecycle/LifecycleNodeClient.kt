// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.lifecycle

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.CallException
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue

/**
 * Client of a lifecycle node: the services <node>/get_state, get_available_transitions and
 * change_state, and the topic <node>/transition_event to learn about changes immediately
 * (also those requested by someone else, e.g. Nav2's lifecycle_manager).
 *
 * Calls block: they are made from the tab's worker thread. `onEvent` arrives on the
 * DDS thread.
 */
class LifecycleNodeClient(val node: String, registry: InterfaceRegistry, onEvent: (LifecycleModel.State) -> Unit) {

    private val getState = DynServiceClient("$node/get_state", registry.srv("lifecycle_msgs/srv/GetState"))
    private val getTransitions = DynServiceClient("$node/get_available_transitions", registry.srv("lifecycle_msgs/srv/GetAvailableTransitions"))
    private val changeState = DynServiceClient("$node/change_state", registry.srv("lifecycle_msgs/srv/ChangeState"))
    private val events = DynRos.subscribe("$node/transition_event", registry.msg("lifecycle_msgs/msg/TransitionEvent"), RosConnection.defaultQos()) { v ->
        state(v["goal_state"])?.let(onEvent)
    }

    fun state(timeoutMs: Long = TIMEOUT_MS): LifecycleModel.State =
        state(getState.call(MsgValue(), timeoutMs)["current_state"]) ?: throw CallException("Invalid response from $node")

    fun availableTransitions(timeoutMs: Long = TIMEOUT_MS): List<LifecycleModel.Transition> {
        val r = getTransitions.call(MsgValue(), timeoutMs)
        val list = (r["available_transitions"] as? List<*>).orEmpty()
        return LifecycleModel.sortTransitions(list.mapNotNull { d ->
            val desc = d as? Map<*, *> ?: return@mapNotNull null
            val t = desc["transition"] as? Map<*, *> ?: return@mapNotNull null
            LifecycleModel.Transition(
                (t["id"] as? Number)?.toInt() ?: return@mapNotNull null,
                t["label"] as? String ?: "",
                ((desc["goal_state"] as? Map<*, *>)?.get("id") as? Number)?.toInt() ?: -1
            )
        })
    }

    /** True if the node accepts the transition (and completes it successfully). */
    fun change(t: LifecycleModel.Transition, timeoutMs: Long = CHANGE_TIMEOUT_MS): Boolean {
        val req = MsgValue().apply {
            put("transition", MsgValue().apply { put("id", t.id.toLong()); put("label", t.label) })
        }
        return changeState.call(req, timeoutMs)["success"] == true
    }

    fun close() {
        try {
            getState.close(); getTransitions.close(); changeState.close(); events.close()
        } catch (e: Exception) {
            Log.w("VizMini2", "Error cerrando el cliente lifecycle de $node", e)
        }
    }

    companion object {
        private const val TIMEOUT_MS = 4000L
        /** on_configure may take a while (loading maps, opening drivers...). */
        private const val CHANGE_TIMEOUT_MS = 20_000L

        private fun state(v: Any?): LifecycleModel.State? {
            val m = v as? Map<*, *> ?: return null
            val id = (m["id"] as? Number)?.toInt() ?: return null
            return LifecycleModel.State(id, (m["label"] as? String ?: ""))
        }
    }
}
