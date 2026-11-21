// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.buttons

import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.i18n.trPlural
import com.juancams.rviz2mini.platform.Platform

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.CallException
import com.juancams.rviz2mini.ros.dyn.DynActionClient
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.DynServiceClient
import com.juancams.rviz2mini.ros.dyn.IdlException
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import com.juancams.rviz2mini.ros.dyn.MsgValue
import com.juancams.rviz2mini.ros.dyn.Prim
import com.juancams.rviz2mini.ros.dyn.ValueException
import com.juancams.rviz2mini.rviz.display.profile
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What a button shows below its text. `detail` is the full text (response, error...). */
sealed class ButtonState {
    data object Idle : ButtonState()
    data class Busy(val text: String) : ButtonState()
    data class Done(val ok: Boolean, val text: String, val detail: String? = null) : ButtonState()
}

/**
 * Runs a button: creates its publishers / clients when constructed (so DDS has already matched them
 * when it is pressed) and closes them in [close]. Closing with `cancelRunning` cancels the action in
 * progress; without it (the screen is recreated on rotation) it only stops waiting for its result. Each button has its own thread: a call to a
 * slow service does not block the others. `onState` is called on the main thread.
 */
class ButtonRunner(
    val spec: ButtonSpec,
    registry: InterfaceRegistry,
    private val onState: (ButtonState) -> Unit
) {

    private val main = Platform.main
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "button-${spec.displayLabel}").apply { isDaemon = true } }
    @Volatile private var closed = false
    @Volatile private var cancelRequested = false
    @Volatile private var cancelOnClose = false
    /** Keeps the node open when leaving the app until the cancellation has been requested. */
    @Volatile private var releaseHold: (() -> Unit)? = null
    private var repeating: ScheduledFuture<*>? = null

    /** Structure of what is sent (message, request or goal). */
    val payloadSpec: MsgSpec?
    /** Why the button cannot work (unknown type...), or null. */
    val setupError: String?

    private var publisher: DynRos.Publisher? = null
    private var service: DynServiceClient? = null
    private var action: DynActionClient? = null
    private var responseSpec: MsgSpec? = null

    /** Action in progress (so the UI offers to cancel). */
    @Volatile var actionRunning = false
        private set

    init {
        var payload: MsgSpec? = null
        var error: String? = null
        try {
            if (spec.target.isBlank()) throw IdlException(tr(when (spec.kind) {
                ButtonKind.PUBLISH -> "buttons_missing_topic_name"
                ButtonKind.SERVICE -> "buttons_missing_service_name"
                ButtonKind.ACTION -> "buttons_missing_action_name"
            }))
            when (spec.kind) {
                ButtonKind.PUBLISH -> {
                    payload = registry.msg(spec.type)
                    publisher = DynRos.publisher(DynRos.normalizeName(spec.target), payload, (spec.qos).profile())
                }
                ButtonKind.SERVICE -> {
                    val srv = registry.srv(spec.type)
                    payload = srv.request
                    responseSpec = srv.response
                    service = DynServiceClient(spec.target, srv)
                }
                ButtonKind.ACTION -> {
                    val a = registry.action(spec.type)
                    payload = a.goal
                    responseSpec = a.result
                    action = DynActionClient(spec.target, a, registry.srv("action_msgs/srv/CancelGoal"))
                }
            }
        } catch (e: IdlException) {
            error = e.message
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo preparar el botón ${spec.displayLabel}", e)
            error = e.message ?: e.javaClass.simpleName
        }
        payloadSpec = payload
        setupError = error
    }

    /** Press (normal buttons) or start of a held press (publish with repetition). */
    fun press() {
        if (closed) return
        setupError?.let { post(ButtonState.Done(false, it)); return }
        val payload = buildPayload() ?: return
        when (spec.kind) {
            ButtonKind.PUBLISH -> {
                if (spec.repeatHz > 0) startRepeating() else worker.execute { publishOnce(payload, waitForMatch = true) }
            }
            ButtonKind.SERVICE -> {
                post(ButtonState.Busy(tr("buttons_calling")))
                worker.execute { callService(payload) }
            }
            ButtonKind.ACTION -> {
                if (actionRunning) return
                actionRunning = true
                cancelRequested = false
                post(ButtonState.Busy(tr("buttons_sending_goal")))
                worker.execute { runAction(payload) }
            }
        }
    }

    /** End of a held press: stops repeating and, with [ButtonSpec.stopOnRelease], sends the stop. */
    fun release() {
        val r = repeating ?: return
        r.cancel(false)
        repeating = null
        // On the same thread as the repetitions: the stop is always the last thing sent.
        if (spec.stopOnRelease) worker.execute { publishStop() }
    }

    fun cancelAction() {
        if (actionRunning) {
            cancelRequested = true
            post(ButtonState.Busy(tr("buttons_canceling")))
        }
    }

    /** @param cancelRunning cancel the action in progress (really leaving, not a screen rotation). */
    fun close(cancelRunning: Boolean = true) {
        cancelOnClose = cancelRunning
        if (cancelRunning && actionRunning) releaseHold = RosConnection.holdOpen()
        closed = true
        release()
        worker.shutdown()
        // The clients are closed when the button thread finishes whatever it is doing (the waits
        // check `closed` and end right away; cancelling an action waits for its response).
        Thread({
            try { worker.awaitTermination(CLOSE_WAIT_S, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            publisher?.close(); service?.close(); action?.close()
        }, "button-close").apply { isDaemon = true }.start()
    }

    private fun buildPayload(): MsgValue? {
        val p = payloadSpec ?: return null
        return try {
            DynCodec.fromJson(p, JSONObject(spec.values.ifBlank { "{}" }), strict = true)
        } catch (e: ValueException) {
            post(ButtonState.Done(false, tr("buttons_invalid_value", e.message)))
            null
        } catch (e: Exception) {
            post(ButtonState.Done(false, tr("buttons_invalid_values", e.message)))
            null
        }
    }

    // ---------------------------------------------------------------- publish

    private fun startRepeating() {
        if (repeating != null) return
        val period = (1000.0 / spec.repeatHz).toLong().coerceAtLeast(10)
        var count = 0
        post(ButtonState.Busy(tr("buttons_publishing_at", fmt(spec.repeatHz))))
        repeating = worker.scheduleAtFixedRate({
            // Values are recomputed on every send (in case they carry the current time).
            val payload = payloadSpec?.let { p -> try { DynCodec.fromJson(p, JSONObject(spec.values), strict = true) } catch (_: Exception) { null } }
            if (payload != null && publishOnce(payload, waitForMatch = false, report = false)) count++
            if (count % 10 == 1) post(ButtonState.Busy(tr("buttons_publishing_at_count", fmt(spec.repeatHz), count)))
        }, 0, period, TimeUnit.MILLISECONDS)
    }

    private fun publishStop() {
        val p = payloadSpec ?: return
        val stop = try { stopPayload(p, DynCodec.fromJson(p, JSONObject(spec.values.ifBlank { "{}" }), strict = true)) } catch (_: Exception) { DynCodec.defaultValue(p) }
        if (publishOnce(stop, waitForMatch = false, report = false)) post(ButtonState.Done(true, tr("buttons_stopped")))
    }

    private fun publishOnce(payload: MsgValue, waitForMatch: Boolean, report: Boolean = true): Boolean {
        val p = publisher ?: return false
        // Just created, DDS may not have matched the subscribers yet: wait a little.
        if (waitForMatch) {
            val until = System.currentTimeMillis() + MATCH_WAIT_MS
            while (p.matched() == 0 && System.currentTimeMillis() < until && !closed) Thread.sleep(50)
        }
        val ok = try { p.publish(payload) } catch (e: Exception) { Log.e(TAG, "Error publicando", e); false }
        if (report) {
            val n = p.matched()
            post(
                if (!ok) ButtonState.Done(false, tr("buttons_no_connection"))
                else ButtonState.Done(true, trPlural("buttons_published_subscribers", n, n), DynCodec.format(p.spec, payload))
            )
        }
        return ok
    }

    // ---------------------------------------------------------------- service

    private fun callService(payload: MsgValue) {
        val s = service ?: return
        val start = System.currentTimeMillis()
        try {
            val r = s.call(payload, (spec.timeoutS * 1000).toLong()) { closed }
            val ms = System.currentTimeMillis() - start
            val res = responseSpec!!
            post(ButtonState.Done(true, tr("buttons_response_time", summary(res, r).ifEmpty { tr("buttons_responded") }, ms), DynCodec.format(res, r)))
        } catch (e: CallException) {
            post(ButtonState.Done(false, e.message ?: tr("buttons_error")))
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a ${spec.target}", e)
            post(ButtonState.Done(false, e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------------------------------------------------------------- action

    private fun runAction(payload: MsgValue) {
        val a = action ?: return
        // The final state is published after clearing actionRunning, so the UI removes "Cancel".
        var final: ButtonState
        try {
            val outcome = a.run(
                payload, (spec.timeoutS * 1000).toLong(),
                onAccepted = { post(ButtonState.Busy(tr("buttons_running"))) },
                onFeedback = { fb -> if (!cancelRequested) post(ButtonState.Busy(tr("buttons_running_feedback", summary(a.action.feedback, fb).ifEmpty { tr("buttons_feedback") }))) },
                cancelRequested = { cancelRequested },
                abandoned = { closed },
                cancelIfAbandoned = { cancelOnClose }
            )
            val res = responseSpec!!
            val text = outcome.statusLabel + summary(res, outcome.result).let { if (it.isEmpty()) "" else " · $it" }
            final = ButtonState.Done(outcome.status == DynActionClient.STATUS_SUCCEEDED, text, "status: ${outcome.statusLabel}\n" + DynCodec.format(res, outcome.result))
        } catch (e: CallException) {
            final = ButtonState.Done(false, e.message ?: tr("buttons_error"))
        } catch (e: Exception) {
            Log.e(TAG, "Error en la acción ${spec.target}", e)
            final = ButtonState.Done(false, e.message ?: e.javaClass.simpleName)
        }
        actionRunning = false
        releaseHold?.invoke()
        post(final)
    }

    private fun post(s: ButtonState) {
        main.post { if (!closed) onState(s) }
    }

    companion object {
        private const val TAG = "VizMini2"
        private const val MATCH_WAIT_MS = 1000L
        /** How long [close] waits for the thread to finish: long enough for a cancellation to respond. */
        private const val CLOSE_WAIT_S = 6L

        private fun fmt(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else d.toString()

        /**
         * Stop message: `v` with numbers and bools at zero / false, also inside submessages and
         * arrays. Texts (frame_id...) and timestamps stay as they were.
         */
        fun stopPayload(spec: MsgSpec, v: MsgValue): MsgValue {
            val out = MsgValue()
            for (f in spec.fields) {
                val x = v[f.name]
                out[f.name] = when {
                    f.nested?.isTime == true -> x
                    f.nested != null -> if (f.isArray) (x as? List<*>).orEmpty().map { stopPayload(f.nested!!, it as MsgValue) } else stopPayload(f.nested!!, x as MsgValue)
                    f.prim!!.isText -> x
                    x is ByteArray -> ByteArray(x.size)
                    f.isArray -> (x as? List<*>).orEmpty().map { DynCodec.defaultPrim(f.prim!!) }
                    else -> DynCodec.defaultPrim(f.prim!!)
                }
            }
            return out
        }

        /** One-line summary: "success: true · message: done". */
        fun summary(spec: MsgSpec, v: Map<String, Any?>, maxLen: Int = 90): String {
            val parts = ArrayList<String>()
            for (f in spec.fields) {
                val x = v[f.name]
                parts.add(f.name + ": " + when {
                    f.isArray -> (x as? List<*>).orEmpty().let { l ->
                        // Long lists: the first values and how many there are ("[0, 1, 1, 2, 3, 5, …] (11)"), not just "[11]".
                        if (f.prim == null) "[${l.size} × {…}]"
                        else if (l.size <= 6) l.joinToString(", ", "[", "]") { DynCodec.scalarText(f.prim!!, it) }
                        else l.take(6).joinToString(", ", "[", ", …] (${l.size})") { DynCodec.scalarText(f.prim!!, it) }
                    }
                    f.nested != null -> "{…}"
                    else -> DynCodec.scalarText(f.prim ?: Prim.STRING, x)
                })
            }
            val s = parts.joinToString(" · ")
            return if (s.length > maxLen) s.take(maxLen - 1) + "…" else s
        }
    }
}
