Controllers
===========

.. clip:: controllers

The ros2_control controllers of a robot, like ``ros2 control``: see them, switch them and move their
joints.

* **Manager** picks the controller manager (the ones on the network, or type its name; the default
  is ``/controller_manager``).
* Each controller shows its state (*active*, *inactive*, *unconfigured*), its type and rate, and the
  buttons its state allows: **Activate**, **Deactivate**, **Configure** and **Unload**.
  *Interfaces* lists the command and state interfaces it claims or needs.
* Activating a controller that needs the joints of an active one offers to switch both in one go
  (deactivate the other and activate this one).
* *Declared but not loaded* lists the controllers in the manager's parameters that are not loaded
  yet, with **Load**.
* **Move joints** (trajectory controllers and forward command controllers): one slider per joint,
  with the robot's limits from ``robot_description`` and its current position from
  ``joint_states``; **Send**, or *Send while dragging*. Trajectories take the time you set.
* **Hardware** lists the hardware components with their state and interfaces, and activates or
  deactivates them.

It works with the controller managers of Foxy, Humble, Jazzy and Kilted/Lyrical (their messages
differ: the version is detected on its own, or set it at the bottom). The demo robot has one too:
deactivating its ``diff_drive_controller`` stops it.
