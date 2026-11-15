// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.sliders

import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.i18n.trPlural
import com.juancams.rviz2mini.platform.Platform

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.IdlException
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.Prim
import com.juancams.rviz2mini.ros.dyn.ValueException
import com.juancams.rviz2mini.rviz.display.QosPreset
import com.juancams.rviz2mini.rviz.display.profile
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What a slider shows below it: state of the last publication. */
sealed class SliderState {
    data object Idle : SliderState()
    data class Publishing(val text: String) : SliderState()
    data class Done(val ok: Boolean, val text: String) : SliderState()
}

/**
 * Publishes a slider. The publisher is created on construction (so DDS has already matched it when it
 * moves) and closed in [close]. Everything is published from a single dedicated thread (the jros2
 * publisher does not support several). `onState` is called on the main thread.
 */
class SliderRunner(
    val spec: SliderSpec,
    registry: InterfaceRegistry,
    private val onState: (SliderState) -> Unit
) {
    private val main = Platform.main
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "slider-${spec.displayLabel}").apply { isDaemon = true } }
    @Volatile private var closed = false
    private var repeating: ScheduledFuture<*>? = null
    private var count = 0

    /** Current value (the finger's while dragging). */
    @Volatile var value: Double = spec.initial
        private set

    val payloadSpec: MsgSpec?
    /** Type of the field the slider moves (null if it cannot be used). */
    val fieldPrim: Prim?
    /** Why it cannot work (unknown type or field...), or null. */
    val setupError: String?
    private var publisher: DynRos.Publisher? = null

    init {
        var payload: MsgSpec? = null
        var prim: Prim? = null
        var error: String? = null
        try {
            if (spec.target.isBlank()) throw IdlException(tr("sliders_missing_topic"))
            payload = registry.msg(spec.type)
            prim = SliderMath.fieldPrim(payload, spec.field) ?: throw IdlException(tr("sliders_bad_field", spec.field, spec.type))
            publisher = DynRos.publisher(DynRos.normalizeName(spec.target), payload, (QosPreset.RELIABLE).profile())
        } catch (e: IdlException) {
            error = e.message
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo preparar el slider ${spec.displayLabel}", e)
            error = e.message ?: e.javaClass.simpleName
        }
        payloadSpec = payload
        fieldPrim = prim
        setupError = error
    }

    val isInteger: Boolean get() = fieldPrim?.isInteger == true

    /** The finger touches the slider: with [SliderMode.WHILE_DRAGGING] it starts publishing at `rateHz`. */
    fun touchStart() {
        if (closed || setupError != null) return
        if (spec.mode != SliderMode.WHILE_DRAGGING || repeating != null) return
        count = 0
        val period = (1000.0 / spec.rateHz).toLong().coerceAtLeast(20)
        repeating = worker.scheduleAtFixedRate({
            if (publish(value)) count++
            if (count % 10 == 1) report(tr("sliders_publishing_at", fmtHz(spec.rateHz), matched()), busy = true)
        }, 0, period, TimeUnit.MILLISECONDS)
    }

    /** New finger value (already snapped to the step). Only stored: the rate loop or release publishes it. */
    fun moveTo(v: Double) { value = v }

    /**
     * The finger lifts: stops rate publishing and sends the final value (or the initial one, if it springs
     * back). Returns the value the slider ends up at.
     */
    fun touchEnd(): Double {
        repeating?.cancel(false)
        repeating = null
        if (spec.springBack && spec.mode == SliderMode.WHILE_DRAGGING) value = spec.initial
        sendOnce(value)
        return value
    }

    /** A value typed by hand: published once. */
    fun setExact(v: Double) {
        value = v
        sendOnce(v)
    }

    /**
     * The tab is no longer visible: it never keeps publishing in the background. If the slider springs back,
     * the initial value is published (release = stop).
     */
    fun pause(): Double {
        val wasDragging = repeating != null
        repeating?.cancel(false)
        repeating = null
        if (spec.springBack && spec.mode == SliderMode.WHILE_DRAGGING && (wasDragging || value != spec.initial)) {
            value = spec.initial
            sendOnce(value)
        }
        return value
    }

    fun close() {
        closed = true
        repeating?.cancel(false)
        repeating = null
        worker.shutdown()
        // The publisher is closed once pending work finishes (e.g. the return to the initial value).
        Thread({
            try { worker.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            publisher?.close()
        }, "slider-close").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- publishing

    private fun sendOnce(v: Double) {
        if (setupError != null) { post(SliderState.Done(false, setupError)); return }
        if (worker.isShutdown) return
        worker.execute {
            // Freshly created, DDS may not have matched the subscribers yet: wait a little.
            val p = publisher ?: return@execute
            val until = System.currentTimeMillis() + MATCH_WAIT_MS
            while (p.matched() == 0 && System.currentTimeMillis() < until && !closed) Thread.sleep(50)
            if (publish(v)) report(trPlural("sliders_published", matched(), format(v), matched()), busy = false)
        }
    }

    /** On the slider thread. False (with the error already shown) if it could not. */
    private fun publish(v: Double): Boolean {
        val p = publisher ?: return false
        val spec = payloadSpec ?: return false
        val msg = try {
            SliderMath.payload(spec, this.spec.values, this.spec.field, v)
        } catch (e: ValueException) {
            post(SliderState.Done(false, tr("sliders_invalid_value", e.message)))
            return false
        } catch (e: Exception) {
            post(SliderState.Done(false, tr("sliders_invalid_value", e.message)))
            return false
        }
        val ok = try { p.publish(msg) } catch (e: Exception) { Log.e(TAG, "Error publicando", e); false }
        if (!ok) post(SliderState.Done(false, tr("sliders_no_connection")))
        return ok
    }

    private fun matched() = try { publisher?.matched() ?: 0 } catch (_: Exception) { 0 }

    private fun report(text: String, busy: Boolean) = post(if (busy) SliderState.Publishing(text) else SliderState.Done(true, text))

    fun format(v: Double): String = SliderMath.format(v, SliderMath.decimals(spec.min, spec.max, spec.step), isInteger)

    private fun post(s: SliderState) {
        main.post { if (!closed) onState(s) }
    }

    private fun fmtHz(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else d.toString()

    private companion object {
        const val TAG = "VizMini2"
        const val MATCH_WAIT_MS = 1000L
    }
}
