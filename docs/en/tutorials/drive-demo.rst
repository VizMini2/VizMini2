Drive the demo robot
====================

Five minutes, no robot needed. The demo runs on the phone (the PC app has no demo mode).

1. Start the demo (:doc:`../getting-started/demo`).
2. Open **Demo RViz**: the robot drives around the room by itself, with its laser, map and planned
   path.
3. Open **Drive** and push the joystick: the robot follows you and stops when you let go. Tap
   **STOP** at any time.
4. Back in **Demo RViz**, try the views from the menu (*TopDownOrtho* is great for maps) and the
   gestures: one finger rotates, two fingers pan, pinch zooms.
5. Open **Camera** to see what the robot sees.
6. Open **Buttons** and tap **Return home**: the robot jumps back to its dock. **Fibonacci 10**
   runs an action and shows its feedback.
7. Open **Lifecycle** and **Deactivate** ``/camera_driver``: the camera stops. **Activate** it
   again.

Next, change how the robot behaves: in a **Nodes** tab open ``/demo_robot`` and lower
``max_linear_speed``, or set ``autopilot`` to ``false`` so it waits for you. The **Sliders**,
**Indicators**, **Plot** and **GPS map** tabs of the demo are ready to try too.
