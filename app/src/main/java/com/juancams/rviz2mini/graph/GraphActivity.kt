// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.graph

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.params.NodeInspectorActivity
import com.juancams.rviz2mini.ui.screens.NodeGraphScreen
import com.juancams.rviz2mini.ui.screens.TfTreeScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/** Node graph and TF tree in full screen (from Home): the shared screens, with tabs to switch. */
class GraphActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val startTf = intent.getBooleanExtra(EXTRA_TF, false)
        setContent {
            VizTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    var tf by remember { mutableStateOf(startTf) }
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("graph_back")) }
                            Text(tr("graph_title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                        }
                        TabRow(selectedTabIndex = if (tf) 1 else 0) {
                            Tab(selected = !tf, onClick = { tf = false }, text = { Text(tr("graph_tab_nodes")) })
                            Tab(selected = tf, onClick = { tf = true }, text = { Text(tr("graph_tab_tf")) })
                        }
                        if (tf) TfTreeScreen(Modifier.weight(1f)) else NodeGraphScreen(Modifier.weight(1f), onOpenNode = { NodeInspectorActivity.open(this@GraphActivity, it) })
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_TF = "tf"

        fun open(context: Context, tf: Boolean = false) {
            context.startActivity(Intent(context, GraphActivity::class.java).putExtra(EXTRA_TF, tf))
        }
    }
}
