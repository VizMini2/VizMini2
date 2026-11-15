Buttons for services and actions
================================

Goal: a panel with the commands you use every day.

A service button
----------------

1. Tap **+ → Buttons**, then **Add button**.
2. Choose **Service** and tap **Browse** to pick it (for example ``/demo_robot/return_home`` in the
   demo, or ``/reset_odom`` on your robot).
3. Fill in the request fields, give it a label and a color, and save.
4. Tap it: the response appears under the button with the time it took.

An action button
----------------

Same steps choosing **Action** (for example ``/fibonacci`` with ``order: 10`` in the demo, or
``/navigate_to_pose`` with a pose on a Nav2 robot). While the goal runs the button shows the
feedback and becomes **Cancel**.

A "hold to move" button
-----------------------

Choose **Publish**, topic ``/cmd_vel``, type ``geometry_msgs/msg/Twist``, ``linear.x = 0.2`` and set
**Repeat while held** to 10 Hz. The robot moves only while you keep the button pressed: when you
release it, the button publishes the same message once with every number at zero (an option you
can turn off).

For anything risky, turn on **Ask for confirmation before sending**.

On the PC it works the same, clicking instead of tapping (**Add** in the side bar is the **+**).
