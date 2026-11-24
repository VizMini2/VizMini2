Try the demo
============

.. clip:: rviz
   :caption: The demo robot in the RViz tab.

The demo starts a **simulated robot inside your phone**: a small differential robot in a
10 × 8 m room with boxes. It publishes what a real mobile robot would (TF, odometry, laser, map,
camera, also as H.264 video, diagnostics, battery, GPS, logs, its URDF and a planned path), obeys
``/cmd_vel``, and drives around on its own when nobody is using the joystick.

It also offers parameters, services, an action, a lifecycle node and a ros2_control controller
manager, so every tab has something to show:

* ``/demo_robot`` parameters: ``autopilot``, ``max_linear_speed``, ``max_angular_speed``,
  ``robot_name`` (read-only) and ``use_sim_time``.
* Services: ``/demo_robot/return_home`` (``std_srvs/Trigger``), ``/demo_robot/set_autopilot``
  (``std_srvs/SetBool``) and ``/add_two_ints``.
* Action: ``/fibonacci`` (``example_interfaces/action/Fibonacci``), with feedback and cancel.
* Lifecycle node ``/camera_driver``: the camera only publishes while it is *active*.
* ``/controller_manager`` with ``joint_state_broadcaster`` and ``diff_drive_controller`` active:
  deactivate ``diff_drive_controller`` in a Controllers tab and the robot stops.

The demo uses its own ROS domain (231) and only the device's internal (loopback) network, so
**nothing reaches your Wi-Fi** and it never interferes with real robots.

Start it
--------

On Home tap **Try demo** (or **Start demo mode** in the quick tour). Your tabs and connection are
saved and come back when you tap **Exit demo**. No account needed.

The demo adds ready-made tabs: *Demo RViz*, *Drive*, *Camera*, *Diagnostics*, *Topics*, *Buttons*,
*Sliders*, *Indicators*, *GPS map*, *Plot*, *Lifecycle* and *Demo dashboard*.

On the PC the demo is the same: *Configuration* → **Start demo**, or start the app with
``vizmini2 --demo`` (:doc:`desktop`). It runs on that computer only.

.. container:: vm-clear

   Next: :doc:`../tutorials/drive-demo`.
