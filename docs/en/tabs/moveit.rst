MoveIt
======

.. clip:: moveit

Plan and move a robot arm with MoveIt (``move_group``), from the phone or the PC.

* It reads the robot and its planning groups from ``move_group`` (URDF and SRDF), with the named
  poses such as *ready*.
* Set the goal joint by joint with one slider each, or move the gripper in X, Y, Z and around its
  axes (MoveIt solves the joints), then **Plan**, **Execute** or **Plan & execute**, at the speed
  you choose. **Stop** cancels the motion.
* *Straight line* plans a Cartesian path of the tip instead of letting the planner choose.
* *Add obstacle* puts an obstacle between the gripper and the goal so the plan goes around it.
* The options choose the planner (OMPL, Pilz, CHOMP... whatever ``move_group`` offers), the planning
  time, the ``move_group`` namespace and the MoveIt message version (Humble, Jazzy, Kilted /
  Lyrical: detected on its own).

Add a *Trajectory* display to an RViz tab to watch the planned motion played by a ghost robot, and a
*Planning scene* display to see the collision objects.
