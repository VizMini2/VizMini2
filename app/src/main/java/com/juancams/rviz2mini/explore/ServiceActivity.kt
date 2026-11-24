// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.explore

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.juancams.rviz2mini.ui.screens.ServiceCallScreen

/** Call a service (like `ros2 service call`): the screen shared with the desktop. */
class ServiceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_NAME) ?: run { finish(); return }
        val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
        showSharedScreen { ServiceCallScreen(name, type) }
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TYPE = "type"

        fun open(context: Context, name: String, type: String) {
            context.startActivity(Intent(context, ServiceActivity::class.java).putExtra(EXTRA_NAME, name).putExtra(EXTRA_TYPE, type))
        }
    }
}
