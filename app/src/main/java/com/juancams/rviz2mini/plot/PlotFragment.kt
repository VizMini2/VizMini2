// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plot

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.juancams.rviz2mini.bag.BagSession
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.ui.screens.PLOT_KEY_BAG
import com.juancams.rviz2mini.ui.screens.PLOT_KEY_LAYOUT
import com.juancams.rviz2mini.ui.screens.PlotController
import com.juancams.rviz2mini.ui.screens.PlotPlatform
import com.juancams.rviz2mini.ui.screens.PlotView
import com.juancams.rviz2mini.ui.theme.VizTheme
import java.io.File

/**
 * Plot tab: the screen shared with the desktop (ui/PlotScreen). The fragment owns the
 * controller (subscriptions live with the view; the screen only refreshes while visible) and adds the
 * Android-specific parts: the bags folder, sharing the exported CSV and importing one.
 */
class PlotFragment : TabFragment() {

    private var controller: PlotController? = null
    private var importDone: ((File?, String?) -> Unit)? = null

    /** Copies a CSV to the bags folder. */
    private val importCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = importDone ?: return@registerForActivityResult
        importDone = null
        if (uri == null) { done(null, null); return@registerForActivityResult }
        val ctx = requireContext()
        val name = (DocumentFile.fromSingleUri(ctx, uri)?.name ?: "data.csv").let { if (it.endsWith(".csv", true)) it else "$it.csv" }
        val base = name.substring(0, name.length - 4)
        var target = File(BagSession.dir(), "$base.csv")
        var n = 2
        while (target.exists()) target = File(BagSession.dir(), "${base}_${n++}.csv")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            done(target, null)
        } catch (e: Exception) { done(null, e.message ?: e.javaClass.simpleName) }
    }

    private val platform = object : PlotPlatform {
        override val bagDir: File get() = BagSession.dir()
        override fun bagFiles(): List<File> = BagSession.files()
        override val exportDir: File get() = File(requireContext().cacheDir, "exports")
        override fun share(file: File): String? {
            if (view == null) return null
            val ctx = requireContext()
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, file.name))
            return null
        }
        override val canImportCsv: Boolean get() = true
        override fun importCsv(done: (File?, String?) -> Unit) {
            importDone = done
            importCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val c = PlotController(tabId, platform)
        controller = c
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { VizTheme { PlotView(c) } }
        }
    }

    override fun onResume() { super.onResume(); controller?.resume() }
    override fun onPause() { super.onPause(); controller?.pause() }

    override fun onDestroyView() {
        super.onDestroyView()
        controller?.close(); controller = null
    }

    // ---------------------------------------------------------------- tests (on the main thread)

    private val c: PlotController get() = controller!!
    internal fun layoutForTest() = c.layout
    internal fun setHeaderStampForTest(on: Boolean) = c.setHeaderStamp(on)
    internal fun latestForTest(): Long? = c.latestNs()
    internal fun textLabelsForTest(): Map<String, List<String>> = c.textLabels()
    internal fun saveLayoutForTest(l: PlotLayout) = c.saveLayoutAndReload(l)
    internal fun bufferSizeForTest(id: String): Int = c.buffers()[id]?.size() ?: 0
    internal fun valueAtForTest(id: String, t: Long): Double? = c.buffers()[id]?.valueAt(t)
    internal fun viewportForTest() = c.viewport
    internal fun bagForTest() = c.bag
    internal fun detailRangeForTest(): LongRange? = c.detailRange
    internal fun openBagForTest(f: File) = c.openBagFile(f)
    internal fun goLiveForTest() = c.goLive()
    internal fun setFollowForTest(on: Boolean) = c.setFollow(on)
    internal fun toggleFollowForTest() = c.toggleFollow()
    internal fun playingForTest() = c.isPlaying
    internal fun seekForTest(f: Double) = c.seekTo(f)
    internal fun csvForTest(): String = c.csv()
    /** One item per panel (formerly, their views). */
    internal fun panelViewsForTest(): List<PanelSpec> = c.layout.panels
    internal fun errorsForTest(): Map<String, String> = c.errors()
    fun onViewportChanged() = c.onViewportChanged()

    companion object {
        const val KEY_LAYOUT = PLOT_KEY_LAYOUT
        const val KEY_BAG = PLOT_KEY_BAG
    }
}
