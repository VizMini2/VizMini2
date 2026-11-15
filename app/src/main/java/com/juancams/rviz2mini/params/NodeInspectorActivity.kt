// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.params

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.juancams.rviz2mini.explore.TopicActivity
import com.juancams.rviz2mini.explore.showSharedScreen
import com.juancams.rviz2mini.ui.screens.NodeInspectorScreen

/**
 * Node inspector (like `ros2 node info` + `ros2 param`): the screen shared with the desktop
 * (ui/NodeInspectorScreen). Tapping a topic opens it. Back exits.
 */
class NodeInspectorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val node = intent.getStringExtra(EXTRA_NODE) ?: run { finish(); return }
        showSharedScreen { NodeInspectorScreen(node, onOpenTopic = { t, type -> TopicActivity.open(this, t, type) }) }
    }

    companion object {
        const val EXTRA_NODE = "node"

        fun open(context: Context, node: String) {
            context.startActivity(Intent(context, NodeInspectorActivity::class.java).putExtra(EXTRA_NODE, node))
        }
    }
}
