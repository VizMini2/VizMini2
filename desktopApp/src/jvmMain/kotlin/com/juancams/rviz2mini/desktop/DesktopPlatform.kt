// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import androidx.compose.ui.graphics.asComposeImageBitmap
import com.juancams.rviz2mini.platform.MainThread
import com.juancams.rviz2mini.platform.Platform
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Desktop: the main thread is the AWT/Swing one, which is where Compose for Desktop runs. */
object DesktopPlatform {
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vizmini2-timer").apply { isDaemon = true } }
    private val windows = System.getProperty("os.name", "").lowercase().startsWith("windows")

    /** The system's open dialog, titled "Import (.rviz)", that only lists files with those extensions. */
    private fun openDialog(exts: List<String>): java.awt.FileDialog {
        val title = com.juancams.rviz2mini.i18n.tr("bags_import") + if (exts.isEmpty()) "" else " (" + exts.joinToString(", ") { ".$it" } + ")"
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> exts.isEmpty() || exts.any { name.endsWith(".$it", ignoreCase = true) } }
        // Windows ignores the FilenameFilter: there a pattern as the file name is what filters the list.
        if (windows && exts.isNotEmpty()) dialog.file = exts.joinToString(";") { "*.$it" }
        return dialog
    }

    /** The system's save dialog, titled "Save", with the suggested name. */
    private fun saveDialog(suggested: String) =
        java.awt.FileDialog(null as java.awt.Frame?, com.juancams.rviz2mini.i18n.tr("common_save"), java.awt.FileDialog.SAVE).apply { file = suggested }

    /** The name written without an extension gets the suggested one (Windows hides it in the dialog, so it is easy to drop). */
    private fun withExtension(f: java.io.File, suggested: String): java.io.File {
        val ext = suggested.substringAfterLast('.', "")
        return if (ext.isEmpty() || f.name.contains('.')) f else java.io.File(f.parentFile, f.name + "." + ext)
    }

    fun install() {
        com.juancams.rviz2mini.ui.common.UiImages.fromArgb = { px, w, h ->
            val bmp = org.jetbrains.skia.Bitmap()
            bmp.allocPixels(org.jetbrains.skia.ImageInfo.makeN32(w, h, org.jetbrains.skia.ColorAlphaType.UNPREMUL))
            // ARGB (int) -> BGRA in memory (little endian), which is Skia's N32.
            val bytes = java.nio.ByteBuffer.allocate(w * h * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.asIntBuffer().put(px, 0, w * h)
            bmp.installPixels(bytes.array())
            bmp.asComposeImageBitmap()
        }
        // H.264/H.265 video topics: the system's ffmpeg.
        com.juancams.rviz2mini.camera.VideoDecoders.create = FfmpegVideoDecoder::create
        com.juancams.rviz2mini.platform.ImageCodecs.decode = { bytes, offset, length ->
            try {
                javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes, offset, length))?.let { img ->
                    com.juancams.rviz2mini.platform.DecodedImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
                }
            } catch (_: Throwable) { null }
        }
        // Text files (the XML of a Nav2 tree...): the system dialog.
        if (!java.awt.GraphicsEnvironment.isHeadless()) com.juancams.rviz2mini.ui.common.FileDialogs.openText = { exts, done ->
            val dialog = openDialog(exts)
            dialog.isVisible = true
            done(dialog.files.firstOrNull()?.let { f -> try { f.readText() } catch (_: Exception) { null } })
        }
        if (!java.awt.GraphicsEnvironment.isHeadless()) com.juancams.rviz2mini.ui.common.FileDialogs.openStream = { exts, done ->
            val dialog = openDialog(exts)
            dialog.isVisible = true
            val f = dialog.files.firstOrNull()
            done(f?.name, f?.let { try { it.inputStream() } catch (_: Exception) { null } })
        }
        if (!java.awt.GraphicsEnvironment.isHeadless()) com.juancams.rviz2mini.ui.common.FileDialogs.saveText = { suggested, content, done ->
            val dialog = saveDialog(suggested)
            dialog.isVisible = true
            val f = dialog.files.firstOrNull()?.let { withExtension(it, suggested) }
            done(f != null && runCatching { f.writeText(content) }.isSuccess)
        }
        // Several files (a map: PGM + YAML): the folder of the first one, picked with the system dialog.
        if (!java.awt.GraphicsEnvironment.isHeadless()) com.juancams.rviz2mini.ui.common.FileDialogs.saveFiles = { files, done ->
            val dialog = saveDialog(files.first().first)
            dialog.isVisible = true
            val first = dialog.files.firstOrNull()?.let { withExtension(it, files.first().first) }
            if (first == null) done(null) else {
                val dir = first.parentFile
                // The first one with the name chosen; the others keep theirs (the YAML points at the image by name).
                val ok = runCatching { files.forEachIndexed { i, (name, bytes) -> java.io.File(dir, if (i == 0) first.name else name).writeBytes(bytes) } }.isSuccess
                done(if (ok) dir.absolutePath else null)
            }
        }
        // Plots: import a CSV with the system file dialog.
        com.juancams.rviz2mini.ui.screens.plotPlatform = object : com.juancams.rviz2mini.ui.screens.JvmPlotPlatform() {
            override val canImportCsv: Boolean get() = !java.awt.GraphicsEnvironment.isHeadless()
            override fun importCsv(done: (java.io.File?, String?) -> Unit) {
                val dialog = openDialog(listOf("csv"))
                dialog.isVisible = true
                val f = dialog.files.firstOrNull() ?: return done(null, null)
                try { done(copyCsv(f), null) } catch (e: Exception) { done(null, e.message ?: e.javaClass.simpleName) }
            }
        }
        Platform.main = object : MainThread {
            override fun post(r: () -> Unit) = SwingUtilities.invokeLater(r)
            override fun postDelayed(r: () -> Unit, delayMs: Long) {
                timer.schedule({ SwingUtilities.invokeLater(r) }, delayMs, TimeUnit.MILLISECONDS)
            }
        }
    }
}
