// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.teleop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.juancams.rviz2mini.R
import kotlin.math.min

/** Circular virtual joystick. Only follows the first finger that touches it. */
class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onStickChanged(stick: Stick)
        fun onStickReleased()
    }

    var listener: Listener? = null
    var deadzone = 0.08f

    /** Labels for the 4 sides (top, bottom, left, right); empty = no label. */
    var labels = arrayOf(context.getString(R.string.teleop_dir_forward), context.getString(R.string.teleop_dir_backward), "", "")
        set(value) { field = value; invalidate() }

    var stick: Stick = Stick.CENTER
        private set

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private val density = resources.displayMetrics.density

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332196F3; style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2196F3.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x552196F3; strokeWidth = 1.5f * density }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2196F3.toInt() }
    private val knobActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; textAlign = Paint.Align.CENTER; textSize = 12f * density
    }

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val knobRadius get() = min(width, height) * 0.14f
    /** Maximum travel of the stick's center. */
    private val travel get() = min(width, height) / 2f - knobRadius - 4f * density

    override fun onDraw(canvas: Canvas) {
        val r = travel + knobRadius
        canvas.drawCircle(cx, cy, r, basePaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, crossPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, crossPaint)
        // On a small joystick (dashboard widget) the labels would overlap: none.
        val showLabels = r >= labelPaint.textSize * 4.5f
        if (showLabels && labels[0].isNotEmpty()) canvas.drawText(labels[0], cx, cy - r + labelPaint.textSize * 1.5f, labelPaint)
        if (showLabels && labels[1].isNotEmpty()) canvas.drawText(labels[1], cx, cy + r - labelPaint.textSize * 0.8f, labelPaint)
        if (showLabels && labels[2].isNotEmpty()) {
            canvas.save(); canvas.rotate(-90f, cx - r + labelPaint.textSize * 1.3f, cy)
            canvas.drawText(labels[2], cx - r + labelPaint.textSize * 1.3f, cy, labelPaint); canvas.restore()
        }
        if (showLabels && labels[3].isNotEmpty()) {
            canvas.save(); canvas.rotate(90f, cx + r - labelPaint.textSize * 1.3f, cy)
            canvas.drawText(labels[3], cx + r - labelPaint.textSize * 1.3f, cy, labelPaint); canvas.restore()
        }

        val kx = cx + stick.x * travel
        val ky = cy - stick.y * travel
        canvas.drawCircle(kx, ky, knobRadius, if (activePointerId != MotionEvent.INVALID_POINTER_ID) knobActivePaint else knobPaint)
    }

    @SuppressLint("ClickableViewAccessibility") // a joystick has no "click" action
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
                update(event.getX(0), event.getY(0))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activePointerId)
                if (i >= 0) update(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) release()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        val raw = Joystick.fromTouch(x - cx, y - cy, travel)
        stick = raw
        listener?.onStickChanged(Joystick.applyDeadzone(raw, deadzone))
        invalidate()
    }

    private fun release() {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return
        activePointerId = MotionEvent.INVALID_POINTER_ID
        stick = Stick.CENTER
        listener?.onStickReleased()
        invalidate()
    }

    val isTouched: Boolean get() = activePointerId != MotionEvent.INVALID_POINTER_ID

    /** Position shown when a physical gamepad moves it (without touching the screen). */
    fun showExternal(s: Stick) {
        if (isTouched) return
        stick = s
        invalidate()
    }

    /** Called when the tab is hidden: returns the stick to the center without notifying. */
    fun resetSilently() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        stick = Stick.CENTER
        invalidate()
    }
}
