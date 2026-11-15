// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Privacy policy, in the app's language. It is the same page published on Firebase
 * Hosting (tools/firebase/hosting/privacy.html; a test checks they do not diverge), bundled in
 * the app so it can be read offline and before having an account. Links open externally.
 */
class PrivacyActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        // Only the page's own JavaScript (language selection); nothing from the network.
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        val lang = if (resources.configuration.locales[0].language == "es") "es" else "en"
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "file") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            // The app's language, without an anchor in the URL (with "#es" the page would jump and the title would not be visible).
            override fun onPageFinished(view: WebView, url: String) = view.evaluateJavascript("show('$lang')", null)
        }
        // targetSdk 35 draws edge to edge: the container keeps the page clear of the system bars
        // (the WebView does not honor its own padding).
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(web, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        web.loadUrl("file:///android_asset/privacy.html")
    }

    companion object {
        /** Public URL (Firebase Hosting), for the Google Play listing. */
        const val PUBLIC_URL = "https://ros-2-viz-mini.web.app/privacy"

        fun open(context: Context) = context.startActivity(Intent(context, PrivacyActivity::class.java))
    }
}
