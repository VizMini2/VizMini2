// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.teleop

import kotlin.math.abs
import kotlin.math.sqrt

/** Normalized joystick position: x to the right, y upward, inside the unit circle. */
data class Stick(val x: Float, val y: Float) {
    val isCenter: Boolean get() = x == 0f && y == 0f

    companion object {
        val CENTER = Stick(0f, 0f)
    }
}

/** Velocity to publish (full geometry_msgs/Twist, REP-103: x forward, y left, z up). */
data class Velocity(
    val lx: Double = 0.0, val ly: Double = 0.0, val lz: Double = 0.0,
    val ax: Double = 0.0, val ay: Double = 0.0, val az: Double = 0.0
) {
    val isZero: Boolean get() = lx == 0.0 && ly == 0.0 && lz == 0.0 && ax == 0.0 && ay == 0.0 && az == 0.0

    // Compatibility with the 2-axis version.
    val linear: Double get() = lx
    val angular: Double get() = az

    companion object {
        val ZERO = Velocity()
        fun planar(linear: Double, angular: Double) = Velocity(lx = linear, az = angular)
    }
}

object Joystick {

    /**
     * Converts the finger offset from the center (in screen pixels, y pointing
     * down) to a [Stick] clamped to the unit circle.
     */
    fun fromTouch(dxPx: Float, dyPx: Float, radiusPx: Float): Stick {
        if (radiusPx <= 0f) return Stick.CENTER
        var x = dxPx / radiusPx
        var y = -dyPx / radiusPx
        val len = sqrt(x * x + y * y)
        if (len > 1f) { x /= len; y /= len }
        return Stick(x, y)
    }

    /**
     * Radial dead zone with rescaling: inside `deadzone` it is 0 and outside it grows from 0 to 1
     * continuously (without the jump that plain clipping would cause).
     */
    fun applyDeadzone(s: Stick, deadzone: Float): Stick {
        val len = sqrt(s.x * s.x + s.y * s.y)
        if (len <= deadzone || len == 0f) return Stick.CENTER
        val scaled = ((len - deadzone) / (1f - deadzone)).coerceAtMost(1f)
        return Stick(s.x / len * scaled, s.y / len * scaled)
    }

    /** Physical gamepad axes (-1..1, y pointing down as in Android) -> Stick with dead zone. */
    fun fromGamepad(axisX: Float, axisY: Float, deadzone: Float): Stick {
        var x = axisX.coerceIn(-1f, 1f); var y = (-axisY).coerceIn(-1f, 1f)
        val len = sqrt(x * x + y * y)
        if (len > 1f) { x /= len; y /= len }
        return applyDeadzone(Stick(x, y), deadzone)
    }
}

/** Twist component a joystick axis is assigned to. */
enum class AxisTarget(val label: String, val linear: Boolean) {
    NONE("None", true),
    LX("linear.x", true), LY("linear.y", true), LZ("linear.z", true),
    AX("angular.x", false), AY("angular.y", false), AZ("angular.z", false);

    companion object { fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: NONE }
}

/** Assignment of a physical axis: which component it goes to and with which sign. */
data class AxisAssignment(val target: AxisTarget, val inverted: Boolean = false) {
    fun encode() = "${target.name}:${if (inverted) "-" else "+"}"

    companion object {
        val NONE = AxisAssignment(AxisTarget.NONE)
        fun decode(s: String?): AxisAssignment {
            val parts = s?.split(":") ?: return NONE
            return AxisAssignment(AxisTarget.fromName(parts.getOrNull(0)), parts.getOrNull(1) == "-")
        }
    }
}

/** Input axes: left/single joystick (L) and right (R). */
enum class StickAxis { LX, LY, RX, RY }

/** Mapping of the 4 input axes to the Twist. */
data class AxisMapping(val axes: Map<StickAxis, AxisAssignment>) {

    fun of(a: StickAxis) = axes[a] ?: AxisAssignment.NONE

    /** Uses the right joystick (whether to draw the second joystick). */
    val usesRightStick: Boolean get() = of(StickAxis.RX).target != AxisTarget.NONE || of(StickAxis.RY).target != AxisTarget.NONE

    fun toVelocity(left: Stick, right: Stick, maxLinear: Double, maxAngular: Double): Velocity {
        val v = DoubleArray(7)
        fun add(axis: StickAxis, value: Float) {
            val a = of(axis)
            if (a.target == AxisTarget.NONE) return
            val scale = if (a.target.linear) maxLinear else maxAngular
            v[a.target.ordinal] += (if (a.inverted) -value else value) * scale
        }
        add(StickAxis.LX, left.x); add(StickAxis.LY, left.y)
        add(StickAxis.RX, right.x); add(StickAxis.RY, right.y)
        fun c(i: Int) = if (abs(v[i]) < EPS) 0.0 else v[i]
        return Velocity(c(1), c(2), c(3), c(4), c(5), c(6))
    }

    fun encode(): Map<String, String> = StickAxis.entries.associate { "map.${it.name}" to of(it).encode() }

    companion object {
        private const val EPS = 1e-4

        fun decode(m: Map<String, String>, fallback: AxisMapping): AxisMapping {
            if (StickAxis.entries.none { m.containsKey("map.${it.name}") }) return fallback
            return AxisMapping(StickAxis.entries.associateWith { AxisAssignment.decode(m["map.${it.name}"]) })
        }

        private fun of(vararg pairs: Pair<StickAxis, AxisAssignment>) = AxisMapping(pairs.toMap())

        // Right = clockwise turn (negative z) and right = -y (REP-103): X axes inverted.
        val DIFFERENTIAL = of(StickAxis.LY to AxisAssignment(AxisTarget.LX), StickAxis.LX to AxisAssignment(AxisTarget.AZ, true))
        val HOLONOMIC_SINGLE = of(StickAxis.LY to AxisAssignment(AxisTarget.LX), StickAxis.LX to AxisAssignment(AxisTarget.LY, true))
        val DUAL_DIFFERENTIAL = of(StickAxis.LY to AxisAssignment(AxisTarget.LX), StickAxis.RX to AxisAssignment(AxisTarget.AZ, true))
        val DUAL_HOLONOMIC = of(
            StickAxis.LY to AxisAssignment(AxisTarget.LX), StickAxis.LX to AxisAssignment(AxisTarget.LY, true),
            StickAxis.RX to AxisAssignment(AxisTarget.AZ, true)
        )
        /** RC Mode 2: left = altitude and yaw, right = forward and lateral. */
        val DRONE_MODE2 = of(
            StickAxis.LY to AxisAssignment(AxisTarget.LZ), StickAxis.LX to AxisAssignment(AxisTarget.AZ, true),
            StickAxis.RY to AxisAssignment(AxisTarget.LX), StickAxis.RX to AxisAssignment(AxisTarget.LY, true)
        )
    }
}

/** Presets offered in the settings (and their mapping). */
enum class TeleopPreset(val label: String, val dual: Boolean, val mapping: AxisMapping?) {
    DIFFERENTIAL("Differential (forward + turn)", false, AxisMapping.DIFFERENTIAL),
    HOLONOMIC("Holonomic (forward + strafe)", false, AxisMapping.HOLONOMIC_SINGLE),
    DUAL_DIFFERENTIAL("Two joysticks: forward / turn", true, AxisMapping.DUAL_DIFFERENTIAL),
    DUAL_HOLONOMIC("Two joysticks: holonomic + turn", true, AxisMapping.DUAL_HOLONOMIC),
    DRONE_MODE2("Two joysticks: drone (mode 2)", true, AxisMapping.DRONE_MODE2),
    CUSTOM("Custom", true, null);

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n }
        fun forMode(dual: Boolean) = entries.filter { it == CUSTOM || it.dual == dual }
    }
}

/**
 * Decides what to publish on each tick (at a fixed rate). No threads or Android: testable.
 *
 * - With the finger on the joystick the current velocity is published on every tick.
 * - On release, on pressing STOP or on hiding the tab, `stopRepeats` zeros are published and then
 *   nothing, so as not to fight other cmd_vel sources (Nav2, physical joystick...).
 * - After a STOP the joystick is ignored until it is released and touched again.
 * - **Watchdog**: if while driving the UI stops giving signs of life (`heartbeat`)
 *   for `watchdogMs`, it stops and locks like a STOP (frozen app, screen off...).
 */
class TeleopController(private val stopRepeats: Int = 3, private val watchdogMs: Long = 500) {

    private var command = Velocity.ZERO
    private var touching = false
    private var latchedStop = false
    private var pendingStops = 0
    private var lastHeartbeat = 0L

    /** Reason for the last automatic STOP (null if none). */
    var autoStopReason: String? = null
        private set

    /** The joystick has moved (or been touched). */
    fun onStick(v: Velocity, nowMs: Long = 0L) {
        if (latchedStop) return
        touching = true
        command = v
        pendingStops = 0
        lastHeartbeat = nowMs
    }

    /** UI heartbeat while there is a finger on the joystick. */
    fun heartbeat(nowMs: Long) { lastHeartbeat = nowMs }

    /** The finger has been lifted. */
    fun onRelease() {
        latchedStop = false
        if (touching) {
            touching = false
            command = Velocity.ZERO
            pendingStops = stopRepeats
        }
    }

    /** Emergency STOP, hidden tab or safety stop (`reason`). */
    fun stop(latch: Boolean, reason: String? = null) {
        touching = false
        command = Velocity.ZERO
        pendingStops = stopRepeats
        if (latch) latchedStop = true
        if (reason != null) autoStopReason = reason
    }

    /** Releases the lock (when hiding the tab or acknowledging the notice). */
    fun reset() {
        latchedStop = false
        autoStopReason = null
    }

    val isActive: Boolean get() = touching || pendingStops > 0
    val isTouching: Boolean get() = touching

    /** Message to publish on this tick, or null if nothing has to be published. */
    fun tick(nowMs: Long = 0L): Velocity? {
        if (touching && watchdogMs > 0 && lastHeartbeat > 0 && nowMs - lastHeartbeat > watchdogMs) {
            stop(latch = true, reason = "UI stopped responding ($watchdogMs ms)")
        }
        if (touching) return command
        if (pendingStops > 0) {
            pendingStops--
            return Velocity.ZERO
        }
        return null
    }
}

/** Settings of a Teleop tab. */
data class TeleopSettings(
    val topic: String = DEFAULT_TOPIC,
    val stamped: Boolean = false,
    val maxLinear: Double = 0.5,
    val maxAngular: Double = 1.0,
    val rateHz: Int = 10,
    val dual: Boolean = false,
    val preset: TeleopPreset = TeleopPreset.DIFFERENTIAL,
    val mapping: AxisMapping = AxisMapping.DIFFERENTIAL,
    /** Stop if the robot stops being subscribed or the network is lost while driving. */
    val stopOnConnectionLoss: Boolean = true
) {
    companion object {
        const val DEFAULT_TOPIC = "/cmd_vel"
        const val MAX_LINEAR_LIMIT = 3.0
        const val MAX_ANGULAR_LIMIT = 6.0

        fun defaults(dual: Boolean) = if (dual)
            TeleopSettings(dual = true, preset = TeleopPreset.DUAL_DIFFERENTIAL, mapping = AxisMapping.DUAL_DIFFERENTIAL)
        else TeleopSettings()

        fun fromMap(m: Map<String, String>, dual: Boolean = m["dual"] == "true"): TeleopSettings {
            val def = defaults(dual)
            val preset = TeleopPreset.fromName(m["preset"])?.takeIf { it == TeleopPreset.CUSTOM || it.dual == dual } ?: def.preset
            val mapping = preset.mapping ?: AxisMapping.decode(m, def.mapping)
            return TeleopSettings(
                topic = m["topic"]?.takeIf { it.isNotBlank() } ?: DEFAULT_TOPIC,
                stamped = m["stamped"] == "true",
                maxLinear = (m["maxLinear"]?.toDoubleOrNull() ?: 0.5).coerceIn(0.0, MAX_LINEAR_LIMIT),
                maxAngular = (m["maxAngular"]?.toDoubleOrNull() ?: 1.0).coerceIn(0.0, MAX_ANGULAR_LIMIT),
                rateHz = (m["rateHz"]?.toIntOrNull() ?: 10).coerceIn(1, 50),
                dual = dual, preset = preset, mapping = mapping,
                stopOnConnectionLoss = m["stopOnLoss"] != "false"
            )
        }
    }

    fun toMap(): Map<String, String> = mapOf(
        "topic" to topic, "stamped" to stamped.toString(),
        "maxLinear" to maxLinear.toString(), "maxAngular" to maxAngular.toString(),
        "rateHz" to rateHz.toString(), "dual" to dual.toString(), "preset" to preset.name,
        "stopOnLoss" to stopOnConnectionLoss.toString()
    ) + mapping.encode()
}
