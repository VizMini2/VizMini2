Known limitations
=================

* **Multicast:** many Wi-Fi networks drop it; use Discovery peers, a Discovery Server, a Zenoh
  router or foxglove_bridge (:doc:`../config/network`).
* **Services, actions, parameters and lifecycle over DDS** need Fast DDS or Cyclone DDS on the
  robot. With other middlewares, use a Zenoh router or foxglove_bridge.
* **Message types** not bundled with the app (:doc:`requirements` lists the packages) have to be
  defined by pasting their ``.msg``, ``.srv`` or ``.action`` text (:ref:`your own types <custom-types>`); the 3D
  view's displays only take the standard types.
* **Meshes** (robot model and ``MESH_RESOURCE`` markers) must be STL or Collada (``.dae``). The
  phone downloads them from a mesh server on the robot; the PC also finds them in its own ROS 2
  install or workspace.
* **Bags** are MCAP only (not ``.db3``). The app records them uncompressed; it plays uncompressed,
  LZ4 and Zstandard bags.
* The **Camera tab** subscribes with *Best effort* (it also receives from *Reliable* publishers).
  The RViz Image and Camera displays let you pick the QoS.
* The **teleop publish rate** is fixed at 10 Hz.
* **ROS 2 Lyrical:** point clouds and images can stop arriving after ``ros2 topic echo`` or
  ``ros2 topic hz`` on the robot (a bug in Lyrical's ``rmw_fastrtps_cpp``): see
  :doc:`troubleshooting`.
* **PC:** no demo mode and no gamepad (drive with the keyboard); H.264/H.265 video needs ``ffmpeg``.
