// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Windowless UI screenshot (for documentation and for checking it in CI): renders `content` for
 * `millis` and saves the last frame to `out` as PNG.
 */
/**
 * `events`: simulated mouse at 2.5 s, "press:x,y;move:x,y;release:x,y;wheel:x,y,dy;ctrlwheel:x,y,dy;wait:ms" (left button), to test
 * tools without a window.
 */
fun shot(out: File, width: Int = 1200, height: Int = 800, millis: Long = 4000, events: String? = null, content: @Composable () -> Unit) {
    // Everything on the Swing thread, like the windowed app (Platform.main notifications arrive on that thread).
    fun <T> edt(block: () -> T): T { var r: Result<T>? = null; javax.swing.SwingUtilities.invokeAndWait { r = runCatching(block) }; return r!!.getOrThrow() }
    val scene = edt { ImageComposeScene(width, height, Density(1f), content = content) }
    try {
        val start = System.nanoTime()
        var last = edt { scene.render(0) }
        var pending = events?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableList()
        var waitUntil = start + 2_500_000_000L
        while (System.nanoTime() - start < millis * 1_000_000) {
            val list = pending
            if (list != null && list.isNotEmpty() && System.nanoTime() >= waitUntil) {
                val ev = list.removeAt(0)
                val kind = ev.substringBefore(':'); val arg = ev.substringAfter(':')
                if (kind == "wait") waitUntil = System.nanoTime() + arg.toLong() * 1_000_000
                else if (kind == "wheel" || kind == "ctrlwheel") {
                    // "wheel:x,y,dy" (dy < 0 = up); "ctrlwheel" with Ctrl held down.
                    val (x, y, dy) = arg.split(',').map { it.trim().toFloat() }
                    edt {
                        @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
                        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll, androidx.compose.ui.geometry.Offset(x, y),
                            scrollDelta = androidx.compose.ui.geometry.Offset(0f, dy),
                            keyboardModifiers = androidx.compose.ui.input.pointer.PointerKeyboardModifiers(isCtrlPressed = kind == "ctrlwheel"))
                    }
                    waitUntil = System.nanoTime() + 60_000_000L
                } else {
                    val (x, y) = arg.split(',').map { it.trim().toFloat() }
                    val type = when (kind) { "press", "rpress" -> androidx.compose.ui.input.pointer.PointerEventType.Press; "release", "rrelease" -> androidx.compose.ui.input.pointer.PointerEventType.Release; else -> androidx.compose.ui.input.pointer.PointerEventType.Move }
                    val pressed = kind != "release" && kind != "rrelease"
                    val right = kind.startsWith("r") && kind != "release"
                    edt {
                        @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
                        scene.sendPointerEvent(type, androidx.compose.ui.geometry.Offset(x, y),
                            buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = pressed && !right, isSecondaryPressed = pressed && right),
                            button = if (kind == "move") null else if (right) androidx.compose.ui.input.pointer.PointerButton.Secondary else androidx.compose.ui.input.pointer.PointerButton.Primary)
                    }
                    waitUntil = System.nanoTime() + 60_000_000L
                }
            }
            Thread.sleep(50)
            last = edt { scene.render(System.nanoTime() - start) }
        }
        out.parentFile?.mkdirs()
        out.writeBytes(last.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    } finally {
        edt { scene.close() }
    }
}
