// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.ServiceFormat
import com.juancams.rviz2mini.ui.common.StatusPill
import com.juancams.rviz2mini.ui.common.rememberRosStatus

/** Network settings (domain, node name, peers, Discovery Server, foxglove_bridge, Zenoh...), as on Android. */
@Composable
fun ConnectionScreen(modifier: Modifier = Modifier, header: @Composable () -> Unit = {}) {
    val cfg = RosConnection.config
    var domain by remember { mutableStateOf(cfg.domainId.toString()) }
    var node by remember { mutableStateOf(cfg.nodeName) }
    var peers by remember { mutableStateOf(cfg.discoveryPeers) }
    var servers by remember { mutableStateOf(cfg.discoveryServers) }
    var bridge by remember { mutableStateOf(cfg.bridgeUrl) }
    var zenoh by remember { mutableStateOf(cfg.zenohRouter) }
    var interfaces by remember { mutableStateOf(cfg.interfaceWhitelist) }
    var format by remember { mutableStateOf(cfg.serviceFormat) }
    val status = rememberRosStatus()

    val errors = mapOf(
        "domain" to RosConfig.validateDomainId(domain),
        "node" to RosConfig.validateNodeName(node),
        "peers" to RosConfig.validatePeers(peers),
        "servers" to RosConfig.validateServers(servers),
        "bridge" to RosConfig.validateBridgeUrl(bridge),
        "zenoh" to (RosConfig.validateZenohRouter(zenoh) ?: if (bridge.isNotBlank() && zenoh.isNotBlank()) tr("config_zenoh_and_bridge") else null),
    )

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Per-platform items that go before the network (on the PC: theme, language, account).
        header()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("config_network"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            StatusPill(status)
        }
        Text(tr("config_network_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        @Composable
        fun field(value: String, set: (String) -> Unit, label: String, key: String, helper: String? = null) = OutlinedTextField(
            value, set, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = errors[key] != null,
            supportingText = (errors[key] ?: helper)?.let { t -> { Text(t) } },
        )
        var finding by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { field(domain, { domain = it }, tr("config_domain_id"), "domain", tr("config_domain_range")) }
            androidx.compose.material3.FilledTonalButton(onClick = { finding = true }, modifier = Modifier.padding(start = 8.dp)) { Text(tr("find_robots_button")) }
        }
        if (finding) com.juancams.rviz2mini.ui.common.FindRobotsDialog(onDismiss = { finding = false }) { d ->
            finding = false
            domain = d.toString()
            RosConnection.applyConfig(RosConnection.config.copy(domainId = d))
        }
        field(node, { node = it }, tr("config_node_name"), "node")
        field(peers, { peers = it }, tr("config_peers"), "peers", tr("config_peers_helper"))
        field(servers, { servers = it }, tr("config_server"), "servers", tr("config_server_helper"))
        field(bridge, { bridge = it }, tr("config_bridge"), "bridge", tr("config_bridge_helper"))
        field(zenoh, { zenoh = it }, tr("config_zenoh"), "zenoh", tr("config_zenoh_helper"))
        field(interfaces, { interfaces = it }, tr("config_interfaces"), "interfaces", tr("config_interfaces_helper"))
        Text(tr("config_service_format"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((f, key) in listOf(ServiceFormat.AUTO to "config_service_format_auto", ServiceFormat.FAST_DDS to "config_service_format_fastdds",
                ServiceFormat.CYCLONE to "config_service_format_cyclone")) {
                FilterChip(selected = format == f, onClick = { format = f }, label = { Text(tr(key)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = errors.values.all { it == null }, onClick = {
                RosConnection.applyConfig(
                    RosConnection.config.copy(
                        domainId = domain.trim().toInt(), nodeName = node.trim(), discoveryPeers = peers.trim(),
                        discoveryServers = servers.trim(), bridgeUrl = bridge.trim(), zenohRouter = zenoh.trim(),
                        interfaceWhitelist = interfaces.trim(), serviceFormat = format,
                    )
                )
            }) { Text(tr("config_apply")) }
            OutlinedButton(onClick = { RosConnection.restart() }) { Text(tr("config_restart")) }
        }
        (status as? RosConnection.Status.Error)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
    }
}
