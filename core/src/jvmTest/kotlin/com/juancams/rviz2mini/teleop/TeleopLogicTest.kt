// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.teleop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class JoystickTest {

    private val eps = 1e-5f

    @Test
    fun touchIsNormalizedAndYIsUp() {
        val s = Joystick.fromTouch(50f, -100f, 200f)
        assertEquals(0.25f, s.x, eps)
        assertEquals(0.5f, s.y, eps)
    }

    @Test
    fun touchOutsideIsClampedToCircle() {
        val s = Joystick.fromTouch(300f, 400f, 100f)
        assertEquals(1f, sqrt(s.x * s.x + s.y * s.y), eps)
        assertEquals(0.6f, s.x, eps)
        assertEquals(-0.8f, s.y, eps)
    }

    @Test
    fun deadzoneIsContinuous() {
        assertEquals(Stick.CENTER, Joystick.applyDeadzone(Stick(0.05f, 0.05f), 0.1f))
        val justOutside = Joystick.applyDeadzone(Stick(0f, 0.11f), 0.1f)
        assertTrue(justOutside.y > 0f && justOutside.y < 0.02f)
        assertEquals(1f, Joystick.applyDeadzone(Stick(0f, 1f), 0.1f).y, eps)
    }

    @Test
    fun gamepadAxesYDownAndDeadzone() {
        // On Android the gamepad Y axis is positive downwards.
        val up = Joystick.fromGamepad(0f, -1f, 0.1f)
        assertEquals(1f, up.y, eps)
        assertTrue(Joystick.fromGamepad(0.05f, 0.05f, 0.1f).isCenter)
        val diag = Joystick.fromGamepad(1f, 1f, 0f)
        assertEquals(1f, sqrt(diag.x * diag.x + diag.y * diag.y), eps)
    }
}

class AxisMappingTest {
    private val up = Stick(0f, 1f)
    private val right = Stick(1f, 0f)

    @Test
    fun differentialFollowsRep103() {
        val m = AxisMapping.DIFFERENTIAL
        assertEquals(Velocity.planar(0.5, 0.0), m.toVelocity(up, Stick.CENTER, 0.5, 1.0))
        val r = m.toVelocity(right, Stick.CENTER, 0.5, 1.0)
        assertEquals(0.0, r.lx, 1e-9)
        assertEquals("Derecha = giro horario = z negativo", -1.0, r.az, 1e-9)
        assertFalse(m.usesRightStick)
    }

    @Test
    fun holonomicSingleStrafes() {
        val v = AxisMapping.HOLONOMIC_SINGLE.toVelocity(right, Stick.CENTER, 0.5, 1.0)
        assertEquals("Derecha = -y (REP-103)", -0.5, v.ly, 1e-9)
        assertEquals(0.0, v.az, 0.0)
    }

    @Test
    fun dualDifferentialUsesRightStickForTurning() {
        val m = AxisMapping.DUAL_DIFFERENTIAL
        assertTrue(m.usesRightStick)
        val v = m.toVelocity(up, right, 0.5, 2.0)
        assertEquals(0.5, v.lx, 1e-9); assertEquals(-2.0, v.az, 1e-9)
        // The left stick's X axis does nothing in this preset.
        assertTrue(m.toVelocity(right, Stick.CENTER, 0.5, 2.0).isZero)
    }

    @Test
    fun dualHolonomicAllThree() {
        val v = AxisMapping.DUAL_HOLONOMIC.toVelocity(Stick(-1f, 1f), Stick(-1f, 0f), 1.0, 1.0)
        assertEquals(1.0, v.lx, 1e-9); assertEquals(1.0, v.ly, 1e-9); assertEquals(1.0, v.az, 1e-9)
    }

    @Test
    fun droneMode2() {
        val v = AxisMapping.DRONE_MODE2.toVelocity(Stick(0f, 1f), Stick(0f, 1f), 0.8, 1.0)
        assertEquals("izquierdo arriba = subir", 0.8, v.lz, 1e-9)
        assertEquals("derecho arriba = avanzar", 0.8, v.lx, 1e-9)
    }

    @Test
    fun customMappingAccumulatesAndEncodes() {
        val m = AxisMapping(mapOf(
            StickAxis.LY to AxisAssignment(AxisTarget.LX), StickAxis.RY to AxisAssignment(AxisTarget.LX, inverted = true),
            StickAxis.RX to AxisAssignment(AxisTarget.AY)
        ))
        assertEquals("Dos ejes al mismo componente se suman", 0.0, m.toVelocity(up, up, 1.0, 1.0).lx, 1e-9)
        assertEquals(0.5, m.toVelocity(Stick.CENTER, Stick(0.5f, 0f), 1.0, 1.0).ay, 1e-6)
        assertEquals(m.encode(), AxisMapping.decode(m.encode(), AxisMapping.DIFFERENTIAL).encode())
        assertEquals(AxisMapping.DIFFERENTIAL, AxisMapping.decode(emptyMap(), AxisMapping.DIFFERENTIAL))
    }

    @Test
    fun centerIsExactlyZero() {
        assertTrue(AxisMapping.DUAL_HOLONOMIC.toVelocity(Stick(0.00001f, -0.00001f), Stick.CENTER, 1.0, 1.0).isZero)
    }

    @Test
    fun presetsMatchMode() {
        assertTrue(TeleopPreset.forMode(false).none { it.dual && it != TeleopPreset.CUSTOM })
        assertTrue(TeleopPreset.forMode(true).all { it.dual })
    }
}

class TeleopControllerTest {

    private fun TeleopController.drain(now: Long = 0L, max: Int = 100): List<Velocity?> {
        val out = ArrayList<Velocity?>()
        repeat(max) {
            val v = tick(now)
            out.add(v)
            if (v == null) return out
        }
        return out
    }

    @Test
    fun idleDoesNotPublish() {
        val c = TeleopController()
        assertFalse(c.isActive)
        assertNull(c.tick())
    }

    @Test
    fun publishesWhileTouchingEveryTick() {
        val c = TeleopController()
        c.onStick(Velocity.planar(0.3, 0.1), 1000)
        repeat(5) { assertEquals(Velocity.planar(0.3, 0.1), c.tick(1000)) }
    }

    @Test
    fun releaseSendsFewZerosThenGoesQuiet() {
        val c = TeleopController(stopRepeats = 3)
        c.onStick(Velocity.planar(0.3, 0.0))
        c.tick()
        c.onRelease()
        assertEquals(listOf(Velocity.ZERO, Velocity.ZERO, Velocity.ZERO, null), c.drain())
    }

    @Test
    fun stopLatchesUntilFingerIsLifted() {
        val c = TeleopController(stopRepeats = 2)
        c.onStick(Velocity.planar(1.0, 0.0))
        c.stop(latch = true)
        c.onStick(Velocity.planar(1.0, 0.0))
        assertEquals(listOf(Velocity.ZERO, Velocity.ZERO, null), c.drain())
        c.onRelease()
        c.onStick(Velocity.planar(0.2, 0.0))
        assertEquals(Velocity.planar(0.2, 0.0), c.tick())
    }

    @Test
    fun watchdogStopsWhenUiStopsBeating() {
        val c = TeleopController(stopRepeats = 1, watchdogMs = 500)
        c.onStick(Velocity.planar(1.0, 0.0), nowMs = 1000)
        c.heartbeat(1300)
        assertEquals(Velocity.planar(1.0, 0.0), c.tick(1700))   // 400 ms since the heartbeat: OK
        assertEquals(Velocity.ZERO, c.tick(1900))                 // 600 ms: watchdog
        assertTrue(c.autoStopReason!!.contains("stopped responding"))
        c.onStick(Velocity.planar(1.0, 0.0), 2000)               // blocked like a STOP
        assertNull(c.tick(2000))
        c.onRelease(); c.reset()
        c.onStick(Velocity.planar(0.5, 0.0), 3000)
        assertEquals(Velocity.planar(0.5, 0.0), c.tick(3100))
        assertNull(c.autoStopReason)
    }

    @Test
    fun stopWithReasonIsReported() {
        val c = TeleopController()
        c.onStick(Velocity.planar(1.0, 0.0))
        c.stop(latch = true, reason = "Red perdida")
        assertEquals("Red perdida", c.autoStopReason)
        assertEquals(Velocity.ZERO, c.tick())
    }

    @Test
    fun hidingTheTabStopsAndDoesNotLatch() {
        val c = TeleopController(stopRepeats = 1)
        c.onStick(Velocity.planar(1.0, 1.0))
        c.stop(latch = false)
        assertEquals(listOf(Velocity.ZERO, null), c.drain())
        c.onStick(Velocity.planar(0.5, 0.0))
        assertEquals(Velocity.planar(0.5, 0.0), c.tick())
    }
}

class TeleopSettingsTest {

    @Test
    fun roundTripSingleAndDual() {
        val s = TeleopSettings("/robot/cmd_vel", true, 0.8, 2.0, 20, false, TeleopPreset.HOLONOMIC, AxisMapping.HOLONOMIC_SINGLE, false)
        assertEquals(s, TeleopSettings.fromMap(s.toMap()))
        val d = TeleopSettings.defaults(true).copy(preset = TeleopPreset.DRONE_MODE2, mapping = AxisMapping.DRONE_MODE2)
        assertEquals(d, TeleopSettings.fromMap(d.toMap()))
    }

    @Test
    fun customMappingPersists() {
        val custom = AxisMapping(mapOf(StickAxis.RY to AxisAssignment(AxisTarget.LZ, true)))
        val s = TeleopSettings.defaults(true).copy(preset = TeleopPreset.CUSTOM, mapping = custom)
        val back = TeleopSettings.fromMap(s.toMap())
        assertEquals(TeleopPreset.CUSTOM, back.preset)
        assertEquals(AxisAssignment(AxisTarget.LZ, true), back.mapping.of(StickAxis.RY))
    }

    @Test
    fun defaultsDependOnMode() {
        assertEquals(TeleopSettings(), TeleopSettings.fromMap(emptyMap()))
        val dual = TeleopSettings.fromMap(emptyMap(), dual = true)
        assertTrue(dual.dual)
        assertEquals(AxisMapping.DUAL_DIFFERENTIAL, dual.mapping)
        // A 2-joystick preset is not applied to a 1-joystick tab.
        assertEquals(TeleopPreset.DIFFERENTIAL, TeleopSettings.fromMap(mapOf("preset" to "DRONE_MODE2"), dual = false).preset)
        assertTrue(TeleopSettings.fromMap(emptyMap()).stopOnConnectionLoss)
    }

    @Test
    fun valuesAreClamped() {
        val s = TeleopSettings.fromMap(mapOf("maxLinear" to "99", "maxAngular" to "-3", "rateHz" to "1000", "topic" to " "))
        assertEquals(TeleopSettings.MAX_LINEAR_LIMIT, s.maxLinear, 0.0)
        assertEquals(0.0, s.maxAngular, 0.0)
        assertEquals(50, s.rateHz)
        assertEquals(TeleopSettings.DEFAULT_TOPIC, s.topic)
    }
}
