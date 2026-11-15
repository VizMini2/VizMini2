// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The Teleop tab joysticks (shared screen, Compose) for the tests: they are found by their
 * accessibility description ("Left joystick", "Right joystick") and touches go in through the activity's
 * window, like a finger. Several joysticks at once are several fingers of the same gesture (multitouch), so
 * both of a dual Teleop can be moved at once.
 */
class TestJoystick(private val activity: Activity, private val pointerId: Int, private val bounds: Rect) {
    val width: Int get() = bounds.width()
    val height: Int get() = bounds.height()
    val visibility: Int get() = android.view.View.VISIBLE

    /** Like view.dispatchTouchEvent with (x, y) relative to the joystick: DOWN, MOVE or UP. */
    fun touch(action: Int, x: Float, y: Float) {
        val origin = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
        val wx = bounds.left + x - origin[0]
        val wy = bounds.top + y - origin[1]
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            when (action) {
                MotionEvent.ACTION_DOWN -> { pointers[pointerId] = wx to wy; send(activity, if (pointers.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, pointerId) }
                MotionEvent.ACTION_MOVE -> { if (pointerId in pointers) { pointers[pointerId] = wx to wy; send(activity, MotionEvent.ACTION_MOVE, pointerId) } }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pointerId in pointers) {
                    send(activity, if (pointers.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, pointerId)
                    pointers.remove(pointerId)
                }
            }
        }
    }

    companion object {
        /** Fingers down (id -> position in the window) and the gesture start. Main thread only. */
        private val pointers = LinkedHashMap<Int, Pair<Float, Float>>()
        private var downTime = 0L

        private fun send(activity: Activity, action: Int, changedId: Int) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val ids = pointers.keys.toList()
            val props = ids.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
            val coords = ids.map { id -> MotionEvent.PointerCoords().apply { x = pointers[id]!!.first; y = pointers[id]!!.second; pressure = 1f; size = 1f } }.toTypedArray()
            val index = ids.indexOf(changedId)
            val fullAction = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
                action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), fullAction, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
            activity.window.decorView.dispatchTouchEvent(e)
            e.recycle()
        }

        /** The joystick with that description in the activity (the one at `index` if there are several identical ones). */
        fun find(scenario: ActivityScenario<out Activity>, description: String, pointerId: Int = 0): TestJoystick {
            val node = Ui.waitFor(description, exact = true)
            val r = Rect().also { node.getBoundsInScreen(it) }
            var a: Activity? = null
            scenario.onActivity { a = it }
            return TestJoystick(a!!, pointerId, r)
        }

        /** Left and right (the right one, if there is none, at the same spot as the left: unused). */
        fun sticks(scenario: ActivityScenario<out Activity>): Pair<TestJoystick, TestJoystick?> {
            // A new gesture: without fingers from a previous test that failed before releasing them.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { pointers.clear() }
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val left = find(scenario, ctx.getString(R.string.teleop_left_joystick), 0)
            val right = if (Ui.exists(ctx.getString(R.string.teleop_right_joystick), exact = true)) find(scenario, ctx.getString(R.string.teleop_right_joystick), 1) else null
            return left to right
        }
    }
}
