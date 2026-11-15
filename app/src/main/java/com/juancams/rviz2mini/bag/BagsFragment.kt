// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bag

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.ui.screens.BagsController
import com.juancams.rviz2mini.ui.screens.BagsPlatform
import com.juancams.rviz2mini.ui.screens.BagsView
import com.juancams.rviz2mini.ui.theme.VizTheme
import java.io.File
import java.io.InputStream

/**
 * Bags tab: the screen shared with the desktop (ui/BagsScreen). Recording and playback
 * live in [BagSession] and continue with the tab hidden. Here, the Android-specific parts: sharing, importing and the demo.
 */
class BagsFragment : TabFragment() {

    private var controller: BagsController? = null
    private var importDone: ((String?, InputStream?) -> Unit)? = null

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = importDone ?: return@registerForActivityResult
        importDone = null
        if (uri == null) { done(null, null); return@registerForActivityResult }
        val ctx = requireContext().applicationContext
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }
        done(name, try { ctx.contentResolver.openInputStream(uri) } catch (_: Exception) { null })
    }

    private val platform = object : BagsPlatform {
        override fun share(file: File): String? {
            val ctx = requireContext()
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, file.name))
            return null
        }
        override val pick: ((done: (String?, InputStream?) -> Unit) -> Unit) get() = { done -> importDone = done; importFile.launch(arrayOf("*/*")) }
        override fun isDemo(): Boolean = com.juancams.rviz2mini.demo.DemoMode.isActive(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val c = BagsController(tabId, platform)
        controller = c
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { VizTheme { BagsView(c) } }
        }
    }

    override fun onResume() { super.onResume(); controller?.resume() }
    override fun onPause() { super.onPause(); controller?.pause() }

    override fun onDestroyView() {
        super.onDestroyView()
        controller?.close(); controller = null
    }
}
