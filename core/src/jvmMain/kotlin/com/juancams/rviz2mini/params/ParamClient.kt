// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.params

import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.CallException
import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgValue

/**
 * Parameters of a node (like `ros2 param`): the <node>/list_parameters,
 * describe_parameters, get_parameters and set_parameters services. The calls block: worker thread.
 */
class ParamClient(val node: String, registry: InterfaceRegistry) {

    data class Entry(val descriptor: ParamModel.Descriptor, val value: ParamModel.Value)

    private val list = DynServiceClient("$node/list_parameters", registry.srv("rcl_interfaces/srv/ListParameters"))
    private val describe = DynServiceClient("$node/describe_parameters", registry.srv("rcl_interfaces/srv/DescribeParameters"))
    private val get = DynServiceClient("$node/get_parameters", registry.srv("rcl_interfaces/srv/GetParameters"))
    private val set = DynServiceClient("$node/set_parameters", registry.srv("rcl_interfaces/srv/SetParameters"))

    /**
     * All parameters with their description and value, sorted by name. Via foxglove_bridge, if
     * it does not advertise the node's services, with its `parameters` capability (no descriptions or ranges).
     */
    fun all(timeoutMs: Long = TIMEOUT_MS): List<Entry> {
        RosConnection.bridgeWithParameters()?.takeIf { !list.hasServer() }?.let { return BridgeParams.all(it, node, timeoutMs) }
        val names = ((list.call(MsgValue().apply { put("prefixes", emptyList<String>()); put("depth", 0L) }, timeoutMs)["result"] as? Map<*, *>)
            ?.get("names") as? List<*>).orEmpty().map { it as String }.sorted()
        if (names.isEmpty()) return emptyList()
        val descriptors = (describe.call(MsgValue().apply { put("names", names) }, timeoutMs)["descriptors"] as? List<*>).orEmpty()
        val values = (get.call(MsgValue().apply { put("names", names) }, timeoutMs)["values"] as? List<*>).orEmpty()
        return names.mapIndexed { i, name ->
            val d = (descriptors.getOrNull(i) as? Map<*, *>)?.let { ParamModel.descriptorFromMsg(it) }
            val v = ParamModel.fromMsg(values.getOrNull(i) as? Map<*, *>)
            Entry(d?.copy(name = name) ?: ParamModel.Descriptor(name, v.type), v)
        }
    }

    /** Changes a parameter. Returns null if it is applied, or the reason the node rejects it. */
    fun set(name: String, value: ParamModel.Value, timeoutMs: Long = TIMEOUT_MS): String? {
        RosConnection.bridgeWithParameters()?.takeIf { !set.hasServer() }?.let { return BridgeParams.set(it, node, name, value, timeoutMs) }
        val req = MsgValue().apply {
            put("parameters", listOf(MsgValue().apply { put("name", name); put("value", ParamModel.toMsg(value)) }))
        }
        val result = (set.call(req, timeoutMs)["results"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw CallException("Invalid response from $node")
        return if (result["successful"] == true) null else (result["reason"] as? String)?.ifBlank { null } ?: "Rejected by the node"
    }

    fun hasServer() = list.hasServer() || RosConnection.bridgeWithParameters() != null

    fun close() {
        list.close(); describe.close(); get.close(); set.close()
    }

    companion object {
        private const val TIMEOUT_MS = 5000L
    }
}
