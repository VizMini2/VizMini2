// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.i18n.trPlural
import com.juancams.rviz2mini.platform.ImageCodecs
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.profiles.ProfileStore
import com.juancams.rviz2mini.profiles.QrProfiles
import com.juancams.rviz2mini.profiles.RobotProfile
import com.juancams.rviz2mini.profiles.RobotProfiles
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.ui.common.FileDialogs
import com.juancams.rviz2mini.ui.common.UiImages

/** QR in Compose and reading a QR from an image (zxing: the same format as the phone, see [QrProfiles]). */
object QrImages {
    /** One pixel per module (painted scaled without smoothing: FilterQuality.None). */
    fun render(text: String): ImageBitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return UiImages.fromArgb(px, m.width, m.height)
    }

    /** The QR text of an image (PNG/JPEG), or null. */
    fun read(bytes: ByteArray): String? {
        val img = ImageCodecs.decode(bytes, 0, bytes.size) ?: return null
        return try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(img.width, img.height, img.pixels))),
                mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text
        } catch (_: Exception) { null }
    }
}

/**
 * Robot profiles (connection + tabs), like the card in the Android configuration: save the current one, load
 * or delete one, show its QR (for the phone to scan) and import a profile from a file or from a QR
 * image. `onLoad` applies a profile (the platform switches the connection and its tabs). `onChanged`: after saving or deleting
 * (to upload the change to the cloud).
 */
@Composable
fun ProfilesCard(onLoad: (RobotProfile) -> Unit, modifier: Modifier = Modifier, onChanged: (name: String, deleted: Boolean) -> Unit = { _, _ -> }) {
    val store = ProfileStore.get()
    var version by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var qrFor by remember { mutableStateOf<RobotProfile?>(null) }
    var pending by remember { mutableStateOf<RobotProfile?>(null) }
    fun tabs(n: Int) = trPlural("common_tabs_count", n, n)
    fun current(name: String) = RobotProfile(name, RosConnection.config, TabStore.get().all.toList())
    fun import(text: String?) {
        if (text == null) return
        val p = try {
            if (text.trim().startsWith("R2M1:")) QrProfiles.decode(text).profile else RobotProfiles.parseFile(text)
        } catch (e: Exception) { message = tr("config_import_failed", e.message ?: ""); return }
        Platform.main.post { pending = p }
    }

    Card(modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (version < 0) return@Column
            Text(tr("config_profiles"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(tr("config_profile_summary", store.activeName ?: tr("config_profile_none"), store.all.size))
            Text(tr("config_profiles_hint"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { dialog = "save" }) { Text(tr("config_save_profile")) }
                OutlinedButton(onClick = { dialog = "load" }) { Text(tr("config_load_profile")) }
                OutlinedButton(onClick = { qrFor = current(store.activeName ?: RosConnection.config.nodeName) }) {
                    Icon(Icons.Default.QrCode2, null, Modifier.size(18.dp)); Text("  QR")
                }
                OutlinedButton(onClick = {
                    FileDialogs.openStream?.invoke(listOf("json", "txt", "png", "jpg", "jpeg")) { name, input ->
                        val bytes = input?.use { it.readBytes() } ?: return@invoke
                        val isImage = name?.lowercase()?.let { n -> listOf(".png", ".jpg", ".jpeg").any { n.endsWith(it) } } == true
                        val text = if (isImage) QrImages.read(bytes) ?: run { Platform.main.post { message = tr("profiles_no_qr_in_image") }; return@invoke }
                            else bytes.toString(Charsets.UTF_8)
                        Platform.main.post { import(text) }
                    }
                }) { Text(tr("config_import")) }
            }
            message?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
        }
    }

    if (dialog == "save") {
        var name by remember { mutableStateOf(store.activeName.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(tr("config_save_profile")) },
            text = {
                Column {
                    Text(tr("config_domain_tabs", RosConnection.config.domainId, tabs(TabStore.get().all.size)))
                    OutlinedTextField(name, { name = it.take(RobotProfiles.MAX_NAME_LENGTH); error = null }, singleLine = true,
                        placeholder = { Text(tr("config_profile_name_hint")) }, isError = error != null,
                        supportingText = error?.let { e -> { Text(e) } }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    RobotProfiles.validateName(n)?.let { error = it; return@TextButton }
                    store.save(current(n)); store.activeName = n
                    message = tr("config_profile_saved", n); dialog = null; version++
                    onChanged(n, false)
                }) { Text(tr("common_save")) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("common_cancel")) } },
        )
    }

    if (dialog == "load") AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text(tr("config_load_profile")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (store.all.isEmpty()) Text(tr("config_no_profiles"))
                for (p in store.all) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { dialog = null; pending = p }) {
                    Text(tr("config_profile_item", p.name, p.ros.domainId, tabs(p.tabs.size)), modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                    IconButton(onClick = { dialog = null; qrFor = p }) { Icon(Icons.Default.QrCode2, "QR") }
                    IconButton(onClick = {
                        store.delete(p.name); version++; onChanged(p.name, true)
                    }) { Icon(Icons.Default.Delete, tr("common_delete_named", p.name)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { dialog = null }) { Text(tr("common_cancel")) } },
    )

    pending?.let { p ->
        val exists = store.get(p.name) != null && store.get(p.name) != p
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(p.name) },
            text = {
                Text((if (exists) tr("config_import_replaces") else "") + tr("config_load_message", p.ros.domainId, p.ros.nodeName,
                    p.tabs.joinToString { it.name }.ifEmpty { tr("config_load_no_tabs") }))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (store.get(p.name) != p) { store.save(p); onChanged(p.name, false) }
                    store.activeName = p.name
                    pending = null; version++
                    onLoad(p)
                }) { Text(tr("common_load")) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(tr("common_cancel")) } },
        )
    }

    qrFor?.let { p -> QrDialog(p, tabs(p.tabs.size)) { qrFor = null } }
}

/** The QR of a profile: full (if it fits) or just the connection, as on the phone. */
@Composable
private fun QrDialog(p: RobotProfile, tabsLabel: String, onDismiss: () -> Unit) {
    val full = remember(p) { QrProfiles.encode(p, connectionOnly = false) }
    val conn = remember(p) { QrProfiles.encode(p, connectionOnly = true) }
    // If it does not fit in one, several QRs that cycle automatically every 2 s (the phone joins them when scanning).
    val parts = remember(p) { QrProfiles.parts(full) }
    val fullFits = true
    var connectionOnly by remember { mutableStateOf(false) }
    var part by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(connectionOnly, parts.size) {
        while (!connectionOnly && parts.size > 1) { kotlinx.coroutines.delay(2000); part = (part + 1) % parts.size }
    }
    val image = remember(connectionOnly, part) { QrImages.render(if (connectionOnly) conn else parts[part % parts.size]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 640.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(p.name) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !connectionOnly, enabled = fullFits, onClick = { connectionOnly = false },
                        label = { Text(tr(if (fullFits) "config_qr_full" else "config_qr_full_too_big")) })
                    FilterChip(selected = connectionOnly, onClick = { connectionOnly = true }, label = { Text(tr("config_qr_connection_only")) })
                }
                // A whole number of pixels per module (about 440 dp): scaled without smoothing, every module equal.
                val density = androidx.compose.ui.platform.LocalDensity.current.density
                val px = image.width * maxOf(1, (520 * density / image.width).toInt())
                Image(image, "QR", Modifier.size((px / density).dp).background(Color.White), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                if (!connectionOnly && parts.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { part = (part + parts.size - 1) % parts.size }) { Text("‹") }
                    Text(tr("config_qr_part", part + 1, parts.size), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { part = (part + 1) % parts.size }) { Text("›") }
                }
                Text(if (connectionOnly) tr("common_domain_node", p.ros.domainId, p.ros.nodeName) +
                        (p.ros.peerEntries().takeIf { it.isNotEmpty() }?.let { tr("config_qr_peers", it.joinToString()) } ?: "") + tr("config_qr_scan_to_connect")
                    else tr("config_qr_caption_full", p.name, p.ros.domainId, tabsLabel), fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("common_close")) } },
    )
}
