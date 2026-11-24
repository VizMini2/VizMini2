// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import java.io.File

/**
 * Windows' own open and save dialogs (the current Explorer ones). AWT's FileDialog hooks into them, which makes Windows
 * show the old Windows XP style ones; without a hook, GetOpenFileName and GetSaveFileName give the current ones. They
 * run on a thread of their own (the window keeps drawing), modal to the app's window.
 */
internal object WindowsFileDialogs {
    @Suppress("FunctionName")
    private interface Comdlg32 : Library {
        fun GetOpenFileNameW(ofn: OpenFileName): Boolean
        fun GetSaveFileNameW(ofn: OpenFileName): Boolean
    }

    @Suppress("FunctionName")
    private interface Ole32 : Library {
        fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
    }

    /** OPENFILENAMEW (commdlg.h). */
    @Suppress("unused", "PropertyName")
    @Structure.FieldOrder("lStructSize", "hwndOwner", "hInstance", "lpstrFilter", "lpstrCustomFilter", "nMaxCustFilter",
        "nFilterIndex", "lpstrFile", "nMaxFile", "lpstrFileTitle", "nMaxFileTitle", "lpstrInitialDir", "lpstrTitle", "Flags",
        "nFileOffset", "nFileExtension", "lpstrDefExt", "lCustData", "lpfnHook", "lpTemplateName", "pvReserved",
        "dwReserved", "FlagsEx")
    class OpenFileName : Structure() {
        @JvmField var lStructSize = 0
        @JvmField var hwndOwner: Pointer? = null
        @JvmField var hInstance: Pointer? = null
        @JvmField var lpstrFilter: Pointer? = null
        @JvmField var lpstrCustomFilter: Pointer? = null
        @JvmField var nMaxCustFilter = 0
        @JvmField var nFilterIndex = 0
        @JvmField var lpstrFile: Pointer? = null
        @JvmField var nMaxFile = 0
        @JvmField var lpstrFileTitle: Pointer? = null
        @JvmField var nMaxFileTitle = 0
        @JvmField var lpstrInitialDir: WString? = null
        @JvmField var lpstrTitle: WString? = null
        @JvmField var Flags = 0
        @JvmField var nFileOffset: Short = 0
        @JvmField var nFileExtension: Short = 0
        @JvmField var lpstrDefExt: WString? = null
        @JvmField var lCustData: Pointer? = null
        @JvmField var lpfnHook: Pointer? = null
        @JvmField var lpTemplateName: Pointer? = null
        @JvmField var pvReserved: Pointer? = null
        @JvmField var dwReserved = 0
        @JvmField var FlagsEx = 0
    }

    private const val OFN_OVERWRITEPROMPT = 0x2
    private const val OFN_HIDEREADONLY = 0x4
    private const val OFN_NOCHANGEDIR = 0x8
    private const val OFN_PATHMUSTEXIST = 0x800
    private const val OFN_FILEMUSTEXIST = 0x1000
    private const val OFN_EXPLORER = 0x80000
    private const val MAX_PATH_CHARS = 32768

    private val comdlg by lazy { Native.load("comdlg32", Comdlg32::class.java) }
    private val ole by lazy { Native.load("ole32", Ole32::class.java) }

    /** Whether these dialogs can be used (Windows, and JNA loads). */
    val available: Boolean by lazy {
        System.getProperty("os.name", "").lowercase().startsWith("windows") && runCatching { comdlg; ole; true }.getOrDefault(false)
    }

    /** "Name (*.a;*.b)\0*.a;*.b\0All files\0*.*\0\0" in UTF-16, as the dialogs take it. */
    private fun filter(name: String, exts: List<String>, all: String): Memory {
        val parts = buildList {
            if (exts.isNotEmpty()) { val p = exts.joinToString(";") { "*.$it" }; add("$name ($p)"); add(p) }
            add(all); add("*.*")
        }
        val text = parts.joinToString("\u0000") + "\u0000\u0000"
        return Memory((text.length + 1) * 2L).apply { clear(); setWideString(0, text) }
    }

    /**
     * Shows the dialog and gives the file chosen (null if cancelled) on the Swing thread. `exts` without dots; for save,
     * `suggested` is the proposed name and its extension is added if the user writes none.
     */
    fun show(save: Boolean, title: String, exts: List<String>, filterName: String, allFiles: String, suggested: String?, done: (File?) -> Unit) {
        val owner = DesktopRecorder.window
        Thread({
            // The current dialogs need COM in single-threaded apartment mode on the calling thread.
            runCatching { ole.CoInitializeEx(null, 0x2) }
            val buffer = Memory(MAX_PATH_CHARS * 2L).apply { clear(); if (suggested != null) setWideString(0, suggested) }
            val ofn = OpenFileName().apply {
                lStructSize = size()
                hwndOwner = owner?.let { runCatching { Native.getWindowPointer(it) }.getOrNull() }
                lpstrFilter = filter(filterName, exts, allFiles)
                nFilterIndex = 1
                lpstrFile = buffer
                nMaxFile = MAX_PATH_CHARS
                lpstrTitle = WString(title)
                lpstrDefExt = (if (save) suggested?.substringAfterLast('.', "") else exts.firstOrNull())?.takeIf { it.isNotEmpty() }?.let { WString(it) }
                Flags = OFN_EXPLORER or OFN_NOCHANGEDIR or OFN_HIDEREADONLY or OFN_PATHMUSTEXIST or
                    (if (save) OFN_OVERWRITEPROMPT else OFN_FILEMUSTEXIST)
            }
            val ok = runCatching { if (save) comdlg.GetSaveFileNameW(ofn) else comdlg.GetOpenFileNameW(ofn) }.getOrDefault(false)
            val file = if (ok) File(buffer.getWideString(0)) else null
            javax.swing.SwingUtilities.invokeLater { done(file) }
        }, "file-dialog").apply { isDaemon = true }.start()
    }
}
