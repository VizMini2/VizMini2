// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.alerts

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.tr

/**
 * The phone's switch at the top of the Alerts tab: alerts are off until the user turns them on (here or in
 * Configuration), and since Android 13 they need the notifications permission, asked for when turning them on.
 */
@Composable
fun AlertsTabSwitch() {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(AlertSettings.load(ctx)) }
    var allowed by remember { mutableStateOf(AlertMonitor.canNotify(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    fun set(on: Boolean) {
        s = s.copy(enabled = on)
        AlertMonitor.apply(ctx, s)
        if (on && !allowed && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Column {
        Row(Modifier.clickable { set(!s.enabled) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("alerts_android_enabled"), fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(s.enabled, { set(it) })
        }
        if (s.enabled && !allowed) Text(tr("config_notifications_blocked"), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
}
