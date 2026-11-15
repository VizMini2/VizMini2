// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.explore

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.juancams.rviz2mini.ui.screens.ActionGoalScreen

/** Send a goal to an action: the shared screen. Leaving cancels the goal, but rotating does not. */
class ActionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_NAME) ?: run { finish(); return }
        val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
        showSharedScreen { ActionGoalScreen(name, type, cancelOnLeave = { !isChangingConfigurations }) }
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TYPE = "type"

        fun open(context: Context, name: String, type: String) {
            context.startActivity(Intent(context, ActionActivity::class.java).putExtra(EXTRA_NAME, name).putExtra(EXTRA_TYPE, type))
        }
    }
}
