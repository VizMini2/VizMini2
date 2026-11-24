RViz
====

.. clip:: rviz

A 3D view of your robot, like RViz: TF, maps, lasers, point clouds, cameras, markers, paths, poses,
the robot model and MoveIt's planned paths, with the Nav2 tools (goal, initial pose and waypoint
routes) and a MoveIt goal you move by dragging. The phone and the PC draw the same scene with the same
displays.

Creating it
-----------

* **Fixed frame:** leave it empty to pick one automatically (``map``, ``odom``… or the root of the
  TF tree).
* **Add map, laser, robot model and path displays** (on by default): adds *Map*, *RobotModel*,
  *LaserScan* and a *Path* called *Plan* on ``/plan`` to the *Grid* and *TF* every view starts
  with, ready for a typical Nav2 robot.

Displays
--------

On the phone, displays appear as chips at the bottom, with a dot for their status. Tap a chip to
turn the display on or off and long-press it to edit or remove it. Tap **Add** to add one *by
display type* or *by topic* (the search lists every topic on the network that can be displayed).

Each display has its own QoS: *Best effort*, *Reliable* or *Transient local*, and you can also change
the depth, history, reliability and durability one by one, as in RViz 2. When a display receives
nothing, its dialog says why and, if it can, offers a fix with a button (another topic of the same
type, or the QoS the publisher uses).

On the PC, the displays are in a panel on the right, like in RViz: *Global Options* (fixed frame and
background), *Global Status* and each display with its checkbox and its properties, edited in place.
Right click a display for *Enable*/*Disable*, *Duplicate*, *Rename* and *Remove* (also at the bottom
of the panel, next to *Add*). Drag the panel's left edge to make it wider; the button at the top right hides it. In a narrow
window or a dashboard widget the chips are shown instead: click to turn a display on or off, right
click to edit it.

The **fixed frame** is picked from the frames in TF or written by hand (on the phone, *Write a frame…*
in its dialog; on the PC, type it in *Fixed Frame*), even one that does not exist yet: to leave a view
ready for a robot that is not running. Until it appears in TF the automatic one is used, and on the PC
*Global Status* says so.

.. list-table::
   :header-rows: 1
   :widths: 22 78

   * - Display
     - Main options
   * - Grid
     - Reference frame, plane (XY, XZ, YZ), cell size and count, color, alpha, line style and width,
       offset.
   * - TF
     - Show names, axes and arrows; marker scale, name size, frame timeout. The TF topics
       (``/tf, /tf_static`` or a robot's own, such as ``/robot1/tf``) and which frames to hide: a
       checkbox per frame, or patterns such as ``*wheel*``. Frame names that would cover each other
       (a small robot seen from afar) are stacked one above the other so they can all be read.
   * - Axes
     - Frame, length, radius, alpha.
   * - RobotModel
     - From ``/robot_description`` (transient local). Visual and collision geometry, alpha and an
       optional **mesh server** (see below).
   * - Map
     - ``nav_msgs/OccupancyGrid`` with *map*, *costmap* or *raw* colors, alpha and *draw behind*.
       Large maps are downsampled to fit the GPU.
   * - LaserScan, PointCloud2, PointCloud
     - Squares or circles, size, color by intensity, range, x/y/z, RGB or flat color, value
       bounds, decay time (up to 30 s). PointCloud2 shows up to 500,000 points.
   * - Odometry
     - Arrows or axes, how many to keep, position and angle tolerance.
   * - Path
     - Color, line style and width, poses as axes or arrows.
   * - Pose, PoseWithCovariance, PoseArray
     - Arrows or axes; covariance ellipse for ``PoseWithCovariance`` (e.g. ``/amcl_pose``).
   * - PointStamped, Polygon, Range
     - Clicked points, footprints and range sensor cones.
   * - Marker, MarkerArray
     - Arrows, cubes, spheres, cylinders, lines, cube/sphere lists, points, triangle lists and
       text; delete, delete all and lifetime are honored. Mesh markers (``MESH_RESOURCE``, STL or
       Collada) are downloaded from the display's **mesh server**, as in RobotModel.
   * - Image
     - A camera image in a panel over the 3D view: ``sensor_msgs/Image``, ``CompressedImage`` (topics
       ending in ``/compressed``) or H.264/H.265 video (``…/foxglove``, ``…/ffmpeg``, see
       :doc:`camera`). Corner, width and alpha. Several panels in the same corner are stacked; drag
       a panel to put it anywhere and drag its bottom-right corner (or pinch it, on the phone) to
       resize it.
   * - Camera
     - The camera image with the 3D scene drawn over it, seen from the camera itself: grid,
       markers, clouds, the robot… line up with what the camera sees. It uses the
       ``camera_info`` next to the image (``/camera/image_raw`` → ``/camera/camera_info``, or another
       one) for the projection and the TF of its frame for the pose. Corner and width; the panel is
       moved and resized like an Image panel. **Image Rendering**: image and scene, the image over
       the scene (with *Overlay Alpha*), only the image or only the scene.
   * - DepthCloud
     - A depth image (``16UC1`` in mm or ``32FC1`` in m) and its ``camera_info`` turned into a point
       cloud, colored by distance or with the colors of another image (*Color Image Topic*). Max
       range, max points, point size and style. ``…/compressedDepth`` topics (PNG, from
       compressed_depth_image_transport) work too.
   * - Effort
     - The effort of each joint in ``sensor_msgs/JointState`` as an arc around the joint's axis (from
       the URDF in ``/robot_description``): the closer to the joint's effort limit, the longer and
       redder; the arrow shows the direction. Radius and width.
   * - GridCells
     - Flat cells of ``nav_msgs/GridCells`` (obstacles, inflated areas…): color and alpha.
   * - Wrench, TwistStamped, AccelStamped
     - Two arrows at the message's frame: force and torque, or linear and angular. The angular
       part is drawn along its axis (right-hand rule). Colors, scale of each arrow and width.
   * - Temperature, Illuminance, FluidPressure, RelativeHumidity
     - A sphere at the sensor's frame, from blue (minimum) to red (maximum), with the value next to
       it.
   * - Trajectory
     - MoveIt's planned path (``moveit_msgs/DisplayTrajectory`` on ``/display_planned_path``) played
       by a translucent ghost robot, built from the URDF and placed where the real robot is. Color,
       alpha, loop and speed; it can also show the goal of the :doc:`moveit` panel.
   * - PlanningScene
     - MoveIt's collision objects (``/monitored_planning_scene``): boxes, spheres, cylinders, cones
       and meshes, also those attached to the robot. Color and alpha.
   * - NavMap
     - `EasyNavigation's NavMap <https://github.com/EasyNavigation/NavMap>`_ (``/navmap``): a navigable
       surface of triangles, on one floor or several (ramps, stairs, multi-storey car parks), colored
       by one of its layers (cost, elevation, occupancy…) with *viridis*, *heat*, *rainbow* or a *flat* color,
       blended across cells or cell by cell; unknown cells in translucent gray. The links between
       floors (stairs, elevators, doors) as arcs colored by type, and the layer updates of
       ``/navmap_layers`` (whole or only the cells that change). Its RViz plugin's settings are
       imported from ``.rviz`` files.
   * - InteractiveMarkers
     - The controls of an interactive marker server (``<namespace>/update``): their markers or, if
       a control has none, RViz's arrows and rings. Move them with the **Interact** tool.

Each message is placed with the TF at its stamp, from the last 10 s of history. If the TF clock goes
back in time (a bag playing in a loop, or a simulation restarted with ``/clock``), the history is
cleared, as in RViz, together with the points accumulated by the decay time, the *Odometry* trail
and the *PointStamped* points.

Views and gestures
------------------

* Views: **Orbit**, **XYOrbit**, **TopDownOrtho**, **FPS** and **ThirdPersonFollower** (follows a
  frame, ``base_link`` by default). *Reset view* puts the camera back.
* **Saved views** (in the menu; on the PC, at the bottom of the displays panel): save the current
  camera with a name and go back to it with a tap.
* One finger rotates, two fingers pan, pinch zooms and a two-finger twist rotates.
* The background can be dark, RViz gray, black, light gray, white or midnight blue.

On the PC, drag with the left button to rotate, with the right or middle button (or :kbd:`Shift` +
left) to pan, and use the wheel to zoom.

Tools
-----

* **Goal** publishes a 2D goal pose (``geometry_msgs/PoseStamped``) to ``/goal_pose``.
* **Pose** publishes a 2D pose estimate (``PoseWithCovarianceStamped``) to ``/initialpose``.

Tap the ground and drag to set the heading. The pose is sent in the fixed frame (or ``map``).

.. clip:: rviz_select
   :poster: rviz
   :caption: Select: tap a point of the laser to see where it is.

On the phone, the ruler button holds the other tools; on the PC, they are all in the toolbar (there
*Publish Point* is **Point** and *MoveIt goal* is **MoveIt**):

* **Publish Point** sends the point of the ground you tap (``geometry_msgs/PointStamped``) to
  ``/clicked_point``, like RViz. You can drag before lifting your finger to adjust it.
* **Measure**: drag on the ground from one point to another to see the distance and how much it
  changes in x and y. The measurement stays until the next one or until you tap the ruler again.
* **Select** works with point clouds and laser scans: tap a point to see where it is, in the fixed
  frame and in the sensor's frame, and how far it is from the sensor.
* **Interact** works with *InteractiveMarkers* displays: drag the arrows to move along an axis, a
  ring to rotate or to move in a plane, and tap buttons and menus. The server receives the same
  feedback as from RViz (``<namespace>/feedback``). Touching anywhere else moves the view as usual,
  and the tool stays on until you tap the ruler again.
* **Waypoints** builds a Nav2 route: tap the ground and drag to set each waypoint's heading. The
  tool stays on to keep adding them.
* **MoveIt goal** shows arrows and rings on the gripper goal of an open :doc:`moveit` panel (a tab
  or a widget): drag the arrows to move it and the rings to turn it, then *Plan* in the panel. If
  MoveIt finds no joints for that position, it says so.

You can change the topics of Goal, Pose and Publish Point from the menu, under *Tool topics…*. See
:doc:`../tutorials/nav2`.

Nav2 bar
--------

With waypoints on the map, a bar at the top shows how many there are and how to send them: **Stop
at each** (Nav2's ``FollowWaypoints``, optionally in a **Loop**) or **Pass through** without stopping
(``NavigateThroughPoses``). The buttons remove the last waypoint or all of them, and **Go** sends the
route (**Cancel** stops it). While it runs, the bar shows the current waypoint, the distance and time
left and the recoveries. The route is saved with the tab.

The bar also shows the progress of any ``navigate_to_pose`` goal, such as one sent with the **Goal**
tool.

Going back
----------

The **Go back** button (the clock with an arrow) replays the last minute of this view (its displays' topics and TF), without having
recorded a bag: only on this device, in a loop, with the usual time bar to pause, change the speed or
jump. Closing the bar goes back to the live robot. On the PC the view keeps that minute from the
start; on the phone the first tap (or *Keep the last minute* in the menu) turns it on, because it
uses memory.

Import and export .rviz
-----------------------

From the menu, **Import .rviz file…** brings in the displays (every type in the table, groups
included; MoveIt's *MotionPlanning* becomes a *Trajectory*), the fixed frame, the background, the
current view and the goal, pose and point topics of a config from desktop RViz. Anything it can't
use is listed as *Not supported here*. Importing replaces the tab's current configuration.
**Export .rviz…** saves the view as a file you can open with ``rviz2 -d``. See
:doc:`../config/import-export` and :doc:`../tutorials/visualize-robot`.

Image annotations
-----------------

Image and Camera have an **Annotations Topic**: boxes, circles, lines and texts drawn on the image,
from ``visualization_msgs/ImageMarker`` (they add up by namespace and id, with their lifetime, like
image_view) or from Foxglove's ``foxglove_msgs/ImageAnnotations`` (each message replaces the
previous one). The type is found on the network by itself. Coordinates are the image's pixels. On an
Image panel the eye button (or a double tap) shows or hides them; on Camera, the *Show Annotations*
option does.

Robot meshes
------------

URDF boxes, cylinders and spheres are drawn directly. Meshes (STL or Collada ``.dae``) are
downloaded over HTTP from the **mesh server** set in the RobotModel display, for example
``http://ROBOT_IP:8000``: ``package://pkg/path`` becomes ``<server>/pkg/path``. Any web server
that serves your packages' ``share`` folders works: see :doc:`../tutorials/visualize-robot`.
Without a mesh server, parts that use meshes are not drawn and the display says how many.
