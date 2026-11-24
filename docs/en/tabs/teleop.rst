Teleop
======

.. clip:: teleop

Drive the robot with an on-screen joystick, a Bluetooth or USB gamepad on the phone, or the
keyboard on the PC.

* Publishes ``geometry_msgs/Twist`` on the velocity topic chosen when you create the tab
  (``/cmd_vel`` by default) at a steady 10 Hz while you drive. Turn on **Use TwistStamped** for
  controllers that expect it (for example ``diff_drive_controller`` on Jazzy).
* The top line shows the topic, the message type and how many subscribers it has, so you can see
  whether the robot is listening, and below it the velocity being sent.
* **Settings** (gear button, not while driving): the topic, maximum linear speed (up to 3 m/s, 0.5
  by default), maximum angular speed (up to 6 rad/s, 1.0 by default), the control preset and the
  connection-loss stop.
* **Presets:** *Differential* (forward + turn), *Holonomic* (forward + strafe) or *Custom*, where
  each stick axis can drive any linear or angular axis, inverted if you like.
* **Gamepad** (phone): the sticks drive and the **B** button is STOP.

On the PC, drag the joystick with the mouse or use the keyboard: :kbd:`W`/:kbd:`S` or :kbd:`↑`/:kbd:`↓`
and :kbd:`A`/:kbd:`D` or :kbd:`←`/:kbd:`→` move the (left) joystick, and :kbd:`Space` is STOP. If
the keys stop working after you clicked the sidebar or a dialog, click anywhere in the tab: it takes
the keyboard back.

Safety
------

* **STOP** sends zero velocity and locks the joystick until you release it and touch it again.
* When you let go of the stick (or the keys), the robot gets zero velocity three times and then
  publishing stops, so it doesn't fight other ``cmd_vel`` sources such as Nav2.
* If the app stops responding for more than 0.6 s while you drive, the robot is stopped.
* **Stop if the connection is lost while driving** (on by default) stops the robot if the ROS 2
  connection drops, if the robot stops listening on the topic or, on the phone, if the network goes
  away. Unplugging the gamepad while driving also stops it. Tap the red banner to continue.
* Leaving the tab, or putting the phone app in the background, stops the robot.
