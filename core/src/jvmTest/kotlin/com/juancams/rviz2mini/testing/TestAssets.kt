// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.testing

import java.io.File

/** App assets (app/src/main/assets) from the tests of any module. */
object TestAssets {
    fun file(name: String): File =
        listOf("src/main/assets/$name", "../app/src/main/assets/$name", "app/src/main/assets/$name").map(::File).first { it.exists() }

    fun text(name: String): String = file(name).readText()
}
