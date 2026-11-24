Teleop (2 joysticks)
====================

.. clip:: teleop_dual

Two joysticks, for holonomic robots and drones. It works like :doc:`teleop` (same message types,
speeds and safety stops) with presets for two sticks:

* **Two joysticks: forward / turn:** one stick drives, the other turns (the default).
* **Two joysticks: holonomic + turn:** one stick moves in any direction, the other turns.
* **Two joysticks: drone (mode 2):** up/down and yaw on the left stick, forward and sideways on the right, like an
  RC transmitter in mode 2 (``linear.z`` and ``angular.z`` on the left, ``linear.x`` and
  ``linear.y`` on the right).
* **Custom:** map each axis of each stick to any linear or angular axis.

With a gamepad on the phone, both physical sticks are used. On the PC the keyboard moves only the
left joystick, so drag the right one with the mouse.
