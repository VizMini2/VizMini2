Requirements
============

Phone
-----

* Android 8.0 (API 26) or newer. Tested on Android 16.
* A GPU with OpenGL ES 3 for the 3D view (every recent phone).
* Wi-Fi on the robot's network, or a VPN / Discovery Server. With a Zenoh router or
  foxglove_bridge, any network that reaches the robot's port.

PC
--

* **Ubuntu 20.04 or newer**, x86-64 (glibc 2.31 or newer), with an OpenGL driver for the 3D view.
  On Wayland it runs through XWayland.
* Installed from the ``.deb`` (it brings everything it needs; ROS 2 doesn't have to be installed on
  the PC) or built as a ROS 2 package with ``colcon build`` (:doc:`../getting-started/install`).
* Recommended: ``ffmpeg`` (``sudo apt install ffmpeg``) for H.264/H.265 video topics and to record
  the window, and ``notify-send`` (package ``libnotify-bin``, already in most desktops) for alert
  notifications.
* Or **Windows 10 or 11**, 64-bit, installed from the ``.msi`` for your user (no administrator
  needed) or unzipped from the ``.zip``. ``ffmpeg`` is recommended there too
  (``winget install ffmpeg``). See :doc:`../getting-started/desktop`.

Robot
-----

* ROS 2 **Foxy, Humble, Jazzy, Kilted or Lyrical**.
* **Fast DDS** (``rmw_fastrtps_cpp``) or **Cyclone DDS** (``rmw_cyclonedds_cpp``): topics,
  services, actions, parameters and lifecycle, even both on the same network. Topics also work with
  other DDS implementations.
* **Zenoh** (``rmw_zenoh_cpp``): everything, through the robot's Zenoh router
  (:doc:`../config/network`).
* Any other middleware: through `foxglove_bridge
  <https://docs.foxglove.dev/docs/visualization/ros-foxglove-bridge>`_ on the robot.
* Bundled interfaces (about 470): ``action_msgs``, ``action_tutorials_interfaces``,
  ``actionlib_msgs``, ``builtin_interfaces``, ``control_msgs``, ``controller_manager_msgs``,
  ``diagnostic_msgs``, ``example_interfaces``, ``ffmpeg_image_transport_msgs``, ``foxglove_msgs``,
  ``geographic_msgs``, ``geometry_msgs``, ``lifecycle_msgs``, ``moveit_msgs``, ``nav2_msgs``,
  ``nav_msgs``, ``object_recognition_msgs``, ``octomap_msgs``, ``rcl_interfaces``,
  ``rosgraph_msgs``, ``sensor_msgs``, ``service_msgs``, ``shape_msgs``, ``slam_toolbox``,
  ``std_msgs``, ``std_srvs``, ``tf2_msgs``, ``trajectory_msgs``, ``turtlesim``,
  ``unique_identifier_msgs`` and ``visualization_msgs``. Where MoveIt (``moveit_msgs``) and
  ros2_control (``controller_manager_msgs``) messages differ between distros, the app detects the
  robot's version by itself.

Permissions (phone)
-------------------

Internet and Wi-Fi multicast (for DDS), notifications (robot alerts), a foreground service (keeping
the connection alive in the background) and screen capture (only when you record a video of the
screen).
