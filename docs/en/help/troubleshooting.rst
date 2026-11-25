Troubleshooting
===============

The robot doesn't show up
-------------------------

Home shows *Only this device is visible*:

1. **Same domain?** Config → Network → Domain ID must match the robot's ``ROS_DOMAIN_ID``.
   **Find robots**, next to it, listens on every domain and shows where there are nodes.
2. **Same network?** The phone must be on the robot's Wi-Fi (not mobile data, not a guest network
   isolated from it).
3. **Multicast blocked?** Very common on office, university, guest and mesh Wi-Fi. Write the robot's
   IP in **Discovery peers** and tap **Apply and reconnect**.
4. Run **Network check**: it shows the phone's IP, the peers in use and every participant it sees.
5. Still nothing? Ask for *IGMP snooping* and *client/AP isolation* to be turned off on the router,
   or use a **Discovery Server** on both sides.

On the PC
~~~~~~~~~

There is no Home on the PC: the status in the side bar shows *Domain N* when the node is up, and a
**Nodes** or **Topics** tab shows what it sees.

1. **Same domain?** Configuration → Network → Domain ID must match the robot's ``ROS_DOMAIN_ID``,
   or use **Find robots**. The first time, the app takes ``ROS_DOMAIN_ID``, ``ROS_STATIC_PEERS``
   and ``ROS_DISCOVERY_SERVER`` from its environment (the terminal it was started from, or on
   Windows your user's environment variables); after that, only what you set in Configuration
   counts.
2. **Firewall?** DDS uses UDP multicast and ports from 7400 upwards. On Linux, if ``ufw`` or
   another firewall is on, allow the robot's network. On Windows, allow VizMini2 when Windows
   Firewall asks; if you dismissed it, open *Windows Security* → *Firewall & network protection* →
   *Allow an app through firewall* and tick *VizMini2* for *Private* networks. If your Wi-Fi is
   marked **Public**, mark it **Private** (*Settings* → *Network & internet*). You can also add the
   robot's IP in **Discovery peers**.
3. **Several network cards** (Docker, VPN…)? Write the right IP or interface in **Allowed network
   interfaces**.
4. Check from a terminal, without the window: ``vizmini2 --check --domain N``. With the ROS 2
   package that is ``ros2 run vizmini2 vizmini2 --check --domain N``; with the ``.deb``,
   ``/opt/vizmini2/bin/vizmini2 --check --domain N``. It connects for a few seconds and prints its
   status, the nodes and the topics it sees. On Windows, ``VizMini2.exe --check`` prints nothing in
   the terminal: redirect it to a file as shown in :doc:`../getting-started/desktop` (*Command
   line*).

Topics appear but no data arrives
---------------------------------

* **QoS:** a *Reliable* subscription doesn't get data from a *Best effort* publisher. In RViz
  displays and Echo, choose *Best effort*. For latched topics like ``/map`` or
  ``/robot_description``, use *Transient local*.
* **Type unknown:** the app bundles about 470 common interfaces (:doc:`requirements`); define your
  own in the Buttons tab (:doc:`../tabs/buttons`).
* **Big messages over Wi-Fi:** point clouds and raw images are heavy. Prefer compressed images
  and downsampled clouds.
* **ROS 2 in a Docker container on the same PC** (Kilted or newer, run as *root* as usual): the
  topics show up but no data arrives, because Fast DDS sends it through shared memory that the app,
  running as your user, can't open. Start the container with
  ``-e FASTDDS_BUILTIN_TRANSPORTS=UDPv4`` (or as your user: ``--user $(id -u):$(id -g)``).
* **Video topics on the PC** (H.264/H.265, such as ``foxglove_msgs/CompressedVideo``) need
  ``ffmpeg``: ``sudo apt install ffmpeg`` on Linux, ``winget install ffmpeg`` on Windows. Without
  it the camera says so.
* **Point clouds or images stop after running** ``ros2 topic echo`` **or** ``ros2 topic hz`` **on
  the robot (ROS 2 Lyrical):** a bug in Lyrical's ``rmw_fastrtps_cpp`` makes a publisher stop
  sending topics with byte arrays (``sensor_msgs/PointCloud2``, ``Image``, ``CompressedImage``…) to
  non-ROS readers such as the app (phone and PC) once another ROS subscriber to that topic closes.
  Restart the node that publishes the topic and the data comes back. Until then, avoid
  ``echo``/``hz`` on those topics while you use the app.

Services or actions don't answer
--------------------------------

* The robot must use **Fast DDS** (``rmw_fastrtps_cpp``) or **Cyclone DDS** (``rmw_cyclonedds_cpp``):
  the app tells them apart by itself. With **Zenoh** (``rmw_zenoh_cpp``), fill in the **Zenoh
  router** in :doc:`Config → Network <../config/network>`. With any other middleware, use
  foxglove_bridge. If they still don't answer, try the **Service format** setting in Config →
  Network.
* The app waits up to the button's timeout (5 s by default). Long services need a longer timeout.

RViz shows the robot without meshes
-----------------------------------

Set up the mesh server: :doc:`../tutorials/visualize-robot`. On the PC, meshes of packages
installed on the same computer are found without one.

The robot keeps moving
----------------------

It shouldn't: releasing the joystick sends zero velocity, and STOP, losing the connection or a
frozen screen stop it too. If your robot keeps moving on its last command, configure a velocity
timeout on the robot (most controllers have one).

PC only
-------

The dock shows a generic icon or a Java name (Linux)
   The ``.deb`` adds *VizMini2* to the applications menu with its icon. With ``ros2 run``, the app
   adds that menu entry (in ``~/.local/share/applications``) the first time it starts; if the dock
   still shows the generic icon, close the app and open it again.

Wayland (Linux)
   The app runs through XWayland, which Ubuntu brings by default. If the window doesn't open in a
   Wayland session, check that XWayland is installed, or log in with an X11 (Xorg) session.

Windows says "Windows protected your PC" when installing
   The ``.msi`` isn't signed: choose *More info* → *Run anyway*.

No notifications
   On Linux, alerts use ``notify-send``: install it with ``sudo apt install libnotify-bin``. On
   Windows they come from VizMini2's icon in the system tray: check that Windows notifications are
   on for it. Check also that **Notifications on this PC** is on in the Alerts tab
   (:doc:`../tabs/alerts`).

Record video doesn't work
   Recording the window needs ``ffmpeg``: ``sudo apt install ffmpeg`` on Linux, ``winget install
   ffmpeg`` on Windows (the app also finds the one winget installs if it was already open). Or set
   the ``VIZMINI2_FFMPEG`` environment variable to the ``ffmpeg`` program.
