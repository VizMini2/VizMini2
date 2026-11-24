// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.bag

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynCodec
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import com.juancams.rviz2mini.ros.dyn.MsgSpec
import us.ihmc.fastddsjava.cdr.CDRBuffer
import java.io.File

/**
 * Plays an MCAP by publishing its messages on the network (like `ros2 bag play`), respecting the
 * timing at the chosen speed. Topics whose type the app doesn't know are skipped.
 */
/**
 * `direct`: messages go straight to the app's tabs ([RosConnection.deliverFromBag]) instead
 * of being published over DDS: nothing goes out to the network and all tabs follow the same timeline.
 */
class BagPlayer(val file: File, private val registry: InterfaceRegistry, private val listener: Listener, val direct: Boolean = false) {

    interface Listener {
        /** Progress in ns since the start of the bag. From the playback thread. */
        fun onProgress(positionNs: Long, durationNs: Long)
        fun onFinished(error: String?)
    }

    /** `publisher` and `spec` only when publishing over DDS (in direct mode the CDR is delivered as is). */
    private class Out(val topic: String, val spec: MsgSpec?, val publisher: DynRos.Publisher?, val latched: Boolean)

    val reader: McapReader = McapReader.open(file)
    /** Topics that cannot be published (unknown type or non-CDR encoding). */
    val skipped = ArrayList<String>()
    @Volatile var rate = 1.0
    @Volatile var loop = false
    @Volatile var paused = false
    /** Current position in ns since the start of the bag. */
    @Volatile var positionNs = 0L; private set
    @Volatile private var stopped = false
    /** Pending seek, in ns since the start of the bag (handled by the playback thread). */
    @Volatile private var seekRequest: Long? = null
    private var thread: Thread? = null

    private val outputs: Map<Int, Out> = reader.channels.values.mapNotNull { c ->
        // In direct mode it is enough for it to be CDR: each tab reads it with its own definition.
        if (c.messageEncoding != "cdr" || (!direct && !registry.has(c.schemaName))) {
            skipped += "${c.topic} (${c.schemaName})"
            return@mapNotNull null
        }
        val latched = BagRecorder.latched(c.topic) || c.metadata["offered_qos_profiles"]?.contains("transient_local") == true
        if (direct) return@mapNotNull c.id to Out(c.topic, null, null, latched)
        val spec = registry.msg(c.schemaName)
        c.id to Out(c.topic, spec, latched = latched, publisher = DynRos.publisher(c.topic, spec, BagRecorder.qos(latched).apply { if (!latched) {
            reliability(us.ihmc.jros2.ROS2QoSProfile.Reliability.RELIABLE); depth(10)
        } }))
    }.toMap()

    val topics: List<String> get() = reader.channels.values.map { it.topic }.sorted()

    fun start() {
        if (thread != null) return
        stopped = false
        thread = Thread({ run() }, "bag-player").apply { isDaemon = true; start() }
    }

    /** Seeks to `positionNs` from the start of the bag (also while paused). */
    fun seek(positionNs: Long) {
        seekRequest = positionNs.coerceIn(0, reader.durationNs)
    }

    fun stop() {
        stopped = true
        thread?.interrupt()
        thread = null
    }

    fun close() {
        stop()
        for (o in outputs.values) o.publisher?.close()
        reader.close()
    }

    private fun run() {
        var error: String? = null
        try {
            // Newly created publishers need a moment to match.
            if (!direct) Thread.sleep(800)
            val t0 = reader.startTimeNs
            do {
                var from = t0 + (seekRequest.also { seekRequest = null } ?: 0L)
                // It iterates from `from`; a seek starts again from the requested instant.
                while (!stopped) {
                    if (from > t0) republishLatched(from)
                    progress(from - t0)
                    val clock = PlaybackClock(System.nanoTime(), from, rate)
                    var jumped: Long? = null
                    for (m in reader.messages(startNs = from, channelIds = outputs.keys)) {
                        if (stopped) return
                        // Waits until the message's instant, in short slices to react quickly to pause,
                        // speed changes and seeks.
                        while (!stopped) {
                            seekRequest?.let { jumped = it; seekRequest = null }
                            if (jumped != null) break
                            if (paused) { Thread.sleep(50); clock.anchor(System.nanoTime(), m.logTimeNs); continue }
                            clock.setRate(rate, System.nanoTime())
                            val wait = clock.dueWallNs(m.logTimeNs) - System.nanoTime()
                            if (wait <= 0) break
                            val step = minOf(wait, MAX_SLEEP_NS)
                            Thread.sleep(step / 1_000_000, (step % 1_000_000).toInt())
                        }
                        if (stopped) return
                        if (jumped != null) break
                        val out = outputs[m.channelId] ?: continue
                        emit(out, m.data, m.logTimeNs)
                        progress(m.logTimeNs - t0)
                    }
                    from = t0 + (jumped ?: break)
                }
            } while (loop && !stopped)
        } catch (_: InterruptedException) {
            return
        } catch (e: Exception) {
            Log.e("VizMini2", "Error reproduciendo ${file.name}", e)
            error = e.message ?: e.javaClass.simpleName
        }
        if (!stopped) listener.onFinished(error)
    }

    /**
     * When seeking forward, the "latched" topics (/tf_static, /map, /robot_description...) were
     * published before the landing point: the last one of each is published again.
     */
    private fun republishLatched(beforeNs: Long) {
        val latched = outputs.filterValues { it.latched }.keys
        if (latched.isEmpty()) return
        val last = HashMap<Int, ByteArray>()
        for (m in reader.messages(endNs = beforeNs - 1, channelIds = latched)) last[m.channelId] = m.data
        for ((ch, data) in last) emit(outputs[ch] ?: continue, data, beforeNs)
    }

    private fun progress(pos: Long) {
        positionNs = pos
        listener.onProgress(pos, reader.durationNs)
    }

    private fun emit(out: Out, data: ByteArray, logTimeNs: Long) {
        if (direct) { RosConnection.deliverFromBag(out.topic, data, logTimeNs); return }
        try {
            out.publisher!!.publish(deserialize(out.spec!!, data))
        } catch (e: Exception) {
            Log.w("VizMini2", "Mensaje no válido en ${file.name}", e)
        }
    }

    companion object {
        /** The longest it sleeps at once: so pause and speed changes take effect right away. */
        private const val MAX_SLEEP_NS = 50_000_000L

        fun deserialize(spec: MsgSpec, data: ByteArray): com.juancams.rviz2mini.ros.dyn.MsgValue = com.juancams.rviz2mini.ros.dyn.CdrBytes.deserialize(spec, data)
    }
}

/**
 * Playback clock: at which instant of the phone's clock each bag message is due. When the speed
 * changes it is re-anchored at the current position, so what has already played is not reinterpreted with the new one
 * (previously, going from 1x to 2x released a sudden burst of messages and going to 0.5x stalled).
 */
class PlaybackClock(wallNs: Long, bagNs: Long, rate: Double) {
    private var wallStart = wallNs
    private var bagStart = bagNs
    var rate = rate
        private set

    /** The bag instant that corresponds to now. */
    fun bagNsAt(wallNs: Long): Long = bagStart + ((wallNs - wallStart) * rate).toLong()

    /** The phone clock instant at which the message with `bagNs` is due. */
    fun dueWallNs(bagNs: Long): Long = wallStart + ((bagNs - bagStart) / rate).toLong()

    fun setRate(newRate: Double, nowNs: Long) {
        if (newRate == rate || newRate <= 0.0) return
        anchor(nowNs, bagNsAt(nowNs))
        rate = newRate
    }

    /** The bag is at `bagNs` at instant `wallNs` (when resuming after a pause). */
    fun anchor(wallNs: Long, bagNs: Long) {
        wallStart = wallNs
        bagStart = bagNs
    }
}
