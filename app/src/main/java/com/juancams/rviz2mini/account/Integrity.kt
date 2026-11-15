// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.account

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.juancams.rviz2mini.BuildConfig
import com.juancams.rviz2mini.R
import java.security.MessageDigest

/**
 * Checks that the APK is signed with our key. If someone decompiles it, modifies it and
 * repackages it, they have to sign it with another key (they do not have ours), so the SHA-256
 * of the certificate changes and the app refuses to start.
 *
 * It is not foolproof (a determined attacker can patch this very check, already obfuscated), but
 * together with R8 obfuscation it raises the bar considerably.
 *
 * The expected SHA-256 is injected by Gradle into BuildConfig, read from the release keystore. In debug
 * it is empty and nothing is checked.
 */
object Integrity {

    private const val TAG = "VizMini2"

    /**
     * Accepted certificates. The first is the release one (set by Gradle). If you ever publish on
     * Google Play with "Play App Signing", Google re-signs with ITS key: add
     * here the SHA-256 of the certificate Play Console shows you, or the app will not start for those who
     * install it from the store.
     */
    private val accepted: List<String> = listOf(
        BuildConfig.EXPECTED_CERT_SHA256,
        // Google Play app signing key (Play Console → Protected with Play → App signing):
        // copies installed from the store are signed by Google with it.
        "7E:4C:DC:83:5F:A7:7C:09:B3:31:98:0C:FD:97:0D:83:AD:CC:88:5D:30:95:2E:6E:A6:4F:67:94:9F:D5:6E:1D",
    ).filter { it.isNotBlank() }

    /** true if the signature is valid (or if there is nothing to check, e.g. in debug). */
    fun isValid(context: Context): Boolean {
        // Without a release certificate (debug) nothing is checked: the Play one is always in the list.
        if (BuildConfig.EXPECTED_CERT_SHA256.isBlank()) return true
        val actual = currentCertSha256(context)
        if (actual == null) {
            Log.w(TAG, "No se pudo leer la firma de la app")
            return false
        }
        return accepted.any { it.equals(actual, ignoreCase = true) }
    }

    /**
     * Checks the signature and, if it is not valid, warns and closes the app. Returns false in that case so
     * the caller does not keep building the screen. Call at the start of onCreate.
     */
    fun enforce(activity: Activity): Boolean {
        if (isValid(activity)) return true
        MaterialAlertDialogBuilder(activity)
            .setCancelable(false)
            .setTitle(R.string.integrity_tampered_title)
            .setMessage(R.string.integrity_tampered_message)
            .setPositiveButton(R.string.common_ok) { _, _ -> activity.finishAffinity() }
            .setOnDismissListener { activity.finishAffinity() }
            .show()
        return false
    }

    /** SHA-256 (uppercase, ':'-separated) of the certificate the app is signed with. */
    private fun currentCertSha256(context: Context): String? = try {
        val pm = context.packageManager
        val pkg = context.packageName
        val certs: Array<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo
            when {
                signing == null -> emptyArray()
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                else -> signing.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        certs.firstOrNull()?.let { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Error al comprobar la firma", e)
        null
    }
}
