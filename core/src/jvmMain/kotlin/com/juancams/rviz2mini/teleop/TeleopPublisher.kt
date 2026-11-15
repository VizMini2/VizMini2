// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.teleop

import com.juancams.rviz2mini.platform.Platform
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.juancams.rviz2mini.ros.RosConnection
import geometry_msgs.Twist
import geometry_msgs.TwistStamped

/**
 * Publishes the joystick command at a fixed rate on its own thread. The logic of when
 * to publish is in [TeleopController]; here the loop is scheduled, the messages are filled and the
 * connection is watched (automatic stop if it is lost while driving).
 */
class TeleopPublisher(val settings: TeleopSettings, private val listener: (State) -> Unit) {

    data class State(
        val lastSent: Velocity?,
        val publishing: Boolean,
        val subscribers: Int,
        val connected: Boolean,
        /** Reason for the last automatic stop (watchdog, connection lost...). */
        val autoStop: String?
    )

    /** The publisher's own thread (everything below runs here, in order). */
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "teleop-publisher").apply { isDaemon = true } }
    private val handler = object {
        fun post(r: () -> Unit) { if (!exec.isShutdown) exec.execute(r) }
        fun postDelayed(r: Runnable, ms: Long): ScheduledFuture<*>? = if (exec.isShutdown) null else exec.schedule(r, ms, TimeUnit.MILLISECONDS)
    }
    private val mainHandler get() = Platform.main
    private val controller = TeleopController(stopRepeats = 3, watchdogMs = WATCHDOG_MS)
    private val periodMs = (1000L / settings.rateHz).coerceAtLeast(1)

    private val twistPublisher = if (!settings.stamped) RosConnection.createPublisher(settings.topic, Twist::class.java) else null
    private val stampedPublisher = if (settings.stamped) RosConnection.createPublisher(settings.topic, TwistStamped::class.java) else null
    private val twist = Twist()
    private val stamped = TwistStamped()

    // Publisher thread only.
    private var ticking = false
    private var lastSent: Velocity? = null
    private var closed = false
    private var hadSubscribersWhileDriving = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            checkConnection()
            controller.tick(now())?.let { send(it) }
            if (controller.isActive) handler.postDelayed(this, periodMs) else ticking = false
            report()
        }
    }

    private val statusRunnable = object : Runnable {
        override fun run() {
            report()
            handler.postDelayed(this, STATUS_PERIOD_MS)
        }
    }

    init {
        handler.post { statusRunnable.run() }
    }

    private fun now() = System.nanoTime() / 1_000_000

    fun onStick(v: Velocity) = handler.post {
        controller.onStick(v, now())
        ensureTicking()
    }

    /** Keep-alive signal from the UI while a finger (or the gamepad) is active. */
    fun heartbeat() = handler.post { controller.heartbeat(now()) }

    fun onRelease() = handler.post {
        controller.onRelease()
        hadSubscribersWhileDriving = false
        ensureTicking()
    }

    /** Manual STOP (`latch`), hidden tab, or safety stop with `reason`. */
    fun stop(latch: Boolean, reason: String? = null) = handler.post {
        controller.stop(latch, reason)
        if (!latch) controller.reset()
        hadSubscribersWhileDriving = false
        ensureTicking()
    }

    /** The user has seen the automatic stop warning. */
    fun acknowledge() = handler.post { controller.reset(); report() }

    /** Sends the pending zeros immediately and releases the publisher and the thread. */
    fun close() {
        handler.post {
            controller.stop(latch = false)
            while (true) send(controller.tick(now()) ?: break)
            closed = true
            twistPublisher?.close()
            stampedPublisher?.close()
            exec.shutdownNow()
        }
    }

    private fun ensureTicking() {
        if (ticking || closed) return
        ticking = true
        handler.post { tickRunnable.run() } // the first message goes out right away, without waiting a period
    }

    private fun subscribers() = (twistPublisher?.matchedSubscribers() ?: stampedPublisher?.matchedSubscribers()) ?: 0

    /** Safety stop if, while driving, ROS 2 goes down or the robot stops listening. */
    private fun checkConnection() {
        if (!settings.stopOnConnectionLoss || !controller.isTouching) return
        if (RosConnection.status !is RosConnection.Status.Connected) {
            controller.stop(latch = true, reason = "ROS 2 connection lost")
            return
        }
        val subs = subscribers()
        if (subs > 0) hadSubscribersWhileDriving = true
        else if (hadSubscribersWhileDriving) {
            controller.stop(latch = true, reason = "The robot stopped listening on ${settings.topic}")
            hadSubscribersWhileDriving = false
        }
    }

    private fun send(v: Velocity) {
        val ok = if (settings.stamped) {
            val nowMs = System.currentTimeMillis()
            stamped.header.stamp.sec = (nowMs / 1000).toInt()
            stamped.header.stamp.nanosec = ((nowMs % 1000) * 1_000_000).toInt()
            fill(stamped.twist, v)
            stampedPublisher!!.publish(stamped)
        } else {
            fill(twist, v)
            twistPublisher!!.publish(twist)
        }
        if (ok) lastSent = v
    }

    private fun fill(t: Twist, v: Velocity) {
        t.linear.x = v.lx; t.linear.y = v.ly; t.linear.z = v.lz
        t.angular.x = v.ax; t.angular.y = v.ay; t.angular.z = v.az
    }

    private fun report() {
        val state = State(
            lastSent = lastSent,
            publishing = controller.isActive,
            subscribers = subscribers(),
            connected = RosConnection.status is RosConnection.Status.Connected,
            autoStop = controller.autoStopReason
        )
        mainHandler.post { if (!closed) listener(state) }
    }

    private companion object {
        const val STATUS_PERIOD_MS = 500L
        const val WATCHDOG_MS = 600L
    }
}
