Send Nav2 goals
===============

Goal: localize a Nav2 robot and send it to a point on the map, like the *2D Pose Estimate* and
*Nav2 Goal* tools of RViz.

1. Connect to the robot and open an **RViz** tab with the fixed frame ``map`` and the Map display.
2. Tap **Pose**, then touch the robot's real position on the map and drag towards where it is
   facing. This publishes ``/initialpose``; AMCL updates the robot's position (add a
   **PoseWithCovariance** display on ``/amcl_pose`` to see it, or **PoseArray** on
   ``/particlecloud``).
3. Tap **Goal**, then touch the destination and drag to choose the final heading. This publishes
   ``/goal_pose`` and Nav2 starts moving; the **Path** display on ``/plan`` shows the route.

If your robot uses other topics (for example with a namespace), change them from the menu, under
*Tool topics…*. If Nav2's servers aren't active, open a :doc:`../tabs/lifecycle` tab to
check their state.

On the PC it works the same with the mouse: click **Pose** or **Goal**, then click on the map and
drag to set the heading.
