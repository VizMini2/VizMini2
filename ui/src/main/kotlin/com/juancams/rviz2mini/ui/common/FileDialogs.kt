// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.common

/**
 * Pick a text file of the user's, provided by each platform (desktop: the system dialog). `done`
 * receives the content, or null if cancelled or it cannot be read. Null here = files cannot be picked.
 */
object FileDialogs {
    /** Same, but the content as a stream with the file name (for large or binary files). */
    @Volatile var openStream: ((extensions: List<String>, done: (String?, java.io.InputStream?) -> Unit) -> Unit)? = null
    @Volatile var openText: ((extensions: List<String>, done: (String?) -> Unit) -> Unit)? = null
    /** Save a text to a file the user picks (`suggested`: proposed name). `done(true)` if it was saved. */
    @Volatile var saveText: ((suggested: String, content: String, done: (Boolean) -> Unit) -> Unit)? = null
    /**
     * Hand over several files at once (a map's PGM and YAML...): the PC saves them next to the one the user picks;
     * Android offers them to other apps (Files, Drive, mail...). `done(where)`, null if cancelled or it failed.
     */
    @Volatile var saveFiles: ((files: List<Pair<String, ByteArray>>, done: (String?) -> Unit) -> Unit)? = null
}
