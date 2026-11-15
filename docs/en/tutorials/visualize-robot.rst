Visualize your robot in 3D
==========================

Goal: see your robot model, TF, laser and map in the RViz tab.

1. Connect to the robot (:doc:`../getting-started/connect`).
2. Tap **+ → RViz**, keep **Add map, laser, robot model and path displays** checked and tap
   **Create**.
3. If the robot publishes ``/robot_description``, the **RobotModel** display draws it. If the model
   uses meshes (``package://my_robot/meshes/base.stl``…), the phone downloads them over HTTP: serve
   the folder that contains your packages' ``share`` folders. For example, with a workspace built
   with ``colcon build --merge-install``, on the robot::

      cd ~/ros2_ws/install/share
      python3 -m http.server 8000

   Then write ``http://ROBOT_IP:8000`` in the RobotModel display's **Mesh server** field. The phone
   asks for ``http://ROBOT_IP:8000/my_robot/meshes/base.stl``, so check that address opens in a
   browser. Only STL and Collada (``.dae``) meshes are drawn. Marker and MarkerArray displays have
   the same field for ``MESH_RESOURCE`` markers.

   **On the PC**, if the robot's packages are installed or built on the same computer, leave the
   field empty: the meshes are read from its ROS 2 install and the workspaces in
   ``AMENT_PREFIX_PATH`` (start the app from a terminal where the workspace is sourced).
4. Missing a display? Tap **Add → By topic**: the list shows every topic that can be drawn.
5. Pick the **Fixed frame** from the picker at the top (``map`` when you navigate, ``odom``
   otherwise).

On the PC, **Add** in the side bar is the **+**. In the 3D view, drag with the left mouse button
to rotate, with the right or middle button to pan, and use the wheel to zoom.

Already have an RViz config?
----------------------------

Copy your ``.rviz`` file to the phone (on the PC, just pick it) and use the menu → **Import .rviz
file…**. The displays, frame, background, view and goal/pose topics come along; anything that
isn't supported is listed.
