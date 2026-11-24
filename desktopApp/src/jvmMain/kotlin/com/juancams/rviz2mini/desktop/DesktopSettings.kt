// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juancams.rviz2mini.i18n.XmlStrings
import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.platform.Platform

/** Desktop app settings that are not network ones: theme, language and the sidebar. Stored in "appearance". */
object DesktopSettings {
    private val store get() = Platform.prefs("appearance")

    /** "system", "light" or "dark". */
    var theme by mutableStateOf(store.getString("theme", null) ?: "system"); private set
    /** "" (the system's), "en" or "es". Changing it recomposes the whole window. */
    var language by mutableStateOf(store.getString("language", null) ?: ""); private set
    var sidebarCollapsed by mutableStateOf(store.getBoolean("sidebarCollapsed", false)); private set
    /** Scale of the whole interface: 0.85 compact, 1 normal, 1.15 large. */
    var uiScale by mutableStateOf(store.getFloat("uiScale", 1f)); private set
    fun changeUiScale(s: Float) { uiScale = s; store.edit().putFloat("uiScale", s).apply() }

    /** Languages with texts (the same as Android: res/values and res/values-es). */
    val LANGUAGES = listOf("", "en", "es")

    /** The language the texts are in: the chosen one, or the system's (or `vizmini2.lang`). */
    fun effectiveLanguage(): String = language.ifEmpty { System.getProperty("vizmini2.lang") ?: java.util.Locale.getDefault().language }

    /** Sets the texts of the saved language (before the first window). */
    fun applyLanguage() {
        val lang = effectiveLanguage()
        Platform.strings = PcWording(XmlStrings(lang), lang)
    }

    fun changeTheme(t: String) { theme = t; store.edit().putString("theme", t).apply() }

    fun changeLanguage(l: String) {
        store.edit().putString("language", l).apply()
        language = l
        applyLanguage()
    }

    fun changeSidebar(collapsed: Boolean) { sidebarCollapsed = collapsed; store.edit().putBoolean("sidebarCollapsed", collapsed).apply() }

    fun languageLabel(l: String) = when (l) { "en" -> "English"; "es" -> "Español"; else -> tr("settings_option_system") }
}

/** Appearance card of the PC settings: theme and language. */
@Composable
fun AppearanceCard(modifier: Modifier = Modifier) {
    Card(modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("settings_appearance"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Choice(tr("settings_theme"), tr("settings_theme_hint"), listOf("system", "light", "dark"), DesktopSettings.theme,
                { if (it == "system") tr("settings_option_system") else tr("settings_theme_$it") }) { DesktopSettings.changeTheme(it) }
            Choice(tr("settings_language"), tr("settings_language_hint_pc"), DesktopSettings.LANGUAGES, DesktopSettings.language,
                { DesktopSettings.languageLabel(it) }) { DesktopSettings.changeLanguage(it) }
            Choice(tr("settings_ui_size"), tr("settings_ui_size_hint"), listOf("0.85", "1.0", "1.15"), DesktopSettings.uiScale.toString(),
                { when (it) { "0.85" -> tr("settings_ui_compact"); "1.15" -> tr("settings_ui_large"); else -> tr("settings_ui_normal") } }) { DesktopSettings.changeUiScale(it.toFloat()) }
        }
    }
}

@Composable
private fun Choice(title: String, hint: String, options: List<String>, current: String, label: (String) -> String, onPick: (String) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow {
                options.forEachIndexed { i, o ->
                    SegmentedButton(selected = o == current, onClick = { onPick(o) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label(o), fontSize = 13.sp) }
                }
            }
        }
        Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The account (the same as on the phone) and the sync of the robot profiles. */
@Composable
fun AccountCard(modifier: Modifier = Modifier) {
    val s = DesktopAccount.session
    var busy by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var email by androidx.compose.runtime.remember { mutableStateOf("") }
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    var info by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    fun run(label: String, block: () -> Unit) {
        busy = label; error = null; info = null
        Thread { val e = runCatching(block).exceptionOrNull(); Platform.main.post { busy = null; error = e?.let { tr("social_failed", it.message ?: it.toString()) } } }.start()
    }
    Card(modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("home_account_optional"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (!DesktopAccount.available) { Text(tr("account_not_configured")); return@Column }
            if (s != null) {
                Text(tr("home_signed_in_as") + " " + (s.email ?: s.uid) + (s.provider?.let { "  ·  " + providerLabel(it) } ?: ""))
                Text(when (val st = DesktopAccount.status) {
                    DesktopAccount.SyncStatus.Idle -> tr("cloud_not_synced")
                    DesktopAccount.SyncStatus.Syncing -> tr("cloud_syncing")
                    is DesktopAccount.SyncStatus.Synced -> tr("cloud_synced", java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(st.atMs)))
                    is DesktopAccount.SyncStatus.Failed -> tr("cloud_failed", st.message)
                }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { DesktopAccount.sync() }) { Text(tr("cloud_sync_now")) }
                    androidx.compose.material3.TextButton(onClick = { DesktopAccount.signOut() }) { Text(tr("common_sign_out")) }
                }
                return@Column
            }
            Text(tr("home_account_why"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in listOf("google.com", "microsoft.com", "github.com")) androidx.compose.material3.OutlinedButton(enabled = busy == null, onClick = {
                    busy = tr("account_waiting_browser"); error = null
                    DesktopAccount.signInWithBrowser(p) { e -> busy = null; error = e?.let { tr("social_failed", it) } }
                }) { Text(tr("account_continue_with", providerLabel(p))) }
            }
            Text(tr("account_browser_hint"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tr("linked_password"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(email, { email = it }, singleLine = true, label = { Text(tr("account_email")) }, modifier = Modifier.weight(1f))
                androidx.compose.material3.OutlinedTextField(password, { password = it }, singleLine = true, label = { Text(tr("common_password")) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(enabled = busy == null && email.isNotBlank() && password.isNotEmpty(),
                    onClick = { run(tr("account_sign_in")) { DesktopAccount.signInWithPassword(email.trim(), password) } }) { Text(tr("account_sign_in")) }
                androidx.compose.material3.OutlinedButton(enabled = busy == null && email.isNotBlank() && password.length >= 6,
                    onClick = { run(tr("account_create")) { DesktopAccount.signUp(email.trim(), password) } }) { Text(tr("account_create")) }
                androidx.compose.material3.TextButton(enabled = busy == null && email.isNotBlank(), onClick = {
                    run(tr("account_forgot")) { DesktopAccount.sendPasswordReset(email.trim()); Platform.main.post { info = tr("account_reset_sent") } }
                }) { Text(tr("account_forgot")) }
            }
            busy?.let { Text(it, fontSize = 13.sp) }
            info?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun providerLabel(p: String) = when (p) { "google.com" -> "Google"; "microsoft.com" -> "Microsoft"; "github.com" -> "GitHub"; else -> tr("linked_password") }

/**
 * Android texts talk about tapping, pinching and "this phone": on the PC they are said with mouse and "this computer".
 * They are adapted when read (so the strings.xml do not need to be duplicated).
 */
private class PcWording(private val base: com.juancams.rviz2mini.i18n.StringSource, lang: String) : com.juancams.rviz2mini.i18n.StringSource {
    private val rules: List<Pair<Regex, String>> = if (lang == "es") listOf(
        Regex("Pellizca para hacer zoom y toca dos veces para ajustar") to "Usa la rueda para hacer zoom y haz doble clic para ajustar",
        Regex("[Pp]ellizca para hacer zoom") to "usa la rueda para hacer zoom",
        Regex("\\btoca dos veces\\b") to "haz doble clic",
        Regex("\\b([Pp])ulsa aquí") to "Haz clic aquí", Regex("\\b[Pp]ulsa de nuevo") to "haz clic de nuevo",
        Regex("\\bToca\\b") to "Haz clic en", Regex("\\btoca\\b") to "haz clic en",
        Regex("\\bPulsa\\b") to "Haz clic en", Regex("\\bpulsa\\b") to "haz clic en",
        Regex("\\beste móvil\\b") to "este ordenador", Regex("\\bdel móvil\\b") to "del ordenador", Regex("\\bel móvil\\b") to "el ordenador",
    ) else listOf(
        Regex("Pinch to zoom, double tap to fit") to "Scroll to zoom, double-click to fit",
        Regex("[Pp]inch to zoom") to "scroll to zoom",
        Regex("\\bdouble tap\\b") to "double-click",
        Regex("\\bTap\\b") to "Click", Regex("\\btap\\b") to "click", Regex("\\bTapping\\b") to "Clicking", Regex("\\btapping\\b") to "clicking",
        Regex("\\bthis phone\\b") to "this computer", Regex("\\byour phone's\\b") to "your computer's", Regex("\\bthe phone\\b") to "the computer",
    )
    private fun fix(t: String?): String? = t?.let { rules.fold(it) { acc, (r, w) -> r.replace(acc, w) } }
    override fun get(key: String) = fix(base.get(key))
    override fun plural(key: String, count: Int) = fix(base.plural(key, count))
}
