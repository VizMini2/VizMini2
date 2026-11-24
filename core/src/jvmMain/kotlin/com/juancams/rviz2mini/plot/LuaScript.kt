// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plot

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib

/** Script that is badly written or fails at runtime (the message comes from Lua). */
class ScriptException(message: String) : Exception(message)

/**
 * Series computed with Lua: `globals` runs once
 * (variables kept between samples) and `body` is the body of
 * `function(time, value, v1, v2, ...)`, which returns the value (or nil to add no sample).
 * `time` is in seconds, `value` is the first input and v1, v2... the rest.
 *
 * No io, os or file loading: only base, math, string, table and bit32.
 */
class LuaScript(globals: String, body: String, inputs: Int) {

    private val g: Globals = sandbox()
    private val fn: LuaValue

    init {
        try {
            if (globals.isNotBlank()) g.load(globals, "globals").call()
            val params = (listOf("time", "value") + (1 until inputs.coerceAtLeast(1)).map { "v$it" }).joinToString(", ")
            fn = g.load("return function($params)\n$body\nend", "function").call()
        } catch (e: LuaError) {
            throw ScriptException(clean(e))
        }
    }

    /** Value for instant `timeS` with the inputs (the first is `value`); null if it returns nil. */
    fun call(timeS: Double, inputs: DoubleArray): Double? {
        val args = arrayOfNulls<LuaValue>(inputs.size + 1)
        args[0] = LuaValue.valueOf(timeS)
        for (i in inputs.indices) args[i + 1] = LuaValue.valueOf(inputs[i])
        val r: Varargs = try { fn.invoke(LuaValue.varargsOf(args)) } catch (e: LuaError) { throw ScriptException(clean(e)) } catch (e: StackOverflowError) { throw ScriptException("stack overflow") }
        val v = r.arg1()
        return when {
            v.isnil() -> null
            v.isnumber() -> v.todouble()
            v.isboolean() -> if (v.toboolean()) 1.0 else 0.0
            else -> throw ScriptException("the function must return a number, not ${v.typename()}")
        }
    }

    private companion object {
        fun sandbox(): Globals = Globals().apply {
            load(JseBaseLib()); load(PackageLib()); load(Bit32Lib()); load(TableLib()); load(StringLib()); load(JseMathLib())
            LoadState.install(this); LuaC.install(this)
            // No reading files or loading modules.
            for (name in listOf("dofile", "loadfile", "require", "collectgarbage")) set(name, LuaValue.NIL)
            set("package", LuaValue.NIL)
        }

        fun clean(e: LuaError): String = (e.message ?: "Lua error").replace("[string \"function\"]", "line").replace("[string \"globals\"]", "globals")
    }
}
