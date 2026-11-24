// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.teleop

import android.content.Context
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.juancams.rviz2mini.R
import com.juancams.rviz2mini.tabs.GamepadInput
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.screens.GamepadState
import com.juancams.rviz2mini.ui.screens.TeleopScreen
import com.juancams.rviz2mini.ui.theme.VizTheme

/**
 * Teleop tab (one or two joysticks): the screen shared with the desktop (ui/TeleopScreen). This holds the
 * Android-specific parts: the physical gamepad (MainActivity forwards its events), and stopping if the network is lost or
 * the gamepad disconnects while driving.
 */
class TeleopFragment : TabFragment(), GamepadInput {

    private var gamepad by mutableStateOf<GamepadState?>(null)
    private var stopRequests by mutableIntStateOf(0)
    @Volatile private var publisher: TeleopPublisher? = null
    private val stopOnLoss: Boolean get() = spec()?.settings?.get("stopOnLoss") != "false"

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            // System thread: if the fragment no longer has a context it stops anyway (with no reason).
            if (stopOnLoss) publisher?.stop(latch = true, reason = context?.getString(R.string.teleop_reason_network_lost).orEmpty())
        }
    }

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateGamepadInfo()
        override fun onInputDeviceRemoved(deviceId: Int) {
            // Gamepad disconnected while driving: stop.
            if (gamepad?.active == true) publisher?.stop(latch = true, reason = context?.getString(R.string.teleop_reason_gamepad_disconnected).orEmpty())
            updateGamepadInfo()
        }
        override fun onInputDeviceChanged(deviceId: Int) = updateGamepadInfo()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        NoParentScroll(requireContext()).also { box -> box.addView(composeView()) }

    /**
     * In a dashboard (which scrolls), dragging the joystick must not move the page: on touch the
     * parents are asked not to intercept the gesture.
     */
    private class NoParentScroll(context: Context) : android.widget.FrameLayout(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            return false
        }
    }

    private fun composeView(): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val dual = spec()?.type == TabType.TELEOP_DUAL
            val compact = spec()?.isWidget == true
            setContent {
                VizTheme {
                    TeleopScreen(tabId, dual = dual, compact = compact, gamepad = gamepad, stopRequests = stopRequests,
                        onPublisher = { publisher = it })
                }
            }
        }

    override fun onResume() {
        super.onResume()
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.registerDefaultNetworkCallback(networkCallback) } catch (_: Exception) {}
        (requireContext().getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, null)
        updateGamepadInfo()
    }

    override fun onPause() {
        super.onPause()
        // Tab hidden or app in the background: the robot stops.
        gamepad = gamepad?.copy(left = Stick.CENTER, right = Stick.CENTER)
        publisher?.stop(latch = false)
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        (requireContext().getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputListener)
    }

    // GamepadInput: called by MainActivity if this tab is the visible one.
    override fun onGamepadMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        val left = Joystick.fromGamepad(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), GAMEPAD_DEADZONE)
        val right = Joystick.fromGamepad(event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), GAMEPAD_DEADZONE)
        gamepad = (gamepad ?: GamepadState(event.device?.name ?: "Gamepad")).copy(left = left, right = right)
        return true
    }

    override fun onGamepadKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_B -> { stopRequests++; true }
            else -> false
        }
    }

    /** The name of the connected gamepad (or nothing), for the screen's help line. */
    private fun updateGamepadInfo() {
        if (view == null) return
        val pad = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }.firstOrNull { d ->
            (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        }
        gamepad = pad?.let { (gamepad ?: GamepadState(it.name)).copy(name = it.name) }
    }

    private companion object { const val GAMEPAD_DEADZONE = 0.1f }
}
